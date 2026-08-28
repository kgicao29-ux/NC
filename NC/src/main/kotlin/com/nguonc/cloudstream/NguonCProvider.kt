package com.nguonc.cloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiHeaders = mapOf(
        "Accept" to "application/json",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "films/phim-moi-cap-nhat" to "Phim mới cập nhật",
        "films/danh-sach/phim-dang-chieu" to "Phim đang chiếu",
        "films/danh-sach/phim-le" to "Phim lẻ",
        "films/danh-sach/phim-bo" to "Phim bộ",
        "films/danh-sach/tv-shows" to "TV Shows",
        "films/the-loai/hoat-hinh" to "Hoạt hình",
        "films/quoc-gia/han-quoc" to "Phim Hàn Quốc",
        "films/quoc-gia/trung-quoc" to "Phim Trung Quốc",
        "films/quoc-gia/au-my" to "Phim Âu Mỹ",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // A temporary API failure on one row should not break the whole home page.
        val response = runCatching {
            apiObject<ListEnvelope>("${request.data}?page=$page")
        }.getOrNull()

        val items = response?.items.orEmpty().mapNotNull { it.toSearchResponse() }
        val hasNext = response?.paginate?.let { paginate ->
            val current = paginate.currentPage ?: page
            val total = paginate.totalPage ?: current
            current < total
        } ?: items.isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = hasNext,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val response = runCatching {
            apiObject<ListEnvelope>("films/search?keyword=$encoded")
        }.getOrNull() ?: return emptyList()
        return response.items.orEmpty().mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/phim/")
            .substringBefore('?')
            .trim('/')
            .takeIf { it.isNotBlank() } ?: return null

        val movie = runCatching {
            apiObject<DetailEnvelope>("film/$slug")
        }.getOrNull()?.movie ?: return null

        val title = movie.name?.takeIf { it.isNotBlank() } ?: slug
        val poster = movie.posterUrl ?: movie.thumbUrl
        val background = movie.thumbUrl ?: movie.posterUrl
        val plot = movie.description?.let { Jsoup.parse(it).text() }?.takeIf { it.isNotBlank() }

        val categoryGroups = movie.category.orEmpty().values
        fun groupValues(groupName: String): List<String> =
            categoryGroups.firstOrNull {
                it.group?.name.equals(groupName, ignoreCase = true)
            }?.list.orEmpty().mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }

        val format = groupValues("Định dạng").firstOrNull()
        val genres = groupValues("Thể loại")
        val year = groupValues("Năm").firstOrNull()?.toIntOrNull()
        val countries = groupValues("Quốc gia")
        val tags = (genres + countries).distinct()

        val actors = movie.casts
            ?.split(",")
            ?.mapNotNull { actor -> actor.trim().takeIf { it.isNotBlank() } }
            ?.map { ActorData(Actor(it)) }
            .orEmpty()

        val durationMinutes = movie.time?.let { DURATION_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }

        // Group episode entries by episode name across all servers, so every episode
        // exposes one link per server in loadLinks.
        val episodesByName = LinkedHashMap<String, MutableList<StreamSource>>()
        movie.episodes.orEmpty().forEach { server ->
            val serverName = server.serverName?.trim()?.takeIf { it.isNotBlank() } ?: "Server"
            server.items.orEmpty().forEach { item ->
                val key = item.name?.trim()?.takeIf { it.isNotBlank() } ?: item.slug ?: return@forEach
                val m3u8 = item.m3u8?.trim()?.takeIf { it.isNotBlank() }
                val embed = item.embed?.trim()?.takeIf { it.isNotBlank() }
                if (m3u8 == null && embed == null) return@forEach
                episodesByName.getOrPut(key) { mutableListOf() }
                    .add(StreamSource(label = serverName, m3u8 = m3u8, embed = embed))
            }
        }

        val totalEpisodes = movie.totalEpisodes ?: episodesByName.size
        val isSeries = totalEpisodes > 1 ||
            episodesByName.size > 1 ||
            format?.contains("bộ", ignoreCase = true) == true ||
            format?.contains("tv", ignoreCase = true) == true

        if (isSeries) {
            val episodes = episodesByName.entries.mapIndexed { index, (episodeName, sources) ->
                val episodeNumber = EPISODE_NUMBER_REGEX.find(episodeName)
                    ?.value?.toIntOrNull() ?: (index + 1)
                newEpisode(PlaybackPayload(sources).toJson()) {
                    this.name = if (episodeName.toIntOrNull() != null) "Tập $episodeName" else episodeName
                    this.episode = episodeNumber
                    this.posterUrl = background
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.duration = durationMinutes
                this.showStatus = if (movie.currentEpisode?.contains("Hoàn tất", true) == true ||
                    movie.currentEpisode?.contains("Full", true) == true
                ) ShowStatus.Completed else ShowStatus.Ongoing
            }
        }

        val sources = episodesByName.values.flatten()
        return newMovieLoadResponse(title, url, TvType.Movie, PlaybackPayload(sources).toJson()) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = tags
            this.actors = actors
            this.duration = durationMinutes
            this.comingSoon = sources.isEmpty()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = runCatching { parseJson<PlaybackPayload>(data) }.getOrNull() ?: return false
        var emitted = 0

        payload.sources.forEach { source ->
            val label = source.label?.trim()?.takeIf { it.isNotBlank() } ?: "Server"
            val m3u8 = source.m3u8
            if (!m3u8.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name • $label",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                emitted++
            } else if (!source.embed.isNullOrBlank()) {
                // Fall back to the embed player when no direct m3u8 is exposed.
                if (loadExtractor(source.embed, "$mainUrl/", subtitleCallback, callback)) {
                    emitted++
                }
            }
        }
        return emitted > 0
    }

    private suspend inline fun <reified T : Any> apiObject(pathAndQuery: String): T {
        val response = app.get("$mainUrl/api/$pathAndQuery", headers = apiHeaders)
        return parseJson(response.text)
    }

    private fun FilmItem.toSearchResponse(): SearchResponse? {
        val itemSlug = slug?.takeIf { it.isNotBlank() } ?: return null
        val displayTitle = name?.takeIf { it.isNotBlank() } ?: originalName ?: return null
        val loadUrl = "$mainUrl/phim/$itemSlug"
        val poster = posterUrl ?: thumbUrl
        val episodes = totalEpisodes ?: 1
        val finished = currentEpisode?.contains("Full", ignoreCase = true) == true

        return if (episodes > 1 || (!finished && currentEpisode?.contains("Tập", true) == true)) {
            newTvSeriesSearchResponse(displayTitle, loadUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = getQualityFromString(this@toSearchResponse.quality)
            }
        } else {
            newMovieSearchResponse(displayTitle, loadUrl, TvType.Movie) {
                this.posterUrl = poster
                this.quality = getQualityFromString(this@toSearchResponse.quality)
            }
        }
    }

    // ---------------------------------------------------------------------
    // API models
    // ---------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ListEnvelope(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("paginate") val paginate: Paginate? = null,
        @JsonProperty("items") val items: List<FilmItem>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Paginate(
        @JsonProperty("current_page") val currentPage: Int? = null,
        @JsonProperty("total_page") val totalPage: Int? = null,
        @JsonProperty("total_items") val totalItems: Int? = null,
        @JsonProperty("items_per_page") val itemsPerPage: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class FilmItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
        @JsonProperty("current_episode") val currentEpisode: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("language") val language: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DetailEnvelope(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("movie") val movie: FilmDetail? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class FilmDetail(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
        @JsonProperty("current_episode") val currentEpisode: String? = null,
        @JsonProperty("time") val time: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("director") val director: String? = null,
        @JsonProperty("casts") val casts: String? = null,
        @JsonProperty("category") val category: Map<String, CategoryGroup>? = null,
        @JsonProperty("episodes") val episodes: List<ServerBlock>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CategoryGroup(
        @JsonProperty("group") val group: NamedItem? = null,
        @JsonProperty("list") val list: List<NamedItem>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class NamedItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ServerBlock(
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("items") val items: List<EpisodeItem>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class EpisodeItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class PlaybackPayload(
        @JsonProperty("sources") val sources: List<StreamSource> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class StreamSource(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null,
        @JsonProperty("embed") val embed: String? = null,
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val EPISODE_NUMBER_REGEX = Regex("""\d+""")
        private val DURATION_REGEX = Regex("""(\d+)\s*phút""")
    }
}
