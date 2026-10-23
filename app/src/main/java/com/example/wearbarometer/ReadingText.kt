package com.example.wearbarometer

import android.content.Context
import com.example.wearbarometer.altitude.AltitudeFormat
import com.example.wearbarometer.altitude.AltitudeReading
import com.example.wearbarometer.altitude.AnchorSource
import com.example.wearbarometer.altitude.ReadingStatus
import kotlin.math.roundToInt

/** Texts shared by the tile and the activity, so both show the same thing. */
object ReadingText {
    /** Main line and detail line. */
    fun lines(context: Context, reading: AltitudeReading): Pair<String, String> {
        val altitude = reading.altitudeM
        return when {
            reading.status == ReadingStatus.NO_BAROMETER ->
                context.getString(R.string.status_no_barometer) to ""
            altitude == null ->
                context.getString(R.string.status_no_data) to context.getString(R.string.status_open_app)
            else ->
                AltitudeFormat.value(altitude, reading.sigmaM) to detail(context, reading)
        }
    }

    private fun detail(context: Context, reading: AltitudeReading): String =
        context.getString(R.string.detail_format, reading.sigmaM.roundToInt(), sourceLabel(context, reading.source))

    fun sourceLabel(context: Context, source: AnchorSource): String = context.getString(
        when (source) {
            AnchorSource.NONE -> R.string.source_none
            AnchorSource.MANUAL -> R.string.source_manual
            AnchorSource.GNSS -> R.string.source_gnss
            AnchorSource.QNH -> R.string.source_qnh
            AnchorSource.DEM -> R.string.source_dem
        },
    )
}
