package com.example.wearbarometer

import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.wearbarometer.altitude.AltitudeRepository
import com.example.wearbarometer.altitude.Barometer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Anchors for the tile when the app screen is closed. With background location it turns GNSS on for one fix;
 * without it only the last known location is used. The barometer is recorded while waiting, so the fix is
 * paired with the pressure of its own moment.
 */
class AnchorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = AltitudeRepository.get(applicationContext)
        val sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gnss = repository.gnss

        var samples = 0
        val fix = coroutineScope {
            val recorder = launch {
                Barometer.pressure(sensorManager, RECORD_PERIOD_US).collect {
                    repository.recordPressure(it)
                    samples++
                }
            }
            try {
                if (gnss.hasBackgroundPermission()) gnss.currentFix(FIX_TIMEOUT_MS) ?: gnss.lastFix() else gnss.lastFix()
            } finally {
                recorder.cancel()
            }
        }
        Log.i(TAG, "fix=$fix pressure samples=$samples background=${gnss.hasBackgroundPermission()}")

        if (repository.refreshAnchors(fix)) {
            TileService.getUpdater(applicationContext).requestUpdate(AltitudeTileService::class.java)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "AnchorWorker"
        private const val WORK_NAME = "tile_anchors"
        private const val FIX_TIMEOUT_MS = 30_000L
        private const val RECORD_PERIOD_US = 500_000

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnchorWorker>().apply {
                // Below API 31 expedited work needs a foreground notification; a short delay is fine there.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }.build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
