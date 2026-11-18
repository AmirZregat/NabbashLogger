package com.amirzregat.nabbash

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Options for [Nabbash.configure]. Every option has a safe default, so calling
 * `configure` is optional.
 *
 * Retention, storage limit, shake and the redaction switches can also be changed
 * from the in-app settings screen. A value chosen there wins over the value set here.
 */
public class NabbashConfig internal constructor() {

    /** How long a finished session is kept before it is deleted. Default: 7 days. */
    public var sessionRetention: Duration = 7.days

    /** Upper bound for everything Nabbash stores. The oldest sessions are dropped first. Default: 50 MB. */
    public var maxStorageMb: Int = 50

    /** Request and response bodies are cut at this size before they are stored. Default: 250 kB. */
    public var maxBodyBytes: Int = 250_000

    /** Open the console by shaking the device. Default: on. */
    public var shakeToOpen: Boolean = true

    /** Add an "Open Nabbash" entry to the app's launcher long-press menu. Default: on. */
    public var launcherShortcut: Boolean = true

    /**
     * Extra header names whose values are masked before they are stored, on top of the
     * built-in list (Authorization, Cookie, Set-Cookie, X-Api-Key and similar). Case-insensitive.
     */
    public val redactHeaders: MutableSet<String> = LinkedHashSet()

    /**
     * Extra field names whose values are masked in JSON, form and XML bodies and in URL
     * query strings, on top of the built-in list (password, token, pin, otp and similar).
     * Matching ignores case, `_` and `-`.
     */
    public val redactBodyKeys: MutableSet<String> = LinkedHashSet()
}
