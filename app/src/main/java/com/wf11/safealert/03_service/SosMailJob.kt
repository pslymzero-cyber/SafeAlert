package com.wf11.safealert.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * 구조 요청 메일 대기열을 감시 밖에서 비우는 예약 작업 (v1.2.2).
 * [중지]·재시작·재부팅으로 단독 작업자 감시가 멈춘 뒤에도, 대기 중인 메일 요청을 네트워크가 있을 때 보낸다.
 * 대기열이 차면 예약하고 비면 취소한다(SosMail.onQueue). 감시 tick 과 같은 대기열 인스턴스를 써서 두 번 보내지 않는다.
 * 실패는 삼킨다 — 메일은 보조 통로라 경보·판정은 이 작업을 기다리지 않는다.
 */
class SosMailJob : JobService() {
    companion object {
        // 앱에서 쓰는 유일한 JobScheduler 작업
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
                            .build()
                    )
                }
            }
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        if (!LoneWorkerSosSync.mailEnabled) return false
        // 남은 항목이 있으면 시스템 재시도(30초부터 늘어남)에 맡긴다
        LoneWorkerSosSync.mail(this).drain { left -> jobFinished(params, left) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
