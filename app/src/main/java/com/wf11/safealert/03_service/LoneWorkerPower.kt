package com.wf11.safealert.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 외부 전원(PDA 충전 거치대) 연결 여부를 알려 주는 수신부 (v1.1.99).
 *
 * 시스템이 보내는 POWER_CONNECTED/DISCONNECTED 만 받는다. 켜고 끄는 값만 넘기며 구조 요청 판정에는 관여하지 않는다.
 * 메인 스레드에서만 부른다.
 */
class LoneWorkerPower(private val ctx: Context, private val onChange: (Boolean) -> Unit) {
    private var receiver: BroadcastReceiver? = null

    /** 수신을 시작하고 지금 전원이 연결돼 있는지 돌려준다. */
    fun start(): Boolean {
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    when (i?.action) {
                        Intent.ACTION_POWER_CONNECTED -> onChange(true)
                        Intent.ACTION_POWER_DISCONNECTED -> onChange(false)
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
