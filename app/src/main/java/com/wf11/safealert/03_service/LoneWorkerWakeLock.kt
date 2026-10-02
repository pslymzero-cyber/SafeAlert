package com.wf11.safealert.service

import android.content.Context
import android.os.PowerManager

/** 단독 작업자 감시의 부분 웨이크락 하나 — 필요 판단은 모니터, 10분 시한은 5초 루프가 갱신. */
internal class LoneWorkerWakeLock(private val ctx: Context) {
    private var lock: PowerManager.WakeLock? = null

    /** need 가 아니면 놓는다. 필요하면 처음 한 번 만들고, renew 이거나 안 잡혀 있으면 10분 시한으로 잡는다. */
    fun hold(need: Boolean, renew: Boolean) {
        if (!need) {
            release()
            return
        }
        val wl = lock ?: runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafeAlert:LoneWorker")
                .apply { setReferenceCounted(false) }
        }.getOrNull()?.also { lock = it } ?: return
        if (renew || !wl.isHeld) runCatching { wl.acquire(TIMEOUT_MS) }
    }

    fun release() {
        lock?.let { if (it.isHeld) runCatching { it.release() } }
    }

    private companion object {
        const val TIMEOUT_MS = 10 * 60_000L
    }
}
