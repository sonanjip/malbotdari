package io.github.taehunkim.malbotdari.files

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.taehunkim.malbotdari.MalbotdariApp
import java.util.concurrent.TimeUnit

/**
 * Reads the files Nunsori indexed since last time and queues them on matching scheduled file tasks
 * ([io.github.taehunkim.malbotdari.chat.schedule.TaskManager.onIndexChanged]). Runs on Nunsori's hint, at app start
 * and once a day, so a missed hint only delays a task.
 */
class IndexChangedWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as MalbotdariApp).container
        return try {
            c.taskManager.onIndexChanged(); Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "index check failed", e); Result.success()
        }
    }

    companion object {
        private const val TAG = "Malbot.IndexCheck"

        /** One check at a time; a hint during a check queues one more after it. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "index-changed", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<IndexChangedWorker>().build(),
            )
        }

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "index-changed-daily", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<IndexChangedWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
