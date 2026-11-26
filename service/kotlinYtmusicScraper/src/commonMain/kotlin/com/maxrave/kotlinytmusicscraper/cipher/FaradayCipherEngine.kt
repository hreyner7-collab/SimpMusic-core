package com.maxrave.kotlinytmusicscraper.cipher

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * Solves YouTube's signature and `n` challenges locally, using the remote player table published at
 * [PLAYER_CONFIG_URL].
 *
 * The table does NOT contain decoded signatures — it only names the function and class inside
 * YouTube's own player JS that perform the work. The player script is still downloaded and executed
 * here, in QuickJS. What the table buys is the one step that breaks whenever YouTube reshuffles its
 * obfuscation: guessing those names by regex.
 *
 * This engine is deliberately platform-agnostic and knows nothing about any extractor. The adapter
 * that plugs it into PipePipe lives in the platform source sets, because PipePipe is not available
 * on iOS.
 */
class FaradayCipherEngine(
    private val httpClient: HttpClient,
    /**
     * The single thread every QuickJS runtime built here will live on. It must be created with a
     * large stack — see [QuickJsEngine]. Passed in rather than made here so the platform that owns
     * threads decides how big.
     */
    private val jsThread: CoroutineDispatcher,
    private val logger: InnerTubeLogger = InnerTubeLogger.NONE,
) {
    /** What PipePipe needs before it can ask for anything to be decoded. */
    data class PlayerInfo(
        val playerId: String,
        val signatureTimestamp: Int,
    )

    data class DecodeResult(
        val signatures: Map<String, String>,
        val nParameters: Map<String, String>,
    )

    // Our table first, Metrolist's to fill the hashes ours has not published yet. The two never
    // disagree on sig/nClass/sts (checked 2026-10-27), so the order only decides which is asked first.
    private val stores =
        listOf(PLAYER_CONFIG_URL, METROLIST_PLAYER_CONFIG_URL).map {
            RemotePlayerConfigStore(httpClient, DiskBackedPlayerConfigRepository(it), logger)
        }

    /**
     * Serialises every public entry point.
     *
     * Two tracks resolve at once (the player pre-caches the next one), and without this both would
     * miss the solver cache and each build its own QuickJS runtime — two native contexts holding a
     * 192 MB limit apiece, one of which is then disposed while the other is mid-flight. Metrolist
     * takes the same lock across the whole of its own entry point for this reason.
     */
    private val operationMutex = Mutex()

    private val mutex = Mutex()
    private var cachedPlayerId: String? = null
    private var cachedPlayerIdAtMs: Long = 0L
    private var cachedSolver: CachedSolver? = null

    @Volatile
    private var currentSolverKey: String? = null

    fun activeSolverKey(): String? = currentSolverKey

    suspend fun warmUp() {
        operationMutex.withLock {
            val info = playerInfoLocked() ?: return@withLock
            solverFor(info.playerId)
        }
    }

    private class CachedSolver(
        val playerId: String,
        val configEpoch: Long,
        val solver: ZemerCipherSolver,
    )

    /**
     * The player YouTube is currently serving, plus the signature timestamp that must accompany
     * player requests for it.
     *
     * Returns null when either half is unavailable — a partial answer is worse than none, because
     * a wrong timestamp yields a response whose signatures cannot be made to work at all.
     */
    suspend fun playerInfo(): PlayerInfo? = operationMutex.withLock { playerInfoLocked() }

    private suspend fun playerInfoLocked(): PlayerInfo? {
        val playerId = currentPlayerId() ?: return null
        val timestamp =
            stores.firstNotNullOfOrNull { it.getSignatureTimestamp(playerUrlFor(playerId)) } ?: return null
        return PlayerInfo(playerId, timestamp)
    }

    /**
     * Solve every challenge in one pass. Values that cannot be solved are simply absent from the
     * result; the caller decides whether a partial answer is usable.
     */
    suspend fun decode(
        playerId: String,
        signatures: List<String>,
        nParameters: List<String>,
    ): DecodeResult = operationMutex.withLock { decodeLocked(playerId, signatures, nParameters) }

    private suspend fun decodeLocked(
        playerId: String,
        signatures: List<String>,
        nParameters: List<String>,
    ): DecodeResult {
        if (signatures.isEmpty() && nParameters.isEmpty()) return DecodeResult(emptyMap(), emptyMap())
        val solver = solverFor(playerId) ?: return DecodeResult(emptyMap(), emptyMap())
        return DecodeResult(
            signatures =
                signatures
                    .distinct()
                    .mapNotNull { challenge ->
                        solver.solveSignature(challenge)?.let { challenge to it }
                    }.toMap(),
            nParameters =
                nParameters
                    .distinct()
                    .mapNotNull { challenge ->
                        solver.solveN(challenge)?.let { challenge to it }
                    }.toMap(),
        )
    }

    /**
     * Called when a URL this engine deciphered was rejected by the CDN.
     *
     * That is a different signal from "player hash unknown": a stale table produces a signature
     * that is well-formed and still wrong, so nothing throws and nothing looks broken until the
     * media request comes back 403.
     */
    suspend fun invalidate() = operationMutex.withLock { invalidateLocked() }

    private suspend fun invalidateLocked() {
        mutex.withLock {
            cachedPlayerId = null
            cachedPlayerIdAtMs = 0L
            currentSolverKey = null
            cachedSolver?.solver?.dispose()
            cachedSolver = null
        }
        deleteCipherCacheFile(PLAYER_ID_CACHE_FILE)
        stores.forEach { runCatching { it.refreshAfterStreamRejection() } }
    }

    private suspend fun currentPlayerId(): String? {
        val now = Clock.System.now().toEpochMilliseconds()
        mutex.withLock {
            cachedPlayerId?.takeIf { now - cachedPlayerIdAtMs < PLAYER_ID_TTL_MS }?.let { return it }
            readPersistedPlayerId(now)?.let { (persistedId, persistedAt) ->
                cachedPlayerId = persistedId
                cachedPlayerIdAtMs = persistedAt
                return persistedId
            }
        }
        val fetched = fetchPlayerId() ?: return null
        val fetchedAt = Clock.System.now().toEpochMilliseconds()
        mutex.withLock {
            cachedPlayerId = fetched
            cachedPlayerIdAtMs = fetchedAt
        }
        persistPlayerId(fetched, fetchedAt)
        return fetched
    }

    /**
     * Read the player hash off `iframe_api`, the same source NewPipe uses. It is a small script that
     * names the current player build, so it costs far less than the watch page.
     */
    private suspend fun fetchPlayerId(): String? =
        try {
            val response =
                httpClient.getTextWithoutRedirects(Url(IFRAME_API_URL), MAX_IFRAME_BYTES) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    header(HttpHeaders.Accept, "*/*")
                }
            val body = response.body
            if (body == null) {
                logger.w(TAG, "iframe_api returned HTTP ${response.status.value}")
                null
            } else {
                PLAYER_HASH_REGEX.find(body)?.groupValues?.getOrNull(1)
                    ?: run {
                        logger.w(TAG, "player hash not found in iframe_api")
                        null
                    }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.w(TAG, "iframe_api fetch failed", details = mapOf("exceptionType" to error.logType()))
            null
        }

    private suspend fun solverFor(playerId: String): ZemerCipherSolver? {
        if (!PLAYER_ID_REGEX.matches(playerId)) return null
        val playerUrl = playerUrlFor(playerId)

        var config = stores.firstNotNullOfOrNull { it.getConfig(playerUrl) }
        if (config == null) {
            stores.forEach { it.forceRefresh(missingHash = playerId) }
            config = stores.firstNotNullOfOrNull { it.getConfig(playerUrl) }
        }
        if (config == null) {
            logger.d(TAG, "player not in remote table", details = mapOf("player" to playerId))
            return null
        }

        // Each store's epoch only grows, so the sum changes exactly when either table does.
        val epoch = stores.sumOf { it.configEpoch }
        val key = "$playerId:$epoch"
        mutex.withLock {
            cachedSolver
                ?.takeIf { it.playerId == playerId && it.configEpoch == epoch }
                ?.let {
                    currentSolverKey = key
                    return it.solver
                }
        }

        return try {
            val playerCode = loadOrDownloadPlayerCode(playerId, playerUrl) ?: return null
            val created = ZemerCipherSolver.create(playerCode, config, jsThread)
            val replaced =
                mutex.withLock {
                    val previous = cachedSolver
                    cachedSolver = CachedSolver(playerId, epoch, created)
                    currentSolverKey = key
                    previous
                }
            replaced?.solver?.dispose()
            created
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.w(
                TAG,
                "solver build failed",
                details = mapOf("player" to playerId, "exceptionType" to error.logType()),
            )
            null
        }
    }

    private suspend fun loadOrDownloadPlayerCode(
        playerId: String,
        playerUrl: String,
    ): String? {
        val cacheName = "base_$playerId.js"
        readCipherCacheFile(cacheName)?.takeIf { it.isNotEmpty() }?.let { return it }
        val downloaded = downloadPlayerCode(playerUrl) ?: return null
        if (downloaded.isNotEmpty()) {
            writeCipherCacheFile(cacheName, downloaded)
            pruneOldPlayerScripts(keepFileName = cacheName)
        }
        return downloaded
    }

    private suspend fun downloadPlayerCode(playerUrl: String): String? =
        try {
            val response =
                httpClient.getTextWithoutRedirects(Url(playerUrl), MAX_PLAYER_SCRIPT_BYTES) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    header(HttpHeaders.Accept, "*/*")
                    header("Referer", "https://www.youtube.com/")
                }
            response.body ?: run {
                logger.w(TAG, "player script HTTP ${response.status.value}")
                null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.w(TAG, "player script fetch failed", details = mapOf("exceptionType" to error.logType()))
            null
        }

    private fun Throwable.logType(): String = this::class.simpleName ?: "Exception"

    companion object {
        private const val TAG = "FaradayCipherEngine"

        /**
         * The published player table.
         *
         * Both gates inside [RemotePlayerConfigStore] have to accept this URL: it must end in
         * `/player_configs.json`, and its path must sit under the repository the store whitelists.
         * A URL that fails either gate makes the store return null with no error anywhere.
         */
        const val PLAYER_CONFIG_URL: String =
            "https://raw.githubusercontent.com/maxrave-dev/simpmusic-files/main/registry/player_configs.json"

        /** Metrolist's own table — same schema, already whitelisted by [RemotePlayerConfigStore]. */
        const val METROLIST_PLAYER_CONFIG_URL: String =
            "https://raw.githubusercontent.com/MetrolistGroup/faraday/master/registry/player_configs.json"

        private const val IFRAME_API_URL = "https://www.youtube.com/iframe_api"
        private const val USER_AGENT = "okhttp/5.4.0"
        private const val MAX_IFRAME_BYTES = 512 * 1024
        private const val MAX_PLAYER_SCRIPT_BYTES = 8 * 1024 * 1024
        private const val PLAYER_ID_TTL_MS = 30 * 60 * 1000L

        private val PLAYER_HASH_REGEX = Regex("""player\\?/([a-z0-9]{8})\\?/""")
        private val PLAYER_ID_REGEX = Regex("^[A-Za-z0-9_-]{4,32}$")
        private const val PLAYER_ID_CACHE_FILE = "player_id.txt"

        internal fun cipherCacheDir(): Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "simpmusic-cipher"

        private fun readCipherCacheFile(name: String): String? =
            runCatching {
                val path = cipherCacheDir() / name
                if (!FileSystem.SYSTEM.exists(path)) return@runCatching null
                FileSystem.SYSTEM.read(path) { readUtf8() }
            }.getOrNull()

        private fun writeCipherCacheFile(
            name: String,
            content: String,
        ) {
            runCatching {
                val dir = cipherCacheDir()
                FileSystem.SYSTEM.createDirectories(dir)
                FileSystem.SYSTEM.write(dir / name) { writeUtf8(content) }
            }
        }

        private fun deleteCipherCacheFile(name: String) {
            runCatching {
                val path = cipherCacheDir() / name
                if (FileSystem.SYSTEM.exists(path)) FileSystem.SYSTEM.delete(path)
            }
        }

        private fun pruneOldPlayerScripts(keepFileName: String) {
            runCatching {
                val dir = cipherCacheDir()
                if (!FileSystem.SYSTEM.exists(dir)) return@runCatching
                FileSystem.SYSTEM.list(dir).forEach { child ->
                    val name = child.name
                    if (name.startsWith("base_") && name.endsWith(".js") && name != keepFileName) {
                        runCatching { FileSystem.SYSTEM.delete(child) }
                    }
                }
            }
        }

        private fun readPersistedPlayerId(nowMs: Long): Pair<String, Long>? {
            val raw = readCipherCacheFile(PLAYER_ID_CACHE_FILE) ?: return null
            val sep = raw.indexOf('|')
            if (sep <= 0) return null
            val ts = raw.substring(0, sep).toLongOrNull() ?: return null
            val id = raw.substring(sep + 1).trim()
            if (nowMs - ts >= PLAYER_ID_TTL_MS || !PLAYER_ID_REGEX.matches(id)) return null
            return id to ts
        }

        private fun persistPlayerId(
            playerId: String,
            fetchedAtMs: Long,
        ) {
            writeCipherCacheFile(PLAYER_ID_CACHE_FILE, "$fetchedAtMs|$playerId")
        }

        internal fun playerUrlFor(playerId: String): String = "https://www.youtube.com/s/player/$playerId/player_ias.vflset/en_GB/base.js"
    }
}

