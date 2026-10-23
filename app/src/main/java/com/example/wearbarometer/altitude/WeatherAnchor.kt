package com.example.wearbarometer.altitude

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class Weather(
    val qnhHpa: Double,
    val temperatureC: Double?,
    /** Elevation of the model point the temperature belongs to. */
    val elevationM: Double?,
    val latitude: Double,
    val longitude: Double,
    val fetchedAtMs: Long,
)

/** Open-Meteo, no API key. Fails fast when there is no network: offline is a normal state in the mountains. */
object WeatherAnchor {
    private const val TAG = "WeatherAnchor"
    private const val TIMEOUT_MS = 5_000

    const val MAX_AGE_MS = 60 * 60_000L
    const val REFRESH_AFTER_MS = 30 * 60_000L

    /** Sea level pressure from a weather model is ~1 hPa off, worse in the mountains where the reduction is long. */
    fun qnhSigmaM(altitudeM: Double): Double = 8.0 + 0.01 * kotlin.math.abs(altitudeM)

    /** A DEM cell is ~90 m wide and the user may stand on a floor above ground. */
    const val DEM_SIGMA_M = 25.0

    /** Coordinates are rounded to 0.01° (~1 km): enough for pressure, less exact for the server. */
    suspend fun fetchWeather(latitude: Double, longitude: Double, nowMs: Long): Weather? {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=pressure_msl,temperature_2m",
            latitude, longitude,
        )
        val json = get(url) ?: return null
        return try {
            val current = json.getJSONObject("current")
            Weather(
                qnhHpa = current.getDouble("pressure_msl"),
                temperatureC = current.optDouble("temperature_2m").takeUnless { it.isNaN() },
                elevationM = json.optDouble("elevation").takeUnless { it.isNaN() },
                latitude = latitude,
                longitude = longitude,
                fetchedAtMs = nowMs,
            )
        } catch (e: Exception) {
            Log.w(TAG, "bad weather response", e)
            null
        }
    }

    /** Terrain elevation at the exact point. Needs precise coordinates, so it is only called with a good fix. */
    suspend fun fetchElevation(latitude: Double, longitude: Double): Double? {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/elevation?latitude=%.4f&longitude=%.4f",
            latitude, longitude,
        )
        val json = get(url) ?: return null
        return try {
            json.getJSONArray("elevation").getDouble(0).takeUnless { it.isNaN() }
        } catch (e: Exception) {
            Log.w(TAG, "bad elevation response", e)
            null
        }
    }

    private suspend fun get(url: String): JSONObject? = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                return@withContext null
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.i(TAG, "offline or unreachable: ${e.javaClass.simpleName}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
