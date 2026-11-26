package com.maxrave.domain.utils

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackBlocklistAndSmartShuffleTest {
    private fun track(
        id: String,
        artistName: String,
        artistId: String? = null,
    ): Track =
        Track(
            album = null,
            artists = listOf(Artist(id = artistId, name = artistName)),
            duration = null,
            durationSeconds = 200,
            isAvailable = true,
            isExplicit = false,
            likeStatus = null,
            thumbnails = null,
            title = "Title $id",
            videoId = id,
            videoType = null,
            category = null,
            feedbackTokens = null,
            resultType = null,
        )

    @Test
    fun `blocklist matches song id, artist id, and case-insensitive artist name`() {
        val items =
            listOf(
                BlockedItem(id = "vid_bad", name = "Bad Song", subtitle = "Artist X", kind = BlockedKind.SONG),
                BlockedItem(id = "UC_BAD_ARTIST", name = "Toxic Artist", kind = BlockedKind.ARTIST),
                BlockedItem(id = "name:noise maker", name = "Noise Maker", kind = BlockedKind.ARTIST),
            )
        val encoded = PlaybackBlocklistCodec.encode(items)
        val state = PlaybackBlocklistCodec.decode(encoded)

        assertTrue(state.isTrackBlocked(track("vid_bad", "Good Artist")))
        assertTrue(state.isTrackBlocked(track("vid_ok1", "Different Display Name", artistId = "UC_BAD_ARTIST")))
        assertTrue(state.isTrackBlocked(track("vid_ok2", "noise maker")))
        assertFalse(state.isTrackBlocked(track("vid_good", "Daft Punk", artistId = "UC_GOOD")))

        val allowed =
            state.filterAllowed(
                listOf(
                    track("vid_bad", "A"),
                    track("vid_good", "Daft Punk"),
                    track("vid_ok2", "NOISE MAKER"),
                ),
            )
        assertEquals(listOf("vid_good"), allowed.map { it.videoId })

        val blockedEntity =
            SongEntity(
                videoId = "other",
                title = "Any",
                artistName = listOf("Noise Maker"),
                duration = "3:20",
                durationSeconds = 200,
                isAvailable = true,
                isExplicit = false,
                likeStatus = "INDIFFERENT",
                videoType = "SONG",
                category = null,
                resultType = null,
            )
        assertTrue(state.isSongBlocked(blockedEntity))
    }

    @Test
    fun `smartArtistSpacedShuffle preserves exact multiset and spaces artists apart`() {
        val tracks =
            listOf(
                track("a1", "Artist A"),
                track("a2", "Artist A"),
                track("a3", "Artist A"),
                track("b1", "Artist B"),
                track("b2", "Artist B"),
                track("b3", "Artist B"),
                track("c1", "Artist C"),
                track("c2", "Artist C"),
                track("c3", "Artist C"),
                track("d1", "Artist D"),
                track("d2", "Artist D"),
            )
        val result = smartArtistSpacedShuffle(tracks, anchorArtist = "Artist A", random = Random(42))

        assertEquals(tracks.size, result.size)
        assertEquals(tracks.map { it.videoId }.sorted(), result.map { it.videoId }.sorted())
        assertNotEquals("Artist A", result.first().artists?.firstOrNull()?.name)

        for (i in 0 until result.lastIndex) {
            val curArtist = result[i].artists?.firstOrNull()?.name
            val nextArtist = result[i + 1].artists?.firstOrNull()?.name
            assertNotEquals(curArtist, nextArtist, "Adjacent duplicate artist at index $i: $curArtist")
        }
    }

    @Test
    fun `SpotifyTrackMatcher buildFallbackQuery strips edition noise only when helpful`() {
        val q = SpotifyTrackMatcher.buildFallbackQuery("Midnight City (2011 Remaster) [feat. M83]", listOf("M83"))
        assertNotNull(q)
        assertEquals("Midnight City M83", q)

        assertNull(SpotifyTrackMatcher.buildFallbackQuery("Midnight City", listOf("M83")))

        val cand =
            SongsResult(
                album = null,
                artists = listOf(Artist(id = null, name = "M83")),
                category = "Song",
                duration = "4:03",
                durationSeconds = 243,
                feedbackTokens = null,
                isExplicit = false,
                resultType = "song",
                thumbnails = null,
                title = "Midnight City",
                videoId = "ok",
                videoType = null,
                year = "",
            )
        assertNotNull(SpotifyTrackMatcher.selectBestCandidate("Midnight City", listOf("M83"), 243_000L, listOf(cand)))
    }
}
