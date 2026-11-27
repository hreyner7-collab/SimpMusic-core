package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.player.KaraokeEffect
import com.maxrave.domain.mediaservice.player.AudioVisualizerBus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

@UnstableApi
class KaraokeAndVisualizerTest {
    @Test
    fun centered1kHzVocalIsAttenuatedByMoreThan15dB() {
        val sampleRate = 48_000
        val defaultEffect = KaraokeEffect(enabled = true, vocalSuppression = 0.85f, preserveBass = true)
        val fullEffect = KaraokeEffect(enabled = true, vocalSuppression = 1.0f, preserveBass = true)

        val defaultAttenuationDb = measureMonoSineAttenuationDb(1_000.0, sampleRate, defaultEffect)
        val fullAttenuationDb = measureMonoSineAttenuationDb(1_000.0, sampleRate, fullEffect)

        assertTrue("Expected > 15 dB attenuation at default 0.85 suppression, got $defaultAttenuationDb dB", defaultAttenuationDb > 15.0)
        assertTrue("Expected > 20 dB attenuation at full 1.0 suppression, got $fullAttenuationDb dB", fullAttenuationDb > 20.0)
    }

    @Test
    fun stereoSideSignalIsPreservedUnchanged() {
        val kernel = KaraokeKernel(48_000)
        val effect = KaraokeEffect(enabled = true, vocalSuppression = 1.0f, preserveBass = true)
        var maxError = 0.0
        repeat(4_800) { n ->
            val side = 16_000.0 * sin(2.0 * PI * 1_000.0 * n / 48_000.0)
            kernel.processSample(side, -side, effect)
            maxError = maxOf(maxError, abs(kernel.leftOut - side), abs(kernel.rightOut - (-side)))
        }
        assertEquals("Stereo side signal must pass through untouched", 0.0, maxError, 1e-9)
    }

    @Test
    fun subBass80HzIsPreservedWhenPreserveBassIsEnabled() {
        val sampleRate = 48_000
        val keepBass = KaraokeEffect(enabled = true, vocalSuppression = 0.85f, preserveBass = true)
        val cutBass = KaraokeEffect(enabled = true, vocalSuppression = 0.85f, preserveBass = false)

        val lossWithPreserveDb = measureMonoSineAttenuationDb(80.0, sampleRate, keepBass)
        val lossWithoutPreserveDb = measureMonoSineAttenuationDb(80.0, sampleRate, cutBass)

        assertTrue("Expected 80 Hz sub-bass loss < 2.0 dB with preserveBass=true, got $lossWithPreserveDb dB", lossWithPreserveDb < 2.0)
        assertTrue("Expected 80 Hz sub-bass attenuation > 14 dB with preserveBass=false, got $lossWithoutPreserveDb dB", lossWithoutPreserveDb > 14.0)
    }

    @Test
    fun disabledKaraokeIsExactBypassInKernelAndProcessor() {
        val kernel = KaraokeKernel(48_000)
        kernel.processSample(12_345.0, -6_789.0, KaraokeEffect.OFF)
        assertEquals(12_345.0, kernel.leftOut, 0.0)
        assertEquals(-6_789.0, kernel.rightOut, 0.0)

        val processor = KaraokeAudioProcessor { KaraokeEffect.OFF }
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()

        val inputShorts = shortArrayOf(1_200, -3_400, 15_000, 15_000, -20_000, 8_000)
        val input = ByteBuffer.allocateDirect(inputShorts.size * 2).order(ByteOrder.nativeOrder())
        inputShorts.forEach { input.putShort(it) }
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val outputShorts = ShortArray(inputShorts.size) { output.short }
        assertArrayEquals(inputShorts, outputShorts)
    }

    @Test
    fun softClampKeepsNormalSamplesLinearAndBoundsExtremePeaks() {
        assertEquals(12_345.toShort(), KaraokeKernel.softClampToShort(12_345.0))
        assertEquals((-25_000).toShort(), KaraokeKernel.softClampToShort(-25_000.0))
        assertTrue(KaraokeKernel.softClampToShort(60_000.0) in 30_000..Short.MAX_VALUE)
        assertTrue(KaraokeKernel.softClampToShort(-60_000.0) in Short.MIN_VALUE..-30_000)
        assertEquals(0.toShort(), KaraokeKernel.softClampToShort(Double.NaN))
    }

