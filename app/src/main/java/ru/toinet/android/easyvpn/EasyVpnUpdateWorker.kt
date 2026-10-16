package ru.toinet.android.easyvpn

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class EasyVpnUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting periodic daily EasyVPN config update & test...")
        return try {
            val workingNodes = EasyVpnUpdater.updateAndTestConfigs(applicationContext)
            Log.i(TAG, "Daily EasyVPN update completed. Found ${workingNodes.size} verified nodes.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during daily EasyVPN update", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EasyVpnUpdateWorker"
        const val WORK_NAME = "easyvpn_daily_config_update"

        @JvmStatic
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<EasyVpnUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.i(TAG, "Daily EasyVPN update work scheduled")
        }

        @JvmStatic
        fun runOnceImmediately(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<EasyVpnUpdateWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.i(TAG, "Enqueued one-time EasyVPN update task")
        }
    }
}
