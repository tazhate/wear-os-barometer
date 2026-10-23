package com.example.wearbarometer.altitude

import android.content.Context
import android.content.SharedPreferences
import android.hardware.SensorManager
import android.location.Location
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ReadingStatus { OK, NO_BAROMETER, NO_DATA }

data class AltitudeReading(
    val status: ReadingStatus,
    val altitudeM: Double?,
    val sigmaM: Double,
    val source: AnchorSource,
    val pressureHpa: Float?,
)

/**
 * Single place where the tile and the activity get altitude. Keeps the filter state in SharedPreferences
 * and applies each anchor once: anchor errors are correlated, counting one twice would fake confidence.
 */
class AltitudeRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Guards the filter state and the "anchor already applied" markers together. */
    private val mutex = Mutex()

    /** Single flight for network anchors: a slow request must not be started again by the next caller. */
    private val networkMutex = Mutex()

    private val history = PressureHistory()
    val gnss = GnssAnchor(appContext)

    suspend fun reading(pressure: Float?, nowMs: Long = System.currentTimeMillis()): AltitudeReading =
        mutex.withLock {
            if (pressure != null) {
                history.add(SystemClock.elapsedRealtime(), pressure)
                savePressure(pressure, nowMs)
            }
            val p = pressure ?: cachedPressure(nowMs)
            val filter = AltitudeFilter(loadState(nowMs)).apply { predict(nowMs) }
            if (p == null) {
                AltitudeReading(ReadingStatus.NO_DATA, null, filter.state.sigmaM, filter.state.source, null)
            } else {
                AltitudeReading(
                    ReadingStatus.OK, filter.altitude(p.toDouble()), filter.state.sigmaM, filter.state.source, p,
                )
            }
        }

    /** True when the tile should try to get new anchors in the background. */
    fun anchorsDue(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs >= prefs.getLong(KEY_REFRESH_NEXT, 0L)

    /**
     * Collect GNSS and weather anchors. [allowFreshFix] turns GNSS on when the last fix is too old.
     * Returns true when the filter changed.
     */
    suspend fun refreshAnchors(allowFreshFix: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        // If the process dies mid-refresh, retry soon instead of waiting for the full interval.
        prefs.edit().putLong(KEY_REFRESH_NEXT, nowMs + REFRESH_RETRY_MS).apply()

        var fix = gnss.lastFix()
        if (allowFreshFix && (fix == null || !GnssGate.acceptsAltitude(fix) || pressureFor(fix) == null)) {
            fix = gnss.currentFix(FRESH_FIX_TIMEOUT_MS) ?: fix
        }
        if (fix == null) {
            Log.i(TAG, "no location: only the saved offset is used")
            return false
        }
        if (fix.ageMs in 0..FRESH_SAMPLE_MAX_AGE_MS && pressureFor(fix) == null) {
            // A fresh fix after a long GNSS wait: sample pressure now, it is still the same moment.
            Barometer.sample(sensorManager, RESAMPLE_MS)?.let { history.add(SystemClock.elapsedRealtime(), it) }
        }

        val gnssApplied = applyFix(fix, nowMs)
        val weatherApplied = applyWeather(fix, nowMs)
        prefs.edit().putLong(KEY_REFRESH_NEXT, System.currentTimeMillis() + REFRESH_INTERVAL_MS).apply()
        return gnssApplied || weatherApplied
    }

    /** GNSS anchor. Fixes are correlated in time, so at most one per [GNSS_MIN_INTERVAL_MS]. */
    suspend fun applyFix(fix: GnssFix, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!GnssGate.acceptsAltitude(fix)) return false
        val altitude = fix.mslAltitudeM ?: return false
        val sigma = GnssGate.altitudeSigmaM(fix) ?: return false
        val pressure = pressureFor(fix) ?: return false
        return mutex.withLock {
            if (fix.fixTimeMs - prefs.getLong(KEY_GNSS_APPLIED, 0L) < GNSS_MIN_INTERVAL_MS) return@withLock false
            prefs.edit().putLong(KEY_GNSS_APPLIED, fix.fixTimeMs).apply()
            update(Atmosphere.offsetFromAltitude(pressure.toDouble(), altitude), sigma, AnchorSource.GNSS, nowMs)
        }
    }

    /**
     * QNH and terrain elevation from Open-Meteo. Offline it backs off for [NETWORK_RETRY_MS] and does nothing:
     * no network is a normal state in the mountains.
     */
    suspend fun applyWeather(fix: GnssFix, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!GnssGate.acceptsWeather(fix)) return false
        if (!networkMutex.tryLock()) return false
        try {
            if (nowMs - prefs.getLong(KEY_NETWORK_FAILED, 0L) < NETWORK_RETRY_MS) return false
            val qnhApplied = applyQnh(fix, nowMs)
            val demApplied = applyDem(fix, nowMs)
            return qnhApplied || demApplied
        } finally {
            networkMutex.unlock()
        }
    }

    private suspend fun applyQnh(fix: GnssFix, nowMs: Long): Boolean {
        val lastFetch = prefs.getLong(KEY_WEATHER_FETCHED, 0L)
        val moved = distanceM(fix, prefs.getFloat(KEY_WEATHER_LAT, 0f), prefs.getFloat(KEY_WEATHER_LON, 0f))
        if (nowMs - lastFetch <= WeatherAnchor.REFRESH_AFTER_MS && moved <= WEATHER_REFETCH_DISTANCE_M) return false
        // QNH describes the air now, so it pairs with the current pressure. Its offset barely depends on
        // pressure, so a sample up to a minute old is fine.
        val pressure = history.at(SystemClock.elapsedRealtime(), QNH_PRESSURE_MAX_AGE_MS)?.toDouble() ?: return false

        val weather = WeatherAnchor.fetchWeather(fix.latitude, fix.longitude, nowMs)
        if (weather == null) {
            prefs.edit().putLong(KEY_NETWORK_FAILED, nowMs).apply()
            return false
        }
        val sigma = WeatherAnchor.qnhSigmaM(Atmosphere.altitude(weather.qnhHpa, pressure))
        val applied = mutex.withLock {
            prefs.edit()
                .putLong(KEY_WEATHER_FETCHED, weather.fetchedAtMs)
                .putFloat(KEY_WEATHER_LAT, weather.latitude.toFloat())
                .putFloat(KEY_WEATHER_LON, weather.longitude.toFloat())
                .putFloat(KEY_WEATHER_QNH, weather.qnhHpa.toFloat())
                .apply()
            update(Atmosphere.offsetFromQnh(pressure, weather.qnhHpa), sigma, AnchorSource.QNH, nowMs)
        }
        Log.i(TAG, "QNH ${weather.qnhHpa} hPa applied=$applied")
        return applied
    }

    private suspend fun applyDem(fix: GnssFix, nowMs: Long): Boolean {
        if (!GnssGate.acceptsDem(fix)) return false
        if (nowMs - prefs.getLong(KEY_DEM_APPLIED, 0L) <= DEM_MIN_INTERVAL_MS) return false
        // Terrain elevation is where the fix was, so it pairs with the pressure of that moment.
        val pressure = pressureFor(fix) ?: return false

        val elevation = WeatherAnchor.fetchElevation(fix.latitude, fix.longitude)
        if (elevation == null) {
            prefs.edit().putLong(KEY_NETWORK_FAILED, nowMs).apply()
            return false
        }
        val applied = mutex.withLock {
            prefs.edit().putLong(KEY_DEM_APPLIED, nowMs).apply()
            update(
                Atmosphere.offsetFromAltitude(pressure.toDouble(), elevation),
                WeatherAnchor.DEM_SIGMA_M,
                AnchorSource.DEM,
                nowMs,
            )
        }
        Log.i(TAG, "DEM $elevation m applied=$applied")
        return applied
    }

    suspend fun calibrate(knownAltitudeM: Double, pressure: Float, nowMs: Long = System.currentTimeMillis()) =
        mutex.withLock {
            val filter = AltitudeFilter(loadState(nowMs))
            filter.calibrate(pressure.toDouble(), knownAltitudeM, nowMs)
            saveState(filter.state)
            Log.i(TAG, "manual calibration to $knownAltitudeM m at $pressure hPa")
        }

    private fun pressureFor(fix: GnssFix): Float? = history.at(fix.elapsedRealtimeMs)

    /** Must be called under [mutex]. */
    private fun update(offsetM: Double, sigmaM: Double, source: AnchorSource, nowMs: Long): Boolean {
        val filter = AltitudeFilter(loadState(nowMs))
        val before = filter.state
        val accepted = filter.update(offsetM, sigmaM, source, nowMs)
        saveState(filter.state)
        Log.i(
            TAG,
            "$source offset=%.1f σ=%.1f accepted=$accepted: %.1f±%.1f -> %.1f±%.1f".format(
                offsetM, sigmaM, before.offsetM, before.sigmaM, filter.state.offsetM, filter.state.sigmaM,
            ),
        )
        return accepted
    }

    private fun loadState(nowMs: Long): FilterState {
        if (!prefs.contains(KEY_OFFSET)) return FilterState.initial(nowMs)
        return FilterState(
            offsetM = Double.fromBits(prefs.getLong(KEY_OFFSET, 0L)),
            varianceM2 = Double.fromBits(prefs.getLong(KEY_VARIANCE, AltitudeFilter.MAX_VARIANCE_M2.toBits())),
            timeMs = prefs.getLong(KEY_TIME, nowMs),
            source = AnchorSource.entries.getOrElse(prefs.getInt(KEY_SOURCE, 0)) { AnchorSource.NONE },
            anchorTimeMs = prefs.getLong(KEY_ANCHOR_TIME, 0L),
        )
    }

    private fun saveState(state: FilterState) {
        prefs.edit()
            .putLong(KEY_OFFSET, state.offsetM.toBits())
            .putLong(KEY_VARIANCE, state.varianceM2.toBits())
            .putLong(KEY_TIME, state.timeMs)
            .putInt(KEY_SOURCE, state.source.ordinal)
            .putLong(KEY_ANCHOR_TIME, state.anchorTimeMs)
            .apply()
    }

    /** The live screen reads at 4 Hz; disk needs the value only for a later tile without a fresh sample. */
    private fun savePressure(pressure: Float, nowMs: Long) {
        if (nowMs - prefs.getLong(KEY_PRESSURE_TIME, 0L) < PRESSURE_SAVE_INTERVAL_MS) return
        prefs.edit().putFloat(KEY_PRESSURE, pressure).putLong(KEY_PRESSURE_TIME, nowMs).apply()
    }

    /** Last pressure, only if recent: an old value would show a stale altitude as current. */
    private fun cachedPressure(nowMs: Long): Float? {
        val time = prefs.getLong(KEY_PRESSURE_TIME, 0L)
        if (nowMs - time > CACHED_PRESSURE_MAX_AGE_MS) return null
        return prefs.getFloat(KEY_PRESSURE, 0f).takeIf { it > 0f }
    }

    private fun distanceM(fix: GnssFix, lat: Float, lon: Float): Float {
        if (lat == 0f && lon == 0f) return Float.MAX_VALUE
        val result = FloatArray(1)
        Location.distanceBetween(fix.latitude, fix.longitude, lat.toDouble(), lon.toDouble(), result)
        return result[0]
    }

    companion object {
        private const val TAG = "AltitudeRepo"
        private const val PREFS = "altitude_state"

        private const val KEY_OFFSET = "offset_bits"
        private const val KEY_VARIANCE = "variance_bits"
        private const val KEY_TIME = "time"
        private const val KEY_SOURCE = "source"
        private const val KEY_ANCHOR_TIME = "anchor_time"
        private const val KEY_PRESSURE = "pressure"
        private const val KEY_PRESSURE_TIME = "pressure_time"
        private const val KEY_REFRESH_NEXT = "refresh_next"
        private const val KEY_GNSS_APPLIED = "gnss_applied_fix_time"
        private const val KEY_DEM_APPLIED = "dem_applied"
        private const val KEY_NETWORK_FAILED = "network_failed"
        private const val KEY_WEATHER_FETCHED = "weather_fetched"
        private const val KEY_WEATHER_LAT = "weather_lat"
        private const val KEY_WEATHER_LON = "weather_lon"
        private const val KEY_WEATHER_QNH = "weather_qnh"

        private const val REFRESH_INTERVAL_MS = 30 * 60_000L
        private const val REFRESH_RETRY_MS = 5 * 60_000L
        private const val NETWORK_RETRY_MS = 2 * 60_000L
        private const val FRESH_FIX_TIMEOUT_MS = 20_000L
        private const val FRESH_SAMPLE_MAX_AGE_MS = 10_000L
        private const val RESAMPLE_MS = 1_000L
        private const val QNH_PRESSURE_MAX_AGE_MS = 60_000L
        private const val GNSS_MIN_INTERVAL_MS = 2 * 60_000L
        private const val DEM_MIN_INTERVAL_MS = 30 * 60_000L
        private const val WEATHER_REFETCH_DISTANCE_M = 10_000f
        private const val CACHED_PRESSURE_MAX_AGE_MS = 10 * 60_000L
        private const val PRESSURE_SAVE_INTERVAL_MS = 15_000L

        @Volatile
        private var instance: AltitudeRepository? = null

        fun get(context: Context): AltitudeRepository =
            instance ?: synchronized(this) {
                instance ?: AltitudeRepository(context).also { instance = it }
            }
    }
}
