package com.example.wearbarometer.altitude

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull

object Barometer {
    fun hasSensor(sensorManager: SensorManager): Boolean =
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    /** Pressure in hPa. The listener is registered only while the flow is collected. */
    fun pressure(sensorManager: SensorManager, samplingPeriodUs: Int): Flow<Float> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(event.values[0])
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, samplingPeriodUs)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    /** Median pressure over [durationMs], or null when the sensor gave nothing. */
    suspend fun sample(sensorManager: SensorManager, durationMs: Long): Float? {
        val values = mutableListOf<Float>()
        withTimeoutOrNull(durationMs) {
            pressure(sensorManager, SAMPLE_PERIOD_US).collect { values += it }
        }
        return Atmosphere.median(values)
    }

    private const val SAMPLE_PERIOD_US = 100_000
}
