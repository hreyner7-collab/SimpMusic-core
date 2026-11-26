package com.maxrave.data.repository

import com.maxrave.domain.repository.DjRepository
import org.simpmusic.aiservice.AiClient

internal class DjRepositoryImpl(
    private val aiClient: AiClient,
) : DjRepository {
    override suspend fun suggest(
        request: String,
        taste: String,
    ): Result<List<String>> =
        aiClient
            .djSuggest("Request: $request. " + if (taste.isNotBlank()) "The listener likes: $taste." else "")
            .mapCatching { raw ->
                val start = raw.indexOf('[')
                val end = raw.lastIndexOf(']')
                require(start in 0 until end) { "The AI did not return a list of songs" }
                kotlinx.serialization.json
                    .Json { ignoreUnknownKeys = true }
                    .decodeFromString<List<String>>(raw.substring(start, end + 1))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(8)
            }

    override suspend fun intro(
        title: String,
        artist: String,
        previousTitle: String?,
        previousArtist: String?,
        reason: String?,
    ): Result<String> {
        val previous =
            if (!previousTitle.isNullOrBlank()) {
                "The song that just played: \"$previousTitle\" by ${previousArtist.orEmpty().ifBlank { "an unknown artist" }}.\n"
            } else {
                ""
            }
        val why = if (!reason.isNullOrBlank()) " Why the DJ picked it: $reason. Work that in naturally." else ""
        return aiClient.djIntro("${previous}The song starting now: \"$title\" by $artist.$why").map { cleanForSpeech(it) }
    }

    /** Speech engines read symbols aloud, so quotes, asterisks and line breaks are dropped. */
    private fun cleanForSpeech(text: String): String =
        text
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("[*_#\"`]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(400)
}
