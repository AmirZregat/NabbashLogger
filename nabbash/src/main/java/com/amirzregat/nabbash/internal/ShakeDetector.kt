package com.amirzregat.nabbash.internal

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/** Fires [onShake] after three hard movements within about a second. */
internal class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var running = false
    private var hits = 0
    private var firstHit = 0L
    private var lastHit = 0L
    private var lastFired = 0L

    fun start() {
        if (running || manager == null || sensor == null) return
        running = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        running = false
        hits = 0
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH
        if (sqrt(x * x + y * y + z * z) < THRESHOLD_G) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastFired < COOLDOWN_MS || now - lastHit < MIN_GAP_MS) return
        if (hits == 0 || now - firstHit > WINDOW_MS) {
            hits = 0
            firstHit = now
        }
        lastHit = now
        if (++hits >= HITS) {
            hits = 0
            lastFired = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val THRESHOLD_G = 2.3f
        const val HITS = 3
        const val MIN_GAP_MS = 90L
        const val WINDOW_MS = 1_200L
        const val COOLDOWN_MS = 1_500L
    }
}
