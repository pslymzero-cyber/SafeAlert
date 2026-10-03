package com.wf11.safealert.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wf11.safealert.service.BleService
import com.wf11.safealert.service.LoneWorkerLogic
import com.wf11.safealert.service.LoneWorkerMonitor
import com.wf11.safealert.service.LoneWorkerNotifier
import com.wf11.safealert.service.LoneWorkerSosSync
import com.wf11.safealert.service.ServiceStartGate
import com.wf11.safealert.utils.DevSettings

/**
 * Lone-worker hooks on the main screen. MainActivity keeps only one-line calls.
 * - Block stop/role switch during an SOS (if only a saved SOS remains with no monitor, revive the service: shift handover)
 * - Warning when the notification channel, full-screen notifications and "다른 앱 위에 표시" (display over other apps) are all blocked
 * - While the main screen is open: open the check/SOS screen directly, charging hint, stop-race recovery
 */
object LoneWorkerUi {

    /**
     * Common suffix of the warning texts. Only warnings ending with it are treated as reachability warnings, re-evaluated and cleared.
     */
    private const val REACH_TAIL = "구조 요청 화면이 뜨지 않습니다. 감시는 계속됩니다."

    /**
     * Warning suffix for missing background location on Android 11. Warnings ending with it are also re-evaluated and cleared.
     */
    private const val BG_LOC_TAIL = "위치 권한을 '항상 허용'으로 바꾸세요."

    /** Warning suffix for missing physical activity permission. Warnings ending with it are also re-evaluated and cleared. */
    private const val ACT_TAIL = "앱 설정에서 신체 활동을 허용하세요."

    /**
     * Warning suffix for monitoring stopped by missing start permissions. Warnings ending with it are also re-evaluated and cleared.
     */
    private const val PERM_TAIL = "눌러서 권한 설정"

    /**
     * Required permissions the main screen requests: service start permissions + fine location (derived from ServiceStartGate).
     */
    val runPermissions: Array<String> = ServiceStartGate.screenPermissions(Build.VERSION.SDK_INT)

