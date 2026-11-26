package com.maxrave.data

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.parser.search.parseSearchSong
import com.maxrave.data.repository.SpotifyLibraryRepositoryImpl
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.SpotifyTrackMatcher
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.spotify.Spotify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpotifyLiveSyncAndPkceTest {
    private fun createPkceOnlyDataStore(): DataStoreManager =
        Proxy.newProxyInstance(
            DataStoreManager::class.java.classLoader,
            arrayOf(DataStoreManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSpdc" -> flowOf("")
                "getSpotifyOAuthClientId" -> flowOf("dummy_client_id")
                "getSpotifyOAuthRefreshToken" -> flowOf("dummy_refresh_token")
                "getSpotifyOAuthAccessToken" -> flowOf("dummy_access_token")
                "getSpotifyOAuthExpiresAt" -> flowOf(Long.MAX_VALUE / 2)
                "getString" -> flowOf<String?>(null)
                else ->
                    if (method.returnType == Flow::class.java) {
                        flowOf("")
                    } else {
                        Unit
                    }
            }
        } as DataStoreManager

    private inline fun <reified T> stubProxy(): T =
        Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            when {
                method.returnType == Flow::class.java -> flowOf<Any?>(null)
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == Integer.TYPE -> 0
                method.returnType == java.lang.Long.TYPE -> 0L
                else -> Unit
            }
        } as T

    private fun allocateDummyLocalDataSource(): LocalDataSource {
        val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        f.isAccessible = true
        val unsafe = f.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(LocalDataSource::class.java) as LocalDataSource
    }

    @Test
    fun `PKCE-only login passes getUserSpotifyLibrary connection gate`() =
        runBlocking {
            val repo =
                SpotifyLibraryRepositoryImpl(
                    spotify = Spotify(),
                    dataStoreManager = createPkceOnlyDataStore(),
                    searchRepository = stubProxy<SearchRepository>(),
                    playlistRepository = stubProxy<PlaylistRepository>(),
                    localDataSource = allocateDummyLocalDataSource(),
                )
            assertTrue(repo.isSpotifyConnected().first(), "PKCE token should report connected")

            val result = repo.getUserSpotifyLibrary(forceRefresh = true)
            if (result is Resource.Error) {
                assertNotEquals(
                    "Spotify account is not connected",
                    result.message,
                    "Bug regression: PKCE-only login was rejected for missing sp_dc",
                )
            }
        }

    @Test
    fun `live public Spotify playlist embed extracts real tracks and matches YouTube Music`() =
        runBlocking {
            val spotify = Spotify()
            val colResult = spotify.getPublicEmbedCollection("playlist", "37i9dQZF1DXcBWIGoYBM5M")
            val collection = colResult.getOrThrow()

            assertTrue(collection.title.isNotBlank(), "Embed playlist title should be non-blank")
            assertTrue(collection.tracks.isNotEmpty(), "Embed playlist must return >0 tracks")
            val sampleTrack = collection.tracks.first()
            assertFalse(sampleTrack.title.isBlank(), "First track title should not be blank")
            assertTrue(sampleTrack.id.length >= 15, "Expected real Spotify track ID, got: ${sampleTrack.id}")

            val yt = YouTube()
            var matchedCount = 0
            val matchedSummaries = mutableListOf<String>()

            for (track in collection.tracks.take(3)) {
                val primaryQuery =
                    buildString {
                        append(track.title)
                        if (track.artists.isNotEmpty()) {
                            append(' ')
                            append(track.artists.first())
                        }
                    }.trim()
                val res = yt.search(primaryQuery, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                val candidates = res?.let(::parseSearchSong).orEmpty()
                var picked =
                    SpotifyTrackMatcher.selectBestCandidate(
                        targetTitle = track.title,
                        targetArtists = track.artists,
                        targetDurationMs = track.durationMs,
                        candidates = candidates,
                    )
                if (picked == null) {
                    val fallbackQuery = SpotifyTrackMatcher.buildFallbackQuery(track.title, track.artists)
                    if (fallbackQuery != null) {
                        val fallbackRes = yt.search(fallbackQuery, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                        val fallbackCandidates = fallbackRes?.let(::parseSearchSong).orEmpty()
                        picked =
                            SpotifyTrackMatcher.selectBestCandidate(
                                targetTitle = track.title,
                                targetArtists = track.artists,
                                targetDurationMs = track.durationMs,
                                candidates = fallbackCandidates,
                            )
                    }
                }
                if (picked != null) {
                    matchedCount++
                    matchedSummaries.add("${track.title} -> ${picked.videoId} (${picked.title})")
                }
            }

            println(
                "LIVE_SPOTIFY_SYNC_VERIFIED playlist='${collection.title}' tracks=${collection.tracks.size} " +
                    "matchedSample=$matchedCount/3 samples=$matchedSummaries",
            )
            assertNotNull(collection.tracks.firstOrNull())
            assertTrue(
                matchedCount >= 2,
                "Expected at least 2/3 live Spotify embed tracks to match on YouTube Music, matched=$matchedCount ($matchedSummaries)",
            )
        }
}
