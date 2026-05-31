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
        // Strip BOM if present
        val cleanBOM = srtContent.replace("\uFEFF", "").replace("\uEFBBBF", "")
        val cleaned = cleanBOM.replace("\r\n", "\n").replace("\r", "\n")
        
        val vttLines = cleaned.split("\n").map { line ->
            if (line.contains("-->")) {
                // Replace commas with dots in timestamp lines for WebVTT compliance
                line.replace(",", ".")
            } else {
                line
            }
        }
        
        return "WEBVTT\n\n" + vttLines.joinToString("\n")
    }

    private fun decodeContent(bytes: ByteArray): String {
        // Try UTF-8 first (no replacement characters)
        try {
            val decoded = String(bytes, Charsets.UTF_8)
            if (decoded.contains("-->") && !decoded.contains("\uFFFD")) {
                return decoded
            }
        } catch (e: Exception) {}

        // Fallback to Windows-1254 (Turkish) which is the default for turkcealtyazi.org
        try {
            val decoded = String(bytes, charset("windows-1254"))
            if (decoded.contains("-->")) {
                return decoded
            }
        } catch (e: Exception) {}

        // Fallback to ISO-8859-9
        try {
            val decoded = String(bytes, charset("ISO-8859-9"))
            if (decoded.contains("-->")) {
                return decoded
            }
        } catch (e: Exception) {}

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

            // Filter by season and episode FIRST, then take the top 8 matching subtitles to avoid overloading
            val filteredRows = rows.mapNotNull { row ->
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
            }.take(8)

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

                if (zipRes.code != 200) continue
                val zipBytes = zipRes.okhttpResponse.body?.bytes() ?: continue

                // 4. Read all .srt files from Zip in memory
                val zipInput = ZipInputStream(ByteArrayInputStream(zipBytes))
                var entry = zipInput.nextEntry
                val srtFiles = mutableListOf<Pair<String, ByteArray>>()

                while (entry != null) {
                    val name = entry.name
                    val lowerName = name.lowercase()
                    val isSrt = lowerName.endsWith(".srt")
                    val isMac = lowerName.startsWith("__macosx") || lowerName.contains("/.")

                    if (isSrt && !isMac) {
                        val outStream = ByteArrayOutputStream()
                        zipInput.copyTo(outStream)
                        srtFiles.add(name to outStream.toByteArray())
                    }
                    entry = zipInput.nextEntry
                }
                zipInput.close()

                if (srtFiles.isEmpty()) continue

                var subBytes: ByteArray? = null

                // If it is a movie, or if there is only 1 srt file, use it!
                if (season == null || episode == null || srtFiles.size == 1) {
                    subBytes = srtFiles.first().second
                } else {
                    // Match for TV Series episode
                    val sPad = season.toString().padStart(2, '0')
                    val ePad2 = episode.toString().padStart(2, '0')
                    val ePad3 = episode.toString().padStart(3, '0')
                    
                    val priorityPatterns = listOf(
                        "S${sPad}E${ePad2}",
                        "S${season}E${episode}",
                        "S${sPad}E${ePad3}",
                        "${season}x${ePad2}",
                        "${season}x${ePad3}",
                        "${season}x${episode}"
                    )

                    // Pass 1: Strict S/E patterns
                    for (pattern in priorityPatterns) {
                        val match = srtFiles.find { it.first.contains(pattern, ignoreCase = true) }
                        if (match != null) {
                            subBytes = match.second
                            break
                        }
                    }

                    // Pass 2: Episode number matching (e.g. "bl 05", "bölüm 05", "ep 05", "- 05")
                    if (subBytes == null) {
                        val epPatterns = listOf(
                            "bl $ePad2", "bl $episode",
                            "bölüm $ePad2", "bölüm $episode",
                            "bolum $ePad2", "bolum $episode",
                            "ep $ePad2", "ep $episode",
                            "episode $ePad2", "episode $episode",
                            "-$ePad2", "-$episode",
                            "_$ePad2", "_$episode",
                            " $ePad2 ", " $episode "
                        )
                        for (pattern in epPatterns) {
                            val match = srtFiles.find { it.first.contains(pattern, ignoreCase = true) }
                            if (match != null) {
                                subBytes = match.second
                                break
                            }
                        }
                    }

                    // Pass 3: Index based matching (if srt files count is close or matches the episodes)
                    if (subBytes == null) {
                        // Sort alphabetically to align with episode order
                        val sortedSrts = srtFiles.sortedBy { it.first.lowercase() }
                        val idx = episode - 1
                        if (idx >= 0 && idx < sortedSrts.size) {
                            subBytes = sortedSrts[idx].second
                        }
                    }
                }

                if (subBytes != null) {
                    val decodedSub = decodeContent(subBytes)
                    val vttContent = srtToVtt(decodedSub)
                    val base64Vtt = Base64.encodeToString(vttContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val dataUri = "data:text/vtt;base64,$base64Vtt"

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
