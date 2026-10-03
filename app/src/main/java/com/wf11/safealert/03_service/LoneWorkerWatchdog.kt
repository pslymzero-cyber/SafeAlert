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
 * Pure model that decides whether the accelerometer signal has stalled.
 *
 * If nothing arrives for stallMs after the last event, asks for sensor re-registration (REREGISTER).
 * If events still don't come, keeps asking for re-registration at doubling intervals (30 s, 60 s, 120 s ... up to 5 min),
 * and reports STALLED from the second re-registration on. Before the next check time it returns OK (STALLED if already stalled).
 * Any event resets everything. While stalled, the caller treats it as no movement and keeps counting no-motion time.
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

    /** Signal confirmed lost. Cleared when an event arrives or on reset. */
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
 * Wake-up alarm that runs the sensor gap check even while the CPU sleeps, plus the receiver for the broadcast sent when a
 * notification is swiped away.
 *
 * The alarm uses setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP), so no exact-alarm permission is needed. The receiver is
 * deliberately a BroadcastReceiver: the alarm manager holds a wake lock while onReceive runs on the main thread.
 *
 * Limits: an inexact alarm may fire late. In Doze, allow-while-idle alarms are limited to roughly once per 9 minutes per app
 * and delayed further by the standby bucket, so if the phone sleeps with the sensor stalled the check can be minutes late.
 * While the phone is awake, the monitor's 10 s handler loop runs the check. If the process dies the alarm doesn't fire either
 * (the sticky restart covers that).
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

    /** PendingIntent sent when the notification is swiped away (the notification's deleteIntent). */
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

    /** Schedules the next wake-up (call only while the feature is on). Called again after onWake handling finishes. */
    fun arm() {
        runCatching {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PERIOD_MS, pi(REQ_WAKE, ACTION_WAKE)
            )
        }
    }

    /** Cancels the scheduled wake-up. There must be no alarm while the feature is off. */
    fun disarm() {
        runCatching { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(REQ_WAKE, ACTION_WAKE)) }
    }

    fun stop() {
        disarm()
        receiver?.let { r -> runCatching { ctx.unregisterReceiver(r) } }
        receiver = null
    }
}
