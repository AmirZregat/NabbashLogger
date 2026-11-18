package com.amirzregat.nabbash.internal

import android.content.Context
import android.content.SharedPreferences

/**
 * Choices made on the in-app settings screen. A null override means
 * "use the value from Nabbash.configure".
 */
internal class Settings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var retentionMs: Long?
        get() = if (prefs.contains(KEY_RETENTION)) prefs.getLong(KEY_RETENTION, 0L).takeIf { it > 0 } else null
        set(value) = put { if (value == null) remove(KEY_RETENTION) else putLong(KEY_RETENTION, value) }

    var maxStorageMb: Int?
        get() = if (prefs.contains(KEY_STORAGE)) prefs.getInt(KEY_STORAGE, 0).takeIf { it > 0 } else null
        set(value) = put { if (value == null) remove(KEY_STORAGE) else putInt(KEY_STORAGE, value) }

    var shakeToOpen: Boolean?
        get() = if (prefs.contains(KEY_SHAKE)) prefs.getBoolean(KEY_SHAKE, true) else null
        set(value) = put { if (value == null) remove(KEY_SHAKE) else putBoolean(KEY_SHAKE, value) }

    var redactHeaders: Boolean
        get() = prefs.getBoolean(KEY_REDACT_HEADERS, true)
        set(value) = put { putBoolean(KEY_REDACT_HEADERS, value) }

    var redactFields: Boolean
        get() = prefs.getBoolean(KEY_REDACT_FIELDS, true)
        set(value) = put { putBoolean(KEY_REDACT_FIELDS, value) }

    private inline fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }

    internal companion object {
        const val FILE = "nabbash_settings"
        private const val KEY_RETENTION = "retention_ms"
        private const val KEY_STORAGE = "max_storage_mb"
        private const val KEY_SHAKE = "shake"
        private const val KEY_REDACT_HEADERS = "redact_headers"
        private const val KEY_REDACT_FIELDS = "redact_fields"
    }
}
