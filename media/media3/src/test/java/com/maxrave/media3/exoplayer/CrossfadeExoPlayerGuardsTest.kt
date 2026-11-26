package com.maxrave.media3.exoplayer

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeExoPlayerGuardsTest {
    @Test
    fun sanitizeMediaDurationNeverLeaksTimeUnsetOrNegativeDuration() {
        assertEquals(0L, sanitizeMediaDuration(C.TIME_UNSET, 0L))
        assertEquals(0L, sanitizeMediaDuration(C.TIME_UNSET, C.TIME_UNSET))
        assertEquals(0L, sanitizeMediaDuration(-100L, -5L))
        assertEquals(185_000L, sanitizeMediaDuration(C.TIME_UNSET, 185_000L))
        assertEquals(210_000L, sanitizeMediaDuration(210_000L, 185_000L))
    }

    @Test
    fun seekForwardAdvancesByFiveSecondsEvenWhenDurationIsUnsetOrZero() {
        // Previously coerced to 0L and rewound tracks to 0:00 whenever duration wasn't parsed yet
        assertEquals(30_000L, computeSeekForwardPosition(25_000L, null))
        assertEquals(30_000L, computeSeekForwardPosition(25_000L, 0L))
        assertEquals(30_000L, computeSeekForwardPosition(25_000L, C.TIME_UNSET))
        // When duration is known, clamps safely at track end
        assertEquals(180_000L, computeSeekForwardPosition(178_000L, 180_000L))
        assertEquals(5_000L, computeSeekForwardPosition(-1_000L, 180_000L))
    }

    @Test
    fun delayedRetryGuardRejectsStaleSeqOrChangedTrack() {
        assertTrue(shouldProceedWithDelayedRetry(5L, 5L, "vid_a", "vid_a"))
        assertFalse(shouldProceedWithDelayedRetry(5L, 6L, "vid_a", "vid_a"))
        assertFalse(shouldProceedWithDelayedRetry(5L, 5L, "vid_a", "vid_b"))
        assertFalse(shouldProceedWithDelayedRetry(5L, 5L, null, "vid_a"))
        assertFalse(shouldProceedWithDelayedRetry(5L, 5L, "", ""))
    }
}
