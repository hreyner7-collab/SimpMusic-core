package com.maxrave.spotify

import com.maxrave.spotify.model.SpotifyAlbumDetailResponse
import com.maxrave.spotify.model.SpotifyPlaylistTracksResponse
import com.maxrave.spotify.model.SpotifySavedTracksResponse
import com.maxrave.spotify.model.SpotifyUserPlaylistsResponse
import com.maxrave.spotify.parser.SpotifyEmbedParser
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class Spotify2026SchemaAndSyncTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    @Test
    fun `deserializes February 2026 Web API items schema and legacy tracks schema`() {
        val feb2026PlaylistsPayload =
            """
            {
              "items": [
                {
                  "id": "pl_2026",
                  "name": "Dev Mode 2026 Mix",
                  "items": { "total": 42 },
                  "owner": { "id": "u1", "display_name": "Henry" }
                },
                {
                  "id": "pl_legacy",
                  "name": "Legacy Mix",
                  "tracks": { "total": 15 }
                }
              ],
              "total": 2
            }
            """.trimIndent()

        val playlists = json.decodeFromString<SpotifyUserPlaylistsResponse>(feb2026PlaylistsPayload)
        assertEquals(2, playlists.items.size)
        assertEquals(42, playlists.items[0].totalTracks)
        assertEquals(15, playlists.items[1].totalTracks)

        val feb2026ItemsPayload =
            """
            {
              "items": [
                {
                  "item": {
                    "id": "11dFghVXANMlKmJXsNCbNl",
                    "name": "Cut To The Feeling",
                    "artists": [{"id": "a1", "name": "Carly Rae Jepsen"}],
                    "duration_ms": 207959
                  }
                },
                {
                  "track": {
                    "id": "legacyId",
                    "name": "Legacy Track",
                    "artists": [{"name": "Legacy Artist"}],
                    "duration_ms": 180000
                  }
                }
              ],
              "total": 2
            }
            """.trimIndent()

        val playlistItems = json.decodeFromString<SpotifyPlaylistTracksResponse>(feb2026ItemsPayload)
        assertEquals(2, playlistItems.items.size)
        assertEquals("Cut To The Feeling", playlistItems.items[0].effectiveTrack?.name)
        assertEquals("Carly Rae Jepsen", playlistItems.items[0].effectiveTrack?.artists?.firstOrNull()?.name)
        assertEquals("Legacy Track", playlistItems.items[1].effectiveTrack?.name)

        val savedTracks = json.decodeFromString<SpotifySavedTracksResponse>(feb2026ItemsPayload)
        assertEquals("Cut To The Feeling", savedTracks.items[0].effectiveTrack?.name)

        val album2026Payload =
            """
            {
              "id": "alb1",
              "name": "Sample Album",
              "items": {
                "items": [{"id": "t1", "name": "Intro", "duration_ms": 90000}],
                "total": 1
              }
            }
            """.trimIndent()
        val album = json.decodeFromString<SpotifyAlbumDetailResponse>(album2026Payload)
        assertEquals(1, album.effectiveTracks?.items?.size)
        assertEquals("Intro", album.effectiveTracks?.items?.firstOrNull()?.name)
    }

    @Test
    fun `embed parser prefers real spotify track URI id over row uid`() {
        val html =
            """
            <script id="__NEXT_DATA__" type="application/json">
            {
              "props": {
                "pageProps": {
                  "state": {
                    "data": {
                      "entity": {
                        "type": "playlist",
                        "id": "37i9dQZF1DXcBWIGoYBM5M",
                        "name": "Today's Top Hits",
                        "trackList": [
                          {
                            "uid": "randomRowKey999",
                            "uri": "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
                            "title": "Never Gonna Give You Up",
                            "subtitle": "Rick\u00A0Astley",
                            "duration": 213000
                          }
                        ]
                      }
                    }
                  }
                }
              }
            }
            </script>
            """.trimIndent()

        val parsed = SpotifyEmbedParser.parseEmbedHtml(html, "playlist", "fallback")
        assertNotNull(parsed)
        assertEquals(1, parsed.tracks.size)
        assertEquals("4cOdK2wGLETKBW3PvgPWqT", parsed.tracks[0].id)
        assertEquals(listOf("Rick Astley"), parsed.tracks[0].artists)
    }
}
