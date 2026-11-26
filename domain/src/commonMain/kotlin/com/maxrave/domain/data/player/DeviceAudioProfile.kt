package com.maxrave.domain.data.player

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.FALSE
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Saved audio chain snapshot bound to one physical output device (e.g. "BLUETOOTH:airpods pro"
 * or "DEVICE_SPEAKER:built-in"). Keyed by [AudioOutputKind] + normalized name rather than
 * platform-ephemeral numeric IDs so Bluetooth reconnects always resolve to the same profile.
 */
@Serializable
data class DeviceAudioProfile(
    val deviceKey: String,
    val displayName: String,
    val kind: String = AudioOutputKind.OTHER.name,
    val equalizerEnabled: Boolean = false,
    val equalizerBands: List<Float> = emptyList(),
    val equalizerPreamp: Float = 0f,
    val autoEqProfile: String = "",
    val stereoWidenEnabled: Boolean = false,
    val stereoWidenAmount: Float = 0.5f,
    val normalizeVolume: Boolean = false,
    val crossfadeEnabled: Boolean = false,
    val crossfadeDurationMs: Int = 5000,
)

@Serializable
data class DeviceAudioProfileStore(
    val profiles: List<DeviceAudioProfile> = emptyList(),
)

object DeviceAudioProfiles {
    const val KEY_ENABLED = "per_device_audio_profiles_enabled_v1"
    const val KEY_PROFILES_JSON = "per_device_audio_profiles_json_v1"

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun deviceKeyFor(output: AudioOutput): String {
        val cleaned = output.name.trim().lowercase().replace(Regex("""\s+"""), " ")
        val suffix = cleaned.ifBlank { "default" }
        return "${output.kind.name}:$suffix"
    }

    fun displayLabelFor(output: AudioOutput): String =
        output.name.trim().ifBlank {
            when (output.kind) {
                AudioOutputKind.DEVICE_SPEAKER -> "Device Speaker"
                AudioOutputKind.BLUETOOTH -> "Bluetooth Audio"
                AudioOutputKind.WIRED -> "Wired Headphones"
                AudioOutputKind.USB -> "USB Audio"
                AudioOutputKind.HDMI -> "HDMI Audio"
                AudioOutputKind.OTHER -> "System Default"
            }
        }

    fun encodeStore(store: DeviceAudioProfileStore): String = json.encodeToString(store)

    fun decodeStore(raw: String?): DeviceAudioProfileStore {
        if (raw.isNullOrBlank()) return DeviceAudioProfileStore()
        return runCatching { json.decodeFromString<DeviceAudioProfileStore>(raw) }
            .getOrDefault(DeviceAudioProfileStore())
    }

    fun DataStoreManager.perDeviceAudioProfilesEnabledFlow(): Flow<Boolean> =
        getString(KEY_ENABLED).map { it == TRUE }

    suspend fun DataStoreManager.setPerDeviceAudioProfilesEnabled(enabled: Boolean) {
        putString(KEY_ENABLED, if (enabled) TRUE else FALSE)
    }

    fun DataStoreManager.deviceAudioProfilesFlow(): Flow<List<DeviceAudioProfile>> =
        getString(KEY_PROFILES_JSON).map { decodeStore(it).profiles }

    suspend fun DataStoreManager.getDeviceAudioProfiles(): List<DeviceAudioProfile> =
        decodeStore(getString(KEY_PROFILES_JSON).first()).profiles

    suspend fun DataStoreManager.saveDeviceAudioProfile(profile: DeviceAudioProfile) {
        val current = getDeviceAudioProfiles().filterNot { it.deviceKey == profile.deviceKey }
        val updated = DeviceAudioProfileStore(profiles = (current + profile).sortedBy { it.displayName.lowercase() })
        putString(KEY_PROFILES_JSON, encodeStore(updated))
    }

    suspend fun DataStoreManager.deleteDeviceAudioProfile(deviceKey: String) {
        val updated = DeviceAudioProfileStore(profiles = getDeviceAudioProfiles().filterNot { it.deviceKey == deviceKey })
        putString(KEY_PROFILES_JSON, encodeStore(updated))
    }

    suspend fun DataStoreManager.captureProfileFor(output: AudioOutput): DeviceAudioProfile {
        val bandsRaw = equalizerBands.first()
        val bands = bandsRaw.split(",").mapNotNull { it.trim().toFloatOrNull() }
        return DeviceAudioProfile(
            deviceKey = deviceKeyFor(output),
            displayName = displayLabelFor(output),
            kind = output.kind.name,
            equalizerEnabled = equalizerEnabled.first() == TRUE,
            equalizerBands = bands,
            equalizerPreamp = equalizerPreamp.first(),
            autoEqProfile = equalizerAutoEqProfile.first(),
            stereoWidenEnabled = stereoWidenEnabled.first() == TRUE,
            stereoWidenAmount = stereoWidenAmount.first(),
            normalizeVolume = normalizeVolume.first() == TRUE,
            crossfadeEnabled = crossfadeEnabled.first() == TRUE,
            crossfadeDurationMs = crossfadeDuration.first(),
        )
    }

    suspend fun DataStoreManager.applyDeviceAudioProfile(profile: DeviceAudioProfile) {
        setEqualizerEnabled(profile.equalizerEnabled)
        if (profile.equalizerBands.isNotEmpty()) {
            setEqualizerBands(profile.equalizerBands)
        }
        setEqualizerPreamp(profile.equalizerPreamp)
        val autoEqLines = profile.autoEqProfile.split("\n", limit = 2)
        if (autoEqLines.size == 2 && autoEqLines[0].isNotBlank()) {
            val autoBands = autoEqLines[1].split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (autoBands.isNotEmpty()) {
                setEqualizerAutoEqProfile(autoEqLines[0], autoBands)
            }
        }
        setStereoWidenEnabled(profile.stereoWidenEnabled)
        setStereoWidenAmount(profile.stereoWidenAmount.coerceIn(0f, 1f))
        setNormalizeVolume(profile.normalizeVolume)
        setCrossfadeEnabled(profile.crossfadeEnabled)
        setCrossfadeDuration(profile.crossfadeDurationMs)
    }
}
