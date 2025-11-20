package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.extension.model.SearchResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class HiFiApi(
    private val getApi: suspend () -> String?,
) {
    val client = OkHttpClient()

    private suspend fun getInstancesFromGithub(): List<String> {
        val url =
            "https://raw.githubusercontent.com/EduardPrigoana/hifi-instances/refs/heads/main/instances.json"
        val res = client.newCall(Request.Builder().url(url).build()).await()
        val json = Json.decode<JsonObject>(res.body.string())
        val instances = json.value["api"]!!.jsonObject.entries.map { entry ->
            entry.value.jsonObject["urls"]!!.jsonArray.map {
                it.jsonPrimitive.content
            }
        }.flatten()
        return instances
    }

    private var instances: List<String>? = null
    private val instancesMutex = Mutex()
    suspend fun getInstances() = instancesMutex.withLock {
        if (instances == null) instances = getInstancesFromGithub()
        instances!!
    }

    private suspend fun <T, R> Iterable<T>.race(block: suspend (T) -> R) = channelFlow {
        forEach { item ->
            async {
                runCatching { block(item) }.onSuccess { send(it) }
            }
        }
    }.firstOrNull()

    val apiMutex = Mutex()
    suspend fun call(path: String) = apiMutex.withLock {
        (listOfNotNull(getApi()).ifEmpty { getInstances() }).toSet().race {
            val res = client.newCall(Request.Builder().url("$it/$path").build()).await()
            val body = res.body.string()
            if (!res.isSuccessful) throw IllegalStateException("Call Failed: ${res.code} $body")
            body
        } ?: throw IllegalStateException("HiFi API instances are not reachable")
    }

    suspend fun searchTrack(query: String): Json.Decoded<SearchResponse.ItemPage> {
        val res = call("search/?s=$query")
        return Json.decode(res)
    }

    suspend fun searchArtist(query: String): Json.Decoded<List<SearchResponse>> {
        val res = call("search/?a=$query")
        return Json.decode(res)
    }

    suspend fun searchAlbum(query: String): Json.Decoded<SearchResponse> {
        val res = call("search/?al=$query")
        return Json.decode(res)
    }

    suspend fun searchPlaylist(query: String): Json.Decoded<SearchResponse> {
        val res = call("search/?p=$query")
        return Json.decode(res)
    }

    enum class ProgressiveQuality(val quality: Int) {
        LOW(1), HIGH(2), LOSSLESS(3)
    }

    enum class DashQuality(val quality: Int) {
//        HI_RES(4), HI_RES_LOSSLESS(5)
    }

    suspend fun stream(trackId: String, quality: String): String {
        val res = call("track/?id=$trackId&quality=$quality")
        return Json.decode<JsonArray>(res).value[2]
            .jsonObject["OriginalTrackUrl"]!!.jsonPrimitive.content
    }

    fun streamDash(trackId: String, quality: String) =
        "https://notworking/dash/?id=$trackId&quality=${quality}"
}