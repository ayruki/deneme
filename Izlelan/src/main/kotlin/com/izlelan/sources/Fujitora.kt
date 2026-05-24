package com.izlelan.sources

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

object Fujitora {
    private const val ANIMECIX_BASE = "https://animecix.tv"
    private val ANIMECIX_HEADERS = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en,tr;q=0.9",
        "Cookie" to "theme=Dark; null_cookie_notice=1; connect.sid=s%3AK0whNI19qAb709lNrGtAF-S2rtQ7dVIg.K8N2WvHTIUS%2FUEByzSMJ0VVCbtRcWHX6zCfdQ2p4Zbo",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "x-e-h" to "j1stzlcwDgXT9tI0aHTBsxwdzIrlwd4vKobLjbI2Naax99OELIaH.s1SoKBwGcJ5EX2R2",
        "Referer" to "https://animecix.tv/",
        "Origin" to "https://animecix.tv"
    )

    private const val TMDB_API_KEY = "a2f888b27315e62e471b2d587048f32e"

    // JSON classes
    data class TmdbDetails(val title: String?, val name: String?, val original_title: String?, val original_name: String?)
    data class AnimecixSearchResponse(val results: List<AnimecixResult>?)
    data class AnimecixResult(val id: Int, val name: String, val tmdb_id: Int?)
    data class AnimecixTitleResponse(val title: AnimecixTitle?)
    data class AnimecixTitle(val videos: List<AnimecixVideo>?)
    data class AnimecixVideo(val name: String, val url: String, val episode_num: Int?)
    data class TauVideoResponse(val urls: List<TauVideoUrl>?)
    data class TauVideoUrl(val label: String, val url: String)

    data class EpisodeGroupResult(val id: String, val name: String?, val type: Int)
    data class EpisodeGroupResponse(val results: List<EpisodeGroupResult>?)
    data class EpisodeItem(val season_number: Int, val episode_number: Int)
    data class GroupDetail(val name: String?, val order: Int?, val episodes: List<EpisodeItem>?)
    data class GroupDetailResponse(val groups: List<GroupDetail>?)
    data class SeasonEpisode(val season: Int, val episode: Int)

    private suspend fun getEpisodeGroupMapping(tvId: Int): Map<Int, SeasonEpisode>? {
        val groupsRes = runCatching { 
            app.get("https://api.themoviedb.org/3/tv/$tvId/episode_groups?api_key=$TMDB_API_KEY")
                .parsedSafe<EpisodeGroupResponse>()
        }.getOrNull() ?: return null

        val seasonsGroup = groupsRes.results?.find { it.type == 1 || (it.name?.contains("seasons", ignoreCase = true) == true) }
            ?: return null

        val groupId = seasonsGroup.id
        val detailRes = runCatching {
            app.get("https://api.themoviedb.org/3/tv/episode_group/$groupId?api_key=$TMDB_API_KEY")
                .parsedSafe<GroupDetailResponse>()
        }.getOrNull() ?: return null

        val groups = detailRes.groups ?: return null
        val mapping = mutableMapOf<Int, SeasonEpisode>()
        var absoluteEp = 1
        val seasonEpisodesCount = mutableMapOf<Int, Int>()

        val sortedGroups = groups.sortedBy { it.order ?: 0 }
        
        sortedGroups.forEachIndexed { index, group ->
            val name = group.name ?: ""
            val seasonMatch = Regex("""Season\s+(\d+)""", RegexOption.IGNORE_CASE).find(name)
            val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: (if ((group.order ?: 0) > 0) group.order!! else (index + 1))

            if (seasonNum == 0 || name.contains("special", ignoreCase = true)) return@forEachIndexed

            val episodes = group.episodes ?: emptyList()
            episodes.forEach { ep ->
                if (ep.season_number == 0) return@forEach
                val count = (seasonEpisodesCount[seasonNum] ?: 0) + 1
                seasonEpisodesCount[seasonNum] = count
                mapping[absoluteEp] = SeasonEpisode(seasonNum, count)
                absoluteEp++
            }
        }
        return mapping
    }

    suspend fun invoke(
        id: Int,
        type: String, // "movie" or "tv"
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isSeries = (type != "movie")
        
        // 1. Get TMDB Details to find titles
        val tmdbUrl = if (isSeries) {
            "https://api.themoviedb.org/3/tv/$id?api_key=$TMDB_API_KEY&language=tr-TR"
        } else {
            "https://api.themoviedb.org/3/movie/$id?api_key=$TMDB_API_KEY&language=tr-TR"
        }
        
        val tmdbRes = runCatching { app.get(tmdbUrl).parsedSafe<TmdbDetails>() }.getOrNull()
        val terms = mutableListOf<String>()
        tmdbRes?.let {
            it.name?.let { t -> terms.add(t) }
            it.title?.let { t -> terms.add(t) }
            it.original_name?.let { t -> terms.add(t) }
            it.original_title?.let { t -> terms.add(t) }
        }

        var foundId: Int? = null

        // Search by terms
        for (term in terms.distinct()) {
            val searchUrl = "$ANIMECIX_BASE/secure/search/${java.net.URLEncoder.encode(term, "UTF-8")}?limit=20"
            val res = runCatching { app.get(searchUrl, headers = ANIMECIX_HEADERS).parsedSafe<AnimecixSearchResponse>() }.getOrNull()
            val match = res?.results?.find { it.tmdb_id == id }
            if (match != null) {
                foundId = match.id
                break
            }
        }

        // Fallback search by ID
        if (foundId == null) {
            val searchUrl = "$ANIMECIX_BASE/secure/search/$id?limit=20"
            val res = runCatching { app.get(searchUrl, headers = ANIMECIX_HEADERS).parsedSafe<AnimecixSearchResponse>() }.getOrNull()
            val match = res?.results?.find { it.tmdb_id == id }
            if (match != null) {
                foundId = match.id
            }
        }

        if (foundId == null) return false

        var targetSeason = season ?: 1
        val targetEp = episode ?: 1

        suspend fun fetchVideos(sNum: Int, epNum: Int): List<AnimecixVideo> {
            val detailsUrl = if (isSeries) {
                "$ANIMECIX_BASE/secure/titles/$foundId?seasonNumber=$sNum"
            } else {
                "$ANIMECIX_BASE/secure/titles/$foundId?titleId=$foundId"
            }
            val res = runCatching { app.get(detailsUrl, headers = ANIMECIX_HEADERS).parsedSafe<AnimecixTitleResponse>() }.getOrNull()
            val vList = res?.title?.videos ?: emptyList()
            return if (isSeries) vList.filter { it.episode_num == epNum } else vList
        }

        var filteredVideos = fetchVideos(targetSeason, targetEp)

        // Fallback for seasonal mapping
        if (isSeries && filteredVideos.isEmpty()) {
            val mapping = getEpisodeGroupMapping(id)
            if (mapping != null && mapping.containsKey(targetEp)) {
                val mapped = mapping[targetEp]!!
                val mappedVideos = fetchVideos(mapped.season, mapped.episode)
                if (mappedVideos.isNotEmpty()) {
                    filteredVideos = mappedVideos
                    targetSeason = mapped.season
                    // targetEp remains absolute
                }
            }
        }

        // Final Fallback: Try Season 1 if targetSeason != 1 and not found
        if (isSeries && filteredVideos.isEmpty() && targetSeason != 1) {
            filteredVideos = fetchVideos(1, targetEp)
        }

        if (filteredVideos.isEmpty()) return false

        for (v in filteredVideos) {
            if (v.name == "Tau Video" && v.url.contains("tau-video.xyz/embed/")) {
                val videoId = v.url.split("/").lastOrNull() ?: continue
                val apiUrl = "https://tau-video.xyz/api/video/$videoId"
                val apiRes = runCatching { app.get(apiUrl, headers = mapOf(
                    "User-Agent" to ANIMECIX_HEADERS["User-Agent"]!!,
                    "Referer" to v.url
                )).parsedSafe<TauVideoResponse>() }.getOrNull()
                
                apiRes?.urls?.forEach { u ->
                    val isM3u = u.url.contains(".m3u8", ignoreCase = true)
                    
                    callback(
                        newExtractorLink(
                            source = "Fujitora - Tau",
                            name = "Fujitora - Tau ${u.label}",
                            url = u.url,
                            type = if (isM3u) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQualityFromName(u.label)
                        }
                    )
                }
            } else if (v.url.contains("sibnet.ru")) {
                val sRes = runCatching { app.get(v.url, headers = mapOf(
                    "User-Agent" to ANIMECIX_HEADERS["User-Agent"]!!,
                    "Referer" to v.url
                )).text }.getOrNull() ?: continue
                
                val m = Regex("""player\.src\(\[\{src: "([^"]+)"""").find(sRes)
                if (m != null) {
                    val direct = "https://video.sibnet.ru${m.groupValues[1]}"
                    callback(
                        newExtractorLink(
                            source = "Fujitora - Sibnet",
                            name = "Fujitora - Sibnet",
                            url = direct,
                            type = if (direct.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = v.url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }

        return true
    }

    private fun getQualityFromName(name: String): Int {
        return when {
            name.contains("1080") -> Qualities.P1080.value
            name.contains("720") -> Qualities.P720.value
            name.contains("480") -> Qualities.P480.value
            name.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
