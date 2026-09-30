package com.wf11.safealert.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.SystemClock

/**
 * 전원 연결 값 디바운스 (순수, v1.1.99). 원시 값이 ms 동안 그대로 바뀐 채 있어야 확정한다 —
 * 거치대 접점이 1~2초 끊겼다 붙는 흔들림은 확정 이벤트를 만들지 않는다.
 */
class PowerDebounce(private val ms: Long = DEBOUNCE_MS) {
    companion object {
        const val DEBOUNCE_MS = 2_000L
    }

    /** 마지막으로 확정한 값. */
    var reported = false
        private set
    private var lastRaw = false
    private var pendingAt = Long.MIN_VALUE

    /** 확정되지 않은 변화가 있다. */
    val pending: Boolean get() = pendingAt != Long.MIN_VALUE

    fun seed(on: Boolean) {
        reported = on
        lastRaw = on
        pendingAt = Long.MIN_VALUE
    }

    /** 원시 값. 확정값으로 돌아오면 대기를 버리고, 새로 달라지면 그 시각부터 잰다. */
    fun raw(on: Boolean, tMs: Long) {
        lastRaw = on
        if (on == reported) pendingAt = Long.MIN_VALUE
        else if (!pending) pendingAt = tMs
    }

    /** ms 동안 안정됐으면 (새 값, 첫 변화 시각) 을 한 번 돌려준다. */
    fun poll(tMs: Long): Pair<Boolean, Long>? {
        if (!pending || tMs - pendingAt < ms || lastRaw == reported) return null
        val at = pendingAt
        reported = lastRaw
        pendingAt = Long.MIN_VALUE
        return reported to at
    }
}

/**
 * 외부 전원(PDA 충전 거치대·보조배터리) 연결 여부를 알려 주는 수신부 (v1.1.99).
 *
 * 시스템이 보내는 POWER_CONNECTED/DISCONNECTED 를 2초 디바운스한 뒤 (값, 첫 변화 elapsed 시각) 으로 넘긴다.
 * 메인 스레드에서만 부른다.
 */
class LoneWorkerPower(
    private val ctx: Context,
    private val handler: Handler,
    private val onChange: (Boolean, Long) -> Unit
) {
    private var receiver: BroadcastReceiver? = null
    private val debounce = PowerDebounce()
    private val pollRunnable = Runnable {
        debounce.poll(SystemClock.elapsedRealtime())?.let { (on, at) -> onChange(on, at) }
    }

    /** 수신을 시작하고 지금 전원이 연결돼 있는지(원시값) 돌려준다. 디바운스는 seed 로 시작한다. */
    fun start(): Boolean {
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    when (i?.action) {
                        Intent.ACTION_POWER_CONNECTED -> raw(true)
                        Intent.ACTION_POWER_DISCONNECTED -> raw(false)
                    }
                }
            }
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            if (runCatching { ctx.registerReceiver(r, f) }.isSuccess) receiver = r
        }
        return plugged()
    }

    /** 디바운스를 reported(저장 상태의 충전 값)로 시작하고 원시값 now 를 넣는다 — 다르면 2초 안정 뒤 onChange. */
    fun seed(reported: Boolean, now: Boolean) {
        debounce.seed(reported)
        raw(now)
    }

    /** 방송을 놓쳤을 때의 보정: 스티키 배터리 상태를 원시 값으로 넣는다. 방송 변화가 대기 중이면 건드리지 않는다. */
    fun pollSticky() {
        if (!debounce.pending) raw(plugged())
    }

    private fun raw(on: Boolean) {
        debounce.raw(on, SystemClock.elapsedRealtime())
        if (debounce.pending) handler.postDelayed(pollRunnable, PowerDebounce.DEBOUNCE_MS + 50)
    }

    /** 배터리 스티키 인텐트로 지금 상태를 읽는다. 읽지 못하면 연결 안 됨. */
    fun plugged(): Boolean = runCatching {
        (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)

    fun stop() {
        handler.removeCallbacks(pollRunnable)
        receiver?.let { r -> runCatching { ctx.unregisterReceiver(r) } }
        receiver = null
    }
}
