package com.example.wearbarometer.altitude

import android.content.Context
import android.content.SharedPreferences
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

    /** Guards the filter state and the "anchor already applied" markers together. */
    private val mutex = Mutex()

    /** Single flight for network anchors: a slow request must not be started again by the next caller. */
    private val networkMutex = Mutex()

    private val history = PressureHistory()
    val gnss = GnssAnchor(appContext)

    suspend fun reading(pressure: Float?, nowMs: Long = System.currentTimeMillis()): AltitudeReading =
        mutex.withLock {
            if (pressure != null) savePressure(pressure, nowMs)
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

    /** Raw pressure sample for pairing anchors with the same moment. Not smoothed: smoothing lags. */
    fun recordPressure(pressureHpa: Float) = history.add(SystemClock.elapsedRealtime(), pressureHpa)

    /** True when the tile should try to get new anchors in the background. */
    fun anchorsDue(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs >= prefs.getLong(KEY_REFRESH_NEXT, 0L)

    /** Called when the tile's anchor work starts, so a killed job is retried soon rather than in 30 minutes. */
    fun markRefreshStarted(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_REFRESH_NEXT, nowMs + REFRESH_RETRY_MS).apply()
    }

    /** Apply GNSS and weather anchors from [fix] for the tile. Returns true when the filter changed. */
    suspend fun refreshAnchors(fix: GnssFix?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (fix == null) {
            Log.i(TAG, "no location: only the saved offset is used")
            prefs.edit().putLong(KEY_REFRESH_NEXT, nowMs + REFRESH_RETRY_MS).apply()
            return false
        }

        val gnssApplied = applyFix(fix, nowMs)
        val weatherApplied = applyWeather(fix, nowMs)
        // No pressure to pair with: the job ran after the process went idle and Android held back the barometer.
        // Then GNSS, DEM and QNH may all have been skipped. Try again soon, when the tile is on screen.
        val noCurrentPressure = history.at(SystemClock.elapsedRealtime(), QNH_PRESSURE_MAX_AGE_MS) == null
        val missedPressure = noCurrentPressure || (GnssGate.acceptsAltitude(fix) && pressureFor(fix) == null)
        if (missedPressure) Log.i(TAG, "no matching pressure, retrying soon")
        val next = if (missedPressure) REFRESH_RETRY_MS else REFRESH_INTERVAL_MS
        prefs.edit().putLong(KEY_REFRESH_NEXT, System.currentTimeMillis() + next).apply()
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
            mutate("GNSS $altitude m σ=$sigma", nowMs) {
                updateAltitude(pressure.toDouble(), altitude, sigma, AnchorSource.GNSS, nowMs)
            }
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
        val seaLevelTempK = weather.temperatureC?.let { t ->
            Atmosphere.seaLevelTempK(t, weather.elevationM ?: Atmosphere.altitude(weather.qnhHpa, pressure))
        }
        val applied = mutex.withLock {
            prefs.edit()
                .putLong(KEY_WEATHER_FETCHED, weather.fetchedAtMs)
                .putFloat(KEY_WEATHER_LAT, weather.latitude.toFloat())
                .putFloat(KEY_WEATHER_LON, weather.longitude.toFloat())
                .putFloat(KEY_WEATHER_QNH, weather.qnhHpa.toFloat())
                .apply()
            mutate("QNH ${weather.qnhHpa} hPa σ=$sigma T0=$seaLevelTempK K", nowMs) {
                // Offline the last known temperature stays: it is still closer to the truth than the standard 15 °C.
                seaLevelTempK?.let { setSeaLevelTemperature(pressure, it) }
                updateQnh(pressure, weather.qnhHpa, sigma, nowMs)
            }
        }
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
            mutate("DEM $elevation m", nowMs) {
                updateAltitude(pressure.toDouble(), elevation, WeatherAnchor.DEM_SIGMA_M, AnchorSource.DEM, nowMs)
            }
        }
        return applied
    }

    /**
     * Forget the manual calibration: keep the current value but trust it no more than an unanchored barometer,
     * and let every anchor apply again right away.
     */
    suspend fun resetToSensors(nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
        mutate("reset to sensors", nowMs) {
            forget(nowMs)
            true
        }
        prefs.edit()
            .remove(KEY_GNSS_APPLIED)
            .remove(KEY_DEM_APPLIED)
            .remove(KEY_WEATHER_FETCHED)
            .remove(KEY_NETWORK_FAILED)
            .remove(KEY_REFRESH_NEXT)
            .apply()
    }

    suspend fun calibrate(knownAltitudeM: Double, pressure: Float, nowMs: Long = System.currentTimeMillis()) =
        mutex.withLock {
            mutate("manual $knownAltitudeM m at $pressure hPa", nowMs) {
                calibrate(pressure.toDouble(), knownAltitudeM, nowMs)
                true
            }
        }

    private fun pressureFor(fix: GnssFix): Float? = history.at(fix.elapsedRealtimeMs)

    /** Load, change and save the filter. Must be called under [mutex]. */
    private fun mutate(what: String, nowMs: Long, block: AltitudeFilter.() -> Boolean): Boolean {
        val filter = AltitudeFilter(loadState(nowMs))
        val before = filter.state
        val accepted = filter.block()
        saveState(filter.state)
        Log.i(
            TAG,
            "$what accepted=$accepted: offset %.1f±%.1f -> %.1f±%.1f, T0 %.1f K".format(
                before.offsetM, before.sigmaM, filter.state.offsetM, filter.state.sigmaM, filter.state.seaLevelTempK,
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
            seaLevelTempK = Double.fromBits(prefs.getLong(KEY_SEA_LEVEL_TEMP, Atmosphere.STANDARD_SEA_LEVEL_TEMP_K.toBits())),
        )
    }

    private fun saveState(state: FilterState) {
        prefs.edit()
            .putLong(KEY_OFFSET, state.offsetM.toBits())
            .putLong(KEY_VARIANCE, state.varianceM2.toBits())
            .putLong(KEY_TIME, state.timeMs)
            .putInt(KEY_SOURCE, state.source.ordinal)
            .putLong(KEY_ANCHOR_TIME, state.anchorTimeMs)
            .putLong(KEY_SEA_LEVEL_TEMP, state.seaLevelTempK.toBits())
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
        private const val KEY_SEA_LEVEL_TEMP = "sea_level_temp_bits"
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
