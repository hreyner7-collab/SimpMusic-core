package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.mediaservice.player.AudioVisualizerBus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure 512-point Hann-windowed FFT spectrum analyzer mapping PCM audio into 24 logarithmic bands
 * (`60 Hz..14 kHz`) normalized to `0f..1f`. Allocation-free after construction.
 */
internal class VisualizerSpectrumAnalyzer(
    sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
) {
    private val fft = Fft(FFT_SIZE)
    private val hannWindow = DoubleArray(FFT_SIZE) { i -> 0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1))) }
    private val ringBuffer = DoubleArray(FFT_SIZE)
    private val real = DoubleArray(FFT_SIZE)
    private val imag = DoubleArray(FFT_SIZE)
    private val magnitudes = DoubleArray(FFT_SIZE / 2)

    private val bandStartBin = IntArray(BAND_COUNT)
    private val bandEndBin = IntArray(BAND_COUNT)
    private val bandCenterBin = DoubleArray(BAND_COUNT)

    private var sampleRate: Int = 0
    private var writePos: Int = 0
    private var filledSamples: Int = 0

    init {
        setSampleRate(sampleRateHz)
    }

    fun setSampleRate(sampleRateHz: Int) {
        val safe = sampleRateHz.coerceAtLeast(8_000)
        if (safe == sampleRate) return
        sampleRate = safe
        recomputeBandBins(safe)
        reset()
    }

    fun reset() {
        ringBuffer.fill(0.0)
        writePos = 0
        filledSamples = 0
    }

    fun pushMonoSample(sample: Double) {
        ringBuffer[writePos] = if (sample.isFinite()) sample else 0.0
        writePos = (writePos + 1) and FFT_MASK
        if (filledSamples < FFT_SIZE) {
            filledSamples++
        }
    }

    /**
     * Runs the 512-point Hann-windowed FFT and fills [outBands] (length >= 24) with normalized
     * `0f..1f` band energies. Returns `false` until the ring buffer holds at least 512 samples.
     */
    fun computeBands(outBands: FloatArray): Boolean {
        if (filledSamples < FFT_SIZE || outBands.size < BAND_COUNT) return false

        // Read ring buffer in chronological order and apply Hann window.
        var idx = writePos
        for (i in 0 until FFT_SIZE) {
            real[i] = ringBuffer[idx] * hannWindow[i]
            imag[i] = 0.0
            idx = (idx + 1) and FFT_MASK
        }

        fft.forward(real, imag)

        // Coherent gain of Hann window is 0.5, single-sided sine magnitude is 0.5 * 0.5 * FFT_SIZE.
        val normScale = 1.0 / (FFT_SIZE * 0.25)
        val half = FFT_SIZE / 2
        for (bin in 1 until half) {
            magnitudes[bin] = hypot(real[bin], imag[bin]) * normScale
        }
        magnitudes[0] = 0.0

        for (band in 0 until BAND_COUNT) {
            val start = bandStartBin[band]
            val end = bandEndBin[band]
            val energy =
                if (end > start) {
                    var sumSq = 0.0
                    var count = 0
                    for (b in start..end) {
                        val m = magnitudes[b]
                        sumSq += m * m
                        count++
                    }
                    sqrt(sumSq / count.coerceAtLeast(1))
                } else {
                    // Narrow low-frequency bands interpolate smoothly between adjacent FFT bins.
                    val frac = bandCenterBin[band].coerceIn(1.0, (half - 1).toDouble())
                    val lo = floor(frac).toInt().coerceIn(1, half - 1)
                    val hi = (lo + 1).coerceAtMost(half - 1)
                    val t = frac - lo
                    magnitudes[lo] * (1.0 - t) + magnitudes[hi] * t
                }

            val db = 20.0 * log10(energy.coerceAtLeast(1e-6)) + band * TREBLE_TILT_DB_PER_BAND
            val normalized = ((db - MIN_DB) / (MAX_DB - MIN_DB)).toFloat().coerceIn(0f, 1f)
            outBands[band] = normalized
        }
        return true
    }

    private fun recomputeBandBins(rateHz: Int) {
        val binHz = rateHz.toDouble() / FFT_SIZE
        val maxBin = FFT_SIZE / 2 - 1
        val nyquistLimit = (rateHz * 0.48).coerceAtLeast(MIN_FREQ_HZ * 2.0)
        val topFreq = MAX_FREQ_HZ.coerceAtMost(nyquistLimit)
        val ratio = topFreq / MIN_FREQ_HZ

        for (band in 0 until BAND_COUNT) {
            val fLow = MIN_FREQ_HZ * ratio.pow(band.toDouble() / BAND_COUNT)
            val fHigh = MIN_FREQ_HZ * ratio.pow((band + 1).toDouble() / BAND_COUNT)
            val fCenter = sqrt(fLow * fHigh)
            val start = floor(fLow / binHz).toInt().coerceIn(1, maxBin)
            val end = floor(fHigh / binHz).toInt().coerceIn(start, maxBin)
            bandStartBin[band] = start
            bandEndBin[band] = end
            bandCenterBin[band] = (fCenter / binHz).coerceIn(1.0, maxBin.toDouble())
        }
    }

    companion object {
        const val FFT_SIZE: Int = 512
        const val BAND_COUNT: Int = AudioVisualizerBus.BAND_COUNT
        const val DEFAULT_SAMPLE_RATE: Int = 44_100
        const val MIN_FREQ_HZ: Double = 60.0
        const val MAX_FREQ_HZ: Double = 14_000.0

        private const val FFT_MASK: Int = FFT_SIZE - 1
        private const val MIN_DB: Double = -54.0
        private const val MAX_DB: Double = -2.0
        private const val TREBLE_TILT_DB_PER_BAND: Double = 0.15
    }
}

