package com.engabd.sendpin.data

import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The one OkHttp client every server talks through — shared timeouts and
 * connection pool, the panel's own User-Agent, and [LanOnlyCleartext]'s guard so
 * plain HTTP only ever goes to hosts on the local network.
 */
object Http {

    /** What this build calls itself to a server — desktop's answer to the app's. */
    const val USER_AGENT: String = "CAMusic-Linux/1.3.2 (desktop)"

    /** Disk budget for cached API responses. Small: this caches JSON, not media. */
    private const val CACHE_BYTES = 16L * 1024 * 1024

    private val userAgent = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .build(),
        )
    }

    @Volatile
    private var cache: Cache? = null

    /** Install the response cache. Optional, as on Android — uncached when skipped. */
    fun initCache(cacheDir: java.io.File) {
        if (cache != null) return
        cache = runCatching { Cache(java.io.File(cacheDir, "http"), CACHE_BYTES) }.getOrNull()
        built = null
    }

    @Volatile
    private var built: OkHttpClient? = null

    /**
     * The shared base — the original's timeouts and pool, deliberately identical:
     * generous enough for a cold NAS, short enough that an unreachable host is
     * reported rather than hung on.
     */
    val base: OkHttpClient
        get() = built ?: synchronized(this) {
            built ?: OkHttpClient.Builder()
                .addInterceptor(LanOnlyCleartext)
                .addInterceptor(userAgent)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .apply { cache?.let { cache(it) } }
                .build()
                .also { built = it }
        }

    /** A long-lived socket: WebSockets and any quiet-but-alive connection. */
    fun socket(pingSeconds: Long): OkHttpClient = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(pingSeconds, TimeUnit.SECONDS)
        .build()

    /** A file transfer: no callTimeout, but a read timeout still gives up. */
    fun transfer(): OkHttpClient = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}