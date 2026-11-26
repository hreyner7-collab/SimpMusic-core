package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SpotifyImportProgress
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SpotifyLibraryItem
import com.maxrave.domain.repository.SpotifyLibraryKind
import com.maxrave.domain.repository.SpotifyLibraryRepository
import com.maxrave.domain.utils.ParsedPlaylistLink
import com.maxrave.domain.utils.PlaylistLinkParser
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.SpotifyTrackMatcher
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import com.maxrave.spotify.Spotify
import com.maxrave.spotify.SpotifyApiException
import com.maxrave.spotify.auth.SpotifyLoginRequest
import com.maxrave.spotify.auth.SpotifyOAuth
import com.maxrave.spotify.auth.SpotifyOAuthException
import com.maxrave.spotify.auth.SpotifyOAuthTokens
import com.maxrave.spotify.model.SpotifyTrackDto
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.time.Clock

private const val TAG = "SpotifyLibraryRepo"
private const val SYNC_MAP_DATASTORE_KEY = "spotify_synced_playlist_map"
private const val LIBRARY_CACHE_DATASTORE_KEY = "spotify_library_cache_v1"
private const val LIKED_SONGS_ITEM_ID = "liked_songs"
private const val MAX_LIBRARY_LIST_PAGES = 5
private const val MAX_API_PAGES = 20
private const val LIBRARY_CACHE_TTL_MS = 5 * 60_000L

private class SpotifySessionExpiredException(
    message: String,
) : IllegalStateException(message)

