package com.izlelan.sources

import com.izlelan.IzlelanProvider
import com.izlelan.BaseUrls

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

object Loki {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"
    private val mainUrl = BaseUrls.get("dizifilm", "https://dizifilm.to")
    private const val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/"
    )

    private suspend fun getImdbId(id: Int, imdbIdParam: String?, type: String): String? {
        if (!imdbIdParam.isNullOrBlank()) return imdbIdParam
        val extUrl = "https://api.themoviedb.org/3/$type/$id/external_ids?api_key=$tmdbApiKey"
        val extRes = runCatching { app.get(extUrl).parsedSafe<IzlelanProvider.ExternalIds>() }.getOrNull()
        return extRes?.imdb_id
    }

    private fun e(c: Int, a: Int): String {
        val mod = c % a
        val suffix = if (mod > 35) (mod + 29).toChar().toString() else mod.toString(36)
        return (if (c < a) "" else e(c / a, a)) + suffix
    }

    private fun unpackJs(packed: String): String {
        val match = Regex("""\}\('([^']*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']*)'\.split\('\|'\)""").find(packed)
            ?: return packed
        val (p, aStr, cStr, kStr) = match.destructured
        val a = aStr.toIntOrNull() ?: return packed
        val c = cStr.toIntOrNull() ?: return packed
        val k = kStr.split("|")

        val d = mutableMapOf<String, String>()
        for (i in k.indices) {
            val key = e(i, a)
            d[key] = if (k[i].isNotEmpty()) k[i] else key
        }

        return Regex("""\b\w+\b""").replace(p) { m ->
            d[m.value] ?: m.value
        }.replace("\\'", "'").replace("\\\"", "\"")
    }

    private suspend fun extractVidlopSubtitles(videoId: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val embedRes = runCatching {
            app.get(
                "https://vidlop.com/video/$videoId",
                headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
            )
        }.getOrNull() ?: return

        if (embedRes.code != 200) return
        val html = embedRes.text

        val scripts = Regex("""eval\(function\(p,a,c,k,e,d\).*?\.split\(['"]\|['"]\),0,\{\}\)\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)

        val seenUrls = mutableSetOf<String>()

        for (scriptMatch in scripts) {
            val unpacked = runCatching { unpackJs(scriptMatch.value) }.getOrNull() ?: continue
            
            // Standard track parsing
            val trackRegex = Regex("""\{[^{}]+?"kind"\s*:\s*"captions"[^{}]+?\}""")
            trackRegex.findAll(unpacked).forEach { match ->
                val trackText = match.value
                val file = Regex(""""file"\s*:\s*"([^"]+)"""").find(trackText)?.groupValues?.getOrNull(1)
                var label = Regex(""""label"\s*:\s*"([^"]+)"""").find(trackText)?.groupValues?.getOrNull(1) ?: "Subtitle"
                if (!file.isNullOrBlank()) {
                    val url = file.replace("\\/", "/")
                    if ((url.endsWith(".jpg") || url.endsWith(".vtt")) && seenUrls.add(url)) {
                        if (label.equals("undefined", ignoreCase = true)) label = "Türkçe (forced)"
                        subtitleCallback(SubtitleFile(label, url).apply {
                            this.headers = mapOf("Referer" to "https://vidlop.com/", "User-Agent" to userAgent)
                        })
                    }
                }
            }

            // Fallback track parsing
            val fallbackMatches = Regex(""""file"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"""").findAll(unpacked)
            for (tm in fallbackMatches) {
                val url = tm.groupValues[1].replace("\\/", "/")
                var label = tm.groupValues[2]
                if ((url.endsWith(".jpg") || url.endsWith(".vtt")) && seenUrls.add(url)) {
                    if (label.equals("undefined", ignoreCase = true)) label = "Türkçe (forced)"
                    subtitleCallback(SubtitleFile(label, url).apply {
                        this.headers = mapOf("Referer" to "https://vidlop.com/", "User-Agent" to userAgent)
                    })
                }
            }
        }
    }

    private suspend fun extractVidlopStream(embedUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val videoId = embedUrl.substringAfterLast("/").trim()
        if (videoId.isBlank()) return false

        // Fetch subtitles
        extractVidlopSubtitles(videoId, subtitleCallback)

        // Fetch video stream
        val vDataRes = runCatching {
            app.post(
                "https://vidlop.com/player/index.php?data=$videoId&do=getVideo",
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "User-Agent" to userAgent,
                    "Referer" to "https://vidlop.com/video/$videoId",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                data = mapOf(
                    "data" to videoId,
                    "do" to "getVideo",
                    "hash" to videoId,
                    "r" to "$mainUrl/"
                )
            )
        }.getOrNull() ?: return false

        if (vDataRes.code != 200) return false
        val json = runCatching { JSONObject(vDataRes.text) }.getOrNull() ?: return false
        val hlsUrl = json.optString("securedLink").ifBlank { json.optString("videoSource") }
        if (hlsUrl.isNullOrBlank()) return false

        callback(
            newExtractorLink(
                source = "Loki",
                name = "Loki",
                url = hlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://vidlop.com/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Referer" to "https://vidlop.com/", "User-Agent" to userAgent)
            }
        )
        return true
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
        val tmdbType = if (type == "movie") "movie" else "tv"
        val imdbId = getImdbId(id, imdbIdParam, tmdbType) ?: return false

        // 1. Search content
        val searchUrl = "$mainUrl/api/search?q=$imdbId&type=all&limit=10"
        val searchRes = runCatching {
            app.get(searchUrl, headers = headers)
        }.getOrNull() ?: return false

        if (searchRes.code != 200) return false
        val searchJson = runCatching { JSONObject(searchRes.text) }.getOrNull() ?: return false
        if (!searchJson.optBoolean("success")) return false
        val results = searchJson.optJSONArray("results") ?: return false
        if (results.length() == 0) return false

        val matched = results.optJSONObject(0) ?: return false
        val slug = matched.optString("slug")
        val contentType = matched.optString("content_type")
        if (slug.isBlank()) return false

        var embedUrl = ""

        if (contentType == "series" && type != "movie" && season != null && episode != null) {
            val detailsUrl = "$mainUrl/api/series/$slug/details"
            val detailsRes = runCatching {
                app.get(detailsUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/dizi/$slug"))
            }.getOrNull() ?: return false

            if (detailsRes.code != 200) return false
            val detailsJson = runCatching { JSONObject(detailsRes.text) }.getOrNull() ?: return false
            if (!detailsJson.optBoolean("success")) return false
            val data = detailsJson.optJSONObject("data") ?: return false
            val seasons = data.optJSONArray("seasonsWithEpisodes") ?: return false

            var targetEpisode: JSONObject? = null
            for (i in 0 until seasons.length()) {
                val s = seasons.optJSONObject(i) ?: continue
                if (s.optInt("season_number") == season) {
                    val episodes = s.optJSONArray("episodes") ?: continue
                    for (j in 0 until episodes.length()) {
                        val ep = episodes.optJSONObject(j) ?: continue
                        if (ep.optInt("episode_number") == episode) {
                            targetEpisode = ep
                            break
                        }
                    }
                    break
                }
            }

            if (targetEpisode == null) return false
            embedUrl = targetEpisode.optString("embed_player_url_1")
        } else if (contentType == "movie" && type == "movie") {
            val movieUrl = "$mainUrl/film/$slug"
            val moviePageRes = runCatching {
                app.get(movieUrl, headers = headers)
            }.getOrNull() ?: return false

            if (moviePageRes.code != 200) return false
            val html = moviePageRes.text

            val vidId = Regex("""vidlop\.com(?:\\/|/)video(?:\\/|/)([a-f0-9]+)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)

            if (!vidId.isNullOrBlank()) {
                embedUrl = "https://vidlop.com/video/$vidId"
            }
        }

        if (embedUrl.isBlank()) return false

        return if (embedUrl.contains("vidlop.com")) {
            extractVidlopStream(embedUrl, subtitleCallback, callback)
        } else {
            runCatching {
                loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            }.getOrDefault(false)
        }
    }
}
