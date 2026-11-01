package com.faznet.tv

import android.os.SystemClock
import android.util.Log

/**
 * Timings in logcat (tag FaznetPerf), one line per step; the scripts in tools/ read them.
 * Only times and channel names go here, never addresses or account data.
 */
object Perf {
    const val TAG = "FaznetPerf"

    fun now(): Long = SystemClock.elapsedRealtime()

    fun log(line: String) {
        Log.i(TAG, line)
    }

    fun since(what: String, start: Long) = log("$what ${now() - start} ms")

    inline fun <R> measure(what: String, block: () -> R): R {
        val start = now()
        val result = block()
        since(what, start)
        return result
    }
}
