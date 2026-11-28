package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

class MpvPlayerShuffleAndGuardsTest {
    private class FakeHandle : MpvPlayerHandle {
        var listener: MpvPlayerHandleEventListener? = null
        var loadedUrls = CopyOnWriteArrayList<String>()
        var seekTargets = CopyOnWriteArrayList<Long>()
        override val videoFrames: MpvVideoFrameSource? = null
        override var isReleased: Boolean = false
        override var time: Long = 0L
        override var length: Long = 0L

        override fun setHandleEventListener(listener: MpvPlayerHandleEventListener?) {
            this.listener = listener
        }

        override fun loadFile(
            url: String,
            startPaused: Boolean,
            startMs: Long,
        ) {
            loadedUrls += url
            time = startMs
        }

        override fun play() {}

        override fun pause() {}

        override fun stop() {}

        override fun seekTo(timeMs: Long) {
            time = timeMs
            seekTargets += timeMs
        }

        override fun setMasterVolume(volume: Int) {}

        override fun setFadeVolume(volume: Int) {}

        override fun setSleepFadeVolume(volume: Int) {}

        override fun setVolumeLevels(
            master: Int,
            sleep: Int,
        ) {}

        override fun setMute(mute: Boolean) {}

        override fun setRate(rate: Float) {}

        override fun setVideoSuspended(suspended: Boolean) {}

        override fun setAudioDevice(name: String) {}

        override fun audioDeviceListJson(): String? = "[]"

        override fun audioDevice(): String? = "auto"

        override fun currentAudioOutputDriver(): String? = "wasapi"

        override fun installCrossfadeChain(
            sweep: MpvCrossfadeFilter?,
            sweepStartHz: Float,
            pitchShift: Boolean,
        ): MpvPlayer.CrossfadeChain = MpvPlayer.CrossfadeChain.NONE

        override fun setCrossfadeCutoffHz(
            sweep: MpvCrossfadeFilter,
            hz: Float,
        ) {}

        override fun setPitchScale(scale: Float) {}

        override fun clearAudioFilters() {}

        override fun setEqualizer(
            bandsDb: List<Float>,
            preampDb: Float,
        ): Boolean = true

        override fun setAudioEffects(
            effects: com.maxrave.domain.data.player.AudioEffects,
            reverbIrPath: String?,
        ): Boolean = true

        override fun setReverbMix(mix: Float): Boolean = true

        override fun setEightD(
            orbit: com.maxrave.domain.data.player.EightDOrbit?,
            fadeOutEffect: com.maxrave.domain.data.player.EightDEffect?,
            fadeIn: Boolean,
        ): Boolean = true

        override fun syncEightD(): Boolean = true

        override fun setLooping(loop: Boolean) {}

        override fun release() {
            isReleased = true
        }
    }

    private fun fakeDataStore(): DataStoreManager =
        Proxy.newProxyInstance(
            DataStoreManager::class.java.classLoader,
            arrayOf(DataStoreManager::class.java),
        ) { _, method, _ ->
            when {
                Flow::class.java.isAssignableFrom(method.returnType) -> {
                    val elemType = (method.genericReturnType as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
                    when (elemType) {
                        java.lang.Integer::class.java, Integer.TYPE -> flowOf(0)
                        java.lang.Float::class.java, java.lang.Float.TYPE -> flowOf(1f)
                        java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> flowOf(false)
                        java.lang.Long::class.java, java.lang.Long.TYPE -> flowOf(0L)
                        else -> flowOf(DataStoreManager.FALSE)
                    }
                }
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == java.lang.Integer.TYPE -> 0
                method.returnType == java.lang.Long.TYPE -> 0L
                method.returnType == java.lang.Float.TYPE -> 1f
                else -> Unit
            }
        } as DataStoreManager

    private fun fakeStreamRepository(): StreamRepository =
        Proxy.newProxyInstance(
            StreamRepository::class.java.classLoader,
            arrayOf(StreamRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getStream" -> {
                    val videoId = args?.getOrNull(1) as? String ?: "vid"
                    flowOf("https://stream.example/$videoId.webm")
                }
                else ->
                    if (Flow::class.java.isAssignableFrom(method.returnType)) {
                        flowOf(null)
                    } else {
                        Unit
                    }
            }
        } as StreamRepository

