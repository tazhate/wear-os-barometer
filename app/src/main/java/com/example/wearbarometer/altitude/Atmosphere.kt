package com.example.wearbarometer.altitude

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure atmosphere math, usable in JVM unit tests. With the standard sea level temperature it is the formula of
 * SensorManager.getAltitude. A real temperature rescales heights: cold air is denser, so the same pressure drop
 * means a smaller climb (-5 °C at sea level: ~7% less than the standard atmosphere says).
 */
object Atmosphere {
    const val STANDARD_SEA_LEVEL_HPA = 1013.25
    const val STANDARD_SEA_LEVEL_TEMP_K = 288.15
    const val LAPSE_RATE_K_PER_M = 0.0065
    private const val EXPONENT = 1.0 / 5.255

    /** Sane range for a sea level equivalent temperature; anything outside is a bad weather response. */
    private const val MIN_SEA_LEVEL_TEMP_K = 228.15
    private const val MAX_SEA_LEVEL_TEMP_K = 328.15

    /** Altitude in meters for [pressureHpa], given sea level pressure and sea level temperature. */
    fun altitude(seaLevelHpa: Double, pressureHpa: Double, seaLevelTempK: Double = STANDARD_SEA_LEVEL_TEMP_K): Double =
        seaLevelTempK / LAPSE_RATE_K_PER_M * (1.0 - (pressureHpa / seaLevelHpa).pow(EXPONENT))

    /** Altitude for sea level pressure 1013.25 hPa. */
    fun isaAltitude(pressureHpa: Double, seaLevelTempK: Double = STANDARD_SEA_LEVEL_TEMP_K): Double =
        altitude(STANDARD_SEA_LEVEL_HPA, pressureHpa, seaLevelTempK)

    /** Weather offset b, such that true altitude = isaAltitude(p) - b, implied by an anchor altitude. */
    fun offsetFromAltitude(
        pressureHpa: Double,
        anchorAltitudeM: Double,
        seaLevelTempK: Double = STANDARD_SEA_LEVEL_TEMP_K,
    ): Double = isaAltitude(pressureHpa, seaLevelTempK) - anchorAltitudeM

    /** Weather offset b implied by a known sea level pressure (QNH). */
    fun offsetFromQnh(
        pressureHpa: Double,
        qnhHpa: Double,
        seaLevelTempK: Double = STANDARD_SEA_LEVEL_TEMP_K,
    ): Double = isaAltitude(pressureHpa, seaLevelTempK) - altitude(qnhHpa, pressureHpa, seaLevelTempK)

    /**
     * Sea level equivalent temperature from air temperature [airTempC] measured at [elevationM],
     * assuming the standard lapse rate. Null when the result is implausible.
     */
    fun seaLevelTempK(airTempC: Double, elevationM: Double): Double? =
        (airTempC + 273.15 + LAPSE_RATE_K_PER_M * elevationM)
            .takeIf { it in MIN_SEA_LEVEL_TEMP_K..MAX_SEA_LEVEL_TEMP_K }

    fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}

object AltitudeFormat {
    const val PRECISE_SIGMA_M = 15.0
    const val ROUGH_SIGMA_M = 50.0

    /** "1234" when precise, "~1234" when rough, "~1234?" when the anchor is unreliable. */
    fun value(altitudeM: Double, sigmaM: Double): String {
        val number = altitudeM.roundToInt().toString()
        return when {
            sigmaM <= PRECISE_SIGMA_M -> number
            sigmaM <= ROUGH_SIGMA_M -> "~$number"
            else -> "~$number?"
        }
    }
}
