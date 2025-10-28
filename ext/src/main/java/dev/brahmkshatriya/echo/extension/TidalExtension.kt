package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Companion.background
import dev.brahmkshatriya.echo.common.models.Streamable.Companion.server
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.TidalApi.Companion.JSON
import dev.brahmkshatriya.echo.extension.model.ImageSize
import dev.brahmkshatriya.echo.extension.model.TokenResponse

class TidalExtension : ExtensionClient, HomeFeedClient, SearchFeedClient, LoginClient.WebView,
    TrackClient {

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            SettingSwitch(
                "Always use 320kbps",
                "only320",
                "For streaming, always use 320kbps quality",
                false
            )
        )
    }

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    val only320 get() = setting.getBoolean("only320") ?: false

    val imageSize by lazy { ImageSize.MEDIUM }
    val api by lazy { TidalApi() }

    override val webViewRequest = object : WebViewRequest.Evaluate<List<User>> {
        override val javascriptToEvaluateOnPageStart = """
            function() {
                'use strict';
                const origFetch = window.fetch;
                window.fetch = async (...args) => {
                    const [resource] = args;
                    const url = typeof resource === 'string' ? resource : resource.url;
                    const res = await origFetch(...args);
                    if (url.includes('oauth2/token')) window.TOKEN = await res.clone().text();
                    return res;
                };
            }
        """.trimIndent()
        override val javascriptToEvaluate = "function() { return window.TOKEN; }"
        override suspend fun onStop(url: NetworkRequest, data: String?): List<User>? {
            val json = JSON.decodeFromString<TokenResponse>(data ?: "")
            val api = TidalApi()
            api.refreshToken = json.refreshToken
            val artistId = api.users(json.userID!!.toString()).artistID!!.toString()
            val artistItem = api.artist(artistId).item!!.data!!
            val user = User(
                id = json.userID.toString(),
                name = artistItem.name ?: "Tidal User",
                subtitle = artistItem.handle?.let { "@$it" } ?: json.user?.email,
                cover = artistItem.picture?.toImage(ImageSize.MEDIUM, false),
                extras = mapOf("refreshToken" to (json.refreshToken!!))
            )
            return listOf(user)
        }

        override val initialUrl = "https://tidal.com/".toGetRequest()
        override val stopUrlRegex = Regex(".*oauth2/me.*")
    }

    var user: User? = null
    override fun setLoginUser(user: User?) {
        this.user = user
        api.clear()
        api.refreshToken = user?.run { extras["refreshToken"]!! }
    }

    override suspend fun getCurrentUser() = user?.copy(extras = mapOf())

    override suspend fun loadHomeFeed() = PagedData.Continuous {
        api.home().run { Page(toShelves(imageSize), page?.cursor) }
    }.toFeed()

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        return if (query.isEmpty()) {
            throw Exception("Just use the search for now, this will be filled later")
        } else if (api.refreshToken == null) {
            val tabs = listOf("TRACKS", "ARTISTS", "ALBUMS", "PLAYLISTS").map { s ->
                Tab(s, s.lowercase().replaceFirstChar { it.uppercase() })
            }
            Feed(tabs) { tab ->
                when (tab?.id) {
                    "TRACKS" -> hiFiApi.searchTrack(query).items!!
                        .toShelves(tab.id, imageSize).toFeedData()

                    "ARTISTS" -> hiFiApi.searchArtist(query).first().artists!!.items!!
                        .toShelves(tab.id, imageSize).toFeedData()

                    "ALBUMS" -> hiFiApi.searchAlbum(query).albums!!.items!!
                        .toShelves(tab.id, imageSize).toFeedData()

                    "PLAYLISTS" -> hiFiApi.searchPlaylist(query).playlists!!.items!!
                        .toShelves(tab.id, imageSize).toFeedData()

                    else -> throw Exception("Unknown tab id: ${tab?.id}")
                }
            }
        } else {
            val tabs = listOf(
                "ALL", "TRACKS", "ARTISTS", "ALBUMS", "PLAYLISTS", "VIDEOS", "UPLOADS"
            ).map { s ->
                Tab(s, s.lowercase().replaceFirstChar { it.uppercase() })
            }
            Feed(tabs) {
                when (it?.id) {
                    "ALL" -> api.search(query).toFeedData(imageSize)
                    else -> searchFeedData(query, it!!.id)
                }
            }
        }
    }

    private fun searchFeedData(query: String, type: String) = PagedData.Continuous<Shelf> {
        val offset = it?.toLong() ?: 0L
        val res = api.search(query, type, it ?: "0").page(type)!!
        val total = res.totalNumberOfItems ?: 0L
        val nextOffset = offset + res.items!!.size
        val items = res.items.toShelves(type, imageSize)
        if (nextOffset >= total) Page(items, null)
        else Page(items, nextOffset.toString())
    }.toFeedData()

    override suspend fun loadTrack(
        track: Track, isDownload: Boolean,
    ): Track {
        val response = api.track(track.id)
        val isHiRes = response.mediaMetadata?.tags.orEmpty().contains("HIRES_LOSSLESS")
        val only320 = only320 && !isDownload
        val qualities = if (!only320) HiFiApi.ProgressiveQuality.entries
        else listOf(HiFiApi.ProgressiveQuality.HIGH)
        val servers = qualities.map {
            server(
                it.name, it.quality, "HiFi ${it.name}",
                mapOf("id" to track.id, "dash" to "false", "quality" to it.name)
            )
        } + if (!only320 && isHiRes) HiFiApi.DashQuality.entries.map {
            server(
                it.name, it.quality, "HiFi ${it.name}",
                mapOf("id" to track.id, "dash" to "true", "quality" to it.name)
            )
        } else emptyList()
        val videoCover = response.album?.videoCover?.let { id ->
            listOf(ImageSize.XLARGE, ImageSize.XXL).map {
                background(it.name, it.px, it.name, mapOf("id" to id))
            }
        }.orEmpty()
        return response.toTrack(ImageSize.XLARGE, false)
            .copy(streamables = servers + videoCover)
    }

    val hiFiApi by lazy { HiFiApi() }
    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean,
    ) = when (streamable.type) {
        Streamable.MediaType.Background -> {
            val id = streamable.extras["id"] ?: error("Background Id not found")
            val size = ImageSize.valueOf(streamable.id)
            Streamable.Media.Background(id.toVideo(size))
        }

        Streamable.MediaType.Server -> {
            val trackId = streamable.extras["id"] ?: error("Track Id not found")
            val quality = streamable.extras["quality"] ?: error("Quality not found")
            val isDash = streamable.extras["dash"] == "true"
            if (!isDash) hiFiApi.stream(trackId, quality).toServerMedia()
            else hiFiApi.streamDash(trackId, quality)
                .toServerMedia(type = Streamable.SourceType.DASH)
        }

        Streamable.MediaType.Subtitle -> throw IllegalStateException()
    }

    override suspend fun loadFeed(track: Track) = null
}