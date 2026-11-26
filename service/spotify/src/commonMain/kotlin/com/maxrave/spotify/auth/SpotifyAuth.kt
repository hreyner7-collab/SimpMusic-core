package com.maxrave.spotify.auth

import com.maxrave.logger.Logger
import com.maxrave.spotify.SpotifyClient
import com.maxrave.spotify.model.response.spotify.PersonalTokenResponse
import io.ktor.client.call.body
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

/**
 * Handles advanced Spotify authentication with TOTP
 */
class SpotifyAuth(
    private val spotifyClient: SpotifyClient,
) {
    var totpSecret: Pair<Int, List<Int>>? = null

    suspend fun getTotpSecret(): Result<Boolean> =
        runCatching {
            val response = spotifyClient.getSpotifyLastestTotpSecret().body<Map<String, List<Int>>>()
            if (response.isNotEmpty()) {
                val latestDict = response.entries.maxByOrNull { it.key.toIntOrNull() ?: Int.MIN_VALUE } ?: response.entries.last()
                val firstKey = latestDict.key.toInt()
                totpSecret = Pair(
                    firstKey,
                    latestDict.value
                )
                return@runCatching true
            } else false
        }

    /**
     * Refresh Spotify token using the new TOTP-based authentication
     * This is more reliable than the standard token method
     */
    suspend fun refreshToken(spDc: String): Result<PersonalTokenResponse> =
        runCatching {
            val cleanSpdc = spDc.substringAfter("sp_dc=", spDc).substringBefore(";").trim()
            if (totpSecret == null) {
                // Fetch the latest TOTP secret if not already fetched
                getTotpSecret().onSuccess {
                    println("Fetched TOTP secret successfully: $totpSecret")
                }.onFailure {
                    println("Failed to fetch TOTP secret: ${it.message}")
                }
            }
            // Get server time from Spotify
            val serverTimeResponse = spotifyClient.getSpotifyServerTime(cleanSpdc)
            val serverTimeJson = Json.parseToJsonElement(serverTimeResponse.body<String>()).jsonObject
            val serverTime =
                serverTimeJson["serverTime"]?.jsonPrimitive?.longOrNull
                    ?: throw Exception("Failed to get server time")
            println("Server time: $serverTime")

            // Generate TOTP secret and OTP value
            val totpVer = totpSecret?.first ?: SpotifyTotp.TOTP_SECRET_V22.first
            val otpValue = SpotifyTotp.at(serverTime * 1000L, totpSecret ?: SpotifyTotp.TOTP_SECRET_V22)
            println("Generated OTP: $otpValue")

            val sTime = "$serverTime"
            val cTime = "$serverTime"

            suspend fun fetchCandidate(reason: String, productType: String): PersonalTokenResponse? =
                try {
                    spotifyClient.getSpotifyAccessToken(
                        spdc = cleanSpdc,
                        otpValue = otpValue,
                        reason = reason,
                        sTime = sTime,
                        cTime = cTime,
                        totpVersion = totpVer,
                        productType = productType,
                    ).body<PersonalTokenResponse>()
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }

            var tokenData = fetchCandidate(reason = "transport", productType = "web-player")
            if (tokenData == null || tokenData.accessToken.isBlank() || tokenData.isAnonymous) {
                tokenData = fetchCandidate(reason = "init", productType = "web-player") ?: tokenData
            }
            if (tokenData == null || tokenData.accessToken.isBlank() || tokenData.isAnonymous) {
                tokenData = fetchCandidate(reason = "init", productType = "mobile-web-player") ?: tokenData
            }

            // Validate token
            if (tokenData == null || tokenData.accessToken.isEmpty()) {
                throw Exception("Unsuccessful token request")
            }

            if (tokenData.isAnonymous) {
                // An expired or revoked sp_dc is still answered, with a guest token that lyrics refuse.
                Logger.e("Auth", "Spotify: sp_dc is no longer valid (anonymous token) — the user must log in to Spotify again")
            } else {
                val minutesLeft = (tokenData.accessTokenExpirationTimestampMs - Clock.System.now().toEpochMilliseconds()) / 60_000
                Logger.i("Auth", "Spotify: token refreshed, valid for $minutesLeft min")
            }
            tokenData
        }.onFailure { Logger.e("Auth", "Spotify: token refresh failed: ${it.message}") }
}