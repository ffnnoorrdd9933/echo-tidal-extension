package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Shelf.Lists.Categories
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.model.ImageSize
import dev.brahmkshatriya.echo.extension.model.Item
import dev.brahmkshatriya.echo.extension.model.Page
import dev.brahmkshatriya.echo.extension.model.SearchResponse
import dev.brahmkshatriya.echo.extension.model.V1PagesResponse
import java.text.SimpleDateFormat
import java.util.Locale

fun SearchResponse.toFeedData(size: ImageSize): Feed.Data<Shelf> {
    return listOfNotNull(
        topHits?.firstOrNull()?.let {
            it.value!!.toMediaItem(it.type, size).toShelf()
        },
        tracks?.items?.toShelf("TRACKS", size),
        uploads?.items?.toShelf("UPLOADS", size),
        albums?.items?.toShelf("ALBUMS", size),
        artists?.items?.toShelf("ARTISTS", size),
        playlists?.items?.toShelf("PLAYLISTS", size),
        videos?.items?.toShelf("VIDEOS", size),
    ).toFeedData()
}

fun List<Item>?.toShelf(type: String?, size: ImageSize) = if (isNullOrEmpty()) null
else Shelf.Lists.Items(
    id = type!!,
    title = type.lowercase().replaceFirstChar { it.uppercase() },
    list = map { it.toMediaItem(type, size) }
)

fun SearchResponse.page(type: String?) = when (type) {
    "TRACKS" -> tracks
    "UPLOADS" -> uploads
    "ALBUMS" -> albums
    "ARTISTS" -> artists
    "PLAYLISTS" -> playlists
    "VIDEOS" -> videos
    else -> throw Exception("Unknown page type: $type")
}

fun List<Item>?.toShelves(type: String?, size: ImageSize) = this!!.map {
    it.toMediaItem(type, size).toShelf()
}

fun Item.toMediaItem(type: String?, size: ImageSize) = when (type) {
    "TRACKS", "UPLOADS", "VIDEOS" -> toTrack(size, type == "VIDEOS")
    "ALBUMS" -> toAlbum(size)
    "ARTISTS" -> toArtist(size)
    "PLAYLISTS" -> toPlaylist(size)
    else -> throw Exception("Unknown media item type: $type")
}

fun Item.toTrack(size: ImageSize, isVideo: Boolean) = Track(
    id = id!!.toString().trim('"'),
    title = title!!,
    cover = (image ?: album?.cover)?.toImage(size, isVideo),
    album = album?.let {
        Album(
            id = it.id!!.toString(),
            title = it.title!!,
            cover = it.cover?.toImage(size),
            releaseDate = it.releaseDate?.toDate(),
            label = providerName
        )
    },
    artists = artists.orEmpty().map {
        Artist(
            id = it.id!!.toString(),
            name = it.name!!,
            cover = it.picture?.toImage(size),
        )
    },
    duration = duration?.let { it * 1000 },
    albumOrderNumber = trackNumber,
    albumDiscNumber = volumeNumber,
    isrc = isrc,
    description = copyright,
    genres = genres.orEmpty().map { it.name!! },
    releaseDate = firstAvailable?.toSDate(),
    extras = mapOf(
        "trackMix" to (mixes?.trackMix ?: ""),
        "isVideo" to isVideo.toString()
    )
)

fun Item.toPlaylist(size: ImageSize) = Playlist(
    id = uuid!!,
    title = title!!,
    isEditable = false,
    isPrivate = when (sharingLevel) {
        "PRIVATE" -> true
        else -> false
    },
    cover = squareImage?.toImage(size),
    description = description,
    trackCount = numberOfTracks,
    duration = duration,
    creationDate = created?.toSSDate(),
    isRadioSupported = false
)

fun Item.toRadio() = Radio(
    id = id!!.toString(),
    title = title!!,
    subtitle = subTitle,
    cover = images?.medium?.url?.toImageHolder(),
    description = description,
)

fun Item.toAlbum(size: ImageSize) = Album(
    id = id!!.content,
    title = title!!,
    type = null,
    cover = cover?.toImage(size),
    artists = artists.orEmpty().map {
        Artist(
            id = it.id!!.toString(),
            name = it.name!!,
            cover = it.picture?.toImage(size),
        )
    },
    trackCount = numberOfTracks,
    duration = duration,
    releaseDate = releaseDate?.toDate(),
    description = copyright,
    label = providerName,
    isExplicit = explicit == true,
    isRadioSupported = false
)

fun Item.toArtist(size: ImageSize) = Artist(
    id = id!!.content,
    name = name!!,
    cover = picture?.toImage(size),
    extras = mapOf(
        "artistMix" to (mixes?.artistMix ?: "")
    )
)

fun Page.toShelves(size: ImageSize) = items!!.mapNotNull { it.toShelves(size) }

