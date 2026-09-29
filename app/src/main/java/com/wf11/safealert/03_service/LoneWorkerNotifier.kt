package com.wf11.safealert.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wf11.safealert.ui.LoneWorkerActivity

/** 동료 항목의 표시 이름: 이름이 없으면 장비 ID 에서 접두어를 뗀 값. */
internal fun LoneWorkerLogic.Peer.displayName(): String =
    name.ifEmpty { bleId.removePrefix("SAFEALERT_DEVICE_").removePrefix("SAFEALERT_WALKER_") }

/**
 * 단독 작업자 알림 (v1.1.99). 모니터에서 분리해 알림 규칙만 모은다. 메인 스레드에서만 부른다.
 *
 * 큰 알림(확인 중·SOS·동료 구조 요청)은 새 경보가 생길 때마다 지웠다가 다시 올려 헤드업과 전체 화면 인텐트가
 * 다시 뜨게 하고(RR05), 사용자가 쓸어 내리면 deleteIntent 로 알려 다시 올린다(RR13).
 * SOS 알림의 [괜찮음]은 서비스에서 바로 끝내지 않고 확인 화면을 연다(U1) — 해제는 화면의 확인 대화상자를 거친다.
 */
class LoneWorkerNotifier(
    private val ctx: Context,
    private val deletePi: () -> PendingIntent
) {
    companion object {
        private const val TAG = "LoneWorkerNotifier"
        const val CHANNEL_ID = "safealert_sos"
        const val NOTIF_ID = 4242
        private const val RESOLVED_NOTIF_MS = 60_000L

        /** 알림의 [괜찮음]이 화면을 열면서 넘기는 표시: 화면은 바로 확인 대화상자를 띄운다. */
        const val EXTRA_CONFIRM_OK = "lw_confirm_ok"
        private const val REQ_ACK = 40
        private const val REQ_SILENCE = 42
        private const val REQ_OPEN = 43
        private const val REQ_CONFIRM = 44
    }

    private var lastKey: String? = null

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val ch = NotificationChannel(CHANNEL_ID, "구조 요청·근무 확인", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun servicePi(req: Int, action: String): PendingIntent =
        PendingIntent.getService(
            ctx, req, Intent(ctx, BleService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun activityIntent() = Intent(ctx, LoneWorkerActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun activityPi(req: Int, confirm: Boolean): PendingIntent =
        PendingIntent.getActivity(
            ctx, req, activityIntent().apply { if (confirm) putExtra(EXTRA_CONFIRM_OK, true) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 상태에 맞는 알림을 올리거나 지운다. 우선순위: SOS, 확인 중, 울리는 동료, 해제됨, 조용한 안내(notice).
     * alertAgain 이 참이고 큰 알림이면 키가 같아도 지웠다가 다시 올린다.
     */
    fun update(
        mode: LoneWorkerLogic.Mode,
        audible: List<LoneWorkerLogic.Peer>,
        resolved: List<LoneWorkerLogic.Peer>,
        notice: Pair<String, String>?,
        alertAgain: Boolean
    ) {
        val loud = mode != LoneWorkerLogic.Mode.WATCHING || audible.isNotEmpty()
        val nm = runCatching { ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }.getOrNull() ?: return
        val quiet = if (!loud && resolved.isEmpty()) notice else null
        if (!loud && resolved.isEmpty() && quiet == null) {
            if (lastKey != null) { nm.cancel(NOTIF_ID); lastKey = null }
            return
        }
        val key = "${quiet?.first}|$mode|${audible.joinToString(",") { it.bleId }}|${resolved.joinToString(",") { it.bleId }}"
        val again = loud && alertAgain
        if (key == lastKey && !again) return
        lastKey = key
        val (title, text, act) = when {
            mode == LoneWorkerLogic.Mode.SOS ->
                Triple("구조 요청 중", "[괜찮음]을 눌러야 해제됩니다", "괜찮음" to activityPi(REQ_CONFIRM, true))
            mode == LoneWorkerLogic.Mode.CHECKING ->
                Triple("근무 중이신가요?", "응답하지 않으면 같은 사업장에 구조 요청이 나갑니다", "근무 중" to servicePi(REQ_ACK, BleService.ACTION_LW_ACK))
            audible.isNotEmpty() ->
                Triple("구조 요청", audible.joinToString(", ") { it.displayName() }, "확인" to servicePi(REQ_SILENCE, BleService.ACTION_LW_SILENCE))
            quiet != null -> Triple(quiet.first, quiet.second, null)
            else -> Triple("해제됨", resolved.joinToString(", ") { it.displayName() }, null)
        }
        val open = activityPi(REQ_OPEN, false)
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
        if (loud) {
            b.setOngoing(true)
            b.setDeleteIntent(deletePi())
            if (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) b.setFullScreenIntent(open, true)
        } else {
            b.setSilent(true)
            if (quiet == null) b.setTimeoutAfter(RESOLVED_NOTIF_MS)
        }
        act?.let { b.addAction(0, it.first, it.second) }
        // 같은 알림 위에 덮어쓰면 헤드업·전체 화면 인텐트가 다시 뜨지 않으므로 먼저 지운다
        if (again) runCatching { nm.cancel(NOTIF_ID) }
        runCatching { nm.notify(NOTIF_ID, b.build()) }
            .onFailure { Log.w(TAG, "알림 게시 실패: ${it.message}") }
    }

    /** 알림을 지우고 기억도 지운다. */
    fun cancel() {
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) }
        lastKey = null
    }

    /** 사용자가 알림을 쓸어 내린 뒤: 다음 update 가 같은 내용이라도 다시 올리도록 기억만 지운다. */
    fun forget() {
        lastKey = null
    }

    /** 확인 화면을 직접 연다. 다른 앱 위에 표시 권한이 있을 때만 시도한다. */
    fun openScreen() {
        if (!runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)) return
        runCatching { ctx.startActivity(activityIntent()) }
            .onFailure { Log.w(TAG, "확인 화면 직접 실행 실패: ${it.message}") }
    }
}
