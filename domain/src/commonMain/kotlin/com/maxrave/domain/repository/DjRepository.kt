package com.maxrave.domain.repository

/** Writes the short spoken intros of the AI DJ. */
interface DjRepository {
    /**
     * Songs for a request such as "something upbeat", as search phrases ("Title - Artist"). [taste]
     * names a few of the listener's favourite artists so the picks suit them.
     */
    suspend fun suggest(
        request: String,
        taste: String,
    ): Result<List<String>>

    /**
     * One or two sentences introducing [title] by [artist], optionally linking it to the song that
     * played before. Fails when no AI provider is set up or the provider refuses the request.
     */
    suspend fun intro(
        title: String,
        artist: String,
        previousTitle: String?,
        previousArtist: String?,
        reason: String? = null,
    ): Result<String>
}
