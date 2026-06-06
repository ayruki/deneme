package com.izlelan.sources

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder

object Kizaru {
    private const val tmdbApiKey = "1865f43a0549ca50d341dd9ab8b29f49"
    
    private val newTvBaseHeaders = mapOf(
        "Cache-Control" to "no-cache, no-store, must-revalidate",
        "Pragma" to "no-cache",
        "Expires" to "0",
        "X-Requested-With" to "NetmirrorNewTV v1.0",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
        "Accept" to "application/json, text/plain, */*"
    )

    private val newTvDomains = listOf(
        "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==",
        "aHR0cHM6Ly9tb2JpbGVkZXRlY3QuYXBw",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNj",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5saXZl",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0cy54eXo="
    )

    private val platformMap = mapOf(
        "netflix" to "nf",
        "primevideo" to "pv",
        "hotstar" to "hs",
        "disney" to "hs"
    )

    private var resolvedApiUrl: String = ""

    private fun safeAtob(encoded: String): String {
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.ISO_8859_1).trim()
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun resolveApiUrl(): String {
        if (resolvedApiUrl.isNotEmpty()) return resolvedApiUrl

        for (encoded in newTvDomains) {
            val base = safeAtob(encoded).removeSuffix("/")
            if (base.isEmpty()) continue
            val checkUrl = "$base/checknewtv.php"
            val headers = newTvBaseHeaders.toMutableMap().apply {
                put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            try {
                val response = app.get(checkUrl, headers = headers)
                if (response.code == 200) {
                    val json = JSONObject(response.text)
                    val tokenHash = json.optString("token_hash")
                    if (tokenHash.isNotEmpty()) {
                        resolvedApiUrl = safeAtob(tokenHash).removeSuffix("/")
                        return resolvedApiUrl
                    }
                }
            } catch (e: Exception) {
                // Try next domain
            }
        }
        
        if (resolvedApiUrl.isNotEmpty()) return resolvedApiUrl
        throw Exception("Failed to resolve NewTV API base URL")
    }

    private fun buildNewTvHeaders(ott: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        return newTvBaseHeaders.toMutableMap().apply {
            put("Ott", ott)
            putAll(extra)
        }
    }

    private suspend fun fetchEpisodesPage(
        seasonId: String,
        page: Int,
        seasonNumber: Int?,
        ott: String,
        apiBase: String
    ): List<EpisodeData> {
        val episodes = mutableListOf<EpisodeData>()
        var pg = page
        while (true) {
            val url = "$apiBase/newtv/episodes.php?id=$seasonId&page=$pg"
            try {
                val resp = app.get(url, headers = buildNewTvHeaders(ott))
                if (resp.code != 200) break
                
                val json = JSONObject(resp.text)
                val rawEpisodes = json.optJSONArray("episodes")
                if (rawEpisodes != null) {
                    for (i in 0 until rawEpisodes.length()) {
                        val ep = rawEpisodes.optJSONObject(i) ?: continue
                        
                        val epNumStr = ep.optString("ep")
                        val epNum = if (epNumStr.isNotEmpty()) {
                            epNumStr.toIntOrNull()
                        } else {
                            val epNumStr2 = ep.optString("epNum")
                            epNumStr2.replace("E", "").toIntOrNull()
                        }
                        
                        val sNumStr = ep.optString("sNum")
                        val sNum = seasonNumber ?: sNumStr.replace("S", "").toIntOrNull()
                        
                        val id = ep.optString("id")
                        if (id.isNotEmpty() && epNum != null && sNum != null) {
                            episodes.add(EpisodeData(id, sNum, epNum))
                        }
                    }
                }
                if (json.optInt("nextPageShow") != 1) break
                pg++
            } catch (e: Exception) {
                break
            }
        }
        return episodes
    }

    private suspend fun getAllEpisodes(
        postData: JSONObject,
        ott: String,
        apiBase: String
    ): List<EpisodeData> {
        val episodes = mutableListOf<EpisodeData>()
        
        val seasons = postData.optJSONArray("season")
        var selectedSeasonIdx = -1
        if (seasons != null) {
            for (i in 0 until seasons.length()) {
                val s = seasons.optJSONObject(i) ?: continue
                if (s.optBoolean("selected")) {
                    selectedSeasonIdx = i
                    break
                }
            }
        }
        
        val selectedSeasonId = if (selectedSeasonIdx >= 0) {
            seasons?.optJSONObject(selectedSeasonIdx)?.optString("id")
        } else {
            postData.optString("nextPageSeason")
        }
        val selectedSeasonNumber = if (selectedSeasonIdx >= 0) selectedSeasonIdx + 1 else null
        
        val rawEpisodes = postData.optJSONArray("episodes")
        if (rawEpisodes != null) {
            for (i in 0 until rawEpisodes.length()) {
                val ep = rawEpisodes.optJSONObject(i) ?: continue
                val epNumStr = ep.optString("ep")
                val epNum = if (epNumStr.isNotEmpty()) {
                    epNumStr.toIntOrNull()
                } else {
                    val epNumStr2 = ep.optString("epNum")
                    epNumStr2.replace("E", "").toIntOrNull()
                }
                
                val sNumStr = ep.optString("sNum")
                val sNum = selectedSeasonNumber ?: sNumStr.replace("S", "").toIntOrNull()
                
                val id = ep.optString("id")
                if (id.isNotEmpty() && epNum != null && sNum != null) {
                    episodes.add(EpisodeData(id, sNum, epNum))
                }
            }
        }
        
        if (postData.optInt("nextPageShow") == 1 && !selectedSeasonId.isNullOrEmpty()) {
            val more = fetchEpisodesPage(selectedSeasonId, 2, selectedSeasonNumber, ott, apiBase)
            episodes.addAll(more)
        }
        
        if (seasons != null) {
            for (i in 0 until seasons.length()) {
                val season = seasons.optJSONObject(i) ?: continue
                val sId = season.optString("id")
                if (sId != selectedSeasonId && sId.isNotEmpty()) {
                    val more = fetchEpisodesPage(sId, 1, i + 1, ott, apiBase)
                    episodes.addAll(more)
                }
            }
        }
        
        return episodes
    }

    private suspend fun fetchFromPlatform(
        platformKey: String,
        ott: String,
        title: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiBase = runCatching { resolveApiUrl() }.getOrNull() ?: return false
        val searchUrl = "$apiBase/newtv/search.php?s=${URLEncoder.encode(title, "UTF-8")}"
        
        val searchResp = runCatching { app.get(searchUrl, headers = buildNewTvHeaders(ott)) }.getOrNull() ?: return false
        if (searchResp.code != 200) return false
        
        val searchData = JSONObject(searchResp.text)
        val searchResults = searchData.optJSONArray("searchResult")
        if (searchResults == null || searchResults.length() == 0) return false
        
        val result = searchResults.optJSONObject(0) ?: return false
        val contentId = result.optString("id")
        if (contentId.isEmpty()) return false
        
        val postUrl = "$apiBase/newtv/post.php?id=$contentId"
        val postResp = runCatching {
            app.get(postUrl, headers = buildNewTvHeaders(ott, mapOf("Lastep" to "", "Usertoken" to "")))
        }.getOrNull() ?: return false
        if (postResp.code != 200) return false
        val postData = JSONObject(postResp.text)
        
        var targetId = contentId
        val isTv = mediaType.equals("tv", ignoreCase = true)
        
        if (isTv) {
            if (season == null || episode == null) return false
            val episodes = getAllEpisodes(postData, ott, apiBase)
            val targetEp = episodes.firstOrNull { it.s == season && it.ep == episode } ?: return false
            targetId = targetEp.id
        } else {
            val hasEpisodes = postData.optJSONArray("episodes")?.let { it.length() > 0 } ?: false
            val isSeries = postData.optString("type") == "t" || hasEpisodes
            if (isSeries) return false
            
            val mainId = postData.optString("main_id")
            if (mainId.isNotEmpty()) {
                targetId = mainId
            }
        }
        
        val playerUrl = "$apiBase/newtv/player.php?id=$targetId"
        val playerResp = runCatching {
            app.get(playerUrl, headers = buildNewTvHeaders(ott, mapOf("Usertoken" to "")))
        }.getOrNull() ?: return false
        if (playerResp.code != 200) return false
        
        val response = JSONObject(playerResp.text)
        if (response.optString("status") == "ok" && response.optString("video_link").isNotEmpty()) {
            val videoLink = response.optString("video_link")
            val referer = response.optString("referer").ifEmpty { apiBase }
            
            // Subtitles extracting
            val subtitles = response.optJSONArray("subtitles")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val sub = subtitles.optJSONObject(i) ?: continue
                    val subUrl = sub.optString("url")
                    val subLang = sub.optString("language").ifEmpty { sub.optString("name") }
                    if (subUrl.isNotEmpty() && subLang.isNotEmpty()) {
                        subtitleCallback(newSubtitleFile(subLang, subUrl))
                    }
                }
            }
            
            val playlist = response.optJSONObject("playlist")
            val tracks = playlist?.optJSONArray("tracks")
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(i) ?: continue
                    val kind = track.optString("kind")
                    if (kind == "captions" || kind == "subtitles") {
                        val subUrl = track.optString("file")
                        val subLang = track.optString("label").ifEmpty { track.optString("language") }
                        if (subUrl.isNotEmpty() && subLang.isNotEmpty()) {
                            subtitleCallback(newSubtitleFile(subLang, subUrl))
                        }
                    }
                }
            }
            
            callback(
                newExtractorLink(
                    source = "🇬🇧 Kizaru",
                    name = "🇬🇧 Kizaru (${platformKey.replaceFirstChar { it.uppercase() }})",
                    url = videoLink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = referer
                    this.headers = mapOf("Referer" to referer)
                }
            )
            return true
        }
        return false
    }

    suspend fun invoke(
        id: Int,
        type: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbType = if (type.equals("tv", ignoreCase = true)) "tv" else "movie"
        val tmdbUrl = "https://api.themoviedb.org/3/$tmdbType/$id?api_key=$tmdbApiKey"
        
        val tmdbResp = runCatching { app.get(tmdbUrl) }.getOrNull() ?: return false
        if (tmdbResp.code != 200) return false
        val tmdbData = JSONObject(tmdbResp.text)
        
        val title = if (tmdbType == "tv") tmdbData.optString("name") else tmdbData.optString("title")
        if (title.isEmpty()) return false
        
        for ((platformKey, ott) in platformMap) {
            val success = fetchFromPlatform(platformKey, ott, title, type, season, episode, subtitleCallback, callback)
            if (success) return true
        }
        
        return false
    }

    private data class EpisodeData(
        val id: String,
        val s: Int,
        val ep: Int
    )
}
