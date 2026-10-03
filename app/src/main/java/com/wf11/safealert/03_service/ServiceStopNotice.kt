package com.wf11.safealert.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.wf11.safealert.ui.MainActivity

/**
 * Notification shown when monitoring fails to start. The ongoing notification
 * channel (LOW) is silent, so a separate audible notice channel is used.
 * Tapping opens the main screen — if permissions are missing, the screen shows the
 * permission warning; otherwise returning to the screen restarts monitoring.
 */
object ServiceStopNotice {
    const val NOTIF_ID = 1002
    const val CHANNEL_ID = "safealert_notice"

    /** permission = stopped because start permissions are missing (the text points to permission settings). */
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
