package com.faznet.tv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.bitmapFactoryMaxParallelism
import coil3.request.crossfade
import com.faznet.tv.net.Http
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toOkioPath

/** Channel logos are kept on the device: they are downloaded once, not on every screen. */
@dagger.hilt.android.HiltAndroidApp
class FaznetApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        com.faznet.tv.player.StreamProbe.app = this
        val repo = com.faznet.tv.data.Repository.get(this)
        com.faznet.tv.data.XtreamApi.userAgent = { repo.userAgent }
        // Keep any crash for the next start, where it is sent as a report (Settings can turn that off).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            com.faznet.tv.data.Reports.saveCrash(this, error)
            previous?.uncaughtException(thread, error)
        }
    }

    /** Downloaded movie and series lists are only a shortcut: give them back first when memory gets short. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) com.faznet.tv.data.LibraryCache.clear()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.images })) }
        // At most two logos at a time are decoded: the screen and the player come first on four small cores.
        .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(2))
        .bitmapFactoryMaxParallelism(2)
        .allowRgb565(true)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("logos").toOkioPath()).maxSizeBytes(80L * 1024 * 1024).build() }
        // Many IPTV logo servers send no-cache headers; logos rarely change, so they are kept anyway: Coil 3 only looks
        // at those headers when asked to, and it is not asked here.
        // No fade-in: logos appear at once and the TV stick's GPU has less to draw while scrolling.
        .crossfade(false)
        .build()
}
