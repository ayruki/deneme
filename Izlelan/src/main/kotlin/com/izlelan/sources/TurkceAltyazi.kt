package com.izlelan.sources

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

object TurkceAltyazi {
    private const val mainUrl = "https://turkcealtyazi.org"
    private const val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/"
    )

    // Convert SRT subtitles to WebVTT format for Cloudstream player compatibility
    private fun srtToVtt(srtContent: String): String {
        val cleaned = srtContent.replace("\r\n", "\n").replace("\r", "\n")
        
        // Pattern to match timestamp lines e.g. "00:01:20,123 --> 00:01:23,456"
        val timestampRegex = Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}),(\d{3})""")
        
        val vttLines = cleaned.split("\n").map { line ->
            if (timestampRegex.containsMatchIn(line)) {
                // Replace comma with dot for WebVTT compliance
                line.replace(",", ".")
            } else {
                line
            }
        }
        
        return "WEBVTT\n\n" + vttLines.joinToString("\n")
    }

    private fun decodeContent(bytes: ByteArray): String {
        // Try UTF-8 with BOM, then UTF-8, then Windows-1254 (Turkish), then ISO-8859-9
        val encodings = listOf("UTF-8", "windows-1254", "ISO-8859-9", "UTF-16")
        for (enc in encodings) {
            try {
                val decoded = String(bytes, charset(enc))
                if (decoded.contains("-->")) {
                    return decoded
                }
            } catch (e: Exception) {
                // Try next encoding
            }
        }
        return String(bytes, Charsets.UTF_8)
    }

    suspend fun invoke(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        if (imdbId.isNullOrBlank()) return false

        try {
            // 1. Fetch search page on turkcealtyazi.org
            val searchUrl = "$mainUrl/$imdbId"
            val res = app.get(searchUrl, headers = headers)
            if (res.code != 200) return false

            val doc = Jsoup.parse(res.text)
            val rows = doc.select(".altsonsez1, .altsonsez2")
            if (rows.isEmpty()) return false

            var foundAny = false

            // Limit to top 5 subtitles to avoid network overload
            val filteredRows = rows.take(8).mapNotNull { row ->
                val langSpan = row.selectFirst(".aldil span")
                val isTr = langSpan?.className()?.contains("flagtr", ignoreCase = true) == true
                if (!isTr) return@mapNotNull null

                val linkTag = row.selectFirst(".alisim a.underline") ?: return@mapNotNull null
                val title = linkTag.text().trim()
                val subUrl = mainUrl + linkTag.attr("href")

                val alcd = row.selectFirst(".alcd")
                var rowSeason: Int? = null
                var rowEpisode: Int? = null
                var isPackage = false

                if (alcd != null) {
                    val alcdText = alcd.text().trim()
                    isPackage = alcdText.contains("Paket", ignoreCase = true)

                    val sMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(alcdText)
                    rowSeason = sMatch?.groupValues?.get(1)?.toIntOrNull()

                    val eMatch = Regex("""E(\d+)""", RegexOption.IGNORE_CASE).find(alcdText)
                    rowEpisode = eMatch?.groupValues?.get(1)?.toIntOrNull()
                }

                // If TV show, match season
                if (season != null && rowSeason != null && rowSeason != season) {
                    return@mapNotNull null
                }
                
                // Match episode if it's not a package
                if (episode != null && !isPackage && rowEpisode != null && rowEpisode != episode) {
                    return@mapNotNull null
                }

                val cevirmen = row.selectFirst(".alcevirmen")?.text()?.trim() ?: ""
                val rip = row.selectFirst(".ripdiv")?.text()?.trim() ?: ""
                
                val label = listOfNotNull(
                    "TR", 
                    rip.takeIf { it.isNotBlank() }, 
                    cevirmen.takeIf { it.isNotBlank() }
                ).joinToString(" - ")

                Triple(subUrl, label, isPackage)
            }

            for ((subPageUrl, label, isPackage) in filteredRows) {
                // 2. Fetch subtitle download page
                val subPageRes = app.get(subPageUrl, headers = headers)
                if (subPageRes.code != 200) continue

                val subDoc = Jsoup.parse(subPageRes.text)
                val form = subDoc.selectFirst("form[action=/ind]") ?: continue

                val payload = mutableMapOf<String, String>()
                form.select("input").forEach { input ->
                    val name = input.attr("name")
                    val value = input.attr("value")
                    if (name.isNotBlank()) {
                        payload[name] = value
                    }
                }

                // 3. POST to download zip
                val zipRes = app.post(
                    "$mainUrl/ind",
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to subPageUrl,
                        "Origin" to mainUrl,
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    data = payload
                )

                if (zipRes.code != 200 || zipRes.document.body() == null) continue
                val zipBytes = zipRes.bytes

                // 4. Extract subtitle from Zip
                val zipInput = ZipInputStream(ByteArrayInputStream(zipBytes))
                var entry = zipInput.nextEntry
                var subBytes: ByteArray? = null
                var subFileName = ""

                val sPad = (season ?: 1).toString().padStart(2, '0')
                val ePad = (episode ?: 1).toString().padStart(2, '0')
                val sPattern = "S$sPad"
                val ePattern = "E$ePad"

                while (entry != null) {
                    val name = entry.name.lowercase()
                    val isSubtitle = name.endsWith(".srt") || name.endsWith(".txt")
                    val isMac = name.startsWith("__macosx")

                    if (isSubtitle && !isMac) {
                        val outStream = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var len = zipInput.read(buffer)
                        while (len > 0) {
                            outStream.write(buffer, 0, len)
                            len = zipInput.read(buffer)
                        }
                        
                        val entryBytes = outStream.toByteArray()
                        
                        // Smart matching for series episodes
                        if (season != null && episode != null) {
                            if (name.contains(sPattern, ignoreCase = true) && name.contains(ePattern, ignoreCase = true)) {
                                subBytes = entryBytes
                                subFileName = entry.name
                                break
                            } else if (name.contains("${season}x$ePad", ignoreCase = true)) {
                                subBytes = entryBytes
                                subFileName = entry.name
                                break
                            }
                        }
                        
                        // Default fallback
                        subBytes = entryBytes
                        subFileName = entry.name
                    }
                    entry = zipInput.nextEntry
                }
                zipInput.close()

                if (subBytes != null) {
                    val decodedSub = decodeContent(subBytes)
                    val vttContent = srtToVtt(decodedSub)
                    val base64Vtt = Base64.encodeToString(vttContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val dataUri = "data:text/vtt;charset=utf-8;base64,$base64Vtt"

                    subtitleCallback(
                        newSubtitleFile(
                            label,
                            dataUri
                        )
                    )
                    foundAny = true
                }
            }

            return foundAny
        } catch (e: Exception) {
            // Silently fail
        }

        return false
    }
}
