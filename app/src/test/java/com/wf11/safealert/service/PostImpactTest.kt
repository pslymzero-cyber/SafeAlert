package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.JudgeEvent
import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The after-impact judgment (1.2.28): the analyzer's result table on synthetic 50 Hz traces (impact at 3.2 s after a free
 * fall), and the whole line analyzer -> LoneWorkerLogic -> accident window wired in LoneWorkerSensors' order.
 */
class PostImpactTest {
    private val imp = 3_200L

    private fun wobble(t: Long, base: FloatArray): FloatArray {
        val k = if ((t / 20) % 2 == 0L) 1.05f else 0.95f
        return floatArrayOf(base[0] * k, base[1] * k, base[2] * k)
    }

    /** Walking: 2 Hz, 3 m/s^2 swing along gravity (upright). */
    private fun walking(t: Long) = floatArrayOf(0f, 0f, 9.81f + 3f * sin(2 * PI * 2 * (t - imp - 60) / 1000).toFloat())

    /** Upright until the free fall of ffMs, the impact sample at imp, then tail(t). */
    private fun trace(ffMs: Long = 200, tail: (Long) -> FloatArray): (Long) -> FloatArray = { t ->
        when {
            t < imp - ffMs -> UPRIGHT_STILL
            t < imp -> FREE_FALL_SAMPLE
            t == imp -> IMPACT_SAMPLE
            else -> tail(t)
        }
    }

    private fun judge(until: Long, f: (Long) -> FloatArray): Pair<List<MotionAnalyzer.Post>, List<Pair<Long, MotionAnalyzer.Signal>>> {
        val posts = ArrayList<MotionAnalyzer.Post>()
        val a = MotionAnalyzer(onPost = { posts += it })
        val signals = a.feed(0, until, f = f)
        return posts to signals
    }

    private fun name(p: MotionAnalyzer.Post) = "${p.out}@${p.atMs}"

    @Test fun analyzer_reports_one_judgment_per_impact() {
        // lying still at once: posture 90 degrees, 3-5 s still -> post5; the FALL signal is the same as before
        val (lying, sig) = judge(16_000, trace { LYING_STILL })
        assertEquals(listOf("post5@5000"), lying.map(::name))
        assertEquals(imp, lying[0].impT)
        assertTrue(lying[0].tiltDeg!! >= 30.0)
        assertEquals(0, lying[0].stdMg)
        assertTrue(lying[0].ffMs in 180L..220L)
        assertTrue(sig.any { it.second == MotionAnalyzer.Signal.FALL })

        // lying but never still (no step peaks): posture alone at 10 s
        assertEquals(listOf("post10@10000"), judge(14_000, trace { wobble(it, LYING_STILL) }).first.map(::name))
        // walking on: quiet end, no alert
        val (walk, _) = judge(10_000, trace { walking(it) })
        assertEquals(listOf("walk@5000"), walk.map(::name))
        assertTrue(walk[0].run >= 3)
        // upright and still without walking: no posture change, still at 10 s
        assertEquals(listOf("still10@10000"), judge(14_000, trace { UPRIGHT_STILL }).first.map(::name))
        // upright, moving about without steps: 30 s at the latest
        assertEquals(listOf("cap30@30000"), judge(35_000, trace { wobble(it, UPRIGHT_STILL) }).first.map(::name))
        // the phone alone fell (free fall 400 ms): no quick alert, the no-motion flow decides
        val (phone, _) = judge(10_000, trace(ffMs = 400) { LYING_STILL })
        assertEquals(listOf("phone@5000"), phone.map(::name))
        assertTrue(phone[0].ffMs >= 300L)
        // a second impact replaces the running judgment (only one result, for the second impact)
        val (second, _) = judge(13_000) { t ->
            when {
                t <= imp -> trace { UPRIGHT_STILL }(t)
                t < 6_000 -> UPRIGHT_STILL
                t < 6_200 -> FREE_FALL_SAMPLE
                t == 6_200L -> IMPACT_SAMPLE
                else -> LYING_STILL
            }
        }
        assertEquals(listOf("post5@5000"), second.map(::name))
        assertEquals(6_200L, second[0].impT)
    }

    /** The analyzer and the logic wired like LoneWorkerSensors (no time conversion); callbacks end with sensorEventEnd and a due tick. */
    private class Rig(val logic: LoneWorkerLogic) {
        val events = ArrayList<JudgeEvent>()
        val analyzer = MotionAnalyzer(
            onPost = { p -> logic.onPost(p.impT, p.out, p.impT + p.atMs, MotionAnalyzer.FallShape(p.ffMs, p.peakG, p.tiltDeg)) },
            onWindow = { logic.onWindow(it) })
        var now = 0L

        init { logic.stepsAvailable = false; logic.onJudge = { events += it } }

