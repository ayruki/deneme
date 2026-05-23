package com.izlelan

// Authorized TMDB Provider for Izlelan Extension
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class IzlelanProvider : MainAPI() {
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
        val searchTitle = title ?: name ?: original_title ?: original_name ?: return null
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

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<LinkData>(url)
        val id = data.id ?: return null
        val type = data.type ?: "movie"

        val detailsUrl = if (type == "movie") {
            "$mainUrl/movie/$id?api_key=$apiKey&language=$langCode&append_to_response=alternative_titles,credits,external_ids,videos,recommendations"
        } else {
            "$mainUrl/tv/$id?api_key=$apiKey&language=$langCode&append_to_response=alternative_titles,credits,external_ids,videos,recommendations"
        }

        val details = app.get(detailsUrl).parsedSafe<MediaDetail>() ?: return null

        val title = details.title ?: details.name ?: details.original_title ?: details.original_name ?: return null
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
        val trailer = details.videos?.results?.firstOrNull { it.type == "Trailer" }?.key?.let { "https://www.youtube.com/watch?v=$it" }
        val imdbId = details.external_ids?.imdb_id

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
                this.plot = details.overview
                this.score = rating
                this.tags = genres
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
                details.seasons?.map { season ->
                    async {
                        val seasonNumber = season.season_number ?: return@async null
                        val seasonUrl = "$mainUrl/tv/$id/season/$seasonNumber?api_key=$apiKey&language=$langCode"
                        val seasonDetails = runCatching { app.get(seasonUrl).parsedSafe<MediaDetailEpisodes>() }.getOrNull()

                        seasonDetails?.episodes?.map { episode ->
                            newEpisode(
                                LinkData(
                                    id = id,
                                    imdbId = imdbId,
                                    type = type,
                                    season = seasonNumber,
                                    episode = episode.episode_number
                                ).toJson()
                            ) {
                                this.name = episode.name
                                this.season = seasonNumber
                                this.episode = episode.episode_number
                                this.posterUrl = episode.still_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                                this.description = episode.overview
                                this.score = episode.vote_average?.let { Score.from10(it.toFloat()) }
                            }
                        }
                    }
                }?.awaitAll()?.filterNotNull()?.flatten() ?: emptyList()
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
                this.plot = details.overview
                this.score = rating
                this.tags = genres
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
        val res = parseJson<LinkData>(data)
        val id = res.id ?: return@coroutineScope false
        val imdbId = res.imdbId
        val season = res.season
        val episode = res.episode
        val type = res.type ?: "movie"

        val urls = mutableListOf<String>()

        if (type == "movie") {
            urls.add("https://vidsrc.to/embed/movie/$id")
            urls.add("https://vidlink.pro/embed/movie/$id")
            urls.add("https://embed.su/embed/movie/$id")
            if (!imdbId.isNullOrEmpty()) {
                urls.add("https://vidsrc.me/embed/movie?imdb=$imdbId")
                urls.add("https://vidsrc.to/embed/movie/$imdbId")
            }
        } else {
            if (season != null && episode != null) {
                urls.add("https://vidsrc.to/embed/tv/$id/$season/$episode")
                urls.add("https://vidlink.pro/embed/tv/$id/$season/$episode")
                urls.add("https://embed.su/embed/tv/$id/$season/$episode")
                if (!imdbId.isNullOrEmpty()) {
                    urls.add("https://vidsrc.me/embed/tv?imdb=$imdbId&season=$season&episode=$episode")
                    urls.add("https://vidsrc.to/embed/tv/$imdbId/$season/$episode")
                }
            }
        }

        urls.map { url ->
            async {
                runCatching {
                    loadExtractor(url, subtitleCallback, callback)
                }
            }
        }.awaitAll()

        true
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
        val release_date: String? = null
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
        val external_ids: ExternalIds? = null,
        val credits: Credits? = null,
        val videos: VideoResults? = null,
        val recommendations: Results? = null
    )

    data class Genre(val name: String? = null)
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
    data class Video(val key: String? = null, val type: String? = null)

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