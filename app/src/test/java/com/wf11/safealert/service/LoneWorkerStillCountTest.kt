package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Rule 2 (still) counting: the count pauses while a peer siren vibrates on this device for at most
 * stillMs per vibration (a device without a vibrator never pauses), is not kept
 * inside a settled safe zone, and the still check opens on sensor time like the accident deadline.
 * A suspect device (accident suspicion running) does not vibrate for a peer siren.
 * A deadline waiting for a power change: LoneWorkerPowerWaitTest.
 */
class LoneWorkerStillCountTest {

    private val stillMs = 180_000L

    /**
     * A peer siren from 60 s pauses the still count while it vibrates here; the count resumes where it stopped when the
     * siren ends (by [OK] or by the peer's resolve). A device that cannot vibrate never pauses.
     */
    @Test fun peer_siren_pauses_still_count_and_resumes_after() {
        val l = newLogic(carried = true)
        l.peerSiren(60_000L, 230_000L)
        assertTrue(l.alarmVibrates)
        l.ackAll(230_000)
        assertFalse(l.alarmVibrates)
        // 60 s were still before the siren, 120 s remain after it
        assertEquals("ack end", Mode.WATCHING, l.seenAt(230_000))
        assertEquals("ack end", Mode.WATCHING, l.seenAt(349_000))
        assertEquals("ack end", Mode.CHECKING, l.seenAt(350_000))
        assertEquals("ack end", "still", l.trigger)

        val r = newLogic(carried = true)
        r.peerSiren(60_000L, 200_000L)
        r.onPeerBle("P", false, 200_000)
        r.tick(210_000)
        assertTrue("resolve end", r.audiblePeers().isEmpty())
        assertEquals("resolve end", Mode.WATCHING, r.seenAt(210_000))
        assertEquals("resolve end", Mode.WATCHING, r.seenAt(329_000))
        assertEquals("resolve end", Mode.CHECKING, r.seenAt(330_000))

        val n = newLogic(carried = true).apply { canVibrate = false }
        n.peerSiren(60_000L, 179_000L)
        assertFalse("no vibrator", n.alarmVibrates)
        assertEquals("no vibrator", 1, n.audiblePeers().size)
        assertEquals("no vibrator", Mode.CHECKING, n.seenAt(180_000))
    }

    @Test fun siren_pause_starts_again_for_a_new_siren() {
        val l = newLogic(carried = true)
        l.peerSiren(60_000L, 250_000L)
        // capped at 240 s: base 180 s; the vibration stops, so the next siren pauses again
        l.ackAll(250_000)
        assertEquals(Mode.WATCHING, l.seenAt(250_000))
        l.peerSiren(260_000L, 400_000L, "Q")
        l.ackAll(400_000)
        // 80 s before Q, 100 s after it
        assertEquals(Mode.WATCHING, l.seenAt(400_000))
        assertEquals(Mode.WATCHING, l.seenAt(499_000))
        assertEquals(Mode.CHECKING, l.seenAt(500_000))

        // the pause ends after stillMs: it ran 60 s .. 240 s and ended there, 60 s before it, 120 s after it
        val cap = newLogic(carried = true)
        cap.peerSiren(60_000L, 359_000L)
        assertTrue(cap.alarmVibrates)
        cap.onPeerBle("P", true, 360_000)
        assertEquals(Mode.CHECKING, cap.seenAt(360_000))
        assertEquals("still", cap.trigger)
    }

    @Test fun movement_during_siren_counts_from_siren_end() {
        val l = newLogic(carried = true)
        for (t in 60_000L..200_000L step 1_000L) {
            l.onPeerBle("P", true, t)
            if (t == 100_000L) l.onMoved(t)
            assertEquals(Mode.WATCHING, l.seenAt(t))
        }
        l.ackAll(200_000)
        assertEquals(Mode.WATCHING, l.seenAt(200_000))
        assertEquals(Mode.WATCHING, l.seenAt(379_000))
        assertEquals(Mode.CHECKING, l.seenAt(380_000))
    }

