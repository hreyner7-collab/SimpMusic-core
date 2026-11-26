package com.my.kizzy.rpc

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ArtworkCache {
    private const val MAX_ENTRIES = 256
    private val cache = LinkedHashMap<String, String>(64)
    private val mutex = Mutex()

    // The whole lookup+fetch runs under one mutex: concurrent presence updates for the same
    // artwork can't fan out into duplicate external-asset HTTP calls, and the plain map is
    // never touched from two threads at once (commonMain — no java.util.concurrent here).
    suspend fun getOrFetch(key: String, fetch: suspend () -> String?): String? =
        mutex.withLock {
            cache[key] ?: fetch()?.also { resolved ->
                if (cache.size >= MAX_ENTRIES) {
                    cache.keys.firstOrNull()?.let { oldest -> cache.remove(oldest) }
                }
                cache[key] = resolved
            }
        }
}
