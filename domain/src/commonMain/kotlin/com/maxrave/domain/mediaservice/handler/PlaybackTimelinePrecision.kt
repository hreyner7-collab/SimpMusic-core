package com.maxrave.domain.mediaservice.handler

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Coordinates progress-ticker cadence between the media player handlers and high-precision UIs.
 *
 * When a fullscreen lyrics view or the expanded Now Playing scrubber is on screen, progress ticks
 * run at 50 ms (20 Hz) so karaoke lines and scrubbers move smoothly. While the user is browsing
 * lists with only the MiniPlayer showing, ticks drop to 250 ms (4 Hz) — cutting main-thread
 * wakeups, ExoPlayer/mpv IPC calls, and Compose invalidations by 5x so scrolling stays silky smooth.
 */
object PlaybackTimelinePrecision {
    private val activeHighPrecisionViews = MutableStateFlow(0)

    const val HIGH_FREQUENCY_TICK_MS: Long = 50L
    const val BACKGROUND_BROWSE_TICK_MS: Long = 250L

    val isHighFrequencyActive: Boolean
        get() = activeHighPrecisionViews.value > 0

    fun currentTickIntervalMs(): Long =
        if (isHighFrequencyActive) HIGH_FREQUENCY_TICK_MS else BACKGROUND_BROWSE_TICK_MS

    fun acquireHighFrequency() {
        activeHighPrecisionViews.update { it + 1 }
    }

    fun releaseHighFrequency() {
        activeHighPrecisionViews.update { (it - 1).coerceAtLeast(0) }
    }

    /** Test helper to ensure clean state between tests. */
    fun resetForTest() {
        activeHighPrecisionViews.value = 0
    }
}
