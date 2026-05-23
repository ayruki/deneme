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

        val imdbId = if (!imdbIdParam.isNullOrEmpty()) {
            imdbIdParam
        } else {
            val extUrl = "https://api.themoviedb.org/3/movie/$id/external_ids?api_key=$tmdbApiKey"
            val extRes = runCatching { app.get(extUrl).parsedSafe<IzlelanProvider.ExternalIds>() }.getOrNull()
            extRes?.imdb_id
        }

        if (imdbId.isNullOrEmpty()) return false

        // Step 1: Search Filmekseni
        val searchHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Accept-Language" to "tr,en;q=0.9",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://filmekseni.cc/"
        )

        var searchRes = runCatching {
            app.post(
                "https://filmekseni.cc/search/",
                headers = searchHeaders,
                data = mapOf("query" to imdbId)
            )
        }.getOrNull() ?: return false

        var json = runCatching { JSONObject(searchRes.text) }.getOrNull()
        var results = json?.optJSONArray("result")

        if ((results == null || results.length() == 0) && imdbId.startsWith("tt")) {
            val singleTId = imdbId.substring(1)
            searchRes = runCatching {
                app.post(
                    "https://filmekseni.cc/search/",
                    headers = searchHeaders,
                    data = mapOf("query" to singleTId)
                )
            }.getOrNull() ?: return false
            json = runCatching { JSONObject(searchRes.text) }.getOrNull()
            results = json?.optJSONArray("result")
        }

        if (results == null || results.length() == 0) return false

        val firstResult = results.getJSONObject(0)
        val slug = firstResult.getString("slug")
        val moviePageUrl = "https://filmekseni.cc/$slug/"

        // Step 2: Fetch Movie Page
        val browserHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "tr,en;q=0.9",
            "Referer" to "https://filmekseni.cc/"
        )

        val moviePageRes = runCatching { app.get(moviePageUrl, headers = browserHeaders) }.getOrNull() ?: return false

        // Step 3: Extract Iframe Url
        val iframeRegex = Regex("""<iframe[^>]*src=["']([^"']*(?:eksenload|eplayer)[^"']*)["'][^>]*>""", RegexOption.IGNORE_CASE)
        val iframeMatch = iframeRegex.find(moviePageRes.text) ?: return false
        var eksenloadUrl = iframeMatch.groupValues[1]
        if (eksenloadUrl.startsWith("//")) {
            eksenloadUrl = "https:" + eksenloadUrl
        }

        // Step 4: Extract File ID
        val fileIdRegex = Regex("""/(?:eplayer|eksenload)/([a-zA-Z0-9]+)""")
        val fileIdMatch = fileIdRegex.find(eksenloadUrl) ?: return false
        val fileId = fileIdMatch.groupValues[1]

        // Step 5: Follow Redirects
        val iframeHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to moviePageUrl
        )

        val iframePageRes = runCatching { app.get(eksenloadUrl, headers = iframeHeaders) }.getOrNull() ?: return false
        val finalUrl = iframePageRes.url

        // Step 6: Resolve Domain
        val parsedDomain = runCatching { java.net.URL(finalUrl).host }.getOrNull() ?: "eksenload.top"
        val domains = listOf(parsedDomain, "cdn.dailymonvideo.biz", "d2.vidload.top", "d3.vidload.top").distinct()

        var m3u8Url: String? = null
        var putperestHtml: String? = null
        var putperestFinalUrl: String? = null

        val streamHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "*/*, text/html",
            "Accept-Language" to "tr,en;q=0.9"
        )

        for (d in domains) {
            val testUrl = "https://$d/putperest/$fileId"
            val testHeaders = streamHeaders + mapOf("Referer" to "https://eksenload.top/")
            val dRes = runCatching { app.get(testUrl, headers = testHeaders) }.getOrNull() ?: continue
            if (dRes.code != 200) continue
            if (!dRes.text.contains(".m3u8")) continue

            val m3u8Regex = Regex("""file:\s*['"]([^'"]+\.m3u8)['"]""", RegexOption.IGNORE_CASE)
            val m3u8Match = m3u8Regex.find(dRes.text) ?: continue
            val relativeM3u8 = m3u8Match.groupValues[1]
            val tentativeM3u8Url = resolveUrl(dRes.url, relativeM3u8)

            // Step 7: Verify M3u8 Stream
            val checkHeaders = streamHeaders + mapOf("Range" to "bytes=0-0", "Referer" to dRes.url)
            val checkRes = runCatching { app.get(tentativeM3u8Url, headers = checkHeaders) }.getOrNull()
            if (checkRes != null && (checkRes.code < 400 || checkRes.code == 416)) {
                m3u8Url = tentativeM3u8Url
                putperestHtml = dRes.text
                putperestFinalUrl = dRes.url
                break
            }
        }

        // Step 8: Parse Subtitles
        val subtitles = mutableListOf<SubtitleFile>()
        if (putperestHtml != null && putperestFinalUrl != null) {
            val trackRegex = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            val trackMatch = trackRegex.find(putperestHtml)
            if (trackMatch != null) {
                val tracksText = trackMatch.groupValues[1]
                val trackObjects = tracksText.split("}").filter { it.contains("file:") }
                for (track in trackObjects) {
                    val fileRegex = Regex("""file:\s*['"]([^'"]+)['"]""")
                    val labelRegex = Regex("""label:\s*['"]([^'"]+)['"]""")
                    val fileM = fileRegex.find(track)
                    val labelM = labelRegex.find(track)
                    if (fileM != null && labelM != null) {
                        val subUrl = fileM.groupValues[1]
                        val label = labelM.groupValues[1]
                        if (subUrl.endsWith(".vtt")) {
                            val absSubUrl = resolveUrl(putperestFinalUrl, subUrl)
                            subtitles.add(newSubtitleFile(label, absSubUrl))
                        }
                    }
                }
            }
        }

        // Fallback Subtitles
        if (subtitles.isEmpty() && !m3u8Url.isNullOrEmpty()) {
            val m3u8Domain = java.net.URL(m3u8Url).host
            val putperestReferer = "https://$m3u8Domain/putperest/$fileId"
            val subLangs = listOf(
                mapOf("l" to "Türkçe", "s" to "tur"),
                mapOf("l" to "English", "s" to "eng"),
                mapOf("l" to "Türkçe (Forced)", "s" to "tur_forced")
            )
            for (lang in subLangs) {
                val vttUrl = "https://$m3u8Domain/uploads/encode/$fileId/${fileId}_${lang["s"]}.vtt"
                val vttHeaders = streamHeaders + mapOf("Referer" to putperestReferer, "Range" to "bytes=0-0")
                val vttRes = runCatching { app.get(vttUrl, headers = vttHeaders) }.getOrNull()
                if (vttRes != null && (vttRes.code < 400 || vttRes.code == 416)) {
                    subtitles.add(newSubtitleFile(lang["l"] ?: "Subtitle", vttUrl))
                }
            }
        }

        if (!m3u8Url.isNullOrEmpty() && !putperestFinalUrl.isNullOrEmpty()) {
            callback(
                newExtractorLink(
                    source = "Shanks",
                    name = "Shanks (HLS)",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = putperestFinalUrl
                    this.quality = Qualities.P1080.value
                }
            )
            subtitles.forEach { subtitleCallback(it) }
            return true
        }

        return false
    }
}
