package com.iurikaflows

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

// Periodic background check: installs every update it finds
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            runCatching { Store.fetch(this).filter { it.hasUpdate && it.apkUrl != null }.forEach { Store.install(this, it) } }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(1) != null) return
            js.schedule(JobInfo.Builder(1, ComponentName(ctx, UpdateJob::class.java))
                .setPeriodic(6 * 60 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build())
        }
    }
}
