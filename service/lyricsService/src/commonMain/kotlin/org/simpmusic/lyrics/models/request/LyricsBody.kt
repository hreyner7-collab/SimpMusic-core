package org.simpmusic.lyrics.models.request

import kotlinx.serialization.Serializable

@Serializable
data class LyricsBody(
    val videoId: String,
    val songTitle: String,
    val artistName: String,
    val albumName: String,
    val durationSeconds: Int,
    val plainLyric: String,
    val syncedLyrics: String = "",
    val richSyncLyrics: String = "",
    val trackType: String,
    val contributor: String,
    val contributorEmail: String,
)