package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.extension.model.ArtistResponse
import dev.brahmkshatriya.echo.extension.model.Item
import dev.brahmkshatriya.echo.extension.model.Page
import dev.brahmkshatriya.echo.extension.model.PlaylistResponse
import dev.brahmkshatriya.echo.extension.model.SearchResponse
import dev.brahmkshatriya.echo.extension.model.TokenResponse
import dev.brahmkshatriya.echo.extension.model.UserResponse
import dev.brahmkshatriya.echo.extension.model.V1PagesResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class TidalApi {
    companion object {
        const val DEVICE_TYPE = "BROWSER"
        const val PLATFORM = "WEB"
    }

    var locale = "en_US"
    var countryCode = "US"

    val version = "2025.10.16"
    val clientId = "txNoH4kkV41MfH25"
    val clientSecret = "dQjy0MinCEvxi1O4UmxvxWnDjt4cgHBPw8ll6nYBk98="

    val client = OkHttpClient()
    fun request(path: String, params: Map<String, String> = mapOf()): Request.Builder {
        val url = HttpUrl.Builder()
            .scheme("https").host("tidal.com")
            .addPathSegments(path)
            .addQueryParameter("deviceType", DEVICE_TYPE)
            .addQueryParameter("platform", PLATFORM)
            .addQueryParameter("locale", locale)
            .addQueryParameter("countryCode", countryCode)

        params.forEach { (k, v) ->
            url.addQueryParameter(k, v)
        }
        val request = Request.Builder()
            .url(url.build())
            .header("x-tidal-client-version", version)
            .header("x-tidal-token", clientId)

        accessToken?.let { request.header("Authorization", "Bearer $it") }
        return request
    }

    suspend fun call(request: Request): String {
        val response = client.newCall(request).await()
        return if (response.isSuccessful) response.body.string()
        else throw IllegalStateException("Call Failed: ${response.code} ${response.body.string()}")
    }

    var refreshToken: String? = null
    var accessToken: String? = null
    var expiresIn: Long? = null
    fun clear() {
        refreshToken = null
        accessToken = null
        expiresIn = null
    }

    val mutex = Mutex()
    suspend fun accessToken() = mutex.withLock {
        if (accessToken == null || isTokenExpired())
            accessToken = createAccessToken(refreshToken)
        accessToken!!
    }

    fun isTokenExpired(): Boolean {
        return expiresIn == null || System.currentTimeMillis() >= expiresIn!!
    }

    suspend fun createAccessToken(refreshToken: String?): String {
        val formBody = if (refreshToken != null) FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", "49YxDN9a2aFV6RTG")
            .add("scope", "r_usr w_usr")
        else FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)

        val res = call(
            Request.Builder()
                .url("https://auth.tidal.com/v1/oauth2/token")
                .post(formBody.build())
                .build()
        )
        val tokenResponse = Json.decode<TokenResponse>(res).value
        expiresIn = System.currentTimeMillis() + (tokenResponse.expiresIn!! * 1000)
        return tokenResponse.accessToken!!
    }

    suspend fun authReq(path: String, params: Map<String, String> = mapOf()) = request(path, params)
        .header("Authorization", "Bearer ${accessToken()}")

    suspend fun home(cursor: String? = null): Json.Decoded<Page> {
        val req = if (refreshToken != null) authReq("v2/home/feed/static")
        else request(
            "v2/home/feed/static",
            if (cursor != null) mapOf("cursor" to cursor) else mapOf()
        )
        val res = call(req.build())
        return Json.decode(res)
    }

    suspend fun search(
        query: String,
        types: String = "ARTISTS,ALBUMS,TRACKS,VIDEOS,PLAYLISTS,UPLOADS",
        offset: String = "0",
    ): Json.Decoded<SearchResponse> {
        if (refreshToken == null) throw IllegalStateException("Refresh Token is required")
        val res = call(
            authReq(
                "v2/search", mapOf(
                    "query" to query,
                    "includeContributors" to "true",
                    "includeDidYouMean" to "false",
                    "includeUserPlaylists" to "true",
                    "offset" to offset,
                    "limit" to "50",
                    "supportsUserData" to "true",
                    "types" to types,
                )
            ).build()
        )
        return Json.decode(res)
    }

    suspend fun track(id: String): Json.Decoded<Item> {
        val res = call(authReq("v1/tracks/$id").build())
        return Json.decode(res)
    }

    suspend fun trackRecommendations(
        id: String,
        offset: String = "0",
    ): Json.Decoded<PlaylistResponse.Items> {
        val res = call(
            authReq(
                "v1/tracks/$id/recommendations",
                mapOf("limit" to "50", "offset" to offset)
            ).build()
        )
        return Json.decode(res)
    }

    suspend fun users(id: String): Json.Decoded<UserResponse> {
        val res = call(authReq("v1/users/$id").build())
        return Json.decode(res)
    }

    suspend fun artist(id: String): Json.Decoded<ArtistResponse> {
        val res = call(authReq("v2/artist/$id").build())
        return Json.decode(res)
    }

    suspend fun album(id: String): Json.Decoded<V1PagesResponse> {
        val res = call(authReq("v1/pages/album", mapOf("albumId" to id)).build())
        return Json.decode(res)
    }

    suspend fun mix(id: String): Json.Decoded<V1PagesResponse> {
        val res = call(authReq("v1/pages/mix", mapOf("mixId" to id)).build())
        return Json.decode(res)
    }

    suspend fun playlist(id: String): Json.Decoded<PlaylistResponse> {
        val res = call(authReq("v2/user-playlists/$id").build())
        return Json.decode(res)
    }

    suspend fun playlistItems(id: String, offset: String): Json.Decoded<PlaylistResponse.Items> {
        val res = call(
            authReq(
                "v1/playlists/$id/items",
                mapOf("offset" to offset, "limit" to "50")
            ).build()
        )
        return Json.decode(res)
    }

    suspend fun v1Page(
        id: String,
        params: Map<String, String> = mapOf(),
    ): Json.Decoded<V1PagesResponse> {
        val res = call(authReq("v1/$id", params).build())
        return Json.decode(res)
    }
}