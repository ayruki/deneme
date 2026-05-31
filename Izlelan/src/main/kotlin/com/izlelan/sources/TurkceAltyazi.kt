package com.izlelan.sources

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile

object TurkceAltyazi {
    private const val workerUrl = "https://turkcealtyazi-worker.ayruki.workers.dev"

    data class SubtitleItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("season") val season: Any? = null,
        @JsonProperty("episode") val episode: Any? = null,
        @JsonProperty("translator") val translator: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("downloadLink") val downloadLink: String? = null
    )

    suspend fun invoke(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val targetId = tmdbId ?: return false

        val url = if (season != null && episode != null) {
            "$workerUrl/$targetId/$season/$episode"
        } else {
            "$workerUrl/$targetId"
        }

        try {
            val response = app.get(url)
            if (response.code != 200) return false

            val subs = response.parsedSafe<Array<SubtitleItem>>() ?: return false
            if (subs.isEmpty()) return false

            var foundAny = false
            for (sub in subs) {
                val dlUrl = sub.downloadLink
                if (dlUrl.isNullOrBlank()) continue

                val translator = sub.translator?.trim() ?: ""
                val quality = sub.quality?.trim() ?: ""
                
                // Beautiful label with Turkish flag
                val label = listOfNotNull(
                    "🇹🇷 TR",
                    quality.takeIf { it.isNotBlank() },
                    translator.takeIf { it.isNotBlank() }
                ).joinToString(" - ")

                subtitleCallback(
                    newSubtitleFile(
                        label,
                        dlUrl
                    )
                )
                foundAny = true
            }
            return foundAny
        } catch (e: Exception) {
            // Silently fail
        }

        return false
    }
}
