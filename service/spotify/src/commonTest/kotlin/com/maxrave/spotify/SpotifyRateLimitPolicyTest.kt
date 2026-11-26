package com.maxrave.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotifyRateLimitPolicyTest {
    @Test
    fun `parseRetryAfterSeconds parses positive integers and ignores invalid input`() {
        assertNull(SpotifyRateLimitPolicy.parseRetryAfterSeconds(null))
        assertNull(SpotifyRateLimitPolicy.parseRetryAfterSeconds(""))
        assertNull(SpotifyRateLimitPolicy.parseRetryAfterSeconds("0"))
        assertNull(SpotifyRateLimitPolicy.parseRetryAfterSeconds("-5"))
        assertNull(SpotifyRateLimitPolicy.parseRetryAfterSeconds("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(3L, SpotifyRateLimitPolicy.parseRetryAfterSeconds(" 3 "))
        assertEquals(300L, SpotifyRateLimitPolicy.parseRetryAfterSeconds("9999"))
    }

    @Test
    fun `computeRetryDelayMs uses exponential backoff and caps automatic wait`() {
        assertEquals(1_500L, SpotifyRateLimitPolicy.computeRetryDelayMs(attempt = 0, retryAfterSeconds = null))
        assertEquals(3_500L, SpotifyRateLimitPolicy.computeRetryDelayMs(attempt = 1, retryAfterSeconds = null))
        assertEquals(7_000L, SpotifyRateLimitPolicy.computeRetryDelayMs(attempt = 2, retryAfterSeconds = null))
        assertEquals(15_000L, SpotifyRateLimitPolicy.computeRetryDelayMs(attempt = 3, retryAfterSeconds = null))
        assertEquals(30_000L, SpotifyRateLimitPolicy.computeRetryDelayMs(attempt = 4, retryAfterSeconds = null))
        assertNull(SpotifyRateLimitPolicy.computeRetryDelayMs(attempt = 5, retryAfterSeconds = null))

        assertEquals(5_000L, SpotifyRateLimitPolicy.computeRetryDelayMs(attempt = 0, retryAfterSeconds = 5L))
        assertEquals(90_000L, SpotifyRateLimitPolicy.computeRetryDelayMs(attempt = 0, retryAfterSeconds = 90L))
        assertNull(SpotifyRateLimitPolicy.computeRetryDelayMs(attempt = 0, retryAfterSeconds = 91L))
    }
}