        /** One sensor callback delivering samples from..to (20 ms apart). */
        fun callback(from: Long, to: Long, f: (Long) -> FloatArray) {
            var t = from
            while (t < to) {
                val v = f(t)
                when (analyzer.add(t, v[0], v[1], v[2])) {
                    MotionAnalyzer.Signal.MOVED -> logic.onMoved(t, analyzer.lastActiveMs)
                    MotionAnalyzer.Signal.FALL -> logic.onAccident(analyzer.eventMs, analyzer.fallShape, byPost = true)
                    MotionAnalyzer.Signal.IMPACT -> logic.onAccident(analyzer.eventMs, analyzer.fallShape, pass = false, byPost = true)
                    MotionAnalyzer.Signal.NONE -> {}
                }
                now = t
                t += 20
            }
            logic.sensorEventEnd(now)
            if (logic.dueNow(now)) logic.tick(now)
        }

        fun seconds(from: Long, to: Long, f: (Long) -> FloatArray) {
            var t = from
            while (t < to) { callback(t, minOf(t + 1_000, to), f); t += 1_000 }
        }

        val checks get() = events.filterIsInstance<JudgeEvent.ModeChange>().filter { it.to == Mode.CHECKING }
        val falls get() = events.filterIsInstance<JudgeEvent.Fall>()
    }

    @Test fun judgment_opens_the_window_once_before_the_deadline_judgment() {
        val lying = trace { LYING_STILL }
        // 1 s callbacks: the 5 s alert (deadline 8.2 s) is applied before the callback that closes its 1 s window judges it
        val a = Rig(fallRuleOnly())
        a.seconds(0, 9_000, lying)
        assertEquals(Mode.WATCHING, a.logic.mode)
        a.seconds(9_000, 16_000, lying)
        assertEquals(Mode.CHECKING, a.logic.mode)
        assertEquals("fall", a.logic.trigger)
        assertFalse(a.logic.dropWindow)
        assertTrue(a.logic.modeSinceMs in 8_200L..10_200L)
        // the 12 s decision (FALL, 15.2 s) lands inside the window of the same impact: shut, and it does not ask
        assertEquals(listOf("shut"), a.falls.map { it.take })
        assertFalse(a.falls.single().asks)
        assertEquals(1, a.checks.size)
        assertTrue(a.logic.ackWorking(a.now))
        assertEquals(1, a.events.filterIsInstance<JudgeEvent.ModeChange>().count { it.asksFall })
        assertEquals(imp, a.events.filterIsInstance<JudgeEvent.ModeChange>().single { it.asksFall }.impT)

        // everything in one callback: the window opens at the end of that callback, the FALL took the watch first
        val b = Rig(fallRuleOnly())
        b.callback(0, 16_000, lying)
        assertEquals(Mode.CHECKING, b.logic.mode)
        assertEquals(listOf("watch"), b.falls.map { it.take })
        assertEquals(1, b.checks.size)
        assertTrue(b.logic.ackWorking(b.now))
        assertEquals(imp, b.events.filterIsInstance<JudgeEvent.ModeChange>().single { it.asksFall }.impT)
    }

    @Test fun walking_opens_nothing_and_a_phone_drop_goes_to_the_no_motion_flow() {
        // walks on after the impact, then stands still: 1.2.27 opened '폰을 떨어뜨렸나요?' within a minute; now nothing for 5 min
        val walked = Rig(fallRuleOnly())
        walked.seconds(0, 15_000, trace { walking(it) })
        walked.seconds(15_000, 310_000) { UPRIGHT_STILL }
        assertEquals(Mode.WATCHING, walked.logic.mode)
        assertTrue(walked.checks.isEmpty())

        // the phone alone fell (free fall 400 ms) and lies still, no-motion limit 60 s: no 'fall' window, the 'still' one opens
        val dropped = Rig(newLogic(carried = true).apply { stillMs = 60_000L })
        dropped.seconds(0, 80_000, trace(ffMs = 400) { LYING_STILL })
        assertEquals(listOf("still"), dropped.checks.map { it.trig })
        assertTrue(dropped.logic.modeSinceMs in 60_000L..75_000L)
        assertEquals(Mode.CHECKING, dropped.logic.mode)
    }

    @Test fun mounted_phone_keeps_the_30_second_rule() {
        val mounted = Rig(newLogic(charging = true).apply { stillMs = 3_600_000L; setEquipment(true, 0) })
        mounted.seconds(0, 30_000, trace { LYING_STILL })
        assertEquals(Mode.WATCHING, mounted.logic.mode) // no 5 s / 10 s window
        mounted.seconds(30_000, 40_000, trace { LYING_STILL })
        assertEquals(Mode.CHECKING, mounted.logic.mode)
        assertEquals("fall", mounted.logic.trigger)
        assertTrue(mounted.logic.modeSinceMs >= imp + 30_000L)
        assertNotEquals(0, mounted.falls.size)
    }
}
