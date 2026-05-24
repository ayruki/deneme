package com.izlelan

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object Vegapunk {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"
    private val mainUrl = BaseUrls.get("vegapunk", "https://ydfvfdizipanel.ru")
    private const val apiKey = "9iQNC5HQwPlaFuJDkhncJ5XTJ8feGXOJatAA"

    private const val signature = "308202c3308201aba0030201020204075cec01300d06092a864886f70d01010b050030123110300e0603550403130753696e65776978301e1" +
            "70d3231303932313233333334395a170d3436303931353233333334395a30123110300e0603550403130753696e6577697830820122300d06092a864" +
            "886f70d01010105000382010f003082010a0282010100b0a2a1bc5c3f16f19c3b2456cfd0a6128ced9f5e2e2c4cca1a100e17b07b86256258f372e76" +
            "a95a17e9e4a1c048e364835723a95e8ef6d5bdfb5694b50277c65a64f7b012fdf164e5dc93629561f6ca29b7dc82ebb3d6f3c8e8fc6795847fe331ad" +
            "4a13ed6c059a83804c43d3747526d769580f3a4153752eb22dac66dd15f1582caa43305dc49f55ac7b1b89013e654d2ca8c94c30956659674cc67325" +
            "6c04208f09118bae14cdd72d78f9ee2aece958084a8c2e315deff45726d4fc1f18ec39569ff1abe4f36a8d01090e5f68c07c28763513b88208bcac1a" +
            "6e1941f6fd8bfdd52f832098ddb2154c8f565bc5d58c7106a19e03787e75c7f34997000e3bcf30203010001a321301f301d0603551d0e04160414b54" +
            "5fc18e74a791d9402b53940ae38b96e9e209c300d06092a864886f70d01010b05000382010100a8a64d9e7c8b5db102af15d3caf94ff8d3e9be9008b" +
            "b0021117ca2f0762e68583354b126a041bb1fb6e6308e421e4b5a71f779cde63e5d2fc5976bff966c3c4034e852c077d8e74458fbae2ec1db74b1f40" +
            "82e188bf8ef7c42a44e3fbfb693bb00ee2a727096b42360ddce1bdcd3536f50c8693bcc62a7b7204bcefe2ecf1f7c820bcd63e1d7a6acc8bf6163086" +
            "915fc5f607cf51bc7a8635f98bb4c65a8f24b7b5a82c7b06868f565cb0d6ac4775c4aac777536ddd1a565f990fd8cbe539185fa7aab610b7855a687a" +
            "00f4e55536d72873444552c50fd10727dbf298a9be6ed6ae62148dd1de365f3729915dd31975e28a472d752ac14db3db548405cc31e1e"

    private val headers = mapOf(
        "signature" to signature,
        "hash256" to "f4d4bc98a3fc4600e7f2c2bab7533f1f03d8a70ff03c256bb11dc57050536bd0",
        "User-Agent" to "EasyPlex (Android 13; SM-A546E; samsung; tr)"
    )

    private data class SearchResult(
        val title: String,
        val type: String,
        val id: Int
    )

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

        // 1. Get info from TMDB
        val tmdbUrl = "https://api.themoviedb.org/3/$tmdbType/$id?api_key=$tmdbApiKey&language=en-US"
        val tmdbResp = runCatching { app.get(tmdbUrl).text }.getOrNull() ?: return false
        val tmdbJson = runCatching { JSONObject(tmdbResp) }.getOrNull() ?: return false
        val queryTitle = tmdbJson.optString("title").ifBlank {
            tmdbJson.optString("name").ifBlank {
                tmdbJson.optString("original_title").ifBlank {
                    tmdbJson.optString("original_name")
                }
            }
        }

        if (queryTitle.isBlank()) return false

        // 2. Search Vegapunk
        val encodedTitle = URLEncoder.encode(queryTitle, "UTF-8").replace("+", "%20")
        val searchUrl = "$mainUrl/public/api/search/$encodedTitle/$apiKey"
        val searchResp = runCatching { app.get(searchUrl, headers = headers).text }.getOrNull() ?: return false
        val searchJson = runCatching { JSONObject(searchResp) }.getOrNull() ?: return false
        val searchResultsArray = searchJson.optJSONArray("search") ?: return false

        val results = mutableListOf<SearchResult>()
        for (i in 0 until searchResultsArray.length()) {
            val item = searchResultsArray.optJSONObject(i) ?: continue
            val itemType = item.optString("type")
            val itemId = item.optInt("id", -1)
            val itemTitle = item.optString("title").ifBlank { item.optString("name") }
            if (itemType.isNotBlank() && itemId != -1 && itemTitle.isNotBlank()) {
                results.add(SearchResult(itemTitle, itemType, itemId))
            }
        }

        // 3. Filter candidates
        val candidates = if (type == "movie") {
            results.filter { it.type == "movie" }
        } else {
            if (season == null || episode == null) return false
            results.filter { it.type == "serie" || it.type == "anime" }
        }

        if (candidates.isEmpty()) return false

        // 4. Verify TMDB ID by fetching details concurrently
        val detailsResults = coroutineScope {
            candidates.map { candidate ->
                async {
                    val detailUrl = when (candidate.type) {
                        "movie" -> "$mainUrl/public/api/media/detail/${candidate.id}/$apiKey"
                        "serie" -> "$mainUrl/public/api/series/show/${candidate.id}/$apiKey"
                        else -> "$mainUrl/public/api/animes/show/${candidate.id}/$apiKey"
                    }
                    val resp = runCatching { app.get(detailUrl, headers = headers).text }.getOrNull()
                    if (!resp.isNullOrBlank()) {
                        val json = runCatching { JSONObject(resp) }.getOrNull()
                        if (json != null) Pair(candidate, json) else null
                    } else null
                }
            }.mapNotNull { it.await() }
        }

        // Find match by TMDB ID
        val matchResult = detailsResults.find { (_, json) ->
            val tmdbIdRaw = json.opt("tmdb_id")?.toString()
            tmdbIdRaw != null && tmdbIdRaw == id.toString()
        } ?: return false

        val detailData = matchResult.second
        var videoLink: String? = null

        if (type == "movie") {
            val videos = detailData.optJSONArray("videos")
            if (videos != null && videos.length() > 0) {
                videoLink = videos.optJSONObject(0)?.optString("link")
            }
        } else {
            val seasons = detailData.optJSONArray("seasons")
            if (seasons != null) {
                // Strict Match by Season Number & Episode Number
                for (i in 0 until seasons.length()) {
                    val seasonObj = seasons.optJSONObject(i) ?: continue
                    if (seasonObj.optInt("season_number") == season) {
                        val episodes = seasonObj.optJSONArray("episodes") ?: continue
                        for (j in 0 until episodes.length()) {
                            val epObj = episodes.optJSONObject(j) ?: continue
                            if (epObj.optInt("episode_number") == episode) {
                                val videos = epObj.optJSONArray("videos")
                                if (videos != null && videos.length() > 0) {
                                    videoLink = videos.optJSONObject(0)?.optString("link")
                                    break
                                }
                            }
                        }
                    }
                    if (!videoLink.isNullOrBlank()) break
                }

                // Fallback: Absolute Episode Numbering / Name Parsing (For Anime)
                if (videoLink.isNullOrBlank()) {
                    for (i in 0 until seasons.length()) {
                        val seasonObj = seasons.optJSONObject(i) ?: continue
                        val episodes = seasonObj.optJSONArray("episodes") ?: continue
                        for (j in 0 until episodes.length()) {
                            val epObj = episodes.optJSONObject(j) ?: continue
                            val epNum = epObj.optInt("episode_number")
                            val epName = epObj.optString("name")

                            if (epNum == episode) {
                                val videos = epObj.optJSONArray("videos")
                                if (videos != null && videos.length() > 0) {
                                    videoLink = videos.optJSONObject(0)?.optString("link")
                                    break
                                }
                            }

                            if (epName.isNotBlank()) {
                                val nameMatch = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epName)
                                val nameEpNum = nameMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                                if (nameEpNum == episode) {
                                    val videos = epObj.optJSONArray("videos")
                                    if (videos != null && videos.length() > 0) {
                                        videoLink = videos.optJSONObject(0)?.optString("link")
                                        break
                                    }
                                }
                            }
                        }
                        if (!videoLink.isNullOrBlank()) break
                    }
                }
            }
        }

        if (videoLink.isNullOrBlank()) return false

        // Simplify resolving: Always use native loadExtractor for Mediafire links to prevent hotlink blocks/User-Agent conflicts in ExoPlayer
        if (videoLink.contains("snwaxdop")) {
            callback(
                newExtractorLink(
                    source = "Vegapunk",
                    name = "Vegapunk",
                    url = videoLink,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        } else {
            return runCatching {
                loadExtractor(videoLink, "$mainUrl/", subtitleCallback, callback)
            }.getOrDefault(false)
        }
    }
}
