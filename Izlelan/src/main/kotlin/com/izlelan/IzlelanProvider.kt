package com.izlelan

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.izlelan.sources.*
import com.izlelan.api.*

object BaseUrls {
    private var urls: JSONObject? = null

    init {
        try {
            val stream = this::class.java.classLoader?.getResourceAsStream("base_urls.json")
            if (stream != null) {
                val jsonStr = stream.bufferedReader().use { it.readText() }
                urls = JSONObject(jsonStr)
            }
        } catch (e: Exception) {
            // Fallback
        }
    }

    fun get(key: String, fallback: String): String {
        return urls?.optString(key)?.ifBlank { fallback } ?: fallback
    }
}

class IzlelanProvider : MainAPI() {
    private val mapper = ObjectMapper().registerKotlinModule()
    override var mainUrl = "https://api.themoviedb.org/3"
    override var name = "İzlelan"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true

    companion object {
        private const val apiKey = "a2f888b27315e62e471b2d587048f32e"
        private const val langCode = "tr-TR"
    }

    override val mainPage = mainPageOf(
        "/trending/movie/day?api_key=$apiKey&language=$langCode" to "Trend Filmler",
        "/trending/tv/day?api_key=$apiKey&language=$langCode" to "Trend Diziler",
        "/movie/popular?api_key=$apiKey&language=$langCode" to "Popüler Filmler",
        "/tv/popular?api_key=$apiKey&language=$langCode" to "Popüler Diziler",
        "/movie/top_rated?api_key=$apiKey&language=$langCode" to "En Çok Oy Alan Filmler",
        "/tv/top_rated?api_key=$apiKey&language=$langCode" to "En Çok Oy Alan Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}&page=$page"
        val response = app.get(url).parsedSafe<Results>()
        val homeItems = response?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        return newHomePageResponse(request.name, homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/multi?api_key=$apiKey&language=$langCode&query=$query"
        val response = app.get(url).parsedSafe<Results>()
        return response?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    private fun Media.toSearchResponse(): SearchResponse? {
        if (media_type == "person") {
            return null
        }
        val genreIds = genre_ids.orEmpty()
        if (genreIds.contains(10764) || genreIds.contains(10767) || genreIds.contains(10766)) {
            return null
        }

        val localTitle = title ?: name
        val originalTitle = original_title ?: original_name
        val searchTitle = localTitle ?: originalTitle ?: return null

        val mediaTypeString = media_type ?: if (first_air_date != null || name != null || original_name != null) "tv" else "movie"
        val tvType = if (mediaTypeString == "tv") TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(
            searchTitle,
            LinkData(id = id, type = mediaTypeString).toJson(),
            tvType
        ) {
            this.posterUrl = if (poster_path != null) "https://image.tmdb.org/t/p/w500$poster_path" else null
            this.score = vote_average?.let { Score.from10(it.toFloat()) }
        }
    }

    private fun getCountryFlagAndName(isoCode: String?): Pair<String, String>? {
        val code = isoCode?.trim()?.uppercase() ?: return null
        if (code.length != 2) return null
        
        // Dynamic flag emoji generation
        val firstChar = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
        val flagEmoji = String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
        
        val turkishName = when (code) {
            "US" -> "ABD"
            "TR" -> "Türkiye"
            "KR" -> "Güney Kore"
            "GB" -> "İngiltere"
            "JP" -> "Japonya"
            "FR" -> "Fransa"
            "DE" -> "Almanya"
            "IT" -> "İtalya"
            "ES" -> "İspanya"
            "CA" -> "Kanada"
            "AU" -> "Avustralya"
            "IN" -> "Hindistan"
            "CN" -> "Çin"
            "RU" -> "Rusya"
            "BR" -> "Brezilya"
            "MX" -> "Meksika"
            else -> code
        }
        return Pair(flagEmoji, turkishName)
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = mapper.readValue(url, LinkData::class.java)
        val id = data.id ?: return null
        val type = data.type ?: "movie"

        val detailsUrl = if (type == "movie") {
            "$mainUrl/movie/$id?api_key=$apiKey&language=$langCode&append_to_response=alternative_titles,credits,external_ids,recommendations"
        } else {
            "$mainUrl/tv/$id?api_key=$apiKey&language=$langCode&append_to_response=alternative_titles,credits,external_ids,recommendations"
        }

        val videosUrl = if (type == "movie") {
            "$mainUrl/movie/$id/videos?api_key=$apiKey"
        } else {
            "$mainUrl/tv/$id/videos?api_key=$apiKey"
        }

        var details: MediaDetail? = null
        var videos: VideoResults? = null

        coroutineScope {
            val detailsDeferred = async { app.get(detailsUrl).parsedSafe<MediaDetail>() }
            val videosDeferred = async { app.get(videosUrl).parsedSafe<VideoResults>() }
            
            details = detailsDeferred.await()
            videos = videosDeferred.await()
        }

        if (details == null) return null

        val localTitle = details.title ?: details.name
        val rawOriginalTitle = details.original_title ?: details.original_name
        val originalLanguage = details.original_language?.trim()?.lowercase()

        // Find English title from alternative titles as a fallback for non-English original titles
        val altList = details.alternative_titles?.results.orEmpty() + details.alternative_titles?.titles.orEmpty()
        val englishAltTitle = altList.firstOrNull {
            val iso = it.iso_3166_1?.uppercase()
            iso == "US" || iso == "GB" || iso == "CA" || iso == "AU"
        }?.let { it.title ?: it.name }

        val readableOriginalTitle = if (originalLanguage == "en") {
            rawOriginalTitle
        } else {
            englishAltTitle ?: rawOriginalTitle
        }

        val title = localTitle ?: readableOriginalTitle ?: return null

        val poster = if (details.poster_path != null) "https://image.tmdb.org/t/p/w500${details.poster_path}" else null
        val backdrop = if (details.backdrop_path != null) "https://image.tmdb.org/t/p/original${details.backdrop_path}" else null
        val rating = details.vote_average?.let { Score.from10(it.toFloat()) }
        val releaseDate = details.release_date ?: details.first_air_date
        val year = releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()

        val genres = details.genres?.mapNotNull { it.name }.orEmpty()
        val actors = details.credits?.cast?.mapNotNull { cast ->
            val castName = cast.name ?: return@mapNotNull null
            ActorData(
                Actor(castName, cast.profile_path?.let { "https://image.tmdb.org/t/p/w185$it" }),
                roleString = cast.character
            )
        }.orEmpty()

        val recommendations = details.recommendations?.results?.mapNotNull { it.toSearchResponse() }.orEmpty()
        
        // Smart YouTube Trailer Priority
        val allVideos = videos?.results.orEmpty().filter { it.site?.equals("YouTube", ignoreCase = true) == true }
        val trailerVideo = allVideos.firstOrNull { it.type == "Trailer" && it.iso_639_1 == "tr" }
            ?: allVideos.firstOrNull { it.type == "Trailer" && it.iso_639_1 == "en" }
            ?: allVideos.firstOrNull { it.type == "Teaser" && it.iso_639_1 == "tr" }
            ?: allVideos.firstOrNull { it.type == "Teaser" && it.iso_639_1 == "en" }
            ?: allVideos.firstOrNull { it.type == "Trailer" }
            ?: allVideos.firstOrNull { it.type == "Teaser" }
            ?: allVideos.firstOrNull()
        val trailer = trailerVideo?.key?.let { "https://www.youtube.com/watch?v=$it" }
        
        val imdbId = details.external_ids?.imdb_id

        val finalPlot = details.overview.orEmpty()

        val finalTags = mutableListOf<String>()

        // 1. Dizi Durum Etiketi (Status Badge - TV Series only)
        if (type != "movie") {
            val statusTag = when (details.status) {
                "Returning Series" -> "🟢 Devam Ediyor"
                "Ended" -> "🔴 Final"
                "Canceled" -> "⚠️ İptal Edildi"
                "In Production", "Planned" -> "🟡 Yapım Aşamasında"
                else -> null
            }
            if (statusTag != null) {
                finalTags.add(statusTag)
            }
        }

        // 2. Ülke Bayrağı ve İsmi Etiketi (Country Tag)
        val primaryCountry = details.production_countries?.firstOrNull()
        val countryMeta = getCountryFlagAndName(primaryCountry?.iso_3166_1)
        if (countryMeta != null) {
            finalTags.add("${countryMeta.first} ${countryMeta.second}")
        }

        // 3. Tür Etiketleri (Genres)
        finalTags.addAll(genres)

        if (type == "movie") {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(id = id, imdbId = imdbId, type = type).toJson()
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = finalPlot
                this.score = rating
                this.tags = finalTags
                this.actors = actors
                this.recommendations = recommendations
                if (trailer != null) {
                    addTrailer(trailer)
                }
                if (imdbId != null) {
                    addImdbId(imdbId)
                }
            }
        } else {
            val episodes = coroutineScope {
                val allRawEpisodes = details.seasons
                    ?.filter { (it.season_number ?: 0) > 0 } // C. Specials (Season 0) Exclusion
                    ?.map { season ->
                        async {
                            val seasonNumber = season.season_number ?: return@async null
                            val seasonUrl = "$mainUrl/tv/$id/season/$seasonNumber?api_key=$apiKey&language=$langCode"
                            val seasonDetails = runCatching { app.get(seasonUrl).parsedSafe<MediaDetailEpisodes>() }.getOrNull()
                            seasonDetails?.episodes.orEmpty()
                        }
                    }?.awaitAll()?.filterNotNull()?.flatten().orEmpty()

                val sortedRawEpisodes = allRawEpisodes.sortedWith(
                    compareBy<Episode> { it.season_number ?: 0 }.thenBy { it.episode_number ?: 0 }
                )

                val currentDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                var unreleasedAdded = 0
                val filteredRawEpisodes = sortedRawEpisodes.filter { ep ->
                    val airDate = ep.air_date.orEmpty()
                    val isReleased = airDate.isEmpty() || airDate <= currentDate
                    if (isReleased) {
                        true
                    } else {
                        if (unreleasedAdded < 1) {
                            unreleasedAdded++
                            true
                        } else {
                            false
                        }
                    }
                }

                val showBackdrop = details.backdrop_path ?: details.poster_path
                filteredRawEpisodes.map { episode ->
                    newEpisode(
                        LinkData(
                            id = id,
                            imdbId = imdbId,
                            type = type,
                            season = episode.season_number ?: 1,
                            episode = episode.episode_number ?: 1
                        ).toJson()
                    ) {
                        this.name = episode.name
                        this.season = episode.season_number ?: 1
                        this.episode = episode.episode_number ?: 1
                        this.posterUrl = (episode.still_path ?: showBackdrop)?.let { "https://image.tmdb.org/t/p/w500$it" }
                        this.description = episode.overview
                        this.score = episode.vote_average?.let { Score.from10(it.toFloat()) }
                    }
                }
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = finalPlot
                this.score = rating
                this.tags = finalTags
                this.actors = actors
                this.recommendations = recommendations
                if (trailer != null) {
                    addTrailer(trailer)
                }
                if (imdbId != null) {
                    addImdbId(imdbId)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val res = mapper.readValue(data, LinkData::class.java)
        val id = res.id ?: return@coroutineScope false
        val type = res.type ?: "movie"
        val imdbId = res.imdbId

        val seenSubUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        fun getSubCallbackFor(sourceName: String) = { sub: SubtitleFile ->
            val normalized = sub.url.substringBefore("?")
            if (seenSubUrls.add(normalized)) {
                val newLabel = if (sub.lang.contains(sourceName, ignoreCase = true)) {
                    sub.lang
                } else {
                    "${sub.lang} ($sourceName)"
                }
                subtitleCallback(SubtitleFile(newLabel, sub.url).apply { headers = sub.headers })
            }
        }

        // TheIntroDB V3 Entegrasyonu (Arka planda çalışır, maks 2 saniye bekler)
        val introDbJob = async {
            return@async
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    val dataUri = TheIntroDB.fetchChapters(id, imdbId, type, res.season, res.episode)
                    if (dataUri != null) {
                        getSubCallbackFor("Intro")(SubtitleFile("İntro Bölümleri", dataUri))
                    }
                }
            }
        }

        val collectedLinks = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val customCallback = { link: ExtractorLink ->
            collectedLinks.add(link)
            Unit
        }

        val isMovie = type == "movie"
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<Boolean>>()

        // Helper function to run source with a 5-second timeout in parallel
        fun runSource(block: suspend () -> Boolean) = async {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    block()
                } ?: false
            }.getOrDefault(false)
        }

        // Add all compatible sources to run in parallel
        jobs.add(runSource { Imu.invoke(id, type, res.season, res.episode, getSubCallbackFor("Imu"), customCallback) })
        if (isMovie) {
            jobs.add(runSource { Shanks.invoke(id, type, imdbId, res.season, res.episode, getSubCallbackFor("Shanks"), customCallback) })
        }
        jobs.add(runSource { Loki.invoke(id, type, imdbId, res.season, res.episode, getSubCallbackFor("Loki"), customCallback) })
        jobs.add(runSource { Fujitora.invoke(id, type, imdbId, res.season, res.episode, getSubCallbackFor("Fujitora"), customCallback) })
        if (!isMovie) {
            jobs.add(runSource { Crocodile.invoke(id, type, imdbId, res.season, res.episode, getSubCallbackFor("Crocodile"), customCallback) })
            jobs.add(runSource { Smoker.invoke(id, type, imdbId, res.season, res.episode, getSubCallbackFor("Smoker"), customCallback) })
        }
        if (isMovie) {
            jobs.add(runSource { Xebec.invoke(id, type, imdbId, res.season, res.episode, getSubCallbackFor("Xebec"), customCallback) })
            jobs.add(runSource { Enel.invoke(id, type, imdbId, res.season, res.episode, getSubCallbackFor("Enel"), customCallback) })
        }
        jobs.add(runSource { Vegapunk.invoke(id, type, imdbId, res.season, res.episode, getSubCallbackFor("Vegapunk"), customCallback) })
        jobs.add(runSource { Rayleigh.invoke(id, type, res.season, res.episode, getSubCallbackFor("Rayleigh"), customCallback) })
        jobs.add(runSource { Chopper.invoke(id, type, res.season, res.episode, getSubCallbackFor("Chopper"), customCallback) })
        jobs.add(runSource { Shamrock.invoke(id, type, res.season, res.episode, getSubCallbackFor("Shamrock"), customCallback) })

        val results = jobs.awaitAll()
        
        // Sort collected links by preferred source order
        val preferredOrder = listOf("Imu", "Shanks", "Loki", "Fujitora", "Crocodile", "Smoker", "Xebec", "Enel", "Vegapunk", "Rayleigh", "Chopper", "Shamrock")
        val sortedLinks = collectedLinks.sortedWith(compareBy { link ->
            val index = preferredOrder.indexOfFirst { link.source.contains(it, ignoreCase = true) }
            if (index != -1) index else preferredOrder.size
        })
        
        sortedLinks.forEach { callback(it) }
        
        return@coroutineScope results.any { it }
    }

