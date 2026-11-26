package com.maxrave.domain.utils

sealed class ParsedPlaylistLink {
    data class SpotifyPlaylist(
        val id: String,
    ) : ParsedPlaylistLink()

    data class SpotifyAlbum(
        val id: String,
    ) : ParsedPlaylistLink()

    data class SpotifyTrack(
        val id: String,
    ) : ParsedPlaylistLink()

    data class YouTubePlaylist(
        val playlistId: String,
    ) : ParsedPlaylistLink()
}

object PlaylistLinkParser {
    private val spotifyUriRegex =
        Regex("""^spotify:(playlist|album|track):([A-Za-z0-9]+)(?:\?.*)?$""", RegexOption.IGNORE_CASE)

    private val spotifyUrlRegex =
        Regex(
            """^https?://(?:open|play)\.spotify\.com/(?:intl-[a-z0-9-]+/)?(?:embed/)?(playlist|album|track)/([A-Za-z0-9]+)(?:[/?#].*)?$""",
            RegexOption.IGNORE_CASE,
        )

    private val youtubeHostRegex =
        Regex("""^https?://(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be|youtube-nocookie\.com)/.*""", RegexOption.IGNORE_CASE)

    private val queryListRegex = Regex("""[?&]list=([A-Za-z0-9_-]+)""")

    fun parse(rawInput: String): ParsedPlaylistLink? {
        val input = rawInput.trim()
        if (input.isEmpty()) return null

        spotifyUriRegex.matchEntire(input)?.let { m ->
            val kind = m.groupValues[1].lowercase()
            val id = m.groupValues[2]
            return toSpotifyLink(kind, id)
        }

        spotifyUrlRegex.matchEntire(input)?.let { m ->
            val kind = m.groupValues[1].lowercase()
            val id = m.groupValues[2]
            return toSpotifyLink(kind, id)
        }

        if (youtubeHostRegex.matches(input)) {
            val listId = queryListRegex.find(input)?.groupValues?.getOrNull(1)?.trim()
            if (!listId.isNullOrEmpty()) {
                return ParsedPlaylistLink.YouTubePlaylist(listId)
            }
        }

        return null
    }

    private fun toSpotifyLink(
        kind: String,
        id: String,
    ): ParsedPlaylistLink? =
        when (kind) {
            "playlist" -> ParsedPlaylistLink.SpotifyPlaylist(id)
            "album" -> ParsedPlaylistLink.SpotifyAlbum(id)
            "track" -> ParsedPlaylistLink.SpotifyTrack(id)
            else -> null
        }
}
