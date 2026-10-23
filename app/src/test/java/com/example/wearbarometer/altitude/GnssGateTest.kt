package com.example.wearbarometer.altitude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssGateTest {

    private fun fix(
        msl: Double? = 150.0,
        vAcc: Double? = 6.0,
        hAcc: Double? = 10.0,
        speed: Double? = 0.5,
        ageMs: Long = 10_000L,
        geoidKnown: Boolean = true,
    ) = GnssFix(msl, geoidKnown, vAcc, hAcc, speed, 55.75, 37.62, ageMs, 0L, 0L)

    @Test
    fun goodFixPasses() {
        assertTrue(GnssGate.acceptsAltitude(fix()))
        assertTrue(GnssGate.acceptsDem(fix()))
        assertTrue(GnssGate.acceptsWeather(fix()))
    }

    @Test
    fun staleFixIsRejectedForAltitudeButFineForWeather() {
        val old = fix(ageMs = 30 * 60_000L)
        assertFalse(GnssGate.acceptsAltitude(old))
        assertFalse(GnssGate.acceptsDem(old))
        assertTrue(GnssGate.acceptsWeather(old))
        assertFalse(GnssGate.acceptsWeather(fix(ageMs = 24 * 60 * 60_000L)))
    }

    @Test
    fun missingOrPoorVerticalAccuracyIsRejected() {
        assertFalse(GnssGate.acceptsAltitude(fix(vAcc = null)))
        assertFalse(GnssGate.acceptsAltitude(fix(vAcc = 40.0)))
        assertFalse(GnssGate.acceptsAltitude(fix(msl = null)))
    }

    @Test
    fun demNeedsGoodHorizontalFixAndLowSpeed() {
        assertFalse(GnssGate.acceptsDem(fix(hAcc = 200.0)))
        assertFalse(GnssGate.acceptsDem(fix(speed = 15.0)))
        assertFalse(GnssGate.acceptsDem(fix(hAcc = null)))
    }

    @Test
    fun ellipsoidHeightIsUsableButLessTrusted() {
        val noGeoid = fix(vAcc = 6.0, geoidKnown = false)
        assertTrue(GnssGate.acceptsAltitude(noGeoid))
        assertEquals(36.0, GnssGate.altitudeSigmaM(noGeoid)!!, 1e-9)
        assertEquals(6.0, GnssGate.altitudeSigmaM(fix(vAcc = 6.0))!!, 1e-9)
    }

    @Test
    fun futureTimestampIsRejected() {
        assertFalse(GnssGate.acceptsAltitude(fix(ageMs = -5_000L)))
    }
}