fun Page.Items.toShelves(size: ImageSize): Shelf? {
    return when (type) {
        Page.ItemsType.HORIZONTAL_LIST -> Shelf.Lists.Items(
            id = moduleID!!,
            title = title!!,
            subtitle = subtitle,
            list = items!!.map { it.toMediaItem(size) }
        )

        Page.ItemsType.TRACK_LIST, Page.ItemsType.ARTIST_TRACK_CREDITS_CARD -> Shelf.Lists.Tracks(
            id = moduleID!!,
            title = title!!,
            subtitle = subtitle,
            list = items!!.map { it.data!!.toTrack(size, false) }
        )

        Page.ItemsType.SHORTCUT_LIST -> null
        null -> null
    }
}

fun Page.PageItem.toMediaItem(size: ImageSize): EchoMediaItem {
    val data = data!!
    return when (type) {
        "PLAYLIST" -> data.toPlaylist(size)
        "ALBUM" -> data.toAlbum(size)
        "TRACK" -> data.toTrack(size, false)
        else -> throw Exception("Unknown media item type: $type")
    }
}

fun String.toDate() = run {
    val year = this.substring(0, 4).toInt()
    val month = this.substring(5, 7).toInt()
    val day = this.substring(8, 10).toInt()
    Date(year, month, day)
}

val sFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US)
fun String.toSDate() = sFormatter.parse(this)?.time?.let { Date(it) }

val ssFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
fun String.toSSDate() = ssFormatter.parse(this)?.time?.let { Date(it) }
fun String.toImage(size: ImageSize, isVideo: Boolean = false) =
    "https://resources.tidal.com/images/${
        replace('-', '/')
    }/${size.px}x${if (isVideo) size.px * 0.5625 else size.px}.jpg".toImageHolder(crop = false)

fun String.toVideo(size: ImageSize) = "https://resources.tidal.com/videos/${
    replace('-', '/')
}/${size.px}x${size.px}.mp4".toGetRequest()


fun V1PagesResponse.toShelves(api: TidalApi, size: ImageSize) = rows!!.toShelves(api, size)

fun List<V1PagesResponse.Row>.toShelves(
    api: TidalApi, size: ImageSize,
) = mapNotNull { row ->
    row.modules!!.mapNotNull {
        it.toShelves(api, size, it.scroll)
    }.flatten()
}.flatten()

fun V1PagesResponse.Module.toShelves(
    api: TidalApi, size: ImageSize, scroll: String?,
) = when (type) {
    V1PagesResponse.ModuleType.PAGE_LINKS_CLOUD -> listOf(
        Categories(
            id = id!!,
            title = title!!,
            list = toCategories(api, size)
        )
    )

    V1PagesResponse.ModuleType.PAGE_LINKS -> toCategories(api, size)

    V1PagesResponse.ModuleType.PLAYLIST_LIST -> toShelf(
        api, size, scroll
    ) { toPlaylist(size) }

    V1PagesResponse.ModuleType.ALBUM_LIST -> toShelf(
        api, size, scroll
    ) { toAlbum(size) }

    V1PagesResponse.ModuleType.ARTIST_LIST -> toShelf(
        api, size, scroll
    ) { toArtist(size) }

    V1PagesResponse.ModuleType.VIDEO_LIST -> toShelf(
        api, size, scroll
    ) { toTrack(size, true) }

    V1PagesResponse.ModuleType.TRACK_LIST -> toShelf(
        api, size, scroll, true
    ) { toTrack(size, false) }

    V1PagesResponse.ModuleType.MULTIPLE_TOP_PROMOTIONS -> null
    V1PagesResponse.ModuleType.FEATURED_PROMOTIONS -> null
    V1PagesResponse.ModuleType.ALBUM_HEADER -> null
    V1PagesResponse.ModuleType.ALBUM_ITEMS -> null
    V1PagesResponse.ModuleType.MIX_HEADER -> null
    null -> null
}


fun V1PagesResponse.Module.toShelf(
    api: TidalApi, size: ImageSize, scroll: String?, isTrackList: Boolean = false,
    block: Item.() -> EchoMediaItem,
) = if (scroll == "VERTICAL") pagedList!!.items!!.map {
    block(it).toShelf()
} else if (isTrackList) listOf(
    Shelf.Lists.Tracks(
        id = id!!,
        title = title!!,
        list = pagedList!!.items!!.map { item ->
            item.toTrack(size, false)
        },
        more = showMore?.apiPath?.toFeed(api, size)
    )
) else listOf(
    Shelf.Lists.Items(
        id = id!!,
        title = title!!,
        list = pagedList!!.items!!.map { block(it) },
        more = showMore?.apiPath?.toFeed(api, size)
    )
)

fun V1PagesResponse.Module.toCategories(
    api: TidalApi, size: ImageSize,
) = pagedList!!.items!!.map { item ->
    Shelf.Category(
        id = item.apiPath!!,
        title = item.title!!,
        feed = item.apiPath.toFeed(api, size)
    )
}

fun String.toFeed(api: TidalApi, size: ImageSize): Feed<Shelf> = Feed(listOf()) {
    api.v1Page(this).value.toShelves(api, size).toFeedData()
}