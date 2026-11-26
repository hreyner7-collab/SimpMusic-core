package com.maxrave.domain.repository

import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

enum class SpotifyLibraryKind {
    LIKED_SONGS,
    PLAYLIST,
    ALBUM,
    TRACK,
}

data class SpotifyLibraryItem(
    val id: String,
    val kind: SpotifyLibraryKind,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val trackCount: Int,
    val localPlaylistId: Long? = null,
    val lastSyncedAtMs: Long? = null,
)

data class SpotifyImportProgress(
    val total: Int,
    val processed: Int,
    val matched: Int,
    val failed: Int,
    val currentTitle: String = "",
    val targetLocalPlaylistId: Long? = null,
    val playlistTitle: String = "",
    val isFinished: Boolean = false,
    val errorMessage: String? = null,
)

interface SpotifyLibraryRepository {
    fun isSpotifyConnected(): Flow<Boolean>

    /** True while the user is signed in through their own Spotify developer app. */
    fun hasOwnAppSession(): Flow<Boolean>

    /** Saves [clientId] and returns the Spotify login page to open in the browser. */
    suspend fun startOwnAppLogin(clientId: String): Result<String>

    /** Finishes the login from the `simpmusic://spotify-auth?code=…` link, or that link pasted by hand. */
    suspend fun completeOwnAppLogin(callbackUrl: String): Result<Unit>

    suspend fun signOutOwnApp()

    suspend fun getUserSpotifyLibrary(forceRefresh: Boolean = false): Resource<List<SpotifyLibraryItem>>

    suspend fun refreshLocalSyncStatus(items: List<SpotifyLibraryItem>): List<SpotifyLibraryItem>

    fun importSpotifyItem(item: SpotifyLibraryItem): Flow<SpotifyImportProgress>

    fun importFromLink(url: String): Flow<SpotifyImportProgress>

    fun syncAllSpotifyLibrary(): Flow<SpotifyImportProgress>
}
