package com.izlelan.sources

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

object Rayleigh {
    private const val VIXSRC_BASE = "https://vixsrc.to"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private const val SEC_CH_UA = "\"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not-A.Brand\";v=\"99\""

    private val baseHeaders = mapOf(
        "User-Agent" to UA,
        "sec-ch-ua" to SEC_CH_UA,
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\""
    )

    suspend fun invoke(
        id: Int,
        type: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isMovie = type.equals("movie", ignoreCase = true)
        val apiUrl = if (isMovie) {
            "$VIXSRC_BASE/api/movie/$id"
        } else {
            val s = season ?: 1
            val e = episode ?: 1
            "$VIXSRC_BASE/api/tv/$id/$s/$e"
        }

        val apiHeaders = baseHeaders.toMutableMap().apply {
            put("Accept", "application/json, text/javascript, */*; q=0.01")
            put("X-Requested-With", "XMLHttpRequest")
            put("Referer", VIXSRC_BASE)
        }

        val apiResponse = runCatching {
            app.get(apiUrl, headers = apiHeaders).text
        }.getOrNull() ?: return false

        val apiData = runCatching { org.json.JSONObject(apiResponse) }.getOrNull() ?: return false
        val embedPath = apiData.optString("src").ifBlank { null } ?: return false
        val embedUrl = if (embedPath.startsWith("http")) embedPath else "$VIXSRC_BASE$embedPath"

        val embedHeaders = baseHeaders.toMutableMap().apply {
            put("Referer", VIXSRC_BASE)
        }

        val html = runCatching {
            app.get(embedUrl, headers = embedHeaders).text
        }.getOrNull() ?: return false

        var videoId = Regex("window\\.video\\s*=\\s*\\{[^}]*id:\\s*['\"]([^'\"]+)['\"]").find(html)?.groupValues?.getOrNull(1)
        if (videoId.isNullOrEmpty()) {
            videoId = Regex("/embed/([^?/?]+)").find(embedPath)?.groupValues?.getOrNull(1)
        }
        if (videoId.isNullOrEmpty()) return false

        val token = Regex("['\"]token['\"]:\\s*['\"]([^'\"]+)['\"]").find(html)?.groupValues?.getOrNull(1) ?: return false
        val expires = Regex("['\"]expires['\"]:\\s*['\"]([^'\"]+)['\"]").find(html)?.groupValues?.getOrNull(1) ?: return false
        val canPlayFHD = html.contains("canPlayFHD\\s*=\\s*true".toRegex())

        var lang = "en"
        if (embedPath.contains("lang=")) {
            val langMatch = Regex("lang=([^&]+)").find(embedPath)
            if (langMatch != null) {
                lang = langMatch.groupValues[1]
            }
        }

        var params = "token=$token&expires=$expires&lang=$lang"
        if (canPlayFHD) {
            params += "&h=1"
        }

        val queryStr = embedPath.substringAfter('?', "")
        if (queryStr.isNotEmpty()) {
            queryStr.split('&').forEach { p ->
                val parts = p.split('=', limit = 2)
                if (parts.size == 2) {
                    val k = parts[0]
                    val v = parts[1]
                    if (k != "token" && k != "expires" && k != "lang" && k != "skin" && k != "canPlayFHD") {
                        params += "&$k=$v"
                    }
                }
            }
        }

        val encodedEmbedPath = java.net.URLEncoder.encode(embedPath, "UTF-8")
        params += "&_ep=$encodedEmbedPath"

        val playlistUrl = "$VIXSRC_BASE/playlist/$videoId?$params"

        val playlistHeaders = baseHeaders.toMutableMap().apply {
            put("Referer", embedUrl)
            put("Origin", VIXSRC_BASE)
        }

        callback(
            newExtractorLink(
                source = "Rayleigh",
                name = "Rayleigh",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
                this.headers = playlistHeaders
            }
        )

        return true
    }
}
