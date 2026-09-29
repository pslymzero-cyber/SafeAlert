package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** External power rule: docked and still = rest, carried = normal watch, unplug without carry waits for motion. */
class LoneWorkerChargeTest {

    private val stillMs = 180_000L
    private val responseMs = 120_000L

    private fun newLogic() = LoneWorkerLogic("SAFEALERT_WALKER_ME").apply { start(0L, false) }

    private fun LoneWorkerLogic.toChecking() { tick(stillMs) }
    private fun LoneWorkerLogic.toSos() { tick(stillMs); tick(stillMs + responseMs) }

    @Test fun charging_and_still_rests() {
        val l = newLogic()
        l.setCharging(true, 1_000)
        l.tick(stillMs + 60_000)
        assertEquals(Mode.WATCHING, l.mode)
        l.onFall(stillMs + 61_000)
        l.tick(stillMs + 62_000)
        assertEquals(Mode.WATCHING, l.mode)
        assertTrue(l.resting)
    }

    @Test fun motion_in_first_15s_after_plug_is_ignored() {
        val l = newLogic()
        l.setCharging(true, 1_000)
        l.onMoved(15_999)
        assertTrue(l.resting)
        l.tick(15_999 + stillMs + 1_000)
        assertEquals(Mode.WATCHING, l.mode)
    }

    @Test fun sustained_motion_while_charging_watches_still_and_fall() {
        val l = newLogic()
        l.setCharging(true, 1_000)
        l.onMoved(16_000)
        assertFalse(l.resting)
        l.tick(16_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(16_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)

        val f = newLogic()
        f.setCharging(true, 1_000)
        f.onMoved(16_000)
        f.onFall(17_000)
        f.tick(17_000)
        assertEquals(Mode.CHECKING, f.mode)
        assertEquals("fall", f.trigger)
    }

    @Test fun unplug_from_rest_waits_for_first_motion() {
        val l = newLogic()
        l.setCharging(true, 1_000)
        l.setCharging(false, 100_000)
        l.tick(100_000 + stillMs + 60_000)
        assertEquals(Mode.WATCHING, l.mode)
        assertTrue(l.resting)
        l.onMoved(400_000)
        assertFalse(l.resting)
        l.tick(400_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(400_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun unplug_while_carried_keeps_watching_and_replug_rests_again() {
        val l = newLogic()
        l.setCharging(true, 1_000)
        l.onMoved(20_000)
        l.setCharging(false, 30_000)
        assertFalse(l.resting)
        l.tick(20_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)

        val r = newLogic()
        r.setCharging(true, 1_000)
        r.onMoved(20_000)
        r.setCharging(false, 30_000)
        r.setCharging(true, 31_000)
        assertTrue(r.resting)
        r.tick(20_000 + stillMs + 60_000)
        assertEquals(Mode.WATCHING, r.mode)
    }

    @Test fun plugging_in_during_check_answers_it() {
        val l = newLogic()
        l.toChecking()
        l.setCharging(true, stillMs + 1_000)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(stillMs + responseMs + 10_000)
        assertEquals(Mode.WATCHING, l.mode)
    }

    @Test fun charging_keeps_sos() {
        val l = newLogic()
        l.toSos()
        l.setCharging(true, stillMs + responseMs + 1_000)
        assertEquals(Mode.SOS, l.mode)
        l.setCharging(false, stillMs + responseMs + 2_000)
        l.tick(stillMs + responseMs + 3_000)
        assertEquals(Mode.SOS, l.mode)
    }
}
