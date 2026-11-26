package com.maxrave.spotify

import com.maxrave.spotify.auth.SpotifyAuth
import com.maxrave.spotify.model.SpotifyAlbumDetailResponse
import com.maxrave.spotify.model.SpotifyAlbumTracksPageDto
import com.maxrave.spotify.model.SpotifyEmbedCollection
import com.maxrave.spotify.model.SpotifyPlaylistTracksResponse
import com.maxrave.spotify.model.SpotifySavedAlbumsResponse
import com.maxrave.spotify.model.SpotifySavedTracksResponse
import com.maxrave.spotify.model.SpotifySimplePlaylistDto
import com.maxrave.spotify.model.SpotifyUserPlaylistsResponse
import com.maxrave.spotify.model.response.spotify.CanvasResponse
import com.maxrave.spotify.model.response.spotify.ClientTokenResponse
import com.maxrave.spotify.model.response.spotify.PersonalTokenResponse
import com.maxrave.spotify.model.response.spotify.SpotifyLyricsResponse
import com.maxrave.spotify.model.response.spotify.search.SpotifySearchResponse
import com.maxrave.spotify.parser.SpotifyEmbedParser
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock

class SpotifyApiException(
    val statusCode: Int,
    val retryAfterSeconds: Long? = null,
    message: String,
) : IllegalStateException(message)

internal object SpotifyRateLimitPolicy {
    private val backoffMs = longArrayOf(1_500L, 3_500L, 7_000L, 15_000L, 30_000L)

    fun parseRetryAfterSeconds(headerValue: String?): Long? {
        val raw = headerValue?.trim()?.toLongOrNull() ?: return null
        if (raw <= 0L) return null
        return raw.coerceAtMost(300L)
    }

    fun computeRetryDelayMs(
        attempt: Int,
        retryAfterSeconds: Long?,
        maxAutoWaitSec: Long = 90L,
    ): Long? {
        if (attempt < 0 || attempt >= backoffMs.size) return null
        if (retryAfterSeconds != null && retryAfterSeconds > maxAutoWaitSec) return null
        val headerWaitMs = (retryAfterSeconds ?: 0L).coerceAtLeast(0L) * 1000L
        return maxOf(headerWaitMs, backoffMs[attempt])
    }
}

class Spotify {
    private val spotifyClient = SpotifyClient()
    private val spotifyAuth = SpotifyAuth(spotifyClient)
    private val apiPaceMutex = Mutex()
    @Volatile private var nextApiSlotAtMs: Long = 0L

    /**
     * Remove proxy for client
     */
    fun removeProxy() {
        spotifyClient.proxy = null
    }

    /**
     * Set the proxy for client
     */
    fun setProxy(
        isHttp: Boolean,
        host: String,
        port: Int,
    ) {
        val verifiedHost =
            if (!host.contains("http")) {
                "http://$host"
            } else {
                host
            }
        runCatching {
            if (isHttp) ProxyBuilder.http("$verifiedHost:$port") else ProxyBuilder.socks(verifiedHost, port)
        }.onSuccess {
            spotifyClient.proxy = it
        }.onFailure {
            it.printStackTrace()
        }
    }

    /**
     * Get personal token using the standard method
     */
    suspend fun getPersonalToken(spdc: String) =
        runCatching {
            spotifyClient.getSpotifyLyricsToken(spdc).body<PersonalTokenResponse>()
        }

    /**
     * Get personal token using the more reliable TOTP-based method
     * This should be used when the standard method fails
     */
    suspend fun getPersonalTokenWithTotp(spdc: String) = spotifyAuth.refreshToken(spdc)

    suspend fun getClientToken() =
        runCatching {
            spotifyClient
                .getSpotifyClientToken()
                .body<ClientTokenResponse>()
        }

    suspend fun searchSpotifyTrack(
        query: String,
        authToken: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient
            .searchSpotifyTrack(query, authToken, clientToken)
            .body<SpotifySearchResponse>()
    }

    suspend fun getSpotifyLyrics(
        trackId: String,
        token: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient
            .getSpotifyLyrics(
                token = token,
                clientToken = clientToken,
                trackId,
            ).body<SpotifyLyricsResponse>()
    }

    suspend fun getSpotifyCanvas(
        trackId: String,
        token: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient.getSpotifyCanvas(trackId, token, clientToken).body<CanvasResponse>()
    }

