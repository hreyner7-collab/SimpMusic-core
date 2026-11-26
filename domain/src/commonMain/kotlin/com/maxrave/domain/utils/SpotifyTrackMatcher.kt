package com.maxrave.domain.utils

import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import kotlin.math.abs

object SpotifyTrackMatcher {
    private val parenRegex = Regex("""[\(\[][^\)\]]*[\)\]]""")
    private val featSuffixRegex = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val dashEditionRegex =
        Regex(
            """\s+-\s+(?:remaster(?:ed)?|live|acoustic|radio edit|mono|stereo|deluxe|version|\d{4}\b).*$""",
            RegexOption.IGNORE_CASE,
        )
    private val nonWordRegex = Regex("""[^\p{L}\p{N}\s]+""")
    private val multiSpaceRegex = Regex("""\s+""")

    private val undesiredKeywords =
        listOf(
            "karaoke",
            "instrumental",
            "8d",
            "slowed",
            "reverb",
            "sped up",
            "nightcore",
            "cover",
            "tribute",
            "8-bit",
            "lullaby",
        )

    fun normalize(text: String): String =
        text
            .lowercase()
            .replace(parenRegex, " ")
            .replace(featSuffixRegex, "")
            .replace(dashEditionRegex, "")
            .replace(nonWordRegex, " ")
            .replace(multiSpaceRegex, " ")
            .trim()

    /**
     * Builds a cleaner secondary search query with parenthetical tags, featuring suffixes, and
     * remaster/edition markers stripped. Returns `null` when it wouldn't improve on the primary query.
     */
    fun buildFallbackQuery(
        title: String,
        artists: List<String>,
    ): String? {
        val cleanTitle =
            title
                .replace(parenRegex, " ")
                .replace(featSuffixRegex, "")
                .replace(dashEditionRegex, "")
                .replace(multiSpaceRegex, " ")
                .trim()
        val primaryArtist = artists.firstOrNull()?.trim().orEmpty()
        val primaryQuery =
            buildString {
                append(title.trim())
                if (primaryArtist.isNotEmpty()) {
                    append(' ')
                    append(primaryArtist)
                }
            }.trim()
        val fallbackQuery =
            buildString {
                append(cleanTitle.ifBlank { title.trim() })
                if (primaryArtist.isNotEmpty()) {
                    append(' ')
                    append(primaryArtist)
                }
            }.trim()
        return fallbackQuery.takeIf { it.isNotBlank() && !it.equals(primaryQuery, ignoreCase = true) }
    }

    fun parseDurationSeconds(durationStr: String?): Int {
        if (durationStr.isNullOrBlank()) return 0
        val parts = durationStr.trim().split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    fun scoreCandidate(
        targetTitle: String,
        targetArtists: List<String>,
        targetDurationMs: Long,
        candidate: SongsResult,
    ): Int {
        val normTargetTitle = normalize(targetTitle)
        val normCandidateTitle = normalize(candidate.title.orEmpty())
        if (normTargetTitle.isBlank() || normCandidateTitle.isBlank()) return -100

        var score = 0

        when {
            normCandidateTitle == normTargetTitle -> score += 60
            normCandidateTitle.contains(normTargetTitle) || normTargetTitle.contains(normCandidateTitle) -> score += 40
            else -> {
                val targetTokens = normTargetTitle.split(" ").filter { it.length > 1 }.toSet()
                val candTokens = normCandidateTitle.split(" ").filter { it.length > 1 }.toSet()
                val overlap = targetTokens.intersect(candTokens).size
                if (targetTokens.isNotEmpty() && overlap > 0) {
                    score += ((overlap * 35) / targetTokens.size)
                } else {
                    score -= 15
                }
            }
        }

        val candArtists =
            candidate.artists
                ?.map { normalize(it.name) }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        val normTargetArtists = targetArtists.map(::normalize).filter { it.isNotBlank() }
        if (normTargetArtists.isNotEmpty() && candArtists.isNotEmpty()) {
            val primaryTarget = normTargetArtists.first()
            val exactArtistMatch =
                candArtists.any { ca ->
                    ca == primaryTarget || ca.contains(primaryTarget) || primaryTarget.contains(ca)
                }
            val anyArtistMatch =
                normTargetArtists.any { ta ->
                    candArtists.any { ca -> ca == ta || ca.contains(ta) || ta.contains(ca) }
                }
            score +=
                when {
                    exactArtistMatch -> 35
                    anyArtistMatch -> 22
                    else -> -10
                }
        }

        val targetSeconds = (targetDurationMs / 1000L).toInt()
        val candSeconds =
            (candidate.durationSeconds ?: 0).let {
                if (it > 0) it else parseDurationSeconds(candidate.duration)
            }
        if (targetSeconds > 0 && candSeconds > 0) {
            val diff = abs(targetSeconds - candSeconds)
            score +=
                when {
                    diff <= 3 -> 25
                    diff <= 8 -> 15
                    diff <= 15 -> 5
                    diff > 45 -> -25
                    else -> 0
                }
        }

        val targetRawLower = targetTitle.lowercase()
        val candRawLower = candidate.title.orEmpty().lowercase()
        for (keyword in undesiredKeywords) {
            if (candRawLower.contains(keyword) && !targetRawLower.contains(keyword)) {
                score -= 35
            }
        }

        return score
    }

    fun selectBestCandidate(
        targetTitle: String,
        targetArtists: List<String>,
        targetDurationMs: Long,
        candidates: List<SongsResult>,
    ): SongsResult? {
        if (candidates.isEmpty()) return null
        val scored =
            candidates.mapIndexed { index, item ->
                Triple(item, scoreCandidate(targetTitle, targetArtists, targetDurationMs, item), index)
            }
        val best =
            scored.maxWithOrNull(
                compareBy<Triple<SongsResult, Int, Int>> { it.second }.thenByDescending { -it.third },
            ) ?: return candidates.firstOrNull()
        return if (best.second >= -5) best.first else null
    }
}