internal class SpotifyLibraryRepositoryImpl(
    private val spotify: Spotify,
    private val dataStoreManager: DataStoreManager,
    private val searchRepository: SearchRepository,
    private val playlistRepository: PlaylistRepository,
    private val localDataSource: LocalDataSource,
) : SpotifyLibraryRepository {
    @Serializable
    private data class SyncedPlaylistRecord(
        val localPlaylistId: Long,
        val lastSyncedAtMs: Long,
    )

    @Serializable
    private data class CachedSpotifyItem(
        val id: String,
        val kindName: String,
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String? = null,
        val trackCount: Int = 0,
    )

    @Serializable
    private data class CachedSpotifyLibrary(
        val fetchedAtMs: Long,
        val items: List<CachedSpotifyItem>,
    )

    private data class ImportableTrack(
        val title: String,
        val artists: List<String>,
        val durationMs: Long,
    )

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val tokenMutex = Mutex()
    private val syncMapMutex = Mutex()
    private val cacheMutex = Mutex()

    @Volatile private var lastKnownSpdc: String = ""
    @Volatile private var lastKnownAuthKey: String = ""
    @Volatile private var cachedLibraryItems: List<SpotifyLibraryItem> = emptyList()
    @Volatile private var cachedLibraryAtMs: Long = 0L

    override fun isSpotifyConnected(): Flow<Boolean> =
        combine(dataStoreManager.spdc, dataStoreManager.spotifyOAuthRefreshToken) { spdc, own ->
            normalizeSpdc(spdc).isNotBlank() || own.isNotBlank()
        }.distinctUntilChanged()

    override fun hasOwnAppSession(): Flow<Boolean> =
        dataStoreManager.spotifyOAuthRefreshToken.map { it.isNotBlank() }.distinctUntilChanged()

    @Volatile private var pendingOwnLogin: SpotifyLoginRequest? = null

    override suspend fun startOwnAppLogin(clientId: String): Result<String> =
        runCatching {
            val id = clientId.trim()
            require(id.isNotEmpty()) { "Paste the Client ID of your Spotify app first." }
            dataStoreManager.setSpotifyOAuthClientId(id)
            val request = SpotifyOAuth.newLogin(id)
            pendingOwnLogin = request
            request.authorizeUrl
        }

    override suspend fun completeOwnAppLogin(callbackUrl: String): Result<Unit> =
        runCatching {
            val request = pendingOwnLogin ?: error("Start the Spotify login first, then paste the link it ends on.")
            val tokens = SpotifyOAuth.completeLogin(callbackUrl, request)
            pendingOwnLogin = null
            saveOwnAppTokens(tokens, previousRefresh = "")
            Logger.i("Auth", "Spotify own-app login saved")
        }

    override suspend fun signOutOwnApp() {
        pendingOwnLogin = null
        dataStoreManager.setSpotifyOAuthRefreshToken("")
        dataStoreManager.setSpotifyOAuthAccessToken("")
        dataStoreManager.setSpotifyOAuthExpiresAt(0L)
        Logger.i("Auth", "Spotify own-app login cleared")
    }

    private suspend fun saveOwnAppTokens(
        tokens: SpotifyOAuthTokens,
        previousRefresh: String,
    ) {
        dataStoreManager.setSpotifyOAuthAccessToken(tokens.accessToken)
        dataStoreManager.setSpotifyOAuthExpiresAt(Clock.System.now().toEpochMilliseconds() + tokens.expiresInSeconds * 1000L)
        // Spotify only sometimes issues a new refresh token on refresh; the old one stays valid otherwise.
        dataStoreManager.setSpotifyOAuthRefreshToken(tokens.refreshToken ?: previousRefresh)
    }

    /** The token of the user's own Spotify app, refreshed when it is about to expire. */
    private suspend fun ownAppToken(
        clientId: String,
        refreshToken: String,
        forceRefresh: Boolean,
    ): Result<Pair<String, String>> {
        val now = Clock.System.now().toEpochMilliseconds()
        val cached = dataStoreManager.spotifyOAuthAccessToken.first()
        if (!forceRefresh && cached.isNotBlank() && dataStoreManager.spotifyOAuthExpiresAt.first() > now + 60_000L) {
            return Result.success(cached to "")
        }
        return try {
            val tokens = SpotifyOAuth.refresh(clientId, refreshToken)
            saveOwnAppTokens(tokens, previousRefresh = refreshToken)
            Result.success(tokens.accessToken to "")
        } catch (e: SpotifyOAuthException) {
            Logger.w("Auth", "Spotify own-app refresh failed: ${e.message}")
            if (e.invalidGrant) {
                signOutOwnApp()
                Result.failure(SpotifySessionExpiredException("Your Spotify login expired — log in with Spotify again."))
            } else {
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun normalizeSpdc(raw: String): String =
        raw.substringAfter("sp_dc=", raw).substringBefore(";").trim()

    private suspend fun ensureTokens(forceRefresh: Boolean = false): Result<Pair<String, String>> =
        tokenMutex.withLock {
            // The user's own Spotify app comes first: it has its own quota, while the cookie login
            // borrows the web player's shared one, which is what answers 429.
            val ownClientId = dataStoreManager.spotifyOAuthClientId.first()
            val ownRefresh = dataStoreManager.spotifyOAuthRefreshToken.first()
            if (ownClientId.isNotBlank() && ownRefresh.isNotBlank()) {
                return@withLock ownAppToken(ownClientId, ownRefresh, forceRefresh)
            }
            val spdc = normalizeSpdc(dataStoreManager.spdc.first())
            if (spdc.isBlank()) {
                return@withLock Result.failure(IllegalStateException("Spotify account not connected"))
            }

            val spdcChanged = lastKnownSpdc.isNotEmpty() && lastKnownSpdc != spdc
            lastKnownSpdc = spdc
            val shouldForce = forceRefresh || spdcChanged

            val now = Clock.System.now().toEpochMilliseconds()
            val cachedPersonal = dataStoreManager.spotifyPersonalToken.first()
            val cachedClient = dataStoreManager.spotifyClientToken.first()
            val personalValid =
                !shouldForce &&
                    cachedPersonal.isNotBlank() &&
                    dataStoreManager.spotifyPersonalTokenExpires.first() > now + 60_000L
            val clientValid =
                !shouldForce &&
                    cachedClient.isNotBlank() &&
                    dataStoreManager.spotifyClientTokenExpires.first() > now + 60_000L

            if (personalValid && clientValid) {
                return@withLock Result.success(cachedPersonal to cachedClient)
            }

            var personal = if (personalValid) cachedPersonal else ""
            var client = if (clientValid) cachedClient else ""

            if (client.isBlank()) {
                spotify
                    .getClientToken()
                    .onSuccess {
                        client = it.grantedToken.token
                        dataStoreManager.setSpotifyClientToken(client)
                        dataStoreManager.setSpotifyClientTokenExpires(
                            (it.grantedToken.expiresAfterSeconds * 1000L) + now,
                        )
                    }.onFailure {
                        Logger.w(TAG, "Spotify client token refresh failed: ${it.message}")
                    }
            }

            var sessionExpired = false
            if (personal.isBlank()) {
                val totpResult = spotify.getPersonalTokenWithTotp(spdc)
                val tokenResponse =
                    if (totpResult.isSuccess) {
                        totpResult
                    } else {
                        Logger.w(TAG, "TOTP token failed (${totpResult.exceptionOrNull()?.message}); trying getPersonalToken")
                        spotify.getPersonalToken(spdc)
                    }
                tokenResponse
                    .onSuccess {
                        if (it.isAnonymous) {
                            sessionExpired = true
                            dataStoreManager.setSpotifyPersonalToken("")
                            dataStoreManager.setSpotifyPersonalTokenExpires(0L)
                            Logger.w(TAG, "Spotify returned anonymous token; sp_dc session expired")
                        } else if (it.accessToken.isNotBlank()) {
                            personal = it.accessToken
                            dataStoreManager.setSpotifyPersonalToken(personal)
                            dataStoreManager.setSpotifyPersonalTokenExpires(it.accessTokenExpirationTimestampMs)
                        }
                    }.onFailure {
                        Logger.w(TAG, "Spotify personal token refresh failed: ${it.message}")
                    }
            }

            when {
                sessionExpired -> Result.failure(
                    SpotifySessionExpiredException("Spotify session expired — tap Reconnect Spotify to sign in again."),
                )
                personal.isBlank() -> Result.failure(
                    IllegalStateException("Could not authenticate with Spotify — tap Reconnect Spotify to sign in again."),
                )
                else -> Result.success(personal to client)
            }
        }

    private suspend fun <T> withAuthRetry(block: suspend (personalToken: String, clientToken: String) -> Result<T>): Result<T> {
        val initial = ensureTokens(forceRefresh = false).getOrElse { return Result.failure(it) }
        val firstResult = block(initial.first, initial.second)
        if (firstResult.isSuccess) return firstResult
        val err = firstResult.exceptionOrNull()
        if (err is SpotifySessionExpiredException) return firstResult

        val status = (err as? SpotifyApiException)?.statusCode
        val isAuthError = status == 401 || status == 403 || err?.message?.contains("401") == true
        if (isAuthError) {
            val refreshed = ensureTokens(forceRefresh = true).getOrElse { return Result.failure(it) }
            return block(refreshed.first, refreshed.second)
        }
        return firstResult
    }

    private suspend fun readSyncMap(): MutableMap<String, SyncedPlaylistRecord> =
        syncMapMutex.withLock {
            val raw = dataStoreManager.getString(SYNC_MAP_DATASTORE_KEY).firstOrNull().orEmpty()
            if (raw.isBlank()) return@withLock mutableMapOf()
            runCatching {
                json.decodeFromString<Map<String, SyncedPlaylistRecord>>(raw).toMutableMap()
            }.getOrDefault(mutableMapOf())
        }

    private suspend fun recordSyncedPlaylist(
        key: String,
        localPlaylistId: Long,
    ) {
        syncMapMutex.withLock {
            val raw = dataStoreManager.getString(SYNC_MAP_DATASTORE_KEY).firstOrNull().orEmpty()
            val map =
                if (raw.isBlank()) {
                    mutableMapOf()
                } else {
                    runCatching { json.decodeFromString<Map<String, SyncedPlaylistRecord>>(raw).toMutableMap() }
                        .getOrDefault(mutableMapOf())
                }
            map[key] =
                SyncedPlaylistRecord(
                    localPlaylistId = localPlaylistId,
                    lastSyncedAtMs = Clock.System.now().toEpochMilliseconds(),
                )
            dataStoreManager.putString(SYNC_MAP_DATASTORE_KEY, json.encodeToString(map))
        }
    }

    private fun syncKey(
        kind: SpotifyLibraryKind,
        id: String,
    ): String = "${kind.name}:$id"

    private suspend fun validSyncRecords(): Map<String, SyncedPlaylistRecord> {
        val syncMap = readSyncMap()
        val valid = mutableMapOf<String, SyncedPlaylistRecord>()
        for ((k, rec) in syncMap) {
            if (localDataSource.getLocalPlaylist(rec.localPlaylistId) != null) {
                valid[k] = rec
            }
        }
        return valid
    }

    override suspend fun refreshLocalSyncStatus(items: List<SpotifyLibraryItem>): List<SpotifyLibraryItem> {
        if (items.isEmpty()) return items
        val valid = validSyncRecords()
        return items.map { item ->
            val rec = valid[syncKey(item.kind, item.id)]
            item.copy(
                localPlaylistId = rec?.localPlaylistId,
                lastSyncedAtMs = rec?.lastSyncedAtMs,
            )
        }
    }

    private suspend fun readDiskLibraryCache(): CachedSpotifyLibrary? =
        cacheMutex.withLock {
            val raw = dataStoreManager.getString(LIBRARY_CACHE_DATASTORE_KEY).firstOrNull().orEmpty()
            if (raw.isBlank()) return@withLock null
            runCatching { json.decodeFromString<CachedSpotifyLibrary>(raw) }.getOrNull()
        }

    private suspend fun writeDiskLibraryCache(
        fetchedAtMs: Long,
        items: List<SpotifyLibraryItem>,
    ) {
        cacheMutex.withLock {
            val payload =
                CachedSpotifyLibrary(
                    fetchedAtMs = fetchedAtMs,
                    items =
                        items.map {
                            CachedSpotifyItem(
                                id = it.id,
                                kindName = it.kind.name,
                                title = it.title,
                                subtitle = it.subtitle,
                                thumbnailUrl = it.thumbnailUrl,
                                trackCount = it.trackCount,
                            )
                        },
                )
            runCatching {
                dataStoreManager.putString(LIBRARY_CACHE_DATASTORE_KEY, json.encodeToString(payload))
            }
        }
    }

    private suspend fun rehydrateDiskCache(cache: CachedSpotifyLibrary): List<SpotifyLibraryItem> {
        val valid = validSyncRecords()
        return cache.items.mapNotNull { entry ->
            val kind = runCatching { SpotifyLibraryKind.valueOf(entry.kindName) }.getOrNull() ?: return@mapNotNull null
            val rec = valid[syncKey(kind, entry.id)]
            SpotifyLibraryItem(
                id = entry.id,
                kind = kind,
                title = entry.title,
                subtitle = entry.subtitle,
                thumbnailUrl = entry.thumbnailUrl,
                trackCount = entry.trackCount,
                localPlaylistId = rec?.localPlaylistId,
                lastSyncedAtMs = rec?.lastSyncedAtMs,
            )
        }
    }

    override suspend fun getUserSpotifyLibrary(forceRefresh: Boolean): Resource<List<SpotifyLibraryItem>> {
        val currentSpdc = normalizeSpdc(dataStoreManager.spdc.first())
        val oauthClientId = dataStoreManager.spotifyOAuthClientId.first().trim()
        val oauthRefresh = dataStoreManager.spotifyOAuthRefreshToken.first().trim()
        val hasOwnAppAuth = oauthClientId.isNotBlank() && oauthRefresh.isNotBlank()
        if (currentSpdc.isBlank() && !hasOwnAppAuth) {
            return Resource.Error("Spotify account is not connected")
        }

        val currentAuthKey =
            if (hasOwnAppAuth) {
                "pkce:$oauthClientId:${oauthRefresh.take(16)}"
            } else {
                "spdc:$currentSpdc"
            }
        val now = Clock.System.now().toEpochMilliseconds()
        val authSame = lastKnownAuthKey.isEmpty() || lastKnownAuthKey == currentAuthKey
        lastKnownAuthKey = currentAuthKey
        if (!forceRefresh && authSame) {
            if (cachedLibraryItems.isNotEmpty() && now - cachedLibraryAtMs <= LIBRARY_CACHE_TTL_MS) {
                return Resource.Success(refreshLocalSyncStatus(cachedLibraryItems))
            }
            val disk = readDiskLibraryCache()
            if (disk != null && disk.items.isNotEmpty() && now - disk.fetchedAtMs <= LIBRARY_CACHE_TTL_MS) {
                val restored = rehydrateDiskCache(disk)
                if (restored.isNotEmpty()) {
                    cachedLibraryItems = restored
                    cachedLibraryAtMs = disk.fetchedAtMs
                    return Resource.Success(restored)
                }
            }
        }

        val staleFallback =
            if (authSame) {
                cachedLibraryItems.takeIf { it.isNotEmpty() }
                    ?: readDiskLibraryCache()?.let { rehydrateDiskCache(it) }.orEmpty()
            } else {
                emptyList()
            }

        val existingValidRecords = validSyncRecords()
        val items = mutableListOf<SpotifyLibraryItem>()

        // 1. Saved / Liked Songs count
        val likedResp =
            withAuthRetry { p, c ->
                spotify.getSavedTracks(token = p, clientToken = c, limit = 1, offset = 0)
            }
        if (likedResp.isFailure) {
            val err = likedResp.exceptionOrNull()
            if (err is SpotifySessionExpiredException) {
                return Resource.Error(err.message ?: "Spotify session expired — tap Reconnect Spotify to sign in again.")
            }
            if ((err as? SpotifyApiException)?.statusCode == 429) {
                if (staleFallback.isNotEmpty()) {
                    return Resource.Success(refreshLocalSyncStatus(staleFallback))
                }
                return Resource.Error(err.message ?: "Spotify rate-limited requests — wait a moment and tap Retry, or use Import Link.")
            }
            Logger.w(TAG, "Liked Songs count unavailable (${err?.message}); continuing to playlists")
        }
        likedResp.onSuccess { page ->
            if (page.total > 0) {
                val key = syncKey(SpotifyLibraryKind.LIKED_SONGS, LIKED_SONGS_ITEM_ID)
                val synced = existingValidRecords[key]
                items.add(
                    SpotifyLibraryItem(
                        id = LIKED_SONGS_ITEM_ID,
                        kind = SpotifyLibraryKind.LIKED_SONGS,
                        title = "Liked Songs",
                        subtitle = "Spotify Liked Songs",
                        thumbnailUrl = page.items.firstOrNull()?.effectiveTrack?.album?.images?.firstOrNull()?.url,
                        trackCount = page.total,
                        localPlaylistId = synced?.localPlaylistId,
                        lastSyncedAtMs = synced?.lastSyncedAtMs,
                    ),
                )
            }
        }

        // 2. User playlists (paginated)
        var playlistOffset = 0
        for (pageIdx in 0 until MAX_LIBRARY_LIST_PAGES) {
            val res =
                withAuthRetry { p, c ->
                    spotify.getUserPlaylists(token = p, clientToken = c, limit = 50, offset = playlistOffset)
                }
            val page =
                res.getOrElse { err ->
                    if (items.isEmpty() && pageIdx == 0) {
                        if (err !is SpotifySessionExpiredException && staleFallback.isNotEmpty()) {
                            return Resource.Success(refreshLocalSyncStatus(staleFallback))
                        }
                        return Resource.Error(err.message ?: "Failed to load Spotify library")
                    }
                    break
                }
            page.items.forEach { pl ->
                if (pl.id.isNotBlank()) {
                    val key = syncKey(SpotifyLibraryKind.PLAYLIST, pl.id)
                    val synced = existingValidRecords[key]
                    items.add(
                        SpotifyLibraryItem(
                            id = pl.id,
                            kind = SpotifyLibraryKind.PLAYLIST,
                            title = pl.name.ifBlank { "Spotify Playlist" },
                            subtitle = pl.owner?.displayName?.takeIf { it.isNotBlank() } ?: "Spotify Playlist",
                            thumbnailUrl = pl.images?.firstOrNull()?.url,
                            trackCount = pl.totalTracks,
                            localPlaylistId = synced?.localPlaylistId,
                            lastSyncedAtMs = synced?.lastSyncedAtMs,
                        ),
                    )
                }
            }
            if (page.next == null || page.items.isEmpty()) break
            playlistOffset += page.items.size
        }

        // 3. Saved albums (paginated)
        var albumOffset = 0
        for (pageIdx in 0 until MAX_LIBRARY_LIST_PAGES) {
            val res =
                withAuthRetry { p, c ->
                    spotify.getSavedAlbums(token = p, clientToken = c, limit = 50, offset = albumOffset)
                }
            val page = res.getOrNull() ?: break
            page.items.mapNotNull { it.album }.forEach { alb ->
                if (alb.id.isNotBlank()) {
                    val key = syncKey(SpotifyLibraryKind.ALBUM, alb.id)
                    val synced = existingValidRecords[key]
                    val artistLine = alb.artists.joinToString(", ") { it.name }.ifBlank { "Spotify Album" }
                    items.add(
                        SpotifyLibraryItem(
                            id = alb.id,
                            kind = SpotifyLibraryKind.ALBUM,
                            title = alb.name.ifBlank { "Spotify Album" },
                            subtitle = artistLine,
                            thumbnailUrl = alb.images?.firstOrNull()?.url,
                            trackCount = alb.totalTracks,
                            localPlaylistId = synced?.localPlaylistId,
                            lastSyncedAtMs = synced?.lastSyncedAtMs,
                        ),
                    )
                }
            }
            if (page.next == null || page.items.isEmpty()) break
            albumOffset += page.items.size
        }

        if (items.isNotEmpty()) {
            val fetchedAt = Clock.System.now().toEpochMilliseconds()
            cachedLibraryItems = items
            cachedLibraryAtMs = fetchedAt
            writeDiskLibraryCache(fetchedAt, items)
        }

        return Resource.Success(items)
    }

    override fun importSpotifyItem(item: SpotifyLibraryItem): Flow<SpotifyImportProgress> =
        channelFlow {
            val key = syncKey(item.kind, item.id)
            send(SpotifyImportProgress(total = item.trackCount, processed = 0, matched = 0, failed = 0, playlistTitle = item.title))

            val fetched =
                when (item.kind) {
                    SpotifyLibraryKind.LIKED_SONGS -> fetchLikedTracks()
                    SpotifyLibraryKind.PLAYLIST -> {
                        val apiRes = fetchPlaylistTracksViaApi(item.id, item.title, item.thumbnailUrl)
                        if (apiRes.isSuccess && apiRes.getOrNull()?.tracks?.isNotEmpty() == true) {
                            apiRes
                        } else {
                            fetchEmbedCollection("playlist", item.id)
                                .takeIf { it.isSuccess && it.getOrNull()?.tracks?.isNotEmpty() == true }
                                ?: apiRes
                        }
                    }
                    SpotifyLibraryKind.ALBUM -> {
                        val apiRes = fetchAlbumTracksViaApi(item.id, item.title, item.thumbnailUrl)
                        if (apiRes.isSuccess && apiRes.getOrNull()?.tracks?.isNotEmpty() == true) {
                            apiRes
                        } else {
                            fetchEmbedCollection("album", item.id)
                                .takeIf { it.isSuccess && it.getOrNull()?.tracks?.isNotEmpty() == true }
                                ?: apiRes
                        }
                    }
                    SpotifyLibraryKind.TRACK -> fetchEmbedCollection("track", item.id)
                }

            val collection =
                fetched.getOrElse { err ->
                    send(
                        SpotifyImportProgress(
                            total = 0,
                            processed = 0,
                            matched = 0,
                            failed = 0,
                            playlistTitle = item.title,
                            isFinished = true,
                            errorMessage = err.message ?: "Failed to load Spotify tracks",
                        ),
                    )
                    return@channelFlow
                }

            importTracksIntoLocalPlaylist(
                playlistTitle = collection.title.ifBlank { item.title },
                thumbnailUrl = collection.thumbnailUrl ?: item.thumbnailUrl,
                youtubePlaylistId = null,
                syncMapKey = key,
                explicitLocalPlaylistId = item.localPlaylistId,
                tracks = collection.tracks,
                emitProgress = { send(it) },
            )
        }.flowOn(Dispatchers.IO)

    override fun importFromLink(url: String): Flow<SpotifyImportProgress> =
        channelFlow {
            val parsed = PlaylistLinkParser.parse(url)
            if (parsed == null) {
                send(
                    SpotifyImportProgress(
                        total = 0,
                        processed = 0,
                        matched = 0,
                        failed = 0,
                        isFinished = true,
                        errorMessage = "Unsupported link. Paste a Spotify playlist/album/track or YouTube playlist URL.",
                    ),
                )
                return@channelFlow
            }

            when (parsed) {
                is ParsedPlaylistLink.YouTubePlaylist -> {
                    importYouTubePlaylist(parsed.playlistId) { send(it) }
                }

                is ParsedPlaylistLink.SpotifyPlaylist -> {
                    val connected = isSpotifyConnected().first()
                    val apiResult =
                        if (connected) {
                            fetchPlaylistTracksViaApi(parsed.id, "Spotify Playlist", null)
                        } else {
                            Result.failure(IllegalStateException("Not connected"))
                        }
                    val resolved =
                        if (apiResult.isSuccess && apiResult.getOrNull()?.tracks?.isNotEmpty() == true) {
                            apiResult
                        } else {
                            fetchEmbedCollection("playlist", parsed.id)
                        }
                    val collection =
                        resolved.getOrElse { err ->
                            send(
                                SpotifyImportProgress(
                                    total = 0,
                                    processed = 0,
                                    matched = 0,
                                    failed = 0,
                                    isFinished = true,
                                    errorMessage = err.message ?: "Could not load Spotify playlist",
                                ),
                            )
                            return@channelFlow
                        }
                    importTracksIntoLocalPlaylist(
                        playlistTitle = collection.title,
                        thumbnailUrl = collection.thumbnailUrl,
                        youtubePlaylistId = null,
                        syncMapKey = syncKey(SpotifyLibraryKind.PLAYLIST, parsed.id),
                        explicitLocalPlaylistId = null,
                        tracks = collection.tracks,
                        emitProgress = { send(it) },
                    )
                }

                is ParsedPlaylistLink.SpotifyAlbum -> {
                    val connected = isSpotifyConnected().first()
                    val apiResult =
                        if (connected) {
                            fetchAlbumTracksViaApi(parsed.id, "Spotify Album", null)
                        } else {
                            Result.failure(IllegalStateException("Not connected"))
                        }
                    val resolved =
                        if (apiResult.isSuccess && apiResult.getOrNull()?.tracks?.isNotEmpty() == true) {
                            apiResult
                        } else {
                            fetchEmbedCollection("album", parsed.id)
                        }
                    val collection =
                        resolved.getOrElse { err ->
                            send(
                                SpotifyImportProgress(
                                    total = 0,
                                    processed = 0,
                                    matched = 0,
                                    failed = 0,
                                    isFinished = true,
                                    errorMessage = err.message ?: "Could not load Spotify album",
                                ),
                            )
                            return@channelFlow
                        }
                    importTracksIntoLocalPlaylist(
                        playlistTitle = collection.title,
                        thumbnailUrl = collection.thumbnailUrl,
                        youtubePlaylistId = null,
                        syncMapKey = syncKey(SpotifyLibraryKind.ALBUM, parsed.id),
                        explicitLocalPlaylistId = null,
                        tracks = collection.tracks,
                        emitProgress = { send(it) },
                    )
                }

                is ParsedPlaylistLink.SpotifyTrack -> {
                    val collection =
                        fetchEmbedCollection("track", parsed.id).getOrElse { err ->
                            send(
                                SpotifyImportProgress(
                                    total = 0,
                                    processed = 0,
                                    matched = 0,
                                    failed = 0,
                                    isFinished = true,
                                    errorMessage = err.message ?: "Could not load Spotify track",
                                ),
                            )
                            return@channelFlow
                        }
                    importTracksIntoLocalPlaylist(
                        playlistTitle = collection.title,
                        thumbnailUrl = collection.thumbnailUrl,
                        youtubePlaylistId = null,
                        syncMapKey = syncKey(SpotifyLibraryKind.TRACK, parsed.id),
                        explicitLocalPlaylistId = null,
                        tracks = collection.tracks,
                        emitProgress = { send(it) },
                    )
                }
            }
        }.flowOn(Dispatchers.IO)

    override fun syncAllSpotifyLibrary(): Flow<SpotifyImportProgress> =
        channelFlow {
            val libraryRes = getUserSpotifyLibrary()
            val items =
                (libraryRes as? Resource.Success)?.data.orEmpty()
            if (libraryRes is Resource.Error || items.isEmpty()) {
                send(
                    SpotifyImportProgress(
                        total = 0,
                        processed = 0,
                        matched = 0,
                        failed = 0,
                        isFinished = true,
                        errorMessage = (libraryRes as? Resource.Error)?.message ?: "Nothing to sync in Spotify library",
                    ),
                )
                return@channelFlow
            }

            val total = items.size
            var processed = 0
            var totalMatched = 0
            var totalFailed = 0
            var lastPlaylistId: Long? = null

            for (item in items) {
                var itemFailed = 0
                var itemMatched = 0
                var itemPlaylistId: Long? = null
                importSpotifyItem(item).collect { prog ->
                    if (prog.isFinished) {
                        itemMatched = prog.matched
                        itemFailed = prog.failed
                        itemPlaylistId = prog.targetLocalPlaylistId
                    } else {
                        send(
                            SpotifyImportProgress(
                                total = total,
                                processed = processed,
                                matched = totalMatched,
                                failed = totalFailed,
                                currentTitle = "${item.title}: ${prog.currentTitle}",
                                playlistTitle = "Syncing Spotify Library",
                            ),
                        )
                    }
                }
                processed++
                totalMatched += itemMatched
                totalFailed += itemFailed
                if (itemPlaylistId != null) lastPlaylistId = itemPlaylistId
                send(
                    SpotifyImportProgress(
                        total = total,
                        processed = processed,
                        matched = totalMatched,
                        failed = totalFailed,
                        currentTitle = item.title,
                        targetLocalPlaylistId = lastPlaylistId,
                        playlistTitle = "Syncing Spotify Library",
                        isFinished = processed >= total,
                    ),
                )
            }
        }.flowOn(Dispatchers.IO)

    private data class FetchedTrackCollection(
        val title: String,
        val thumbnailUrl: String?,
        val tracks: List<ImportableTrack>,
    )

    private suspend fun fetchLikedTracks(): Result<FetchedTrackCollection> {
        val collected = mutableListOf<ImportableTrack>()
        var thumb: String? = null
        var offset = 0
        for (pageIdx in 0 until MAX_API_PAGES) {
            val res =
                withAuthRetry { p, c ->
                    spotify.getSavedTracks(token = p, clientToken = c, limit = 50, offset = offset)
                }
            val page = res.getOrElse { return if (pageIdx == 0) Result.failure(it) else break }
            if (thumb == null) {
                thumb = page.items.firstOrNull()?.effectiveTrack?.album?.images?.firstOrNull()?.url
            }
            page.items.mapNotNull { it.effectiveTrack }.forEach { dto ->
                dto.toImportable()?.let { collected.add(it) }
            }
            if (page.next == null || page.items.isEmpty()) break
            offset += page.items.size
        }
        return Result.success(FetchedTrackCollection("Liked Songs", thumb, collected))
    }

    private suspend fun fetchPlaylistTracksViaApi(
        playlistId: String,
        fallbackTitle: String,
        fallbackThumbnail: String?,
    ): Result<FetchedTrackCollection> {
        var resolvedTitle = fallbackTitle
        var resolvedThumb = fallbackThumbnail
        withAuthRetry { p, c ->
            spotify.getPlaylistMeta(playlistId, p, c)
        }.onSuccess { meta ->
            if (meta.name.isNotBlank()) resolvedTitle = meta.name
            if (resolvedThumb.isNullOrBlank()) resolvedThumb = meta.images?.firstOrNull()?.url
        }

        val collected = mutableListOf<ImportableTrack>()
        var offset = 0
        for (pageIdx in 0 until MAX_API_PAGES) {
            val res =
                withAuthRetry { p, c ->
                    spotify.getPlaylistTracks(playlistId, p, c, limit = 100, offset = offset)
                }
            val page = res.getOrElse { return if (pageIdx == 0) Result.failure(it) else break }
            page.items.mapNotNull { it.effectiveTrack }.forEach { dto ->
                dto.toImportable()?.let {
                    collected.add(it)
                    if (resolvedThumb.isNullOrBlank()) {
                        resolvedThumb = dto.album?.images?.firstOrNull()?.url
                    }
                }
            }
            if (page.next == null || page.items.isEmpty()) break
            offset += page.items.size
        }
        return Result.success(FetchedTrackCollection(resolvedTitle, resolvedThumb, collected))
    }

    private suspend fun fetchAlbumTracksViaApi(
        albumId: String,
        fallbackTitle: String,
        fallbackThumbnail: String?,
    ): Result<FetchedTrackCollection> {
        val detail =
            withAuthRetry { p, c ->
                spotify.getAlbumDetail(albumId, p, c)
            }.getOrElse { return Result.failure(it) }

        val title = detail.name.ifBlank { fallbackTitle }
        val thumb = detail.images.firstOrNull()?.url ?: fallbackThumbnail
        val albumArtists = detail.artists.mapNotNull { it.name.takeIf(String::isNotBlank) }
        val collected = mutableListOf<ImportableTrack>()

        val firstPage = detail.effectiveTracks
        firstPage?.items?.forEach { dto ->
            dto.toImportable(albumArtists)?.let { collected.add(it) }
        }
        var nextUrl = firstPage?.next
        var pageGuard = 0
        while (!nextUrl.isNullOrBlank() && pageGuard < MAX_API_PAGES) {
            pageGuard++
            val urlToFetch = nextUrl
            val nextPage =
                withAuthRetry { p, c ->
                    spotify.getAlbumTracksPage(urlToFetch, p, c)
                }.getOrNull() ?: break
            nextPage.items.forEach { dto ->
                dto.toImportable(albumArtists)?.let { collected.add(it) }
            }
            nextUrl = nextPage.next
        }
        return Result.success(FetchedTrackCollection(title, thumb, collected))
    }

    private suspend fun fetchEmbedCollection(
        kind: String,
        id: String,
    ): Result<FetchedTrackCollection> =
        spotify.getPublicEmbedCollection(kind, id).map { col ->
            FetchedTrackCollection(
                title = col.title,
                thumbnailUrl = col.thumbnailUrl,
                tracks =
                    col.tracks.map {
                        ImportableTrack(
                            title = it.title,
                            artists = it.artists,
                            durationMs = it.durationMs,
                        )
                    },
            )
        }

    private fun SpotifyTrackDto.toImportable(fallbackArtists: List<String> = emptyList()): ImportableTrack? {
        if (name.isBlank() || isLocal) return null
        val artistNames =
            artists.mapNotNull { it.name.takeIf(String::isNotBlank) }.ifEmpty { fallbackArtists }
        return ImportableTrack(
            title = name,
            artists = artistNames,
            durationMs = durationMs,
        )
    }

    private suspend fun importYouTubePlaylist(
        playlistId: String,
        emitProgress: suspend (SpotifyImportProgress) -> Unit,
    ) {
        emitProgress(SpotifyImportProgress(total = 1, processed = 0, matched = 0, failed = 0, playlistTitle = "YouTube Playlist"))
        val res =
            playlistRepository
                .getFullPlaylistData(playlistId, "")
                .lastOrNull()
        val browse =
            (res as? Resource.Success)?.data
                ?: run {
                    emitProgress(
                        SpotifyImportProgress(
                            total = 0,
                            processed = 0,
                            matched = 0,
                            failed = 0,
                            isFinished = true,
                            errorMessage = (res as? Resource.Error)?.message ?: "Failed to load YouTube playlist",
                        ),
                    )
                    return
                }

        val songs = browse.tracks.map { it.toSongEntity() }.distinctBy { it.videoId }
        val existing = localDataSource.getLocalPlaylistByYoutubePlaylistId(playlistId)
        val thumb = browse.thumbnails.lastOrNull()?.url
        val finalId =
            upsertLocalPlaylist(
                title = browse.title.ifBlank { "YouTube Playlist" },
                thumbnailUrl = thumb,
                youtubePlaylistId = playlistId,
                explicitLocalPlaylistId = existing?.id,
                syncMapKey = null,
                songs = songs,
            )
        emitProgress(
            SpotifyImportProgress(
                total = songs.size,
                processed = songs.size,
                matched = songs.size,
                failed = 0,
                targetLocalPlaylistId = finalId,
                playlistTitle = browse.title,
                isFinished = true,
            ),
        )
    }

    private suspend fun importTracksIntoLocalPlaylist(
        playlistTitle: String,
        thumbnailUrl: String?,
        youtubePlaylistId: String?,
        syncMapKey: String?,
        explicitLocalPlaylistId: Long?,
        tracks: List<ImportableTrack>,
        emitProgress: suspend (SpotifyImportProgress) -> Unit,
    ) {
        if (tracks.isEmpty()) {
            emitProgress(
                SpotifyImportProgress(
                    total = 0,
                    processed = 0,
                    matched = 0,
                    failed = 0,
                    playlistTitle = playlistTitle,
                    isFinished = true,
                    errorMessage = "No tracks found to import in \"$playlistTitle\".",
                ),
            )
            return
        }

        val total = tracks.size
        val semaphore = Semaphore(4)
        val counterMutex = Mutex()
        var processed = 0
        var matchedCount = 0
        var failedCount = 0
        val matchedByIndex = arrayOfNulls<SongEntity>(total)

        coroutineScope {
            tracks.mapIndexed { index, track ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val query =
                            buildString {
                                append(track.title)
                                if (track.artists.isNotEmpty()) {
                                    append(' ')
                                    append(track.artists.first())
                                }
                            }.trim()
                        val candidates =
                            runCatching {
                                val res =
                                    searchRepository
                                        .getSearchDataSong(query)
                                        .firstOrNull()
                                (res as? Resource.Success)?.data.orEmpty()
                            }.getOrDefault(emptyList())

                        var best =
                            SpotifyTrackMatcher.selectBestCandidate(
                                targetTitle = track.title,
                                targetArtists = track.artists,
                                targetDurationMs = track.durationMs,
                                candidates = candidates,
                            )
                        if (best == null) {
                            val fallbackQuery = SpotifyTrackMatcher.buildFallbackQuery(track.title, track.artists)
                            if (fallbackQuery != null) {
                                val fallbackCandidates =
                                    runCatching {
                                        val res =
                                            searchRepository
                                                .getSearchDataSong(fallbackQuery)
                                                .firstOrNull()
                                        (res as? Resource.Success)?.data.orEmpty()
                                    }.getOrDefault(emptyList())
                                best =
                                    SpotifyTrackMatcher.selectBestCandidate(
                                        targetTitle = track.title,
                                        targetArtists = track.artists,
                                        targetDurationMs = track.durationMs,
                                        candidates = fallbackCandidates,
                                    )
                            }
                        }
                        val entity = best?.toTrack()?.toSongEntity()
                        counterMutex.withLock {
                            matchedByIndex[index] = entity
                            processed++
                            if (entity != null) matchedCount++ else failedCount++
                            emitProgress(
                                SpotifyImportProgress(
                                    total = total,
                                    processed = processed,
                                    matched = matchedCount,
                                    failed = failedCount,
                                    currentTitle = track.title,
                                    playlistTitle = playlistTitle,
                                ),
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        val songs = matchedByIndex.filterNotNull().distinctBy { it.videoId }
        val finalId =
            if (songs.isNotEmpty()) {
                upsertLocalPlaylist(
                    title = playlistTitle,
                    thumbnailUrl = thumbnailUrl,
                    youtubePlaylistId = youtubePlaylistId,
                    explicitLocalPlaylistId = explicitLocalPlaylistId,
                    syncMapKey = syncMapKey,
                    songs = songs,
                )
            } else {
                null
            }

        emitProgress(
            SpotifyImportProgress(
                total = total,
                processed = total,
                matched = matchedCount,
                failed = failedCount,
                targetLocalPlaylistId = finalId,
                playlistTitle = playlistTitle,
                isFinished = true,
                errorMessage =
                    if (songs.isEmpty()) "No matching YouTube Music tracks found for \"$playlistTitle\"." else null,
            ),
        )
    }

    private suspend fun upsertLocalPlaylist(
        title: String,
        thumbnailUrl: String?,
        youtubePlaylistId: String?,
        explicitLocalPlaylistId: Long?,
        syncMapKey: String?,
        songs: List<SongEntity>,
    ): Long {
        localDataSource.insertSongs(songs)
        val newVideoIds = songs.map { it.videoId }.distinct()

        val mappedId =
            syncMapKey?.let { key ->
                readSyncMap()[key]?.localPlaylistId
            }
        val candidateId = explicitLocalPlaylistId ?: mappedId
        val existing = candidateId?.let { localDataSource.getLocalPlaylist(it) }

        val finalId =
            if (existing != null) {
                val existingTrackSet = existing.tracks.orEmpty().toSet()
                val mergedTracks = (existing.tracks.orEmpty() + newVideoIds).distinct()
                mergedTracks.forEachIndexed { idx, vid ->
                    if (vid !in existingTrackSet) {
                        localDataSource.insertPairSongLocalPlaylist(
                            PairSongLocalPlaylist(
                                playlistId = existing.id,
                                songId = vid,
                                position = idx,
                            ),
                        )
                    }
                }
                localDataSource.updateLocalPlaylistTracks(mergedTracks, existing.id)
                if (!thumbnailUrl.isNullOrBlank() && existing.thumbnail.isNullOrBlank()) {
                    localDataSource.updateLocalPlaylistThumbnail(thumbnailUrl, existing.id)
                }
                existing.id
            } else {
                val resolvedThumb =
                    thumbnailUrl?.takeIf { it.isNotBlank() } ?: songs.firstOrNull()?.thumbnails
                localDataSource.insertLocalPlaylistWithTracks(
                    LocalPlaylistEntity(
                        title = title,
                        thumbnail = resolvedThumb,
                        youtubePlaylistId = youtubePlaylistId,
                        tracks = newVideoIds,
                    ),
                    newVideoIds,
                )
            }

        if (syncMapKey != null && finalId > 0L) {
            recordSyncedPlaylist(syncMapKey, finalId)
        }
        return finalId
    }
}
