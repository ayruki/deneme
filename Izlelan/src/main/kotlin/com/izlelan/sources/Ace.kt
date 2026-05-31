package com.izlelan.sources

import com.izlelan.BaseUrls
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

object Ace {
    private val workerUrl = BaseUrls.get("ace_api", "https://filmizlehub.ayruki.workers.dev")

    suspend fun invoke(
        id: Int,
        type: String,
        imdbIdParam: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Build Worker API URL
        val targetUrl = if (type == "movie" || season == null || episode == null) {
            "$workerUrl/$id"
        } else {
            "$workerUrl/$id/$season/$episode"
        }

        val res = runCatching { app.get(targetUrl) }.getOrNull() ?: return false
        if (res.code != 200) return false

        val json = runCatching { JSONObject(res.text) }.getOrNull() ?: return false
        var found = false

        // 1. First extract direct HLS links
        val hlsObj = json.optJSONObject("hls")
        if (hlsObj != null) {
            hlsObj.keys().forEach { rawLabel ->
                val hlsUrl = hlsObj.optString(rawLabel)
                if (hlsUrl.isNotBlank()) {
                    val label = when {
                        rawLabel.contains("dublaj", ignoreCase = true) -> "TR Dublaj"
                        rawLabel.contains("altyazılı", ignoreCase = true) || rawLabel.contains("altyazı", ignoreCase = true) -> "TR Altyazı"
                        else -> rawLabel
                    }
                    callback(
                        newExtractorLink(
                            source = "🇹🇷 Ace",
                            name = "🇹🇷 Ace [$label]",
                            url = hlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://filmizlehub.xyz/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }
            }
        }

        // 2. Fallback to iframe sources if HLS extraction failed
        if (!found) {
            val sourcesObj = json.optJSONObject("sources")
            if (sourcesObj != null) {
                sourcesObj.keys().forEach { rawLabel ->
                    val sourceUrl = sourcesObj.optString(rawLabel)
                    if (sourceUrl.isNotBlank()) {
                        val label = when {
                            rawLabel.contains("dublaj", ignoreCase = true) -> "TR Dublaj"
                            rawLabel.contains("altyazılı", ignoreCase = true) || rawLabel.contains("altyazı", ignoreCase = true) -> "TR Altyazı"
                            else -> rawLabel
                        }
                        val loaded = runCatching {
                            loadExtractor(sourceUrl, "https://filmizlehub.xyz/", subtitleCallback, callback)
                        }.getOrDefault(false)
                        if (loaded) found = true
                    }
                }
            }
        }

        return found
    }
}
