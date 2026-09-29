package com.wf11.safealert.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.wf11.safealert.service.LoneWorkerLogic
import com.wf11.safealert.service.LoneWorkerMonitor
import com.wf11.safealert.service.LoneWorkerSosSync

/**
 * 단독 작업자 기능이 메인 화면에 거는 세 가지 (v1.1.99). MainActivity 에는 한 줄 호출만 둔다.
 * - 구조 요청 중 정지·역할 전환 차단
 * - 알림·"다른 앱 위에 표시" 권한이 모두 꺼졌을 때의 경고
 * - 메인 화면이 열려 있는 동안 확인·구조 요청 화면으로 직접 진입
 */
object LoneWorkerUi {

    /** 내 구조 요청이 진행 중(또는 저장돼 복원 대기)이면 안내를 띄우고 true 를 돌려준다. 호출한 쪽은 동작을 멈춘다. */
    fun blockIfOwnSos(activity: Activity): Boolean {
        val own = LoneWorkerMonitor.current?.sosActive == true || LoneWorkerSosSync.hasStoredSos(activity)
        if (own) Toast.makeText(activity, "[괜찮음]으로 먼저 해제하세요", Toast.LENGTH_LONG).show()
        return own
    }

    /** 알림과 다른 앱 위 표시가 둘 다 꺼져 있으면 화면이 뜰 길이 없다 — 경고와 설정 이동 버튼을 보인다. 감시는 계속된다. */
    fun warnIfUnreachable(activity: Activity, show: (String, () -> Unit) -> Unit) {
        val notif = runCatching { NotificationManagerCompat.from(activity).areNotificationsEnabled() }.getOrDefault(true)
        val overlay = runCatching { Settings.canDrawOverlays(activity) }.getOrDefault(true)
        if (notif || overlay) return
        show(
            "알림 권한과 '다른 앱 위에 표시' 권한이 모두 꺼져 있어, 화면이 꺼져 있거나 다른 앱을 쓰는 중에는 " +
                "근무 확인·구조 요청 화면이 뜨지 않습니다. 감시는 계속됩니다."
        ) {
            val ok = runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                )
            }.isSuccess
            if (!ok) runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
                )
            }
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
}
