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
                val trackObjects = tracksText.split("}").filter { it.contains("file:") || it.contains("\"file\"") }
                for (track in trackObjects) {
                    val fileRegex = Regex("""["']?file["']?\s*:\s*['"]([^'"]+)['"]""")
                    val labelRegex = Regex("""["']?label["']?\s*:\s*['"]([^'"]+)['"]""")
                    val fileM = fileRegex.find(track)
                    val labelM = labelRegex.find(track)
                    if (fileM != null) {
                        val subUrl = fileM.groupValues[1]
                        var label = labelM?.groupValues?.get(1)?.trim() ?: ""
                        if (label.isEmpty()) {
                            label = if (subUrl.contains("tur") || subUrl.contains("tr")) "Türkçe" else "English"
                        }
                        if (subUrl.endsWith(".vtt")) {
                            val absSubUrl = resolveUrl(putperestFinalUrl, subUrl)
                            val cachedSubUrl = cacheSubtitleLocally(label, absSubUrl, putperestFinalUrl)
                            if (cachedSubUrl != null) {
                                subtitles.add(newSubtitleFile(label, cachedSubUrl))
                            }
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
                val cachedSubUrl = cacheSubtitleLocally(lang["l"] ?: "Subtitle", vttUrl, putperestReferer)
                if (cachedSubUrl != null) {
                    subtitles.add(newSubtitleFile(lang["l"] ?: "Subtitle", cachedSubUrl))
                }
            }
        }

        if (!m3u8Url.isNullOrEmpty() && !putperestFinalUrl.isNullOrEmpty()) {
            var finalM3u8Url: String = m3u8Url
            val cacheDir = IzlelanPlugin.context?.cacheDir
            if (cacheDir != null) {
                val cleanedM3u8 = cleanM3u8(m3u8Url, putperestFinalUrl)
                if (cleanedM3u8 != null) {
                    val tempFile = java.io.File(cacheDir, "shanks_temp.m3u8")
                    runCatching {
                        tempFile.writeText(cleanedM3u8)
                        finalM3u8Url = tempFile.toURI().toString()
                    }
                }
            }

            callback(
                newExtractorLink(
                    source = "Shanks",
                    name = "",
                    url = finalM3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = putperestFinalUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            subtitles.forEach { subtitleCallback(it) }
            return true
        }

        return false
    }

    private suspend fun cacheSubtitleLocally(label: String, subUrl: String, referer: String): String? {
        val cacheDir = IzlelanPlugin.context?.cacheDir ?: return null
        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        val vttContent = runCatching { app.get(subUrl, headers = headers).text }.getOrNull() ?: return null
        if (vttContent.isEmpty() || !vttContent.contains("WEBVTT")) return null
        
        val tempSubFile = java.io.File(cacheDir, "shanks_sub_${label.replace(" ", "_")}.vtt")
        return runCatching {
            tempSubFile.writeText(vttContent)
            tempSubFile.toURI().toString()
        }.getOrNull()
    }

    private suspend fun cleanM3u8(m3u8Url: String, referer: String): String? {
        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        val res = runCatching { app.get(m3u8Url, headers = headers).text }.getOrNull() ?: return null
        
        val lines = res.split("\n")
        val newLines = mutableListOf<String>()
        
        var targetAudioGroupId: String? = null
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                newLines.add(line)
                continue
            }
            
            if (trimmed.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                val groupId = Regex("""GROUP-ID=["']([^"']+)["']""").find(trimmed)?.groupValues?.get(1)
                val uri = Regex("""URI=["']([^"']+)["']""").find(trimmed)?.groupValues?.get(1)
                
                if (groupId != null) {
                    if (targetAudioGroupId == null) {
                        targetAudioGroupId = groupId
                    }
                    if (groupId == targetAudioGroupId) {
                        var newLine = trimmed
                        if (uri != null && !uri.startsWith("http://") && !uri.startsWith("https://")) {
                            val absUri = resolveUrl(m3u8Url, uri)
                            newLine = trimmed.replace(uri, absUri)
                        }
                        newLines.add(newLine)
                    }
                } else {
                    newLines.add(trimmed)
                }
            } else if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                var newLine = trimmed
                if (targetAudioGroupId != null) {
                    val audioMatch = Regex("""AUDIO=["']([^"']+)["']""").find(trimmed)
                    if (audioMatch != null) {
                        val currentAudioGroup = audioMatch.groupValues[1]
                        newLine = trimmed.replace("AUDIO=\"$currentAudioGroup\"", "AUDIO=\"$targetAudioGroupId\"")
                            .replace("AUDIO='$currentAudioGroup'", "AUDIO='$targetAudioGroupId'")
                    }
                }
                newLines.add(newLine)
            } else if (trimmed.startsWith("#")) {
                newLines.add(trimmed)
            } else {
                if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    val absUrl = resolveUrl(m3u8Url, trimmed)
                    newLines.add(absUrl)
                } else {
                    newLines.add(trimmed)
                }
            }
        }
        
        return newLines.joinToString("\n")
    }
}
