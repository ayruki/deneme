package com.izlelan.sources

import com.izlelan.IzlelanProvider
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import org.json.JSONObject

object Nusjuro {
    private const val DB_BASE = "https://enc-dec.app/db"
    private const val API_BASE = "https://enc-dec.app/api"
    private const val YFLIX_AJAX = "https://yflix.to/ajax"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "https://yflix.to/",
        "Accept" to "application/json"
    )

    suspend fun invoke(
        id: Int,
        type: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbType = if (type.equals("movie", ignoreCase = true)) "movie" else "tv"
        
        // 1. Find entry in enc-dec database
        val dbUrl = "$DB_BASE/flix/find?tmdb_id=$id&type=$tmdbType"
        val dbResponse = runCatching { app.get(dbUrl).text }.getOrNull() ?: return false
        val dbArray = runCatching { org.json.JSONArray(dbResponse) }.getOrNull() ?: return false
        if (dbArray.length() == 0) return false
        val entry = dbArray.getJSONObject(0)

        // 2. Extract EID (Episode/Content ID)
        val episodesData = entry.optJSONObject("episodes") ?: return false
        val sStr = if (tmdbType == "movie") "1" else (season ?: 1).toString()
        val eStr = if (tmdbType == "movie") "1" else (episode ?: 1).toString()
        
        val seasonObj = episodesData.optJSONObject(sStr) ?: return false
        val epObj = seasonObj.optJSONObject(eStr) ?: return false
        val eid = epObj.optString("eid").ifBlank { null } ?: return false

        // 3. Encrypt EID
        val encEidUrl = "$API_BASE/enc-movies-flix?text=$eid"
        val encEidResponse = runCatching { app.get(encEidUrl).text }.getOrNull() ?: return false
        val encEidJson = runCatching { JSONObject(encEidResponse) }.getOrNull() ?: return false
        val encEid = encEidJson.optString("result").ifBlank { null } ?: return false

        // 4. Fetch servers list HTML from YFlix AJAX
        val serversUrl = "$YFLIX_AJAX/links/list?eid=$eid&_=$encEid"
        val serversResponse = runCatching { app.get(serversUrl, headers = headers).text }.getOrNull() ?: return false
        val serversJson = runCatching { JSONObject(serversResponse) }.getOrNull() ?: return false
        val serversHtml = serversJson.optString("result").ifBlank { null } ?: return false

        // 5. Parse servers list HTML via enc-dec parse-html API
        val parseUrl = "$API_BASE/parse-html"
        val parseResponse = runCatching {
            app.post(parseUrl, headers = headers, json = mapOf("text" to serversHtml)).text
        }.getOrNull() ?: return false
        val parseJson = runCatching { JSONObject(parseResponse) }.getOrNull() ?: return false
        val servers = parseJson.optJSONObject("result") ?: return false

        var foundAny = false
        val keys = servers.keys()

        // 6. Loop through parsed servers and load embed URLs
        while (keys.hasNext()) {
            val sType = keys.next() ?: continue
            val sList = servers.optJSONObject(sType) ?: continue
            val sIds = sList.keys()
            
            while (sIds.hasNext()) {
                val sId = sIds.next() ?: continue
                val sInfo = sList.optJSONObject(sId) ?: continue
                val lid = sInfo.optString("lid").ifBlank { null } ?: continue

                // Encrypt LID
                val encLidUrl = "$API_BASE/enc-movies-flix?text=$lid"
                val encLidResponse = runCatching { app.get(encLidUrl).text }.getOrNull() ?: continue
                val encLidJson = runCatching { JSONObject(encLidResponse) }.getOrNull() ?: continue
                val encLid = encLidJson.optString("result").ifBlank { null } ?: continue

                // Fetch YFlix AJAX view response
                val viewUrl = "$YFLIX_AJAX/links/view?id=$lid&_=$encLid"
                val viewResponse = runCatching { app.get(viewUrl, headers = headers).text }.getOrNull() ?: continue
                val viewJson = runCatching { JSONObject(viewResponse) }.getOrNull() ?: continue
                val encryptedText = viewJson.optString("result").ifBlank { null } ?: continue

                // Decrypt embed parameters
                val decUrl = "$API_BASE/dec-movies-flix"
                val decResponse = runCatching {
                    app.post(decUrl, headers = headers, json = mapOf("text" to encryptedText)).text
                }.getOrNull() ?: continue
                val decJson = runCatching { JSONObject(decResponse) }.getOrNull() ?: continue
                val resultData = decJson.optJSONObject("result") ?: continue
                val embedUrl = resultData.optString("url").ifBlank { null } ?: continue

                // 7. Process subtitles from sub.list query parameter
                if (embedUrl.contains("sub.list=")) {
                    val subListUrlEncoded = embedUrl.substringAfter("sub.list=").substringBefore("&")
                    val subListUrl = runCatching { java.net.URLDecoder.decode(subListUrlEncoded, "UTF-8") }.getOrNull()
                    if (!subListUrl.isNullOrBlank()) {
                        val subResponse = runCatching { app.get(subListUrl).text }.getOrNull()
                        if (!subResponse.isNullOrBlank()) {
                            val subArray = runCatching { org.json.JSONArray(subResponse) }.getOrNull()
                            if (subArray != null) {
                                for (j in 0 until subArray.length()) {
                                    val subObj = subArray.optJSONObject(j) ?: continue
                                    val file = subObj.optString("file")
                                    val label = subObj.optString("label")
                                    if (file.isNotBlank() && label.isNotBlank()) {
                                        subtitleCallback(newSubtitleFile(label, file))
                                    }
                                }
                            }
                        }
                    }
                }

                // 8. Try to extract direct sources if it's a rapidshare/vidcloud embed
                var hasDirectSources = false
                if (embedUrl.contains("/e/")) {
                    val mediaUrl = embedUrl.replace("/e/", "/media/")
                    val referer = embedUrl.substringBefore("/e/") + "/"
                    val mediaHeaders = mapOf(
                        "Referer" to referer,
                        "User-Agent" to UA,
                        "Accept" to "application/json"
                    )
                    
                    val mediaResponse = runCatching { app.get(mediaUrl, headers = mediaHeaders).text }.getOrNull()
                    val mediaJson = if (mediaResponse != null) runCatching { JSONObject(mediaResponse) }.getOrNull() else null
                    val mediaEnc = mediaJson?.optString("result")?.ifBlank { null }
                    
                    if (mediaEnc != null) {
                        val decRapidUrl = "$API_BASE/dec-rapid"
                        val decRapidResponse = runCatching {
                            app.post(
                                decRapidUrl,
                                headers = headers,
                                json = mapOf(
                                    "text" to mediaEnc,
                                    "agent" to UA
                                )
                            ).text
                        }.getOrNull()
                        
                        val decRapidJson = if (decRapidResponse != null) runCatching { JSONObject(decRapidResponse) }.getOrNull() else null
                        if (decRapidJson != null && decRapidJson.optInt("status") == 200) {
                            val mediaDec = decRapidJson.optJSONObject("result")
                            if (mediaDec != null) {
                                // Extract subtitles/tracks from mediaDec if any
                                val tracks = mediaDec.optJSONArray("tracks")
                                if (tracks != null) {
                                    for (t in 0 until tracks.length()) {
                                        val track = tracks.optJSONObject(t) ?: continue
                                        if (track.optString("kind") == "captions") {
                                            val label = track.optString("label")
                                            val file = track.optString("file")
                                            if (label.isNotBlank() && file.isNotBlank()) {
                                                subtitleCallback(newSubtitleFile(label, file))
                                            }
                                        }
                                    }
                                }
                                
                                val sourcesArray = mediaDec.optJSONArray("sources")
                                if (sourcesArray != null) {
                                    for (s in 0 until sourcesArray.length()) {
                                        val realSrc = sourcesArray.optJSONObject(s) ?: continue
                                        val file = realSrc.optString("file")
                                        val label = realSrc.optString("label", "HD")
                                        if (file.isNotBlank()) {
                                            val name = "yFlix - ${sInfo.optString("name", "Server")} ($label)"
                                            val isM3u8 = file.endsWith(".m3u8") || file.contains(".m3u8")
                                            val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            
                                            callback(
                                                newExtractorLink(
                                                    source = "Nusjuro",
                                                    name = name,
                                                    url = file,
                                                    type = linkType
                                                ) {
                                                    this.referer = referer
                                                    this.quality = Qualities.Unknown.value
                                                    this.headers = mapOf("Referer" to referer, "User-Agent" to UA)
                                                }
                                            )
                                            hasDirectSources = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (hasDirectSources) {
                    foundAny = true
                } else {
                    // Fallback to loadExtractor
                    val loaded = runCatching {
                        loadExtractor(embedUrl, "https://yflix.to/", subtitleCallback, callback)
                    }.getOrDefault(false)

                    if (loaded) {
                        foundAny = true
                    }
                }
            }
        }

        return foundAny
    }
}
