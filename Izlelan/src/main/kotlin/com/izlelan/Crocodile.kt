package com.izlelan

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crocodile {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"
    private const val mainUrl = "https://dizillahd.com"
    private const val decryptKey = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"

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

    private data class SourceData(
        val name: String,
        val url: String
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

    private fun decryptData(encrypted: String?): String {
        if (encrypted.isNullOrBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val key = SecretKeySpec(decryptKey.toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec(ByteArray(16))
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT)).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun decryptedJson(encrypted: String?): JSONObject? {
        val decrypted = decryptData(encrypted)
        return runCatching { JSONObject(decrypted) }.getOrNull()
    }

    private fun cleanEscaped(value: String): String {
        return value
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
            app.post("$mainUrl/api/bg/searchContent?searchterm=$encoded", headers = headers)
        }.getOrNull() ?: return emptyList()

        if (res.code != 200) return emptyList()
        val json = runCatching { JSONObject(res.text) }.getOrNull() ?: return emptyList()
        if (!json.optBoolean("success")) return emptyList()

        val parsed = decryptedJson(json.optString("response")) ?: return emptyList()
        val results = parsed.optJSONArray("result") ?: return emptyList()
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
        val res = runCatching { app.get(url, headers = headers) }.getOrNull() ?: return null
        if (res.code != 200) return null

        val nextData = Jsoup.parse(res.text).selectFirst("script#__NEXT_DATA__")?.data()
            ?: return null
        val json = runCatching { JSONObject(nextData) }.getOrNull() ?: return null
        val secureData = json.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optString("secureData")
            .orEmpty()
        val parsed = decryptedJson(secureData) ?: return null

        val contentItem = parsed.optJSONObject("contentItem")
        val title = contentItem?.optString("used_title")
            ?.ifBlank { contentItem.optString("original_title", "Series") }
            ?: "Series"

        val seasons = parsed.optJSONObject("RelatedResults")
            ?.optJSONObject("getSerieSeasonAndEpisodes")
            ?.optJSONArray("result")
            ?: return SeriesInfo(title, emptyList())

        val episodes = mutableListOf<EpisodeData>()
        for (i in 0 until seasons.length()) {
            val seasonItem = seasons.optJSONObject(i) ?: continue
            val seasonNo = seasonItem.optInt("season_no", -1)
            val eps = seasonItem.optJSONArray("episodes") ?: continue
            for (j in 0 until eps.length()) {
                val ep = eps.optJSONObject(j) ?: continue
                val epNo = ep.optInt("episode_no", -1)
                val epSlug = ep.optString("used_slug").ifBlank { ep.optString("episode_slug") }
                val epUrl = fixUrl(epSlug) ?: continue
                if (seasonNo > 0 && epNo > 0) {
                    episodes.add(EpisodeData(seasonNo, epNo, epUrl))
                }
            }
        }

        return SeriesInfo(title, episodes)
    }

    private suspend fun getVideoLinks(epUrl: String): List<SourceData> {
        val res = runCatching { app.get(epUrl, headers = headers) }.getOrNull() ?: return emptyList()
        if (res.code != 200) return emptyList()

        val nextData = Jsoup.parse(res.text).selectFirst("script#__NEXT_DATA__")?.data()
            ?: return emptyList()
        val json = runCatching { JSONObject(nextData) }.getOrNull() ?: return emptyList()
        val secureData = json.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optString("secureData")
            .orEmpty()
        val parsed = decryptedJson(secureData) ?: return emptyList()

        val sources = parsed.optJSONObject("RelatedResults")
            ?.optJSONObject("getEpisodeSources")
            ?.optJSONArray("result")
            ?: return emptyList()

        val iframeRegex = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return (0 until sources.length()).mapNotNull { index ->
            val item = sources.optJSONObject(index) ?: return@mapNotNull null
            val iframe = iframeRegex.find(item.optString("source_content"))
                ?.groupValues
                ?.getOrNull(1)
            val iframeUrl = fixUrl(iframe) ?: return@mapNotNull null
            val name = item.optString("source_name", "Source")
            val lang = item.optString("language_name")
            val quality = item.optString("quality_name")
            val fullName = listOf(name, "($lang $quality)".trim())
                .joinToString(" ")
                .trim()
            SourceData(fullName, iframeUrl)
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
        val vId = Regex("""window\.openPlayer\('([^']+)'""").find(text)?.groupValues?.getOrNull(1)
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
            results.add(HlsData("Master HLS", masterUrl, subtitles))
        }

        val dubMatch = Regex("""["']([^"']+)["'],["'](Türkçe|TÃ¼rkÃ§e)["']""")
            .find(text)
        val dubId = dubMatch?.groupValues?.getOrNull(1)
        val dubUrl = dubId?.let { loadSource2(baseUrl, iframeUrl, it) }
        if (!dubUrl.isNullOrBlank() && dubUrl != masterUrl) {
            results.add(HlsData("Turkce Dublaj HLS", dubUrl, emptyList()))
        }

        return results
    }

    private suspend fun loadSource2(baseUrl: String, referer: String, videoId: String): String? {
        val res = runCatching {
            app.get(
                "$baseUrl/source2.php?v=$videoId",
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

        val links = getVideoLinks(target.url)
        var found = false

        for (source in links) {
            val isContentx = listOf("pichive", "picholes", "contentx", "dplayer", "four.pichive")
                .any { source.url.contains(it, ignoreCase = true) }

            if (isContentx) {
                val hlsLinks = extractContentx(source.url)
                for (hls in hlsLinks) {
                    val subHeaders = mapOf(
                        "Referer" to source.url,
                        "User-Agent" to headers.getValue("User-Agent")
                    )
                    hls.subtitles.forEach { sub ->
                        subtitleCallback(SubtitleFile(sub.name, sub.url).apply { headers = subHeaders })
                    }

                    callback(
                        newExtractorLink(
                            source = "Crocodile",
                            name = "Crocodile ${source.name} - ${hls.label}",
                            url = hls.url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = source.url
                            this.quality = Qualities.Unknown.value
                            this.headers = subHeaders
                        }
                    )
                    found = true
                }
            } else {
                val loaded = runCatching {
                    loadExtractor(source.url, mainUrl, subtitleCallback, callback)
                }.getOrDefault(false)
                if (loaded) found = true
            }
        }

        return found
    }
}
