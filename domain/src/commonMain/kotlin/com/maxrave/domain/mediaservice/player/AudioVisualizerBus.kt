package com.maxrave.domain.mediaservice.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Thread-safe spectrum bus between playback DSP engines and Compose visualizers.
 *
 * Uses [MutableStateFlow] CAS instead of `kotlinx.atomicfu` (which `core/domain` does not depend
 * on). Audio processors check [hasActiveCollectors] before touching PCM frames so hidden
 * visualizers incur zero FFT CPU cost.
 */
object AudioVisualizerBus {
    const val BAND_COUNT: Int = 24

    private const val ATTACK_ALPHA: Float = 0.65f
    private const val DECAY_ALPHA: Float = 0.25f
    private const val SILENCE_FLOOR: Float = 0.002f

    private val collectorCount = MutableStateFlow(0)
    private val _bands = MutableStateFlow(FloatArray(BAND_COUNT))

    /** 24 normalized (`0f..1f`) temporally smoothed spectrum bands. */
    val bands: StateFlow<FloatArray> = _bands.asStateFlow()

    /** Number of currently attached UI visualizers. */
    val activeCollectorCount: StateFlow<Int> = collectorCount.asStateFlow()

    fun registerCollector() {
        collectorCount.update { it + 1 }
    }

    fun unregisterCollector() {
        val remaining = collectorCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (remaining == 0) {
            reset()
        }
    }

    /** Fast lock-free check used on the audio hot path to skip all FFT work when hidden. */
    fun hasActiveCollectors(): Boolean = collectorCount.value > 0

    /**
     * Smooths [rawBands] with fast-attack / gentle-decay temporal filtering and emits an immutable
     * snapshot for UI collectors.
     */
    fun publishRawSpectrum(rawBands: FloatArray) {
        if (rawBands.isEmpty()) return
        _bands.update { prev ->
            FloatArray(BAND_COUNT) { i ->
                val raw = if (i < rawBands.size) rawBands[i] else 0f
                val target = if (raw.isFinite()) raw.coerceIn(0f, 1f) else 0f
                val previous = if (i < prev.size) prev[i] else 0f
                val alpha = if (target > previous) ATTACK_ALPHA else DECAY_ALPHA
                val smoothed = previous + alpha * (target - previous)
                if (target == 0f && smoothed < SILENCE_FLOOR) 0f else smoothed.coerceIn(0f, 1f)
            }
        }
    }

    /** Clears all spectrum bands to zero (e.g. on pause, stop, or last collector unregister). */
    fun reset() {
        if (_bands.value.any { it != 0f }) {
            _bands.value = FloatArray(BAND_COUNT)
        }
    }
}
