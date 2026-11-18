package com.amirzregat.nabbashlogger

import android.app.Application
import com.amirzregat.nabbash.Nabbash
import kotlin.time.Duration.Companion.days

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Optional. Nabbash already runs with safe defaults in debug builds;
        // in release builds this call resolves to the no-op artifact.
        Nabbash.configure {
            sessionRetention = 7.days
            maxStorageMb = 50
            redactHeaders += "X-Session-Id"
            redactBodyKeys += "nationalId"
        }
    }
}
