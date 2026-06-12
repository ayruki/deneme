package com.izlelan.sources

import com.izlelan.IzlelanProvider
import com.izlelan.BaseUrls
import com.izlelan.network.CFClient

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import android.util.Log

object Sabo {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"

    private fun resolveUrl(base: String, relative: String): String {
        return when {
            relative.startsWith("http://") || relative.startsWith("https://") -> relative
            relative.startsWith("//") -> "https:$relative"
            relative.startsWith("/") -> {
                val u = java.net.URL(base)
                "${u.protocol}://${u.authority}$relative"
            }
            else -> base.substring(0, base.lastIndexOf('/') + 1) + relative
        }
    }

    private fun parseSubtitles(raw: String): List<SubtitleFile> {
        val subs = mutableListOf<SubtitleFile>()
        if (raw.isEmpty()) return subs
        val regex = Regex("""\[([^\]]+)\](https?://[^\s,]+)""")
        regex.findAll(raw).forEach { match ->
            val lang = match.groupValues[1].trim()
            val url = match.groupValues[2].trim()
            subs.add(SubtitleFile(lang, url))
        }
        
        // Sort: Turkish first, English second, others later
        return subs.sortedWith(compareBy {
            val l = it.lang.lowercase()
            when {
                l.contains("türkçe") || l.contains("turkish") || l == "tr" -> 0
                l.contains("english") || l == "en" -> 1
                else -> 2
            }
        })
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
        // ── Step 1: TMDB Details & IMDB ID ─────────────────────────────────────
        val tmdbUrl = "https://api.themoviedb.org/3/$type/$id?api_key=$tmdbApiKey&language=en-US"
        val tmdbRes = runCatching { app.get(tmdbUrl).parsedSafe<IzlelanProvider.MediaDetail>() }.getOrNull()
        val titleFallback = tmdbRes?.name ?: tmdbRes?.title

        val imdbId = if (!imdbIdParam.isNullOrEmpty()) {
            imdbIdParam
        } else {
            tmdbRes?.external_ids?.imdb_id ?: run {
                val extUrl = "https://api.themoviedb.org/3/$type/$id/external_ids?api_key=$tmdbApiKey"
                val extRes = runCatching { app.get(extUrl).parsedSafe<IzlelanProvider.ExternalIds>() }.getOrNull()
                extRes?.imdb_id
            }
        }

        // ── Step 2: Search cinemacity.cc ───────────────────────────────────────
        val base = BaseUrls.get("sabo", "https://cinemacity.cc")
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "$base/"
        )

        val results = mutableListOf<String>()

