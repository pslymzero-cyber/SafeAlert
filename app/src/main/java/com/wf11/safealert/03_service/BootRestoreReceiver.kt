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
    companion object {
        /** 재부팅·업데이트 복원 시작 표시: 단독 작업자 감시를 집어 들기 대기로 시작한다. */
        const val EXTRA_BOOT_RESTORE = "com.wf11.safealert.extra.BOOT_RESTORE"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val running = runCatching {
            ctx.getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE).getString("running_mode", null)
        }.getOrNull() ?: return
        if (!LoneWorkerUi.hasRunPermissions(ctx)) {
            Log.w("BootRestore", "run permissions missing, skip restore ($running)")
            return
        }
        val start = Intent(ctx, BleService::class.java).putExtra(EXTRA_BOOT_RESTORE, true)
        runCatching { ContextCompat.startForegroundService(ctx, start) }
            .onFailure { Log.w("BootRestore", "service start failed: ${it.javaClass.simpleName}") }
    }
}
