package com.simpmusic.media_jvm.mpv

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DesktopSpectrumAnalyzerTest {
    private fun sineWave(freqHz: Double, sampleRate: Int = 8000, durationSec: Double = 0.5): ShortArray {
        val count = (sampleRate * durationSec).toInt()
        return ShortArray(count) { i ->
            (sin(2.0 * PI * freqHz * i / sampleRate) * 24000.0).toInt().toShort()
        }
    }

    @Test
    fun separatesBassVocalAndHighPitchFrequencies() {
        val bassSpec = DesktopSpectrumAnalyzer.computeSpectrumFrames(sineWave(100.0))
        assertTrue(bassSpec.size >= 4)
        assertTrue("100 Hz should spike bass band", bassSpec[0] > bassSpec[2] && bassSpec[0] > bassSpec[3])

        val vocalSpec = DesktopSpectrumAnalyzer.computeSpectrumFrames(sineWave(1200.0))
        assertTrue("1200 Hz should spike vocalMid band", vocalSpec[2] > vocalSpec[0] && vocalSpec[2] > vocalSpec[3])

        val highSpec = DesktopSpectrumAnalyzer.computeSpectrumFrames(sineWave(3200.0))
        assertTrue("3200 Hz should spike highPitch band", highSpec[3] > highSpec[0] && highSpec[3] > highSpec[2])
    }
}
