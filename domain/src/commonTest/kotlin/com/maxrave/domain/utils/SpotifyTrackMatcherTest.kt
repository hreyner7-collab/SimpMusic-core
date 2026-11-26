package com.maxrave.domain.utils

import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyTrackMatcherTest {
    private fun song(
        id: String,
        title: String,
        artist: String,
        durationSeconds: Int,
    ): SongsResult =
        SongsResult(
            album = null,
            artists = listOf(Artist(id = null, name = artist)),
            category = "Song",
            duration = null,
            durationSeconds = durationSeconds,
            feedbackTokens = null,
            isExplicit = false,
            resultType = "song",
            thumbnails = null,
            title = title,
            videoId = id,
            videoType = null,
            year = "",
        )

    @Test
    fun `prefers studio match over karaoke or sped-up trap`() {
        val karaoke = song("karaoke_id", "Midnight City (Karaoke Version)", "Karaoke Box", 243)
        val spedUp = song("sped_id", "Midnight City (Sped Up)", "Nightcore Channel", 190)
        val original = song("orig_id", "Midnight City", "M83", 243)

        val picked =
            SpotifyTrackMatcher.selectBestCandidate(
                targetTitle = "Midnight City (2011 Remaster)",
                targetArtists = listOf("M83"),
                targetDurationMs = 243_000L,
                candidates = listOf(karaoke, spedUp, original),
            )
        assertEquals("orig_id", picked?.videoId)
    }

    @Test
    fun `normalize strips featuring tags and brackets while parseDurationSeconds handles mmss`() {
        assertEquals("starboy", SpotifyTrackMatcher.normalize("Starboy (feat. Daft Punk) [Explicit]"))
        assertEquals(225, SpotifyTrackMatcher.parseDurationSeconds("3:45"))
        assertEquals(3723, SpotifyTrackMatcher.parseDurationSeconds("1:02:03"))
    }
}
