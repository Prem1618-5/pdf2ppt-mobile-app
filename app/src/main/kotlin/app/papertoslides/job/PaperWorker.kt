package app.papertoslides.job

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.papertoslides.MainActivity
import app.papertoslides.R
import app.papertoslides.Services
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One job at a time: a unique, expedited WorkManager job running as a foreground "dataSync" task with a progress
 * notification and a Cancel action. WorkManager restarts it after process death; the caches make it resume.
 */
class PaperWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Preparing…", 0, 0)

    override suspend fun doWork(): Result {
        val s = Services.get(applicationContext)
        val spec = Json.decodeFromString<JobSpec>(inputData.getString(KEY_SPEC) ?: return Result.failure())
        val stateFile = File(applicationContext.filesDir, "job.json")
        JobBus.set(JobProgress(spec.projectId, spec.name))
        runCatching { setForeground(foreground("Starting…", 0, 0)) } // denied notification permission: the job still runs
        var lastNote = 0L
        val runner = JobRunner(applicationContext, s) { f ->
            JobBus.update(f)
            val p = JobBus.state.value ?: return@JobRunner
            val now = System.currentTimeMillis()
            if (now - lastNote > 800) { // the notification is cheap but not free
                lastNote = now
                runCatching { stateFile.writeText(Json.encodeToString(JobProgress.serializer(), p)) }
                notify(p.stage, p.done, p.total)
            }
        }
        val governor = Governor(applicationContext) { msg -> JobBus.update { copy(message = msg) } }
        return try {
            val out = runner.run(spec, governor)
            JobBus.update { copy(stage = "Done", finished = true, warnings = out.warnings, done = total, apiRequests = out.project.stats.apiRequests) }
            done("Your slides are ready", "${out.project.items.size} questions · ${spec.name}")
            Result.success()
        } catch (e: CancellationException) {
            JobBus.update { copy(stage = "Cancelled", finished = true, cancelled = true) }
            throw e
        } catch (e: Throwable) {
            val msg = (e as? app.papertoslides.platform.SourceException)?.message
                ?: (e as? java.io.IOException)?.message ?: "Something went wrong (${e.javaClass.simpleName}). Your files are safe; please try again."
            JobBus.update { copy(stage = "Stopped", finished = true, error = msg) }
            done("Could not make the slides", msg)
            Result.failure()
        } finally {
            governor.close()
            stateFile.delete()
        }
    }

    private fun channel() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Making slides", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun openApp() = PendingIntent.getActivity(applicationContext, 0,
        Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    private fun build(title: String, done: Int, total: Int) = NotificationCompat.Builder(applicationContext, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Making slides")
        .setContentText(if (total > 0) "$title · $done / $total" else title)
        .setProgress(total, done, total == 0)
        .setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
        .build()

    private fun foreground(title: String, done: Int, total: Int): ForegroundInfo {
        channel()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTE_ID, build(title, done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTE_ID, build(title, done, total))
    }

    private fun notify(title: String, done: Int, total: Int) {
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(NOTE_ID, build(title, done, total)) }
    }

    private fun done(title: String, text: String) {
        channel()
        val n = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(openApp()).build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(DONE_ID, n) }
    }

    companion object {
        const val UNIQUE = "paper-job"
        private const val KEY_SPEC = "spec"
        private const val CHANNEL = "jobs"
        private const val NOTE_ID = 41
        private const val DONE_ID = 42

        fun start(ctx: Context, spec: JobSpec) {
            val req = OneTimeWorkRequestBuilder<PaperWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_SPEC to Json.encodeToString(JobSpec.serializer(), spec)))
                .addTag(spec.projectId)
                .build()
            JobBus.set(JobProgress(spec.projectId, spec.name))
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE) }

        /** After process death the UI re-reads the last persisted progress. */
        fun restore(ctx: Context) {
            if (JobBus.state.value != null) return
            val f = File(ctx.filesDir, "job.json")
            runCatching { JobBus.set(Json.decodeFromString(JobProgress.serializer(), f.readText())) }
        }
    }
}
