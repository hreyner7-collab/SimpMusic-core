package com.maxrave.domain.data.model.offline

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.FALSE
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class AutoSyncPlaylistKind {
    LOCAL_PLAYLIST,
    YOUTUBE_PLAYLIST,
}

@Serializable
data class WifiAutoSyncPlaylistRef(
    val id: String,
    val title: String,
    val kind: AutoSyncPlaylistKind,
)

@Serializable
data class WifiAutoSyncStore(
    val playlists: List<WifiAutoSyncPlaylistRef> = emptyList(),
)

object OfflineCacheAndSyncSettings {
    const val KEY_ROLLING_CACHE_ENABLED = "rolling_offline_cache_enabled_v1"
    const val KEY_ROLLING_CACHE_LIMIT_MB = "rolling_offline_cache_limit_mb_v1"
    const val KEY_WIFI_AUTO_SYNC_ENABLED = "wifi_playlist_auto_sync_enabled_v1"
    const val KEY_WIFI_AUTO_SYNC_PLAYLISTS = "wifi_playlist_auto_sync_list_v1"

    const val DEFAULT_ROLLING_CACHE_MB = 1024
    val ROLLING_CACHE_PRESETS_MB = listOf(256, 512, 1024, 2048, 4096)

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun decodePlaylists(raw: String?): List<WifiAutoSyncPlaylistRef> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<WifiAutoSyncStore>(raw).playlists }.getOrDefault(emptyList())
    }

    fun encodePlaylists(list: List<WifiAutoSyncPlaylistRef>): String =
        json.encodeToString(WifiAutoSyncStore(playlists = list))

    fun DataStoreManager.rollingOfflineCacheEnabledFlow(): Flow<Boolean> =
        getString(KEY_ROLLING_CACHE_ENABLED).map { it != FALSE }

    suspend fun DataStoreManager.setRollingOfflineCacheEnabled(enabled: Boolean) {
        putString(KEY_ROLLING_CACHE_ENABLED, if (enabled) TRUE else FALSE)
    }

    fun DataStoreManager.rollingOfflineCacheLimitMbFlow(): Flow<Int> =
        getString(KEY_ROLLING_CACHE_LIMIT_MB).map {
            it?.toIntOrNull()?.coerceIn(64, 10_240) ?: DEFAULT_ROLLING_CACHE_MB
        }

    suspend fun DataStoreManager.setRollingOfflineCacheLimitMb(limitMb: Int) {
        putString(KEY_ROLLING_CACHE_LIMIT_MB, limitMb.coerceIn(64, 10_240).toString())
    }

    fun DataStoreManager.wifiAutoSyncEnabledFlow(): Flow<Boolean> =
        getString(KEY_WIFI_AUTO_SYNC_ENABLED).map { it != FALSE }

    suspend fun DataStoreManager.setWifiAutoSyncEnabled(enabled: Boolean) {
        putString(KEY_WIFI_AUTO_SYNC_ENABLED, if (enabled) TRUE else FALSE)
    }

    fun DataStoreManager.wifiAutoSyncPlaylistsFlow(): Flow<List<WifiAutoSyncPlaylistRef>> =
        getString(KEY_WIFI_AUTO_SYNC_PLAYLISTS).map { decodePlaylists(it) }

    suspend fun DataStoreManager.getWifiAutoSyncPlaylists(): List<WifiAutoSyncPlaylistRef> =
        decodePlaylists(getString(KEY_WIFI_AUTO_SYNC_PLAYLISTS).first())

    suspend fun DataStoreManager.toggleWifiAutoSyncPlaylist(ref: WifiAutoSyncPlaylistRef): Boolean {
        val current = getWifiAutoSyncPlaylists()
        val exists = current.any { it.id == ref.id && it.kind == ref.kind }
        val updated =
            if (exists) {
                current.filterNot { it.id == ref.id && it.kind == ref.kind }
            } else {
                current + ref
            }
        putString(KEY_WIFI_AUTO_SYNC_PLAYLISTS, encodePlaylists(updated))
        return !exists
    }

    fun List<WifiAutoSyncPlaylistRef>.containsPlaylist(
        id: String,
        kind: AutoSyncPlaylistKind,
    ): Boolean = any { it.id == id && it.kind == kind }
}
