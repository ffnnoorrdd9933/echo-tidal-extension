package dev.brahmkshatriya.echo.extension.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PlaylistResponse (
    val playlist: Item? = null,
    val followInfo: FollowInfo? = null,
    val profile: Profile? = null
) {

    @Serializable
    data class FollowInfo (
        val nrOfFollowers: Long? = null,
        val tidalResourceName: String? = null,
        val followed: Boolean? = null,
        val followType: String? = null
    )

    @Serializable
    data class Profile (
        @SerialName("userId")
        val userID: Long? = null,
        val name: String? = null,
        val color: List<String>? = null
    )

    @Serializable
    data class Items (
        val limit: Long? = null,
        val offset: Long? = null,
        val totalNumberOfItems: Long? = null,
        val items: List<ItemElement>? = null
    )

    @Serializable
    data class ItemElement (
        val item: Item? = null,
        val type: String? = null,
        val cut: JsonElement? = null
    )
}
