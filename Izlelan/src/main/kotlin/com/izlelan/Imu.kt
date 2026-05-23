package com.izlelan

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import okhttp3.OkHttpClient

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

        // ── Step 2: Build Vidmody URL ────────────────────────────────────────
        val paddedEpisode = episode?.toString()?.padStart(2, '0') ?: "01"
        val vidmodyUrl = if (type == "movie") {
            "https://vidmody.com/vs/$imdbId"
        } else {
            val s = season ?: 1
            "https://vidmody.com/vs/$imdbId/s$s/e$paddedEpisode"
        }

        // ── Step 3: Fetch Master HLS Playlist and Follow Redirects Manually ──
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://vidmody.com/",
            "Origin" to "https://vidmody.com"
        )

        val customClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val redirectClient = Requests(customClient)

        var currentUrl = vidmodyUrl
        var finalResponse: NiceResponse? = null
        var redirectCount = 0
        val maxRedirects = 5

        while (redirectCount < maxRedirects) {
            val res = runCatching {
                redirectClient.get(
                    currentUrl,
                    headers = headers
                )
            }.getOrNull() ?: break

            finalResponse = res

            if (res.code in 300..399) {
                val location = res.headers["Location"] ?: res.headers["location"]
                if (!location.isNullOrEmpty()) {
                    currentUrl = resolveUrl(currentUrl, location)
                    redirectCount++
                } else {
                    break
                }
            } else {
                break
            }
        }

        if (finalResponse == null || finalResponse.code != 200 || finalResponse.text.isEmpty() || finalResponse.text.contains("içerik bulunamadı")) {
            return false
        }

        // Parse subtitle tracks without blocking stream delivery.
        // Vidmody subtitles are HLS playlists with thousands of small VTT files.
        // Pass the playlist through instead of downloading and merging every segment.
        val m3u8Content = finalResponse.text
        val lines = m3u8Content.split("\n")
        val nameRegex = Regex("""NAME="([^"]+)"""", RegexOption.IGNORE_CASE)
        val uriRegex = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)

        val parsedSubs = mutableListOf<Pair<String, String>>()
        lines.forEach { line ->
            if (line.contains("TYPE=SUBTITLES", ignoreCase = true)) {
                val name = nameRegex.find(line)?.groupValues?.get(1) ?: "Subtitle"
                val uri = uriRegex.find(line)?.groupValues?.get(1)
                if (!uri.isNullOrEmpty()) {
                    val absoluteSubUrl = resolveUrl(currentUrl, uri)
                    parsedSubs.add(Pair(name, absoluteSubUrl))
                }
            }
        }

        val seenSubUrls = mutableSetOf<String>()

        parsedSubs.forEach { (name, url) ->
            if (seenSubUrls.add(url)) {
                subtitleCallback(
                    SubtitleFile(name, url).apply {
                        this.headers = headers
                    }
                )
            }
        }

        // Deliver the extractor link to the callback.
        callback(
            newExtractorLink(
                source = "Imu",
                name = "Imu",
                url = currentUrl,
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
