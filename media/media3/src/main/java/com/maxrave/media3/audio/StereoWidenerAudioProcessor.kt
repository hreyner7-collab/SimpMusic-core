package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.player.AudioEffects
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [AudioProcessor] for the stereo widener.
 *
 * Mid/side: mid = (L+R)/2 stays, side = (L-R)/2 is scaled by the width, then L = mid+side and
 * R = mid-side. That is what ffmpeg's `extrastereo` does on desktop (width 1.0 unchanged, higher
 * wider), so one stored setting sounds the same on both platforms. Results are clipped to 16 bit,
 * as `extrastereo` does with c=1.
 *
 * Like the other effect processors the setting is read fresh on every buffer, so dragging the
 * slider is audible at once. Only stereo PCM 16-bit is touched; anything else passes through.
 */
@UnstableApi
class StereoWidenerAudioProcessor(
    private val effects: () -> AudioEffects,
) : BaseAudioProcessor() {
    private var stereo = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        stereo = inputAudioFormat.channelCount == 2
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val width = effects().stereoWidth
        if (width == null || !stereo || width == 1f) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        while (inputBuffer.remaining() >= 4) {
            val l = inputBuffer.short.toInt()
            val r = inputBuffer.short.toInt()
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * width
            output.putShort((mid + side).toInt().coerceIn(-32768, 32767).toShort())
            output.putShort((mid - side).toInt().coerceIn(-32768, 32767).toShort())
        }
        // A frame is four bytes, so this should never be left over; pass any remainder through.
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
    }
}
