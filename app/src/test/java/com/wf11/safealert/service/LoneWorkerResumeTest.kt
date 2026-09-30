package com.wf11.safealert.service

import android.content.SharedPreferences
import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Resume after a service restart: the state is saved on the wall clock, so a reboot (elapsed time
 * starts again near zero) keeps the real time that passed. After resume the monitor applies the
 * current power state as a real plug/unplug, then a stored own SOS wins.
 */
class LoneWorkerResumeTest {

    private val wall0 = 1_000_000_000L

    /** Save old at savedAt (wall wall0), reboot to elapsed now with wall0 + wallGap, and resume like the monitor. */
    private fun reboot(old: LoneWorkerLogic, savedAt: Long, now: Long, wallGap: Long, charging: Boolean = false): LoneWorkerLogic {
        val raw = LoneWorkerResume.encode(old.snapshot(), savedAt, wall0)
        val s = LoneWorkerResume.decode(raw, now, wall0 + wallGap)
        assertNotNull(s)
        return LoneWorkerLogic("SAFEALERT_WALKER_ME").apply {
            start(now, false, charging)
            resume(s!!, now)
            setCharging(charging, now)
        }
    }

    private fun accidentCheck(): LoneWorkerLogic = newLogic(carried = true).apply {
        onAccident(1_000)
        assertEquals(Mode.CHECKING, seenAt(31_000))
        assertEquals("fall", trigger)
    }

    @Test fun resume_reopens_accident_check_with_full_response() {
        val old = accidentCheck()
        assertEquals(50_000L, old.responseLeftMs(41_000))
        val l = reboot(old, 41_000, 5_000, 20_000)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("fall", l.trigger)
        assertEquals(LoneWorkerLogic.ACCIDENT_RESPONSE_MS, l.responseLeftMs(5_000))
    }

    @Test fun resume_reopens_still_check_with_full_response() {
        val old = newLogic(carried = true)
        assertEquals(Mode.CHECKING, old.modeAt(180_000))
        assertEquals("still", old.trigger)
        val l = reboot(old, 200_000, 5_000, 20_000)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)
        assertEquals(l.responseMs, l.responseLeftMs(5_000))
    }

    @Test fun resume_drops_suspicion_older_than_5min() {
        val old = newLogic()
        old.onAccident(1_000)
        // saved 9 s after the trigger, restarted 300 s of wall time later: 309 s after the trigger
        val l = reboot(old, 10_000, 5_000, 300_000)
        assertNull(l.snapshot().accidentUntil)
        assertEquals(Mode.WATCHING, l.seenAt(65_000))
    }

    @Test fun resume_keeps_suspicion_within_5min() {
        val old = newLogic()
        old.onAccident(1_000)
        // restarted 119 s after the trigger: the 30 s deadline has passed, sensor data after it is gone
        val l = reboot(old, 10_000, 5_000, 110_000)
        assertNotNull(l.snapshot().accidentUntil)
        assertEquals(Mode.CHECKING, l.modeAt(5_000 + LoneWorkerLogic.LATE_MS))
        assertEquals("fall", l.trigger)
    }

    @Test fun resume_keeps_carried_latch_while_charging() {
        val old = newLogic(charging = true)
        assertEquals(Rest.DOCKED, old.rest)
        old.walk(10_000, 10)
        assertEquals(Rest.NONE, old.rest)
        val l = reboot(old, 12_000, 5_000, 20_000, charging = true)
        assertEquals(Rest.NONE, l.rest)
    }

    @Test fun resume_keeps_wait_and_still_base() {
        val waiting = reboot(newLogic(), 10_000, 5_000, 20_000)
        assertEquals(Rest.WAIT, waiting.rest)

        val old = newLogic(carried = true)
        old.onMoved(50_000)
        val l = reboot(old, 100_000, 5_000, 20_000)
        assertEquals(Rest.NONE, l.rest)
        // still since wall0 - 50 s, restarted at wall0 + 20 s: 70 s already still, the check opens 110 s after restart
        val due = l.snapshot().stillBase + l.stillMs
        assertEquals(115_000L, due)
        assertEquals(Mode.WATCHING, l.modeAt(due - 1))
        assertEquals(Mode.CHECKING, l.modeAt(due))
        assertEquals("still", l.trigger)
    }

    @Test fun power_change_during_downtime_applies_as_real_change() {
        val old = newLogic(carried = true)
        old.onAccident(1_000)
        assertNotNull(old.snapshot().accidentUntil)
        val l = reboot(old, 10_000, 5_000, 20_000, charging = true)
        assertEquals(Rest.DOCKED, l.rest)
        assertNull(l.snapshot().accidentUntil)
        assertEquals(Mode.WATCHING, l.seenAt(65_000))
    }

    @Test fun sos_is_not_saved_as_check() {
        val l = newLogic(carried = true)
        l.restoreSos("fall", 1_000)
        assertEquals(Mode.SOS, l.mode)
        assertEquals("", l.snapshot().check)
        assertNull(l.snapshot().checkAt)
    }

    @Test fun decode_rejects_garbage() {
        val ok = LoneWorkerResume.encode(accidentCheck().snapshot(), 40_000, wall0)
        assertNotNull(LoneWorkerResume.decode(ok, 40_000, wall0))
        assertEquals(accidentCheck().snapshot(), LoneWorkerResume.decode(ok, 40_000, wall0))
        for (bad in listOf(null, "", "v1|1|2", "v2" + ok.drop(2), ok.replace("|fall|", "|x|"),
            ok.replaceFirst("|", "|abc"), ok.dropLast(1) + "x", ok + "|1")) {
            assertNull(bad, LoneWorkerResume.decode(bad, 40_000, wall0))
        }
    }

    @Test fun clear_on_user_stop_removes_resume_and_running_keys() {
        val removed = ArrayList<String>()
        val editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?): SharedPreferences.Editor { removed.add(key!!); return this }
            override fun clear() = this
            override fun commit() = true
            override fun apply() {}
        }
        val back = LoneWorkerResume.clearOnUserStop(editor)
        assertEquals(editor, back)
        assertEquals(setOf("running_mode", "running_since", "running_category", LoneWorkerResume.KEY), removed.toSet())
    }
}
