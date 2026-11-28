package com.simpmusic.media_jvm.mpv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WindowsBluetoothAudioHelperTest {
    @Test
    fun `uuidToWinGuidBytes produces exact Windows mixed-endian GUID bytes`() {
        val guid = UUID.fromString("7FA06C40-B8F6-4C7E-8556-E8C33A12E54D")
        val expected =
            byteArrayOf(
                0x40, 0x6C, 0xA0.toByte(), 0x7F,
                0xF6.toByte(), 0xB8.toByte(),
                0x7E, 0x4C,
                0x85.toByte(), 0x56,
                0xE8.toByte(), 0xC3.toByte(), 0x3A, 0x12, 0xE5.toByte(), 0x4D,
            )
        assertArrayEquals(expected, WindowsBluetoothAudioHelper.uuidToWinGuidBytes(guid))
    }

    @Test
    fun `listDisconnectedSpeakers enumerates cleanly without throwing`() {
        val speakers = WindowsBluetoothAudioHelper.listDisconnectedSpeakers()
        // Every returned speaker must have a 12-hex MAC and a non-blank name & endpoint GUID.
        speakers.forEach { spk ->
            assertTrue("Expected 12-char hex MAC, got ${spk.mac}", spk.mac.matches(Regex("[0-9A-F]{12}")))
            assertTrue(spk.name.isNotBlank())
            assertTrue(spk.endpointGuid.startsWith("{"))
            assertTrue(spk.ksPath.contains("0000110b", ignoreCase = true))
        }
    }
}