/**
 * Pass-through Media3 [AudioProcessor] feeding [AudioVisualizerBus] at ~25 fps.
 *
 * Checks [AudioVisualizerBus.hasActiveCollectors] before touching PCM samples so hidden visualizers
 * perform zero FFT math and zero allocations on the audio thread.
 */
@UnstableApi
class VisualizerAudioProcessor(
    private val isSourceActive: () -> Boolean = { true },
    private val clockNanos: () -> Long = System::nanoTime,
) : BaseAudioProcessor() {
    private val analyzer = VisualizerSpectrumAnalyzer()
    private val scratchBands = FloatArray(AudioVisualizerBus.BAND_COUNT)

    private var channelCount: Int = 0
    private var encoding: Int = C.ENCODING_INVALID
    private var lastPublishNanos: Long = 0L
    private var wasCapturing: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported =
            inputAudioFormat.channelCount >= 1 &&
                (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        if (!supported) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        analyzer.setSampleRate(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun onFlush() {
        analyzer.reset()
        lastPublishNanos = 0L
        wasCapturing = false
    }

    override fun onReset() {
        analyzer.reset()
        channelCount = 0
        encoding = C.ENCODING_INVALID
        lastPublishNanos = 0L
        wasCapturing = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Always pass audio through unmodified.
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()

        // Fast-path exit: ZERO FFT or sample iteration when no UI visualizer is visible.
        if (!AudioVisualizerBus.hasActiveCollectors() || !isSourceActive() || channelCount <= 0) {
            if (wasCapturing) {
                analyzer.reset()
                lastPublishNanos = 0L
                wasCapturing = false
            }
            return
        }
        wasCapturing = true

        output.order(ByteOrder.nativeOrder())
        val channels = channelCount
        if (encoding == C.ENCODING_PCM_16BIT) {
            val frameBytes = channels * 2
            val frameCount = remaining / frameBytes
            var offset = 0
            if (channels == 2) {
                for (i in 0 until frameCount) {
                    val l = output.getShort(offset).toDouble()
                    val r = output.getShort(offset + 2).toDouble()
                    analyzer.pushMonoSample((l + r) * (0.5 / 32768.0))
                    offset += 4
                }
            } else if (channels == 1) {
                for (i in 0 until frameCount) {
                    analyzer.pushMonoSample(output.getShort(offset).toDouble() / 32768.0)
                    offset += 2
                }
            } else {
                val invScale = 1.0 / (channels * 32768.0)
                for (i in 0 until frameCount) {
                    var sum = 0.0
                    for (c in 0 until channels) {
                        sum += output.getShort(offset + c * 2).toDouble()
                    }
                    analyzer.pushMonoSample(sum * invScale)
                    offset += frameBytes
                }
            }
        } else if (encoding == C.ENCODING_PCM_FLOAT) {
            val frameBytes = channels * 4
            val frameCount = remaining / frameBytes
            var offset = 0
            if (channels == 2) {
                for (i in 0 until frameCount) {
                    val l = output.getFloat(offset).toDouble()
                    val r = output.getFloat(offset + 4).toDouble()
                    analyzer.pushMonoSample((l + r) * 0.5)
                    offset += 8
                }
            } else {
                val invChannels = 1.0 / channels
                for (i in 0 until frameCount) {
                    var sum = 0.0
                    for (c in 0 until channels) {
                        sum += output.getFloat(offset + c * 4).toDouble()
                    }
                    analyzer.pushMonoSample(sum * invChannels)
                    offset += frameBytes
                }
            }
        }

        val nowNs = clockNanos()
        if (lastPublishNanos == 0L || nowNs - lastPublishNanos >= MIN_PUBLISH_INTERVAL_NS) {
            if (analyzer.computeBands(scratchBands)) {
                lastPublishNanos = nowNs
                AudioVisualizerBus.publishRawSpectrum(scratchBands)
            }
        }
    }

    companion object {
        /** ~25 fps throttle (40 ms between FFT computations). */
        const val MIN_PUBLISH_INTERVAL_NS: Long = 40_000_000L
    }
}
