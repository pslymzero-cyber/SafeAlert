package com.wf11.safealert.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * 가속도 센서 신호가 끊겼는지 판단하는 순수 모델 (v1.1.99, RR08).
 *
 * 마지막 이벤트로부터 stallMs 동안 아무것도 오지 않으면 센서를 다시 등록하게 한다(REREGISTER).
 * 그래도 이벤트가 없으면 간격을 두 배씩 늘려(30초, 60초, 120초 ... 최대 5분) 계속 다시 등록하게 하고,
 * 두 번째 재등록부터는 STALLED 로 알린다. 다음 검사 시각 전에는 OK(이미 STALLED 면 STALLED)를 준다.
 * 이벤트가 오면 모두 되돌아간다. 끊긴 동안에도 호출하는 쪽은 움직임이 없는 것으로 보고 무동작 시간을 계속 센다.
 */
class SensorStall(private val stallMs: Long = STALL_MS) {
    enum class Action { OK, REREGISTER, STALLED }

    companion object {
        const val STALL_MS = 30_000L
        const val MAX_RETRY_MS = 300_000L
    }

    private var nextCheckAt = stallMs
    private var retryGap = stallMs
    private var tries = 0

    /** 신호가 끊긴 것으로 확정된 상태. 이벤트가 오거나 reset 하면 풀린다. */
    var stalled = false
        private set

    fun reset(nowMs: Long) {
        nextCheckAt = nowMs + stallMs
        retryGap = stallMs
        tries = 0
        stalled = false
    }

    fun onEvent(nowMs: Long) = reset(nowMs)

    fun check(nowMs: Long): Action {
        if (nowMs < nextCheckAt) return if (stalled) Action.STALLED else Action.OK
        tries++
        stalled = tries >= 2
        nextCheckAt = nowMs + retryGap
        retryGap = minOf(retryGap * 2, MAX_RETRY_MS)
        return Action.REREGISTER
    }
}

/**
 * CPU 가 잠든 동안에도 센서 공백 검사를 돌리는 깨우기 알람과, 알림을 쓸어 내렸을 때 받는 브로드캐스트 수신부 (v1.1.99).
 *
 * 알람은 setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP) 이라 정확 알람 권한이 필요 없다. 수신부는 일부러
 * BroadcastReceiver 로 둔다: onReceive 가 메인 스레드에서 도는 동안 알람 매니저가 웨이크락을 잡아 준다.
 *
 * 한계: 정확하지 않은 알람이라 늦게 울릴 수 있다. Doze 에서 allow-while-idle 알람은 앱당 대략 9분에 한 번으로
 * 제한되고 대기 버킷에 따라 더 밀리므로, 센서가 끊긴 채 폰이 잠들면 검사가 몇 분 늦을 수 있다.
 * 폰이 깨어 있을 때는 모니터의 10초 핸들러 순환이 검사를 맡는다. 프로세스가 죽으면 알람도 울리지 않는다(스티키 재시작이 맡는다).
 */
class LoneWorkerWatchdog(
    private val ctx: Context,
    private val onWake: () -> Unit,
    private val onDismissed: () -> Unit
) {
    companion object {
        const val ACTION_WAKE = "com.wf11.safealert.LW_WAKE"
        const val ACTION_DISMISSED = "com.wf11.safealert.LW_DISMISSED"
        const val PERIOD_MS = 60_000L
        private const val REQ_WAKE = 51
        private const val REQ_DISMISS = 52
    }

    private var receiver: BroadcastReceiver? = null

    private fun pi(req: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, req, Intent(action).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 알림을 쓸어 내렸을 때 보낼 PendingIntent (알림의 deleteIntent). */
    fun dismissPi(): PendingIntent = pi(REQ_DISMISS, ACTION_DISMISSED)

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    ACTION_WAKE -> onWake()
                    ACTION_DISMISSED -> onDismissed()
                }
            }
        }
        receiver = r
        val f = IntentFilter().apply { addAction(ACTION_WAKE); addAction(ACTION_DISMISSED) }
        runCatching { ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    /** 다음 깨우기를 예약한다(기능이 켜져 있을 때만 부른다). 깨어난 뒤 onWake 처리가 끝나면 다시 부른다. */
    fun arm() {
        runCatching {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PERIOD_MS, pi(REQ_WAKE, ACTION_WAKE)
            )
        }
    }

    /** 예약된 깨우기를 취소한다. 기능을 끈 동안에는 알람이 없어야 한다. */
    fun disarm() {
        runCatching { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(REQ_WAKE, ACTION_WAKE)) }
    }

    fun stop() {
        disarm()
        receiver?.let { r -> runCatching { ctx.unregisterReceiver(r) } }
        receiver = null
    }
}
