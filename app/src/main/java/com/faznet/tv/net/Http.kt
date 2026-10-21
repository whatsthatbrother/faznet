package com.faznet.tv.net

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared OkHttp clients: keep-alive connections are reused across zaps, which makes channel switching faster. */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(5, 2, TimeUnit.MINUTES))
            .build()
    }

    /** Short timeouts for probing a stream's format before playback. */
    val probe: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .build()
    }

    /** API calls to the Faznet server. */
    val api: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
