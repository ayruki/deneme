package com.izlelan.sources

import com.izlelan.IzlelanProvider
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import org.json.JSONObject

object Chopper {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"
    private const val DEC_API = "https://enc-dec.app/api/dec-videasy"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "Accept" to "*/*",
        "Origin" to "https://cineby.sc",
        "Referer" to "https://cineby.sc/",
        "User-Agent" to UA
    )

    private fun cleanEncode(str: String): String {
        return java.net.URLEncoder.encode(str, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    private suspend fun getImdbId(id: Int, type: String): String? {
        val url = "https://api.themoviedb.org/3/$type/$id/external_ids?api_key=$tmdbApiKey"
        return runCatching {
            app.get(url).parsedSafe<IzlelanProvider.ExternalIds>()?.imdb_id
        }.getOrNull()
    }

    private suspend fun getTmdbMeta(id: Int, type: String): Pair<String, String>? {
        val url = "https://api.themoviedb.org/3/$type/$id?api_key=$tmdbApiKey&language=en-US"
        return runCatching {
            val json = JSONObject(app.get(url).text)
            val title = json.optString("title").ifBlank { json.optString("name") }
            val releaseDate = json.optString("release_date").ifBlank { json.optString("first_air_date") }
            val year = releaseDate.split("-").firstOrNull().orEmpty()
            Pair(title, year)
        }.getOrNull()
    }

    suspend fun invoke(
        id: Int,
        type: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbType = if (type == "movie") "movie" else "tv"
        val meta = getTmdbMeta(id, tmdbType) ?: return false
        val title = meta.first
        val year = meta.second
        if (title.isBlank()) return false

        val imdbId = getImdbId(id, tmdbType).orEmpty()

        val encTitle = cleanEncode(cleanEncode(title))
        val isMovie = type.equals("movie", ignoreCase = true)

        val servers = listOf("mb-flix", "cdn")
        var foundAny = false

        for (server in servers) {
            val url = if (isMovie) {
                "https://api.videasy.net/$server/sources-with-title?title=$encTitle&mediaType=movie&year=$year&tmdbId=$id&imdbId=$imdbId"
            } else {
                val s = season ?: 1
                val e = episode ?: 1
                "https://api.videasy.net/$server/sources-with-title?title=$encTitle&mediaType=tv&year=$year&episodeId=$e&seasonId=$s&tmdbId=$id&imdbId=$imdbId"
            }

            val encData = runCatching {
                app.get(url, headers = headers).text
            }.getOrNull()

            if (encData.isNullOrBlank()) continue

            val decPostPayload = mapOf("text" to encData, "id" to id.toString())
            val decResponse = runCatching {
                app.post(
                    DEC_API,
                    headers = headers,
                    json = decPostPayload
                ).text
            }.getOrNull()

            if (decResponse.isNullOrBlank()) continue

            val decJson = runCatching { JSONObject(decResponse) }.getOrNull() ?: continue
            if (decJson.optInt("status") != 200) continue

            val result = decJson.optJSONObject("result") ?: continue
            val sources = result.optJSONArray("sources") ?: continue
            val subtitles = result.optJSONArray("subtitles")

            // Parse and trigger subtitles
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val sub = subtitles.optJSONObject(i) ?: continue
                    val subUrl = sub.optString("url")
                    val subLang = sub.optString("language")
                    if (subUrl.isNotBlank() && subLang.isNotBlank()) {
                        subtitleCallback(newSubtitleFile(subLang, subUrl))
                    }
                }
            }

            // Parse and trigger sources
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val streamUrl = src.optString("url")
                val quality = src.optString("quality")
                if (streamUrl.isNotBlank()) {
                    val qVal = when (quality.lowercase().trim()) {
                        "1080p" -> Qualities.P1080.value
                        "720p" -> Qualities.P720.value
                        "480p" -> Qualities.P480.value
                        "360p" -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }

                    val sName = server.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                    val displayName = "Chopper - $sName"
                    callback(
                        newExtractorLink(
                            source = displayName,
                            name = displayName,
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://cineby.sc/"
                            this.quality = qVal
                            this.headers = mapOf("Referer" to "https://cineby.sc/")
                        }
                    )
                    foundAny = true
                }
            }
        }

        return foundAny
    }
}
