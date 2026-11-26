package com.simpmusic.media_jvm.cache

import com.maxrave.common.Config
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.DEFAULT_ROLLING_CACHE_MB
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.rollingOfflineCacheEnabledFlow
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.rollingOfflineCacheLimitMbFlow
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "RollingAudioCache"

/**
 * Bounded, LRU-evicting on-disk audio cache for Desktop playback.
 *
 * Streams played via mpv are copied to disk asynchronously in the background using atomic
 * `.tmp -> .audio` renames so a cancelled or interrupted download never leaves a corrupt cache
 * entry. Replays hit disk immediately with zero network usage and zero stutter.
 */
class RollingAudioCache(
    private val cacheDir: File = defaultRollingCacheDir(),
    private val dataStoreManager: DataStoreManager? = null,
    private val maxBytesOverride: Long? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lock = Any()

    init {
        runCatching {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            // Clean up any abandoned temp files from a prior hard crash.
            cacheDir.listFiles { _, name -> name.endsWith(".tmp") }?.forEach { it.delete() }
        }
    }

    private fun safeId(videoId: String): String =
        videoId.replace(Regex("""[^A-Za-z0-9_\-]"""), "_")

    private fun entryFile(videoId: String): File = File(cacheDir, "${safeId(videoId)}.audio")

    suspend fun isEnabled(): Boolean {
        val ds = dataStoreManager ?: return true
        return runCatching { ds.rollingOfflineCacheEnabledFlow().first() }.getOrDefault(true)
    }

    suspend fun maxBytes(): Long {
        maxBytesOverride?.let { return it.coerceAtLeast(1024L) }
        val limitMb =
            dataStoreManager?.let {
                runCatching { it.rollingOfflineCacheLimitMbFlow().first() }.getOrNull()
            } ?: DEFAULT_ROLLING_CACHE_MB
        return limitMb.toLong() * 1024L * 1024L
    }

    /** Returns the cached file if present and non-empty, updating mtime for LRU ordering. */
    fun getCachedFile(videoId: String): File? {
        if (videoId.isBlank()) return null
        val file = entryFile(videoId)
        if (!file.exists() || file.length() <= 0L) return null
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return file
    }

    /** Synchronously stores [bytes] atomically and enforces [maxBytesLimit]. */
    fun putBytes(
        videoId: String,
        bytes: ByteArray,
        maxBytesLimit: Long,
    ): File? {
        if (videoId.isBlank() || bytes.isEmpty()) return null
        return synchronized(lock) {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val target = entryFile(videoId)
            val tmp = File(cacheDir, "${safeId(videoId)}_${System.nanoTime()}.tmp")
            try {
                FileOutputStream(tmp).use { it.write(bytes) }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                target.setLastModified(System.currentTimeMillis())
                evictIfNeededLocked(maxBytesLimit)
                target
            } catch (e: Exception) {
                runCatching { tmp.delete() }
                Logger.w(TAG, "putBytes failed for $videoId: ${e.message}")
                null
            }
        }
    }

    /** Copies a remote stream URL to the rolling cache in the background without blocking playback. */
    fun cacheUrlInBackground(
        videoId: String,
        url: String,
    ) {
        if (videoId.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) return
        if (entryFile(videoId).let { it.exists() && it.length() > 0L }) return
        if (!inFlight.add(videoId)) return

        scope.launch {
            try {
                if (!isEnabled()) return@launch
                val limit = maxBytes()
                if (limit <= 0L) return@launch
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val tmp = File(cacheDir, "${safeId(videoId)}_${System.nanoTime()}.tmp")
                var success = false
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 20_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    try {
                        if (conn.responseCode !in 200..299) return@launch
                        val contentLength = conn.contentLengthLong
                        // Skip single files larger than half the configured rolling cache cap (e.g. multi-hour podcasts)
                        if (contentLength > 0L && contentLength > (limit / 2).coerceAtLeast(32L * 1024 * 1024)) {
                            return@launch
                        }
                        var totalWritten = 0L
                        val maxSingleFile = (limit / 2).coerceAtLeast(32L * 1024 * 1024)
                        conn.inputStream.use { input ->
                            FileOutputStream(tmp).use { output ->
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    val read = input.read(buf)
                                    if (read <= 0) break
                                    totalWritten += read
                                    if (totalWritten > maxSingleFile) {
                                        return@launch
                                    }
                                    output.write(buf, 0, read)
                                }
                            }
                        }
                        success = totalWritten > 4096L && (contentLength <= 0L || totalWritten >= contentLength)
                    } finally {
                        conn.disconnect()
                    }
                    if (success) {
                        synchronized(lock) {
                            val target = entryFile(videoId)
                            if (target.exists()) target.delete()
                            if (!tmp.renameTo(target)) {
                                tmp.copyTo(target, overwrite = true)
                                tmp.delete()
                            }
                            target.setLastModified(System.currentTimeMillis())
                            evictIfNeededLocked(limit)
                        }
                        Logger.d(TAG, "Cached $videoId (${entryFile(videoId).length() / 1024} KB)")
                    }
                } finally {
                    if (!success && tmp.exists()) runCatching { tmp.delete() }
                }
            } catch (e: Exception) {
                Logger.d(TAG, "Background cache skipped for $videoId: ${e.message}")
            } finally {
                inFlight.remove(videoId)
            }
        }
    }

    fun totalSizeBytes(): Long =
        synchronized(lock) {
            cacheDir.listFiles { _, name -> name.endsWith(".audio") }?.sumOf { it.length() } ?: 0L
        }

    fun allCachedVideoIds(): List<String> =
        synchronized(lock) {
            cacheDir
                .listFiles { _, name -> name.endsWith(".audio") }
                ?.filter { it.length() > 0L }
                ?.map { it.name.removeSuffix(".audio") }
                .orEmpty()
        }

    fun remove(videoId: String) {
        synchronized(lock) {
            runCatching { entryFile(videoId).delete() }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    fun evictToLimit(maxBytesLimit: Long) {
        synchronized(lock) {
            evictIfNeededLocked(maxBytesLimit)
        }
    }

    private fun evictIfNeededLocked(maxBytesLimit: Long) {
        val files =
            cacheDir
                .listFiles { _, name -> name.endsWith(".audio") }
                ?.toMutableList()
                ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytesLimit) return
        // Evict oldest-accessed files first (true LRU).
        files.sortBy { it.lastModified() }
        for (file in files) {
            if (total <= maxBytesLimit) break
            val len = file.length()
            if (file.delete()) {
                total -= len
            }
        }
    }

    companion object {
        fun defaultRollingCacheDir(): File {
            val base =
                System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                    ?: (System.getProperty("user.home") + File.separator + ".cache")
            return File(File(base, "SimpMusic"), "rolling_audio_cache")
        }
    }
}

internal class DesktopRollingCacheRepository(
    private val rollingAudioCache: RollingAudioCache,
) : CacheRepository {
    override suspend fun getCacheSize(cacheName: String): Long =
        withContext(Dispatchers.IO) {
            when (cacheName) {
                Config.PLAYER_CACHE -> rollingAudioCache.totalSizeBytes()
                else -> 0L
            }
        }

    override fun clearCache(cacheName: String) {
        if (cacheName == Config.PLAYER_CACHE) {
            rollingAudioCache.clearAll()
        }
    }

    override suspend fun getAllCacheKeys(cacheName: String): List<String> =
        withContext(Dispatchers.IO) {
            when (cacheName) {
                Config.PLAYER_CACHE -> rollingAudioCache.allCachedVideoIds()
                else -> emptyList()
            }
        }

    override suspend fun getFullyCachedSongIds(): List<String> =
        withContext(Dispatchers.IO) {
            rollingAudioCache.allCachedVideoIds()
        }

    override suspend fun removeCachedSong(videoId: String) =
        withContext(Dispatchers.IO) {
            rollingAudioCache.remove(videoId)
        }
}
