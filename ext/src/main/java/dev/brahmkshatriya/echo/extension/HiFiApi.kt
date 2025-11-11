package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.extension.TidalApi.Companion.JSON
import dev.brahmkshatriya.echo.extension.model.SearchResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class HiFiApi {
    val client = OkHttpClient()
    var api = "https://wolf.qqdl.site"

    suspend fun call(path: String): String {
        val res = client.newCall(Request.Builder().url("$api/$path").build()).await()
        val body = res.body.string()
        if (!res.isSuccessful) throw IllegalStateException("Call Failed: ${res.code} $body")
        return body
    }

    suspend fun searchTrack(query: String): SearchResponse.ItemPage {
        val res = call("search/?s=$query")
        return JSON.decodeFromString(res)
    }

    suspend fun searchArtist(query: String): List<SearchResponse> {
        val res = call("search/?a=$query")
        return JSON.decodeFromString(res)
    }

    suspend fun searchAlbum(query: String): SearchResponse {
        val res = call("search/?al=$query")
        return JSON.decodeFromString(res)
    }

    suspend fun searchPlaylist(query: String): SearchResponse {
        val res = call("search/?p=$query")
        return JSON.decodeFromString(res)
    }

    enum class ProgressiveQuality(val quality: Int) {
        LOW(1), HIGH(2), LOSSLESS(3)
    }

    enum class DashQuality(val quality: Int) {
//        HI_RES(4), HI_RES_LOSSLESS(5)
    }

    suspend fun stream(trackId: String, quality: String): String {
        val res = call("track/?id=$trackId&quality=$quality")
        return JSON.decodeFromString<JsonArray>(res)[2]
            .jsonObject["OriginalTrackUrl"]!!.jsonPrimitive.content
    }

    fun streamDash(trackId: String, quality: String) = "$api/dash/?id=$trackId&quality=${quality}"
}