/**
 * Keeps the fetched table in memory AND persists it to [FileSystem.SYSTEM_TEMPORARY_DIRECTORY]/simpmusic-cipher
 * so cold app starts skip downloading `player_configs.json` on the critical playback path.
 */
internal class DiskBackedPlayerConfigRepository(
    private val url: String,
) : PlayerConfigRepository {
    override val enabled: Boolean = true
    override val sourceUrl: String = url
    override val defaultSourceUrl: String = url

    private val cacheFileName: String = "player_cfg_${url.hashCode().toUInt().toString(16)}.cache"
    private var loaded = false

    private var _cachedJson: String = ""
    private var _cachedAtMs: Long = 0L
    private var _cachedSourceUrl: String = ""
    private var _cachedEtag: String = ""

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val path = FaradayCipherEngine.cipherCacheDir() / cacheFileName
            if (!FileSystem.SYSTEM.exists(path)) return
            val text = FileSystem.SYSTEM.read(path) { readUtf8() }
            val nl = text.indexOf('\n')
            if (nl <= 0) return
            val header = text.substring(0, nl).split('\t')
            if (header.size < 3) return
            _cachedAtMs = header[0].toLongOrNull() ?: 0L
            _cachedSourceUrl = header[1]
            _cachedEtag = header[2]
            _cachedJson = text.substring(nl + 1)
        }
    }

    private fun persist() {
        runCatching {
            val dir = FaradayCipherEngine.cipherCacheDir()
            FileSystem.SYSTEM.createDirectories(dir)
            val safeSource = _cachedSourceUrl.replace('\n', ' ').replace('\t', ' ')
            val safeEtag = _cachedEtag.replace('\n', ' ').replace('\t', ' ')
            val payload = "${_cachedAtMs}\t$safeSource\t$safeEtag\n$_cachedJson"
            FileSystem.SYSTEM.write(dir / cacheFileName) { writeUtf8(payload) }
        }
    }

    override var cachedJson: String
        get() {
            ensureLoaded()
            return _cachedJson
        }
        set(value) {
            ensureLoaded()
            _cachedJson = value
            persist()
        }

    override var cachedAtMs: Long
        get() {
            ensureLoaded()
            return _cachedAtMs
        }
        set(value) {
            ensureLoaded()
            _cachedAtMs = value
            persist()
        }

    override var cachedSourceUrl: String
        get() {
            ensureLoaded()
            return _cachedSourceUrl
        }
        set(value) {
            ensureLoaded()
            _cachedSourceUrl = value
            persist()
        }

    override var cachedEtag: String
        get() {
            ensureLoaded()
            return _cachedEtag
        }
        set(value) {
            ensureLoaded()
            _cachedEtag = value
            persist()
        }
}

/**
 * Keeps the fetched table for the life of the process only.
 */
internal class InMemoryPlayerConfigRepository(
    private val url: String,
) : PlayerConfigRepository {
    override val enabled: Boolean = true
    override val sourceUrl: String = url
    override val defaultSourceUrl: String = url
    override var cachedJson: String = ""
    override var cachedAtMs: Long = 0L
    override var cachedSourceUrl: String = ""
    override var cachedEtag: String = ""
}