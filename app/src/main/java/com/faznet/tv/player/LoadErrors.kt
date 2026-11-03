package com.faznet.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * While a channel opens, a 4xx answer goes straight to the player, which decides with [RefusalWatch]: Media3's own retries
 * came on top of the player's, up to 12 requests on an account that may be at its limit and 16 s before the viewer was told.
 * Once a channel plays, Media3 keeps retrying by itself: the buffer covers a refused segment and nobody sees it.
 */
@OptIn(UnstableApi::class)
class OpeningErrorPolicy(private val opening: () -> Boolean) : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (opening() && isRefusal(info.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(info)

    private fun isRefusal(e: Throwable) = e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499
}

/**
 * What went wrong with a load, for the problem log: one word and nothing from the error's text, which can hold an address.
 * Also readable after R8, which renames Media3's classes.
 */
@OptIn(UnstableApi::class)
fun loadErrorKind(e: Throwable): String {
    generateSequence(e) { it.cause }.take(8).forEach {
        when (it) {
            is HttpDataSource.InvalidResponseCodeException -> return "http${it.responseCode}"
            is SocketTimeoutException -> return "timeout"
            is UnknownHostException -> return "dns"
            is ConnectException -> return "connect"
            is SSLException -> return "tls"
            is EOFException -> return "eof"
            is SocketException -> return "socket"
        }
    }
    return "other"
}
