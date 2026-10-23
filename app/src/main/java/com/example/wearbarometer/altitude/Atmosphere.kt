package com.example.wearbarometer.altitude

import kotlin.math.pow
import kotlin.math.roundToInt

/** Pure atmosphere math. Same formula as SensorManager.getAltitude, but usable in JVM unit tests. */
object Atmosphere {
    const val STANDARD_SEA_LEVEL_HPA = 1013.25

    /** Altitude in meters for pressure [pressureHpa], given sea level pressure [seaLevelHpa]. */
    fun altitude(seaLevelHpa: Double, pressureHpa: Double): Double =
        44330.0 * (1.0 - (pressureHpa / seaLevelHpa).pow(1.0 / 5.255))

    /** Altitude in the standard atmosphere (sea level 1013.25 hPa). */
    fun isaAltitude(pressureHpa: Double): Double = altitude(STANDARD_SEA_LEVEL_HPA, pressureHpa)

    /** Weather offset b, such that true altitude = isaAltitude(p) - b, implied by an anchor altitude. */
    fun offsetFromAltitude(pressureHpa: Double, anchorAltitudeM: Double): Double =
        isaAltitude(pressureHpa) - anchorAltitudeM

    /** Weather offset b implied by a known sea level pressure (QNH). */
    fun offsetFromQnh(pressureHpa: Double, qnhHpa: Double): Double =
        isaAltitude(pressureHpa) - altitude(qnhHpa, pressureHpa)

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
