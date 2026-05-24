package com.izlelan.sources

import com.izlelan.IzlelanProvider
import com.izlelan.BaseUrls

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

object Enel {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"
    private val mainUrl = BaseUrls.get("enel", "https://selcukflix.net")

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private data class SearchResult(
        val name: String,
        val slug: String
    )

    private data class HlsData(
        val label: String,
        val url: String,
        val subtitles: List<SubtitleData>
    )

    private data class SubtitleData(
        val name: String,
        val url: String
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
                val base = java.net.URL(baseUrl)
                "${base.protocol}://${base.authority}$value"
            }
            else -> {
                val index = baseUrl.lastIndexOf('/')
                val baseDir = if (index >= 0) baseUrl.substring(0, index + 1) else "$mainUrl/"
                baseDir + value
            }
        }
    }

    private fun decodeSecureData(secureData: String): String {
        return try {
            val cleaned = secureData.replace("\"", "").trim()
            val decodedBytes = Base64.decode(cleaned, Base64.DEFAULT)
            String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
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

    private fun queryParam(url: String, key: String): String? {
        return runCatching {
            java.net.URL(url).query
                ?.split("&")
                ?.firstOrNull { it.substringBefore("=") == key }
                ?.substringAfter("=", "")
        }.getOrNull()
    }

    private suspend fun getImdbId(id: Int, imdbIdParam: String?, type: String): String? {
        if (!imdbIdParam.isNullOrBlank()) return imdbIdParam
        val extUrl = "https://api.themoviedb.org/3/$type/$id/external_ids?api_key=$tmdbApiKey"
        val extRes = runCatching { app.get(extUrl).parsedSafe<IzlelanProvider.ExternalIds>() }.getOrNull()
        return extRes?.imdb_id
    }

    private suspend fun search(imdbId: String): List<SearchResult> {
        val encoded = URLEncoder.encode(imdbId, "UTF-8")
        val url = "$mainUrl/api/bg/searchcontent?searchterm=$encoded"
        val res = runCatching {
            app.post(url, headers = headers)
        }.getOrNull() ?: return emptyList()

        if (res.code != 200) return emptyList()
        val json = runCatching { JSONObject(res.text) }.getOrNull() ?: return emptyList()
        val secureDataRaw = json.optString("response")
        if (secureDataRaw.isBlank()) return emptyList()

        val decodedJsonStr = decodeSecureData(secureDataRaw)
        if (decodedJsonStr.isBlank()) return emptyList()

        val decodedJson = runCatching { JSONObject(decodedJsonStr) }.getOrNull() ?: return emptyList()
        if (!decodedJson.optBoolean("state")) return emptyList()

        val results = decodedJson.optJSONArray("result") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val slug = item.optString("used_slug").ifBlank { 
                item.optString("slug").ifBlank { 
                    item.optString("url").ifBlank { 
                        item.optString("object_slug").ifBlank { 
                            item.optString("object_url") 
                        } 
                    } 
                } 
            }
            if (slug.isBlank()) return@mapNotNull null
            SearchResult(
                name = item.optString("object_name").ifBlank { item.optString("title", "Movie") },
                slug = slug
            )
        }
    }

    private suspend fun extractContentx(iframeUrl: String): List<HlsData> {
        val parsed = runCatching { java.net.URL(iframeUrl) }.getOrNull() ?: return emptyList()
        val baseUrl = "${parsed.protocol}://${parsed.authority}"
        val page = runCatching {
            app.get(iframeUrl, headers = mapOf("Referer" to mainUrl, "User-Agent" to headers.getValue("User-Agent")))
        }.getOrNull() ?: return emptyList()
        if (page.code != 200) return emptyList()

        val text = page.text
        val vId = Regex("""openPlayer\s*\(\s*['"]([^'"]+)['"]""").find(text)?.groupValues?.getOrNull(1)
            ?: queryParam(iframeUrl, "v")

        val subtitles = mutableListOf<SubtitleData>()
        val seenSubs = mutableSetOf<String>()
        val subtitlePatterns = listOf(
            Regex("""\{"file":"([^"]+)","kind":"subtitles","label":"([^"]+)""""),
            Regex(""""file":"([^"]+)","label":"([^"]+)"""")
        )

        for (pattern in subtitlePatterns) {
            pattern.findAll(text).forEach { match ->
                val subUrl = cleanEscaped(match.groupValues[1])
                val subLabel = cleanEscaped(match.groupValues[2]).ifBlank { "Subtitle" }
                val absoluteSubUrl = resolveUrl(iframeUrl, subUrl) ?: return@forEach
                if (seenSubs.add(absoluteSubUrl)) {
                    subtitles.add(SubtitleData(subLabel, absoluteSubUrl))
                }
            }
            if (subtitles.isNotEmpty()) break
        }

        val results = mutableListOf<HlsData>()
        val masterRegexes = listOf(
            Regex(""""file"\s*:\s*"([^"]+/master\.(?:m3u8|php)[^"]*)""""),
            Regex(""""file"\s*:\s*"([^"]+\?(?:t|token)=[^"]+)"""")
        )

        val masterUrl = masterRegexes
            .asSequence()
            .mapNotNull { it.find(text)?.groupValues?.getOrNull(1) }
            .firstOrNull()
            ?.let { cleanEscaped(it) }
            ?.let { resolveUrl(baseUrl, it) }
            ?: vId?.let { loadSource2(baseUrl, iframeUrl, it) }

        if (!masterUrl.isNullOrBlank()) {
            results.add(HlsData("Enel", masterUrl, subtitles))
        }

        // Extractor for Turkish Dubbing
        val dubId = Regex("""["']([^"']+)["'],\s*["']Türkçe["']""").find(text)?.groupValues?.getOrNull(1)
        if (!dubId.isNullOrBlank()) {
            val dubMasterUrl = loadSource2(baseUrl, iframeUrl, dubId)
            if (!dubMasterUrl.isNullOrBlank()) {
                results.add(HlsData("Enel", dubMasterUrl, subtitles))
            }
        }

        return results
    }

    private suspend fun loadSource2(baseUrl: String, referer: String, videoId: String): String? {
        val encodedId = URLEncoder.encode(videoId, "UTF-8")
        val res = runCatching {
            app.get(
                "$baseUrl/source2.php?v=$encodedId",
                headers = mapOf("Referer" to referer, "User-Agent" to headers.getValue("User-Agent"))
            )
        }.getOrNull() ?: return null
        if (res.code != 200) return null
        val rawUrl = Regex(""""file":"([^"]+)"""").find(res.text)?.groupValues?.getOrNull(1)
            ?: return null
        val cleanedUrl = cleanEscaped(rawUrl)
        val finalUrl = if (cleanedUrl.contains("/m.php")) cleanedUrl.replace("/m.php", "/master.m3u8") else cleanedUrl
        return resolveUrl(baseUrl, finalUrl)
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
        if (type != "movie") return false // SelcukFlix only supports movies

        val imdbId = getImdbId(id, imdbIdParam, type) ?: return false
        val selected = search(imdbId).firstOrNull() ?: return false
        
        val url = fixUrl(selected.slug) ?: return false
        val res = runCatching { app.get(url, headers = headers) }.getOrNull() ?: return false
        if (res.code != 200) return false

        val document = Jsoup.parse(res.text)
        val script = document.selectFirst("script#__NEXT_DATA__") ?: return false
        val nextJson = runCatching { JSONObject(script.html()) }.getOrNull() ?: return false
        val props = nextJson.optJSONObject("props") ?: return false
        val pageProps = props.optJSONObject("pageProps") ?: return false
        val secureDataRaw = pageProps.optString("secureData")
        if (secureDataRaw.isBlank()) return false

        val decodedJsonStr = decodeSecureData(secureDataRaw)
        if (decodedJsonStr.isBlank()) return false

        val root = runCatching { JSONObject(decodedJsonStr) }.getOrNull() ?: return false
        val relatedResults = root.optJSONObject("RelatedResults") ?: return false
        val movieParts = relatedResults.optJSONObject("getMoviePartsById") ?: return false
        if (!movieParts.optBoolean("state")) return false
        val partsArray = movieParts.optJSONArray("result") ?: return false

        var found = false

        for (i in 0 until partsArray.length()) {
            val part = partsArray.optJSONObject(i) ?: continue
            val partId = part.optString("id")
            val partSourcesKey = "getMoviePartSourcesById_$partId"
            val partSources = relatedResults.optJSONObject(partSourcesKey) ?: continue
            val resultList = partSources.optJSONArray("result") ?: continue

            for (j in 0 until resultList.length()) {
                val ifs = resultList.optJSONObject(j) ?: continue
                val sourceContent = ifs.optString("source_content")
                val iframeUrl = Jsoup.parse(sourceContent).selectFirst("iframe")?.attr("src")
                if (iframeUrl.isNullOrBlank()) continue

                val fixedIframeUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl

                val isContentx = listOf("pichive", "picholes", "piccrest", "contentx", "celestialwallscapes", "dplayer")
                    .any { fixedIframeUrl.contains(it, ignoreCase = true) }

                if (isContentx) {
                    val hlsLinks = extractContentx(fixedIframeUrl)
                    val seenSubUrls = mutableSetOf<String>()

                    for (hls in hlsLinks) {
                        val subHeaders = mapOf(
                            "Referer" to fixedIframeUrl,
                            "User-Agent" to headers.getValue("User-Agent")
                        )
                        hls.subtitles.forEach { sub ->
                            if (seenSubUrls.add(sub.url)) {
                                subtitleCallback(SubtitleFile(sub.name, sub.url).apply { this.headers = subHeaders })
                            }
                        }

                        callback(
                            newExtractorLink(
                                source = "Enel",
                                name = hls.label,
                                url = hls.url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = fixedIframeUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = subHeaders
                            }
                        )
                        found = true
                    }
                } else {
                    val loaded = runCatching {
                        loadExtractor(fixedIframeUrl, mainUrl, subtitleCallback, callback)
                    }.getOrDefault(false)
                    if (loaded) found = true
                }
            }
        }

        return found
    }
}
