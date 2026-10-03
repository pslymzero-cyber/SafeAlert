package com.wf11.safealert.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Power connection debounce (pure). A raw value is confirmed only after staying changed for DEBOUNCE_MS —
 * a cradle contact that drops for 1–2 s and reconnects produces no confirmed event.
 */
class PowerDebounce {
    companion object {
        const val DEBOUNCE_MS = 2_000L
        /** When to check for confirmation — this long after the first change (50ms boundary margin). */
        const val CONFIRM_MS = DEBOUNCE_MS + 50
    }

    /** Value confirmed after 2 s of stability (JudgeOrder applies it to the logic in deadline order). */
    var reported = false
        private set
    /** First change time of the unconfirmed change; MIN_VALUE if none. */
    var pendingAt = Long.MIN_VALUE
        private set

    /** An unconfirmed change exists. */
    val pending: Boolean get() = pendingAt != Long.MIN_VALUE

    /** An unconfirmed change started at or before at — that deadline's judgment waits until it is confirmed or discarded. */
    fun pendingBy(at: Long): Boolean = pending && pendingAt <= at

    /** Confirm check time = first change + CONFIRM_MS; null when nothing is pending. */
    val confirmAt: Long? get() = if (pending) pendingAt + CONFIRM_MS else null

    fun seed(on: Boolean) {
        reported = on
        pendingAt = Long.MIN_VALUE
    }

    /**
     * Raw value. Returning to the confirmed value discards the pending change; a new difference is timed from that moment. A sticky
     * value (sticky battery correction) is dropped while a change is pending — broadcasts take precedence. True if pendingAt changed.
     */
    fun raw(on: Boolean, tMs: Long, sticky: Boolean = false): Boolean {
        if (sticky && pending) return false
        val before = pendingAt
        if (on == reported) pendingAt = Long.MIN_VALUE
        else if (!pending) pendingAt = tMs
        return pendingAt != before
    }

    /** Once stable for DEBOUNCE_MS, returns (new value, first change time) once. */
    fun poll(tMs: Long): Pair<Boolean, Long>? {
        if (!pending || tMs - pendingAt < DEBOUNCE_MS) return null
        val at = pendingAt
        reported = !reported
        pendingAt = Long.MIN_VALUE
        return reported to at
    }
}

/**
 * Receiver for external power (PDA charging cradle, power bank) connection broadcasts.
 * Passes raw values to onRaw; the 2 s debounce and confirmation belong to the judge logic (JudgeOrder.raw, PowerDebounce).
 * Call on the main thread only.
 */
class LoneWorkerPower(
    private val ctx: Context,
    private val onRaw: (Boolean) -> Unit
) {
    private var receiver: BroadcastReceiver? = null

    /** Starts receiving and returns whether power is connected now (raw value). */
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

    /** Reads the current state from the sticky battery intent; not connected if it cannot be read. */
    fun plugged(): Boolean = runCatching {
        (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)

    fun stop() {
        receiver?.let { r -> runCatching { ctx.unregisterReceiver(r) } }
        receiver = null
    }
}
