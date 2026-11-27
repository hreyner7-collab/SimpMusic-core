package com.maxrave.domain.data.player

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Real-time mid-side karaoke vocal suppression setting shared by Android Media3 and Desktop MPV.
 *
 * @param enabled whether vocal suppression is active.
 * @param vocalSuppression suppression depth in `0f..1f` (`0.85f` default).
 * @param preserveBass when true, center energy below ~180 Hz (kick/sub-bass) is kept intact while
 *   cancelling centered vocals above the crossover.
 */
@Serializable
data class KaraokeEffect(
    val enabled: Boolean = false,
    val vocalSuppression: Float = DEFAULT_SUPPRESSION,
    val preserveBass: Boolean = true,
) {
    val clampedSuppression: Float
        get() = clampSuppression(vocalSuppression)

    val isActive: Boolean
        get() = enabled && clampedSuppression > 0f

    fun clamped(): KaraokeEffect = copy(vocalSuppression = clampSuppression(vocalSuppression))

    fun encodeToJson(): String = json.encodeToString(serializer(), clamped())

    companion object {
        const val DATASTORE_KEY: String = "karaoke_effect_v1"
        const val DEFAULT_SUPPRESSION: Float = 0.85f
        const val BASS_CUTOFF_HZ: Float = 180f

        val OFF: KaraokeEffect = KaraokeEffect(enabled = false)

        private val json: Json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        fun clampSuppression(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

        fun decodeFromJson(raw: String?): KaraokeEffect {
            if (raw.isNullOrBlank()) return OFF
            return runCatching {
                json.decodeFromString(serializer(), raw).clamped()
            }.getOrDefault(OFF)
        }
    }
}
