package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.model.ImageSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class ExtensionUnitTest {
    private val extension: ExtensionClient = TidalExtension()
    private val searchQuery = "Zomboy Born To Survive"

//    private val user = User(
//        "", "Test User", extras = mapOf(
//            "refreshToken" to ""
//        )
//    )
    private val user: User? = null

    @Test
    fun testEmptySearch() = testIn("Testing Empty Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val search = extension.loadSearchFeed("").pagedDataOfFirst().loadPage(null).data
        search.forEach {
            println(it)
        }
    }

    @Test
    fun testSearch() = testIn("Testing Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        println("Searching  : $searchQuery")
        val feed = extension.loadSearchFeed(searchQuery)
        println("Tabs : ${feed.tabs}")
        val paged = feed.getPagedData(feed.tabs[1]).pagedData
        val next = paged.loadPage(null)
        println(next.data[0])
        paged.loadPage(next.continuation).data.forEach {
            println(it)
        }
    }

    @Test
    fun testHomeFeed() = testIn("Testing Home Feed") {
        if (extension !is HomeFeedClient) error("HomeFeedClient is not implemented")
        val feed = extension.loadHomeFeed()
        println("Tabs : ${feed.tabs}")
        feed.pagedDataOfFirst().loadPage(null).data.forEach {
            println(it)
        }
    }

    private suspend fun searchTrack(q: String? = null): Track {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val query = q ?: searchQuery
        println("Searching : $query")
        val track = extension.loadSearchFeed(searchQuery).pagedDataOfFirst().loadAll()
            .firstNotNullOfOrNull {
                when (it) {
                    is Shelf.Item -> it.media as? Track
                    is Shelf.Lists.Tracks -> it.list.firstOrNull()
                    is Shelf.Lists.Items -> it.list.firstOrNull() as? Track
                    else -> null
                }
            }
        return track ?: error("Track not found, try a different search query")
    }

    @Test
    fun testTrackGet() = testIn("Testing Track Get") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val search = searchTrack()
        measureTimeMillis {
            val track = extension.loadTrack(search, false)
            println(track)
        }.also { println("time : $it") }
    }

    @Test
    fun testTrackStream() = testIn("Testing Track Stream") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val search = Track("134858521", "")
        val track = extension.loadTrack(search, false)
        track.streamables.sortedBy { it.quality }.forEach { streamable ->
            measureTimeMillis {
                val stream = extension.loadStreamableMedia(streamable, false)
                println(stream)
            }.also { println("time : $it") }
        }
    }

    @Test
    fun testTrackRadio() = testIn("Testing Track Radio") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        if (extension !is RadioClient) error("RadioClient is not implemented")
        val track = extension.loadTrack(searchTrack(), false)
        val radio = extension.radio(track, null)
        val radioTracks = extension.loadTracks(radio).loadAll()
        radioTracks.forEach {
            println(it)
        }
    }

    @Test
    fun testTrackShelves() = testIn("Testing Track Shelves") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val track = extension.loadTrack(searchTrack(), false)
        val mediaItems = extension.loadFeed(track)?.pagedDataOfFirst()?.loadPage(null)?.data
        if (mediaItems.isNullOrEmpty()) println("No shelves found for track")
        else mediaItems.forEach {
            println(it)
        }
    }

    @Test
    fun testArtist() = testIn("Artist") {
        val api = (extension as? TidalExtension)?.api ?: error("HiFiApi not found")
        val userID = 205961199
        val artistId = api.users(userID.toString()).value.artistID!!.toString()
        val artistItem = api.artist(artistId).value.item!!.data!!
        val user = User(
            id = artistId,
            name = artistItem.name ?: "Tidal User",
            subtitle = artistItem.handle,
            cover = artistItem.picture?.toImage(ImageSize.MEDIUM, false),
//            extras = mapOf("refreshToken" to (json.refreshToken!!))
        )
        println(user)
    }

    @Test
    fun testAlbumGet() = testIn("Testing Album Get") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val small = extension.loadTrack(searchTrack(), false).album ?: error("Track has no album")
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        val album = extension.loadAlbum(small)
        println(album)
        val tracks = extension.loadTracks(album)?.loadAll()
        if (tracks.isNullOrEmpty()) println("No tracks found for album")
        else tracks.forEach {
            println(it)
        }
    }


    // Test Setup
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(MockedSettings())
        runBlocking {
            extension.onInitialize()
            extension.onExtensionSelected()
            if (extension is LoginClient) extension.setLoginUser(user)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain() // reset the main dispatcher to the original Main dispatcher
        mainThreadSurrogate.close()
    }

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n-- $title --")
        block.invoke(this)
        println("\n")
    }
}