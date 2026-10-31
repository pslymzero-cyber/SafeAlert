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
 * The after-impact judgment: the analyzer's result table on synthetic 50 Hz traces (impact at 3.2 s after a free
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

    private val lift2 = ArrayList<MotionAnalyzer.Post>()

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
        // walking, then lying down still (here the walk is seen at 5 s, the watch goes on): a second result for the same impact
        val (down, _) = judge(14_000) { t ->
            when {
                t <= imp -> trace { UPRIGHT_STILL }(t)
                t < 8_200 -> walking(t)
                else -> LYING_STILL
            }
        }
        assertEquals(listOf("walk@5000", "walkstill@7000"), down.map(::name))
        assertEquals(listOf(imp, imp), down.map { it.impT })
        // upright and still without walking: no posture change, still at 10 s
        assertEquals(listOf("still10@10000"), judge(14_000, trace { UPRIGHT_STILL }).first.map(::name))
        // upright, moving about without steps: 30 s at the latest
        assertEquals(listOf("cap30@30000"), judge(35_000, trace { wobble(it, UPRIGHT_STILL) }).first.map(::name))
        // a long free fall (400 ms: a dropped phone, or a person from a height) has no quick alert: only a sign of someone with
        // the phone ends it, otherwise the alert comes at 30 s
        val (phone, _) = judge(36_000, trace(ffMs = 400) { LYING_STILL })
        assertEquals(listOf("phone30@30000"), phone.map(::name))
        assertTrue(phone[0].ffMs >= 300L)
        // sign 1: walking
        assertEquals(listOf("walk@5000"), judge(10_000, trace(ffMs = 400) { walking(it) }).first.map(::name))
        // sign 2: the phone is lifted 0.5 m or more (a still second, then a 1.5 s pull of 0.5 G)
        val (lift, _) = judge(10_000, trace(ffMs = 400) { t -> if (t in 6_000L until 6_500L) floatArrayOf(9.81f * 1.5f, 0f, 0f) else LYING_STILL })
        assertEquals(listOf("lift@5000"), lift.map(::name))
        // sign 3: the screen is unlocked while the phone was moving (not when it lay still); the alert stays for the still phone
        val moving = MotionAnalyzer(onPost = { lift2 += it })
        moving.feed(0, 6_000, f = trace(ffMs = 400) { wobble(it, LYING_STILL) })
        moving.onScreen()
        assertEquals(listOf("screen@2780"), lift2.map(::name))
        lift2.clear()
        val lying2 = MotionAnalyzer(onPost = { lift2 += it })
        lying2.feed(0, 6_000, f = trace(ffMs = 400) { LYING_STILL })
        lying2.onScreen()
        assertTrue(lift2.isEmpty())
        // a person falling after a phone-only drop still gets the quick alert: the new impact takes over
        val (afterDrop, _) = judge(12_500) { t ->
            when {
                t <= imp -> trace(ffMs = 400) { UPRIGHT_STILL }(t)
                t < 6_000 -> UPRIGHT_STILL
                t < 6_200 -> FREE_FALL_SAMPLE
                t == 6_200L -> IMPACT_SAMPLE
                else -> LYING_STILL
            }
        }
        assertEquals(listOf("post5@5000"), afterDrop.map(::name))
        assertEquals(6_200L, afterDrop[0].impT)
        // an impact while a (short free fall) judgment runs is kept: the running one ends quietly (superseded) and the kept one is
        // judged at once from the stored samples, the checks already past included (here: walking starts at 6.7 s, the first
        // judgment sees it at 6 s = 9.6 s, the second impact at 4.2 s is 5.4 s old then)
        val (chain, _) = judge(10_500) { t ->
            when {
                t <= imp -> trace { UPRIGHT_STILL }(t)
                t < 4_000 -> UPRIGHT_STILL
                t < 4_200 -> FREE_FALL_SAMPLE
                t == 4_200L -> IMPACT_SAMPLE
                t < 6_700 -> UPRIGHT_STILL
                else -> floatArrayOf(0f, 0f, 9.81f + 3f * sin(2 * PI * 2 * (t - 6_700) / 1000).toFloat())
            }
        }
        assertEquals(listOf("walk@6000", "walk@5000"), chain.map(::name))
        assertEquals(listOf(imp, 4_200L), chain.map { it.impT })
        assertEquals(listOf(true, false), chain.map { it.superseded })
        // a second impact while the judgment runs starts nothing: the first impact's judgment (its own posture base) goes on
        val (second, _) = judge(14_000) { t ->
            when {
                t <= imp -> trace { UPRIGHT_STILL }(t)
                t < 6_000 -> UPRIGHT_STILL
                t < 6_200 -> FREE_FALL_SAMPLE
                t == 6_200L -> IMPACT_SAMPLE
                else -> LYING_STILL
            }
        }
        assertEquals(listOf("post10@10000"), second.map(::name)) // the 6.2 s spike is inside its 3-5 s window, so 10 s by posture
        assertEquals(imp, second[0].impT)
        // an impact after the first judgment has ended starts a new one (posture base = the lying posture before it)
        val (later, _) = judge(20_000) { t ->
            when {
                t <= imp -> trace { UPRIGHT_STILL }(t)
                t < 8_800 -> LYING_STILL
                t < 9_000 -> FREE_FALL_SAMPLE
                t == 9_000L -> IMPACT_SAMPLE
                else -> LYING_STILL
            }
        }
        assertEquals(listOf("post5@5000", "still10@10000"), later.map(::name))
        assertEquals(listOf(imp, 9_000L), later.map { it.impT })
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
        assertEquals(listOf("watch"), a.falls.map { it.take }) // the impact that opened the window is recorded as the watch it is
        assertFalse(a.falls.single().asks)
        assertEquals(1, a.checks.size)
        assertTrue(a.logic.ackWorking(a.now))
        assertEquals(1, a.events.filterIsInstance<JudgeEvent.ModeChange>().count { it.asksFall })
        assertEquals(imp, a.events.filterIsInstance<JudgeEvent.ModeChange>().single { it.asksFall }.impT)

        // answered '괜찮아요' before its 12 s decision: that decision starts no watch and asks nothing again
        val ok = Rig(fallRuleOnly())
        ok.seconds(0, 12_000, lying)
        assertEquals(Mode.CHECKING, ok.logic.mode)
        assertTrue(ok.logic.ackWorking(ok.now))
        ok.seconds(12_000, 70_000, lying)
        assertEquals(Mode.WATCHING, ok.logic.mode)
        assertEquals(1, ok.checks.size)
        assertEquals(listOf("watch"), ok.falls.map { it.take })
        assertFalse(ok.falls.single().asks)
        assertEquals(1, ok.events.filterIsInstance<JudgeEvent.ModeChange>().count { it.asksFall })

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

        // a long free fall and lying still: no sign of someone, so the 'fall' window opens at 30 s (alert deadline 33.2 s), not the no-motion one
        val dropped = Rig(newLogic(carried = true).apply { stillMs = 3_600_000L })
        dropped.seconds(0, 40_000, trace(ffMs = 400) { LYING_STILL })
        assertEquals(listOf("fall"), dropped.checks.map { it.trig })
        assertTrue(dropped.logic.modeSinceMs in 33_200L..36_000L)
        assertEquals(Mode.CHECKING, dropped.logic.mode)
        assertFalse(dropped.logic.dropWindow)

        // the safety net: the watch of an impact whose judgment never reported opens at impact + 32 s; a sign of someone with the phone
        // switches it off (a superseded end does not); an alert holds it as its own deadline, and once that window has been closed by walking
        // the 30 s rule re-arms (a second fall after the window opens one again)
        val shape = MotionAnalyzer.FallShape(200, 3.0, 90.0)
        val net = fallRuleOnly().apply { onAccident(6_000, shape, byPost = true) }
        assertEquals(Mode.WATCHING, net.seenAt(37_000))
        assertEquals(Mode.CHECKING, net.seenAt(38_000))
        val sure = fallRuleOnly().apply { onAccident(6_000, shape, byPost = true); onPost(6_000, "lift", 11_000, shape) }
        assertEquals(Mode.WATCHING, sure.seenAt(60_000))
        val sup = fallRuleOnly().apply { onAccident(6_000, shape, byPost = true); onPost(6_000, "walk", 11_000, shape, superseded = true) }
        assertEquals(Mode.CHECKING, sup.seenAt(38_000))
        val again = fallRuleOnly().apply { onAccident(6_000, shape, byPost = true); onPost(6_000, "post5", 11_000, shape) }
        assertEquals(Mode.CHECKING, again.seenAt(12_000))
        again.walk(20_000, 6) // clear movement closes the window; the 30 s rule counts from it
        assertEquals(Mode.WATCHING, again.seenAt(21_000))
        assertEquals(Mode.WATCHING, again.seenAt(49_000))
        assertEquals(Mode.CHECKING, again.seenAt(51_000))

        // the safe zone is looked at again once its state at the impact is final (12 s): leaving within 12 s lifts the exclusion
        val weak = MotionAnalyzer.FallShape(200, 3.0, 40.0)
        val left = fallRuleOnly(zoneInside = true).apply { onPost(6_000, "post5", 11_000, weak); onZone(false, 9_000) }
        assertEquals(Mode.WATCHING, left.seenAt(17_000))
        assertEquals(Mode.CHECKING, left.seenAt(19_000))
        val stayed = fallRuleOnly(zoneInside = true).apply { onPost(6_000, "post5", 11_000, weak) }
        assertEquals(Mode.WATCHING, stayed.seenAt(19_000))
        assertEquals(Mode.WATCHING, stayed.seenAt(60_000))
        // one verdict per impact: the 12 s decision's (counts: the held alert goes on, at 12 s after the impact at the earliest;
        // ignored: it is dropped, and a later alert of the same impact follows it even with a shape that would pass)
        val strong = MotionAnalyzer.FallShape(250, 3.0, 90.0)
        val counts = fallRuleOnly(zoneInside = true).apply { onPost(6_000, "post5", 11_000, weak); onAccident(6_000, strong, byPost = true) }
        assertEquals(Mode.WATCHING, counts.seenAt(17_000))
        assertEquals(Mode.CHECKING, counts.seenAt(19_000))
        val ignored = fallRuleOnly(zoneInside = true).apply { onPost(6_000, "post5", 11_000, weak); onAccident(6_000, weak, byPost = true) }
        assertEquals(Mode.WATCHING, ignored.seenAt(60_000))
        ignored.onPost(6_000, "phone30", 36_000, strong)
        assertEquals(Mode.WATCHING, ignored.seenAt(70_000))
        // walking ended it quietly, then lying still in a changed posture 'walkstill' comes for the same impact: a window at that time
        val later = fallRuleOnly().apply { onPost(6_000, "walk", 11_000, shape); onPost(6_000, "walkstill", 14_000, shape) }
        assertEquals(Mode.WATCHING, later.seenAt(13_000))
        assertEquals(Mode.CHECKING, later.seenAt(15_000))
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
