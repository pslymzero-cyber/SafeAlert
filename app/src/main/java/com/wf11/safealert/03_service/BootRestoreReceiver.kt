package com.wf11.safealert.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * 재부팅·앱 업데이트 뒤 서비스가 돌던 상태였으면 저장 상태 복원 경로로 다시 띄운다 (v1.1.99).
 * action 없는 시작은 BleService 의 복원 경로이며 monitor.start 가 저장된 내 구조 요청도 되살린다.
 * 아무것도 하지 않는 경우: 실행 상태(running_mode)가 없을 때, 마지막 시작 뒤 사용자가 앱을 강제로 멈췄을 때
 * (Android 11+ — 이때는 실행 상태도 지운다. 저장된 내 구조 요청이 있으면 예외로 복원),
 * 서비스 시작 권한(ServiceStartGate)이 빠졌을 때.
 */
class BootRestoreReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootRestore"
        private const val UPDATE_EXIT_SLACK_MS = 60_000L

        /** 백그라운드 복원 시작 표시 — Android 11 위치 접근 판정용(BleService). */
        const val EXTRA_BOOT_RESTORE = "com.wf11.safealert.extra.BOOT_RESTORE"

        /**
         * 서비스가 마지막으로 시작된 시각(벽시계 ms, safealert_prefs). 사용자 중지 판정 전용이며
         * 화면 표시용 running_since 와 별개다. 이보다 오래된 종료 기록은 보지 않는다.
         */
        const val K_STARTED_AT = "service_started_at"

        /**
         * 마지막 시작(sinceMs) 뒤에 사용자가 요청한 종료가 있으면 true. exits = (reason, timestamp).
         * 시작 시각을 모르면(sinceMs <= 0) 판정하지 않고 복원한다. Android 11~13 은 업데이트 종료도
         * 사용자 요청으로 남을 수 있어 앱 갱신 시각(updatedAtMs) 앞뒤 60초 안 기록은 세지 않는다.
         * 이 여유는 갱신이 마지막 시작보다 뒤일 때만 둔다(시작 뒤 갱신이 없었으면 모든 기록을 센다).
         */
        fun userStopped(sdk: Int, exits: List<Pair<Int, Long>>, sinceMs: Long, updatedAtMs: Long): Boolean {
            if (sdk < Build.VERSION_CODES.R || sinceMs <= 0L) return false
            val checkUpdate = sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && sinceMs < updatedAtMs
            return exits.any { (reason, at) ->
                reason == ApplicationExitInfo.REASON_USER_REQUESTED && at > sinceMs &&
                    !(checkUpdate && abs(at - updatedAtMs) <= UPDATE_EXIT_SLACK_MS)
            }
        }

        /** 사용자 중지 판정 기준 시각 — 판정 전용 키가 없으면(옛 버전에서 시작) 표시용 시작 시각으로 대신한다. */
        fun startedAt(newKey: Long, runningSince: Long): Long = if (newKey > 0L) newKey else runningSince
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = runCatching { ctx.getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE) }.getOrNull() ?: return
        val running = prefs.getString("running_mode", null) ?: return
        val since = startedAt(prefs.getLong(K_STARTED_AT, 0L), prefs.getLong("running_since", 0L))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && since > 0L && !LoneWorkerSosSync.hasStoredSos(ctx)) {
            val exits = runCatching {
                ctx.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(ctx.packageName, 0, 0)
                    .map { it.reason to it.timestamp }
            }.getOrDefault(emptyList())
            val updatedAt = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }
                .getOrDefault(0L)
            if (userStopped(Build.VERSION.SDK_INT, exits, since, updatedAt)) {
                LoneWorkerResume.clearOnUserStop(prefs.edit()).commit()
                Log.w(TAG, "user stopped after last start, skip restore ($running)")
                return
            }
        }
        if (!ServiceStartGate.canStart(ctx)) {
            Log.w(TAG, "start permissions missing, skip restore ($running)")
            return
        }
        val start = Intent(ctx, BleService::class.java).putExtra(EXTRA_BOOT_RESTORE, true)
        runCatching { ContextCompat.startForegroundService(ctx, start) }
            .onFailure { Log.w(TAG, "service start failed: ${it.javaClass.simpleName}") }
    }
}