    @Test
    fun audioVisualizerBusRefcountingSmoothingAndReset() {
        drainCollectors()
        try {
            assertFalse(AudioVisualizerBus.hasActiveCollectors())
            AudioVisualizerBus.unregisterCollector() // Underflow guard
            assertFalse(AudioVisualizerBus.hasActiveCollectors())

            AudioVisualizerBus.registerCollector()
            AudioVisualizerBus.registerCollector()
            assertTrue(AudioVisualizerBus.hasActiveCollectors())

            AudioVisualizerBus.unregisterCollector()
            assertTrue(AudioVisualizerBus.hasActiveCollectors())

            val peak = FloatArray(AudioVisualizerBus.BAND_COUNT) { if (it == 4) 1.0f else 0f }
            AudioVisualizerBus.publishRawSpectrum(peak)
            val afterAttack = AudioVisualizerBus.bands.value[4]
            assertTrue("Fast attack should rise quickly (> 0.5), got $afterAttack", afterAttack > 0.5f && afterAttack <= 1.0f)

            val silence = FloatArray(AudioVisualizerBus.BAND_COUNT) { 0f }
            AudioVisualizerBus.publishRawSpectrum(silence)
            val afterDecay = AudioVisualizerBus.bands.value[4]
            assertTrue("Smooth decay should fall gently (0 < $afterDecay < $afterAttack)", afterDecay > 0.1f && afterDecay < afterAttack)

            // Malformed input values clamp cleanly to 0..1.
            val malformed = FloatArray(AudioVisualizerBus.BAND_COUNT) { if (it == 0) Float.NaN else 5.0f }
            AudioVisualizerBus.publishRawSpectrum(malformed)
            assertTrue(AudioVisualizerBus.bands.value.all { it in 0f..1f && it.isFinite() })

            AudioVisualizerBus.reset()
            assertTrue(AudioVisualizerBus.bands.value.all { it == 0f })

            // Re-populate and verify last unregister automatically resets the bus.
            AudioVisualizerBus.publishRawSpectrum(peak)
            assertTrue(AudioVisualizerBus.bands.value[4] > 0f)
            AudioVisualizerBus.unregisterCollector()
            assertFalse(AudioVisualizerBus.hasActiveCollectors())
            assertTrue(AudioVisualizerBus.bands.value.all { it == 0f })
        } finally {
            drainCollectors()
        }
    }

    @Test
    fun visualizerProcessorIsZeroWorkWhenHiddenAndPublishesSpectrumWhenActive() {
        drainCollectors()
        try {
            var fakeClockNs = 100_000_000L
            val processor = VisualizerAudioProcessor(clockNanos = { fakeClockNs })
            processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
            processor.flush()

            // Feed 1 kHz sine with NO active collectors: pass-through is bit-exact and bus stays silent.
            val sineFrameCount = 1_024
            val hiddenBuf = createStereoSineBuffer(1_000.0, 48_000, sineFrameCount, 24_000.0)
            val expectedBytes = ByteArray(hiddenBuf.remaining()).also {
                hiddenBuf.duplicate().get(it)
            }
            processor.queueInput(hiddenBuf)
            val hiddenOut = processor.output
            val actualBytes = ByteArray(hiddenOut.remaining()).also { hiddenOut.get(it) }
            assertArrayEquals("Visualizer must pass PCM through unmodified", expectedBytes, actualBytes)
            assertTrue("Hidden visualizer must publish nothing", AudioVisualizerBus.bands.value.all { it == 0f })

            // Register collector and feed 1 kHz sine: bus receives 24 normalized levels with a clear mid-band peak.
            AudioVisualizerBus.registerCollector()
            fakeClockNs += VisualizerAudioProcessor.MIN_PUBLISH_INTERVAL_NS + 1_000_000L
            val activeBuf = createStereoSineBuffer(1_000.0, 48_000, sineFrameCount, 24_000.0)
            processor.queueInput(activeBuf)
            val bands = AudioVisualizerBus.bands.value
            assertEquals(AudioVisualizerBus.BAND_COUNT, bands.size)
            assertTrue(bands.all { it in 0f..1f && it.isFinite() })
            val peakBand = bands.indices.maxBy { bands[it] }
            assertTrue("1 kHz peak should land in lower-mid bands (8..15), was band $peakBand (val=${bands[peakBand]})", peakBand in 8..15)
            assertTrue("1 kHz peak band should be strong (> 0.4), was ${bands[peakBand]}", bands[peakBand] > 0.4f)
        } finally {
            drainCollectors()
        }
    }

    private fun measureMonoSineAttenuationDb(
        freqHz: Double,
        sampleRateHz: Int,
        effect: KaraokeEffect,
    ): Double {
        val kernel = KaraokeKernel(sampleRateHz)
        val totalSamples = sampleRateHz / 2 // 500 ms
        val settleSamples = sampleRateHz / 5 // ignore first 200 ms transient
        var inSumSq = 0.0
        var outSumSq = 0.0
        for (n in 0 until totalSamples) {
            val x = 16_000.0 * sin(2.0 * PI * freqHz * n / sampleRateHz)
            kernel.processSample(x, x, effect)
            if (n >= settleSamples) {
                inSumSq += x * x
                outSumSq += kernel.leftOut * kernel.leftOut
            }
        }
        val inRms = sqrt(inSumSq / (totalSamples - settleSamples))
        val outRms = sqrt(outSumSq / (totalSamples - settleSamples)).coerceAtLeast(1e-9)
        return 20.0 * log10(inRms / outRms)
    }

    private fun createStereoSineBuffer(
        freqHz: Double,
        sampleRateHz: Int,
        frameCount: Int,
        amplitude: Double,
    ): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.nativeOrder())
        for (n in 0 until frameCount) {
            val s = (amplitude * sin(2.0 * PI * freqHz * n / sampleRateHz)).toInt().toShort()
            buf.putShort(s)
            buf.putShort(s)
        }
        buf.flip()
        return buf
    }

    private fun drainCollectors() {
        while (AudioVisualizerBus.hasActiveCollectors()) {
            AudioVisualizerBus.unregisterCollector()
        }
        AudioVisualizerBus.reset()
    }
}
