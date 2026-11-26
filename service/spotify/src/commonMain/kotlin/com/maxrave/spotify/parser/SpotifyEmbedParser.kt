package com.maxrave.spotify.parser

import com.maxrave.spotify.model.SpotifyEmbedCollection
import com.maxrave.spotify.model.SpotifyEmbedTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure KMP parser for public `open.spotify.com/embed/{playlist|album|track}/{id}` pages.
 * No Spotify account or token is required because Spotify embeds `__NEXT_DATA__` state directly.
 */
object SpotifyEmbedParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val nextDataRegex =
        Regex(
            pattern = """<script[^>]*id\s*=\s*["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

    fun parseEmbedHtml(
        html: String,
        fallbackKind: String,
        fallbackId: String,
    ): SpotifyEmbedCollection? {
        val rawJson = nextDataRegex.find(html)?.groupValues?.getOrNull(1)?.trim() ?: return null
        if (rawJson.isEmpty()) return null
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val entity =
            root["props"]
                ?.asObjOrNull()
                ?.get("pageProps")
                ?.asObjOrNull()
                ?.get("state")
                ?.asObjOrNull()
                ?.get("data")
                ?.asObjOrNull()
                ?.get("entity")
                ?.asObjOrNull()
                ?: findEntityObject(root)
                ?: return null

        val kind = entity.string("type")?.lowercase()?.takeIf { it.isNotBlank() } ?: fallbackKind.lowercase()
        val id = entity.string("id")?.takeIf { it.isNotBlank() } ?: fallbackId
        val title =
            entity.string("name")
                ?: entity.string("title")
                ?: "Spotify ${kind.replaceFirstChar { it.uppercase() }}"
        val subtitle = entity.string("subtitle")
        val thumbnailUrl = extractBestImageUrl(entity)

        val rawTracks = entity["trackList"] as? JsonArray
        val tracks =
            if (rawTracks != null && rawTracks.isNotEmpty()) {
                rawTracks.mapIndexedNotNull { idx, elem ->
                    val obj = elem.asObjOrNull() ?: return@mapIndexedNotNull null
                    val trackTitle =
                        obj.string("title")
                            ?: obj.string("name")
                            ?: return@mapIndexedNotNull null
                    val artists = extractArtists(obj, fallbackSubtitle = obj.string("subtitle"))
                    val trackId =
                        obj.string("uri")?.substringAfterLast(":")?.takeIf { it.isNotBlank() }
                            ?: obj.string("id")?.takeIf { it.isNotBlank() }
                            ?: obj.string("uid")?.takeIf { it.isNotBlank() }
                            ?: "${id}_$idx"
                    val durationMs = (obj["duration"] as? JsonPrimitive)?.longOrNull ?: 0L
                    SpotifyEmbedTrack(
                        id = trackId,
                        title = trackTitle,
                        artists = artists,
                        durationMs = durationMs,
                        thumbnailUrl = extractBestImageUrl(obj) ?: thumbnailUrl,
                    )
                }
            } else if (kind == "track") {
                val artists = extractArtists(entity, fallbackSubtitle = subtitle)
                listOf(
                    SpotifyEmbedTrack(
                        id = id,
                        title = title,
                        artists = artists,
                        durationMs = (entity["duration"] as? JsonPrimitive)?.longOrNull ?: 0L,
                        thumbnailUrl = thumbnailUrl,
                    ),
                )
            } else {
                emptyList()
            }

        return SpotifyEmbedCollection(
            id = id,
            kind = kind,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            tracks = tracks,
        )
    }

    private fun extractArtists(
        obj: JsonObject,
        fallbackSubtitle: String?,
    ): List<String> {
        val artistsArray = obj["artists"] as? JsonArray
        val fromArray =
            artistsArray
                ?.mapNotNull { it.asObjOrNull()?.string("name")?.takeIf(String::isNotBlank) }
                .orEmpty()
        if (fromArray.isNotEmpty()) return fromArray

        return fallbackSubtitle
            ?.replace('\u00A0', ' ')
            ?.split(",", "•", "·")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    private fun extractBestImageUrl(obj: JsonObject): String? {
        val sources =
            (
                obj["coverArt"]?.asObjOrNull()?.get("sources")
                    ?: obj["visualIdentity"]?.asObjOrNull()?.get("image")
                    ?: obj["images"]
            ) as? JsonArray ?: return null

        var bestUrl: String? = null
        var bestArea = -1
        for (item in sources) {
            val img = item.asObjOrNull() ?: continue
            val url = img.string("url")?.takeIf { it.isNotBlank() } ?: continue
            val w = (img["maxWidth"] as? JsonPrimitive)?.longOrNull?.toInt()
                ?: (img["width"] as? JsonPrimitive)?.longOrNull?.toInt()
                ?: 0
            val h = (img["maxHeight"] as? JsonPrimitive)?.longOrNull?.toInt()
                ?: (img["height"] as? JsonPrimitive)?.longOrNull?.toInt()
                ?: 0
            val area = w * h
            if (bestUrl == null || area >= bestArea) {
                bestUrl = url
                bestArea = area
            }
        }
        return bestUrl
    }

    private fun findEntityObject(element: JsonElement): JsonObject? {
        val obj = element as? JsonObject ?: return null
        obj["entity"]?.asObjOrNull()?.let { return it }
        for (value in obj.values) {
            findEntityObject(value)?.let { return it }
        }
        return null
    }

    private fun JsonElement.asObjOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
