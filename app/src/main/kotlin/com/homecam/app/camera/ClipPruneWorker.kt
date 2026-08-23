package com.homecam.app.camera

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.homecam.app.AppGraph
import com.homecam.app.data.ClipStore
import java.util.concurrent.TimeUnit

/**
 * Retention also runs after every clip write; this exists for the limits that can be
 * crossed without one — the age limit on a camera that has been quiet for a fortnight, and
 * free space eaten by something else on the phone entirely.
 */
class ClipPruneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val store = ClipStore(applicationContext, graph.database.clips())
        store.prune(graph.settings.current.retention)
        return Result.success()
    }

    companion object {
        private const val NAME = "homecam.prune"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ClipPruneWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
