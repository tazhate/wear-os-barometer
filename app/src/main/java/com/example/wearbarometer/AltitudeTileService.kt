package com.example.wearbarometer

import android.hardware.SensorManager
import android.util.Log
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.example.wearbarometer.altitude.AltitudeReading
import com.example.wearbarometer.altitude.AltitudeRepository
import com.example.wearbarometer.altitude.AnchorSource
import com.example.wearbarometer.altitude.Barometer
import com.example.wearbarometer.altitude.ReadingStatus
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalHorologistApi::class)
class AltitudeTileService : SuspendingTileService() {

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }

    override suspend fun resourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ResourceBuilders.Resources =
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(
                ICON_ID,
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder()
                            .setResourceId(R.drawable.ic_mountain)
                            .build(),
                    )
                    .build(),
            )
            .build()

    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val repository = AltitudeRepository.get(this)
        val reading = if (Barometer.hasSensor(sensorManager)) {
            repository.reading(Barometer.sample(sensorManager, SAMPLE_MS))
        } else {
            AltitudeReading(ReadingStatus.NO_BAROMETER, null, 0.0, AnchorSource.NONE, null)
        }
        Log.d(TAG, "tile reading: $reading")

        if (reading.pressureHpa != null && repository.anchorsDue()) {
            refreshInBackground(repository)
        }

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(layout(reading, requestParams.deviceConfiguration)),
            )
            .build()
    }

    /** GNSS and network can take tens of seconds: render now, re-render when anchors arrive. */
    private fun refreshInBackground(repository: AltitudeRepository) {
        if (!refreshing.compareAndSet(false, true)) return
        val appContext = applicationContext
        refreshScope.launch {
            try {
                if (repository.refreshAnchors(allowFreshFix = true)) {
                    TileService.getUpdater(appContext).requestUpdate(AltitudeTileService::class.java)
                }
            } catch (e: Exception) {
                Log.w(TAG, "anchor refresh failed", e)
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun layout(
        reading: AltitudeReading,
        deviceParams: DeviceParametersBuilders.DeviceParameters,
    ): LayoutElementBuilders.LayoutElement {
        val (main, detail) = ReadingText.lines(this, reading)
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.wrap())
            .setHeight(DimensionBuilders.wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(ICON_ID)
                    .setWidth(dp(28f))
                    .setHeight(dp(20f))
                    .build(),
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(12f)).build())
            .addContent(text(main, if (reading.status == ReadingStatus.OK) 56f else 20f, 0xFFFFFFFF, bold = true))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(text(detail, 14f, 0xFFB3C5D7, bold = false))
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(deviceParams.screenWidthDp.toFloat()))
            .setHeight(dp(deviceParams.screenHeightDp.toFloat()))
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(0xFF101922.toInt())).build(),
                    )
                    .setClickable(openAppClickable())
                    .build(),
            )
            .addContent(column)
            .build()
    }

    private fun text(value: String, sizeSp: Float, color: Long, bold: Boolean): LayoutElementBuilders.Text =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(sizeSp))
                    .setColor(argb(color.toInt()))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build(),
            )
            .build()

    /** Opening the app is the only way to grant location and to calibrate. */
    private fun openAppClickable(): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId("open_app")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()

    companion object {
        private const val TAG = "AltitudeTileService"
        private const val RESOURCES_VERSION = "1"
        private const val ICON_ID = "ic_mountain"
        private const val SAMPLE_MS = 1_200L
        private const val FRESHNESS_MS = 60_000L

        private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val refreshing = AtomicBoolean(false)
    }
}
