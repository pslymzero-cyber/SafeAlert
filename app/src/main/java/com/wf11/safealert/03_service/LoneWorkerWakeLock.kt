package com.wf11.safealert.service

import android.content.Context
import android.os.PowerManager

/**
 * The single partial wake lock for lone-worker monitoring — need is decided by wakeNeeded
 * below (called by the monitor); the 5 s loop renews the 10-minute timeout.
 */
internal class LoneWorkerWakeLock(private val ctx: Context) {
    private var lock: PowerManager.WakeLock? = null

    /** Releases unless need. When needed, creates it once and acquires it with a 10-minute timeout if renew or not held. */
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

/**
 * Whether the 5 s refresh loop is needed — during checking / SOS, while peer entries exist,
 * when sensors must stay awake, or during an equipment mount while monitoring.
 */
internal fun LoneWorkerLogic.loopNeeded(sensorsNeedWake: Boolean): Boolean =
    mode != LoneWorkerLogic.Mode.WATCHING || peers.isNotEmpty() || sensorsNeedWake || enabled && mounted

/**
 * Whether the wake lock is needed — sensors, an equipment mount while monitoring,
 * checking / SOS, a ringing peer, or a passed deadline awaiting judgment.
 */
internal fun LoneWorkerLogic.wakeNeeded(sensorsNeedWake: Boolean, nowMs: Long): Boolean =
    sensorsNeedWake || enabled && mounted || mode != LoneWorkerLogic.Mode.WATCHING ||
        audiblePeers().isNotEmpty() || waitingToJudge(nowMs)
