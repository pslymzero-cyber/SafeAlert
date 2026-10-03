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
import com.wf11.safealert.model.PitType
import com.wf11.safealert.ui.LoneWorkerActivity
import com.wf11.safealert.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Time display format for peer entries (thread-safe, created once). */
private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Display name of a peer entry: without a name, the equipment ID minus its prefix. */
internal fun LoneWorkerPeers.Peer.displayName(): String =
    name.ifEmpty { bleId.removePrefix("SAFEALERT_DEVICE_").removePrefix("SAFEALERT_WALKER_") }

/**
 * Rescue-request role label: "보행자" for a walker; if the name is an equipment ID, the equipment's English name (PitType.label);
 * otherwise "지게차", "EPJ" or "알 수 없음". Same rule as the mail (Code.gs roleName).
 */
internal fun sosRoleLabel(role: String, name: String): String =
    if (role == "WALKER") "보행자"
    else PitType.parse(name)?.first?.label ?: when (role) {
        "FORKLIFT" -> "지게차"
        "EPJ" -> "EPJ"
        else -> "알 수 없음"
    }

/**
 * One peer entry line: name · role · time · location · status. Role is omitted
 * when empty (peer seen only over BLE, before a server record).
 */
internal fun LoneWorkerPeers.Peer.line(nowMs: Long): String {
    val roleText = role.ifEmpty { null }?.let { sosRoleLabel(it, displayName()) }
    val wall = if (fromServer) createdAtMs else System.currentTimeMillis() - (nowMs - firstSeenMs)
    return listOfNotNull(
        displayName(), roleText, HHMM.format(Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault())),
        beacon.ifEmpty { null }?.let { if (fromServer) "마지막 위치: $it" else "${it} 근처" }, if (active) "구조 요청" else "해제됨"
    ).joinToString(" · ")
}

