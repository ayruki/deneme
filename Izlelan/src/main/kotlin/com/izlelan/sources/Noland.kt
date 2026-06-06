package com.izlelan.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.newSubtitleFile
import org.json.JSONObject

object Noland {
    suspend fun invoke(
        id: Int,
        type: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Encrypt TMDB ID using enc-dec.app API
        val encUrl = "https://enc-dec.app/api/enc-vidlink?text=$id"
        val encRes = runCatching { app.get(encUrl).text }.getOrNull() ?: return false
        val encJson = runCatching { JSONObject(encRes) }.getOrNull() ?: return false
        if (encJson.optInt("status") != 200) return false
        val encrypted = encJson.optString("result")
        if (encrypted.isNullOrBlank()) return false

        // 2. Request vidlink.pro details URL
        val isMovie = type.equals("movie", ignoreCase = true)
        val url = if (isMovie) {
            "https://vidlink.pro/api/b/movie/$encrypted"
        } else {
            val s = season ?: 1
            val e = episode ?: 1
            "https://vidlink.pro/api/b/tv/$encrypted/$s/$e"
        }

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to "https://vidlink.pro",
            "Referer" to "https://vidlink.pro/"
        )

        val responseText = runCatching { app.get(url, headers = headers).text }.getOrNull() ?: return false
        val json = runCatching { JSONObject(responseText) }.getOrNull() ?: return false
        
        val stream = json.optJSONObject("stream") ?: return false
        val playlist = stream.optString("playlist")
        if (playlist.isNullOrBlank()) return false

        // 3. Parse and trigger subtitles
        val captions = stream.optJSONArray("captions")
        if (captions != null) {
            for (i in 0 until captions.length()) {
                val caption = captions.optJSONObject(i) ?: continue
                val subUrl = caption.optString("url")
                val subLang = caption.optString("language")
                if (subUrl.isNotBlank() && subLang.isNotBlank()) {
                    subtitleCallback(newSubtitleFile(subLang, subUrl))
                }
            }
        }

        // 4. Trigger extractor link
        callback(
            newExtractorLink(
                source = "🇬🇧 Noland",
                name = "🇬🇧 Noland",
                url = playlist,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
