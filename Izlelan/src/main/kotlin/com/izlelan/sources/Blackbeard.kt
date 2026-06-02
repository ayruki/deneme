package com.izlelan.sources

import com.izlelan.IzlelanProvider
import com.izlelan.BaseUrls

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.net.URL

object Blackbeard {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"
    private val mainUrl = BaseUrls.get("blackbeard", "https://dizipal2075.com")

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private fun fixUrl(url: String?, base: String = mainUrl): String? {
        val value = url?.trim().orEmpty()
        return when {
            value.isEmpty() -> null
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$base$value"
            else -> "$base/$value"
        }
    }

    private fun resolveUrl(baseUrl: String, url: String?): String? {
        val value = url?.trim().orEmpty()
        return when {
            value.isEmpty() -> null
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> {
                val base = URL(baseUrl)
                "${base.protocol}://${base.authority}$value"
            }
            else -> {
                val index = baseUrl.lastIndexOf('/')
                val baseDir = if (index >= 0) baseUrl.substring(0, index + 1) else "$mainUrl/"
                baseDir + value
            }
        }
    }

    private fun cleanEscaped(value: String): String {
        val unicodeDecoded = Regex("""\\u([0-9a-fA-F]{4})""").replace(value) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return unicodeDecoded
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\", "")
            .trim()
    }

    private suspend fun getImdbId(id: Int, type: String, imdbIdParam: String?): String? {
        if (!imdbIdParam.isNullOrBlank()) return imdbIdParam
        val tmdbType = if (type == "movie") "movie" else "tv"
        val extUrl = "https://api.themoviedb.org/3/$tmdbType/$id/external_ids?api_key=$tmdbApiKey"
        val extRes = runCatching { app.get(extUrl).parsedSafe<IzlelanProvider.ExternalIds>() }.getOrNull()
        return extRes?.imdb_id
    }

    private suspend fun search(imdbId: String): String? {
        val encoded = URLEncoder.encode(imdbId, "UTF-8")
        val searchUrl = "$mainUrl/ajax-search?q=$encoded"
        
        val res = runCatching {
            app.get(
                searchUrl,
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to headers.getValue("User-Agent")
                ),
                referer = "$mainUrl/"
            )
        }.getOrNull() ?: return null

        if (res.code != 200) return null
        val json = runCatching { JSONObject(res.text) }.getOrNull() ?: return null
        if (!json.optBoolean("success")) return null

        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        val firstItem = results.optJSONObject(0) ?: return null
        return firstItem.optString("url").ifBlank { null }
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
        val imdbId = getImdbId(id, type, imdbIdParam) ?: return false
        val contentUrl = search(imdbId) ?: return false

        var targetUrl = contentUrl
        val isMovie = type == "movie"

        if (!isMovie) {
            if (season == null || episode == null) return false
            val tvPageRes = runCatching { app.get(contentUrl, headers = headers) }.getOrNull() ?: return false
            if (tvPageRes.code != 200) return false

            val soup = Jsoup.parse(tvPageRes.text)
            val episodeRegex = Regex("""bolum/[a-zA-Z0-9\-]+?-(\d+)(?:x|-sezon-)(\d+)""")
            val episodes = mutableListOf<Triple<Int, Int, String>>()

            soup.select("a").forEach { a ->
                val href = a.attr("href").orEmpty()
                val match = episodeRegex.find(href)
                if (match != null) {
                    val sNo = match.groupValues[1].toIntOrNull() ?: -1
                    val eNo = match.groupValues[2].toIntOrNull() ?: -1
                    val fullHref = fixUrl(href)
                    if (sNo > 0 && eNo > 0 && fullHref != null) {
                        episodes.add(Triple(sNo, eNo, fullHref))
                    }
                }
            }

            val target = episodes.firstOrNull { it.first == season && it.second == episode } ?: return false
            targetUrl = target.third
        }

        // Fetch episode page or movie page to extract data-cfg and cookies
        val pageRes = runCatching {
            app.get(
                targetUrl,
                headers = mapOf(
                    "User-Agent" to headers.getValue("User-Agent"),
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache"
                )
            )
        }.getOrNull() ?: return false
        if (pageRes.code != 200) return false

        val soup = Jsoup.parse(pageRes.text)
        val container = soup.selectFirst("#videoContainer") ?: return false
        val configToken = container.attr("data-cfg").trim()
        if (configToken.isEmpty()) return false