    @Test fun suspect_device_does_not_vibrate_for_peer_siren() {
        val l = newLogic(carried = true)
        l.onAccident(10_000)
        l.onPeerBle("P", true, 11_000)
        assertEquals(Mode.WATCHING, l.modeAt(11_000))
        assertEquals(1, l.audiblePeers().size)
        assertFalse(l.alarmVibrates)
        // a real plug ends the suspicion: the siren vibrates again
        l.reportPower(true, 12_000)
        assertEquals(Mode.WATCHING, l.modeAt(12_000 + PowerDebounce.CONFIRM_MS))
        assertTrue(l.alarmVibrates)

        // a device with its own check open (still check, then SOS) does not vibrate for a peer siren either
        val own = newLogic(carried = true)
        assertFalse(own.alarmVibrates)
        own.seenAt(stillMs)
        assertEquals(Mode.CHECKING, own.mode)
        assertFalse(own.alarmVibrates)
        own.onPeerBle("P", true, stillMs + 1)
        assertFalse(own.alarmVibrates)
        own.seenAt(stillMs + 120_000L)
        assertEquals(Mode.SOS, own.mode)
        assertFalse(own.alarmVibrates)

        // a device with nothing of its own open vibrates
        val r = newLogic(carried = true)
        r.onPeerBle("P", true, 1_000)
        assertEquals(1, r.audiblePeers().size)
        assertTrue(r.alarmVibrates)
    }

