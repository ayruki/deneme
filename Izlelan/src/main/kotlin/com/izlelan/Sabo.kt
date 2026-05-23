package com.izlelan

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object Sabo {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"
    private val mainUrl = BaseUrls.get("sabo", "https://cinemacity.cc")

    // Cookie Kotlin kodundaki gibi base64 decode edildi:
    // ZGxlX3VzZXJfaWQ9MzI3Mjk7IGRsZV9wYXNzd29yZD04OTQxNzFjNmE4ZGFiMThlZTU5NGQ1YzY1MjAwOWEzNTs=
    private val headers = mapOf(
        "Cookie" to base64Decode("ZGxlX3VzZXJfaWQ9MzI3Mjk7IGRsZV9wYXNzd29yZD04OTQxNzFjNmE4ZGFiMThlZTU5NGQ1YzY1MjAwOWEzNTs=")
    )

    private val cfKiller = CloudflareKiller()

    private suspend fun getImdbId(id: Int, type: String): String? {
        val url = "https://api.themoviedb.org/3/$type/$id/external_ids?api_key=$tmdbApiKey"
        return runCatching {
            app.get(url).parsedSafe<IzlelanProvider.ExternalIds>()?.imdb_id
        }.getOrNull()
    }

    private suspend fun getTmdbTitle(id: Int, type: String): String? {
        val url = "https://api.themoviedb.org/3/$type/$id?api_key=$tmdbApiKey&language=en-US"
        return runCatching {
            val json = JSONObject(app.get(url).text)
            json.optString("title").ifBlank { json.optString("name") }
        }.getOrNull()?.ifBlank { null }
    }

    private suspend fun search(query: String): List<String> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?do=search&subaction=search&search_start=0&full_search=0&result_from=1&story=$encoded"
        val doc = runCatching {
            app.get(url, headers = headers, interceptor = cfKiller).document
        }.getOrNull() ?: return emptyList()

        return doc.select("div.dar-short_item").mapNotNull { el ->
            el.children().firstOrNull { it.tagName() == "a" }?.attr("href")?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }
        }
    }

    private data class ParsedPage(
        val imdbId: String?,
        val episodes: List<EpEntry>,
        val movieStreamUrl: String?,
        val movieSubtitles: JSONArray
    )

    private data class EpEntry(
        val season: Int,
        val episode: Int,
        val streamUrl: String,
        val subtitles: JSONArray
    )

    private fun parseSubtitleArray(raw: String?): JSONArray {
        val arr = JSONArray()
        if (raw.isNullOrBlank()) return arr
        Regex("""\[([^\]]+)](https?://[^\s,]+)""").findAll(raw).forEach { m ->
            arr.put(JSONObject().apply {
                put("language", m.groupValues[1].trim())
                put("subtitleUrl", m.groupValues[2].trim())
            })
        }
        return arr
    }

    private suspend fun parsePage(url: String): ParsedPage? {
        val page = runCatching {
            app.get(url, headers = headers, interceptor = cfKiller)
        }.getOrNull() ?: return null

        if (page.code != 200) return null
        val doc = page.document
        val html = page.text
        val isTV = url.contains("/tv-series/", ignoreCase = true)

        // Extract IMDB
        val imdbId = Regex("tt\\d{7,}").find(html)?.value

        // Extract PlayerJS: Kotlin kodunda getOrNull(1) kullanılıyor
        val scriptData = doc.select("script:containsData(atob)").getOrNull(1)?.data()
            ?: doc.select("script:containsData(atob)").getOrNull(0)?.data()
            ?: return ParsedPage(imdbId, emptyList(), null, JSONArray())

        val b64 = Regex("""atob\s*\(\s*"([^"]+)"\s*\)""").find(scriptData)?.groupValues?.getOrNull(1)
            ?: return ParsedPage(imdbId, emptyList(), null, JSONArray())

        val decoded = runCatching { base64Decode(b64) }.getOrNull()
            ?: return ParsedPage(imdbId, emptyList(), null, JSONArray())

        if (!decoded.contains("Playerjs", ignoreCase = true))
            return ParsedPage(imdbId, emptyList(), null, JSONArray())

        // Extract JSON from new Playerjs({...})
        val pjsContent = Regex("""new\s+Playerjs\s*\(\s*(\{[\s\S]*?})\s*\)\s*;""").find(decoded)
            ?.groupValues?.getOrNull(1)
            ?: return ParsedPage(imdbId, emptyList(), null, JSONArray())

        val playerJson = runCatching { JSONObject(pjsContent) }.getOrNull()
            ?: return ParsedPage(imdbId, emptyList(), null, JSONArray())

        val rawFile = playerJson.opt("file")
            ?: return ParsedPage(imdbId, emptyList(), null, JSONArray())

        val fileArray: JSONArray = when (rawFile) {
            is JSONArray -> rawFile
            is String -> {
                val v = rawFile.trim().replace("\\/", "/")
                when {
                    v.startsWith("[") -> runCatching { JSONArray(v) }.getOrDefault(JSONArray())
                    v.startsWith("{") -> JSONArray().apply { put(JSONObject(v)) }
                    v.isNotBlank() -> {
                        // Direct stream URL for movie
                        val subs = parseSubtitleArray(
                            (playerJson.opt("subtitle") as? String)
                        )
                        return ParsedPage(imdbId, emptyList(), v, subs)
                    }
                    else -> return ParsedPage(imdbId, emptyList(), null, JSONArray())
                }
            }
            else -> return ParsedPage(imdbId, emptyList(), null, JSONArray())
        }

        if (!isTV) {
            // Movie: first item in array
            val item = fileArray.optJSONObject(0)
            val streamUrl = item?.optString("file")?.ifBlank { null }?.replace("\\/", "/")
            val subs = parseSubtitleArray(item?.optString("subtitle"))
            return ParsedPage(imdbId, emptyList(), streamUrl, subs)
        }

        // TV Series: parse seasons/episodes
        val episodes = mutableListOf<EpEntry>()
        val seasonRegex = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val episodeRegex = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)

        for (i in 0 until fileArray.length()) {
            val seasonObj = fileArray.optJSONObject(i) ?: continue
            val sNum = seasonRegex.find(seasonObj.optString("title"))
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            val epArray = seasonObj.optJSONArray("folder") ?: continue

            for (j in 0 until epArray.length()) {
                val epObj = epArray.optJSONObject(j) ?: continue
                val eNum = episodeRegex.find(epObj.optString("title"))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                val stream = epObj.optString("file").ifBlank { null }?.replace("\\/", "/") ?: continue
                val subs = parseSubtitleArray(epObj.optString("subtitle"))
                episodes.add(EpEntry(sNum, eNum, stream, subs))
            }
        }

        return ParsedPage(imdbId, episodes, null, JSONArray())
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
        val imdbId = imdbIdParam?.ifBlank { null }
            ?: getImdbId(id, tmdbType)
            ?: return false

        // 1. Search by IMDB ID first, then title as fallback
        var searchResults = search(imdbId)
        if (searchResults.isEmpty()) {
            val title = getTmdbTitle(id, tmdbType) ?: return false
            searchResults = search(title)
        }
        if (searchResults.isEmpty()) return false

        // 2. Find matching page by IMDB ID
        var matched: ParsedPage? = null
        for (url in searchResults.take(5)) {
            val parsed = parsePage(url) ?: continue
            if (parsed.imdbId == imdbId) {
                matched = parsed
                break
            }
        }

        if (matched == null) return false

        // 3. Extract stream
        if (type == "movie") {
            val streamUrl = matched.movieStreamUrl ?: return false
            matched.movieSubtitles.let { subs ->
                for (i in 0 until subs.length()) {
                    val s = subs.optJSONObject(i) ?: continue
                    val lang = s.optString("language")
                    val subUrl = s.optString("subtitleUrl")
                    if (lang.isNotBlank() && subUrl.isNotBlank()) {
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    }
                }
            }
            callback(
                newExtractorLink(
                    source = "Sabo",
                    name = "Sabo",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        } else {
            if (season == null || episode == null) return false
            val target = matched.episodes.firstOrNull { it.season == season && it.episode == episode }
                ?: return false

            for (i in 0 until target.subtitles.length()) {
                val s = target.subtitles.optJSONObject(i) ?: continue
                val lang = s.optString("language")
                val subUrl = s.optString("subtitleUrl")
                if (lang.isNotBlank() && subUrl.isNotBlank()) {
                    subtitleCallback(newSubtitleFile(lang, subUrl))
                }
            }
            callback(
                newExtractorLink(
                    source = "Sabo",
                    name = "Sabo",
                    url = target.streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        }
    }
}
