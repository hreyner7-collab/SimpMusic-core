package com.maxrave.media3.exoplayer

import androidx.media3.common.C

internal fun sanitizeMediaDuration(
    rawDurationMs: Long,
    fallbackCachedDurationMs: Long,
): Long {
    if (rawDurationMs > 0L && rawDurationMs != C.TIME_UNSET) return rawDurationMs
    if (fallbackCachedDurationMs > 0L && fallbackCachedDurationMs != C.TIME_UNSET) return fallbackCachedDurationMs
    return 0L
}

internal fun computeSeekForwardPosition(
    currentPositionMs: Long,
    durationMs: Long?,
    stepMs: Long = 5_000L,
): Long {
    val safePos = currentPositionMs.coerceAtLeast(0L)
    val safeEnd = durationMs?.takeIf { it > 0L && it != C.TIME_UNSET }
    return if (safeEnd != null) {
        (safePos + stepMs).coerceAtMost(safeEnd)
    } else {
        safePos + stepMs
    }
}

internal fun shouldProceedWithDelayedRetry(
    capturedLoadSeq: Long,
    currentLoadSeq: Long,
    expectedVideoId: String?,
    currentVideoId: String?,
): Boolean =
    capturedLoadSeq == currentLoadSeq &&
        !expectedVideoId.isNullOrBlank() &&
        expectedVideoId == currentVideoId
