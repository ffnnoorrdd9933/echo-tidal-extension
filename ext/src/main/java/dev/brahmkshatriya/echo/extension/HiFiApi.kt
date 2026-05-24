package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.extension.model.SearchResponse
import dev.brahmkshatriya.echo.extension.model.V1PagesResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.io.encoding.Base64

class HiFiApi(
    private val getApi: suspend () -> String?,
    private val getPort: suspend () -> Int,
) {
    val client = OkHttpClient()

    private suspend fun getInstancesFromGithub(): List<String> {
        val url =
            "https://raw.githubusercontent.com/brahmkshatriya/hifi-instances/refs/heads/patch-1/instances.json"
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

    @Serializable
    data class Res<T>(
        val version: String? = null,
        val data: T? = null,
    )

    @Serializable
    data class Data(
        val trackId: Long? = null,
        val assetPresentation: String? = null,
        val audioMode: String? = null,
        val audioQuality: String? = null,
        val manifestMimeType: String? = null,
        val manifestHash: String? = null,
        val manifest: String? = null,
        val albumReplayGain: Double? = null,
        val albumPeakAmplitude: Double? = null,
        val trackReplayGain: Double? = null,
        val trackPeakAmplitude: Double? = null,
        val bitDepth: Long? = null,
        val sampleRate: Long? = null,
    )

    @Serializable
    data class Decoded(
        val mimeType: String? = null,
        val codecs: String? = null,
        val encryptionType: String? = null,
        val urls: List<String>? = null,
    )

    suspend fun searchTrack(query: String): Json.Decoded<Res<V1PagesResponse.PagedList>> {
        val res = call("search/?s=$query")
        return Json.decode(res)
    }

    suspend fun searchArtist(query: String): Json.Decoded<Res<SearchResponse>> {
        val res = call("search/?a=$query")
        return Json.decode(res)
    }

    suspend fun searchAlbum(query: String): Json.Decoded<Res<SearchResponse>> {
        val res = call("search/?al=$query")
        return Json.decode(res)
    }

    suspend fun searchPlaylist(query: String): Json.Decoded<Res<SearchResponse>> {
        val res = call("search/?p=$query")
        return Json.decode(res)
    }

    enum class ProgressiveQuality(val quality: Int) {
        LOW(1), HIGH(2), LOSSLESS(3)
    }

    enum class DashQuality(val quality: Int) {
        HI_RES(4), HI_RES_LOSSLESS(5)
    }

    suspend fun track(trackId: String, quality: String): String {
        val res = call("track/?id=$trackId&quality=$quality")
        val base64 = Json.decode<Res<Data>>(res).value.data?.manifest!!
        return Base64.decode(base64).toString(Charsets.UTF_8)
    }

    // ИСПРАВЛЕННЫЙ БЛОК: Добавлена проверка на XML (DASH)
    suspend fun stream(id: String, quality: String): String {
        val res = track(id, quality)
        if (res.trimStart().startsWith("<")) {
            return streamDash(id, quality)
        }
        val decoded = Json.decode<Decoded>(res).value
        return decoded.urls!!.random()
    }

    val dashServer = DashServer { trackId, quality ->
        track(trackId, quality)
    }

    suspend fun streamDash(trackId: String, quality: String): String {
        val port = getPort()
        dashServer.start(port)
        return "http://localhost:$port/manifest?id=$trackId&quality=$quality"
    }
}