    private suspend inline fun <reified T> unwrapSuccess(crossinline block: suspend () -> HttpResponse): T {
        var attempt = 0
        while (true) {
            apiPaceMutex.withLock {
                val now = Clock.System.now().toEpochMilliseconds()
                val waitMs = nextApiSlotAtMs - now
                if (waitMs > 0L) {
                    delay(waitMs)
                }
                nextApiSlotAtMs = Clock.System.now().toEpochMilliseconds() + MIN_API_GAP_MS
            }

            val response = block()
            val status = response.status.value
            if (response.status.isSuccess()) {
                return response.body()
            }
            if (status == 429) {
                val retryAfter = SpotifyRateLimitPolicy.parseRetryAfterSeconds(response.headers[HttpHeaders.RetryAfter])
                val delayMs = SpotifyRateLimitPolicy.computeRetryDelayMs(attempt, retryAfter)
                if (delayMs != null) {
                    apiPaceMutex.withLock {
                        val until = Clock.System.now().toEpochMilliseconds() + delayMs
                        if (until > nextApiSlotAtMs) nextApiSlotAtMs = until
                    }
                    delay(delayMs)
                    attempt++
                    continue
                }
                val cooldownSec = (retryAfter ?: 15L).coerceIn(5L, 60L)
                apiPaceMutex.withLock {
                    val until = Clock.System.now().toEpochMilliseconds() + (cooldownSec * 1000L)
                    if (until > nextApiSlotAtMs) nextApiSlotAtMs = until
                }
                val waitLabel = if (retryAfter != null && retryAfter > 0L) "${retryAfter}s" else "a moment"
                throw SpotifyApiException(
                    statusCode = 429,
                    retryAfterSeconds = retryAfter,
                    message = "Spotify rate-limited requests — wait $waitLabel and tap Retry, or use Import Link.",
                )
            }
            throw SpotifyApiException(
                statusCode = status,
                retryAfterSeconds = null,
                message = "Spotify API HTTP $status",
            )
        }
    }

    private companion object {
        const val MIN_API_GAP_MS = 300L
    }

    suspend fun getUserPlaylists(
        token: String,
        clientToken: String,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyUserPlaylistsResponse> =
        runCatching {
            unwrapSuccess { spotifyClient.getUserPlaylists(token, clientToken, limit, offset) }
        }

    suspend fun getSavedTracks(
        token: String,
        clientToken: String,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifySavedTracksResponse> =
        runCatching {
            unwrapSuccess { spotifyClient.getSavedTracks(token, clientToken, limit, offset) }
        }

    suspend fun getSavedAlbums(
        token: String,
        clientToken: String,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifySavedAlbumsResponse> =
        runCatching {
            unwrapSuccess { spotifyClient.getSavedAlbums(token, clientToken, limit, offset) }
        }

    suspend fun getPlaylistMeta(
        playlistId: String,
        token: String,
        clientToken: String,
    ): Result<SpotifySimplePlaylistDto> =
        runCatching {
            unwrapSuccess { spotifyClient.getPlaylistMeta(playlistId, token, clientToken) }
        }

    suspend fun getPlaylistTracks(
        playlistId: String,
        token: String,
        clientToken: String,
        limit: Int = 100,
        offset: Int = 0,
    ): Result<SpotifyPlaylistTracksResponse> =
        runCatching {
            unwrapSuccess { spotifyClient.getPlaylistTracks(playlistId, token, clientToken, limit, offset) }
        }

    suspend fun getAlbumDetail(
        albumId: String,
        token: String,
        clientToken: String,
    ): Result<SpotifyAlbumDetailResponse> =
        runCatching {
            unwrapSuccess { spotifyClient.getAlbumDetail(albumId, token, clientToken) }
        }

    suspend fun getAlbumTracksPage(
        nextUrl: String,
        token: String,
        clientToken: String,
    ): Result<SpotifyAlbumTracksPageDto> =
        runCatching {
            unwrapSuccess { spotifyClient.getApiUrl(nextUrl, token, clientToken) }
        }

    suspend fun getPublicEmbedCollection(
        kind: String,
        id: String,
    ): Result<SpotifyEmbedCollection> =
        runCatching {
            val normalizedKind = kind.lowercase()
            require(normalizedKind in setOf("playlist", "album", "track")) {
                "Unsupported Spotify embed kind: $kind"
            }
            val response = spotifyClient.getPublicEmbedHtml(normalizedKind, id)
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Spotify embed HTTP ${response.status.value}")
            }
            val html = response.bodyAsText()
            SpotifyEmbedParser.parseEmbedHtml(html, normalizedKind, id)
                ?: throw IllegalStateException("Failed to parse Spotify embed data for $normalizedKind/$id")
        }
}