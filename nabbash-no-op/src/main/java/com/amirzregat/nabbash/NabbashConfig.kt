package com.amirzregat.nabbash

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Same options as the real library so the same `Nabbash.configure { }` block compiles
 * in release builds. Nothing reads them here.
 */
public class NabbashConfig internal constructor() {
    public var sessionRetention: Duration = 7.days
    public var maxStorageMb: Int = 50
    public var maxBodyBytes: Int = 250_000
    public var shakeToOpen: Boolean = true
    public var launcherShortcut: Boolean = true
    public val redactHeaders: MutableSet<String> = LinkedHashSet()
    public val redactBodyKeys: MutableSet<String> = LinkedHashSet()
}
