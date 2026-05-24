package com.izlelan.api

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app

object TheIntroDB {
    private const val API_BASE_URL = "https://api.theintrodb.org/v3"
    private const val API_KEY = "theintrodb:user_38i2tp7Rcv4rmOzRUTs3hbmGcVH:ds5gUnl12_Lywlqb_NnbZgtx8tcB5Wh8dCVfkLEOqV0"

    data class MediaResponse(
        @JsonProperty("tmdb_id") val tmdbId: Long? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("intro") val intro: List<SegmentTimestamp>? = null,
        @JsonProperty("recap") val recap: List<SegmentTimestamp>? = null,
        @JsonProperty("credits") val credits: List<SegmentTimestamp>? = null,
        @JsonProperty("preview") val preview: List<SegmentTimestamp>? = null
    )

    data class SegmentTimestamp(
        @JsonProperty("start_ms") val startMs: Long? = null,
        @JsonProperty("end_ms") val endMs: Long? = null
    )

    private fun formatMs(ms: Long): String {
        val totalSecs = ms / 1000
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        val millis = ms % 1000
        return String.format("%02d:%02d:%02d.%03d", hours, mins, secs, millis)
    }

    suspend fun fetchChapters(
        tmdbId: Int,
        imdbId: String?,
        type: String,
        season: Int?,
        episode: Int?
    ): String? {
        val idSuffix = if (imdbId != null && imdbId.startsWith("tt")) {
            "imdb_id=$imdbId"
        } else {
            "tmdb_id=$tmdbId"
        }

        val url = if (type == "movie") {
            "$API_BASE_URL/media?$idSuffix"
        } else {
            if (season != null && episode != null) {
                "$API_BASE_URL/media?$idSuffix&season=$season&episode=$episode"
            } else {
                return null
            }
        }

        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "Authorization" to "Bearer $API_KEY",
                    "Accept" to "application/json",
                    "User-Agent" to "IntroDB-Android"
                )
            )

            if (response.code == 200) {
                val parsed = response.parsedSafe<MediaResponse>()
                if (parsed != null) {
                    val vttBuilder = StringBuilder()
                    vttBuilder.append("WEBVTT\n\n")

                    // Recap (Özet)
                    parsed.recap?.firstOrNull()?.let { stamp ->
                        val startMs = stamp.startMs ?: 0L
                        val endMs = stamp.endMs ?: (startMs + 90000L)
                        vttBuilder.append("${formatMs(startMs)} --> ${formatMs(endMs)}\nRecap / Özet\n\n")
                    }

                    // Intro (Giriş)
                    parsed.intro?.firstOrNull()?.let { stamp ->
                        val startMs = stamp.startMs ?: 0L
                        val endMs = stamp.endMs ?: (startMs + 90000L)
                        vttBuilder.append("${formatMs(startMs)} --> ${formatMs(endMs)}\nIntro / Opening\n\n")
                    }

                    // Preview (Önizleme)
                    parsed.preview?.firstOrNull()?.let { stamp ->
                        val startMs = stamp.startMs ?: 0L
                        val endMs = stamp.endMs ?: (startMs + 300000L)
                        vttBuilder.append("${formatMs(startMs)} --> ${formatMs(endMs)}\nPreview\n\n")
                    }

                    // Credits / Outro (Kapanış/Jenerik)
                    parsed.credits?.firstOrNull()?.let { stamp ->
                        val startMs = stamp.startMs ?: 0L
                        val endMs = stamp.endMs ?: (startMs + 300000L)
                        vttBuilder.append("${formatMs(startMs)} --> ${formatMs(endMs)}\nOutro / Ending\n\n")
                    }

                    val vttString = vttBuilder.toString()
                    if (vttString.contains("-->")) {
                        val base64Vtt = Base64.encodeToString(
                            vttString.toByteArray(Charsets.UTF_8),
                            Base64.NO_WRAP
                        )
                        "data:text/vtt;base64,$base64Vtt"
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Throwable) {
            null
        }
    }
}
