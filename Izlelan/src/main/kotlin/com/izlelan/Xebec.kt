package com.izlelan

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

object Xebec {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"
    private val mainUrl = BaseUrls.get("xebec", "https://www.fullhdfilmizlesene.life")

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 OPR/130.0.0.0 (Edition std-2)",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en,tr;q=0.9,zh-CN;q=0.8,zh;q=0.7,fr;q=0.6,es;q=0.5,ja;q=0.4,ru;q=0.3,ko;q=0.2,pt-BR;q=0.1,pt;q=0.1,pl;q=0.1",
        "Cookie" to "fullhd_source=atom; fullhd_sourceType=t"
    )

    private fun rot13(str: String): String {
        val sb = StringBuilder()
        for (c in str) {
            when (c) {
                in 'a'..'z' -> sb.append(((c.code - 'a'.code + 13) % 26 + 'a'.code).toChar())
                in 'A'..'Z' -> sb.append(((c.code - 'A'.code + 13) % 26 + 'A'.code).toChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun atob(str: String): String {
        return try {
            val cleaned = str.replace(Regex("""[\s\n\r]"""), "")
                .replace("-", "+")
                .replace("_", "/")
            val decoded = Base64.decode(cleaned, Base64.DEFAULT)
            String(decoded, Charsets.ISO_8859_1) // ISO_8859_1 is Latin1
        } catch (e: Exception) {
            ""
        }
    }

    private fun decodeHex(hex: String): String? {
        return try {
            val sb = StringBuilder()
            var i = 0
            while (i < hex.length) {
                val str = hex.substring(i, i + 2)
                sb.append(str.toInt(16).toChar())
                i += 2
            }
            val result = sb.toString()
            if (result.contains("http")) result else null
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeRapidVid(encodedString: String): String? {
        return try {
            val reversed = encodedString.reversed()
            val firstDecode = Base64.decode(reversed, Base64.DEFAULT)
            val key = "K9L"
            val sb = java.lang.StringBuilder()
            for (i in firstDecode.indices) {
                val keyChar = key[i % 3]
                val charCode = firstDecode[i].toInt() and 0xFF
                val keyOffset = (keyChar.code % 5) + 1
                val newCharCode = charCode - keyOffset
                sb.append(newCharCode.toChar())
            }
            val finalBytes = Base64.decode(sb.toString(), Base64.DEFAULT)
            val finalResult = String(finalBytes, Charsets.UTF_8)
            if (finalResult.isBlank()) null else finalResult
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getImdbId(id: Int, imdbIdParam: String?, type: String): String? {
        if (!imdbIdParam.isNullOrBlank()) return imdbIdParam
        val extUrl = "https://api.themoviedb.org/3/$type/$id/external_ids?api_key=$tmdbApiKey"
        val extRes = runCatching { app.get(extUrl).parsedSafe<IzlelanProvider.ExternalIds>() }.getOrNull()
        return extRes?.imdb_id
    }

    private suspend fun extractM3U8FromRapidVid(
        rapidvidUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String? {
        // Bugfix: Do NOT append &c=1 or ?c=1 to the URL, as it returns an empty body!
        val page = runCatching {
            app.get(
                rapidvidUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 OPR/130.0.0.0 (Edition std-2)",
                    "Referer" to referer,
                    "Origin" to mainUrl
                )
            )
        }.getOrNull() ?: return null

        if (page.code != 200) return null
        val html = page.text

        // Extract subtitles
        val cleanedHtmlForSubs = html.replace("\\/", "/")
        val subRegex = Regex("""captions"\s*,\s*"file"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"""")
        subRegex.findAll(cleanedHtmlForSubs).forEach { match ->
            val subUrl = match.groupValues[1]
            val subLabel = match.groupValues[2]
            subtitleCallback(SubtitleFile(subLabel, subUrl).apply {
                this.headers = mapOf("Referer" to "https://rapidvid.net/", "User-Agent" to Xebec.headers.getValue("User-Agent"))
            })
        }

        if (cleanedHtmlForSubs.contains("thumbs.vtt").not()) {
            val genericSubRegex = Regex("""(https?://[^\s"'\\]+\.vtt[^\s"'\\]*)""", RegexOption.IGNORE_CASE)
            genericSubRegex.findAll(cleanedHtmlForSubs).forEach { match ->
                val subUrl = match.groupValues[1]
                if (!subUrl.contains("thumbs.vtt", ignoreCase = true)) {
                    subtitleCallback(SubtitleFile("Subtitle", subUrl).apply {
                        this.headers = mapOf("Referer" to "https://rapidvid.net/", "User-Agent" to Xebec.headers.getValue("User-Agent"))
                    })
                }
            }
        }

        // Try av() or _() pattern
        val avMatch = Regex("""av\s*\(\s*['"]([^'"]+)['"]\s*\)""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""_\s*\(\s*['"]([^'"]+)['"]\s*\)""").find(html)?.groupValues?.getOrNull(1)

        if (!avMatch.isNullOrBlank()) {
            val decoded = decodeRapidVid(avMatch)
            if (!decoded.isNullOrBlank()) return decoded
        }

        // Try Hex pattern
        val hexMatch = Regex("""file"\s*:\s*"([a-f0-9]{40,})"""").find(html)?.groupValues?.getOrNull(1)
        if (!hexMatch.isNullOrBlank()) {
            val decoded = decodeHex(hexMatch)
            if (!decoded.isNullOrBlank()) return decoded
        }

        // Try direct file pattern
        val directMatch = Regex("""file\s*:\s*['"]([^'"]+\.(?:m3u8|vr1)[^'"]*)['"]""").find(html)?.groupValues?.getOrNull(1)
        if (!directMatch.isNullOrBlank()) return directMatch

        // Greedy fallback
        val greedyMatch = Regex("""https?://[^\s"'\\`]+\.(?:m3u8|vr1)(?:\?[^\s"'\\`]+)?""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(0)
        return greedyMatch
    }

    private suspend fun extractHlsFromTrPlayer(url: String): String? {
        val res = runCatching {
            app.get(url, headers = mapOf("User-Agent" to headers.getValue("User-Agent")))
        }.getOrNull() ?: return null
        if (res.code != 200) return null
        val html = res.text

        val videoMatch = Regex("""var\s+video\s*=\s*(\{[\s\S]*?\});""").find(html)?.groupValues?.getOrNull(1)
        if (!videoMatch.isNullOrBlank()) {
            val videoJson = runCatching { JSONObject(videoMatch) }.getOrNull()
            if (videoJson != null) {
                val md5 = videoJson.optString("md5")
                val id = videoJson.optString("id")
                val uid = videoJson.optString("uid").ifBlank { "8" }
                if (md5.isNotBlank() && id.isNotBlank()) {
                    return "https://watch.trplayer.com/m3u8/$uid/$md5/master.txt?s=1&id=$id&cache=1"
                }
            }
        }

        val directMatch = Regex("""/m3u8/(\d+)/([a-f0-9]{32})/master\.txt\?s=1&id=(\d+)""").find(html)
        if (directMatch != null) {
            val uid = directMatch.groupValues[1]
            val md5 = directMatch.groupValues[2]
            val id = directMatch.groupValues[3]
            return "https://watch.trplayer.com/m3u8/$uid/$md5/master.txt?s=1&id=$id&cache=1"
        }

        return null
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
        if (type != "movie") return false // FullHDFilmizlesene only has movies

        val imdbId = getImdbId(id, imdbIdParam, "movie") ?: return false
        val searchUrl = "$mainUrl/arama/$imdbId"

        val searchRes = runCatching {
            app.get(searchUrl, headers = headers)
        }.getOrNull() ?: return false
        if (searchRes.code != 200) return false

        val document = Jsoup.parse(searchRes.text)
        val matchedLink = document.selectFirst("a.tt")?.attr("href") ?: return false
        val movieLink = if (matchedLink.startsWith("/")) "$mainUrl$matchedLink" else matchedLink

        val moviePageRes = runCatching {
            app.get(movieLink, headers = headers)
        }.getOrNull() ?: return false
        if (moviePageRes.code != 200) return false

        val html = moviePageRes.text
        val scxMatch = Regex("""scx = (.*?);""").find(html)?.groupValues?.getOrNull(1) ?: return false
        val scxData = runCatching { JSONObject(scxMatch) }.getOrNull() ?: return false

        val keys = listOf("atom", "advid", "advidprox", "proton", "fast", "fastly", "tr", "en")
        var found = false

        val hlsDomains = listOf("picturebox.cloud", "imagebin.pics", "pixypost.art", "imageshub.pro", "rapidvid.net", "rapidvid.pro", "rapidimages.pro", "imgz.me", "pixtureup.org", "watch.trplayer.com")
        val isHlsUrl = { url: String ->
            url.contains(".m3u8") || url.contains("/m3u8/") || url.contains("master.txt") || url.endsWith("vr1") ||
                    (hlsDomains.any { url.contains(it) } && !url.contains("/watch/") && !url.contains("/play/") && !url.contains("/embed/") && !url.contains("/vod/") && !url.contains("/v/"))
        }

        for (key in keys) {
            val keyData = scxData.optJSONObject(key) ?: continue
            val sx = keyData.optJSONObject("sx") ?: continue
            val t = sx.opt("t") ?: continue

            val linkPairs = mutableListOf<Pair<String, String>>()
            when (t) {
                is JSONArray -> {
                    for (i in 0 until t.length()) {
                        t.optString(i)?.takeIf { it.isNotBlank() }?.let { linkPairs.add(it to "") }
                    }
                }
                is JSONObject -> {
                    for (k in t.keys()) {
                        t.optString(k)?.takeIf { it.isNotBlank() }?.let { linkPairs.add(it to k) }
                    }
                }
                else -> {
                    val raw = t.toString()
                    if (raw.isNotBlank()) linkPairs.add(raw to "")
                }
            }

            for ((rawLink, subKey) in linkPairs) {
                if (rawLink.isBlank()) continue
                val decodedLink = atob(rot13(rawLink))
                if (decodedLink.isBlank()) continue

                var label = when (key) {
                    "tr" -> "TR Dublaj"
                    "en" -> "TR Altyazı"
                    "atom" -> "Turbo (Atom)"
                    else -> key.uppercase()
                }

                var finalName = "Xebec ($label)"
                if (subKey.isNotBlank() && (key.equals("advid", true) || key.equals("advidprox", true))) {
                    val subLabel = when (subKey.lowercase()) {
                        "tr" -> "Dublaj"
                        "en" -> "Altyazı"
                        else -> subKey.uppercase()
                    }
                    finalName = "Xebec [$subLabel]"
                }

                var finalUrl = decodedLink
                val linkHeaders = mapOf(
                    "Referer" to movieLink,
                    "User-Agent" to headers.getValue("User-Agent")
                )

                if (decodedLink.contains("rapidvid.net") || decodedLink.contains("rapidvid.pro")) {
                    val m3u8 = extractM3U8FromRapidVid(decodedLink, movieLink, subtitleCallback)
                    if (!m3u8.isNullOrBlank()) {
                        finalUrl = m3u8
                    }
                } else if (decodedLink.contains("trplayer.com") || decodedLink.contains("turkeyplayer.com")) {
                    val m3u8 = extractHlsFromTrPlayer(decodedLink)
                    if (!m3u8.isNullOrBlank()) {
                        finalUrl = m3u8
                    }
                }

                if (isHlsUrl(finalUrl)) {
                    callback(
                        newExtractorLink(
                            source = "Xebec",
                            name = finalName,
                            url = finalUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://rapidvid.net/"
                            this.quality = Qualities.Unknown.value
                            this.headers = linkHeaders
                        }
                    )
                    found = true
                } else {
                    val loaded = runCatching {
                        loadExtractor(finalUrl, movieLink, subtitleCallback, callback)
                    }.getOrDefault(false)
                    if (loaded) found = true
                }
            }
        }

        return found
    }
}
