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
import androidx.core.app.ActivityCompat
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
 * 단독 작업자 기능이 메인 화면에 거는 것들 (v1.1.99). MainActivity 에는 한 줄 호출만 둔다.
 * - 구조 요청 중 정지·역할 전환 차단 (모니터가 없이 저장된 구조 요청만 남았으면 서비스를 되살린다: 교대 인계)
 * - 알림 채널·전체 화면 알림·"다른 앱 위에 표시"가 모두 막혔을 때의 경고
 * - 메인 화면이 열려 있는 동안 확인·구조 요청 화면으로 직접 진입, 충전 중 안내, 정지 경합 복구
 */
object LoneWorkerUi {

    /** 경고 문구 공통 끝말. 이 끝말로 끝나는 경고만 도달성 경고로 보고 다시 판정해 지운다. */
    private const val REACH_TAIL = "구조 요청 화면이 뜨지 않습니다. 감시는 계속됩니다."

    /** Android 11 에서 백그라운드 위치가 없을 때의 경고 끝말. 이 끝말로 끝나는 경고도 다시 판정해 지운다. */
    private const val BG_LOC_TAIL = "위치 권한을 '항상 허용'으로 바꾸세요."
    private const val REQ_BG_LOC = 4730

    /**
     * 메인 화면이 요청하는 권한 목록(위치 포함). 서비스 시작 판정은 ServiceStartGate 가 따로 한다.
     */
    val runPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private fun runningMode(ctx: Context): String? = runCatching {
        ctx.getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE).getString("running_mode", null)
    }.getOrNull()

    /**
     * 저장된 내 구조 요청이 있는데 모니터가 없으면 서비스를 다시 띄운다 (교대 인계·서비스 사망).
     * action 없는 시작은 BleService 의 저장 상태 복원 경로이며 monitor.start 가 저장된 구조 요청을 되살린다.
     * 되살릴 실행 상태(running_mode)가 없거나 실행 권한이 빠져 있으면 아무것도 하지 않는다. 시작을 요청했으면 true.
     */
    fun reviveIfStoredSos(ctx: Context): Boolean {
        if (LoneWorkerMonitor.current != null || !LoneWorkerSosSync.hasStoredSos(ctx)) return false
        if (runningMode(ctx) == null || !ServiceStartGate.canStart(ctx)) return false
        return runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, BleService::class.java)) }.isSuccess
    }

    /**
     * 내 구조 요청이 진행 중이면 안내를 띄우고 true 를 돌려준다. 호출한 쪽은 동작을 멈춘다.
     * 판정은 살아 있는 모니터의 sosActive 만 쓴다. 모니터가 없고 저장된 구조 요청만 있으면 서비스를 되살리고 막는다.
     */
    fun blockIfOwnSos(activity: Activity): Boolean {
        val mon = LoneWorkerMonitor.current
        if (mon != null) {
            if (!mon.sosActive) return false
            Toast.makeText(activity, "[괜찮음]으로 먼저 해제하세요", Toast.LENGTH_LONG).show()
            return true
        }
        if (!reviveIfStoredSos(activity)) {
            // 권한이 빠져 서비스를 못 띄우는 상태: 저장된 구조 요청이 남아 있으면 실행 상태를 지우지 않고 막는다
            if (runningMode(activity) == null || !LoneWorkerSosSync.hasStoredSos(activity) || ServiceStartGate.canStart(activity)) return false
            Toast.makeText(activity, "근처 기기 권한을 허용한 뒤 [괜찮음]으로 먼저 해제하세요", Toast.LENGTH_LONG).show()
            return true
        }
        Toast.makeText(activity, "구조 요청을 복원합니다 — [괜찮음]으로 먼저 해제하세요", Toast.LENGTH_LONG).show()
        return true
    }

    /**
     * 화면이 꺼져 있거나 다른 앱을 쓰는 중에 확인·구조 요청 화면이 뜰 길이 없으면 (경고 문구, 설정 이동 의도)를 돌려준다.
     * "다른 앱 위에 표시"가 켜져 있으면 서비스가 화면을 직접 열 수 있어 null 이다. 감시는 계속된다.
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

    /** Android 11 에서 정밀 위치는 있는데 '항상 허용'이 아니면 경고 문구. 재시작 뒤 스캔이 멈출 수 있다. */
    private fun backgroundLocationWarning(ctx: Context): String? {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.R) return null
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return null
        return "위치 권한이 '앱 사용 중에만'이라 재시작·재부팅 뒤 근접 감지가 멈출 수 있습니다. $BG_LOC_TAIL"
    }

    private fun requestBackgroundLocation(activity: Activity) = runCatching {
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG_LOC)
    }

    /** 고른 설정 화면을 연다. 열지 못하면 앱 정보 화면으로 대신한다. */
    private fun open(activity: Activity, intent: Intent) {
        val ok = runCatching { activity.startActivity(intent) }.isSuccess
        if (!ok) runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            )
        }
    }

    /** 확인·구조 요청·동료 경보가 떠 있으면 화면을 연다. 메인 화면이 보이는 동안의 폴링에서 부른다. */
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
     * 메인 화면 800ms 폴링에서 한 번에 부른다: 알림 화면 진입, 정지 경합 복구, 거치 중 안내, 도달성 경고 재판정.
     * 도달성·백그라운드 위치(Android 11) 경고는 매번 다시 판정한다 — 막혀 있으면 띄우고 풀렸으면 지운다. 블루투스 권한 경고는 건드리지 않는다.
     * stopped 는 메인 화면이 실행 상태를 지운 상태(currentMode == null)다. 그런데 서비스가 구조 요청 때문에
     * 정지를 무시했다면 서비스가 running_mode 를 되살렸으므로 실행 카드로 돌아간다.
     */
    fun onPoll(
        activity: Activity, status: TextView, warnBox: View, warnMsg: TextView,
        stopped: Boolean, warn: (String, () -> Unit) -> Unit, restore: () -> Unit
    ) {
        openIfAlerting(activity)
        // 도달성 경고가 먼저, 없으면 Android 11 백그라운드 위치 경고 (문구, 버튼 동작)
        val w: Pair<String, () -> Unit>? = if (stopped) null else
            reachabilityWarning(activity)?.let { (t, i) -> t to { open(activity, i) } }
                ?: backgroundLocationWarning(activity)?.let { t -> t to { requestBackgroundLocation(activity); Unit } }
        val cur = if (warnBox.visibility == View.VISIBLE) warnMsg.text.toString() else null
        val ours = cur != null && (cur.endsWith(REACH_TAIL) || cur.endsWith(BG_LOC_TAIL))
        // 다른 경고(블루투스 권한 등)가 떠 있으면 덮어쓰지 않는다 — 비어 있거나 우리 경고일 때만 갱신
        if (w != null && cur != w.first && (cur == null || ours)) warn(w.first, w.second)
        if (w == null && ours) warnBox.visibility = View.GONE
        if (stopped && LoneWorkerMonitor.current?.sosActive == true) {
            if (runningMode(activity) != null) restore()
        }
        val text = when (if (DevSettings.lwEnabled) LoneWorkerMonitor.current?.rest else null) {
            LoneWorkerLogic.Rest.DOCKED -> "거치 중 — 무동작 감시 쉼 (들어 올리면 다시 시작)"
            LoneWorkerLogic.Rest.PICKUP -> "집어 들기 전 — 무동작 감시 쉼 (집어 들면 다시 시작)"
            else -> null
        }
        val vis = if (text != null) View.VISIBLE else View.GONE
        if (status.visibility != vis) status.visibility = vis
        if (text != null && status.text.toString() != text) status.text = text
    }
}
