package com.izlelan.sources

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.izlelan.IzlelanProvider
import com.izlelan.BaseUrls
import org.jsoup.Jsoup
import java.util.regex.Pattern

object Shamrock {
    private const val tmdbApiKey = "a2f888b27315e62e471b2d587048f32e"
    private const val febbox = "https://www.febbox.com"

    // Varsayılan token ve region
    private const val defaultToken = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpYXQiOjE3NzcxNDUwMTcsIm5iZiI6MTc3NzE0NTAxNywiZXhwIjoxODA4MjQ5MDM3LCJkYXRhIjp7InVpZCI6MTY2Njk1MSwidG9rZW4iOiJhMGNlNjc2YjRhYmZjODk4OWUwZTU4YjFmMWMxMTU3YSJ9fQ.X6f7F-xssmzkLpiZapwLCqidsDrVqDFVLAda17nTG_E"
    private const val defaultRegion = "USA7"

    // ShowBox API verileri
    private val showBox = ShowBoxAPI()

    private suspend fun getImdbId(id: Int, imdbIdParam: String?, type: String): String? {
        if (!imdbIdParam.isNullOrBlank()) return imdbIdParam
        val typePath = if (type == "movie") "movie" else "tv"
        val extUrl = "https://api.themoviedb.org/3/$typePath/$id/external_ids?api_key=$tmdbApiKey"
        val extRes = runCatching { app.get(extUrl).parsedSafe<IzlelanProvider.ExternalIds>() }.getOrNull()
        return extRes?.imdb_id
    }

    private suspend fun getEpisodeImdbId(tmdbId: Int, season: Int, episode: Int): String? {
        val url = "https://api.themoviedb.org/3/tv/$tmdbId/season/$season/episode/$episode/external_ids?api_key=$tmdbApiKey"
        val extRes = runCatching { app.get(url).parsedSafe<IzlelanProvider.ExternalIds>() }.getOrNull()
        return extRes?.imdb_id
    }

    private fun getFebboxHeaders(token: String, shareKey: String, region: String): Map<String, String> {
        val fakeGState = "{\"i_l\":0,\"i_ll\":9999999999999,\"i_b\":\"AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKK\",\"i_e\":{\"enable_itp_optimization\":1}}"
        val cookieStr = "g_state=$fakeGState; ui=$token; oss_group=$region"
        return mapOf(
            "Cookie" to cookieStr,
            "x-requested-with" to "XMLHttpRequest",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to "https://www.febbox.com/share/$shareKey",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin" to "https://www.febbox.com"
        )
    }

