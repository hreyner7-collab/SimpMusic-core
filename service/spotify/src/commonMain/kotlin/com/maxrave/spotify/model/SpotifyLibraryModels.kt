package com.maxrave.spotify.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpotifyImageDto(
    @SerialName("url")
    val url: String = "",
    @SerialName("width")
    val width: Int? = null,
    @SerialName("height")
    val height: Int? = null,
)

@Serializable
data class SpotifyArtistRefDto(
    @SerialName("id")
    val id: String? = null,
    @SerialName("name")
    val name: String = "",
)

@Serializable
data class SpotifyAlbumRefDto(
    @SerialName("id")
    val id: String? = null,
    @SerialName("name")
    val name: String = "",
    @SerialName("images")
    val images: List<SpotifyImageDto> = emptyList(),
)

@Serializable
data class SpotifyTrackDto(
    @SerialName("id")
    val id: String? = null,
    @SerialName("name")
    val name: String = "",
    @SerialName("artists")
    val artists: List<SpotifyArtistRefDto> = emptyList(),
    @SerialName("album")
    val album: SpotifyAlbumRefDto? = null,
    @SerialName("duration_ms")
    val durationMs: Long = 0L,
    @SerialName("is_local")
    val isLocal: Boolean = false,
)

@Serializable
data class SpotifyPlaylistTracksSummaryDto(
    @SerialName("total")
    val total: Int = 0,
)

@Serializable
data class SpotifyPlaylistOwnerDto(
    @SerialName("id")
    val id: String? = null,
    @SerialName("display_name")
    val displayName: String? = null,
)

@Serializable
data class SpotifySimplePlaylistDto(
    @SerialName("id")
    val id: String = "",
    @SerialName("name")
    val name: String = "",
    @SerialName("images")
    val images: List<SpotifyImageDto>? = null,
    @SerialName("tracks")
    val tracks: SpotifyPlaylistTracksSummaryDto? = null,
    @SerialName("items")
    val itemsSummary: SpotifyPlaylistTracksSummaryDto? = null,
    @SerialName("owner")
    val owner: SpotifyPlaylistOwnerDto? = null,
) {
    val totalTracks: Int
        get() = itemsSummary?.total ?: tracks?.total ?: 0
}

@Serializable
data class SpotifyUserPlaylistsResponse(
    @SerialName("items")
    val items: List<SpotifySimplePlaylistDto> = emptyList(),
    @SerialName("total")
    val total: Int = 0,
    @SerialName("next")
    val next: String? = null,
)

@Serializable
data class SpotifySavedTrackWrapperDto(
    @SerialName("added_at")
    val addedAt: String? = null,
    @SerialName("track")
    val track: SpotifyTrackDto? = null,
    @SerialName("item")
    val item: SpotifyTrackDto? = null,
) {
    val effectiveTrack: SpotifyTrackDto?
        get() = item ?: track
}

@Serializable
data class SpotifySavedTracksResponse(
    @SerialName("items")
    val items: List<SpotifySavedTrackWrapperDto> = emptyList(),
    @SerialName("total")
    val total: Int = 0,
    @SerialName("next")
    val next: String? = null,
)

@Serializable
data class SpotifyPlaylistTrackWrapperDto(
    @SerialName("track")
    val track: SpotifyTrackDto? = null,
    @SerialName("item")
    val item: SpotifyTrackDto? = null,
) {
    val effectiveTrack: SpotifyTrackDto?
        get() = item ?: track
}

@Serializable
data class SpotifyPlaylistTracksResponse(
    @SerialName("items")
    val items: List<SpotifyPlaylistTrackWrapperDto> = emptyList(),
    @SerialName("total")
    val total: Int = 0,
    @SerialName("next")
    val next: String? = null,
)

@Serializable
data class SpotifySimpleAlbumDto(
    @SerialName("id")
    val id: String = "",
    @SerialName("name")
    val name: String = "",
    @SerialName("artists")
    val artists: List<SpotifyArtistRefDto> = emptyList(),
    @SerialName("images")
    val images: List<SpotifyImageDto>? = null,
    @SerialName("total_tracks")
    val totalTracks: Int = 0,
)

@Serializable
data class SpotifySavedAlbumWrapperDto(
    @SerialName("added_at")
    val addedAt: String? = null,
    @SerialName("album")
    val album: SpotifySimpleAlbumDto? = null,
)

@Serializable
data class SpotifySavedAlbumsResponse(
    @SerialName("items")
    val items: List<SpotifySavedAlbumWrapperDto> = emptyList(),
    @SerialName("total")
    val total: Int = 0,
    @SerialName("next")
    val next: String? = null,
)

@Serializable
data class SpotifyAlbumTracksPageDto(
    @SerialName("items")
    val items: List<SpotifyTrackDto> = emptyList(),
    @SerialName("total")
    val total: Int = 0,
    @SerialName("next")
    val next: String? = null,
)

@Serializable
data class SpotifyAlbumDetailResponse(
    @SerialName("id")
    val id: String = "",
    @SerialName("name")
    val name: String = "",
    @SerialName("artists")
    val artists: List<SpotifyArtistRefDto> = emptyList(),
    @SerialName("images")
    val images: List<SpotifyImageDto> = emptyList(),
    @SerialName("tracks")
    val tracks: SpotifyAlbumTracksPageDto? = null,
    @SerialName("items")
    val itemsPage: SpotifyAlbumTracksPageDto? = null,
) {
    val effectiveTracks: SpotifyAlbumTracksPageDto?
        get() = itemsPage ?: tracks
}

/**
 * Result parsed from a public `open.spotify.com/embed/{kind}/{id}` page without user login.
 */
data class SpotifyEmbedTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val durationMs: Long,
    val thumbnailUrl: String?,
)

data class SpotifyEmbedCollection(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    val tracks: List<SpotifyEmbedTrack>,
)
