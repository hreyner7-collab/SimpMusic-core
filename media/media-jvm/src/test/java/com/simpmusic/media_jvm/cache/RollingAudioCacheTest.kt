package com.simpmusic.media_jvm.cache

import com.maxrave.common.Config
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class RollingAudioCacheTest {
    @Test
    fun putGetAndLruEvictionRespectByteLimit() {
        val dir = createTempDirectory("simpmusic-rolling-cache-test").toFile()
        try {
            val cache = RollingAudioCache(cacheDir = dir, maxBytesOverride = 2500L)
            val dataA = ByteArray(1000) { 1 }
            val dataB = ByteArray(1000) { 2 }
            val dataC = ByteArray(1000) { 3 }

            val fileA = cache.putBytes("vidA", dataA, 2500L)
            assertNotNull(fileA)
            fileA!!.setLastModified(1_000L)

            val fileB = cache.putBytes("vidB", dataB, 2500L)
            assertNotNull(fileB)
            fileB!!.setLastModified(2_000L)

            // Touch vidA via getCachedFile so vidB becomes the least-recently-used entry!
            val hitA = cache.getCachedFile("vidA")
            assertNotNull(hitA)
            assertArrayEquals(dataA, hitA!!.readBytes())
            hitA.setLastModified(5_000L)

            // Adding vidC pushes total to 3000 > 2500 -> LRU (vidB) must be evicted, vidA & vidC kept!
            val fileC = cache.putBytes("vidC", dataC, 2500L)
            assertNotNull(fileC)
            assertNull("Least recently used entry vidB must be evicted", cache.getCachedFile("vidB"))
            assertNotNull("Recently accessed vidA must stay cached", cache.getCachedFile("vidA"))
            assertNotNull("Newly inserted vidC must be cached", cache.getCachedFile("vidC"))
            assertTrue(cache.totalSizeBytes() <= 2500L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun desktopRollingCacheRepositoryReportsSizeKeysAndClearsCleanly() =
        runBlocking {
            val dir = createTempDirectory("simpmusic-rolling-repo-test").toFile()
            try {
                // Leave a stale .tmp file before init to confirm startup cleanup drops partial temp files
                File(dir, "partial_crash.tmp").writeBytes(ByteArray(512))
                val cache = RollingAudioCache(cacheDir = dir, maxBytesOverride = 10_000L)
                assertEquals(0L, cache.totalSizeBytes())
                assertTrue(dir.listFiles { _, name -> name.endsWith(".tmp") }?.isEmpty() == true)

                val repo = DesktopRollingCacheRepository(cache)
                cache.putBytes("track_1", ByteArray(2048) { 7 }, 10_000L)
                cache.putBytes("track_2", ByteArray(1024) { 9 }, 10_000L)

                assertEquals(3072L, repo.getCacheSize(Config.PLAYER_CACHE))
                assertEquals(setOf("track_1", "track_2"), repo.getFullyCachedSongIds().toSet())

                repo.removeCachedSong("track_1")
                assertEquals(1024L, repo.getCacheSize(Config.PLAYER_CACHE))
                assertEquals(listOf("track_2"), repo.getAllCacheKeys(Config.PLAYER_CACHE))

                repo.clearCache(Config.PLAYER_CACHE)
                assertEquals(0L, repo.getCacheSize(Config.PLAYER_CACHE))
                assertTrue(repo.getFullyCachedSongIds().isEmpty())
            } finally {
                dir.deleteRecursively()
            }
        }
}
