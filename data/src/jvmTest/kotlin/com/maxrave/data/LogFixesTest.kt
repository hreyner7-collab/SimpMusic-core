package com.maxrave.data

import com.maxrave.kotlinytmusicscraper.inferStreamItag
import com.maxrave.kotlinytmusicscraper.mergeNewPipeStreams
import com.maxrave.kotlinytmusicscraper.models.ResponseContext
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import com.maxrave.kotlinytmusicscraper.raceFirstNonNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.simpmusic.lyrics.models.request.LyricsBody
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogFixesTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    @Test
    fun gridContinuationDeserializesWithoutItemsField() {
        val raw =
            """
            {
              "itemSize": "GRID_ITEM_SIZE_SMALL",
              "continuations": []
            }
            """.trimIndent()
        val parsed = json.decodeFromString<BrowseResponse.ContinuationContents.GridContinuation>(raw)
        assertEquals("GRID_ITEM_SIZE_SMALL", parsed.itemSize)
        assertTrue(parsed.items.isEmpty())
    }

    @Test
    fun lyricsBodyAlwaysSerializesSyncedAndRichSyncLyrics() {
        val body =
            LyricsBody(
                videoId = "abc123",
                songTitle = "Test Song",
                artistName = "Test Artist",
                albumName = "Test Album",
                durationSeconds = 180,
                plainLyric = "hello",
                trackType = "SONG",
                contributor = "User",
                contributorEmail = "user@example.com",
            )
        val encoded = json.encodeToString(body)
        assertTrue(encoded.contains("\"syncedLyrics\":\"\""), "Expected syncedLyrics in JSON: $encoded")
        assertTrue(encoded.contains("\"richSyncLyrics\":\"\""), "Expected richSyncLyrics in JSON: $encoded")
    }

    @Test
    fun mergeNewPipeStreamsSynthesizesPlayableResponseWhenWebRemixRefusesTrack() {
        val unplayableWebRemix =
            PlayerResponse(
                responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null, webResponseContextExtensionData = null),
                playabilityStatus = PlayerResponse.PlayabilityStatus(status = "UNPLAYABLE", reason = "Video unavailable"),
                playerConfig = null,
                streamingData = null,
                videoDetails = null,
                playbackTracking = null,
                captions = null,
            )
        val fallbackStreams =
            listOf(
                251 to "https://rr1---sn-example.googlevideo.com/videoplayback?itag=251&clen=3456789&mime=audio%2Fwebm",
            )
        val merged = mergeNewPipeStreams(unplayableWebRemix, fallbackStreams)
        assertEquals("OK", merged.playabilityStatus.status)
        val sd = assertNotNull(merged.streamingData)
        assertTrue(sd.expiresInSeconds > 0)
        val audio = sd.adaptiveFormats.firstOrNull { it.itag == 251 }
        assertNotNull(audio)
        assertEquals(3456789L, audio.contentLength)
        assertTrue(audio.url?.contains("itag=251") == true)
    }

    @Test
    fun raceFirstNonNullPicksFastestWorkingServiceAndCancelsSlowOnes() =
        runBlocking {
            val slowCancelled = AtomicBoolean(false)
            val startMs = System.currentTimeMillis()
            val winner =
                raceFirstNonNull(
                    listOf(
                        {
                            // Simulates a fast 403/null service -> must NOT win
                            delay(10L)
                            null
                        },
                        {
                            // Simulates the fastest working service (~40ms) -> MUST win immediately
                            delay(40L)
                            "ANDROID_VR_STREAM"
                        },
                        {
                            // Simulates a slow 5s service -> MUST be cancelled as soon as 40ms winner finishes
                            try {
                                delay(5_000L)
                                "SLOW_SERVICE"
                            } catch (c: CancellationException) {
                                slowCancelled.set(true)
                                throw c
                            }
                        },
                    ),
                )
            val elapsedMs = System.currentTimeMillis() - startMs
            assertEquals("ANDROID_VR_STREAM", winner)
            assertTrue(slowCancelled.get(), "Expected slower racer to be cancelled immediately")
            assertTrue(elapsedMs < 1_500L, "Expected fast race completion, took ${elapsedMs}ms")
        }

    @Test
    fun inferStreamItagHandlesPipedMissingItags() {
        assertEquals(251, inferStreamItag(rawItag = -1, isAudio = true, mimeType = "audio/webm", codec = "opus"))
        assertEquals(140, inferStreamItag(rawItag = -1, isAudio = true, mimeType = "audio/mp4", codec = "mp4a.40.2"))
        assertEquals(251, inferStreamItag(rawItag = 251, isAudio = true, mimeType = "", codec = ""))
    }

    @Test
    fun mergeNewPipeStreamsPreservesAac140AudioAndVisionOsClientIsConfigured() {
        assertTrue(com.maxrave.common.ITAG.AUDIO_AAC_MEDIUM in com.maxrave.common.ITAG.AUDIO)
        assertEquals("VISIONOS", com.maxrave.kotlinytmusicscraper.models.YouTubeClient.VISIONOS.clientName)
        assertEquals(101, com.maxrave.kotlinytmusicscraper.models.YouTubeClient.VISIONOS.xClientName)

        val unplayableWebRemix =
            PlayerResponse(
                responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null, webResponseContextExtensionData = null),
                playabilityStatus = PlayerResponse.PlayabilityStatus(status = "UNPLAYABLE", reason = "Video unavailable"),
                playerConfig = null,
                streamingData = null,
                videoDetails = null,
                playbackTracking = null,
                captions = null,
            )
        val aacOnlyStreams =
            listOf(
                140 to "https://rr1---sn-example.googlevideo.com/videoplayback?itag=140&clen=4123456&c=VISIONOS&mime=audio%2Fmp4",
            )
        val merged = mergeNewPipeStreams(unplayableWebRemix, aacOnlyStreams)
        assertEquals("OK", merged.playabilityStatus.status)
        val audio140 = merged.streamingData?.adaptiveFormats?.firstOrNull { it.itag == 140 }
        assertNotNull(audio140)
        assertTrue(audio140.isAudio)
        assertEquals(4123456L, audio140.contentLength)
        assertTrue(audio140.mimeType.contains("mp4a.40.2"))
    }
}
