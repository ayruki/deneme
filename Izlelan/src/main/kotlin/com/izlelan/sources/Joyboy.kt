package com.izlelan.sources

import com.izlelan.IzlelanProvider
import com.izlelan.BaseUrls

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.Jsoup
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.net.URL

object Joyboy {
    private val mainUrl = BaseUrls.get("joyboy", "https://japierdolevid.com")

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private fun rh(length: Int): String {
        val chars = "0123456789abcdef"
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun generateGfp(url: String): Map<String, Any> {
        val ref = Base64.encodeToString(url.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val refSlice = ref.substring(0, minOf(ref.length, (20..40).random()))
        val sig = rh(64)
        return mapOf(
            "_sig" to sig,
            "_nonce" to rh(32),
            "_ts2" to System.currentTimeMillis() - (100..500).random(),
            "_v" to "2.${(1..9).random()}.${(0..99).random()}",
            "_sid" to rh(24),
            "_ref" to refSlice,
            "_enc" to listOf("rsa4096", "ed25519", "x25519", "secp384r1").random(),
            "_mode" to listOf("ecb", "ofb", "cfb8", "xts").random(),
            "_iter" to (1000..5000).random(),
            "_salt" to rh(16),
            "_hmac" to rh(40),
            "_pad" to listOf("ansix923", "iso7816", "zero", "none").random(),
            "_cmp" to listOf("gzip", "none").random(),
            "_fmt" to (1..3).random(),
            "_fl" to (0..7).random(),
            "_x" to sig.substring(0, 8)
        )
    }

    private fun generateGbs(): Map<String, Any> {
        return mapOf(
            "ts" to System.currentTimeMillis(),
            "sw" to 1920,
            "sh" to 1080,
            "plt" to (50..250).random(),
            "tz" to -180,
            "lang" to "tr-TR",
            "pl" to "Win32",
            "ct" to 8,
            "dm" to 24,
            "td" to 0,
            "cv" to 1,
            "wg" to 1
        )
    }

    private fun xd(enc: String, key: String): String {
        val r = java.lang.StringBuilder()
        for (i in enc.indices) {
            val encChar = enc[i].toString().toInt(16)
            val keyChar = key[i % key.length].toString().toInt(16)
            val xorRes = encChar xor keyChar
            r.append(xorRes.toString(16))
        }
        return r.toString()
    }

    private fun decryptAesCbc(ciphertextB64: String, keyBytes: ByteArray, ivBytes: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decoded = Base64.decode(ciphertextB64, Base64.DEFAULT)
            cipher.doFinal(decoded).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptAesCbcHex(ciphertextB64: String, keyHex: String, ivHex: String): String? {
        return decryptAesCbc(ciphertextB64, decodeHex(keyHex), decodeHex(ivHex))
    }

    private fun decodeHex(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun getCr(scriptText: String): String? {
        val atobMatches = Regex("""atob\(\s*['"]([^'"]+)['"]\s*\)""").findAll(scriptText).toList()
        if (atobMatches.size >= 2) {
            val part1 = String(Base64.decode(atobMatches[0].groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
            val part2 = String(Base64.decode(atobMatches[1].groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
            return part1 + part2
        }

        val arrayMatches = Regex("""=\s*\[([0-9,\s]+)\]""").findAll(scriptText).toList()
        if (arrayMatches.size >= 2) {
            val arr1 = arrayMatches[0].groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
            val arr2 = arrayMatches[1].groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
            if (arr1.isNotEmpty() && arr2.isNotEmpty()) {
                val sb = StringBuilder()
                for (i in arr1.indices) {
                    val xor = arr1[i] xor arr2[i % arr2.size]
                    sb.append(xor.toChar())
                }
                return sb.toString()
            }
        }
        return null
    }

    private fun getVar(html: String, name: String): String? {
        return Regex("""(?:var|let|const)?\s*$name\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""$name\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
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
        val isMovie = type == "movie"
        val idParam = if (imdbIdParam != null && imdbIdParam.startsWith("tt")) "imdb" else "tmdb"
        val idVal = if (idParam == "imdb") imdbIdParam else id.toString()

        val embedUrl = if (isMovie) {
            "$mainUrl/embed/movie?$idParam=$idVal&ds_lang=tr"
        } else {
            if (season == null || episode == null) return false
            "$mainUrl/embed/tv?$idParam=$idVal&season=$season&episode=$episode&ds_lang=tr"
        }

        val embedRes = runCatching {
            app.get(embedUrl, headers = mapOf("Referer" to "https://japierdolevid.com/", "User-Agent" to headers.getValue("User-Agent")))
        }.getOrNull() ?: return false

        if (embedRes.code != 200) return false
        val html = embedRes.text

        // Search for the script containing _ept
        val scriptText = Regex("""<script[^>]*>([\s\S]*?)</script>""").findAll(html)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("_ept") } ?: return false

        val cr = getCr(scriptText) ?: return false
        val ept = getVar(scriptText, "_ept") ?: return false
        val ws = getVar(scriptText, "_ws") ?: return false

        val pt = xd(ept, cr)
        val digestWcr = MessageDigest.getInstance("SHA-256")
        val wcrFull = digestWcr.digest((ws + cr).toByteArray(Charsets.UTF_8)).joinToString("") { String.format("%02x", it) }
        val wcr = wcrFull.substring(0, 16)

        val finalUrl = embedRes.url
        val fp = generateGfp(finalUrl)
        val bs = generateGbs()

        val postData = mutableMapOf<String, Any>()
        postData.putAll(fp)
        postData.put("pt", pt)
        postData.put("cr", cr)
        postData.put("wc", wcr)
        postData.put("bs", bs)
        postData.put("_x", (fp.get("_sig") as String).substring(0, 8))

        val parsedUrl = URL(finalUrl)
        val streamUrl = "${parsedUrl.protocol}://${parsedUrl.authority}${parsedUrl.path}/stream"

        val streamHeaders = mapOf(
            "User-Agent" to headers.getValue("User-Agent"),
            "Referer" to finalUrl,
            "Content-Type" to "application/json",
            "X-Requested-With" to "XMLHttpRequest"
        )

        // Sleep for 1.5 seconds as in python
        kotlinx.coroutines.delay(1500)

        val sr = runCatching {
            app.post(streamUrl, headers = streamHeaders, json = postData)
        }.getOrNull() ?: return false

        if (sr.code != 200) return false
        val resJson = JSONObject(sr.text)

        val s = resJson.optString("s")
        val d = resJson.optString("d")
        val x = resJson.optString("x")
        val p1 = resJson.optString("p1")
        val p2 = resJson.optString("p2")
        val p3 = resJson.optString("p3")
        val p4 = resJson.optString("p4")
        val v = resJson.optString("v")

        if (s.isBlank() || d.isBlank() || x.isBlank()) return false

        val seed = p1 + p2 + p3 + p4
        val rawKeyMaterial = (seed + cr + x).toByteArray(Charsets.UTF_8)
        val derivedKey = MessageDigest.getInstance("SHA-256").digest(rawKeyMaterial)
        val ivBytes = decodeHex(v)

        val payload = decryptAesCbc(d, derivedKey, ivBytes) ?: return false
        val payloadJson = JSONObject(payload)

        var htmlContent: String? = null
        if (payloadJson.has("encrypted_player_data")) {
            val epd = payloadJson.optJSONObject("encrypted_player_data") ?: return false
            val k1 = epd.optString("k1")
            val k2 = epd.optString("k2")
            val k3 = epd.optString("k3")
            val k4 = epd.optString("k4")
            val ct = epd.optString("ct")
            val iv = epd.optString("iv")
            val keyHex = k1 + k2 + k3 + k4
            htmlContent = decryptAesCbcHex(ct, keyHex, iv)
        } else if (payloadJson.has("processed_template")) {
            htmlContent = payloadJson.optString("processed_template")
        }

        if (htmlContent.isNullOrBlank()) return false

        val hlsMatch = Regex("""video(?:Hls|Src)\s*:\s*['"]([^'"]+)['"]""").find(htmlContent)?.groupValues?.get(1)
        val subtitles = mutableListOf<SubtitleData>()

        val subMatch = Regex("""subtitles\s*:\s*(\[[\s\S]*?\])\s*,?\s*audioVariants""").find(htmlContent)?.groupValues?.get(1)
        if (subMatch != null) {
            runCatching {
                val cleanedSubMatch = subMatch.replace("\\/", "/")
                val subArray = JSONArray(cleanedSubMatch)
                for (i in 0 until subArray.length()) {
                    val subObj = subArray.getJSONObject(i)
                    val src = subObj.optString("src").replace("\\/", "/")
                    val label = subObj.optString("label", "Unknown")
                    val lang = subObj.optString("lang", "tr")
                    if (src.isNotBlank()) {
                        subtitles.add(SubtitleData(label, src))
                    }
                }
            }
        }

        if (hlsMatch.isNullOrBlank()) return false

        subtitles.forEach { sub ->
            subtitleCallback(SubtitleFile(sub.name, sub.url))
        }

        callback(
            ExtractorLink(
                source = "🇹🇷 Joyboy",
                name = "🇹🇷 Joyboy",
                url = hlsMatch,
                referer = "https://japierdolevid.com/",
                quality = Qualities.Unknown.value,
                headers = mapOf(
                    "Referer" to "https://japierdolevid.com/",
                    "User-Agent" to USER_AGENT,
                    "Origin" to "https://japierdolevid.com"
                ),
                type = ExtractorLinkType.M3U8
            )
        )

        return true
    }

    private data class SubtitleData(val name: String, val url: String)
}
