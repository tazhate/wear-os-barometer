package com.example.wearbarometer

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlin.math.pow
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import android.util.Log
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

class AltitudeTileService : TileService() {

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val locationManager by lazy {
        getSystemService(LOCATION_SERVICE) as LocationManager
    }

    private val sensorManager by lazy {
        getSystemService(SENSOR_SERVICE) as SensorManager
    }

    private val executor = Executors.newSingleThreadExecutor()

    private val prefs by lazy {
        getSharedPreferences("barometer_prefs", MODE_PRIVATE)
    }

    @Volatile
    private var lastPressure: Float? = null
    @Volatile
    private var lastPressureTime: Long = 0

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion("1")
                .addIdToImageMapping(
                    "ic_mountain",
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_mountain)
                                .build()
                        )
                        .build()
                )
                .build()
        )
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        Log.d(TAG, "onTileRequest() called")

        return Futures.submitAsync({
            Log.d(TAG, "Building tile in executor")
            Futures.immediateFuture(buildTile(requestParams))
        }, executor)
    }

    private fun buildTile(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val altitude = getAltitudeSync()
        val deviceParams = requestParams.deviceConfiguration

        return TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setFreshnessIntervalMillis(5_000) // Update every 5 seconds
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(createLayout(altitude, deviceParams))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun getAltitudeSync(): AltitudeData {
        Log.d(TAG, "getAltitudeSync() called")

        // Check permissions
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "No location permission")
            return AltitudeData("Нет доступа", false)
        }

        return try {
            // Try LocationManager first (direct access)
            Log.d(TAG, "Checking LocationManager...")
            val providers = locationManager.allProviders
            Log.d(TAG, "Available providers: $providers")

            var bestLocation: Location? = null
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                try {
                    val loc = locationManager.getLastKnownLocation(provider)
                    Log.d(TAG, "Provider $provider: $loc")
                    if (loc != null && (bestLocation == null || loc.time > bestLocation.time)) {
                        bestLocation = loc
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "No permission for $provider")
                }
            }

            if (bestLocation != null && bestLocation.hasAltitude()) {
                val altitudeMeters = bestLocation.altitude.toInt()
                val age = (System.currentTimeMillis() - bestLocation.time) / 1000
                Log.d(TAG, "LocationManager altitude: $altitudeMeters m (age: ${age}s)")
                return AltitudeData(altitudeMeters.toString(), true)
            }

            // Fallback to Fused Location Provider
            Log.d(TAG, "Trying FusedLocationProvider...")
            val lastLocation = Tasks.await(fusedLocationClient.lastLocation, 2, java.util.concurrent.TimeUnit.SECONDS)

            if (lastLocation != null && lastLocation.hasAltitude()) {
                val altitudeMeters = lastLocation.altitude.toInt()
                Log.d(TAG, "Fused location altitude: $altitudeMeters m")
                return AltitudeData(altitudeMeters.toString(), true)
            }

            val location = bestLocation ?: lastLocation
            Log.d(TAG, "Final location: $location")

            if (location != null) {
                if (location.hasAltitude()) {
                    val altitudeMeters = location.altitude.toInt()
                    Log.d(TAG, "Altitude: $altitudeMeters m")
                    AltitudeData(altitudeMeters.toString(), true)
                } else {
                    Log.w(TAG, "Location has no altitude data")
                    // Show lat/lon for debugging
                    val debugInfo = "Lat: ${location.latitude.toInt()}, Lon: ${location.longitude.toInt()}"
                    Log.d(TAG, debugInfo)
                    AltitudeData("GPS без выс", false)
                }
            } else {
                Log.w(TAG, "Location is null - using sensor fallback")
                // Try barometer/pressure sensor as fallback
                val pressure = getPressureAltitude()
                if (pressure != null) {
                    Log.d(TAG, "Using pressure altitude: $pressure m")
                    AltitudeData("$pressure", true)
                } else {
                    AltitudeData("⏳", false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting altitude", e)
            AltitudeData("Ошибка: ${e.message?.take(20) ?: "неизв"}", false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate() - starting pressure monitoring")
        // Restore last known values
        lastPressure = prefs.getFloat("last_pressure", 0f).takeIf { it > 0 }
        lastPressureTime = prefs.getLong("last_pressure_time", 0)
        Log.d(TAG, "Restored pressure: $lastPressure from ${System.currentTimeMillis() - lastPressureTime}ms ago")
        startPressureMonitoring()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() - stopping pressure monitoring")
        stopPressureMonitoring()
        super.onDestroy()
    }

    override fun onTileEnterEvent(requestParams: androidx.wear.tiles.EventBuilders.TileEnterEvent) {
        super.onTileEnterEvent(requestParams)
        Log.d(TAG, "onTileEnterEvent() - tile visible, ensuring sensor active")
        startPressureMonitoring()
    }

    override fun onTileLeaveEvent(requestParams: androidx.wear.tiles.EventBuilders.TileLeaveEvent) {
        super.onTileLeaveEvent(requestParams)
        Log.d(TAG, "onTileLeaveEvent() - tile hidden")
        // Keep sensor running for a bit in case user comes back
    }

    private fun startPressureMonitoring() {
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (pressureSensor != null) {
            // Unregister first to avoid double registration
            try {
                sensorManager.unregisterListener(pressureListener)
            } catch (e: Exception) {
                // Ignore
            }
            Log.d(TAG, "Starting pressure monitoring")
            sensorManager.registerListener(
                pressureListener,
                pressureSensor,
                SensorManager.SENSOR_DELAY_UI
            )
        } else {
            Log.w(TAG, "No pressure sensor available")
        }
    }

    private fun stopPressureMonitoring() {
        Log.d(TAG, "Stopping pressure monitoring")
        try {
            sensorManager.unregisterListener(pressureListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping sensor", e)
        }
    }

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_PRESSURE) {
                lastPressure = event.values[0]
                lastPressureTime = System.currentTimeMillis()
                // Save to SharedPreferences
                prefs.edit()
                    .putFloat("last_pressure", event.values[0])
                    .putLong("last_pressure_time", lastPressureTime)
                    .apply()
                Log.d(TAG, "Pressure updated: ${lastPressure} hPa")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            Log.d(TAG, "Pressure sensor accuracy changed: $accuracy")
        }
    }

    private fun getPressureAltitude(): Int? {
        val pressure = lastPressure
        val age = System.currentTimeMillis() - lastPressureTime

        if (pressure == null || pressure == 0f) {
            Log.w(TAG, "No pressure data available")
            return null
        }

        if (age > 300_000) { // 5 minutes - show old data but warn
            Log.w(TAG, "Pressure data old: ${age}ms, but showing it")
        }

        // Standard atmosphere formula: h = 44330 * (1 - (P/P0)^0.1903)
        // P0 = 1013.25 hPa (sea level standard pressure)
        val seaLevelPressure = 1013.25
        val altitude = 44330.0 * (1.0 - (pressure / seaLevelPressure).pow(0.1903))

        Log.d(TAG, "Calculated altitude from pressure $pressure hPa: ${altitude.toInt()} m")
        return altitude.toInt()
    }

    companion object {
        private const val TAG = "AltitudeTileService"
    }

    private fun createLayout(
        altitude: AltitudeData,
        deviceParams: DeviceParametersBuilders.DeviceParameters
    ): LayoutElementBuilders.LayoutElement {
        val context = this

        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(deviceParams.screenWidthDp.toFloat()))
            .setHeight(dp(deviceParams.screenHeightDp.toFloat()))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF101922.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(DimensionBuilders.expand())
                    .setHeight(DimensionBuilders.expand())
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        LayoutElementBuilders.Column.Builder()
                            .setWidth(DimensionBuilders.wrap())
                            .setHeight(DimensionBuilders.wrap())
                            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                            .addContent(
                                createMountainIcon()
                            )
                            .addContent(
                                LayoutElementBuilders.Spacer.Builder()
                                    .setHeight(dp(16f))
                                    .build()
                            )
                            .addContent(
                                if (altitude.hasData) {
                                    LayoutElementBuilders.Text.Builder()
                                        .setText(altitude.number)
                                        .setFontStyle(
                                            LayoutElementBuilders.FontStyle.Builder()
                                                .setSize(DimensionBuilders.sp(56f))
                                                .setColor(argb(0xFFFFFFFF.toInt()))
                                                .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                                .build()
                                        )
                                        .build()
                                } else {
                                    // Show animated arc as spinner
                                    LayoutElementBuilders.Arc.Builder()
                                        .setAnchorAngle(DimensionBuilders.degrees(0f))
                                        .addContent(
                                            LayoutElementBuilders.ArcLine.Builder()
                                                .setLength(DimensionBuilders.degrees(120f))
                                                .setThickness(dp(3f))
                                                .setColor(argb(0x80FFFFFF.toInt()))
                                                .build()
                                        )
                                        .setModifiers(
                                            ModifiersBuilders.Modifiers.Builder()
                                                .build()
                                        )
                                        .build()
                                }
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun createMountainIcon(): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Image.Builder()
            .setResourceId("ic_mountain")
            .setWidth(dp(28f))
            .setHeight(dp(20f))
            .build()
    }


    private data class AltitudeData(
        val text: String,
        val hasData: Boolean
    ) {
        val number: String
            get() = text.replace("~", "").replace("м", "").trim()

        val unit: String
            get() = "м"
    }
}
