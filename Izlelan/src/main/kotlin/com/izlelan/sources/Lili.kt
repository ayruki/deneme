package com.izlelan.sources

import com.izlelan.BaseUrls
import com.izlelan.IzlelanProvider
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Lili {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"

    private fun cleanTitle(title: String?): String {
        if (title.isNullOrEmpty()) return ""
        var t = title.lowercase()
        val replacements = mapOf(
            'ı' to 'i', 'ş' to 's', 'ğ' to 'g', 'ö' to 'o', 'ü' to 'u', 'ç' to 'c',
            'â' to 'a', 'î' to 'i', 'û' to 'u'
        )
        for ((search, replace) in replacements) {
            t = t.replace(search, replace)
        }
        t = Regex("[^a-z0-9\\s]").replace(t, "")
        t = Regex("\\s+").replace(t, " ").trim()
        return t
    }

    private fun evpKdf(password: ByteArray, salt: ByteArray, keySize: Int = 32, ivSize: Int = 16): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val dTot = java.io.ByteArrayOutputStream()
        var d = ByteArray(0)
        while (dTot.size() < keySize + ivSize) {
            md.reset()
            md.update(d)
            md.update(password)
            md.update(salt)
            d = md.digest()
            dTot.write(d)
        }
        val keyAndIv = dTot.toByteArray()
        val key = keyAndIv.copyOfRange(0, keySize)
        val iv = keyAndIv.copyOfRange(keySize, keySize + ivSize)
        return Pair(key, iv)
    }

    private fun decryptAesCryptoJS(encryptedB64: String, password: String): String? {
        return try {
            val encrypted = Base64.decode(encryptedB64, Base64.DEFAULT)
            val salt: ByteArray
            val ciphertext: ByteArray
            if (encrypted.size >= 16 && encrypted.copyOfRange(0, 8).contentEquals("Salted__".toByteArray(Charsets.UTF_8))) {
                salt = encrypted.copyOfRange(8, 16)
                ciphertext = encrypted.copyOfRange(16, encrypted.size)
            } else {
                salt = ByteArray(0)
                ciphertext = encrypted
            }
            val (key, iv) = evpKdf(password.toByteArray(Charsets.UTF_8), salt)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun run59d4acac(s: String): String {
        var rev = s.reversed()
        while (rev.length % 4 != 0) {
            rev += "="
        }
        return String(Base64.decode(rev, Base64.DEFAULT), Charsets.UTF_8)
    }

    private data class Candidate(
        val url: String,
        val title: String,
        val score: Int
    )

    suspend fun invoke(
        id: Int,
        type: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbType = if (type == "movie") "movie" else "tv"
        var queryTitle = ""
        val languages = listOf("tr-TR", "en-US")
        for (lang in languages) {
            val tmdbUrl = "https://api.themoviedb.org/3/$tmdbType/$id?api_key=$tmdbApiKey&language=$lang"
            val tmdbResp = runCatching { app.get(tmdbUrl).text }.getOrNull() ?: continue
            val tmdbJson = runCatching { JSONObject(tmdbResp) }.getOrNull() ?: continue
            val fetchedTitle = if (type == "movie") {
                tmdbJson.optString("title").ifBlank { tmdbJson.optString("original_title") }
            } else {
                tmdbJson.optString("name").ifBlank { tmdbJson.optString("original_name") }
            }
            if (fetchedTitle.isNotBlank()) {
                queryTitle = fetchedTitle
                break
            }
        }
        if (queryTitle.isBlank()) return false

        val base = BaseUrls.get("lili", "https://dizi64.life")
        val searchUrl = "$base/ara?q=${java.net.URLEncoder.encode(queryTitle, "UTF-8")}"
        val searchHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$base/"
        )
        val searchResponse = runCatching { app.get(searchUrl, headers = searchHeaders).text }.getOrNull() ?: return false
        val soup = Jsoup.parse(searchResponse)
        
        val isTv = type != "movie"
        val normalizedQuery = cleanTitle(queryTitle)
        val candidates = mutableListOf<Candidate>()
        
        for (link in soup.select("a[href]")) {
            val href = link.attr("href")
            var isMatchType = false
            if (isTv && href.contains("/dizi/") && !href.contains("/sezon/")) {
                isMatchType = true
            } else if (!isTv && href.contains("/film/")) {
                isMatchType = true
            }
            
            if (isMatchType) {
                val img = link.selectFirst("img")
                val title = if (img != null && img.hasAttr("alt")) {
                    img.attr("alt")
                } else {
                    link.text()
                }
                
                val normalizedTitle = cleanTitle(title)
                if (normalizedTitle.isNotEmpty()) {
                    var score = 0
                    if (normalizedTitle == normalizedQuery) {
                        score = 100
                    } else if (normalizedTitle.contains(normalizedQuery) || normalizedQuery.contains(normalizedTitle)) {
                        score = 80
                    } else {
                        val queryWords = normalizedQuery.split(" ").filter { it.isNotBlank() }.toSet()
                        val titleWords = normalizedTitle.split(" ").filter { it.isNotBlank() }.toSet()
                        val common = queryWords.intersect(titleWords)
                        if (common.isNotEmpty()) {
                            score = ((common.size.toDouble() / maxOf(queryWords.size, titleWords.size).toDouble()) * 70).toInt()
                        }
                    }
                    if (score > 0) {
                        val fullUrl = if (href.startsWith("http")) href else "$base${if (href.startsWith("/")) "" else "/"}$href"
                        candidates.add(Candidate(fullUrl, title, score))
                    }
                }
            }
        }
        
        if (candidates.isEmpty()) return false
        
        candidates.sortByDescending { it.score }
        val matchedUrl = candidates[0].url

        val pageUrl = if (isTv) {
            val seriesSlug = matchedUrl.trimEnd('/').split('/').lastOrNull() ?: return false
            "$base/dizi/$seriesSlug/sezon/$season/bolum/$episode"
        } else {
            matchedUrl
        }
        
        val pageResponse = runCatching { app.get(pageUrl, headers = searchHeaders).text }.getOrNull() ?: return false
        val pageSoup = Jsoup.parse(pageResponse)
        
        var playerLink: String? = null
        for (iframe in pageSoup.select("iframe")) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotEmpty()) {
                if (src.contains("player") || src.startsWith("http") || src.startsWith("//")) {
                    playerLink = src
                    break
                }
            }
        }
        if (playerLink.isNullOrEmpty()) return false

        val finalPlayerLink = if (playerLink.startsWith("//")) "https:$playerLink" else playerLink
        val playerHtml = runCatching { app.get(finalPlayerLink, headers = searchHeaders).text }.getOrNull() ?: return false
        
        val funcMatch = Regex("""var\s+([a-zA-Z0-9_]+)\s*=\s*function\(s\)\{\s*return\s*atob""").find(playerHtml)
        val funcName = funcMatch?.groupValues?.get(1) ?: "_59d4acac"

        val encArgs = Regex("""=\s*${funcName}\(\"([^\"]+)\"\)""").findAll(playerHtml).map { it.groupValues[1] }.toList()

        var decrypted: String? = null

        if (encArgs.size >= 2) {
            val encryptedData = run59d4acac(encArgs[0])
            val key = run59d4acac(encArgs[1])
            decrypted = decryptAesCryptoJS(encryptedData, key)

            val replMatch = Regex("""=\s*\[([^\]]+)\]\.join\(\'\'\)""").find(playerHtml)
            if (replMatch != null && decrypted != null) {
                val replContent = replMatch.groupValues[1]
                val replList = replContent.split(",").map { it.trim().trim('"', '\'') }
                val replaceStr = replList.joinToString("")
                decrypted = decrypted.replace(replaceStr, "")
            }
        } else {
            val match = Regex("""C\.A\.dct\("([^"]+)",\s*"([^"]+)"\)""").find(playerHtml)
            if (match != null) {
                val encryptedData = match.groupValues[1]
                val key = match.groupValues[2]

                val replaceMatch = Regex("""\.replace\('([^']*)',\s*'([^']*)'\)""").find(playerHtml)
                val replaceFrom = replaceMatch?.groupValues?.get(1) ?: ""
                val replaceTo = replaceMatch?.groupValues?.get(2) ?: ""

                decrypted = decryptAesCryptoJS(encryptedData, key)
                if (decrypted != null && replaceFrom.isNotEmpty()) {
                    decrypted = decrypted.replace(replaceFrom, replaceTo)
                }
            }
        }

        if (decrypted.isNullOrEmpty()) return false

        var fileMatch = Regex("""file:\s*["']([^"']+)["']""").find(decrypted)?.groupValues?.get(1)
        if (fileMatch.isNullOrEmpty()) {
            fileMatch = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(decrypted)?.groupValues?.get(1)
        }

        if (fileMatch.isNullOrEmpty()) return false
        val hlsUrl = fileMatch.replace("\\/", "/")

        callback(
            newExtractorLink(
                source = "🇹🇷 Lili",
                name = "🇹🇷 Lili",
                url = hlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
