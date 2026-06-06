package com.izlelan.sources

import com.izlelan.IzlelanProvider
import com.izlelan.BaseUrls
import com.izlelan.network.CFClient

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Smoker {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"
    private val mainUrl = BaseUrls.get("smoker", "https://dizipal1555.com")
    private const val pbkdf2Password = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )


    private data class SearchResult(
        val name: String,
        val slug: String
    )

    private data class EpisodeData(
        val season: Int,
        val episode: Int,
        val url: String
    )

    private data class SeriesInfo(
        val title: String,
        val episodes: List<EpisodeData>
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

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun decryptDizipal(ciphertext: String, saltHex: String, ivHex: String): String {
        return try {
            val salt = hexToBytes(saltHex)
            val iv = hexToBytes(ivHex)
            val ciphertextBytes = Base64.decode(ciphertext, Base64.DEFAULT)
            
            val spec = PBEKeySpec(pbkdf2Password.toCharArray(), salt, 999, 256)
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val keyBytes = skf.generateSecret(spec).encoded
            
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            String(cipher.doFinal(ciphertextBytes), Charsets.UTF_8)
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

    private suspend fun getImdbId(id: Int, imdbIdParam: String?): String? {
        if (!imdbIdParam.isNullOrBlank()) return imdbIdParam
        val extUrl = "https://api.themoviedb.org/3/tv/$id/external_ids?api_key=$tmdbApiKey"
        val extRes = runCatching { app.get(extUrl).parsedSafe<IzlelanProvider.ExternalIds>() }.getOrNull()
        return extRes?.imdb_id
    }

    private suspend fun search(imdbId: String): List<SearchResult> {
        val encoded = URLEncoder.encode(imdbId, "UTF-8")
        val res = runCatching {
            CFClient.post(
                "$mainUrl/bg/searchcontent", 
                headers = headers, 
                data = mapOf("searchterm" to encoded)
            )
        }.getOrNull() ?: return emptyList()

        if (res.code != 200) return emptyList()
        val json = runCatching { JSONObject(res.text) }.getOrNull() ?: return emptyList()
        val data = json.optJSONObject("data") ?: return emptyList()
        if (!data.optBoolean("state")) return emptyList()

        val results = data.optJSONArray("result") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val slug = item.optString("used_slug").ifBlank { item.optString("slug") }
            if (slug.isBlank()) return@mapNotNull null
            SearchResult(
                name = item.optString("object_name").ifBlank { item.optString("title", "Series") },
                slug = slug
            )
        }
    }

    private suspend fun getSeriesInfo(slug: String): SeriesInfo? {
        val url = fixUrl(slug) ?: return null
        val res = runCatching { CFClient.get(url, headers = headers) }.getOrNull() ?: return null
        if (res.code != 200) return null

        val document = Jsoup.parse(res.text)
        val title = document.title().replace("Dizipal", "").trim()

        val episodes = mutableListOf<EpisodeData>()
        val episodeRegex = Regex("""bolum/[a-zA-Z0-9\-]+?-(\d+)x(\d+)""")

        document.select("a").forEach { a ->
            val href = a.attr("href").orEmpty()
            val match = episodeRegex.find(href)
            if (match != null) {
                val seasonNo = match.groupValues[1].toIntOrNull() ?: -1
                val epNo = match.groupValues[2].toIntOrNull() ?: -1
                val epUrl = fixUrl(href) ?: return@forEach
                if (seasonNo > 0 && epNo > 0) {
                    episodes.add(EpisodeData(seasonNo, epNo, epUrl))
                }
            }
        }

        return SeriesInfo(title, episodes.distinctBy { it.url })
    }

    private suspend fun extractContentx(iframeUrl: String): List<HlsData> {
        val parsed = runCatching { java.net.URL(iframeUrl) }.getOrNull() ?: return emptyList()
        val baseUrl = "${parsed.protocol}://${parsed.authority}"
        val page = runCatching {
            CFClient.get(iframeUrl, headers = mapOf("Referer" to mainUrl, "User-Agent" to headers.getValue("User-Agent")))
        }.getOrNull() ?: return emptyList()
        if (page.code != 200) return emptyList()

        val text = page.text
        val vId = queryParam(iframeUrl, "v")
            ?: Regex("""openPlayer\s*\(\s*['"][^'"]+['"]\s*,\s*['"]([^'"]+)['"]""").find(text)?.groupValues?.getOrNull(1)
            ?: Regex("""openPlayer\s*\(\s*['"]([^'"]+)['"]""").find(text)?.groupValues?.getOrNull(1)

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
            results.add(HlsData("Master HLS", masterUrl, subtitles))
        }

        return results
    }

    private suspend fun loadSource2(baseUrl: String, referer: String, videoId: String): String? {
        val encodedId = java.net.URLEncoder.encode(videoId, "UTF-8")
        val res = runCatching {
            CFClient.get(
                "$baseUrl/source2.php?v=$encodedId",
                headers = mapOf("Referer" to referer, "User-Agent" to headers.getValue("User-Agent"))
            )
        }.getOrNull() ?: return null
        if (res.code != 200) return null
        val rawUrl = Regex(""""file":"([^"]+)"""").find(res.text)?.groupValues?.getOrNull(1)
            ?: return null
        return resolveUrl(baseUrl, cleanEscaped(rawUrl))
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
        if (type == "movie" || season == null || episode == null) return false

        val imdbId = getImdbId(id, imdbIdParam) ?: return false
        val selected = search(imdbId).firstOrNull() ?: return false
        val info = getSeriesInfo(selected.slug) ?: return false
        val target = info.episodes.firstOrNull { it.season == season && it.episode == episode }
            ?: return false

        // Fetch episode page to extract [data-rm-k]
        val epPageRes = runCatching { CFClient.get(target.url, headers = headers) }.getOrNull() ?: return false
        if (epPageRes.code != 200) return false

        val epDoc = Jsoup.parse(epPageRes.text)
        val dataEl = epDoc.selectFirst("[data-rm-k]") ?: return false
        val encryptedJsonStr = dataEl.text()
        if (encryptedJsonStr.isBlank()) return false

        val encryptedJson = runCatching { JSONObject(encryptedJsonStr) }.getOrNull() ?: return false
        val ciphertext = encryptedJson.optString("ciphertext")
        val salt = encryptedJson.optString("salt")
        val iv = encryptedJson.optString("iv")

        if (ciphertext.isNullOrBlank() || salt.isNullOrBlank() || iv.isNullOrBlank()) return false

        val decryptedIframeUrl = decryptDizipal(ciphertext, salt, iv)
        var iframeUrl = cleanEscaped(decryptedIframeUrl)
        if (iframeUrl.isBlank()) return false
        if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"

        val isContentx = listOf("pichive", "picholes", "contentx", "dplayer", "four.pichive")
            .any { iframeUrl.contains(it, ignoreCase = true) }

        if (isContentx) {
            val hlsLinks = extractContentx(iframeUrl)
            var found = false
            val seenSubUrls = mutableSetOf<String>()

            for (hls in hlsLinks) {
                val subHeaders = mapOf(
                    "Referer" to iframeUrl,
                    "User-Agent" to headers.getValue("User-Agent")
                )
                hls.subtitles.forEach { sub ->
                    if (seenSubUrls.add(sub.url)) {
                        subtitleCallback(SubtitleFile(sub.name, sub.url).apply { headers = subHeaders })
                    }
                }

                callback(
                    newExtractorLink(
                        source = "🇹🇷 Smoker",
                        name = "🇹🇷 Smoker",
                        url = hls.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = iframeUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = subHeaders
                    }
                )
                found = true
            }
            return found
        } else {
            val loaded = runCatching {
                loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
            }.getOrDefault(false)
            return loaded
        }
    }
}