        val cookies = pageRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        // Send POST request to ajax-player-config
        val playerConfigUrl = "$mainUrl/ajax-player-config"
        val configResponseRaw = runCatching {
            app.post(
                playerConfigUrl,
                headers = mapOf(
                    "User-Agent" to headers.getValue("User-Agent"),
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to mainUrl,
                    "Cookie" to cookies
                ),
                referer = targetUrl,
                data = mapOf("cfg" to configToken)
            ).text
        }.getOrNull() ?: return false

        val embedUrlRaw = Regex(""""v"\s*:\s*"([^"]+)"""").find(configResponseRaw)?.groupValues?.getOrNull(1) ?: return false
        var embedUrl = cleanEscaped(embedUrlRaw)
        if (embedUrl.startsWith("//")) embedUrl = "https:$embedUrl"

        if (embedUrl.contains("imagestoo")) {
            val videoId = embedUrl.trimEnd('/').substringAfterLast("/")
            val imagestooApiUrl = "https://imagestoo.com/player/index.php?data=$videoId&do=getVideo"

            val apiResponse = runCatching {
                app.post(
                    imagestooApiUrl,
                    referer = embedUrl,
                    headers = mapOf(
                        "User-Agent" to headers.getValue("User-Agent"),
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "*/*"
                    )
                )
            }.getOrNull() ?: return false

            if (apiResponse.code == 200) {
                val apiJson = runCatching { JSONObject(apiResponse.text) }.getOrNull() ?: return false
                val securedLink = apiJson.optString("securedLink")
                if (!securedLink.isNullOrBlank()) {
                    val finalHlsUrl = cleanEscaped(securedLink)
                    
                    var sessionCookie = ""
                    val playerToken = apiResponse.cookies["fireplayer_player"]
                    if (!playerToken.isNullOrEmpty()) {
                        sessionCookie = "fireplayer_player=$playerToken"
                    } else {
                        val rawSetCookie = apiResponse.headers["Set-Cookie"] ?: apiResponse.headers["set-cookie"]
                        if (rawSetCookie != null && rawSetCookie.contains("fireplayer_player")) {
                            val cleanCookie = rawSetCookie.split(";").firstOrNull()
                            if (cleanCookie != null) {
                                sessionCookie = "$cleanCookie;"
                            }
                        }
                    }

                    callback(
                        newExtractorLink(
                            source = "🇹🇷 Blackbeard",
                            name = "🇹🇷 Blackbeard (Imagestoo)",
                            url = finalHlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = embedUrl
                            this.headers = mapOf("Cookie" to sessionCookie)
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }
            return false
        } else {
            val embedRes = runCatching {
                app.get(
                    embedUrl,
                    referer = targetUrl,
                    headers = mapOf("User-Agent" to headers.getValue("User-Agent"))
                )
            }.getOrNull() ?: return false
            if (embedRes.code != 200) return false

            val embedText = embedRes.text

            val m3u8Match = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8.*?)["']""").find(embedText)
                ?: Regex("""file\s*:\s*["']([^"']+\.m3u8.*?)["']""").find(embedText)

            val m3u8UrlRaw = m3u8Match?.groupValues?.getOrNull(1) ?: return false
            val m3u8Url = resolveUrl(embedUrl, cleanEscaped(m3u8UrlRaw)) ?: return false

            val subHeaders = mapOf(
                "Referer" to embedUrl,
                "User-Agent" to headers.getValue("User-Agent")
            )

            // Extract subtitles from tracks block
            val tracksBlockMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(embedText)
            tracksBlockMatch?.groupValues?.getOrNull(1)?.let { tracksBlock ->
                val trackItemRegex = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
                trackItemRegex.findAll(tracksBlock).forEach { itemMatch ->
                    val itemStr = itemMatch.groupValues[1]
                    val fileMatch = Regex("""file\s*:\s*["']([^"']+)["']""").find(itemStr)
                    val labelMatch = Regex("""label\s*:\s*["']([^"']+)["']""").find(itemStr)

                    val fileUrl = fileMatch?.groupValues?.getOrNull(1)
                    val label = labelMatch?.groupValues?.getOrNull(1) ?: "Unknown"

                    if (fileUrl != null && (fileUrl.endsWith(".vtt") || fileUrl.endsWith(".srt"))) {
                        val resolvedSubUrl = resolveUrl(embedUrl, cleanEscaped(fileUrl))
                        if (resolvedSubUrl != null) {
                            subtitleCallback(
                                SubtitleFile(
                                    lang = label,
                                    url = resolvedSubUrl
                                ).apply { this.headers = subHeaders }
                            )
                        }
                    }
                }
            }

            callback(
                newExtractorLink(
                    source = "🇹🇷 Blackbeard",
                    name = "🇹🇷 Blackbeard",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedUrl
                    this.headers = subHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
    }
}
