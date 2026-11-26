package com.maxrave.spotify.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpotifyEmbedParserTest {
    @Test
    fun `parses playlist embed NEXT_DATA html cleanly`() {
        val html =
            """
            <html><head><title>Spotify</title></head><body>
            <script id="__NEXT_DATA__" type="application/json">
            {
              "props": {
                "pageProps": {
                  "state": {
                    "data": {
                      "entity": {
                        "type": "playlist",
                        "id": "pl123",
                        "name": "Late Night Coding",
                        "subtitle": "By Henry",
                        "coverArt": {
                          "sources": [
                            {"url": "https://img/small.jpg", "width": 64, "height": 64},
                            {"url": "https://img/large.jpg", "width": 640, "height": 640}
                          ]
                        },
                        "trackList": [
                          {"uid": "t1", "title": "Midnight City", "subtitle": "M83", "duration": 243000},
                          {"uri": "spotify:track:t2", "title": "Resonance", "subtitle": "HOME, Synth", "duration": 212000}
                        ]
                      }
                    }
                  }
                }
              }
            }
            </script></body></html>
            """.trimIndent()

        val parsed = SpotifyEmbedParser.parseEmbedHtml(html, "playlist", "fallback")
        assertNotNull(parsed)
        assertEquals("pl123", parsed.id)
        assertEquals("playlist", parsed.kind)
        assertEquals("Late Night Coding", parsed.title)
        assertEquals("https://img/large.jpg", parsed.thumbnailUrl)
        assertEquals(2, parsed.tracks.size)
        assertEquals("Midnight City", parsed.tracks[0].title)
        assertEquals(listOf("M83"), parsed.tracks[0].artists)
        assertEquals(243000L, parsed.tracks[0].durationMs)
        assertEquals("t2", parsed.tracks[1].id)
        assertEquals(listOf("HOME", "Synth"), parsed.tracks[1].artists)
    }

    @Test
    fun `parses single track embed without trackList`() {
        val html =
            """
            <script id="__NEXT_DATA__" type="application/json">
            {
              "props": {
                "pageProps": {
                  "state": {
                    "data": {
                      "entity": {
                        "type": "track",
                        "id": "tr99",
                        "name": "Blinding Lights",
                        "artists": [{"name": "The Weeknd"}],
                        "duration": 200000
                      }
                    }
                  }
                }
              }
            }
            </script>
            """.trimIndent()

        val parsed = SpotifyEmbedParser.parseEmbedHtml(html, "track", "tr99")
        assertNotNull(parsed)
        assertEquals(1, parsed.tracks.size)
        assertEquals("Blinding Lights", parsed.tracks[0].title)
        assertEquals(listOf("The Weeknd"), parsed.tracks[0].artists)
    }

    @Test
    fun `returns null when embed payload is absent`() {
        assertNull(SpotifyEmbedParser.parseEmbedHtml("<html><body>No data</body></html>", "playlist", "x"))
    }
}