/**
 * Lone-worker notifications. Split out of the monitor to keep the notification rules together. Call on the main thread only.
 *
 * Major alerts (checking, SOS, peer rescue request) are cancelled and re-posted on every new alert so the heads-up and the
 * full-screen intent show again; when the user swipes one away, deleteIntent reports it and it is re-posted.
 * The SOS notification's "괜찮아요" does not end SOS in the service; it opens the check screen — clearing goes through the
 * screen's confirmation dialog.
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

        /**
         * How-to-close hint for the mounted no-motion window — the single text shared
         * by the screen body, the back-button hint and the notification.
         */
        const val TURN_CLOSE_HINT = "회전하거나 ${LoneWorkerLogic.STRONG_RUN_MS / 1000}초 넘게 흔들거나 [괜찮아요]를 누르면 닫혀요"

        /** Flag passed when the notification's "괜찮아요" opens the screen: the screen shows the confirmation dialog right away. */
        const val EXTRA_CONFIRM_OK = "lw_confirm_ok"
        /** Entry ids passed by the notification's "확인" action: only these entries are muted. */
        const val EXTRA_PEER_IDS = "lw_peer_ids"
        /** Episode IDs (bleId#ep) in the same order as EXTRA_PEER_IDS. */
        const val EXTRA_PEER_EPS = "lw_peer_eps"

        /** Mute targets of the "확인" intent (entry id -> episode ID). If the two lists differ in length, nothing is muted. */
        fun peerTargets(intent: Intent?): Map<String, String> {
            val ids = intent?.getStringArrayListExtra(EXTRA_PEER_IDS).orEmpty()
            val eps = intent?.getStringArrayListExtra(EXTRA_PEER_EPS).orEmpty()
            return if (ids.size == eps.size) ids.zip(eps).toMap() else emptyMap()
        }
        private const val REQ_ACK = 40
        private const val REQ_SILENCE = 42
        private const val REQ_OPEN = 43
        private const val REQ_CONFIRM = 44
        private const val REQ_MAIN = 45
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

    private fun servicePi(req: Int, action: String, peers: List<LoneWorkerPeers.Peer>? = null): PendingIntent =
        PendingIntent.getService(
            ctx, req,
            Intent(ctx, BleService::class.java).apply {
                this.action = action
                peers?.let {
                    putStringArrayListExtra(EXTRA_PEER_IDS, ArrayList(it.map { p -> p.id }))
                    putStringArrayListExtra(EXTRA_PEER_EPS, ArrayList(it.map { p -> p.epId }))
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun activityIntent() = Intent(ctx, LoneWorkerActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun activityPi(req: Int, confirm: Boolean): PendingIntent =
        PendingIntent.getActivity(
            ctx, req, activityIntent().apply { if (confirm) putExtra(EXTRA_CONFIRM_OK, true) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Tapping the quiet notice notification opens the main screen (no reason to open the check screen). */
    private fun mainPi(): PendingIntent =
        PendingIntent.getActivity(
            ctx, REQ_MAIN,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Posts or cancels the notification matching the state. Priority: SOS, checking, ringing peer, resolved, quiet notice (notice).
     * If alertAgain is true and it is a major alert, cancels and re-posts even with the same key.
     * With closesByTurn, the checking hint is TURN_CLOSE_HINT instead of steps.
     */
    fun update(
        mode: LoneWorkerLogic.Mode,
        closesByTurn: Boolean,
        audible: List<LoneWorkerPeers.Peer>,
        resolved: List<LoneWorkerPeers.Peer>,
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
        val key = "${quiet?.first}|${quiet?.second}|$mode|$closesByTurn|${audible.joinToString(",") { it.id }}|${resolved.joinToString(",") { it.id }}"
        val again = loud && alertAgain
        if (key == lastKey && !again) return
        lastKey = key
        val (title, text, act) = when {
            mode == LoneWorkerLogic.Mode.SOS ->
                Triple("구조 요청 중", "[괜찮아요]를 눌러야 해제돼요", "괜찮아요" to activityPi(REQ_CONFIRM, true))
            mode == LoneWorkerLogic.Mode.CHECKING ->
                Triple("괜찮으세요?", "응답이 없으면 같은 사업장에 구조 요청이 나가요. " + (if (closesByTurn) TURN_CLOSE_HINT else "걸으면 자동으로 닫혀요"), "괜찮아요" to servicePi(REQ_ACK, BleService.ACTION_LW_ACK))
            audible.isNotEmpty() ->
                Triple("구조 요청", audible.joinToString(", ") { it.displayName() }, "확인" to servicePi(REQ_SILENCE, BleService.ACTION_LW_SILENCE, audible))
            quiet != null -> Triple(quiet.first, quiet.second, null)
            else -> Triple("해제됨", resolved.joinToString(", ") { it.displayName() }, null)
        }
        val open = activityPi(REQ_OPEN, false)
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(if (quiet != null) mainPi() else open)
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
            // The quiet notice is re-posted even after a swipe: deleteIntent -> onNotificationDismissed -> forget -> render
            if (quiet == null) b.setTimeoutAfter(RESOLVED_NOTIF_MS) else b.setDeleteIntent(deletePi())
        }
        act?.let { b.addAction(0, it.first, it.second) }
        // Overwriting the same notification won't re-show heads-up / full-screen intent, so cancel it first
        if (again) runCatching { nm.cancel(NOTIF_ID) }
        runCatching { nm.notify(NOTIF_ID, b.build()) }
            .onFailure { Log.w(TAG, "알림 게시 실패: ${it.message}") }
    }

    /** Cancels the notification and forgets it. */
    fun cancel() {
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) }
        lastKey = null
    }

    /** After the user swipes the notification away: only forgets it, so the next update re-posts even identical content. */
    fun forget() {
        lastKey = null
    }

    /** Opens the check screen directly. Tried only with the display-over-other-apps permission. */
    fun openScreen() {
        if (!runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)) return
        runCatching { ctx.startActivity(activityIntent()) }
            .onFailure { Log.w(TAG, "확인 화면 직접 실행 실패: ${it.message}") }
    }
}
