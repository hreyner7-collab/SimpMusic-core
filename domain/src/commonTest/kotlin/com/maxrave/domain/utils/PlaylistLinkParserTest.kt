package com.maxrave.domain.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistLinkParserTest {
    @Test
    fun `parses spotify URLs and URIs across playlist album and track`() {
        assertEquals(
            ParsedPlaylistLink.SpotifyPlaylist("37i9dQZF1DXcBWIGoYBM5M"),
            PlaylistLinkParser.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123"),
        )
        assertEquals(
            ParsedPlaylistLink.SpotifyAlbum("4aawyAB9vmqN3uQ7FjRGTy"),
            PlaylistLinkParser.parse("https://open.spotify.com/intl-es/album/4aawyAB9vmqN3uQ7FjRGTy"),
        )
        assertEquals(
            ParsedPlaylistLink.SpotifyTrack("7qiZfU4dY1lWllzX7mPBI3"),
            PlaylistLinkParser.parse("https://open.spotify.com/embed/track/7qiZfU4dY1lWllzX7mPBI3"),
        )
        assertEquals(
            ParsedPlaylistLink.SpotifyPlaylist("12345abcde"),
            PlaylistLinkParser.parse("spotify:playlist:12345abcde"),
        )
    }

    @Test
    fun `parses youtube and yt music playlist links`() {
        assertEquals(
            ParsedPlaylistLink.YouTubePlaylist("PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj"),
            PlaylistLinkParser.parse("https://www.youtube.com/playlist?list=PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj"),
        )
        assertEquals(
            ParsedPlaylistLink.YouTubePlaylist("OLAK5uy_k999test"),
            PlaylistLinkParser.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=OLAK5uy_k999test"),
        )
    }

    @Test
    fun `returns null for empty or unsupported links`() {
        assertNull(PlaylistLinkParser.parse(""))
        assertNull(PlaylistLinkParser.parse("   "))
        assertNull(PlaylistLinkParser.parse("https://example.com/music/123"))
        assertNull(PlaylistLinkParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }
}
