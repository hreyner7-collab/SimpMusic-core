package com.maxrave.spotify.auth

import com.maxrave.ktorext.getEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.http.parseQueryString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One login attempt: the page to open, and what has to survive until the user comes back. */
class SpotifyLoginRequest internal constructor(
    val authorizeUrl: String,
    internal val clientId: String,
    internal val state: String,
    internal val codeVerifier: String,
)

data class SpotifyOAuthTokens(
    val accessToken: String,
    /** Null when Spotify did not issue a new one; the caller keeps the refresh token it already has. */
    val refreshToken: String?,
    val expiresInSeconds: Long,
)

/** [invalidGrant] means Spotify refused the code or the refresh token, so retrying cannot help. */
class SpotifyOAuthException(
    val invalidGrant: Boolean,
    message: String,
) : Exception(message)

/**
 * Spotify's authorization code flow with PKCE, using a Client ID from the user's own developer app.
 * Requests then count against that app's quota instead of the web player's shared one, which is what
 * rate-limits the cookie-based login. No client secret exists anywhere: the code verifier proves
 * that the app finishing the login is the one that started it.
 */
object SpotifyOAuth {
    /** Default Spotify Client ID integrated into SimpMusic for PKCE login. */
    const val DEFAULT_CLIENT_ID = "9e744a63aa5441139d9fad6d5f9ee4f7"

    /** The redirect address the user registers on their Spotify app. */
    const val REDIRECT_URI = "simpmusic://spotify-auth"

    private const val SCOPES = "user-library-read playlist-read-private playlist-read-collaborative user-follow-read"
    private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(getEngine()) {
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
            }
            expectSuccess = false
        }
    }

    fun newLogin(clientId: String): SpotifyLoginRequest {
        val id = clientId.trim()
        val verifier = randomToken()
        val state = randomToken()
        val url =
            URLBuilder(AUTHORIZE_URL)
                .apply {
                    parameters.append("client_id", id)
                    parameters.append("response_type", "code")
                    parameters.append("redirect_uri", REDIRECT_URI)
                    parameters.append("scope", SCOPES)
                    parameters.append("state", state)
                    parameters.append("code_challenge_method", "S256")
                    parameters.append("code_challenge", codeChallenge(verifier))
                }.buildString()
        return SpotifyLoginRequest(authorizeUrl = url, clientId = id, state = state, codeVerifier = verifier)
    }

    /**
     * Finishes [request] from Spotify's redirect, `simpmusic://spotify-auth?code=…&state=…`, or the
     * same link pasted by hand. The state must match the one this attempt sent.
     */
    suspend fun completeLogin(
        callbackUrl: String,
        request: SpotifyLoginRequest,
    ): SpotifyOAuthTokens {
        val query = parseQueryString(callbackUrl.trim().substringAfter('?', ""))
        query["error"]?.let { throw SpotifyOAuthException(invalidGrant = true, message = "Authorization refused: $it") }
        val code = query["code"]
        if (code.isNullOrEmpty() || query["state"] != request.state) {
            throw SpotifyOAuthException(invalidGrant = true, message = "Callback without a code, or for another attempt")
        }
        return requestTokens(
            request.clientId,
            parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", REDIRECT_URI)
                append("code_verifier", request.codeVerifier)
            },
        )
    }

    suspend fun refresh(
        clientId: String,
        refreshToken: String,
    ): SpotifyOAuthTokens =
        requestTokens(
            clientId.trim(),
            parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            },
        )

    private suspend fun requestTokens(
        clientId: String,
        grant: Parameters,
    ): SpotifyOAuthTokens {
        val response =
            client.submitForm(
                url = TOKEN_URL,
                formParameters =
                    parameters {
                        append("client_id", clientId)
                        appendAll(grant)
                    },
            )
        val body = response.bodyAsText()
        if (response.status.isSuccess()) {
            val tokens = json.decodeFromString<TokenResponse>(body)
            return SpotifyOAuthTokens(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresInSeconds = tokens.expiresIn,
            )
        }
        val error = runCatching { json.decodeFromString<ErrorResponse>(body).error }.getOrNull()
        throw SpotifyOAuthException(
            invalidGrant = error == "invalid_grant",
            message = "Token request failed: HTTP ${response.status.value} ${error.orEmpty()}",
        )
    }

    /** RFC 7636 S256: BASE64URL(SHA256(ASCII(code_verifier))), without padding. */
    internal fun codeChallenge(verifier: String): String = verifier.encodeUtf8().sha256().base64Url().trimEnd('=')

    @OptIn(ExperimentalUuidApi::class)
    private fun randomToken(): String =
        (Uuid.random().toByteArray() + Uuid.random().toByteArray())
            .toByteString()
            .base64Url()
            .trimEnd('=')

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long,
    )

    @Serializable
    private data class ErrorResponse(
        @SerialName("error") val error: String? = null,
    )
}