        suspend fun performSearch(query: String) {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$base/?do=search&subaction=search&story=$encodedQuery"
            val searchRes = runCatching { CFClient.get(searchUrl, headers = headers) }.getOrNull() ?: return
            if (searchRes.code != 200) return

            var searchArea = searchRes.text
            if (searchArea.contains("id=\"dle-content\"")) {
                searchArea = searchArea.substringAfter("id=\"dle-content\"")
                if (searchArea.contains("class=\"footer\"")) {
                    searchArea = searchArea.substringBefore("class=\"footer\"")
                }
            }

            val linkRegex = Regex("""href="([^"]+/(?:movies|tv-series)/[^"]+)"""")
            linkRegex.findAll(searchArea).forEach { match ->
                val href = match.groupValues[1]
                val absUrl = resolveUrl(base, href)
                if (!results.contains(absUrl)) {
                    results.add(absUrl)
                }
            }
        }

        if (!imdbId.isNullOrEmpty()) {
            performSearch(imdbId)
        }

        if (results.isEmpty() && !titleFallback.isNullOrEmpty()) {
            performSearch(titleFallback)
        }

        if (results.isEmpty()) return false

        // ── Step 3: Match and Parse Content ────────────────────────────────────
        for (url in results) {
            val detailRes = runCatching { CFClient.get(url, headers = headers) }.getOrNull() ?: continue
            if (detailRes.code != 200) continue
            val html = detailRes.text

            val matchImdb = Regex("""tt\d+""").find(html)?.value
            if (!imdbId.isNullOrEmpty() && matchImdb != imdbId) continue

            // Extract Title
            val titleMatch = Regex("""<meta[^>]*property="og:title"[^>]*content="([^"]+)"""").find(html)
            val title = titleMatch?.let {
                Regex("""\s*\(\d{4}\)\s*$""").replace(it.groupValues[1], "").trim()
            } ?: ""

            // Extract PlayerJS encoded data
            val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
            val atobMatches = atobRegex.findAll(html)
            
            var streamUrl: String? = null
            var parsedSubs = listOf<SubtitleFile>()

            for (match in atobMatches) {
                val b64 = match.groupValues[1].replace("\\s".toRegex(), "")
                val decoded = runCatching {
                    String(Base64.decode(b64, Base64.DEFAULT))
                }.getOrNull() ?: continue

                if (decoded.contains("new Playerjs(")) {
                    val fileMatch = Regex("""file:\s*'([^']+)'""").find(decoded)
                        ?: Regex("""file:\s*"([^"]+)"""").find(decoded)
                    
                    if (fileMatch != null) {
                        val fileVal = fileMatch.groupValues[1].replace("\\/", "/")
                        
                        if (fileVal.startsWith("[")) {
                            val fileArray = runCatching { JSONArray(fileVal) }.getOrNull()
                            if (fileArray != null) {
                                if (type == "tv") {
                                    // TV Series
                                    for (sIdx in 0 until fileArray.length()) {
                                        val sItem = fileArray.getJSONObject(sIdx)
                                        val sTitle = sItem.optString("title", "")
                                        val sNumMatch = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(sTitle) ?: continue
                                        val sNum = sNumMatch.groupValues[1].toInt()
                                        
                                        if (sNum == season) {
                                            val epList = sItem.optJSONArray("folder") ?: sItem.optJSONArray("file") ?: continue
                                            for (eIdx in 0 until epList.length()) {
                                                val epItem = epList.getJSONObject(eIdx)
                                                val epTitle = epItem.optString("title", "")
                                                val eNumMatch = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(epTitle) ?: continue
                                                val eNum = eNumMatch.groupValues[1].toInt()
                                                
                                                if (eNum == episode) {
                                                    streamUrl = epItem.optString("file", "").replace("\\/", "/")
                                                    parsedSubs = parseSubtitles(epItem.optString("subtitle", ""))
                                                    break
                                                }
                                            }
                                        }
                                        if (streamUrl != null) break
                                    }
                                } else {
                                    // Movie in JSON format
                                    val movieItem = fileArray.optJSONObject(0)
                                    if (movieItem != null) {
                                        streamUrl = movieItem.optString("file", "").replace("\\/", "/")
                                        parsedSubs = parseSubtitles(movieItem.optString("subtitle", ""))
                                    }
                                }
                            }
                        } else {
                            // Direct stream URL
                            if (type == "movie") {
                                streamUrl = fileVal
                                // Check if there's subtitles in the JS config
                                val subMatch = Regex("""subtitle:\s*'([^']+)'""").find(decoded)
                                    ?: Regex("""subtitle:\s*"([^"]+)"""").find(decoded)
                                if (subMatch != null) {
                                    parsedSubs = parseSubtitles(subMatch.groupValues[1].replace("\\/", "/"))
                                }
                            }
                        }
                    }
                }
                if (streamUrl != null) break
            }

            if (!streamUrl.isNullOrEmpty()) {
                // Determine if Turkish is available
                val isTurkish = parsedSubs.any {
                    val l = it.lang.lowercase()
                    l.contains("türkçe") || l.contains("turkish") || l == "tr"
                } || title.contains("Türkçe", ignoreCase = true) || html.contains("Türkçe Dublaj", ignoreCase = true)

                val sourceName = if (isTurkish) "🇹🇷 Sabo" else "🇬🇧 Sabo"

                parsedSubs.forEach { sub ->
                    val cleanLabel = if (sub.lang.lowercase().contains("türkçe") || sub.lang.lowercase().contains("turkish") || sub.lang.lowercase() == "tr") {
                        "Türkçe"
                    } else {
                        sub.lang
                    }
                    subtitleCallback(SubtitleFile(cleanLabel, sub.url))
                }

                callback(
                    newExtractorLink(
                        sourceName,
                        sourceName,
                        streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://cinemacity.cc/"
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
                return true
            }
        }
        return false
    }
}
