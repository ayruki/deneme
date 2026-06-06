package com.izlelan.sources

import com.izlelan.IzlelanProvider
import com.izlelan.BaseUrls

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64

object Shanks {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"

    private fun resolveUrl(base: String, relative: String): String {
        return when {
            relative.startsWith("http://") || relative.startsWith("https://") -> relative
            relative.startsWith("//") -> "https:$relative"
            relative.startsWith("/") -> {
                val u = java.net.URL(base)
                "${u.protocol}://${u.authority}$relative"
            }
            else -> base.substring(0, base.lastIndexOf('/') + 1) + relative
        }
    }

    /**
     * filmekseni.nl artık SPA (Livewire). Player verileri HTML'de
     * data-video-player attribute içinde JSON olarak gömülü geliyor.
     * Template'ler Base64 ile kodlanmış iframe HTML'i içeriyor.
     *
     * Örnek:
     *   "template": "PGlmcmFtZSBjbGFzcz0...base64..." → decode → <iframe data-src="//eksenload.top/eplayer/{url}">
     *   "link": "nspbi1ahvdp97zseyz8n"  → fileId
     */
    private fun parsePlayerData(html: String): JSONObject? {
        // x-data="videoPlayerData(JSON.parse('...'), ..."
        val match = Regex(
            """data-video-player[^>]*x-data="videoPlayerData\(JSON\.parse\('(.*?)'\)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(html) ?: return null

        val raw = match.groupValues[1]
            .replace("\\u0022", "\"")
            .replace("\\u003e", ">")
            .replace("\\u003c", "<")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\\\", "\\")

        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /** Base64 decode + template doldur → eksenload URL */
    private fun buildEksenloadUrl(source: JSONObject): String? {
        val templateB64 = source.optString("template").takeIf { it.isNotEmpty() } ?: return null
        val link        = source.optString("link").takeIf { it.isNotEmpty() } ?: return null
        val slug        = source.optString("slug", "")

        val decoded = runCatching {
            String(Base64.decode(templateB64, Base64.DEFAULT))
        }.getOrNull() ?: return null

        val iframeHtml = decoded.replace("{url}", link).replace("{slug}", slug)

        // data-src veya src'den URL al
        val srcMatch = Regex("""(?:data-src|src)="([^"]+)"""").find(iframeHtml) ?: return null
        var url = srcMatch.groupValues[1]
        if (url.startsWith("//")) url = "https:$url"
        return url
    }

    suspend fun invoke(
        id: Int,
        type: String,
        imdbIdParam: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (type != "movie") return false

        // ── Step 1: IMDB ID ────────────────────────────────────────────────────
        val imdbId = if (!imdbIdParam.isNullOrEmpty()) {
            imdbIdParam
        } else {
            val extUrl = "https://api.themoviedb.org/3/movie/$id/external_ids?api_key=$tmdbApiKey"
            val extRes = runCatching { app.get(extUrl).parsedSafe<IzlelanProvider.ExternalIds>() }.getOrNull()
            extRes?.imdb_id
        }
        if (imdbId.isNullOrEmpty()) return false

        // ── Step 2: Search filmekseni.nl (GET /api/search?q=) ─────────────────
        val base = BaseUrls.get("shanks", "https://filmekseni.nl")
        val searchHeaders = mapOf(
            "User-Agent"     to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept"         to "application/json",
            "Accept-Language" to "tr,en;q=0.9",
            "Referer"        to "$base/"
        )

        val searchRes = runCatching {
            app.get("$base/api/search?q=${imdbId}", headers = searchHeaders)
        }.getOrNull() ?: return false

        val searchJson = runCatching { JSONObject(searchRes.text) }.getOrNull() ?: return false
        val dataArr    = searchJson.optJSONArray("data") ?: return false
        if (dataArr.length() == 0) return false

        val firstResult = dataArr.getJSONObject(0)
        val slug        = firstResult.getString("slug")
        val movieTitle  = firstResult.optString("title", "")
        val moviePageUrl = "$base/$slug"

        // ── Step 3: Fetch Movie Page ───────────────────────────────────────────
        val browserHeaders = mapOf(
            "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr,en;q=0.9",
            "Referer"         to "$base/"
        )
        val moviePageRes = runCatching { app.get(moviePageUrl, headers = browserHeaders) }.getOrNull() ?: return false

        // ── Step 4: Parse data-video-player JSON → eksenload URL ───────────────
        val playerData = parsePlayerData(moviePageRes.text) ?: return false

        // "dual" veya "sub" veya "tr" array'ini bul
        val sources: JSONArray = playerData.optJSONArray("dual")
            ?: playerData.optJSONArray("sub")
            ?: playerData.optJSONArray("tr")
            ?: return false

        // "vip" (eksenload) kaynağını tercih et, yoksa ilkini al
        var vipSource: JSONObject? = null
        for (i in 0 until sources.length()) {
            val s = sources.getJSONObject(i)
            if (s.optString("service_slug") == "vip") { vipSource = s; break }
        }
        if (vipSource == null && sources.length() > 0) vipSource = sources.getJSONObject(0)
        vipSource ?: return false

        var eksenloadUrl = buildEksenloadUrl(vipSource) ?: return false

        // ── Step 5: Extract File ID ────────────────────────────────────────────
        val fileId = Regex("""/(?:eplayer|eksenload)/([a-zA-Z0-9]+)""")
            .find(eksenloadUrl)?.groupValues?.get(1) ?: return false

        // ── Step 6: Follow Redirects ───────────────────────────────────────────
        val iframeHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer"    to moviePageUrl
        )
        val iframePageRes = runCatching { app.get(eksenloadUrl, headers = iframeHeaders) }.getOrNull() ?: return false
        val finalUrl      = iframePageRes.url

        // ── Step 7: Try CDN Domains ────────────────────────────────────────────
        val parsedDomain = runCatching { java.net.URL(finalUrl).host }.getOrNull() ?: "eksenload.top"
        val domains      = listOf(parsedDomain, "cdn.dailymonvideo.biz", "d2.vidload.top", "d3.vidload.top").distinct()

        var m3u8Url          : String? = null
        var putperestHtml    : String? = null
        var putperestFinalUrl: String? = null

        val streamHeaders = mapOf(
            "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept"          to "*/*, text/html",
            "Accept-Language" to "tr,en;q=0.9"
        )

        for (d in domains) {
            val testUrl     = "https://$d/putperest/$fileId"
            val testHeaders = streamHeaders + mapOf("Referer" to "https://eksenload.top/")
            val dRes        = runCatching { app.get(testUrl, headers = testHeaders) }.getOrNull() ?: continue
            if (dRes.code != 200 || !dRes.text.contains(".m3u8")) continue

            val relativeM3u8 = Regex("""file:\s*['"]([^'"]+\.m3u8)['"]""", RegexOption.IGNORE_CASE)
                .find(dRes.text)?.groupValues?.get(1) ?: continue
            val tentativeM3u8Url = resolveUrl(dRes.url, relativeM3u8)

            val checkHeaders = streamHeaders + mapOf("Range" to "bytes=0-0", "Referer" to dRes.url)
            val checkRes     = runCatching { app.get(tentativeM3u8Url, headers = checkHeaders) }.getOrNull()
            if (checkRes != null && (checkRes.code < 400 || checkRes.code == 416)) {
                m3u8Url           = tentativeM3u8Url
                putperestHtml     = dRes.text
                putperestFinalUrl = dRes.url
                break
            }
        }

        if (m3u8Url.isNullOrEmpty() || putperestFinalUrl.isNullOrEmpty()) return false

        // ── Step 8: Subtitles ──────────────────────────────────────────────────
        val subHeaders = mapOf(
            "Referer"    to putperestFinalUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        fun makeSubtitleFile(label: String, url: String): SubtitleFile {
            return SubtitleFile(label, url).apply { headers = subHeaders }
        }

        val seenSubUrls = mutableSetOf<String>()

        // 8a: tracks[] HTML'inden parse et
        val trackMatch = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(putperestHtml ?: "")
        if (trackMatch != null) {
            trackMatch.groupValues[1].split("}").filter { it.contains("file:") || it.contains("\"file\"") }
                .forEach { track ->
                    val subUrl = Regex("""["']?file["']?\s*:\s*['"]([^'"]+)['"]""").find(track)?.groupValues?.get(1) ?: return@forEach
                    if (!subUrl.endsWith(".vtt")) return@forEach
                    val absUrl = resolveUrl(putperestFinalUrl, subUrl)
                    if (!seenSubUrls.add(absUrl)) return@forEach
                    var label = Regex("""["']?label["']?\s*:\s*['"]([^'"]+)['"]""").find(track)?.groupValues?.get(1)?.trim() ?: ""
                    if (label.isEmpty()) label = if (subUrl.contains("tur") || subUrl.contains("tr")) "Türkçe" else "English"
                    subtitleCallback(makeSubtitleFile(label, absUrl))
                }
        }

        // 8b: Fallback bilinen path'ler
        if (seenSubUrls.isEmpty()) {
            val m3u8Domain = runCatching { java.net.URL(m3u8Url).host }.getOrNull() ?: ""
            if (m3u8Domain.isNotEmpty()) {
                listOf(
                    "Türkçe"          to "${fileId}_tur.vtt",
                    "English"         to "${fileId}_eng.vtt",
                    "Türkçe (Forced)" to "${fileId}_tur_forced.vtt"
                ).forEach { (label, file) ->
                    val vttUrl   = "https://$m3u8Domain/uploads/encode/$fileId/$file"
                    val checkRes = runCatching { app.get(vttUrl, headers = subHeaders) }.getOrNull()
                    if (checkRes != null && checkRes.code == 200 && checkRes.text.contains("WEBVTT")) {
                        subtitleCallback(makeSubtitleFile(label, vttUrl))
                    }
                }
            }
        }

        // ── Step 9: Deliver M3U8 stream ────────────────────────────────────────
        callback(
            newExtractorLink(
                source = "🇹🇷 Shanks",
                name   = "🇹🇷 Shanks",
                url    = m3u8Url,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = putperestFinalUrl
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
