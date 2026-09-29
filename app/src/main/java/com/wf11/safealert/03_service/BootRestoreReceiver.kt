package com.wf11.safealert.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.wf11.safealert.ui.LoneWorkerUi

/**
 * 재부팅·앱 업데이트 뒤 서비스가 돌던 상태였으면 권한을 확인하고 저장 상태 복원 경로로 다시 띄운다 (v1.1.99).
 * action 없는 시작은 BleService 의 복원 경로이며 monitor.start 가 저장된 내 구조 요청도 되살린다.
 * 사용자가 중지한 상태(running_mode 없음)나 실행 권한이 빠진 상태에서는 아무것도 하지 않는다.
 */
class BootRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val running = runCatching {
            ctx.getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE).getString("running_mode", null)
        }.getOrNull() ?: return
        if (!LoneWorkerUi.hasRunPermissions(ctx)) {
            Log.w("BootRestore", "run permissions missing, skip restore ($running)")
            return
        }
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, BleService::class.java)) }
            .onFailure { Log.w("BootRestore", "service start failed: ${it.javaClass.simpleName}") }
    }
}
