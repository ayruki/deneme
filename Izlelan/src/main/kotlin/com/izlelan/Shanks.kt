package com.izlelan

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

object Shanks {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"

    private fun resolveUrl(base: String, relative: String): String {
        return if (relative.startsWith("http://") || relative.startsWith("https://")) {
            relative
        } else if (relative.startsWith("/")) {
            val u = java.net.URL(base)
            "${u.protocol}://${u.authority}$relative"
        } else {
            val baseDir = base.substring(0, base.lastIndexOf('/') + 1)
            baseDir + relative
        }
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
        // Filmekseni (Shanks) only works for movies as of now
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

        // ── Step 2: Search Filmekseni ──────────────────────────────────────────
        val base = BaseUrls.get("shanks", "https://filmekseni.cc")
        val searchHeaders = mapOf(
            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
            "Accept-Language"  to "tr,en;q=0.9",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer"          to "$base/"
        )

        var searchRes = runCatching {
            app.post("$base/search/", headers = searchHeaders, data = mapOf("query" to imdbId))
        }.getOrNull() ?: return false

        var json    = runCatching { JSONObject(searchRes.text) }.getOrNull()
        var results = json?.optJSONArray("result")

        if ((results == null || results.length() == 0) && imdbId.startsWith("tt")) {
            val singleTId = imdbId.substring(1)
            searchRes = runCatching {
                app.post("$base/search/", headers = searchHeaders, data = mapOf("query" to singleTId))
            }.getOrNull() ?: return false
            json    = runCatching { JSONObject(searchRes.text) }.getOrNull()
            results = json?.optJSONArray("result")
        }

        if (results == null || results.length() == 0) return false

        val slug         = results.getJSONObject(0).getString("slug")
        val moviePageUrl = "$base/$slug/"

        // ── Step 3: Fetch Movie Page ───────────────────────────────────────────
        val browserHeaders = mapOf(
            "User-Agent"     to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept"         to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "tr,en;q=0.9",
            "Referer"        to "$base/"
        )
        val moviePageRes = runCatching { app.get(moviePageUrl, headers = browserHeaders) }.getOrNull() ?: return false

        // ── Step 4: Extract Iframe URL ─────────────────────────────────────────
        val iframeRegex = Regex("""<iframe[^>]*src=["']([^"']*(?:eksenload|eplayer)[^"']*)[^>]*>""", RegexOption.IGNORE_CASE)
        val iframeMatch = iframeRegex.find(moviePageRes.text) ?: return false
        var eksenloadUrl = iframeMatch.groupValues[1]
        if (eksenloadUrl.startsWith("//")) eksenloadUrl = "https:$eksenloadUrl"

        // ── Step 5: Extract File ID ────────────────────────────────────────────
        val fileIdRegex = Regex("""/(?:eplayer|eksenload)/([a-zA-Z0-9]+)""")
        val fileId      = fileIdRegex.find(eksenloadUrl)?.groupValues?.get(1) ?: return false

        // ── Step 6: Follow Redirects ───────────────────────────────────────────
        val iframeHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer"    to moviePageUrl
        )
        val iframePageRes = runCatching { app.get(eksenloadUrl, headers = iframeHeaders) }.getOrNull() ?: return false
        val finalUrl      = iframePageRes.url

        // ── Step 7: Resolve Domain ─────────────────────────────────────────────
        val parsedDomain = runCatching { java.net.URL(finalUrl).host }.getOrNull() ?: "eksenload.top"
        val domains      = listOf(parsedDomain, "cdn.dailymonvideo.biz", "d2.vidload.top", "d3.vidload.top").distinct()

        var m3u8Url        : String? = null
        var putperestHtml  : String? = null
        var putperestFinalUrl: String? = null

        val streamHeaders = mapOf(
            "User-Agent"     to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept"         to "*/*, text/html",
            "Accept-Language" to "tr,en;q=0.9"
        )

        for (d in domains) {
            val testUrl     = "https://$d/putperest/$fileId"
            val testHeaders = streamHeaders + mapOf("Referer" to "https://eksenload.top/")
            val dRes        = runCatching { app.get(testUrl, headers = testHeaders) }.getOrNull() ?: continue
            if (dRes.code != 200 || !dRes.text.contains(".m3u8")) continue

            val m3u8Regex    = Regex("""file:\s*['"]([^'"]+\.m3u8)['"]""", RegexOption.IGNORE_CASE)
            val relativeM3u8 = m3u8Regex.find(dRes.text)?.groupValues?.get(1) ?: continue
            val tentativeM3u8Url = resolveUrl(dRes.url, relativeM3u8)

            // Verify stream is reachable
            val checkHeaders = streamHeaders + mapOf("Range" to "bytes=0-0", "Referer" to dRes.url)
            val checkRes     = runCatching { app.get(tentativeM3u8Url, headers = checkHeaders) }.getOrNull()
            if (checkRes != null && (checkRes.code < 400 || checkRes.code == 416)) {
                m3u8Url          = tentativeM3u8Url
                putperestHtml    = dRes.text
                putperestFinalUrl = dRes.url
                break
            }
        }

        if (m3u8Url.isNullOrEmpty() || putperestFinalUrl.isNullOrEmpty()) return false

        // ── Step 8: Subtitles ──────────────────────────────────────────────────
        // Headers required by the putperest CDN to serve subtitle files
        val subHeaders = mapOf(
            "Referer"    to putperestFinalUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        fun makeSubtitleFile(label: String, url: String): SubtitleFile {
            return SubtitleFile(label, url).apply { headers = subHeaders }
        }

        val seenSubUrls = mutableSetOf<String>()

        // 8a: Parse subtitle tracks from player HTML
        if (putperestHtml != null) {
            val trackRegex  = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            val trackMatch  = trackRegex.find(putperestHtml)
            if (trackMatch != null) {
                val tracksText   = trackMatch.groupValues[1]
                val trackObjects = tracksText.split("}").filter { it.contains("file:") || it.contains("\"file\"") }
                for (track in trackObjects) {
                    val fileM  = Regex("""["']?file["']?\s*:\s*['"]([^'"]+)['"]""").find(track) ?: continue
                    val subUrl = fileM.groupValues[1]
                    if (!subUrl.endsWith(".vtt")) continue
                    val absUrl = resolveUrl(putperestFinalUrl, subUrl)
                    if (!seenSubUrls.add(absUrl)) continue          // skip duplicates
                    val labelM = Regex("""["']?label["']?\s*:\s*['"]([^'"]+)['"]""").find(track)
                    var label  = labelM?.groupValues?.get(1)?.trim() ?: ""
                    if (label.isEmpty()) {
                        label = if (subUrl.contains("tur") || subUrl.contains("tr")) "Türkçe" else "English"
                    }
                    subtitleCallback(makeSubtitleFile(label, absUrl))
                }
            }
        }

        // 8b: Fallback — try well-known subtitle paths if HTML parsing found nothing
        if (seenSubUrls.isEmpty()) {
            val m3u8Domain = runCatching { java.net.URL(m3u8Url).host }.getOrNull() ?: ""
            if (m3u8Domain.isNotEmpty()) {
                val subLangs = listOf(
                    "Türkçe"          to "${fileId}_tur.vtt",
                    "English"         to "${fileId}_eng.vtt",
                    "Türkçe (Forced)" to "${fileId}_tur_forced.vtt"
                )
                for ((label, file) in subLangs) {
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
                source = "Shanks",
                name   = "Shanks",
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
