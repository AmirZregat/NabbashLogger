@file:Suppress("UNUSED_PARAMETER")

package com.amirzregat.nabbash

import android.content.Context

/**
 * Release-build stand-in for Nabbash. It has the same public API as the real library
 * and does nothing: no storage, no console, no components in the manifest.
 *
 * ```
 * debugImplementation("…:nabbash:<version>")
 * releaseImplementation("…:nabbash-no-op:<version>")
 * ```
 */
public object Nabbash {

    /** Always false in this artifact. */
    @JvmStatic
    public val isEnabled: Boolean
        get() = false

    /** Does nothing. The block is not run. */
    @JvmStatic
    public fun configure(block: NabbashConfig.() -> Unit) {
    }

    /** Does nothing. */
    @JvmStatic
    @JvmOverloads
    public fun log(
        message: String,
        label: String = "App",
        level: NabbashLevel = NabbashLevel.DEBUG,
        throwable: Throwable? = null,
    ) {
    }

    /** Does nothing. */
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
    }

    /** Does nothing. */
    @JvmStatic
    public fun open(context: Context) {
    }
}
