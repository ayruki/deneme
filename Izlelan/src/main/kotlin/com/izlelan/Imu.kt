package com.izlelan

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object Imu {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"

    private fun resolveUrl(base: String, relative: String): String {
        return if (relative.startsWith("http://") || relative.startsWith("https://")) {
            relative
        } else if (relative.startsWith("/")) {
            val u = java.net.URL(base)
            "${u.protocol}://${u.authority}$relative"
        } else {
            val baseDir = base.substring(0, base.lastIndexOf('/') + 1)
            baseDir + relative
        }
    }

    private suspend fun mergeSubtitleSegments(
        playlistUrl: String,
        headers: Map<String, String>
    ): String? {
        val playlistResponse = runCatching {
            app.get(playlistUrl, headers = headers)
        }.getOrNull() ?: return null

        if (playlistResponse.code != 200) return null

        val lines = playlistResponse.text.split("\n")
        val segmentUrls = mutableListOf<String>()

        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                segmentUrls.add(resolveUrl(playlistUrl, trimmed))
            }
        }

        if (segmentUrls.isEmpty()) return null

        // Fetch all subtitle segments concurrently using coroutines
        return coroutineScope {
            val deferreds = segmentUrls.map { url ->
                async {
                    runCatching {
                        app.get(url, headers = headers).text
                    }.getOrNull()
                }
            }
            val results = deferreds.awaitAll()
            val merged = StringBuilder("WEBVTT\n\n")
            results.forEach { text ->
                if (!text.isNullOrEmpty()) {
                    // Strip the WEBVTT header from segment files
                    val cleanText = text.replace(Regex("^WEBVTT\\s*\\n\\n?", RegexOption.IGNORE_CASE), "")
                    if (cleanText.trim().isNotEmpty()) {
                        merged.append(cleanText)
                        if (!cleanText.endsWith("\n\n")) {
                            merged.append("\n\n")
                        }
                    }
                }
            }
            merged.toString()
        }
    }

    suspend fun invoke(
        id: Int,
        type: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ── Step 1: TMDB to IMDB ID Resolution ───────────────────────────────
        val typePath = if (type == "movie") "movie" else "tv"
        val extUrl = "https://api.themoviedb.org/3/$typePath/$id/external_ids?api_key=$tmdbApiKey"
        val extRes = runCatching { app.get(extUrl).parsedSafe<IzlelanProvider.ExternalIds>() }.getOrNull()
        val imdbId = extRes?.imdb_id

        if (imdbId.isNullOrEmpty()) return false

        // ── Step 2: Build Direct Vidmody URL ─────────────────────────────────
        val paddedEpisode = episode?.toString()?.padStart(2, '0') ?: "01"
        val vidmodyUrl = if (type == "movie") {
            "https://vidmody.com/vs/$imdbId"
        } else {
            val s = season ?: 1
            "https://vidmody.com/vs/$imdbId/s$s/e$paddedEpisode"
        }

        // ── Step 3: Fetch Master HLS Playlist ────────────────────────────────
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://vidmody.com/",
            "Origin" to "https://vidmody.com"
        )

        val response = runCatching {
            app.get(vidmodyUrl, headers = headers)
        }.getOrNull() ?: return false

        if (response.code != 200 || response.text.isEmpty() || response.text.contains("içerik bulunamadı")) {
            return false
        }

        // ── Step 4: Parse & Merge Subtitle Tracks Natively ────────────────────
        val m3u8Content = response.text
        val lines = m3u8Content.split("\n")
        val nameRegex = Regex("""NAME="([^"]+)"""", RegexOption.IGNORE_CASE)
        val uriRegex = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)

        lines.forEach { line ->
            if (line.contains("TYPE=SUBTITLES", ignoreCase = true)) {
                val name = nameRegex.find(line)?.groupValues?.get(1) ?: "Subtitle"
                val uri = uriRegex.find(line)?.groupValues?.get(1)
                if (!uri.isNullOrEmpty()) {
                    val absoluteSubUrl = resolveUrl(vidmodyUrl, uri)
                    val finalName = if (name.equals("Sesotho", ignoreCase = true)) "Türkçe (Forced)" else name
                    
                    // Merge and cache the subtitle segments
                    val mergedVtt = mergeSubtitleSegments(absoluteSubUrl, headers)
                    if (!mergedVtt.isNullOrEmpty()) {
                        val cacheDir = IzlelanPlugin.context?.cacheDir
                        if (cacheDir != null) {
                            val tempFile = java.io.File(cacheDir, "imu_sub_${finalName}.vtt")
                            runCatching {
                                tempFile.writeText(mergedVtt)
                                subtitleCallback(SubtitleFile(finalName, "file://${tempFile.absolutePath}"))
                            }
                        }
                    }
                }
            }
        }

        // ── Step 5: Deliver the extractor link to the callback
        callback(
            newExtractorLink(
                source = "Imu",
                name = "Imu",
                url = vidmodyUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://vidmody.com/"
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )

        return true
    }
}
