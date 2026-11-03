package com.faznet.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicLong

/** Counts network bytes so the stats overlay can show the real download speed of the stream. */
@OptIn(UnstableApi::class)
object Throughput : TransferListener {
    private val bytes = AtomicLong(0)
    private var lastBytes = 0L
    private var lastTime = System.nanoTime()
    @Volatile private var lastBytesAt = System.nanoTime()

    /** How long the network has sent nothing: tells a provider that stopped from a player that stopped. */
    fun msSinceLastBytes() = (System.nanoTime() - lastBytesAt) / 1_000_000

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) {
            bytes.addAndGet(bytesTransferred.toLong())
            lastBytesAt = System.nanoTime()
        }
    }

    /** Mbit/s since the previous call. */
    fun sampleMbps(): Double {
        val now = System.nanoTime()
        val b = bytes.get()
        val dt = (now - lastTime) / 1e9
        val mbps = if (dt > 0) (b - lastBytes) * 8 / dt / 1e6 else 0.0
        lastBytes = b
        lastTime = now
        return mbps
    }
}
