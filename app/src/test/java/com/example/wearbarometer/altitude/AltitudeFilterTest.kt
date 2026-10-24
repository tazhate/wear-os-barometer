package com.example.wearbarometer.altitude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AltitudeFilterTest {

    private val hour = 3_600_000L
    private val start = 1_700_000_000_000L

    @Test
    fun isaMatchesStandardAtmosphere() {
        assertEquals(0.0, Atmosphere.isaAltitude(1013.25), 1e-9)
        // ISA: 898.76 hPa at 1000 m.
        assertEquals(1000.0, Atmosphere.isaAltitude(898.76), 1.0)
    }

    @Test
    fun qnhEqualToPressureMeansSeaLevel() {
        val pressure = 1007.0
        val filter = AltitudeFilter(FilterState.initial(start))
        filter.update(Atmosphere.offsetFromQnh(pressure, 1007.0), 1.0, AnchorSource.QNH, start)
        assertEquals(0.0, filter.altitude(pressure), 1.0)
    }

    @Test
    fun oneHectopascalIsAboutEightMetersNearSeaLevel() {
        val offset = Atmosphere.offsetFromQnh(1000.0, 1012.0) - Atmosphere.offsetFromQnh(1000.0, 1013.0)
        assertEquals(8.3, offset, 0.3)
    }

    @Test
    fun convergesToAnchorAndShrinksSigma() {
        val pressure = 990.0
        val trueAltitude = 250.0
        val filter = AltitudeFilter(FilterState.initial(start))
        repeat(5) { i ->
            val z = Atmosphere.offsetFromAltitude(pressure, trueAltitude + if (i % 2 == 0) 4.0 else -4.0)
            assertTrue(filter.update(z, 8.0, AnchorSource.GNSS, start + i * 60_000L))
        }
        assertEquals(trueAltitude, filter.altitude(pressure), 3.0)
        assertTrue(filter.state.sigmaM < 5.0)
        assertTrue(filter.state.sigmaM >= 2.0)
        assertEquals(AnchorSource.GNSS, filter.state.source)
    }

    @Test
    fun sigmaGrowsWithoutAnchorsAndIsCapped() {
        val filter = AltitudeFilter(FilterState.initial(start))
        filter.calibrate(1000.0, 120.0, start)
        assertEquals(1.0, filter.state.sigmaM, 0.01)

        filter.predict(start + 4 * hour)
        assertEquals(Math.sqrt(1.0 + 4 * AltitudeFilter.DRIFT_M2_PER_HOUR), filter.state.sigmaM, 0.01)

        filter.predict(start + 10_000 * hour)
        assertEquals(100.0, filter.state.sigmaM, 0.01)
    }

    @Test
    fun predictIgnoresClockGoingBack() {
        val filter = AltitudeFilter(FilterState.initial(start))
        filter.calibrate(1000.0, 120.0, start)
        filter.predict(start - hour)
        assertEquals(1.0, filter.state.sigmaM, 0.01)
    }

    @Test
    fun outlierIsRejectedButManualAlwaysWins() {
        val pressure = 950.0
        val filter = AltitudeFilter(FilterState.initial(start))
        filter.calibrate(pressure, 500.0, start)

        val bad = Atmosphere.offsetFromAltitude(pressure, 900.0)
        assertFalse(filter.update(bad, 10.0, AnchorSource.GNSS, start + 60_000L))
        assertEquals(500.0, filter.altitude(pressure), 0.5)

        assertTrue(filter.update(bad, 1.0, AnchorSource.MANUAL, start + 120_000L))
        assertEquals(AnchorSource.MANUAL, filter.state.source)
    }

    @Test
    fun weakAnchorDoesNotTakeOverTheLabel() {
        val pressure = 1007.0
        val filter = AltitudeFilter(FilterState.initial(start))
        filter.update(Atmosphere.offsetFromAltitude(pressure, 176.0), 2.0, AnchorSource.GNSS, start)
        assertTrue(filter.update(Atmosphere.offsetFromAltitude(pressure, 173.0), 25.0, AnchorSource.DEM, start + 60_000L))
        assertEquals(AnchorSource.GNSS, filter.state.source)
        assertEquals(start, filter.state.anchorTimeMs)
    }

    @Test
    fun barometerTracksClimbAfterCalibration() {
        val filter = AltitudeFilter(FilterState.initial(start))
        filter.calibrate(1000.0, 100.0, start)
        // Three floors up is ~0.36 hPa lower.
        assertEquals(103.0, filter.altitude(999.64), 0.3)
    }

    @Test
    fun seaLevelTemperatureFromStationReading() {
        assertEquals(268.15, Atmosphere.seaLevelTempK(-5.0, 0.0)!!, 1e-9)
        // -10 °C at 2000 m is +3 °C at sea level by the standard lapse rate.
        assertEquals(276.15, Atmosphere.seaLevelTempK(-10.0, 2000.0)!!, 1e-9)
        assertNull(Atmosphere.seaLevelTempK(-90.0, 0.0))
    }

    @Test
    fun coldAirShrinksTheClimb() {
        val filter = AltitudeFilter(FilterState.initial(start))
        filter.calibrate(1013.25, 0.0, start)
        filter.setSeaLevelTemperature(1013.25, 268.15)
        // ISA says 1000 m for 898.76 hPa; at -5 °C the same pressure drop is ~7% less.
        assertEquals(1000.0 * 268.15 / 288.15, filter.altitude(898.76), 1.5)
    }

    @Test
    fun temperatureChangeKeepsTheCurrentAltitude() {
        val filter = AltitudeFilter(FilterState.initial(start))
        filter.calibrate(850.0, 1500.0, start)
        filter.setSeaLevelTemperature(850.0, 260.0)
        assertEquals(1500.0, filter.altitude(850.0), 1e-6)
        assertEquals(260.0, filter.state.seaLevelTempK, 1e-9)
    }

    @Test
    fun qnhAnchorUsesTheSameTemperature() {
        val pressure = 900.0
        val filter = AltitudeFilter(FilterState.initial(start))
        filter.setSeaLevelTemperature(pressure, 268.15)
        filter.updateQnh(pressure, 1020.0, 1.0, start)
        assertEquals(Atmosphere.altitude(1020.0, pressure, 268.15), filter.altitude(pressure), 1.0)
    }

    @Test
    fun forgetKeepsValueAndLetsGnssOverrideWrongCalibration() {
        val pressure = 1000.0
        val filter = AltitudeFilter(FilterState.initial(start))
        filter.calibrate(pressure, 500.0, start)
        val gnss = Atmosphere.offsetFromAltitude(pressure, 110.0)
        assertFalse(filter.update(gnss, 5.0, AnchorSource.GNSS, start + 60_000L))

        filter.forget(start + 120_000L)
        assertEquals(500.0, filter.altitude(pressure), 1e-6)
        assertEquals(AnchorSource.NONE, filter.state.source)

        assertTrue(filter.update(gnss, 5.0, AnchorSource.GNSS, start + 180_000L))
        assertEquals(110.0, filter.altitude(pressure), 1.0)
    }

    @Test
    fun median() {
        assertNull(Atmosphere.median(emptyList()))
        assertEquals(2f, Atmosphere.median(listOf(3f, 1f, 2f)))
        assertEquals(2.5f, Atmosphere.median(listOf(4f, 1f, 2f, 3f)))
    }

    @Test
    fun formatShowsUncertainty() {
        assertEquals("1234", AltitudeFormat.value(1234.4, 5.0))
        assertEquals("~1234", AltitudeFormat.value(1234.4, 30.0))
        assertEquals("~1234?", AltitudeFormat.value(1234.4, 80.0))
    }
}
