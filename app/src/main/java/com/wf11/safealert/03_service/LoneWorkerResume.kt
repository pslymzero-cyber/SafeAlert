package com.wf11.safealert.service

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import kotlin.math.abs

/**
 * Restart-resume store for lone-worker monitoring.
 *
 * Saved: accident suspicion (30 s count baseline and end), open check window kind, charging, carry confirmed, no-motion
 * baseline time (excluding the siren pause), safe-zone state (settled flag and zone entry time; absent when outside), and the
 * equipment mount flag (the v2 format reads as not mounted). Waiting = not charging and carry not confirmed.
 * The own SOS is not saved (SosLedger restores it and takes precedence).
 * Times are stored as elapsedRealtime at save time, along with the boot count, save elapsed and save wall clock. On the same
 * boot the elapsed values are used as is (immune to wall-clock changes); on a different boot (or unknown boot count) they are
 * shifted by the wall-clock time elapsed (negative counts as 0).
 * On restart the check window is shown again with a fresh response time, and accident suspicion more than 5 min after its
 * trigger is dropped (LoneWorkerLogic.startFrom).
 * If the saved charging flag differs from the current power, a restart power hold applies (RestartHold).
 * "중지" or a user-stop decision clears it via clearOnUserStop. Merely stopping monitoring does not clear it, so it survives a
 * system kill or restart.
 */
class LoneWorkerResume(private val ctx: Context) {

    /**
     * All times are elapsedRealtime ms. check is the open check window kind ("fall" or
     * "still"), empty if none. zoneSince null means outside the zone.
     */
    data class State(
        val accidentHold: Long?,
        val accidentUntil: Long?,
        val check: String,
        val charging: Boolean,
        val carried: Boolean,
        val stillBase: Long,
        val zoneSettled: Boolean,
        val zoneSince: Long?,
        /**
         * Saved during an equipment mount (equipment mode + charging + not carried) — only then is the mount count baseline saved and trustworthy.
         */
        val mounted: Boolean = false
    )

    companion object {
        const val KEY = "lw_resume"
        private const val PREFS = "safealert_prefs"
        private const val VERSION = "v3"
        private const val FIELDS = 13
        /** Legacy 12-field format without the mount field; read as mounted = false. */
        private const val OLD_VERSION = "v2"
        /**
         * When only the no-motion baseline changed, save again only once it moved this much (avoids a write every second while moving).
         */
        private const val STILL_SAVE_MS = 10_000L
        private val CHECKS = setOf("", "fall", "still")

        fun encode(s: State, elapsedNow: Long, wallNow: Long, boot: Int): String {
            fun t(v: Long?) = v?.toString() ?: ""
            fun b(v: Boolean) = if (v) "1" else "0"
            return listOf(VERSION, boot.toString(), elapsedNow.toString(), wallNow.toString(),
                t(s.accidentHold), t(s.accidentUntil), s.check, b(s.charging), b(s.carried), s.stillBase.toString(),
                b(s.zoneSettled), t(s.zoneSince), b(s.mounted)).joinToString("|")
        }

        /**
         * null if the format, field count, numbers or kind value don't match (start fresh
         * without resuming). boot is the current boot count (-1 if unknown).
         */
        fun decode(raw: String?, elapsedNow: Long, wallNow: Long, boot: Int): State? {
            if (raw == null) return null
            return runCatching {
                val f = raw.split("|")
                val cur = f.size == FIELDS && f[0] == VERSION
                require((cur || f.size == FIELDS - 1 && f[0] == OLD_VERSION) && f[6] in CHECKS)
                val savedBoot = f[1].toInt()
                val savedElapsed = f[2].toLong()
                val savedWall = f[3].toLong()
                val sameBoot = boot >= 0 && savedBoot == boot && elapsedNow >= savedElapsed
                val shift = if (sameBoot) 0L else elapsedNow - maxOf(0L, wallNow - savedWall) - savedElapsed
                fun t(v: String): Long? = if (v.isEmpty()) null else v.toLong() + shift
                fun past(v: String): Long? = t(v)?.let { minOf(it, elapsedNow) }
                fun b(v: String): Boolean = when (v) { "1" -> true; "0" -> false; else -> error(v) }
                State(past(f[4]), t(f[5]), f[6], b(f[7]), b(f[8]), past(f[9])!!, b(f[10]), past(f[11]),
                    cur && b(f[12]))
            }.getOrNull()
        }

        /**
         * Whether to save: first save, a field other than the baseline changed, or the baseline moved by
         * STILL_SAVE_MS or more while carried or equipment-mounted outside a settled zone.
         */
        fun shouldSave(prev: State?, s: State): Boolean {
            if (prev == null || prev.copy(stillBase = s.stillBase) != s) return true
            return (s.carried || s.mounted) && !s.zoneSettled && abs(s.stillBase - prev.stillBase) >= STILL_SAVE_MS
        }

        /**
         * The user stopped: clears the run-restore key together with the restart state. Returns the same editor (the caller commits).
         */
        fun clearOnUserStop(editor: SharedPreferences.Editor): SharedPreferences.Editor =
            editor.remove("running_mode").remove("running_since").remove("running_category").remove(KEY)
    }

    private var last: State? = null
    private val boot: Int by lazy {
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
    }

    fun load(nowMs: Long): State? {
        last = null
        val raw = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) }.getOrNull()
        return decode(raw, nowMs, System.currentTimeMillis(), boot)
    }

    /** Saves only when shouldSave. nowMs uses the same elapsedRealtime base as the times in s. */
    fun save(s: State, nowMs: Long) {
        if (!shouldSave(last, s)) return
        last = s
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, encode(s, nowMs, System.currentTimeMillis(), boot)).apply()
        }
    }
}
