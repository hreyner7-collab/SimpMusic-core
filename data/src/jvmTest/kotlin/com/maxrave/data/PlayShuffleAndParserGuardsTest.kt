package com.maxrave.data

import com.maxrave.data.mediaservice.JvmMediaPlayerHandlerImpl
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.DiscordRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistRadioEndpoint
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistShuffleEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Test
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayShuffleAndParserGuardsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    @Test
    fun playlistParserExtractsTopLevelButtonRendererShuffleAndRadioWatchEndpoints() {
        val rawResponsive =
            """
            {
              "responseContext": {},
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicResponsiveHeaderRenderer": {
                                  "title": { "runs": [{ "text": "Top songs" }] },
                                  "straplineTextOne": { "runs": [{ "text": "SKEPTA" }] },
                                  "buttons": [
                                    {
                                      "buttonRenderer": {
                                        "icon": { "iconType": "MUSIC_SHUFFLE" },
                                        "navigationEndpoint": {
                                          "watchEndpoint": {
                                            "playlistId": "RDAMPLPL_SKEPTA_2026",
                                            "params": "wAEB8gECGAE%3D"
                                          }
                                        }
                                      }
                                    },
                                    {
                                      "buttonRenderer": {
                                        "icon": { "iconType": "MIX" },
                                        "command": {
                                          "watchPlaylistEndpoint": {
                                            "playlistId": "RDQM_SKEPTA_MIX",
                                            "params": "wAEB"
                                          }
                                        }
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val parsed = json.decodeFromString<BrowseResponse>(rawResponsive)
        val shuffleEp = assertNotNull(parsed.getPlaylistShuffleEndpoint())
        assertEquals("RDAMPLPL_SKEPTA_2026", shuffleEp.playlistId)
        assertEquals("wAEB8gECGAE%3D", shuffleEp.params)

        val radioEp = assertNotNull(parsed.getPlaylistRadioEndpoint())
        assertEquals("RDQM_SKEPTA_MIX", radioEp.playlistId)
        assertEquals("wAEB", radioEp.params)
    }

    private class FakePlayer {
        var shuffleModeEnabled = false
        var repeatMode = 0
        var duration = 0L
        var currentPosition = 0L
        var volume = 1f
        val seekCalls = mutableListOf<Long>()

        val proxy: MediaPlayerInterface =
            Proxy.newProxyInstance(
                MediaPlayerInterface::class.java.classLoader,
                arrayOf(MediaPlayerInterface::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getShuffleModeEnabled" -> shuffleModeEnabled
                    "setShuffleModeEnabled" -> {
                        shuffleModeEnabled = args?.getOrNull(0) as? Boolean ?: false
                        Unit
                    }
                    "getRepeatMode" -> repeatMode
                    "setRepeatMode" -> {
                        repeatMode = args?.getOrNull(0) as? Int ?: 0
                        Unit
                    }
                    "getDuration" -> duration
                    "getCurrentPosition", "getContentPosition" -> currentPosition
                    "seekTo" -> {
                        val pos =
                            if (args?.size == 1) {
                                args[0] as Long
                            } else {
                                args?.getOrNull(1) as? Long ?: 0L
                            }
                        currentPosition = pos
                        seekCalls += pos
                        Unit
                    }
                    "getVolume" -> volume
                    "setVolume" -> {
                        volume = args?.getOrNull(0) as? Float ?: 1f
                        Unit
                    }
                    "getPlaybackParameters" -> GenericPlaybackParameters.DEFAULT
                    "getCurrentMediaTimeLine" -> emptyList<GenericMediaItem>()
                    "getAlbumTrackIds" -> emptySet<String>()
                    else -> defaultReturn(method.returnType, method.genericReturnType)
                }
            } as MediaPlayerInterface
    }

    @Test
    fun jvmHandlerSetShuffleSyncsPlayerControlStateAndGuardsUpdateProgressAgainstUnsetDuration() =
        runBlocking {
            val storedShuffle = MutableStateFlow(DataStoreManager.TRUE)
            val storedRepeat = MutableStateFlow("REPEAT_MODE_OFF")
        val fakePlayer = FakePlayer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val dataStore =
            Proxy.newProxyInstance(
                DataStoreManager::class.java.classLoader,
                arrayOf(DataStoreManager::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getSaveStateOfPlayback" -> flowOf(DataStoreManager.TRUE)
                    "getShuffleKey" -> storedShuffle
                    "getRepeatKey" -> storedRepeat
                    "recoverShuffleAndRepeatKey" -> {
                        val s = args?.getOrNull(0) as? Boolean ?: false
                        storedShuffle.value = if (s) DataStoreManager.TRUE else DataStoreManager.FALSE
                        Unit
                    }
                    else -> defaultReturn(method.returnType, method.genericReturnType)
                }
            } as DataStoreManager

        val handler =
            JvmMediaPlayerHandlerImpl(
                dataStoreManager = dataStore,
                songRepository = proxyRepo(SongRepository::class.java),
                streamRepository = proxyRepo(StreamRepository::class.java),
                localPlaylistRepository = proxyRepo(LocalPlaylistRepository::class.java),
                analyticsRepository = proxyRepo(AnalyticsRepository::class.java),
                discordRepository = proxyRepo(DiscordRepository::class.java),
                coroutineScope = scope,
                playerOverride = fakePlayer.proxy,
                mainDispatcher = Dispatchers.Unconfined,
            )

        try {
            // Startup restored shuffle = TRUE from DataStore
            assertTrue(fakePlayer.shuffleModeEnabled)
            assertTrue(handler.controlState.value.isShuffle)

            // Tapping [Play] calls setShuffle(false) -> must immediately clear both player & UI controlState
            handler.setShuffle(false)
            assertFalse(fakePlayer.shuffleModeEnabled)
            assertFalse(handler.controlState.value.isShuffle)

            // Tapping [Shuffle] calls setShuffle(true) -> must immediately enable both player & UI controlState
            handler.setShuffle(true)
            assertTrue(fakePlayer.shuffleModeEnabled)
            assertTrue(handler.controlState.value.isShuffle)

            // UpdateProgress (0..100 percentage) must ignore unset / negative Media3 C.TIME_UNSET durations and NaN progress
            fakePlayer.duration = -9223372036854775807L
            handler.onPlayerEvent(PlayerEvent.UpdateProgress(50f))
            fakePlayer.duration = 0L
            handler.onPlayerEvent(PlayerEvent.UpdateProgress(50f))
            fakePlayer.duration = 240_000L
            handler.onPlayerEvent(PlayerEvent.UpdateProgress(Float.NaN))
            assertTrue(fakePlayer.seekCalls.isEmpty(), "Must not seek when duration <= 0 or progress is NaN")

            // Valid duration seeks accurately to 50% without Long overflow
            handler.onPlayerEvent(PlayerEvent.UpdateProgress(50f))
            assertEquals(listOf(120_000L), fakePlayer.seekCalls)

            // 1-song local playlist shuffle must set isShuffle = true and clear continuation = null
            handler.setShuffle(false)
            val singleTrack =
                Track(
                    album = Album(id = "a1", name = "Single Album"),
                    artists = listOf(Artist(id = "ar1", name = "Skepta")),
                    duration = "3:00",
                    durationSeconds = 180,
                    isAvailable = true,
                    isExplicit = false,
                    likeStatus = "INDIFFERENT",
                    thumbnails = emptyList(),
                    title = "Track One",
                    videoId = "vid_1",
                    videoType = "MUSIC_VIDEO_TYPE_ATV",
                    category = "Song",
                    feedbackTokens = null,
                    resultType = "Song",
                )
            handler.setQueueData(
                QueueData.Data(
                    listTracks = arrayListOf(singleTrack),
                    firstPlayedTrack = singleTrack,
                    playlistId = "${com.maxrave.common.LOCAL_PLAYLIST_ID}1",
                    playlistName = "Single Song Playlist",
                    playlistType = PlaylistType.LOCAL_PLAYLIST,
                    continuation = "ASC0",
                ),
            )
            handler.shufflePlaylist(0)
            withTimeout(2_000L) {
                while (handler.queueData.value.data.continuation != null) delay(10L)
            }
            assertTrue(handler.controlState.value.isShuffle)
            assertNull(handler.queueData.value.data.continuation)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        fun <T> proxyRepo(clazz: Class<T>): T =
            @Suppress("UNCHECKED_CAST")
            (
                Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, _ ->
                    defaultReturn(method.returnType, method.genericReturnType)
                } as T
            )

        fun defaultReturn(
            returnType: Class<*>,
            genericType: java.lang.reflect.Type,
        ): Any? =
            when {
                Flow::class.java.isAssignableFrom(returnType) -> {
                    val elem = (genericType as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
                    when (elem) {
                        java.lang.Integer::class.java, Integer.TYPE -> flowOf(0)
                        java.lang.Float::class.java, java.lang.Float.TYPE -> flowOf(1f)
                        java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> flowOf(false)
                        java.lang.Long::class.java, java.lang.Long.TYPE -> flowOf(0L)
                        String::class.java -> flowOf(DataStoreManager.FALSE)
                        else -> emptyFlow<Any?>()
                    }
                }
                returnType == java.lang.Boolean.TYPE -> false
                returnType == java.lang.Integer.TYPE -> 0
                returnType == java.lang.Long.TYPE -> 0L
                returnType == java.lang.Float.TYPE -> 0f
                returnType == java.lang.Double.TYPE -> 0.0
                returnType == Unit::class.java || returnType == Void.TYPE -> Unit
                List::class.java.isAssignableFrom(returnType) -> emptyList<Any?>()
                Set::class.java.isAssignableFrom(returnType) -> emptySet<Any?>()
                else -> null
            }
    }
}
