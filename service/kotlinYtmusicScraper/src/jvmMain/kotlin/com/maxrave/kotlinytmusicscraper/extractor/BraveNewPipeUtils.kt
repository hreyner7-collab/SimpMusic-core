package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal fun List<Pair<Int, String>>.hasRequiredItags(): Boolean {
    val itags = this.mapTo(HashSet()) { it.first }
    return ITAG.AUDIO.any { it in itags }
}

private val streamHealthCheckClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .callTimeout(4500, TimeUnit.MILLISECONDS)
        .build()
}

/**
 * Pick one audio URL (or video URL if absent) and verify a real 64 KB byte-range GET (`bytes=0-65535`)
 * returns 2xx with actual media payload bytes (`>= 64` bytes). `googlevideo.com` can answer `200` to
 * `HEAD` and `206` to `bytes=0-0` on URLs that immediately 403 on real playback chunks.
 */
internal fun List<Pair<Int, String>>.headCheckRandomStream(): Boolean {
    val candidate =
        this.firstOrNull { it.first in ITAG.AUDIO }
            ?: this.firstOrNull { it.first in ITAG.VIDEO }
            ?: return false
    return runCatching {
        val request =
            okhttp3.Request
                .Builder()
                .get()
                .url(candidate.second)
                .header("Range", "bytes=0-65535")
                .build()
        streamHealthCheckClient.newCall(request).execute().use { response ->
            if (response.code !in 200..299) return@use false
            val source = response.body?.source() ?: return@use false
            source.request(64)
            source.buffer.size >= 64L
        }
    }.getOrDefault(false)
}

class BraveNewPipeDownloaderImpl(
    proxy: Proxy?,
) : Downloader() {
    private val client =
        OkHttpClient
            .Builder()
            .proxy(proxy)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response =
        client.newCall(buildOkHttpRequest(request)).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }

            val body = response.body?.string() ?: ""
            Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                body,
                response.request.url.toString(),
            )
        }

    private fun buildOkHttpRequest(request: Request): okhttp3.Request {
        val builder =
            okhttp3.Request
                .Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())
                .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        request.headers().forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                builder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    builder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                builder.header(headerName, headerValueList[0])
            }
        }
        return builder.build()
    }
}
