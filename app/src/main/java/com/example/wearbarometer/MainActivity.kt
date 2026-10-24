package com.example.wearbarometer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.example.wearbarometer.altitude.AltitudeReading
import com.example.wearbarometer.altitude.AltitudeRepository
import com.example.wearbarometer.altitude.Barometer
import com.example.wearbarometer.altitude.GnssFix
import com.example.wearbarometer.altitude.ReadingStatus
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var root: View
    private lateinit var altitudeValue: TextView
    private lateinit var altitudeDetail: TextView
    private lateinit var hint: TextView

    private lateinit var sensorManager: SensorManager
    private lateinit var repository: AltitudeRepository
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pressureJob: Job? = null

    private var smoothedPressure: Float? = null
    private var lastReading: AltitudeReading? = null
    private var lastRenderMs = 0L

    private var calibrating = false
    private var calibrationM = 0.0
    private val crown = CrownAcceleration()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            scope.launch { applyAnchors(repository.gnss.toFix(location)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        altitudeValue = findViewById(R.id.altitude_value)
        altitudeDetail = findViewById(R.id.altitude_detail)
        hint = findViewById(R.id.hint)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        repository = AltitudeRepository.get(this)

        altitudeValue.setOnLongClickListener {
            startCalibration()
            true
        }
        altitudeValue.setOnClickListener {
            if (calibrating) finishCalibration()
        }
        root.setOnGenericMotionListener { _, event -> onRotary(event) }

        if (!repository.gnss.hasPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        root.requestFocus()
        if (!Barometer.hasSensor(sensorManager)) {
            altitudeValue.text = getString(R.string.status_no_barometer)
            return
        }
        pressureJob = scope.launch {
            Barometer.pressure(sensorManager, LIVE_SAMPLE_PERIOD_US).collect { p ->
                val previous = smoothedPressure
                smoothedPressure = if (previous == null) p else previous + SMOOTHING * (p - previous)
                render(force = previous == null)
                if (previous == null) {
                    // render() has put the first sample into the pressure history the anchors are paired with.
                    launch { repository.gnss.lastFix()?.let { applyAnchors(it) } }
                }
            }
        }
        startLocationUpdates()
    }

    /** The repository rate-limits each anchor, so every fix can be offered. */
    private suspend fun applyAnchors(fix: GnssFix) {
        val gnssApplied = repository.applyFix(fix)
        val weatherApplied = repository.applyWeather(fix)
        if (gnssApplied || weatherApplied) render(force = true)
    }

    override fun onStop() {
        pressureJob?.cancel()
        pressureJob = null
        fusedClient.removeLocationUpdates(locationCallback)
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST_CODE) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            startLocationUpdates()
        }
        updateHint()
    }

    /** GNSS runs only while the screen is visible. */
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        updateHint()
        if (!repository.gnss.hasPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS).build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private suspend fun render(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRenderMs < RENDER_INTERVAL_MS) return
        lastRenderMs = now
        val reading = repository.reading(smoothedPressure)
        lastReading = reading
        if (calibrating) return
        val (main, detail) = ReadingText.lines(this, reading)
        altitudeValue.text = main
        altitudeDetail.text = detail
    }

    private fun startCalibration() {
        val altitude = lastReading?.altitudeM ?: return
        if (lastReading?.status != ReadingStatus.OK) return
        calibrating = true
        calibrationM = altitude.roundToInt().toDouble()
        altitudeValue.setTextColor(CALIBRATION_COLOR)
        altitudeValue.text = calibrationM.roundToInt().toString()
        updateHint()
    }

    private fun finishCalibration() {
        val pressure = smoothedPressure ?: return
        calibrating = false
        altitudeValue.setTextColor(NORMAL_COLOR)
        updateHint()
        scope.launch {
            repository.calibrate(calibrationM, pressure)
            render(force = true)
        }
    }

    private fun onRotary(event: MotionEvent): Boolean {
        if (!calibrating) return false
        if (event.action != MotionEvent.ACTION_SCROLL || !event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            return false
        }
        calibrationM += crown.meters(event.getAxisValue(MotionEvent.AXIS_SCROLL), event.eventTime)
        altitudeValue.text = calibrationM.roundToInt().toString()
        return true
    }

    private fun updateHint() {
        val text = when {
            calibrating -> getString(R.string.status_calibrate_hint)
            !repository.gnss.hasPermission() -> getString(R.string.status_no_location)
            else -> null
        }
        hint.text = text
        hint.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val LIVE_SAMPLE_PERIOD_US = 250_000
        private const val LOCATION_INTERVAL_MS = 1_000L
        private const val RENDER_INTERVAL_MS = 1_000L

        /** EMA at 4 Hz: ~2 s time constant, removes the 0.01 hPa noise without lag on stairs. */
        private const val SMOOTHING = 0.15f


        private const val NORMAL_COLOR = 0xFFFFFFFF.toInt()
        private const val CALIBRATION_COLOR = 0xFFFFB74D.toInt()
    }
}