    private fun mediaItem(id: String): GenericMediaItem =
        GenericMediaItem(
            mediaId = id,
            uri = null,
            metadata = GenericMediaMetadata(title = "Title $id"),
        )

    @Test
    fun playAfterShufflePreservesExactOriginalOrder() {
        val handles = CopyOnWriteArrayList<FakeHandle>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val adapter =
            MpvPlayerAdapter(
                coroutineScope = scope,
                dataStoreManager = fakeDataStore(),
                streamRepository = fakeStreamRepository(),
                playerFactory = { _, _, _ -> FakeHandle().also { handles += it } },
            )
        try {
            // 1. User previously turned Shuffle ON.
            adapter.shuffleModeEnabled = true
            adapter.setMediaItem(mediaItem("old_0"))
            adapter.addMediaItem(mediaItem("old_1"))
            adapter.addMediaItem(mediaItem("old_2"))
            assertTrue(adapter.shuffleModeEnabled)

            // 2. User taps [Play] on a new playlist: setShuffle(false) runs BEFORE loading items.
            adapter.shuffleModeEnabled = false
            assertFalse(adapter.shuffleModeEnabled)

            val expectedIds = listOf("t0", "t1", "t2", "t3", "t4")
            adapter.setMediaItem(mediaItem(expectedIds.first()))
            expectedIds.drop(1).forEach { id ->
                adapter.addMediaItem(mediaItem(id))
            }

            assertEquals(expectedIds, adapter.getCurrentMediaTimeLine().map { it.mediaId })
            assertEquals("t0", adapter.currentMediaItem?.mediaId)
            assertEquals(0, adapter.currentMediaItemIndex)
        } finally {
            adapter.release()
            scope.cancel()
        }
    }