    data class LinkData(
        val id: Int? = null,
        val imdbId: String? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    data class Results(
        val results: List<Media>? = null
    )

    data class Media(
        val id: Int? = null,
        val name: String? = null,
        val title: String? = null,
        val original_title: String? = null,
        val original_name: String? = null,
        val media_type: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val vote_average: Double? = null,
        val overview: String? = null,
        val first_air_date: String? = null,
        val release_date: String? = null,
        val genre_ids: List<Int>? = null
    )

    data class MediaDetail(
        val id: Int? = null,
        val name: String? = null,
        val title: String? = null,
        val original_title: String? = null,
        val original_name: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val vote_average: Double? = null,
        val overview: String? = null,
        val first_air_date: String? = null,
        val release_date: String? = null,
        val genres: List<Genre>? = null,
        val seasons: List<Season>? = null,
        val runtime: Int? = null,
        val status: String? = null,
        val number_of_seasons: Int? = null,
        val number_of_episodes: Int? = null,
        val external_ids: ExternalIds? = null,
        val credits: Credits? = null,
        val videos: VideoResults? = null,
        val recommendations: Results? = null,
        val production_countries: List<ProductionCountry>? = null,
        val original_language: String? = null,
        val alternative_titles: AlternativeTitles? = null
    )
 
    data class AlternativeTitles(
        val results: List<AltTitle>? = null,
        val titles: List<AltTitle>? = null
    )
 
    data class AltTitle(
        val iso_3166_1: String? = null,
        val title: String? = null,
        val name: String? = null
    )

    data class Genre(val name: String? = null)
    data class ProductionCountry(val iso_3166_1: String? = null, val name: String? = null)
    data class Season(
        val season_number: Int? = null,
        val episode_count: Int? = null,
        val air_date: String? = null,
        val name: String? = null
    )
    data class ExternalIds(val imdb_id: String? = null)
    data class Credits(val cast: List<Cast>? = null)
    data class Cast(
        val name: String? = null,
        val profile_path: String? = null,
        val character: String? = null
    )
    data class VideoResults(val results: List<Video>? = null)
    data class Video(val key: String? = null, val type: String? = null, val iso_639_1: String? = null, val site: String? = null)

    data class MediaDetailEpisodes(
        val episodes: List<Episode>? = null
    )

    data class Episode(
        val id: Int? = null,
        val name: String? = null,
        val episode_number: Int? = null,
        val season_number: Int? = null,
        val air_date: String? = null,
        val overview: String? = null,
        val still_path: String? = null,
        val vote_average: Double? = null
    )
}
