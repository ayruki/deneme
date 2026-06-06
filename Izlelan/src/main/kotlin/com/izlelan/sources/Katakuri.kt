package com.izlelan.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.newSubtitleFile
import org.json.JSONObject

object Katakuri {
    suspend fun invoke(
        id: Int,
        type: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isMovie = type.equals("movie", ignoreCase = true)
        val pageUrl = if (isMovie) {
            "https://vidfast.pro/movie/$id/"
        } else {
            val s = season ?: 1
            val e = episode ?: 1
            "https://vidfast.pro/tv/$id/$s/$e/"
        }

        // 1. Fetch the HTML page to find the payload text
        val pageHtml = runCatching { app.get(pageUrl).text }.getOrNull() ?: return false
        val text = Regex("""\\"en\\":\\"(.*?)\\"""").find(pageHtml)?.groupValues?.getOrNull(1)
            ?: return false

        // 2. Encrypt using enc-dec.app API to get server/stream info and token
        val encUrl = "https://enc-dec.app/api/enc-vidfast?text=$text&version=1"
        val encRes = runCatching { app.get(encUrl).text }.getOrNull() ?: return false
        val encJson = runCatching { JSONObject(encRes) }.getOrNull() ?: return false
        if (encJson.optInt("status") != 200) return false
        val result = encJson.optJSONObject("result") ?: return false
        
        val serversUrl = result.optString("servers")
        val streamBase = result.optString("stream")
        val token = result.optString("token")
        if (serversUrl.isNullOrBlank() || streamBase.isNullOrBlank() || token.isNullOrBlank()) return false

        // 3. Post to servers endpoint using CSRF token
        val postHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to "https://vidfast.pro/",
            "X-Requested-With" to "XMLHttpRequest",
            "X-CSRF-Token" to token
        )

        val serversEncrypted = runCatching { app.post(serversUrl, headers = postHeaders).text }.getOrNull() ?: return false

        // 4. Decrypt the server list
        val decUrl = "https://enc-dec.app/api/dec-vidfast"
        val decRes = runCatching {
            app.post(
                decUrl,
                json = mapOf("text" to serversEncrypted, "version" to "1"),
                headers = mapOf("Content-Type" to "application/json")
            ).text
        }.getOrNull() ?: return false

        val decJson = runCatching { JSONObject(decRes) }.getOrNull() ?: return false
        if (decJson.optInt("status") != 200) return false
        val serversDecrypted = decJson.optJSONArray("result") ?: return false

        // 5. Query servers until first working HLS link is found
        for (i in 0 until serversDecrypted.length()) {
            val server = serversDecrypted.optJSONObject(i) ?: continue
            val data = server.optString("data") ?: continue
            val streamUrl = "$streamBase/$data"

            val streamRes = runCatching {
                app.post(streamUrl, headers = postHeaders).text
            }.getOrNull()
            if (streamRes.isNullOrBlank()) continue

            val streamDecRes = runCatching {
                app.post(
                    decUrl,
                    json = mapOf("text" to streamRes, "version" to "1"),
                    headers = mapOf("Content-Type" to "application/json")
                ).text
            }.getOrNull() ?: continue

            val streamDecJson = runCatching { JSONObject(streamDecRes) }.getOrNull() ?: continue
            if (streamDecJson.optInt("status") != 200) continue
            val streamResult = streamDecJson.optJSONObject("result") ?: continue
            val playlistUrl = streamResult.optString("url")

            if (!playlistUrl.isNullOrBlank()) {
                // Parse subtitles
                val tracks = streamResult.optJSONArray("tracks")
                if (tracks != null) {
                    for (j in 0 until tracks.length()) {
                        val track = tracks.optJSONObject(j) ?: continue
                        val subUrl = track.optString("file")
                        val subLang = track.optString("label")
                        if (!subUrl.isNullOrBlank() && !subLang.isNullOrBlank()) {
                            subtitleCallback(newSubtitleFile(subLang, subUrl))
                        }
                    }
                }

                // Trigger HLS link
                callback(
                    newExtractorLink(
                        source = "🇬🇧 Katakuri",
                        name = "🇬🇧 Katakuri",
                        url = playlistUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true // Stop searching on first successful link
            }
        }

        return false
    }
}
