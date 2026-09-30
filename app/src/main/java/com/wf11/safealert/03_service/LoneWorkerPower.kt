package com.wf11.safealert.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 전원 연결 값 디바운스 (순수, v1.1.99). 원시 값이 DEBOUNCE_MS 동안 그대로 바뀐 채 있어야 확정한다 —
 * 거치대 접점이 1~2초 끊겼다 붙는 흔들림은 확정 이벤트를 만들지 않는다.
 */
class PowerDebounce {
    companion object {
        const val DEBOUNCE_MS = 2_000L
        /** 확정을 확인하는 시각 — 첫 변화 뒤 이만큼(경계 여유 50ms). */
        const val CONFIRM_MS = DEBOUNCE_MS + 50
    }

    /** 2초 안정돼 확정한 값(로직 적용은 JudgeOrder 가 마감 순서에 맞춰 한다). */
    var reported = false
        private set
    /** 확정되지 않은 변화의 첫 변화 시각. 없으면 MIN_VALUE. */
    var pendingAt = Long.MIN_VALUE
        private set

    /** 확정되지 않은 변화가 있다. */
    val pending: Boolean get() = pendingAt != Long.MIN_VALUE

    /** 확정되지 않은 변화가 at 이전(같은 시각 포함)에 시작됐다 — 그 마감 판정은 확정·버림까지 기다린다(M1). */
    fun pendingBy(at: Long): Boolean = pending && pendingAt <= at

    /** 확정을 확인할 시각 = 첫 변화 + CONFIRM_MS. 대기가 없으면 null. */
    val confirmAt: Long? get() = if (pending) pendingAt + CONFIRM_MS else null

    fun seed(on: Boolean) {
        reported = on
        pendingAt = Long.MIN_VALUE
    }

    /**
     * 원시 값. 확정값으로 돌아오면 대기를 버리고, 새로 달라지면 그 시각부터 잰다. sticky(스티키 배터리 보정)는
     * 대기 중이면 버린다 — 방송이 앞선다. 대기(pendingAt)가 바뀌었으면 true.
     */
    fun raw(on: Boolean, tMs: Long, sticky: Boolean = false): Boolean {
        if (sticky && pending) return false
        val before = pendingAt
        if (on == reported) pendingAt = Long.MIN_VALUE
        else if (!pending) pendingAt = tMs
        return pendingAt != before
    }

    /** DEBOUNCE_MS 동안 안정됐으면 (새 값, 첫 변화 시각) 을 한 번 돌려준다. */
    fun poll(tMs: Long): Pair<Boolean, Long>? {
        if (!pending || tMs - pendingAt < DEBOUNCE_MS) return null
        val at = pendingAt
        reported = !reported
        pendingAt = Long.MIN_VALUE
        return reported to at
    }
}

/**
 * 외부 전원(PDA 충전 거치대·보조배터리) 연결 방송 수신부 (v1.1.99).
 * 원시 값을 onRaw 로 넘기고, 2초 디바운스·확정은 판정 로직(JudgeOrder.raw, PowerDebounce)이 맡는다.
 * 메인 스레드에서만 부른다.
 */
class LoneWorkerPower(
    private val ctx: Context,
    private val onRaw: (Boolean) -> Unit
) {
    private var receiver: BroadcastReceiver? = null

    /** 수신을 시작하고 지금 전원이 연결돼 있는지(원시값) 돌려준다. */
    fun start(): Boolean {
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    when (i?.action) {
                        Intent.ACTION_POWER_CONNECTED -> onRaw(true)
                        Intent.ACTION_POWER_DISCONNECTED -> onRaw(false)
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

    /** 배터리 스티키 인텐트로 지금 상태를 읽는다. 읽지 못하면 연결 안 됨. */
    fun plugged(): Boolean = runCatching {
        (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)

    fun stop() {
        receiver?.let { r -> runCatching { ctx.unregisterReceiver(r) } }
        receiver = null
    }
}
