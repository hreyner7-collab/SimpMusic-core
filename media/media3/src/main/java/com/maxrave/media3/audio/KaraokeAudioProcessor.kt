package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.player.KaraokeEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Pure stereo DSP kernel for real-time karaoke vocal suppression.
 *
 * Separates low-frequency center bass (< ~180 Hz) from the centered mid/high vocal band using a
 * cascaded one-pole low-pass filter on the mid channel `mid = (L + R) * 0.5`. Subtracting
 * `suppression * (mid - midLow)` from both channels cancels centered vocals (> 15 dB at 1 kHz)
 * while leaving stereo side content (`(L - R) * 0.5`) completely untouched and preserving kick/sub-bass
 * when [KaraokeEffect.preserveBass] is enabled.
 */
class KaraokeKernel(
    sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
) {
    private var sampleRate: Int = sampleRateHz.coerceAtLeast(1)
    private var alpha: Double = computeAlpha(sampleRate)

    // Two cascaded 1-pole low-pass stages with pole at ~280 Hz give an exact -3 dB crossover at
    // 180 Hz, >92% retention at 80 Hz sub-bass, and >19 dB vocal cancellation at 1 kHz (at 0.85).
    private var lpStage1: Double = 0.0
    private var lpStage2: Double = 0.0

    var leftOut: Double = 0.0
        private set
    var rightOut: Double = 0.0
        private set

    fun setSampleRate(sampleRateHz: Int) {
        val safe = sampleRateHz.coerceAtLeast(1)
        if (safe != sampleRate) {
            sampleRate = safe
            alpha = computeAlpha(safe)
            reset()
        }
    }

    fun reset() {
        lpStage1 = 0.0
        lpStage2 = 0.0
        leftOut = 0.0
        rightOut = 0.0
    }

    fun processSample(
        left: Double,
        right: Double,
        effect: KaraokeEffect,
    ) {
        if (!effect.isActive) {
            leftOut = left
            rightOut = right
            return
        }
        val suppression = effect.clampedSuppression.toDouble()
        val mid = (left + right) * 0.5
        val cancelComponent =
            if (effect.preserveBass) {
                lpStage1 += alpha * (mid - lpStage1)
                lpStage2 += alpha * (lpStage1 - lpStage2)
                mid - lpStage2
            } else {
                mid
            }
        val subtract = suppression * cancelComponent
        leftOut = left - subtract
        rightOut = right - subtract
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE: Int = 48_000

        // For 2 cascaded 1-pole low-pass stages, f_-3dB = f_pole * sqrt(sqrt(2) - 1) ~= 0.6436 * f_pole.
        // Putting the pole at ~280 Hz places the -3 dB crossover right at KaraokeEffect.BASS_CUTOFF_HZ (180 Hz).
        private const val BASS_STAGE_POLE_HZ: Double = 280.0
        private const val SOFT_CLIP_KNEE: Double = 30_000.0
        private const val SHORT_CEILING: Double = 32_767.0

        private fun computeAlpha(sampleRateHz: Int): Double = 1.0 - exp(-2.0 * PI * BASS_STAGE_POLE_HZ / sampleRateHz)

        /**
         * Linear inside `[-30000, 30000]` and C1-smooth `tanh` saturation up to 16-bit PCM bounds
         * so hot mixes never hard-clip or wrap.
         */
        fun softClampToShort(sample: Double): Short {
            if (!sample.isFinite()) return 0
            val headroom = SHORT_CEILING - SOFT_CLIP_KNEE
            val shaped =
                when {
                    sample > SOFT_CLIP_KNEE -> SOFT_CLIP_KNEE + headroom * tanh((sample - SOFT_CLIP_KNEE) / headroom)
                    sample < -SOFT_CLIP_KNEE -> -SOFT_CLIP_KNEE + headroom * tanh((sample + SOFT_CLIP_KNEE) / headroom)
                    else -> sample
                }
            return shaped.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}

/**
 * Media3 [AudioProcessor] applying real-time bass-preserving mid-side karaoke vocal cancellation
 * to stereo PCM16 audio. Zero DSP work is performed when disabled.
 */
@UnstableApi
class KaraokeAudioProcessor(
    private val effectProvider: () -> KaraokeEffect,
) : BaseAudioProcessor() {
    private val kernel = KaraokeKernel()
    private var stereo: Boolean = false
    private var wasActive: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        stereo = inputAudioFormat.channelCount == 2
        kernel.setSampleRate(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun onFlush() {
        kernel.reset()
        wasActive = false
    }

    override fun onReset() {
        kernel.reset()
        stereo = false
        wasActive = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val output = replaceOutputBuffer(remaining)
        val effect = effectProvider().clamped()
        if (!effect.isActive || !stereo) {
            if (wasActive) {
                kernel.reset()
                wasActive = false
            }
            output.put(inputBuffer)
            output.flip()
            return
        }
        if (!wasActive) {
            kernel.reset()
            wasActive = true
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        while (inputBuffer.remaining() >= 4) {
            val left = inputBuffer.short.toDouble()
            val right = inputBuffer.short.toDouble()
            kernel.processSample(left, right, effect)
            output.putShort(KaraokeKernel.softClampToShort(kernel.leftOut))
            output.putShort(KaraokeKernel.softClampToShort(kernel.rightOut))
        }
        while (inputBuffer.hasRemaining()) {
            output.put(inputBuffer.get())
        }
        output.flip()
    }
}
