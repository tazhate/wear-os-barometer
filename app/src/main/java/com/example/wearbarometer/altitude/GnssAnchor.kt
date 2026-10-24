package com.example.wearbarometer.altitude

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** A location fix reduced to what the altitude filter needs. Altitude is above mean sea level. */
data class GnssFix(
    val mslAltitudeM: Double?,
    /** False when [mslAltitudeM] is the ellipsoid height: no geoid model on this device. */
    val geoidKnown: Boolean,
    val verticalAccuracyM: Double?,
    val horizontalAccuracyM: Double?,
    val speedMps: Double?,
    val latitude: Double,
    val longitude: Double,
    val ageMs: Long,
    val fixTimeMs: Long,
    /** SystemClock.elapsedRealtime() of the fix, to pair it with pressure from the same moment. */
    val elapsedRealtimeMs: Long,
)

object GnssGate {
    const val MAX_AGE_MS = 2 * 60_000L
    const val MAX_VERTICAL_ACCURACY_M = 25.0

    /** Coordinates are good enough to ask for weather at that point. */
    const val MAX_WEATHER_AGE_MS = 3 * 60 * 60_000L

    const val DEM_MAX_HORIZONTAL_ACCURACY_M = 50.0
    const val DEM_MAX_SPEED_MPS = 2.0

    /** Without a geoid model the ellipsoid height is used with this extra error (undulation is ±100 m worldwide). */
    const val NO_GEOID_EXTRA_SIGMA_M = 30.0

    fun acceptsAltitude(fix: GnssFix): Boolean {
        val vAcc = fix.verticalAccuracyM ?: return false
        return fix.mslAltitudeM != null && fix.ageMs in 0..MAX_AGE_MS && vAcc <= MAX_VERTICAL_ACCURACY_M
    }

    /** Measurement error for the filter: the receiver's estimate plus the unknown geoid when there is none. */
    fun altitudeSigmaM(fix: GnssFix): Double? {
        val vAcc = fix.verticalAccuracyM ?: return null
        return if (fix.geoidKnown) vAcc else vAcc + NO_GEOID_EXTRA_SIGMA_M
    }

    fun acceptsDem(fix: GnssFix): Boolean {
        val hAcc = fix.horizontalAccuracyM ?: return false
        return fix.ageMs in 0..MAX_AGE_MS &&
            hAcc <= DEM_MAX_HORIZONTAL_ACCURACY_M &&
            (fix.speedMps ?: 0.0) <= DEM_MAX_SPEED_MPS
    }

    fun acceptsWeather(fix: GnssFix): Boolean = fix.ageMs in 0..MAX_WEATHER_AGE_MS
}

class GnssAnchor(context: Context) {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** "Allow all the time": the tile's worker may turn GNSS on with the app closed. */
    fun hasBackgroundPermission(): Boolean =
        hasPermission() && (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            )

    /** A freshly measured fix, never a cached one: a cached fix has no pressure sample of its moment. */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(timeoutMs: Long): GnssFix? {
        if (!hasPermission()) return null
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(timeoutMs)
            .setMaxUpdateAgeMillis(0)
            .build()
        val cancellation = CancellationTokenSource()
        return try {
            client.getCurrentLocation(request, cancellation.token).await(cancellation)?.let { toFix(it) }
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentLocation failed", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun lastFix(): GnssFix? {
        if (!hasPermission()) return null
        return try {
            client.lastLocation.await()?.let { toFix(it) }
        } catch (e: Exception) {
            Log.w(TAG, "lastLocation failed", e)
            null
        }
    }

    /** Converts a Location to a fix with altitude above mean sea level. Does disk I/O for the geoid. */
    suspend fun toFix(location: Location): GnssFix = withContext(Dispatchers.IO) {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        var msl: Double? = null
        var geoidKnown = false
        var vAcc: Double? = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else null

        if (location.hasAltitude()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (!location.hasMslAltitude()) {
                    try {
                        AltitudeConverter().addMslAltitudeToLocation(appContext, location)
                    } catch (e: Exception) {
                        Log.w(TAG, "geoid conversion failed", e)
                    }
                }
                if (location.hasMslAltitude()) {
                    msl = location.mslAltitudeMeters
                    geoidKnown = true
                    if (location.hasMslAltitudeAccuracy()) {
                        vAcc = location.mslAltitudeAccuracyMeters.toDouble()
                    }
                }
            }
            if (msl == null) {
                // Ellipsoid height: GnssGate.altitudeSigmaM adds the unknown geoid undulation.
                msl = location.altitude
            }
        }

        GnssFix(
            mslAltitudeM = msl,
            geoidKnown = geoidKnown,
            verticalAccuracyM = vAcc,
            horizontalAccuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            latitude = location.latitude,
            longitude = location.longitude,
            ageMs = ageMs,
            fixTimeMs = location.time,
            elapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000,
        )
    }

    companion object {
        private const val TAG = "GnssAnchor"
    }
}
