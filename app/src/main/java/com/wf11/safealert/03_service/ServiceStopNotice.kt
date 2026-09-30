package com.wf11.safealert.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.wf11.safealert.ui.MainActivity

/**
 * 감시 시작 실패 안내 알림 (v1.1.99). 상시 알림 채널(LOW)은 소리가 없어 소리 나는 안내 채널을 따로 쓴다.
 * 탭하면 메인 화면이 열린다 — 권한 부족이면 화면이 권한 경고를 띄우고, 아니면 화면 복귀가 다시 시작한다.
 */
object ServiceStopNotice {
    const val NOTIF_ID = 1002
    const val CHANNEL_ID = "safealert_notice"

    /** permission = 시작 권한이 없어 멈춘 경우(문구가 권한 설정 안내). */
    fun show(ctx: Context, permission: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "감시 중지 안내", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = Intent(ctx, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching {
            val pi = PendingIntent.getActivity(ctx, 5, open, PendingIntent.FLAG_IMMUTABLE)
            nm.notify(NOTIF_ID, NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(if (permission) "권한이 없어 감시가 멈췄습니다" else "감시가 중지됐습니다")
                .setContentText(if (permission) "눌러서 권한 설정" else "눌러서 다시 시작")
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }

    fun cancel(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
    }
}
