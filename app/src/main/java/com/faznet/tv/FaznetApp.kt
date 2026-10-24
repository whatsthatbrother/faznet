package com.faznet.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.faznet.tv.net.Http

/** Channel logos are kept on the device: they are downloaded once, not on every screen. */
class FaznetApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        com.faznet.tv.player.StreamProbe.app = this
        // Keep any crash for the next start, where it is sent as a report (Settings can turn that off).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            com.faznet.tv.data.Reports.saveCrash(this, error)
            previous?.uncaughtException(thread, error)
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Http.client }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("logos")).maxSizeBytes(80L * 1024 * 1024).build() }
        // Many IPTV logo servers send no-cache headers; logos rarely change, so keep them anyway.
        .respectCacheHeaders(false)
        // No fade-in: logos appear at once and the TV stick's GPU has less to draw while scrolling.
        .crossfade(false)
        .build()
}
