package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.data.player.KaraokeEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeGraphTest {
    @Test
    fun returnsNullWhenDisabledOrZeroSuppression() {
        assertNull(karaokeGraph(KaraokeEffect.OFF))
        assertNull(karaokeGraph(KaraokeEffect(enabled = false, vocalSuppression = 0.9f, preserveBass = true)))
        assertNull(karaokeGraph(KaraokeEffect(enabled = true, vocalSuppression = 0f, preserveBass = true)))
        assertNull(karaokeGraph(KaraokeEffect(enabled = true, vocalSuppression = -0.5f, preserveBass = true)))
        assertNull(karaokeGraph(KaraokeEffect(enabled = true, vocalSuppression = Float.NaN, preserveBass = true)))
        assertNull(karaokeGraph(KaraokeEffect(enabled = true, vocalSuppression = Float.NEGATIVE_INFINITY, preserveBass = false)))
    }

    @Test
    fun buildsValidBassPreservingLavfiGraph() {
        val graph = karaokeGraph(KaraokeEffect(enabled = true, vocalSuppression = 0.85f, preserveBass = true))
        assertNotNull(graph)
        val value = graph!!
        assertTrue("Expected split into low/high bands: $value", value.startsWith("asplit[kl][kh];"))
        assertTrue("Expected 180 Hz low-pass bass branch: $value", value.contains("[kl]lowpass=f=180:p=1[kbass];"))
        assertTrue("Expected 180 Hz high-pass vocal branch: $value", value.contains("[kh]highpass=f=180:p=1,stereotools=mlev=0.1500:slev=1[kvox];"))
        assertTrue("Expected unity non-normalizing mix: $value", value.endsWith("[kbass][kvox]amix=inputs=2:weights=1 1:normalize=0"))
    }

    @Test
    fun buildsValidFullBandGraphWhenPreserveBassIsFalse() {
        val graph = karaokeGraph(KaraokeEffect(enabled = true, vocalSuppression = 0.85f, preserveBass = false))
        assertEquals("stereotools=mlev=0.1500:slev=1", graph)
    }

    @Test
    fun clampsOutOfRangeSuppressionAndRespectsFfmpegMinMlev() {
        val atFull = karaokeGraph(KaraokeEffect(enabled = true, vocalSuppression = 1.0f, preserveBass = false))
        val aboveOne = karaokeGraph(KaraokeEffect(enabled = true, vocalSuppression = 2.5f, preserveBass = false))
        val posInf = karaokeGraph(KaraokeEffect(enabled = true, vocalSuppression = Float.POSITIVE_INFINITY, preserveBass = true))

        assertEquals("stereotools=mlev=0.0157:slev=1", atFull)
        assertEquals(atFull, aboveOne)
        assertNotNull(posInf)
        assertTrue("Expected clamped mlev=0.0157 in split graph: $posInf", posInf!!.contains("stereotools=mlev=0.0157:slev=1"))
        assertFalse(posInf.contains("NaN"))
        assertFalse(posInf.contains("Infinity"))
        assertFalse("mlev must never drop below ffmpeg stereotools minimum 0.015625", posInf.contains("mlev=0.0000"))
    }

    @Test
    fun karaokeEffectJsonRoundTripAndClamping() {
        val raw = KaraokeEffect(enabled = true, vocalSuppression = 1.4f, preserveBass = false)
        val encoded = raw.encodeToJson()
        val decoded = KaraokeEffect.decodeFromJson(encoded)
        assertEquals(KaraokeEffect(enabled = true, vocalSuppression = 1.0f, preserveBass = false), decoded)
        assertEquals(KaraokeEffect.OFF, KaraokeEffect.decodeFromJson("not-valid-json"))
        assertEquals(KaraokeEffect.OFF, KaraokeEffect.decodeFromJson(null))
    }
}
