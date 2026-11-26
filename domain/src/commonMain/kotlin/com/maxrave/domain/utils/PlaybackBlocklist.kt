package com.maxrave.domain.utils

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val PLAYBACK_BLOCKLIST_KEY = "playback_blocklist_json"
private const val MUSIC_ONLY_MODE_KEY = "music_only_mode"

private val blocklistJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

@Serializable
enum class BlockedKind {
    SONG,
    ARTIST,
}

@Serializable
data class BlockedItem(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val kind: BlockedKind,
)

data class PlaybackBlocklistState(
    val items: List<BlockedItem> = emptyList(),
) {
    private val blockedSongIds: Set<String> =
        items.asSequence()
            .filter { it.kind == BlockedKind.SONG && it.id.isNotBlank() }
            .map { it.id.trim() }
            .toHashSet()

    private val blockedArtistIds: Set<String> =
        items.asSequence()
            .filter { it.kind == BlockedKind.ARTIST && it.id.isNotBlank() && !it.id.startsWith("name:") }
            .map { it.id.trim() }
            .toHashSet()

    private val blockedArtistNamesNormalized: Set<String> =
        items.asSequence()
            .filter { it.kind == BlockedKind.ARTIST && it.name.isNotBlank() }
            .map { it.name.trim().lowercase() }
            .toHashSet()

    fun isEmpty(): Boolean = items.isEmpty()

    fun isSongIdBlocked(videoId: String?): Boolean {
        val clean = videoId?.trim().orEmpty()
        return clean.isNotEmpty() && clean in blockedSongIds
    }

    fun isArtistBlocked(
        artistId: String?,
        artistName: String?,
    ): Boolean {
        val cleanId = artistId?.trim().orEmpty()
        if (cleanId.isNotEmpty() && !cleanId.startsWith("name:") && cleanId in blockedArtistIds) {
            return true
        }
        val cleanName = artistName?.trim()?.lowercase().orEmpty()
        return cleanName.isNotEmpty() && cleanName in blockedArtistNamesNormalized
    }

    fun isTrackBlocked(track: Track): Boolean {
        if (isSongIdBlocked(track.videoId)) return true
        return track.artists?.any { artist ->
            isArtistBlocked(artist.id, artist.name)
        } == true
    }

    fun isSongBlocked(song: SongEntity): Boolean {
        if (isSongIdBlocked(song.videoId)) return true
        val ids = song.artistId.orEmpty()
        val names = song.artistName.orEmpty()
        if (ids.any { id -> id.trim().takeIf { it.isNotEmpty() } in blockedArtistIds }) return true
        return names.any { name -> name.trim().lowercase() in blockedArtistNamesNormalized }
    }

    fun filterAllowed(tracks: List<Track>): List<Track> =
        if (items.isEmpty()) tracks else tracks.filterNot(::isTrackBlocked)
}

object PlaybackBlocklistCodec {
    fun decode(raw: String?): PlaybackBlocklistState {
        if (raw.isNullOrBlank()) return PlaybackBlocklistState()
        val parsed = runCatching { blocklistJson.decodeFromString<List<BlockedItem>>(raw) }.getOrNull().orEmpty()
        return PlaybackBlocklistState(parsed.distinctBy { it.kind to it.id })
    }

    fun encode(items: List<BlockedItem>): String = blocklistJson.encodeToString(items)

    fun normalizeArtistId(
        artistId: String?,
        artistName: String,
    ): String = artistId?.trim()?.takeIf { it.isNotEmpty() } ?: "name:${artistName.trim().lowercase()}"
}

fun DataStoreManager.playbackBlocklistFlow(): Flow<PlaybackBlocklistState> =
    getString(PLAYBACK_BLOCKLIST_KEY)
        .map { PlaybackBlocklistCodec.decode(it) }
        .distinctUntilChanged()

suspend fun DataStoreManager.getPlaybackBlocklist(): PlaybackBlocklistState =
    PlaybackBlocklistCodec.decode(getString(PLAYBACK_BLOCKLIST_KEY).first())

suspend fun DataStoreManager.blockSong(
    videoId: String,
    title: String,
    artistSummary: String = "",
) {
    val cleanId = videoId.trim()
    if (cleanId.isEmpty()) return
    val current = getPlaybackBlocklist().items
    val entry =
        BlockedItem(
            id = cleanId,
            name = title.trim().ifEmpty { cleanId },
            subtitle = artistSummary.trim(),
            kind = BlockedKind.SONG,
        )
    val updated = listOf(entry) + current.filterNot { it.kind == BlockedKind.SONG && it.id == cleanId }
    putString(PLAYBACK_BLOCKLIST_KEY, PlaybackBlocklistCodec.encode(updated))
}

suspend fun DataStoreManager.blockArtist(
    artistId: String?,
    artistName: String,
) {
    val cleanName = artistName.trim()
    if (cleanName.isEmpty() && artistId.isNullOrBlank()) return
    val resolvedId = PlaybackBlocklistCodec.normalizeArtistId(artistId, cleanName)
    val displayName = cleanName.ifEmpty { resolvedId }
    val current = getPlaybackBlocklist().items
    val entry =
        BlockedItem(
            id = resolvedId,
            name = displayName,
            subtitle = "Artist",
            kind = BlockedKind.ARTIST,
        )
    val updated =
        listOf(entry) +
            current.filterNot {
                it.kind == BlockedKind.ARTIST &&
                    (it.id == resolvedId || (displayName.isNotEmpty() && it.name.equals(displayName, ignoreCase = true)))
            }
    putString(PLAYBACK_BLOCKLIST_KEY, PlaybackBlocklistCodec.encode(updated))
}

suspend fun DataStoreManager.unblockItem(
    kind: BlockedKind,
    id: String,
) {
    val current = getPlaybackBlocklist().items
    val updated = current.filterNot { it.kind == kind && it.id == id }
    putString(PLAYBACK_BLOCKLIST_KEY, PlaybackBlocklistCodec.encode(updated))
}

fun DataStoreManager.musicOnlyModeFlow(): Flow<Boolean> =
    getString(MUSIC_ONLY_MODE_KEY)
        .map { it == DataStoreManager.TRUE }
        .distinctUntilChanged()

suspend fun DataStoreManager.setMusicOnlyMode(enabled: Boolean) {
    putString(MUSIC_ONLY_MODE_KEY, if (enabled) DataStoreManager.TRUE else DataStoreManager.FALSE)
}
