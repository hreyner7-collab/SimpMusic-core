package com.maxrave.domain.utils

import com.maxrave.domain.data.model.browse.album.Track
import kotlin.random.Random

/**
 * Unbiased Fisher-Yates shuffle enhanced with artist-spacing so tracks by the same artist are
 * maximally spread apart without sacrificing true variety between runs.
 */
fun <T> smartArtistSpacedShuffle(
    items: List<T>,
    artistKeySelector: (T) -> String?,
    anchorArtistKey: String? = null,
    random: Random = Random.Default,
): List<T> {
    if (items.size <= 1) return items.toList()

    fun normKey(raw: String?): String = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "__unknown__"

    val anchorNorm = anchorArtistKey?.let(::normKey)
    val shuffledInput = items.shuffled(random)
    val buckets = linkedMapOf<String, ArrayDeque<T>>()
    for (item in shuffledInput) {
        val key = normKey(artistKeySelector(item))
        buckets.getOrPut(key) { ArrayDeque() }.addLast(item)
    }

    if (buckets.size <= 1) return shuffledInput

    val result = ArrayList<T>(items.size)
    val recentArtists = ArrayDeque<String>()
    if (anchorNorm != null) {
        recentArtists.addLast(anchorNorm)
    }
    val recentWindowSize = minOf(3, (buckets.size - 1).coerceAtLeast(1))
    var lastArtistKey: String? = anchorNorm

    while (buckets.isNotEmpty()) {
        val eligible =
            buckets.entries
                .filter { it.key !in recentArtists }
                .ifEmpty { buckets.entries.filter { it.key != lastArtistKey } }
                .ifEmpty { buckets.entries.toList() }

        // Prefer buckets with more remaining tracks so large single-artist sets get distributed evenly,
        // while still jittering ties randomly so runs stay unpredictable.
        val maxRemaining = eligible.maxOf { it.value.size }
        val topBuckets = eligible.filter { it.value.size == maxRemaining || (maxRemaining > 1 && it.value.size >= maxRemaining - 1) }
        val chosen = topBuckets[random.nextInt(topBuckets.size)]

        val picked = chosen.value.removeFirst()
        if (chosen.value.isEmpty()) {
            buckets.remove(chosen.key)
        }

        result.add(picked)
        lastArtistKey = chosen.key
        recentArtists.addLast(chosen.key)
        while (recentArtists.size > recentWindowSize) {
            recentArtists.removeFirst()
        }
    }

    return result
}

fun smartArtistSpacedShuffle(
    tracks: List<Track>,
    anchorArtist: String? = null,
    random: Random = Random.Default,
): List<Track> =
    smartArtistSpacedShuffle(
        items = tracks,
        artistKeySelector = { track -> track.artists?.firstOrNull()?.name },
        anchorArtistKey = anchorArtist,
        random = random,
    )
