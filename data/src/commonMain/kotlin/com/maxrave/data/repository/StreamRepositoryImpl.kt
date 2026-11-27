package com.maxrave.data.repository

import com.maxrave.common.ITAG
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.common.QUALITY
import com.maxrave.common.VIDEO_QUALITY
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.mapping.toSponsorSkipSegments
import com.maxrave.data.mapping.toTrack
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.extension.isBefore
import com.maxrave.domain.extension.now
import com.maxrave.domain.extension.plusSeconds
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.MediaType
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime

internal class StreamRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : StreamRepository {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class CachedStreamUrl(
        val url: String,
        val expiresAt: LocalDateTime,
    )

    companion object {
        @kotlin.concurrent.Volatile
        private var tidalPausedUntil: LocalDateTime? = null

        @kotlin.concurrent.Volatile
        private var tidal429Streak: Int = 0
    }

    /** A stream lookup slower than this gets a second, racing attempt. */
    private val HEDGE_AFTER_MS = 2500L

    private val inFlightMutex = Mutex()
    private val inFlightPlayers = HashMap<String, Deferred<Result<Triple<String?, PlayerResponse, MediaType>>>>()

    private suspend fun playerSingleFlight(
        videoId: String,
        noLogIn: Boolean,
        preferredAudioLanguage: String?,
    ): Result<Triple<String?, PlayerResponse, MediaType>> {
        val flightKey = "$videoId|$noLogIn|$preferredAudioLanguage"
        val (deferred, isOwner) =
            inFlightMutex.withLock {
                val existing = inFlightPlayers[flightKey]
                if (existing != null && existing.isActive) {
                    existing to false
                } else {
                    val created =
                        backgroundScope.async {
                            hedged(HEDGE_AFTER_MS) {
                                youTube.player(
                                    videoId,
                                    noLogIn = noLogIn,
                                    preferredAudioLanguage = preferredAudioLanguage,
                                )
                            }
                        }
                    inFlightPlayers[flightKey] = created
                    created to true
                }
            }
        return try {
            kotlinx.coroutines.withTimeout(22_000L) {
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            if (isOwner) deferred.cancel()
            Result.failure(Exception("Stream resolution timed out for $videoId"))
        } finally {
            if (isOwner) {
                inFlightMutex.withLock {
                    if (inFlightPlayers[flightKey] === deferred) {
                        inFlightPlayers.remove(flightKey)
                    }
                }
            }
        }
    }

    private suspend fun <T> hedged(
        afterMs: Long,
        block: suspend () -> Result<T>,
    ): Result<T> =
        coroutineScope {
            val all = listOf(async { block() }, async { delay(afterMs); block() })
            val pending = all.toMutableList()
            var last: Result<T>? = null
            while (pending.isNotEmpty()) {
                val (done, result) =
                    select<Pair<Deferred<Result<T>>, Result<T>>> {
                        pending.forEach { d -> d.onAwait { d to it } }
                    }
                pending.remove(done)
                if (result.isSuccess) {
                    all.forEach { it.cancel() }
                    return@coroutineScope result
                }
                last = result
            }
            last!!
        }

    private val streamCacheMutex = Mutex()
    private val streamUrlCache = LinkedHashMap<String, CachedStreamUrl>(64)

    private suspend fun getCachedStreamUrl(key: String): String? =
        streamCacheMutex.withLock {
            val entry = streamUrlCache[key] ?: return null
            if (entry.expiresAt.isBefore(now().plusSeconds(90))) {
                streamUrlCache.remove(key)
                null
            } else {
                entry.url
            }
        }

    private suspend fun putCachedStreamUrl(
        key: String,
        url: String,
        expiresInSeconds: Long,
    ) {
        if (expiresInSeconds <= 120L) return
        streamCacheMutex.withLock {
            if (streamUrlCache.size >= 120) {
                val oldestKey = streamUrlCache.keys.firstOrNull()
                if (oldestKey != null) streamUrlCache.remove(oldestKey)
            }
            streamUrlCache[key] = CachedStreamUrl(url = url, expiresAt = now().plusSeconds(expiresInSeconds))
        }
    }
    override suspend fun insertNewFormat(newFormat: NewFormatEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertNewFormat(newFormat)
        }

    override fun getNewFormat(videoId: String): Flow<NewFormatEntity?> = flow { emit(localDataSource.getNewFormat(videoId)) }.flowOn(Dispatchers.IO)

    override suspend fun getFormatFlow(videoId: String) = localDataSource.getNewFormatAsFlow(videoId)

    override fun getExtractSource(videoId: String): String? = youTube.getExtractSource(videoId)

    override suspend fun updateFormat(videoId: String) {
        localDataSource.getNewFormat(videoId)?.let { oldFormat ->
            Logger.d("Stream", "oldFormatExpired: ${oldFormat.expiredTime}")
            Logger.d("Stream", "now: ${now()}")
            Logger.d("Stream", "isExpired: ${oldFormat.expiredTime.isBefore(now())}")
            if (oldFormat.expiredTime.isBefore(now())) {
                youTube
                    .player(videoId)
                    .onSuccess { triple ->
                        val response = triple.second
                        localDataSource.updateNewFormat(
                            oldFormat.copy(
                                expiredTime = now().plusSeconds(response.streamingData?.expiresInSeconds?.toLong() ?: 0L),
                                playbackTrackingVideostatsPlaybackUrl =
                                    response.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                playbackTrackingAtrUrl =
                                    response.playbackTracking?.atrUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                playbackTrackingVideostatsWatchtimeUrl =
                                    response.playbackTracking?.videostatsWatchtimeUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                cpn = triple.first,
                            ),
                        )
                        Logger.w("UpdateFormat", "Updated format for $videoId")
                    }.onFailure { throwable ->
                        if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                        Logger.w("UpdateFormat", "Skipped format refresh for $videoId: ${throwable.message ?: throwable::class.simpleName}")
                    }
            }
        }
    }

    override fun getStream(
        dataStoreManager: DataStoreManager,
        videoId: String,
        isDownloading: Boolean,
        isVideo: Boolean,
        muxed: Boolean,
    ): Flow<String?> =
        flow {
            val itag =
                if (isDownloading) {
                    QUALITY.itagOf(dataStoreManager.downloadQuality.first())
                } else {
                    QUALITY.itagOf(dataStoreManager.quality.first())
                }
            val preferredAudioLanguage = dataStoreManager.preferredAudioLanguage.first()
            val streamCacheKey = "$videoId|$itag|$preferredAudioLanguage"
            if (!isDownloading && !isVideo && !muxed) {
                getCachedStreamUrl(streamCacheKey)?.let { cachedUrl ->
                    Logger.d("Stream", "$videoId: cache hit")
                    emit(cachedUrl)
                    return@flow
                }
            }
            val videoItag =
                if (muxed) {
                    ITAG.MUXED_360P
                } else if (isVideo) {
                    VIDEO_QUALITY.itags.getOrNull(
                        VIDEO_QUALITY.items.indexOf(
                            if (isDownloading) {
                                dataStoreManager.videoDownloadQuality.first()
                            } else {
                                dataStoreManager.videoQuality.first()
                            },
                        ),
                    )
                        ?: ITAG.VIDEO_360P
                } else {
                    ITAG.VIDEO_360P
                }
            // A lookup that is still running after HEDGE_AFTER_MS is usually stuck on one slow
            // server or a fallback step, so a second identical request races it; the first success wins.
            // Deduplicated across concurrent callers per (videoId, noLogIn, preferredAudioLanguage).
            playerSingleFlight(
                videoId = videoId,
                noLogIn = muxed,
                preferredAudioLanguage = preferredAudioLanguage,
            )
                .onSuccess { data ->
                    val response = data.second
                    val isLive = response.videoDetails?.isLive == true
                    LiveStreamRegistry.recordLiveStatus(videoId, isLive)
                    // The song row follows, so the LIVE badge and the download gate know too —
                    // including when a broadcast has ended and its recording downloads normally.
                    backgroundScope.launch {
                        runCatching { localDataSource.updateSongLiveStatus(videoId, isLive) }
                    }
                    if (isLive && isDownloading) {
                        // Nothing to save: handing an endless HLS playlist to a downloader stores
                        // the playlist text as if it were the song. Emitting nothing fails the
                        // download where the caller resolves its URL.
                        Logger.i("Stream", "$videoId: live stream, refusing download")
                        return@onSuccess
                    }
                    if (isLive) {
                        // A live broadcast plays from its HLS playlist alone, so none of the itag
                        // choice below applies. Nothing about it goes into the format table either:
                        // the playlist URL is good for a few hours and the broadcast may be over by
                        // the next run, so every play resolves it afresh — and the players learn
                        // that it is live from LiveStreamRegistry, recorded just above.
                        val liveHlsUrl = response.streamingData?.hlsManifestUrl
                        Logger.i("Stream", "$videoId: live stream, HLS URL ${if (liveHlsUrl == null) "missing" else "found"}")
                        emit(liveHlsUrl)
                        return@onSuccess
                    }
                    Logger.d(
                        "Stream",
                        response.streamingData
                            ?.formats
                            ?.map { it.itag }
                            .toString() + " " +
                            response.streamingData
                                ?.adaptiveFormats
                                ?.map { it.itag }
                                .toString(),
                    )
                    val formatList = mutableListOf<PlayerResponse.StreamingData.Format>()
                    formatList.addAll(
                        response.streamingData?.formats?.filter { it.url.isNullOrEmpty().not() } ?: emptyList(),
                    )
                    formatList.addAll(
                        response.streamingData?.adaptiveFormats?.filter { it.url.isNullOrEmpty().not() }
                            ?: emptyList(),
                    )
                    val videoFormat =
                        formatList.find { it.itag == videoItag }
                            ?: formatList.find { it.itag == ITAG.VIDEO_720P }
                            ?: formatList.find { it.itag == ITAG.VIDEO_360P }
                            ?: formatList.find { !it.isAudio && it.url.isNullOrEmpty().not() }
                    val audioTwinItag = ITAG.highQualityTwinOf(itag)
                    val audioFormat =
                        formatList.find { it.itag == itag && !youTube.isManifestUrl(it.url.orEmpty()) }
                            ?: audioTwinItag?.let { twin ->
                                formatList.find { it.itag == twin && !youTube.isManifestUrl(it.url.orEmpty()) }
                            }
                            ?: formatList.find { it.itag == ITAG.AUDIO_OPUS_MEDIUM && !youTube.isManifestUrl(it.url.orEmpty()) }
                            ?: formatList.find { it.itag == ITAG.AUDIO_AAC_MEDIUM && !youTube.isManifestUrl(it.url.orEmpty()) }
                            ?: formatList.find { it.isAudio && !it.url.isNullOrEmpty() && !youTube.isManifestUrl(it.url.orEmpty()) }
                            ?: formatList.find { it.itag == ITAG.MUXED_360P && !it.url.isNullOrEmpty() && !youTube.isManifestUrl(it.url.orEmpty()) }
                    var format =
                        if (isVideo) {
                            videoFormat
                        } else {
                            audioFormat
                        }
                    if (format == null) {
                        format =
                            if (!isVideo && !muxed) {
                                formatList.lastOrNull { !it.url.isNullOrEmpty() && !youTube.isManifestUrl(it.url.orEmpty()) && it.isAudio }
                                    ?: formatList.lastOrNull { !it.url.isNullOrEmpty() && !youTube.isManifestUrl(it.url.orEmpty()) }
                            } else {
                                formatList.lastOrNull { it.url.isNullOrEmpty().not() }
                            }
                    }
                    if (muxed) {
                        format = formatList
                            .filter {
                                val url = it.url
                                url != null && youTube.isManifestUrl(url)
                            }.maxByOrNull { it.width ?: 0 } ?: formatList.find { it.itag == videoItag }
                    }
                    // One line per resolved stream and never the URL: a googlevideo URL carries the
                    // listener's IP address.
                    Logger.i(
                        "Stream",
                        "$videoId: ${data.third}, itag ${format?.itag} ${format?.mimeType?.substringBefore(';')}" +
                            (if (muxed) ", HLS" else "") +
                            ", expires in ${response.streamingData?.expiresInSeconds}s",
                    )
                    val rangeEnd = format?.contentLength?.takeIf { it > 0 }
                    val resolvedUrl =
                        if (data.first != null) {
                            if (muxed) {
                                response.streamingData?.hlsManifestUrl
                            } else {
                                format?.url?.let { url ->
                                    val withCpn = if (url.contains("cpn=")) url else "$url&cpn=${data.first}"
                                    if (youTube.isManifestUrl(url) || withCpn.contains("range=") || rangeEnd == null) {
                                        withCpn
                                    } else {
                                        "$withCpn&range=0-$rangeEnd"
                                    }
                                }
                            }
                        } else {
                            if (muxed) {
                                response.streamingData?.hlsManifestUrl
                            } else {
                                format?.url?.let { url ->
                                    if (youTube.isManifestUrl(url) || url.contains("range=") || rangeEnd == null) {
                                        url
                                    } else {
                                        "$url&range=0-$rangeEnd"
                                    }
                                }
                            }
                        }
                    if (!isDownloading && !isVideo && !muxed && resolvedUrl != null) {
                        putCachedStreamUrl(
                            streamCacheKey,
                            resolvedUrl,
                            response.streamingData?.expiresInSeconds?.toLong() ?: 0L,
                        )
                    }
                    // Emit stream URL immediately so playback starts without waiting for Room DB or Tidal lookup.
                    emit(resolvedUrl)
                    val durationSecond = response.videoDetails?.lengthSeconds?.toIntOrNull()
                    backgroundScope.launch {
                        val formatEntity =
                            NewFormatEntity(
                                videoId = if (VIDEO_QUALITY.itags.contains(format?.itag)) "${MERGING_DATA_TYPE.VIDEO}$videoId" else videoId,
                                itag = format?.itag ?: itag,
                                mimeType =
                                    Regex("""([^;]+);\s*codecs=["']([^"']+)["']""")
                                        .find(
                                            format?.mimeType ?: "",
                                        )?.groupValues
                                        ?.getOrNull(1) ?: format?.mimeType ?: "",
                                codecs =
                                    Regex("""([^;]+);\s*codecs=["']([^"']+)["']""")
                                        .find(
                                            format?.mimeType ?: "",
                                        )?.groupValues
                                        ?.getOrNull(2) ?: format?.mimeType ?: "",
                                bitrate = format?.bitrate,
                                sampleRate = format?.audioSampleRate,
                                contentLength = format?.contentLength,
                                loudnessDb =
                                    response.playerConfig
                                        ?.audioConfig
                                        ?.loudnessDb
                                        ?.toFloat(),
                                lengthSeconds = response.videoDetails?.lengthSeconds?.toInt(),
                                playbackTrackingVideostatsPlaybackUrl =
                                    response.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                playbackTrackingAtrUrl =
                                    response.playbackTracking?.atrUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                playbackTrackingVideostatsWatchtimeUrl =
                                    response.playbackTracking?.videostatsWatchtimeUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                cpn = data.first,
                                expiredTime = now().plusSeconds(response.streamingData?.expiresInSeconds?.toLong() ?: 0L),
                                audioUrl = if (muxed) response.streamingData?.hlsManifestUrl else format?.url,
                                videoUrl = if (muxed) response.streamingData?.hlsManifestUrl else videoFormat?.url,
                                bpm = null,
                                musicKey = null,
                                keyScale = null,
                            )
                        runCatching { insertNewFormat(formatEntity) }
                        // After TIDAL answers "too many requests", further lookups only fail again and
                        // compete with playback for the connection, so they pause for a while.
                        val tidalPaused = tidalPausedUntil?.let { now().isBefore(it) } == true
                        if (!isVideo && durationSecond != null && data.third == MediaType.Song && !tidalPaused) {
                            val title = response.videoDetails?.title ?: ""
                            val author = response.videoDetails?.author ?: ""
                            val q =
                                "$title $author"
                                    .replace(
                                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                                        " ",
                                    ).replace(
                                        Regex("( và | & | и | e | und |, |和| dan)"),
                                        " ",
                                    ).replace("  ", " ")
                                    .replace(Regex("([()])"), "")
                                    .replace(".", " ")
                                    .replace("  ", " ")
                            Logger.d("Stream", "Search Tidal metadata for: $q")
                            youTube
                                .searchTidalMetadata(q, durationSecond)
                                .onSuccess { metadata ->
                                    tidal429Streak = 0
                                    Logger.d("Stream", "Tidal metadata: $metadata")
                                    runCatching {
                                        val current = localDataSource.getNewFormat(formatEntity.videoId)
                                        if (current != null && !current.expiredTime.isBefore(now())) {
                                            insertNewFormat(
                                                current.copy(
                                                    bpm = metadata.bpm,
                                                    musicKey = metadata.musicKey,
                                                    keyScale = metadata.keyScale,
                                                ),
                                            )
                                        }
                                    }
                                }.onFailure {
                                    if (it is kotlinx.coroutines.CancellationException) throw it
                                    if (it is InterruptedException) {
                                        Thread.currentThread().interrupt()
                                        Logger.d("Stream", "Tidal metadata interrupted")
                                    } else if (it.message?.contains("429") == true) {
                                        val backoffSeconds = minOf(300L * (1L shl tidal429Streak.coerceAtMost(3)), 3600L)
                                        tidal429Streak++
                                        tidalPausedUntil = now().plusSeconds(backoffSeconds)
                                        Logger.w("Stream", "Tidal rate limited (429), pausing metadata lookups for ${backoffSeconds}s")
                                    } else if (it.message == "No matching track found") {
                                        Logger.d("Stream", "Tidal metadata: no match for $q")
                                    } else {
                                        Logger.w("Stream", "Tidal metadata unavailable: ${it.message ?: it::class.simpleName}")
                                    }
                                }
                        }
                    }
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Logger.w("Stream", "Stream unavailable: ${it.message ?: it::class.simpleName}")
                    emit(null)
                }
        }.flowOn(Dispatchers.IO)

    override fun initPlayback(
        playback: String,
        atr: String,
        watchTime: String,
        cpn: String,
        playlistId: String?,
    ): Flow<Pair<Int, Float>> =
        flow {
            youTube
                .initPlayback(playback, atr, watchTime, cpn, playlistId)
                .onSuccess { response ->
                    emit(response)
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Logger.w("InitPlayback", "Skipped: ${it.message ?: it::class.simpleName}")
                    emit(Pair(0, 0f))
                }
        }.flowOn(Dispatchers.IO)

    override fun updateWatchTimeFull(
        watchTime: String,
        cpn: String,
        playlistId: String?,
    ): Flow<Int> =
        flow {
            runCatching {
                youTube
                    .updateWatchTimeFull(watchTime, cpn, playlistId)
                    .onSuccess { response ->
                        emit(response)
                    }.onFailure {
                        it.printStackTrace()
                        emit(0)
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun updateWatchTime(
        playbackTrackingVideostatsWatchtimeUrl: String,
        watchTimeList: ArrayList<Float>,
        cpn: String,
        playlistId: String?,
    ): Flow<Int> =
        flow {
            runCatching {
                youTube
                    .updateWatchTime(
                        playbackTrackingVideostatsWatchtimeUrl,
                        watchTimeList,
                        cpn,
                        playlistId,
                    ).onSuccess { response ->
                        emit(response)
                    }.onFailure {
                        it.printStackTrace()
                        emit(0)
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getSkipSegments(videoId: String): Flow<Resource<List<SponsorSkipSegments>>> =
        flow {
            youTube
                .getSkipSegments(videoId)
                .onSuccess { data ->
                    emit(Resource.Success(data.map { it.toSponsorSkipSegments() }))
                }.onFailure {
                    emit(Resource.Error(it.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getFullMetadata(videoId: String): Flow<Resource<Track>> =
        flow {
            Logger.w("getFullMetadata", "videoId: $videoId")
            youTube
                .getFullMetadata(videoId)
                .onSuccess {
                    emit(Resource.Success(it.toTrack()))
                }.onFailure {
                    Logger.e("getFullMetadata", "Error: ${it.message}")
                    emit(Resource.Error(it.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override fun is403Url(url: String) = flow { emit(youTube.is403Url(url)) }.flowOn(Dispatchers.IO)

    override suspend fun invalidateFormat(videoId: String) {
        withContext(Dispatchers.IO) {
            youTube.invalidateExtractor()
            streamCacheMutex.withLock {
                val prefix = "$videoId|"
                val keysToRemove = streamUrlCache.keys.filter { it.startsWith(prefix) }
                keysToRemove.forEach { streamUrlCache.remove(it) }
            }
            localDataSource.getNewFormat(videoId)?.let { format ->
                Logger.d("Stream", "Invalidating cached format for $videoId")
                localDataSource.updateNewFormat(
                    format.copy(expiredTime = now().plusSeconds(-1)),
                )
            }
        }
    }
}