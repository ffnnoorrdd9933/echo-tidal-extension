package dev.brahmkshatriya.echo.extension.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class Page (
    val uuid: String? = null,
    val page: PageClass? = null,
    val header: Header? = null,
    val items: List<Items>? = null
) {

    @Serializable
    data class Header(
        val vibes: Vibes? = null,
    )

    @Serializable
    data class Vibes(
        val items: JsonArray? = null,
    )

    @Serializable
    data class Items(
        val type: ItemsType? = null,

        @SerialName("moduleId")
        val moduleID: String? = null,

        val title: String? = null,
        val icons: JsonArray? = null,
        val showQuickPlay: Boolean? = null,
        val viewAll: String? = null,
        val items: List<PageItem>? = null,
        val subtitle: String? = null,
    )

    @Serializable
    enum class ItemsType {
        HORIZONTAL_LIST, TRACK_LIST, SHORTCUT_LIST, ARTIST_TRACK_CREDITS_CARD, LINKS_LIST
    }

    @Serializable
    data class PageItem(
        val type: String? = null,
        val following: Boolean? = null,
        val numberOfFollowers: Long? = null,
        val data: Item? = null,
    )

    @Serializable
    data class PageClass(
        val cursor: String? = null,
    )
}