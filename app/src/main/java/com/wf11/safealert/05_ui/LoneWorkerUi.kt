package com.wf11.safealert.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import com.wf11.safealert.utils.DevSettings

/**
 * 단독 작업자 기능이 메인 화면에 거는 것들 (v1.1.99). MainActivity 에는 한 줄 호출만 둔다.
 * - 구조 요청 중 정지·역할 전환 차단 (모니터가 없이 저장된 구조 요청만 남았으면 서비스를 되살린다: 교대 인계)
 * - 알림 채널·전체 화면 알림·"다른 앱 위에 표시"가 모두 막혔을 때의 경고
 * - 메인 화면이 열려 있는 동안 확인·구조 요청 화면으로 직접 진입, 충전 중 안내, 정지 경합 복구
 */
object LoneWorkerUi {

    /**
     * 저장된 내 구조 요청이 있는데 모니터가 없으면 서비스를 다시 띄운다 (교대 인계·서비스 사망).
     * action 없는 시작은 BleService 의 저장 상태 복원 경로이며 monitor.start 가 저장된 구조 요청을 되살린다.
     * 되살릴 실행 상태(running_mode)가 없으면 아무것도 하지 않는다. 시작을 요청했으면 true.
     */
    fun reviveIfStoredSos(ctx: Context): Boolean {
        if (LoneWorkerMonitor.current != null || !LoneWorkerSosSync.hasStoredSos(ctx)) return false
        val running = runCatching {
            ctx.getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE).getString("running_mode", null)
        }.getOrNull()
        if (running == null) return false
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
        if (!reviveIfStoredSos(activity)) return false
        Toast.makeText(activity, "구조 요청을 복원합니다 — [괜찮음]으로 먼저 해제하세요", Toast.LENGTH_LONG).show()
        return true
    }

    /**
     * 화면이 꺼져 있거나 다른 앱을 쓰는 중에 확인·구조 요청 화면이 뜰 길이 없으면 경고와 설정 이동 버튼을 보인다.
     * "다른 앱 위에 표시"가 켜져 있으면 서비스가 화면을 직접 열 수 있어 경고하지 않는다. 감시는 계속된다.
     */
    fun warnIfUnreachable(activity: Activity, show: (String, () -> Unit) -> Unit) {
        val overlay = runCatching { Settings.canDrawOverlays(activity) }.getOrDefault(true)
        if (overlay) return
        val pkg = activity.packageName
        val notif = runCatching { NotificationManagerCompat.from(activity).areNotificationsEnabled() }.getOrDefault(true)
        val nm = runCatching { activity.getSystemService(NotificationManager::class.java) }.getOrNull()
        val chan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            runCatching { nm?.getNotificationChannel(LoneWorkerNotifier.CHANNEL_ID) }.getOrNull() else null
        val fsiDenied = Build.VERSION.SDK_INT >= 34 && runCatching { nm?.canUseFullScreenIntent() == false }.getOrDefault(false)
        when {
            !notif -> show(
                "알림 권한과 '다른 앱 위에 표시' 권한이 모두 꺼져 있어, 화면이 꺼져 있거나 다른 앱을 쓰는 중에는 " +
                    "근무 확인·구조 요청 화면이 뜨지 않습니다. 감시는 계속됩니다."
            ) { open(activity, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)) }
            chan != null && chan.importance < NotificationManager.IMPORTANCE_HIGH -> show(
                "구조 요청 알림 채널이 꺼져 있거나 중요도가 '높음'보다 낮아, 화면이 꺼져 있거나 다른 앱을 쓰는 중에는 " +
                    "근무 확인·구조 요청 화면이 뜨지 않습니다. 감시는 계속됩니다."
            ) {
                open(
                    activity,
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, LoneWorkerNotifier.CHANNEL_ID)
                )
            }
            fsiDenied -> show(
                "전체 화면 알림 권한이 꺼져 있어, 화면이 꺼져 있을 때 근무 확인·구조 요청 화면이 뜨지 않습니다. 감시는 계속됩니다."
            ) {
                if (Build.VERSION.SDK_INT >= 34)
                    open(activity, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")))
            }
        }
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
     * 메인 화면 800ms 폴링에서 한 번에 부른다: 알림 화면 진입, 정지 경합 복구, 충전 중 안내.
     * stopped 는 메인 화면이 실행 상태를 지운 상태(currentMode == null)다. 그런데 서비스가 구조 요청 때문에
     * 정지를 무시했다면 서비스가 running_mode 를 되살렸으므로 실행 카드로 돌아간다.
     */
    fun onPoll(activity: Activity, status: TextView, stopped: Boolean, restore: () -> Unit) {
        openIfAlerting(activity)
        if (stopped && LoneWorkerMonitor.current?.sosActive == true) {
            val running = runCatching {
                activity.getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE).getString("running_mode", null)
            }.getOrNull()
            if (running != null) restore()
        }
        val charging = LoneWorkerMonitor.current?.charging == true && DevSettings.lwEnabled
        status.visibility = if (charging) View.VISIBLE else View.GONE
        if (charging) status.text = "충전 중 — 무동작 감시 쉼"
    }
}