    private suspend fun getFebboxShareKey(showboxMid: Int, boxType: Int): String? {
        val url = "https://www.febbox.com/mbp/to_share_page?box_type=$boxType&mid=$showboxMid&json=1"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "application/json"
        )
        return try {
            val response = app.get(url, headers = headers).parsedSafe<SharePageResponse>()
            val shareLink = response?.data?.share_link ?: response?.data?.link
            if (!shareLink.isNullOrBlank()) {
                shareLink.split("/").lastOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractFidFromHtml(html: String): String? {
        val patterns = listOf(
            Pattern.compile("class=\"[^\"]*play_video[^\"]*\"\\s+data-id=\"(\\d+)\""),
            Pattern.compile("class=\"details\"\\s+data-id=\"(\\d+)\""),
            Pattern.compile("data-id=\"(\\d+)\"")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private fun selectBestFile(files: List<FebBoxFile>): FebBoxFile? {
        val non3D = files.filter { !it.file_name.orEmpty().contains(".3d.", ignoreCase = true) }
        val candidates = if (non3D.isNotEmpty()) non3D else files
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.file_size_bytes ?: 0L }
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
        val isTv = type != "movie"
        val imdbId = getImdbId(id, imdbIdParam, type)
        val episodeImdbId = if (isTv && season != null && episode != null) {
            getEpisodeImdbId(id, season, episode)
        } else {
            null
        }

        val searchQuery = if (imdbId.isNullOrBlank()) {
            // Başlıkla ara (fallback)
            val detailsUrl = "https://api.themoviedb.org/3/${if (isTv) "tv" else "movie"}/$id?api_key=$tmdbApiKey&language=tr-TR"
            val meta = runCatching { app.get(detailsUrl).parsedSafe<IzlelanProvider.MediaDetail>() }.getOrNull()
            meta?.title ?: meta?.name ?: return false
        } else {
            imdbId
        }

        // 1. Showbox Arama
        val boxTypeQuery = if (isTv) "tv" else "movie"
        val searchResults = showBox.search(searchQuery, boxTypeQuery)
        val matched = searchResults.firstOrNull() ?: run {
            if (searchQuery == imdbId) {
                // Başlık ile aramayı dene
                val detailsUrl = "https://api.themoviedb.org/3/${if (isTv) "tv" else "movie"}/$id?api_key=$tmdbApiKey&language=tr-TR"
                val meta = runCatching { app.get(detailsUrl).parsedSafe<IzlelanProvider.MediaDetail>() }.getOrNull()
                val title = meta?.title ?: meta?.name
                if (!title.isNullOrBlank()) {
                    showBox.search(title, boxTypeQuery).firstOrNull()
                } else {
                    null
                }
            } else {
                null
            }
        } ?: return false

        val showboxId = matched.id ?: return false
        val boxType = matched.box_type ?: (if (isTv) 2 else 1)

        // 2. Share Key
        val shareKey = getFebboxShareKey(showboxId, boxType) ?: return false
        val headers = getFebboxHeaders(defaultToken, shareKey, defaultRegion)

        // 3. Dosya Listesi ve Hedef Dosya Seçimi
        val listUrl = "$febbox/file/file_share_list?page=1&share_key=$shareKey&pwd=&parent_id=0&is_html=0"
        val shareRes = runCatching { app.get(listUrl, headers = headers).parsedSafe<ShareListResponse>() }.getOrNull()
        val fileList = shareRes?.data?.file_list.orEmpty()

        var targetFile: FebBoxFile? = null

        if (fileList.isEmpty()) {
            // Tekli dosya
            val sharePageUrl = "$febbox/share/$shareKey"
            val html = runCatching { app.get(sharePageUrl, headers = headers).text }.getOrNull() ?: return false
            val fid = extractFidFromHtml(html) ?: return false
            targetFile = FebBoxFile(fid = fid.toLongOrNull(), file_name = searchQuery, file_size_bytes = 0)
        } else {
            if (isTv && season != null && episode != null) {
                // Sezon bul
                var seasonFolder: FebBoxFile? = null
                val seasonPattern = Pattern.compile("season\\s*0*$season\\b", Pattern.CASE_INSENSITIVE)
                for (item in fileList) {
                    if (item.is_dir == 1 && seasonPattern.matcher(item.file_name.orEmpty()).find()) {
                        seasonFolder = item
                        break
                    }
                }
                
                if (seasonFolder == null) {
                    // Sayfalı arama
                    for (page in 2..4) {
                        val pUrl = "$febbox/file/file_share_list?page=$page&share_key=$shareKey&pwd=&parent_id=0&is_html=0"
                        val pRes = runCatching { app.get(pUrl, headers = headers).parsedSafe<ShareListResponse>() }.getOrNull()
                        val pList = pRes?.data?.file_list.orEmpty()
                        if (pList.isEmpty()) break
                        for (item in pList) {
                            if (item.is_dir == 1 && seasonPattern.matcher(item.file_name.orEmpty()).find()) {
                                seasonFolder = item
                                break
                            }
                        }
                        if (seasonFolder != null) break
                    }
                }

                val seasonFid = seasonFolder?.fid ?: return false
                val epUrl = "$febbox/file/file_share_list?share_key=$shareKey&pwd=&parent_id=$seasonFid&is_html=0"
                val epRes = runCatching { app.get(epUrl, headers = headers).parsedSafe<ShareListResponse>() }.getOrNull()
                val epFiles = epRes?.data?.file_list.orEmpty().filter { it.is_dir != 1 }

                val epPattern = Pattern.compile("[Ss]\\d+[Ee]0*$episode\\b", Pattern.CASE_INSENSITIVE)
                val matchedEpisodes = epFiles.filter { epPattern.matcher(it.file_name.orEmpty()).find() }
                if (matchedEpisodes.isEmpty()) return false
                targetFile = selectBestFile(matchedEpisodes)
            } else {
                val videoFiles = fileList.filter { it.is_dir != 1 }
                if (videoFiles.isEmpty()) return false
                targetFile = selectBestFile(videoFiles)
            }
        }

        val fid = targetFile?.fid ?: return false

        // 4. Stream ve Altyazı Alımı (Player POST)
        val playerUrl = "$febbox/console/player"
        val postdata = mapOf(
            "fid" to fid.toString(),
            "share" to "",
            "imdb_id" to "",
            "quality" to ""
        )

        val playerResponse = runCatching {
            app.post(playerUrl, data = postdata, headers = headers).text
        }.getOrNull() ?: return false

        // Sadece MKV/MP4 (ORG vb.) yani HLS olmayan, direct download veya orijinal dosyaları çekelim
        // 1. Yöntem: video_quality_list HTML'ini dene (Buradaki linkler HLS dışı orjinal dosyalar barındırabilir)
        val qualityUrl = "$febbox/console/video_quality_list?fid=$fid"
        var foundLink = false
        try {
            val qRes = app.get(qualityUrl, headers = headers).parsedSafe<VideoQualityListResponse>()
            val html = qRes?.html.orEmpty()
            if (html.isNotBlank()) {
                val document = Jsoup.parse(html)
                val elements = document.select(".file_quality")
                for (div in elements) {
                    val qName = div.attr("data-quality") ?: "ORG"
                    val fileUrl = div.attr("data-url") ?: ""
                    // HLS linkleri (.m3u8) filtrele, sadece mkv/mp4 olanları/orjinalleri ekle
                    if (fileUrl.isNotBlank() && !fileUrl.contains(".m3u8") && !fileUrl.contains("/m3u8/")) {
                        callback(
                            newExtractorLink(
                                source = "🇬🇧 Shamrock",
                                name = "🇬🇧 Shamrock (ORG - $qName)",
                                url = fileUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$febbox/"
                                this.quality = Qualities.Unknown.value
                                this.headers = headers
                            }
                        )
                        foundLink = true
                    }
                }
            }
        } catch (e: Exception) {
            // Silently skip
        }

        // 2. Yöntem: Player HTML'inden JSON {"type":"...","file":"...","label":"..."} regex ile çek
        val regex = Pattern.compile("\\{\"type\":\"([^\"]+)\",\"file\":\"([^\"]+)\",\"label\":\"([^\"]+)\"\\}")
        val matcher = regex.matcher(playerResponse)
        while (matcher.find()) {
            val fileType = matcher.group(1) ?: ""
            val fileUrl = (matcher.group(2) ?: "").replace("\\/", "/")
            val label = matcher.group(3) ?: "ORG"

            // M3U8/HLS olmayanları filtrele
            if (fileUrl.isNotBlank() && !fileUrl.contains(".m3u8") && !fileUrl.contains("/m3u8/")) {
                callback(
                    newExtractorLink(
                        source = "🇬🇧 Shamrock",
                        name = "🇬🇧 Shamrock ($label - ${fileType.uppercase()})",
                        url = fileUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$febbox/"
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
                foundLink = true
            }
        }

        // 3. Yöntem: Hiçbir şey bulunamazsa file_download fallback kullan
        if (!foundLink) {
            try {
                val dlApiUrl = "$febbox/console/file_download?fids=[\"$fid\"]&share="
                val dlRes = app.get(dlApiUrl, headers = headers).parsedSafe<FileDownloadResponse>()
                val dlUrl = dlRes?.data?.firstOrNull()?.download_url
                if (!dlUrl.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            source = "🇬🇧 Shamrock",
                            name = "🇬🇧 Shamrock (ORG - Download)",
                            url = dlUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$febbox/"
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                    foundLink = true
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 5. Altyazıları Ekle (Player HTML'inden parsing)
        // Örnek: <li data-url="https://images.febbox.com/subtilte/a/b/c.srt" data-lang="Turkish">Turkish</li>
        val liPattern = Pattern.compile("<li[^>]+data-url=\"([^\"]+)\"[^>]*data-lang=\"([^\"]+)\"[^>]*>(.*?)</li>", Pattern.DOTALL)
        val liMatcher = liPattern.matcher(playerResponse)
        var parsedAnySub = false

        while (liMatcher.find()) {
            val subUrl = (liMatcher.group(1) ?: "").replace("\\/", "/")
            val lang = (liMatcher.group(2) ?: "").trim()
            val subName = (liMatcher.group(3) ?: "").trim()
            if (subUrl.isNotBlank()) {
                val cleanSubName = subName.replace(Regex("<[^<]+?>"), "").trim()
                val finalLang = if (cleanSubName.isNotBlank() && !cleanSubName.matches(Regex("^[0-9:\\-\\s\\/_\\.]+$"))) {
                    cleanSubName
                } else if (lang.isNotBlank() && !lang.matches(Regex("^[0-9:\\-\\s\\/_\\.]+$"))) {
                    lang
                } else {
                    "Subtitle"
                }
                subtitleCallback(
                    SubtitleFile(
                        lang = finalLang,
                        url = subUrl
                    ).apply {
                        this.headers = headers
                    }
                )
                parsedAnySub = true
            }
        }

        if (!parsedAnySub) {
            val fallbackLiPattern = Pattern.compile("<li[^>]+data-url=\"([^\"]+)\"[^>]*>(.*?)</li>", Pattern.DOTALL)
            val fallbackLiMatcher = fallbackLiPattern.matcher(playerResponse)
            while (fallbackLiMatcher.find()) {
                val subUrl = (fallbackLiMatcher.group(1) ?: "").replace("\\/", "/")
                val text = (fallbackLiMatcher.group(2) ?: "").replace(Regex("<[^<]+?>"), "").trim()
                if (subUrl.isNotBlank()) {
                    val finalLang = if (text.isNotBlank() && !text.matches(Regex("^[0-9:\\-\\s\\/_\\.]+$"))) text else "Subtitle"
                    subtitleCallback(
                        SubtitleFile(
                            lang = finalLang,
                            url = subUrl
                        ).apply {
                            this.headers = headers
                        }
                    )
                }
            }
        }

        return foundLink
    }

    // Helper classes for ShowBox API
    private class ShowBoxAPI {
        private val baseUrl = "https://mbpapi.shegu.net/api/api_client/index/"
        private val appKey = "moviebox"
        private val key = javax.crypto.spec.SecretKeySpec("123d6cedf626dy54233aa1w6".toByteArray(), "DESede")
        private val iv = javax.crypto.spec.IvParameterSpec("wEiphTn!".toByteArray())

        private fun encrypt(data: String): String {
            val cipher = javax.crypto.Cipher.getInstance("DESede/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, iv)
            val encryptedBytes = cipher.doFinal(data.toByteArray())
            return android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.DEFAULT).trim().replace("\n", "")
        }

        private fun md5(data: String): String {
            val digest = java.security.MessageDigest.getInstance("MD5")
            val hash = digest.digest(data.toByteArray())
            return hash.joinToString("") { String.format("%02x", it) }
        }

        private fun generateVerify(encryptedData: String): String {
            val appKeyHash = md5(appKey)
            val verifyString = appKeyHash + "123d6cedf626dy54233aa1w6" + encryptedData
            return md5(verifyString)
        }

        private fun nanoid(): String {
            val alphabet = "0123456789abcdef"
            val random = java.util.Random()
            return (1..32).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }

        suspend fun search(keyword: String, mediaType: String): List<SearchItem> {
            val expiredDate = (System.currentTimeMillis() / 1000) + 60 * 60 * 12
            val requestData = mapOf(
                "childmode" to "0",
                "app_version" to "11.5",
                "lang" to "en",
                "platform" to "android",
                "channel" to "Website",
                "appid" to "27",
                "expired_date" to expiredDate.toString(),
                "module" to "Search5",
                "page" to "1",
                "type" to mediaType,
                "keyword" to keyword,
                "pagelimit" to "20"
            )

            val mapper = com.fasterxml.jackson.databind.ObjectMapper().registerKotlinModule()
            val jsonString = mapper.writeValueAsString(requestData)
            val encryptedData = encrypt(jsonString)
            val appKeyHash = md5(appKey)
            val verify = generateVerify(encryptedData)

            val bodyDict = mapOf(
                "app_key" to appKeyHash,
                "verify" to verify,
                "encrypt_data" to encryptedData
            )

            val bodyJson = mapper.writeValueAsString(bodyDict)
            val base64Body = android.util.Base64.encodeToString(bodyJson.toByteArray(), android.util.Base64.DEFAULT).trim().replace("\n", "")

            val formData = mapOf(
                "data" to base64Body,
                "appid" to "27",
                "platform" to "android",
                "version" to "129",
                "medium" to "Website"
            )

            val bodyString = formData.map { (k, v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8") }.joinToString("&") + "&token=${nanoid()}"

            val headers = mapOf(
                "Platform" to "android",
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to "okhttp/3.2.0"
            )

            return try {
                val response = app.post(
                    baseUrl, 
                    headers = headers, 
                    data = formData
                ).parsedSafe<SearchResponse>()
                response?.data.orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private data class SearchResponse(
        @JsonProperty("data") val data: List<SearchItem>? = null
    )

    private data class SearchItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("box_type") val box_type: Int? = null,
        @JsonProperty("title") val title: String? = null
    )

    private data class SharePageResponse(
        @JsonProperty("data") val data: SharePageData? = null
    )

    private data class SharePageData(
        @JsonProperty("share_link") val share_link: String? = null,
        @JsonProperty("link") val link: String? = null
    )

    private data class ShareListResponse(
        @JsonProperty("data") val data: ShareListData? = null
    )

    private data class ShareListData(
        @JsonProperty("file_list") val file_list: List<FebBoxFile>? = null
    )

    private data class FebBoxFile(
        @JsonProperty("fid") val fid: Long? = null,
        @JsonProperty("file_name") val file_name: String? = null,
        @JsonProperty("file_size_bytes") val file_size_bytes: Long? = null,
        @JsonProperty("is_dir") val is_dir: Int? = null
    )

    private data class VideoQualityListResponse(
        @JsonProperty("html") val html: String? = null
    )

    private data class FileDownloadResponse(
        @JsonProperty("data") val data: List<FileDownloadItem>? = null
    )

    private data class FileDownloadItem(
        @JsonProperty("download_url") val download_url: String? = null
    )
}