    @Test fun settled_zone_does_not_count_still() {
        val l = newLogic(zoneInside = true, carried = true)
        l.tick(60_000)
        assertTrue(l.zoneSettled)
        for (t in 60_000L..600_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
        l.onZone(false, 600_000)
        assertEquals(Mode.WATCHING, l.seenAt(779_000))
        assertEquals(Mode.CHECKING, l.seenAt(780_000))

        // an equipment device on the charger counts nothing in a settled zone either
        val m = newLogic(charging = true, zoneInside = true).apply { setEquipment(true, 0L) }
        for (t in 60_000L..600_000L step 10_000L) assertEquals("mounted at $t", Mode.WATCHING, m.seenAt(t))
    }

    /** The still check opens when the data covers 180 s (dueNow says so once), or LATE_MS after it. */
    @Test fun still_check_waits_for_sensor_data() {
        val l = newLogic(carried = true)
        l.sensed(178_000)
        assertEquals(Mode.WATCHING, l.modeAt(180_000))
        assertTrue(l.waitingOnSensors(180_000))
        assertFalse(l.dueNow(179_999))
        assertFalse(l.dueNow(180_000))
        l.sensed(180_000)
        assertTrue(l.dueNow(180_000))
        assertEquals(Mode.CHECKING, l.modeAt(180_000))
        assertFalse(l.dueNow(180_000))

        val silent = newLogic(carried = true)
        silent.sensed(178_000)
        assertEquals(Mode.WATCHING, silent.modeAt(185_999))
        assertEquals(Mode.CHECKING, silent.modeAt(186_000))
    }

    @Test fun late_movement_before_still_deadline_keeps_watching() {
        val l = newLogic(carried = true)
        l.sensed(178_000)
        assertEquals(Mode.WATCHING, l.modeAt(180_000))
        assertEquals(0L, l.countdown(150_000)!!.buzzKey)
        l.onMoved(179_500)
        l.sensed(181_000)
        assertEquals(Mode.WATCHING, l.modeAt(181_000))
        // the movement reset the count: the last minute starts over with a new buzz key
        assertEquals(179_500L, l.countdown(300_000)!!.buzzKey)
        assertEquals(Mode.WATCHING, l.seenAt(359_499))
        assertEquals(Mode.CHECKING, l.seenAt(359_500))
    }

    @Test fun still_deadline_is_scheduled() {
        assertEquals(180_000L, newLogic(carried = true).nextCheckAt(0))

        // the last minute: shown from 120 s, one buzz key, a wake-up cue that is not a judgment deadline
        val c = newLogic(carried = true)
        assertNull(c.countdown(0))
        assertEquals(120_000L, c.stillCueAt())
        assertEquals(120_000L, c.nextCueAt(0))
        assertEquals(LoneWorkerLogic.Countdown(LoneWorkerLogic.CountdownKind.STILL, 60_000L, 0L), c.countdown(120_000))
        assertEquals(LoneWorkerLogic.Countdown(LoneWorkerLogic.CountdownKind.STILL, 30_000L, 0L), c.countdown(150_000))
        assertFalse(c.dueNow(120_000))
        // a setting of 1 min or less shows the text from the start but never buzzes
        val one = newLogic(carried = true).apply { stillMs = 60_000L }
        assertEquals(LoneWorkerLogic.Countdown(LoneWorkerLogic.CountdownKind.STILL, 60_000L, null), one.countdown(0))
        assertNull(one.stillCueAt())

        val settled = newLogic(zoneInside = true, carried = true)
        settled.tick(60_000)
        assertNull(settled.nextCheckAt(60_000))
        assertNull(settled.countdown(150_000))
        assertNull(settled.stillCueAt())

        val siren = newLogic(carried = true)
        siren.onPeerBle("P", true, 1_000)
        siren.tick(1_000)
        assertNull(siren.nextCheckAt(1_000))
        assertNull(siren.countdown(150_000))
        assertNull(siren.stillCueAt())

        assertNull(newLogic().nextCheckAt(0))
        assertNull(newLogic().countdown(150_000))
        assertNull(newLogic().stillCueAt())
    }

    /** Status line texts (seconds rounded up) and the monitor wiring: banner order, buzz gated by the vibration switch. */
    @Test fun countdown_banner_text_and_wiring() {
        fun text(kind: LoneWorkerLogic.CountdownKind, left: Long) = countdownBanner(LoneWorkerLogic.Countdown(kind, left, null))
        val k = LoneWorkerLogic.CountdownKind.values()
        assertEquals("구조 요청 중 — 해제는 구조 요청 화면에서", text(k[0], 0))
        assertEquals("'괜찮으세요?' 응답 대기 — 60초 뒤 구조 요청", text(k[1], 59_001))
        assertEquals("넘어짐 감지 — 1초 동안 움직임이 없으면 '괜찮으세요?' 창", text(k[2], 1))
        assertEquals("움직임 없음 — 0초 뒤 '괜찮으세요?' 창 (움직이면 취소)", text(k[3], 0))
        assertEquals("움직임 없음 — 30초 뒤 '괜찮으세요?' 창 (움직이면 취소)", text(k[3], 30_000))

        val monitor = noComments(serviceSource("LoneWorkerMonitor.kt"))
        val banner = monitor.substring(monitor.indexOf("val banner"), monitor.indexOf("private var buzzedFall"))
        assertTrue(banner.indexOf("sensors.noSensor") < banner.indexOf("countdownBanner(it)"))
        assertTrue(banner.indexOf("countdownBanner(it)") < banner.indexOf("logic.rest.banner"))
        val render = sourceBlock(monitor, "private fun renderOnce()")
        assertTrue(render.indexOf("DevSettings.vibrationEnabled") in 0 until render.indexOf("VibrationHelper.vibrateCountdown"))
        assertTrue(noComments(serviceSource("LoneWorkerWakeLock.kt")).contains("stillCueAt()"))
        // the fall candidate: one countdown call (the screen and the buzz share it), redrawn on a candidate change before the
        // 1 s throttle, same time conversion as the fall passed to onAccident (same buzz key)
        assertEquals(1, Regex("""logic\.countdown\(""").findAll(monitor).count())
        assertTrue(monitor.contains("logic.countdown(t, sensors.pendingImpactAt, sensors.pendingShape)"))
        val sensorEvent = sourceBlock(monitor, "private fun onSensorEvent(")
        assertTrue(sensorEvent.indexOf("sensors.pendingImpactAt") in 0 until sensorEvent.indexOf("TICK_MIN_MS"))
        val sensors = noComments(serviceSource("LoneWorkerSensors.kt"))
        assertTrue(sensors.contains("analyzer.pendingImpactMs?.plus(accelSkew)"))
        assertTrue(sensors.contains("analyzer.eventMs + accelSkew"))
    }

    /**
     * A still deadline that passed before the siren started is still judged once sensor data covers it, whether the
     * vibration is still going when the data comes (ackBeforeData false) or stopped before it (true: the deadline
     * keeps its base).
     */
    @Test fun siren_stopping_before_data_keeps_a_passed_still_deadline() {
        for (ackBeforeData in listOf(false, true)) {
            val m = "ackBeforeData=$ackBeforeData"
            val l = newLogic(carried = true)
            l.sensed(178_000)
            assertEquals(m, Mode.WATCHING, l.modeAt(180_000))
            assertTrue(m, l.waitingOnSensors(180_000))
            l.onPeerBle("P", true, 181_000)
            assertEquals(m, Mode.WATCHING, l.modeAt(181_000))
            if (!ackBeforeData) {
                assertTrue(m, l.alarmVibrates)
                assertTrue(m, l.waitingOnSensors(181_000))
                l.sensed(181_000)
                assertEquals(m, Mode.CHECKING, l.modeAt(181_000))
                assertEquals(m, "still", l.trigger)
            } else {
                assertEquals(m, 0L, l.snapshot(181_500).stillBase)
                // the siren is acknowledged before the data comes: the vibration stops
                l.ackAll(182_000)
                assertEquals(m, Mode.WATCHING, l.modeAt(182_000))
                assertFalse(m, l.alarmVibrates)
                // one batch: data covering the deadline, then a motion after it
                l.sensed(180_000)
                assertTrue(m, l.dueNow(182_000))
                assertEquals(m, Mode.CHECKING, l.modeAt(182_000))
                l.onMoved(180_500)
                l.sensed(181_000)
                assertEquals(m, Mode.CHECKING, l.modeAt(182_000))
                assertEquals(m, "still", l.trigger)
            }
        }
    }

    /**
     * A still check closes on [OK] or on distinct motion (5 steps), not on a MOVED signal; a fall check does not close
     * on a MOVED signal either.
     */
    @Test fun still_check_closed_by_ok_or_distinct_motion_not_by_moved() {
        val moved = newLogic()
        moved.sensorSilent(0)
        moved.seenAt(stillMs)
        moved.onMoved(stillMs + 1_000)
        moved.walk(stillMs + 5_000, 4)
        assertEquals(Mode.CHECKING, moved.modeAt(stillMs + 6_000))

        val fidget = newLogic(carried = true).also { it.stillMs = 3_600_000L }
        fidget.onAccident(10_000)
        assertEquals(Mode.CHECKING, fidget.seenAt(40_000))
        fidget.onMoved(45_000)
        assertEquals(Mode.CHECKING, fidget.seenAt(50_000))
        assertEquals("fall", fidget.trigger)

        val walked = newLogic()
        walked.sensorSilent(0)
        walked.seenAt(stillMs)
        walked.walk(stillMs + 5_000, 5)
        assertEquals(Mode.WATCHING, walked.mode)
        assertEquals(Mode.WATCHING, walked.seenAt(stillMs + 5_000 + stillMs - 1))
        assertEquals(Mode.CHECKING, walked.seenAt(stillMs + 5_000 + stillMs))

        // [OK] returns to watching and restarts the still count
        val ok = newLogic()
        ok.sensorSilent(0)
        ok.seenAt(stillMs)
        assertTrue(ok.ackWorking(200_000))
        assertEquals(Mode.WATCHING, ok.mode)
        ok.seenAt(200_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, ok.mode)
        ok.seenAt(200_000 + stillMs)
        assertEquals(Mode.CHECKING, ok.mode)
    }
}
