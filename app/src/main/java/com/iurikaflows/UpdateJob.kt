package com.iurikaflows

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

// Periodic background check: installs every update it finds, if auto-update is on for that app
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            runCatching {
                // The store itself last: updating it kills this process
                Store.fetch(this).filter { it.hasUpdate && Store.autoUpdates(this, it) }.sortedBy { it.isSelf }
                    .forEach { Store.install(this, it) }
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        // force = settings changed (frequency, Wi-Fi only), replace the scheduled job
        fun schedule(ctx: Context, force: Boolean = false) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (!force && js.getPendingJob(1) != null) return
            val s = Store.settings(ctx)
            js.schedule(JobInfo.Builder(1, ComponentName(ctx, UpdateJob::class.java))
                .setPeriodic(s.getInt("freq", 6) * 60 * 60 * 1000L)
                .setRequiredNetworkType(if (s.getBoolean("wifi", true)) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build())
        }
    }
}
