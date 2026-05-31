package com.izlelan.sources

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import org.json.JSONObject

object TurkceAltyazi {
    suspend fun invoke(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        if (imdbId.isNullOrBlank()) return false

        val subsUrls = listOf(
            "https://opensubtitles.stremio.homes/en|hi|de|ar|tr|es|ta|te|ru|ko/ai-translated=true|from=all|auto-adjustment=true",
            "https://subsense.nepiraw.com/n0tcjfba-%7B%22languages%22%3A%5B%22en%22%2C%22hi%22%2C%22ta%22%2C%22es%22%2C%22ar%22%2C%22tr%22%5D%2C%22maxSubtitles%22%3A10%7D"
        )

        var foundAny = false

        for (baseUrl in subsUrls) {
            try {
                val url = if (season != null && episode != null) {
                    "$baseUrl/subtitles/series/$imdbId:$season:$episode.json"
                } else {
                    "$baseUrl/subtitles/movie/$imdbId.json"
                }

                val res = app.get(url, timeout = 5000L)
                if (res.code != 200) continue

                val json = JSONObject(res.text)
                val subtitlesArray = json.optJSONArray("subtitles") ?: continue

                for (i in 0 until subtitlesArray.length()) {
                    val sub = subtitlesArray.optJSONObject(i) ?: continue
                    val subUrl = sub.optString("url")
                    val lang = sub.optString("lang").ifBlank { sub.optString("lang_code") }

                    if (subUrl.isNotBlank() && (lang.contains("tr", ignoreCase = true) || lang.contains("tur", ignoreCase = true))) {
                        // Check if it is AI-translated
                        val isAi = subUrl.contains("ai-translated=true", ignoreCase = true) || sub.optString("id").contains("ai", ignoreCase = true)
                        val suffix = if (isAi) " (Yapay Zeka)" else ""
                        val label = "Türkçe$suffix"
                        
                        subtitleCallback(
                            newSubtitleFile(
                                label,
                                subUrl
                            )
                        )
                        foundAny = true
                    }
                }
            } catch (e: Exception) {
                // Silently ignore individual API source failures
            }
        }

        return foundAny
    }
}
