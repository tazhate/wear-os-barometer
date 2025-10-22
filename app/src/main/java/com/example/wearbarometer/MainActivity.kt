package com.example.wearbarometer

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.pow

class MainActivity : Activity() {

    private lateinit var altitudeValue: TextView
    private lateinit var altitudeUnit: TextView
    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastPressure: Float? = null

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_PRESSURE) {
                lastPressure = event.values[0]
                updateAltitude()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        altitudeValue = findViewById(R.id.altitude_value)
        altitudeUnit = findViewById(R.id.altitude_unit)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        // Request location permission if needed
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        startPressureMonitoring()
        updateAltitude()
    }

    private fun startPressureMonitoring() {
        pressureSensor?.let {
            sensorManager.registerListener(pressureListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopPressureMonitoring() {
        sensorManager.unregisterListener(pressureListener)
    }

    private fun updateAltitude() {
        val pressure = lastPressure
        if (pressure != null) {
            val seaLevelPressure = 1013.25
            val altitude = 44330.0 * (1.0 - (pressure / seaLevelPressure).pow(0.1903))

            handler.post {
                altitudeValue.text = altitude.toInt().toString()
                altitudeUnit.text = ""
            }
        } else {
            handler.post {
                altitudeValue.text = "•"
                altitudeUnit.text = ""
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startPressureMonitoring()
    }

    override fun onPause() {
        super.onPause()
        stopPressureMonitoring()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
}
