package com.wf11.safealert.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Scheduled job that drains the SOS mail queue outside monitoring.
 * Even after lone-worker monitoring stops ("중지", restart, reboot), pending mail requests are sent when a network is available.
 * Scheduled when the queue has items and cancelled when it is empty (SosMail.onQueue). Uses
 * the same queue instance as the monitoring tick, so nothing is sent twice.
 * Failures are swallowed — mail is a secondary channel, so alerts and judgments never wait on this job.
 */
class SosMailJob : JobService() {
    companion object {
        // The only JobScheduler job in the app
        private const val JOB_ID = 0x5A0501

        fun sync(ctx: Context, pending: Boolean) {
            runCatching {
                val js = ctx.getSystemService(JobScheduler::class.java) ?: return
                if (!pending) {
                    js.cancel(JOB_ID)
                } else if (js.getPendingJob(JOB_ID) == null) {
                    js.schedule(
                        JobInfo.Builder(JOB_ID, ComponentName(ctx, SosMailJob::class.java))
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setPersisted(true)
                            .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_LINEAR)
                            .build()
                    )
                }
            }
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        if (!LoneWorkerSosSync.mailEnabled) return false
        // If items remain, leave it to the system retry (growing by 10 s each time). Together with the in-app retry wait (10 s to 5 min),
        // the actual resend interval can stretch to around 10 minutes, and the system may defer it further
        LoneWorkerSosSync.mail(this).drain { left -> jobFinished(params, left) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