    @Test
    fun shuffleStartingMidListPreservesAllTracksAndUnshuffleRestoresAlbumOrder() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val adapter =
            MpvPlayerAdapter(
                coroutineScope = scope,
                dataStoreManager = fakeDataStore(),
                streamRepository = fakeStreamRepository(),
                playerFactory = { _, _, _ -> FakeHandle() },
            )
        try {
            // Mirrors AlbumViewModel.shuffle() + loadMoreCatalog when randomIndex = 3 on a 6-song album:
            // Track 3 is loaded first via setMediaItem, tracks 0..2 inserted before it, tracks 4..5 appended after it.
            val albumIds = listOf("s0", "s1", "s2", "s3", "s4", "s5")
            adapter.shuffleModeEnabled = true
            adapter.setMediaItem(mediaItem("s3"))
            adapter.addMediaItem(0, mediaItem("s0"))
            adapter.addMediaItem(1, mediaItem("s1"))
            adapter.addMediaItem(2, mediaItem("s2"))
            adapter.addMediaItem(mediaItem("s4"))
            adapter.addMediaItem(mediaItem("s5"))

            val shuffledTimeline = adapter.getCurrentMediaTimeLine().map { it.mediaId }
            assertEquals("All 6 album tracks must be in the shuffled timeline (none stranded)", 6, shuffledTimeline.size)
            assertEquals(albumIds.toSet(), shuffledTimeline.toSet())
            assertEquals("s3", adapter.currentMediaItem?.mediaId)
            assertEquals("The chosen starting song must sit at the front of the shuffled order", "s3", shuffledTimeline.first())

            // Turning Shuffle OFF in Now Playing must restore the true album tracklist [s0..s5].
            adapter.shuffleModeEnabled = false
            assertEquals(albumIds, adapter.getCurrentMediaTimeLine().map { it.mediaId })
            assertEquals("s3", adapter.currentMediaItem?.mediaId)
        } finally {
            adapter.release()
            scope.cancel()
        }
    }

    @Test
    fun seekForwardDoesNotRewindToZeroWhenDurationIsNotYetKnown() {
        val handle = FakeHandle()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val adapter =
            MpvPlayerAdapter(
                coroutineScope = scope,
                dataStoreManager = fakeDataStore(),
                streamRepository = fakeStreamRepository(),
                playerFactory = { _, _, _ -> handle },
            )
        try {
            adapter.setMediaItem(mediaItem("live_or_unparsed"))
            // Duration is still 0L (unparsed container header). Seek to 25s, then seekForward (+5s).
            adapter.seekTo(25_000L)
            assertEquals(0L, adapter.duration)
            adapter.seekForward()
            assertEquals(30_000L, adapter.currentPosition)
        } finally {
            adapter.release()
            scope.cancel()
        }
    }

    @Test
    fun staleErrorRetryAbortsWhenUserSwitchesToAnotherTrackDuringBackoff() =
        runBlocking {
            val handles = CopyOnWriteArrayList<FakeHandle>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val adapter =
                MpvPlayerAdapter(
                    coroutineScope = scope,
                    dataStoreManager = fakeDataStore(),
                    streamRepository = fakeStreamRepository(),
                    playerFactory = { _, _, _ -> FakeHandle().also { handles += it } },
                )
            try {
                adapter.setMediaItem(mediaItem("broken_track"))
                withTimeout(2_000L) {
                    while (handles.isEmpty()) delay(10L)
                }
                val firstHandle = handles.first()
                // Trigger mpv error callback -> launches 500ms retry backoff coroutine
                firstHandle.listener?.error(firstHandle)

                // User immediately switches to another song before the 500ms backoff finishes
                adapter.setMediaItem(mediaItem("good_track"))
                withTimeout(2_000L) {
                    while (handles.size < 2 || handles.last().loadedUrls.isEmpty()) delay(10L)
                }
                assertEquals("good_track", adapter.currentMediaItem?.mediaId)

                // Wait past the 500ms retry delay and verify broken_track did NOT hijack good_track
                delay(650L)
                assertEquals("good_track", adapter.currentMediaItem?.mediaId)
                val allLoaded = handles.flatMap { it.loadedUrls }
                assertEquals(
                    "good_track must remain the last loaded stream",
                    "https://stream.example/good_track.webm",
                    allLoaded.last(),
                )
            } finally {
                adapter.release()
                scope.cancel()
            }
        }

    @Test
    fun multiOutputSpawnsAndReleasesMirrorHandlesAndClampsSyncDelay() {
        val handles = CopyOnWriteArrayList<FakeHandle>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val adapter =
            MpvPlayerAdapter(
                coroutineScope = scope,
                dataStoreManager = fakeDataStore(),
                streamRepository = fakeStreamRepository(),
                playerFactory = { _, _, _ -> FakeHandle().also { handles += it } },
            )
        try {
            assertTrue(adapter.multiOutputSupported)
            adapter.toggleSecondaryAudioOutput("wasapi/{jbl-1}")
            adapter.toggleSecondaryAudioOutput("wasapi/{alexa-1}")
            assertEquals(setOf("wasapi/{jbl-1}", "wasapi/{alexa-1}"), adapter.secondaryAudioOutputIds.value)
            assertEquals(2, handles.size)

            adapter.setAudioOutputDelayMs("wasapi/{alexa-1}", 999)
            assertEquals(500, adapter.audioOutputDelaysMs.value["wasapi/{alexa-1}"])
            adapter.setAudioOutputDelayMs("wasapi/{alexa-1}", -999)
            assertEquals(-500, adapter.audioOutputDelaysMs.value["wasapi/{alexa-1}"])

            adapter.toggleSecondaryAudioOutput("wasapi/{jbl-1}")
            assertEquals(setOf("wasapi/{alexa-1}"), adapter.secondaryAudioOutputIds.value)
            assertTrue(handles[0].isReleased)
            assertFalse(handles[1].isReleased)
        } finally {
            adapter.release()
            scope.cancel()
        }
    }
}
