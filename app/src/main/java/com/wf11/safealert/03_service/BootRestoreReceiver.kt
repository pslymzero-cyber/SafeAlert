package com.wf11.safealert.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * After a reboot or app update, if the service was running, restarts it through the saved-state restore path.
 * A start with no action is BleService's restore path, and monitor.start also revives my saved rescue request.
 * Does nothing when: there is no running state (running_mode); the user force-stopped the app after the last start
 * (Android 11+ — the running state is cleared too; restored anyway as an exception if I have a saved rescue request);
 * or the service start permission (ServiceStartGate) is missing.
 */
class BootRestoreReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootRestore"
        private const val UPDATE_EXIT_SLACK_MS = 60_000L

        /** Marks a background restore start — for the Android 11 location access check (BleService). */
        const val EXTRA_BOOT_RESTORE = "com.wf11.safealert.extra.BOOT_RESTORE"

        /**
         * When the service last started (wall-clock ms, safealert_prefs). Used only for the user-stop check,
         * separate from running_since, which is for display. Exit records older than this are ignored.
         */
        const val K_STARTED_AT = "service_started_at"

        /**
         * True if there is a user-requested exit after the last start (sinceMs). exits = (reason, timestamp).
         * If the start time is unknown (sinceMs <= 0), it does not check and restores. On Android 11~13 an update
         * exit may also be recorded as user-requested, so records within 60s either side of the app update time
         * (updatedAtMs) are not counted. That margin applies only when the update came after the last start
         * (with no update after the start, every record counts).
         */
        fun userStopped(sdk: Int, exits: List<Pair<Int, Long>>, sinceMs: Long, updatedAtMs: Long): Boolean {
            if (sdk < Build.VERSION_CODES.R || sinceMs <= 0L) return false
            val checkUpdate = !updateExitSeparate(sdk) && sinceMs < updatedAtMs
            return exits.any { (reason, at) ->
                reason == ApplicationExitInfo.REASON_USER_REQUESTED && at > sinceMs &&
                    !(checkUpdate && abs(at - updatedAtMs) <= UPDATE_EXIT_SLACK_MS)
            }
        }

        /**
         * Reference time for the user-stop check — if the check-only key is missing (started by an older app version),
         * falls back to the display start time on Android 14+ only.
         * On Android 11~13 returns 0 (no check, restore) — an update exit may be recorded as user-requested, and the
         * separate REASON_PACKAGE_UPDATED only exists from Android 14.
         */
        fun startedAt(newKey: Long, runningSince: Long, sdk: Int): Long = when {
            newKey > 0L -> newKey
            updateExitSeparate(sdk) -> runningSince
            else -> 0L
        }

        /** Update exits are recorded separately as REASON_PACKAGE_UPDATED — from Android 14. */
        private fun updateExitSeparate(sdk: Int) = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = runCatching { ctx.getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE) }.getOrNull() ?: return
        val running = prefs.getString("running_mode", null) ?: return
        val since = startedAt(prefs.getLong(K_STARTED_AT, 0L), prefs.getLong("running_since", 0L), Build.VERSION.SDK_INT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && since > 0L && !LoneWorkerSosSync.hasStoredSos(ctx)) {
            val exits = runCatching {
                ctx.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(ctx.packageName, 0, 0)
                    .map { it.reason to it.timestamp }
            }.getOrDefault(emptyList())
            val updatedAt = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }
                .getOrDefault(0L)
            if (userStopped(Build.VERSION.SDK_INT, exits, since, updatedAt)) {
                LoneWorkerResume.clearOnUserStop(prefs.edit()).commit()
                Log.w(TAG, "user stopped after last start, skip restore ($running)")
                return
            }
        }
        if (!ServiceStartGate.canStart(ctx)) {
            Log.w(TAG, "start permissions missing, skip restore ($running)")
            return
        }
        val start = Intent(ctx, BleService::class.java).putExtra(EXTRA_BOOT_RESTORE, true)
        runCatching { ContextCompat.startForegroundService(ctx, start) }
            .onFailure { Log.w(TAG, "service start failed: ${it.javaClass.simpleName}") }
    }
}
