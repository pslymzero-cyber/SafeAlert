package com.wf11.safealert.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 재부팅·앱 업데이트 뒤 서비스가 돌던 상태였으면 저장 상태 복원 경로로 다시 띄운다 (v1.1.99).
 * action 없는 시작은 BleService 의 복원 경로이며 monitor.start 가 저장된 내 구조 요청도 되살린다.
 * 아무것도 하지 않는 경우: 실행 상태(running_mode)가 없을 때, 마지막 시작 뒤 사용자가 앱을 강제로 멈췄을 때
 * (Android 11+, 업데이트는 14+ 에서만 확인 — 이때는 실행 상태도 지운다. 저장된 내 구조 요청이 있으면 예외로 복원),
 * 서비스 시작 권한(ServiceStartGate)이 빠졌을 때.
 */
class BootRestoreReceiver : BroadcastReceiver() {
    companion object {
        /** 재부팅·업데이트 복원 시작 표시: 단독 작업자 감시를 집어 들기 대기로 시작한다. */
        const val EXTRA_BOOT_RESTORE = "com.wf11.safealert.extra.BOOT_RESTORE"

        /** 서비스가 마지막으로 시작된 시각(벽시계 ms, safealert_prefs). 이보다 오래된 종료 기록은 보지 않는다. */
        const val K_RUNNING_SINCE = "running_since"

        /**
         * 마지막 시작(sinceMs) 뒤에 사용자가 요청한 종료가 있으면 true. exits = (reason, timestamp).
         * 업데이트 복원은 업데이트 자체의 종료가 사용자 요청으로 남을 수 있어 Android 14+ 에서만 보고,
         * 업데이트 종료 기록은 세지 않는다.
         */
        fun userStopped(sdk: Int, packageReplaced: Boolean, exits: List<Pair<Int, Long>>, sinceMs: Long): Boolean {
            if (sdk < Build.VERSION_CODES.R) return false
            if (packageReplaced && sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
            return exits.any { (reason, at) -> at > sinceMs && reason == ApplicationExitInfo.REASON_USER_REQUESTED }
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = runCatching { ctx.getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE) }.getOrNull() ?: return
        val running = prefs.getString("running_mode", null) ?: return
        if (!LoneWorkerSosSync.hasStoredSos(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val exits = runCatching {
                ctx.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(ctx.packageName, 0, 0)
                    .map { it.reason to it.timestamp }
            }.getOrDefault(emptyList())
            val replaced = action == Intent.ACTION_MY_PACKAGE_REPLACED
            if (userStopped(Build.VERSION.SDK_INT, replaced, exits, prefs.getLong(K_RUNNING_SINCE, 0L))) {
                prefs.edit().remove("running_mode").commit()
                Log.w("BootRestore", "user stopped after last start, skip restore ($running)")
                return
            }
        }
        if (!ServiceStartGate.canStart(ctx)) {
            Log.w("BootRestore", "start permissions missing, skip restore ($running)")
            return
        }
        val start = Intent(ctx, BleService::class.java).putExtra(EXTRA_BOOT_RESTORE, true)
        runCatching { ContextCompat.startForegroundService(ctx, start) }
            .onFailure { Log.w("BootRestore", "service start failed: ${it.javaClass.simpleName}") }
    }
}
