package com.izlelan.sources

import android.util.Base64
import com.izlelan.BaseUrls
import com.izlelan.IzlelanProvider
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
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

    private fun normalizeSubtitleName(name: String): String {
        val normalized = name.trim()
        val lowered = normalized.lowercase()
        return when (lowered) {
            "sesotho",
            "southern sotho",
            "g\u00fcney setho dili",
            "g\u00fcney sotho dili",
            "south sotho" -> "T\u00fcrk\u00e7e (Forced)"
            else -> normalized
        }
    }

    private fun rewriteMasterPlaylist(masterUrl: String, content: String): String {
        val uriRegex = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("""NAME="([^"]+)"""", RegexOption.IGNORE_CASE)

        return content.lines().joinToString("\n") { rawLine ->
            var line = rawLine

            if (line.contains("TYPE=SUBTITLES", ignoreCase = true)) {
                line = uriRegex.replace(line) { match ->
                    val absoluteUrl = resolveUrl(masterUrl, match.groupValues[1])
                    """URI="$absoluteUrl""""
                }
                line = nameRegex.replace(line) { match ->
                    val displayName = normalizeSubtitleName(match.groupValues[1])
                    """NAME="$displayName""""
                }
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                line = resolveUrl(masterUrl, line.trim())
            }

            line
        }
    }

    private fun toDataUri(content: String): String {
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "data:application/vnd.apple.mpegurl;base64,$encoded"
    }

    suspend fun invoke(
        id: Int,
        type: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val typePath = if (type == "movie") "movie" else "tv"
        val extUrl = "https://api.themoviedb.org/3/$typePath/$id/external_ids?api_key=$tmdbApiKey"
        val extRes = runCatching { app.get(extUrl).parsedSafe<IzlelanProvider.ExternalIds>() }.getOrNull()
        val imdbId = extRes?.imdb_id

        if (imdbId.isNullOrEmpty()) return false

        val base = BaseUrls.get("imu", "https://vidmody.com")
        val paddedEpisode = episode?.toString()?.padStart(2, '0') ?: "01"
        val vidmodyUrl = if (type == "movie") {
            "$base/vs/$imdbId"
        } else {
            val s = season ?: 1
            "$base/vs/$imdbId/s$s/e$paddedEpisode"
        }

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$base/",
            "Origin" to base
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

        if (
            finalResponse == null ||
            finalResponse.code != 200 ||
            finalResponse.text.isEmpty() ||
            finalResponse.text.contains("i\u00e7erik bulunamad\u0131", ignoreCase = true) ||
            !finalResponse.text.contains("#EXTM3U")
        ) {
            return false
        }

        val rewrittenPlaylist = rewriteMasterPlaylist(currentUrl, finalResponse.text)

        callback(
            newExtractorLink(
                source = "Imu",
                name = "Imu",
                url = toDataUri(rewrittenPlaylist),
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$base/"
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )

        return true
    }
}
