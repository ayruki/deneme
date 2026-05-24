package com.izlelan.sources

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import org.json.JSONObject

object Shamrock {
    private const val BASE_API = "https://fembox.aether.mom"
    private const val TOKEN = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpYXQiOjE3NzcxNDUwMTcsIm5iZiI6MTc3NzE0NTAxNywiZXhwIjoxODA4MjQ5MDM3LCJkYXRhIjp7InVpZCI6MTY2Njk1MSwidG9rZW4iOiJhMGNlNjc2YjRhYmZjODk4OWUwZTU4YjFmMWMxMTU3YSJ9fQ.X6f7F-xssmzkLpiZapwLCqidsDrVqDFVLAda17nTG_E"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to UA
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
        val targetUrl = if (isMovie) {
            "$BASE_API/movie/$id?ui=$TOKEN"
        } else {
            val s = season ?: 1
            val e = episode ?: 1
            "$BASE_API/tv/$id/$s/$e?ui=$TOKEN"
        }

        val response = runCatching { app.get(targetUrl, headers = headers).text }.getOrNull()
        if (response.isNullOrBlank()) return false

        val data = runCatching { JSONObject(response) }.getOrNull() ?: return false
        val sources = data.optJSONArray("sources") ?: return false
        val subtitles = data.optJSONArray("subtitles")

        // Parse subtitles
        if (subtitles != null) {
            for (i in 0 until subtitles.length()) {
                val sub = subtitles.optJSONObject(i) ?: continue
                val lang = sub.optString("language")
                val url = sub.optString("url")
                if (lang.isNotBlank() && url.isNotBlank()) {
                    subtitleCallback(newSubtitleFile(lang, url))
                }
            }
        }

        // Loop through sources and ONLY select ORG quality
        var foundOrg = false
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val quality = src.optString("quality")
            if (quality.equals("ORG", ignoreCase = true)) {
                val url = src.optString("url")
                if (url.isNotBlank()) {
                    val isM3u8 = url.endsWith(".m3u8") || url.contains(".m3u8")
                    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    
                    callback(
                        newExtractorLink(
                            source = "Shamrock",
                            name = "Shamrock ORG",
                            url = url,
                            type = linkType
                        ) {
                            this.referer = "https://fembox.aether.mom/"
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                    foundOrg = true
                    break
                }
            }
        }

        return foundOrg
    }
}
