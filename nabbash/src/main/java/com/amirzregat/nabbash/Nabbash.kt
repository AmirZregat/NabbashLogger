package com.amirzregat.nabbash

import android.content.Context
import com.amirzregat.nabbash.internal.NabbashRuntime

/**
 * Debug-only network and message logger with a built-in console.
 *
 * Nabbash starts by itself when the app process starts; there is nothing to call in
 * `Application.onCreate`. It only runs in debuggable builds: in any other build every
 * method here returns immediately and nothing is stored.
 *
 * Add it with `debugImplementation` and add `nabbash-no-op` with `releaseImplementation`
 * so release builds contain none of this code.
 */
public object Nabbash {

    /** True when Nabbash is recording, which is only ever the case in a debuggable build. */
    @JvmStatic
    public val isEnabled: Boolean
        get() = NabbashRuntime.core != null

    /** Changes the defaults. Safe to call at any time and from any thread. */
    @JvmStatic
    public fun configure(block: NabbashConfig.() -> Unit) {
        NabbashRuntime.configure(block)
    }

    /** Stores a message in the current session. */
    @JvmStatic
    @JvmOverloads
    public fun log(
        message: String,
        label: String = "App",
        level: NabbashLevel = NabbashLevel.DEBUG,
        throwable: Throwable? = null,
    ) {
        val core = NabbashRuntime.core ?: return
        core.recordMessage(System.currentTimeMillis(), level.ordinal, label, message, throwable)
    }

    /**
     * Stores one network call. Apps on OkHttp or Retrofit should add [NabbashInterceptor]
     * instead; this is for other HTTP clients.
     */
    @JvmStatic
    @JvmOverloads
    public fun logNetwork(
        method: String,
        url: String,
        statusCode: Int? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        requestBody: String? = null,
        responseHeaders: Map<String, String> = emptyMap(),
        responseBody: String? = null,
        durationMs: Long? = null,
        error: Throwable? = null,
    ) {
        val core = NabbashRuntime.core ?: return
        core.recordNetwork(
            time = System.currentTimeMillis() - (durationMs ?: 0L),
            method = method,
            url = url,
            requestHeaders = requestHeaders.map { it.key to it.value },
            requestBody = requestBody,
            requestSize = requestBody?.length?.toLong(),
            status = statusCode,
            responseHeaders = responseHeaders.map { it.key to it.value },
            responseBody = responseBody,
            responseSize = responseBody?.length?.toLong(),
            durationMs = durationMs,
            error = error?.let { "${it.javaClass.name}: ${it.message.orEmpty()}" },
        )
    }

    /** Opens the console. Does nothing when Nabbash is not enabled. */
    @JvmStatic
    public fun open(context: Context) {
        NabbashRuntime.core?.openConsole(context)
    }
}