    private fun runningMode(ctx: Context): String? = runCatching {
        ctx.getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE).getString("running_mode", null)
    }.getOrNull()

    /**
     * If my saved SOS exists but there is no monitor, restart the service (shift handover, service death).
     * A start without an action is BleService's saved-state restore path; monitor.start revives the saved SOS.
     * Does nothing if there is no running state (running_mode) to revive or run
     * permissions are missing. Returns true if a start was requested.
     */
    fun reviveIfStoredSos(ctx: Context): Boolean {
        if (LoneWorkerMonitor.current != null || !LoneWorkerSosSync.hasStoredSos(ctx)) return false
        if (runningMode(ctx) == null || !ServiceStartGate.canStart(ctx)) return false
        return runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, BleService::class.java)) }.isSuccess
    }

    /**
     * If the running state (running_mode) remains but the service isn't running,
     * restart it (returning to the screen after a failed start / force stop).
     * Does nothing without start permissions. Returns true if a start was requested.
     */
    fun reviveIfStopped(ctx: Context): Boolean {
        if (BleService.isRunning || LoneWorkerMonitor.current != null) return false
        if (runningMode(ctx) == null || !ServiceStartGate.canStart(ctx)) return false
        return runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, BleService::class.java)) }.isSuccess
    }

    /**
     * If my SOS is in progress, show a notice and return true; the caller stops its action.
     * Only the live monitor's sosActive decides. With no monitor but a saved SOS, revive the service and block.
     */
    fun blockIfOwnSos(activity: Activity): Boolean {
        val mon = LoneWorkerMonitor.current
        if (mon != null) {
            if (!mon.sosActive) return false
            Toast.makeText(activity, "[괜찮아요]로 먼저 해제하세요", Toast.LENGTH_LONG).show()
            return true
        }
        if (!reviveIfStoredSos(activity)) {
            // Service can't start for missing permissions: if a saved SOS remains, block without clearing the running state
            if (runningMode(activity) == null || !LoneWorkerSosSync.hasStoredSos(activity) || ServiceStartGate.canStart(activity)) return false
            Toast.makeText(activity, "근처 기기 권한을 허용한 뒤 [괜찮아요]로 먼저 해제하세요", Toast.LENGTH_LONG).show()
            return true
        }
        Toast.makeText(activity, "구조 요청을 복원합니다 — [괜찮아요]로 먼저 해제하세요", Toast.LENGTH_LONG).show()
        return true
    }

    /**
     * Returns (warning text, settings intent) if the check/SOS screen has no way to appear while the screen is off or another app is in use.
     * null when "다른 앱 위에 표시" (display over other apps) is on, since the service can open the screen directly. Monitoring continues.
     */
    fun reachabilityWarning(ctx: Context): Pair<String, Intent>? {
        val overlay = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(true)
        if (overlay) return null
        val pkg = ctx.packageName
        val notif = runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(true)
        val nm = runCatching { ctx.getSystemService(NotificationManager::class.java) }.getOrNull()
        val chan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            runCatching { nm?.getNotificationChannel(LoneWorkerNotifier.CHANNEL_ID) }.getOrNull() else null
        val fsiDenied = Build.VERSION.SDK_INT >= 34 && runCatching { nm?.canUseFullScreenIntent() == false }.getOrDefault(false)
        return when {
            !notif -> "알림 권한과 '다른 앱 위에 표시' 권한이 모두 꺼져 있어, 화면이 꺼져 있거나 다른 앱을 쓰는 중에는 " +
                "근무 확인·" + REACH_TAIL to Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            chan != null && chan.importance < NotificationManager.IMPORTANCE_HIGH ->
                "구조 요청 알림 채널이 꺼져 있거나 '소리와 함께 화면에 팝업'(Android 8 은 '긴급')으로 설정되어 있지 않아, " +
                    "화면이 꺼져 있거나 다른 앱을 쓰는 중에는 근무 확인·" + REACH_TAIL to
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, LoneWorkerNotifier.CHANNEL_ID)
            fsiDenied -> "전체 화면 알림 권한이 꺼져 있어, 화면이 꺼져 있을 때 근무 확인·" + REACH_TAIL to
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg"))
            else -> null
        }
    }

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /**
     * On Android 11, fine location is granted but not "항상 허용" (allow all the time).
     * The main screen requests it right after the permission request.
     */
    fun needsBackgroundLocation(ctx: Context): Boolean = Build.VERSION.SDK_INT == Build.VERSION_CODES.R &&
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Warning text if not "항상 허용" (allow all the time); scanning may stop after a restart. */
    private fun backgroundLocationWarning(ctx: Context): String? = if (!needsBackgroundLocation(ctx)) null else
        "위치 권한이 '앱 사용 중에만'이라 재시작·재부팅 뒤 근접 감지가 멈출 수 있습니다. $BG_LOC_TAIL"

    /** Warning text if there is a step sensor but no physical activity permission. Checked only while monitoring runs. */
    private fun activityWarning(): String? =
        if (DevSettings.lwEnabled && LoneWorkerMonitor.current?.stepPermissionMissing == true)
            "신체 활동 권한이 없어 걸음을 감지하지 못합니다(강한 움직임으로 대신 판단). $ACT_TAIL" else null

    /**
     * Warning text if the running state remains, there is no service/monitor and start
     * permissions are missing (entered by tapping the stop-notice notification).
     */
    private fun permissionStopWarning(ctx: Context): String? =
        if (runningMode(ctx) != null && !BleService.isRunning && LoneWorkerMonitor.current == null &&
            !ServiceStartGate.canStart(ctx)) "권한이 없어 감시가 멈췄습니다 — $PERM_TAIL" else null

    private fun appInfo(activity: Activity) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))

    /** Open the chosen settings screen; if it can't be opened, open the app info screen instead. */
    private fun open(activity: Activity, intent: Intent) {
        val ok = runCatching { activity.startActivity(intent) }.isSuccess
        if (!ok) runCatching { activity.startActivity(appInfo(activity)) }
    }

    /** Open the screen if a check, SOS or peer alert is up. Called from polling while the main screen is visible. */
    fun openIfAlerting(activity: Activity) {
        val st = LoneWorkerMonitor.current?.uiState() ?: return
        if (st.mode == LoneWorkerLogic.Mode.WATCHING && !st.peerActive) return
        runCatching {
            activity.startActivity(
                Intent(activity, LoneWorkerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    /**
     * Called once per main-screen 800ms poll: open the alert screen, stop-race recovery, mounted hint, reachability warning recheck.
     * The permission-stop, reachability, background location (Android 11) and physical activity warnings are re-evaluated
     * every time — shown while blocked, cleared once unblocked. The Bluetooth permission warning is left alone.
     * The permission-stop warning's button opens the app info (permissions) screen; after enabling
     * permissions and returning, the on-return restart runs again and this warning clears.
     * If both background location and physical activity warnings apply, they show together on two lines.
     * stopped means the main screen cleared the running state (currentMode == null). But if the service ignored the stop
     * because of an SOS, the service restored running_mode, so go back to the running card.
     */
    fun onPoll(
        activity: Activity, status: TextView, warnBox: View, warnMsg: TextView,
        stopped: Boolean, warn: (String, () -> Unit) -> Unit, restore: () -> Unit
    ) {
        openIfAlerting(activity)
        // Order: permission stop → reachability → Android 11 background location / physical activity warnings (text, button action)
        val w: Pair<String, () -> Unit>? = if (stopped) null else
            permissionStopWarning(activity)?.let { t -> t to { open(activity, appInfo(activity)) } }
                ?: reachabilityWarning(activity)?.let { (t, i) -> t to { open(activity, i) } }
                ?: listOfNotNull(backgroundLocationWarning(activity), activityWarning()).joinToString("\n").ifEmpty { null }
                    ?.let { t -> t to { open(activity, appInfo(activity)) } }
        val cur = if (warnBox.visibility == View.VISIBLE) warnMsg.text.toString() else null
        val ours = cur != null && (cur.endsWith(REACH_TAIL) || cur.endsWith(BG_LOC_TAIL) || cur.endsWith(ACT_TAIL) ||
            cur.endsWith(PERM_TAIL))
        // Don't overwrite another warning (Bluetooth permission etc.) — update only when empty or one of ours
        if (w != null && cur != w.first && (cur == null || ours)) warn(w.first, w.second)
        if (w == null && ours) warnBox.visibility = View.GONE
        if (stopped && LoneWorkerMonitor.current?.sosActive == true) {
            if (runningMode(activity) != null) restore()
        }
        val text = if (DevSettings.lwEnabled) LoneWorkerMonitor.current?.banner else null
        val vis = if (text != null) View.VISIBLE else View.GONE
        if (status.visibility != vis) status.visibility = vis
        if (text != null && status.text.toString() != text) status.text = text
    }
}
