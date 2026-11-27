package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.mediaservice.player.WAVEFORM_BARS_PER_SECOND
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure 256-point radix-2 FFT analyzer over 8 kHz mono PCM16 samples.
 * Produces 4 normalized (`0f..1f`) frequency bands per frame:
 * `[bass (40-180 Hz), lowMid (180-650 Hz), vocalMid (650-2000 Hz), highPitch (2000-4000 Hz)]`.
 */
internal object DesktopSpectrumAnalyzer {
    const val BANDS_PER_FRAME = 4
    private const val FFT_SIZE = 256

    private val hannWindow =
        FloatArray(FFT_SIZE) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
        }

    fun computeSpectrumFrames(
        pcm16: ShortArray,
        sampleRate: Int = 8000,
        framesPerSecond: Float = WAVEFORM_BARS_PER_SECOND,
    ): FloatArray {
        if (pcm16.isEmpty() || sampleRate <= 0 || framesPerSecond <= 0f) return FloatArray(0)
        val hop = (sampleRate / framesPerSecond).roundToInt().coerceAtLeast(1)
        val frameCount = ((pcm16.size - 1) / hop) + 1
        if (frameCount <= 0) return FloatArray(0)

        val out = FloatArray(frameCount * BANDS_PER_FRAME)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val binHz = sampleRate.toFloat() / FFT_SIZE.toFloat()
        var maxEnergy = 1e-6f

        for (f in 0 until frameCount) {
            val start = f * hop
            for (i in 0 until FFT_SIZE) {
                val idx = start + i
                val sample = if (idx < pcm16.size) pcm16[idx] / 32768f else 0f
                re[i] = sample * hannWindow[i]
                im[i] = 0f
            }
            fft256(re, im)

            var bass = 0f
            var lowMid = 0f
            var vocalMid = 0f
            var highPitch = 0f
            var bassN = 0
            var lowMidN = 0
            var vocalMidN = 0
            var highPitchN = 0

            for (bin in 1 until FFT_SIZE / 2) {
                val freq = bin * binHz
                val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                when {
                    freq < 180f -> {
                        bass += mag
                        bassN++
                    }
                    freq < 650f -> {
                        lowMid += mag
                        lowMidN++
                    }
                    freq < 2000f -> {
                        vocalMid += mag
                        vocalMidN++
                    }
                    else -> {
                        highPitch += mag
                        highPitchN++
                    }
                }
            }

            val b = if (bassN > 0) bass / bassN else 0f
            val l = if (lowMidN > 0) lowMid / lowMidN else 0f
            val v = if (vocalMidN > 0) vocalMid / vocalMidN else 0f
            val h = if (highPitchN > 0) highPitch / highPitchN else 0f

            val base = f * BANDS_PER_FRAME
            out[base] = b
            out[base + 1] = l
            out[base + 2] = v
            out[base + 3] = h

            if (b > maxEnergy) maxEnergy = b
            if (l > maxEnergy) maxEnergy = l
            if (v > maxEnergy) maxEnergy = v
            if (h > maxEnergy) maxEnergy = h
        }

        val inv = 1f / maxEnergy
        for (i in out.indices) {
            // Mild sqrt compression keeps quiet sections expressive without clipping drops.
            out[i] = sqrt((out[i] * inv).coerceIn(0f, 1f))
        }
        return out
    }

    private fun fft256(re: FloatArray, im: FloatArray) {
        val n = FFT_SIZE
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angle = -2.0 * PI / len
            val wStepRe = cos(angle).toFloat()
            val wStepIm = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until half) {
                    val even = start + k
                    val odd = even + half
                    val tRe = wRe * re[odd] - wIm * im[odd]
                    val tIm = wRe * im[odd] + wIm * re[odd]
                    re[odd] = re[even] - tRe
                    im[odd] = im[even] - tIm
                    re[even] = re[even] + tRe
                    im[even] = im[even] + tIm
                    val nextWRe = wRe * wStepRe - wIm * wStepIm
                    wIm = wRe * wStepIm + wIm * wStepRe
                    wRe = nextWRe
                }
                start += len
            }
            len = len shl 1
        }
    }
}
