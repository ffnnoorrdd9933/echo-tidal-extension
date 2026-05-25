package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Companion.background
import dev.brahmkshatriya.echo.common.models.Streamable.Companion.server
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.SettingSlider
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.model.ArtistResponse
import dev.brahmkshatriya.echo.extension.model.ImageSize
import dev.brahmkshatriya.echo.extension.model.TokenResponse
import dev.brahmkshatriya.echo.extension.model.V1PagesResponse

class TidalExtension : ExtensionClient, HomeFeedClient, SearchFeedClient, LoginClient.WebView,
    TrackClient, AlbumClient, PlaylistClient, ArtistClient, RadioClient {

    override suspend fun getSettingItems() = listOf(
        SettingSwitch(
            "Always use 320kbps",
            "only320",
            "For streaming, always use 320kbps quality",
            false
        ),
        SettingTextInput(
            "HiFi API",
            "hiFiApi",
            "Base URL for HiFi API, leave empty to auto fetch a working one (will make it slower)",
            ""
        ),
        SettingSlider(
            "DASH Port",
            "dashPort",
            "Port for DASH streaming (only for advanced users running a local proxy)",
            6969,
            0,
            65535,
            1
        )
    )

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    val only320 get() = setting.getBoolean("only320") ?: false
    val hiFiUrl get() = setting.getString("hiFiApi")
    val port get() = setting.getInt("dashPort") ?: 6969

    val imageSize by lazy { ImageSize.MEDIUM }
    val api by lazy { TidalApi() }
    val hiFiApi by lazy { HiFiApi ({ hiFiUrl }, { port }) }

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
            val json = Json.impl.decodeFromString<TokenResponse>(data ?: "")
            val api = TidalApi()
            api.refreshToken = json.refreshToken
            val artistId = api.users(json.userID!!.toString()).value.artistID!!.toString()
            val artistItem = api.artist(artistId).value.item!!.data!!
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
        api.home().value.run { Page(toShelves(imageSize), page?.cursor) }
    }.toFeed()

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        return if (query.isEmpty()) {
            Feed(listOf()) {
                api.v1Page("pages/explore").value.toShelves(api, imageSize).toFeedData()
            }
        } else if (api.refreshToken == null) {
            val tabs = listOf("TRACKS", "ARTISTS", "ALBUMS", "PLAYLISTS").map { s ->
                Tab(s, s.lowercase().replaceFirstChar { it.uppercase() })
            }
            Feed(tabs) { tab ->
                when (tab?.id) {
                    "TRACKS" -> hiFiApi.searchTrack(query).value.data?.items!!
                        .toShelves(tab.id, imageSize).toFeedData()

                    "ARTISTS" -> hiFiApi.searchArtist(query).value.data!!.artists!!.items!!
                        .toShelves(tab.id, imageSize).toFeedData()

                    "ALBUMS" -> hiFiApi.searchAlbum(query).value.data!!.albums!!.items!!
                        .toShelves(tab.id, imageSize).toFeedData()

                    "PLAYLISTS" -> hiFiApi.searchPlaylist(query).value.data!!.playlists!!.items!!
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
                    "ALL" -> api.search(query).value.toFeedData(imageSize)
                    else -> searchFeedData(query, it!!.id)
                }
            }
        }
    }

    private fun searchFeedData(query: String, type: String) = PagedData.Continuous<Shelf> {
        val offset = it?.toLong() ?: 0L
        val res = api.search(query, type, it ?: "0").value.page(type)!!
        val total = res.totalNumberOfItems ?: 0L
        val nextOffset = offset + res.items!!.size
        val items = res.items.toShelves(type, imageSize)
        if (nextOffset >= total) Page(items, null)
        else Page(items, nextOffset.toString())
    }.toFeedData()

    override suspend fun loadTrack(
        track: Track, isDownload: Boolean,
    ): Track {
        val response = api.track(track.id).value
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

    // ИСПРАВЛЕННЫЙ БЛОК: Динамическое определение DASH на этапе загрузки потока
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

            if (isDash) {
                hiFiApi.streamDash(trackId, quality)
                    .toServerMedia(type = Streamable.SourceType.DASH)
            } else {
                val url = hiFiApi.stream(trackId, quality)
                if (url.contains("/manifest?id=")) {
                    url.toServerMedia(type = Streamable.SourceType.DASH)
                } else {
                    url.toServerMedia()
                }
            }
        }

        Streamable.MediaType.Subtitle -> throw IllegalStateException()
    }

    override suspend fun loadFeed(track: Track) = listOf(
        Shelf.Lists.Tracks(
            id = "rec$track.id",
            title = "Recommended Tracks",
            list = api.trackRecommendations(track.id).value.items!!.map {
                it.item!!.toTrack(imageSize, false)
            }
        )
    ).toFeed<Shelf>()

    override suspend fun loadAlbum(album: Album): Album {
        val decoded = api.album(album.id)
        val rows = decoded.value.rows!!
        val album = rows[0].modules!![0].album!!
        return album.toAlbum(ImageSize.XLARGE).copy(
            description = album.description,
            extras = mapOf(
                "json" to decoded.json
            )
        )
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val json = album.extras["json"]!!
        val decoded = Json.impl.decodeFromString<V1PagesResponse>(json)
        return decoded.rows!![1].modules!![0].pagedList!!.items!!.map {
            it.item!!.toTrack(imageSize, false)
        }.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        val json = album.extras["json"]!!
        val decoded = Json.impl.decodeFromString<V1PagesResponse>(json)
        return decoded.toShelves(api, imageSize).toFeed()
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        if (api.refreshToken == null)
            return api.playlist(playlist.id).value.toPlaylist(ImageSize.XLARGE)

        val decoded = api.userPlaylist(playlist.id).value
        return decoded.playlist!!.toPlaylist(ImageSize.XLARGE).copy(
            isFollowable = true,
            extras = mapOf(
                "following" to decoded.followInfo?.followed.toString(),
                "followers" to decoded.followInfo?.nrOfFollowers.toString()
            )
        )
    }

    override suspend fun loadTracks(playlist: Playlist) = PagedData.Continuous { cont ->
        val offset = cont ?: "0"
        val res = api.playlistItems(playlist.id, offset).value
        val tracks = res.items!!.map {
            it.item!!.toTrack(imageSize, false)
        }
        val nextOffset = offset.toLong() + res.items.size
        if (nextOffset >= (res.totalNumberOfItems ?: 0L)) Page(tracks, null)
        else Page(tracks, nextOffset.toString())
    }.toFeed()

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    override suspend fun loadArtist(artist: Artist): Artist {
        val decoded = api.artist(artist.id)
        val artistItem = decoded.value
        val following = artistItem.item?.following.toString()
        val followers = artistItem.header?.followersAmount.toString()
        return artistItem.item!!.data!!.toArtist(ImageSize.XLARGE).copy(
            bio = artistItem.header?.biography?.text,
            extras = mapOf(
                "json" to decoded.json,
                "following" to following,
                "followers" to followers
            )
        )
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val json = artist.extras["json"]!!
        val decoded = Json.impl.decodeFromString<ArtistResponse>(json)
        return decoded.items!!.mapNotNull { it.toShelves(imageSize) }.toFeed()
    }

    override suspend fun loadRadio(radio: Radio) = radio
    override suspend fun loadTracks(radio: Radio): Feed<Track> {
        val json = radio.extras["json"]!!
        val decoded = Json.impl.decodeFromString<V1PagesResponse>(json)
        return decoded.rows!![1].modules!![0].pagedList!!.items!!.map {
            it.toTrack(imageSize, false)
        }.toFeed()
    }

    override suspend fun radio(
        item: EchoMediaItem, context: EchoMediaItem?,
    ): Radio {
        val radio = when (item) {
            is Track -> Radio(
                id = item.extras["trackMix"]!!,
                title = "${item.title} Radio",
            )

            is Artist -> Radio(
                id = item.extras["artistMix"]!!,
                title = "${item.name} Radio",
            )

            else -> throw IllegalArgumentException(item::javaClass.name)
        }
        val decoded = api.mix(radio.id)
        val mix = decoded.value.rows!![0].modules!![0].mix!!
        return mix.toRadio().copy(
            extras = mapOf("json" to decoded.json)
        )
    }
}
