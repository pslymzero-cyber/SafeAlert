package com.wf11.safealert.ble

import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness as H
import com.wf11.safealert.support.BleServiceTestHarness.NOISE_6
import com.wf11.safealert.support.BleServiceTestHarness.asmOf
import com.wf11.safealert.support.BleServiceTestHarness.fieldOf
import com.wf11.safealert.support.BleServiceTestHarness.forkliftPayload
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Reverse/loading special alerts and the Time-Gate that confirms a first detection.
 *
 * First detection (a forklift first heard while already reversing):
 *  - it never alerts before an IDLE forklift on the same signal;
 *  - it still gets its special alert, but only through a confirmation: a waiver, a 2-frame contact streak while not
 *    departing, or a sustained Time-Gate approach;
 *  - never while it is departing, and never while the peer declares IN_ZONE.
 * Already alerting: switching to reverse or loading gets the special alert on that same frame.
 * Reverse never delays the TTC pre-alert, DANGER or the cooldown re-alarm compared with IDLE, and a fast-approaching
 * reversing forklift is alerted before it passes its closest point.
 * Time-Gate (evalTimeGate): non-approach gaps of up to 300 ms keep the approach streak without delaying confirmation;
 * two fast-approach frames in a row pass it early.
 *
 * The simulation tests print measurements as "[ReverseSpecial] <scenario> key=value" and collect their failures to
 * assert at the end, so every measurement prints even when one fails. State is read through reflection that fails the
 * test when a field is missing (no fallbacks), and a scenario whose premise does not hold fails instead of passing.
 * Sources: the special-alert block comment and the evalTimeGate comment in AlertStateMachine.kt.
 */
@RunWith(RobolectricTestRunner::class)
class SpecialAlertTimeGateTest {

    private val id = "SA-TEST-01"
    private val dt = 120L
    private val t0 = 1_000L
    private val REV = BleConstants.PSTATE_REVERSE
    private val IDLE = BleConstants.PSTATE_IDLE
    private val DANGER = BleConstants.LEVEL_DANGER

    /** Five ways a forklift can first come into range, shared by the first-detection tests. */
    private val firstDetectionApproaches: List<Pair<String, (Int) -> Int>> = listOf(
        "slow" to { f -> minOf(-90 + f / 2, -40) },
        "fast" to { f -> minOf(-90 + f * 2, -40) },
        "jitter" to { f -> minOf(-90 + f, -40) + NOISE_6[f % NOISE_6.size] },
        "step" to { _ -> -45 },
        "weakThenStep" to { f -> if (f < 10) -90 else -45 },
    )

    private fun out(s: String) = println("[ReverseSpecial] $s")
    private fun labels(s: BleService) = fieldOf<MutableMap<String, String>>(s, "suddenLabelMap")
    private fun logCount(key: String) = ShadowLog.getLogs().count { it.msg?.contains(key) == true }

    /** One frame as observed right after processAlert. */
    data class Fr(
        val f: Int, val t: Long, val rssi: Int, val state: Int, val before: Int?, val after: Int?,
        val label: Boolean, val specialFire: Boolean, val ttc: Boolean, val bc: Int,
        val ds: Int?, val ws: Int?, val trk: String?, val vel: Double?, val streakStart: Long?, val fast: Any?,
    ) {
        fun fmt() = "f=$f t=$t r=$rssi st=$state lv=$before>$after lab=$label spFire=$specialFire ttc=$ttc bc+$bc " +
            "ds=$ds ws=$ws trk=$trk vel=${vel?.let { "%.2f".format(it) }} asStart=$streakStart fast=$fast"
    }

    private fun step(s: BleService, f: Int, t: Long, rssi: Int, state: Int, peerInZone: Boolean = false, waive: Boolean = false): Fr {
        if (peerInZone) fieldOf<MutableMap<String, Boolean>>(s, "peerInZoneMap")[id] = true
        if (waive) fieldOf<MutableSet<String>>(s, "timeGateWaiveSet").add(id)
        val before = H.alertLevelOf(s, id)
        val bc0 = H.alertBroadcasts().size
        val sp0 = logCount("특수경보(STATE=")
        val ttc0 = logCount("TTC 선발령")
        H.callProcessAlert(s, id, rssi, remoteState = forkliftPayload(state), payloadPresent = true, nowMs = t)
        return Fr(
            f, t, rssi, state, before, H.alertLevelOf(s, id),
            labels(s).containsKey(id), logCount("특수경보(STATE=") > sp0, logCount("TTC 선발령") > ttc0,
            H.alertBroadcasts().size - bc0,
            fieldOf<Map<String, Int>>(s, "dangerContactStreakMap")[id],
            fieldOf<Map<String, Int>>(s, "warningContactStreakMap")[id],
            fieldOf<Map<String, Any>>(s, "trackingStateMap")[id]?.toString(),
            fieldOf<Map<String, KalmanFilter>>(s, "kalmanFilters")[id]?.estimatedVel,
            fieldOf<Map<String, Long>>(s, "approachStreakStartMap")[id],
            fieldOf<Map<String, Any>>(asmOf(s), "fastApproachStreakMap")[id],
        )
    }

    private fun runSeq(
        state: Int, rssiAt: (Int) -> Int, frames: Int, peerInZone: Boolean = false,
        s: BleService = H.newService(), startF: Int = 0, startT: Long = t0,
    ): List<Fr> = (0 until frames).map { i ->
        step(s, startF + i, startT + i * dt, rssiAt(i), state, peerInZone)
    }

    /** First alert, first special alert, first DANGER, first broadcast, first TTC pre-alert, and the closest point (CPA). */
    private data class Sum(val alert: Fr?, val special: Fr?, val danger: Fr?, val bc: Fr?, val ttc: Fr?, val cpa: Fr)
    private fun sum(tr: List<Fr>) = Sum(
        tr.firstOrNull { it.after != null }, tr.firstOrNull { it.label || it.specialFire },
        tr.firstOrNull { it.after == DANGER }, tr.firstOrNull { it.bc > 0 }, tr.firstOrNull { it.ttc },
        tr.maxByOrNull { it.rssi }!!,
    )

    /**
     * Infers after the fact which confirmation path held on the firing frame (the gate call and the firing
     * happen in the same processAlert call, so the confirm→fire delay is structurally 0ms).
     */
    private fun route(fr: Fr): String = when {
        fr.before != null -> "already-alerted"
        (fr.ds ?: 0) >= 2 || (fr.ws ?: 0) >= 2 -> "2frame"
        (fr.fast as? Int ?: 0) >= 2 || (fr.streakStart != null && fr.t - fr.streakStart >= 500) -> "timegate"
        else -> "none(waiver-or-nogate)"
    }

    private fun rel(fr: Fr?, base: Fr) = fr?.let { (it.t - base.t).toString() } ?: "none"

    // ─────────────────────────────── First detection while reversing ───────────────────────────────

    /** A first-detection special alert goes through the same confirmation as a normal alert: never before IDLE. */
    @Test
    fun firstDetectionReverseNeverAlertsBeforeIdle() {
        for ((name, seq) in firstDetectionApproaches) {
            val rev = sum(runSeq(REV, seq, 150))
            val idle = sum(runSeq(IDLE, seq, 150))
            assertNotNull("$name: 후진 기기도 경보가 떠야 한다", rev.alert)
            assertNotNull("$name: 후진 기기도 DANGER 에 도달해야 한다", rev.danger)
            assertTrue("$name: 후진 첫 경보가 IDLE 보다 빠르다 rev=${rev.alert!!.f} idle=${idle.alert!!.f}",
                rev.alert.f >= idle.alert.f)
            assertTrue("$name: 후진 DANGER 가 IDLE 보다 빠르다 rev=${rev.danger!!.f} idle=${idle.danger!!.f}",
                rev.danger.f >= idle.danger.f)
        }
    }

    /**
     * A forklift heard first while already reversing still gets its special alert in every approach, and the frame it
     * fires on shows a confirmation (2-frame streak or Time-Gate). The only test where reverse is on from the first frame.
     */
    @Test
    fun reverseFromFirstDetectionGetsSpecialAlertAfterConfirmation() {
        val fails = mutableListOf<String>()
        for ((name, seq) in firstDetectionApproaches) {
            val trFire = runSeq(REV, seq, 150)
            val fire = sum(trFire)
            val sp = fire.special
            out("firstDetection seq=$name tFire=${sp?.t} route=${sp?.let { route(it) }} revFirstAlert=${fire.alert?.t} revDanger=${fire.danger?.t}")
            trFire.filter { sp != null && it.f in (sp.f - 3)..sp.f }.forEach { out("firstDetection-trace seq=$name ${it.fmt()}") }
            if (sp == null) fails += "$name: 특수경보 미발령"
            else if (sp.before == null && route(sp).startsWith("none")) fails += "$name: 확인 근거 없이 첫 감지 특수경보 ${sp.fmt()}"
        }
        assertTrue(fails.joinToString(" | "), fails.isEmpty())
    }

    // Per-path isolation (state surgery): a device made to look first-detected again, with exactly one confirmation left.
    private fun prepStep(s: BleService): Long {
        var t = t0
        repeat(12) { step(s, it, t, -45, IDLE); t += dt }
        return t
    }

    /** -62 → -50 gentle approach (1dB per 5 frames ≈ 1.67dBm/s, below the fastApproach 2.0) */
    private fun prepRamp(s: BleService): Long {
        var t = t0
        for (f in 0 until 60) { step(s, f, t, -62 + f / 5, IDLE); t += dt }
        return t
    }

    private fun surgery(s: BleService, zeroStreaks: Boolean, clearApproach: Boolean) {
        val asm = asmOf(s)
        fieldOf<MutableMap<String, Pair<Int, Long>>>(s, "alertState").remove(id)
        fieldOf<MutableMap<String, Any>>(asm, "pendingDisplayMap").remove(id)
        labels(s).remove(id)
        if (zeroStreaks) {
            fieldOf<MutableMap<String, Int>>(s, "dangerContactStreakMap")[id] = 0
            fieldOf<MutableMap<String, Int>>(s, "warningContactStreakMap")[id] = 0
        }
        if (clearApproach) {
            fieldOf<MutableMap<String, Long>>(s, "approachStreakStartMap").remove(id)
            fieldOf<MutableMap<String, Any>>(asm, "fastApproachStreakMap").remove(id)
            fieldOf<MutableMap<String, Any>>(asm, "approachLastSeenMap").remove(id)
        }
    }

    /** Each confirmation alone (waiver, 2-frame streak, sustained Time-Gate) fires the special alert; none fires nothing. */
    @Test
    fun eachConfirmationPathAloneFiresFirstDetectionSpecialAlert() {
        val fails = mutableListOf<String>()
        data class Case(val name: String, val expectFire: Boolean, val run: (BleService) -> Fr)
        val cases = listOf(
            Case("waiver", true) { s -> val t = prepStep(s); surgery(s, true, true); step(s, 99, t, -45, REV, waive = true) },
            Case("2frame", true) { s -> val t = prepStep(s); surgery(s, false, true); step(s, 99, t, -45, REV) },
            Case("timegate", true) { s -> val t = prepRamp(s); surgery(s, true, false); step(s, 99, t, -50, REV) },
            Case("timegate-ctrl", false) { s -> val t = prepRamp(s); surgery(s, true, true); step(s, 99, t, -50, REV) },
            Case("negative", false) { s -> val t = prepStep(s); surgery(s, true, true); step(s, 99, t, -45, REV) },
        )
        for (c in cases) {
            val s = H.newService()
            val fr = c.run(s)
            val fired = fr.label && fr.after == DANGER
            out("route case=${c.name} fired=$fired expectFire=${c.expectFire} confirmToFireMs=${if (fired) 0 else "na"} ${fr.fmt()}")
            if (fired != c.expectFire) fails += "${c.name}: fired=$fired expect=${c.expectFire} ${fr.fmt()}"
        }
        assertTrue(fails.joinToString(" | "), fails.isEmpty())
    }

    /** A device that is moving away and then reports reverse is not promoted to a first-detection special alert. */
    @Test
    fun departingDeviceSwitchingToReverseIsNotPromoted() {
        val fails = mutableListOf<String>()
        // departing check (special-alert block in AlertStateMachine.kt) = kfVel < -0.5 ||
        // trackingStateMap==DEPARTING (previous frame's value — updateTrackingState runs later in the
        // frame)
        fun held(trkBefore: String?, fr: Fr) = trkBefore == "DEPARTING" || (fr.vel ?: 0.0) < -0.5
        // premise: the REV frame meets an unregistered device, departing holds and the 2-frame streak exists
        fun premise(trkBefore: String?, fr: Fr) =
            held(trkBefore, fr) && ((fr.ds ?: 0) >= 2 || (fr.ws ?: 0) >= 2) && fr.before == null
        fun judge(tag: String, trkBefore: String?, fr: Fr) {
            val ok = premise(trkBefore, fr)
            out("$tag premise=$ok trkBefore=$trkBefore promoted=${fr.before == null && fr.label} ${fr.fmt()}")
            if (!ok) fails += "$tag 시나리오 전제 불성립(미등록·departing·streak≥2) ${fr.fmt()}"
            else if (fr.label) fails += "$tag 이탈 중 후진이 첫 감지 특수경보로 승급 ${fr.fmt()}"
        }
        fun setDeparting(s: BleService, t: Long) {
            val cls = Class.forName("com.wf11.safealert.service.AlertStateMachine\$TrackingState")
            val dep = cls.enumConstants.first { (it as Enum<*>).name == "DEPARTING" }
            fieldOf<MutableMap<String, Any>>(asmOf(s), "trackingStateMap")[id] = dep
            fieldOf<MutableMap<String, Long>>(asmOf(s), "departingStartMap")[id] = t
        }
        // 1. surgery: 12@-45 IDLE (ds≥2 occurs naturally) → remove the alert record and approach-streak state, keep contact
        // streaks + trackingState=DEPARTING → -45 REV. Without DEPARTING the same frame fires (the "2frame" case of
        // eachConfirmationPathAloneFiresFirstDetectionSpecialAlert).
        run {
            val s = H.newService()
            val t = prepStep(s)
            surgery(s, zeroStreaks = false, clearApproach = true)
            setDeparting(s, t - dt)
            val trkBefore = fieldOf<Map<String, Any>>(s, "trackingStateMap")[id]?.toString()
            judge("surgeryDep", trkBefore, step(s, 99, t, -45, REV))
        }
        // 2. natural (no surgery): pass by in IDLE (rise -60→-40, hold 30 frames, fall 0.5dB/frame). The alert is
        // released on the fall and the state machine itself later reaches DEPARTING while the device is unregistered;
        // on the first such frame send REV. Not reaching that state is a broken premise, not a pass.
        run {
            val s = H.newService()
            var t = t0; var f = 0
            var last: Fr? = null
            for (i in 0..20) { last = step(s, f++, t, -60 + i, IDLE); t += dt }
            repeat(30) { last = step(s, f++, t, -40, IDLE); t += dt }
            var r = -40.0
            var released = false
            var reached = false
            for (i in 0 until 60) {
                r -= 0.5
                val fr = step(s, f++, t, r.toInt(), IDLE); t += dt
                if (i % 4 == 0) out("natural-trace ${fr.fmt()}")
                last = fr
                if (fr.before != null && fr.after == null) released = true
                if (released && fr.after == null && fr.trk == "DEPARTING") { reached = true; break }
            }
            out("natural released=$released reached=$reached at ${last?.fmt()}")
            if (!reached) fails += "natural 전제 불성립: 해제 뒤 미등록 DEPARTING 에 도달하지 못함 ${last?.fmt()}"
            else {
                var trkBefore = last?.trk
                val rr = maxOf(r.toInt(), -56)
                for (k in 0 until 8) {
                    val fr = step(s, f++, t, rr, REV); t += dt
                    if (k > 0 && !premise(trkBefore, fr)) break   // frame 0 must meet the premise; later ones only while it holds
                    judge("natural[$k]", trkBefore, fr)
                    trkBefore = fr.trk
                }
            }
        }
        // 3. kinematic: after a long hold, fall 0.5dB/frame; on the first frame where departing holds
        // (vel<-0.5 or DEPARTING), remove only the alert record → REV at that RSSI-1
        run {
            val s = H.newService()
            var t = t0; var f = 0
            for (i in 0..20) { step(s, f++, t, -60 + i, IDLE); t += dt }
            repeat(30) { step(s, f++, t, -40, IDLE); t += dt }
            var r = -40.0
            var pre: Fr? = null
            while (r > -56) {
                r -= 0.5
                val fr = step(s, f++, t, r.toInt(), IDLE); t += dt
                if (fr.trk == "DEPARTING" || (fr.vel ?: 0.0) < -0.5) { pre = fr; break }
            }
            out("kinematic pre=${pre?.fmt()}")
            if (pre == null) fails += "kinematic 전제 불성립: -56 까지 departing 미성립"
            else {
                surgery(s, zeroStreaks = false, clearApproach = false)
                val fr = step(s, f, t, pre.rssi - 1, REV)
                judge("kinematic", pre.trk, fr)
            }
        }
        assertTrue(fails.joinToString(" | "), fails.isEmpty())
    }

    /** While the peer declares IN_ZONE no special alert fires, whether the device is already alerting or newly detected. */
    @Test
    fun peerInZoneBlocksSpecialAlert() {
        val fails = mutableListOf<String>()
        run {
            val s = H.newService()
            val t = prepStep(s)
            val lvl0 = H.alertLevelOf(s, id)
            val tr = runSeq(REV, { -45 }, 10, peerInZone = true, s = s, startF = 12, startT = t)
            val hit = tr.firstOrNull { it.label || it.specialFire }
            if (lvl0 == null) fails += "alerted 전제 불성립: prep 후 경보 미등록"
            out("inZone-alerted levelBeforeInZone=$lvl0 specialFrames=${tr.count { it.label || it.specialFire }} levels=${tr.map { it.after }} first=${hit?.fmt()}")
            if (hit != null) fails += "경보 중 기기+IN_ZONE 에서 특수경보 ${hit.fmt()}"
        }
        run {
            val tr = runSeq(REV, { -45 }, 40, peerInZone = true)
            val hit = tr.firstOrNull { it.label || it.specialFire }
            out("inZone-first specialFrames=${tr.count { it.label || it.specialFire }} firstSpecialT=${hit?.t} firstAlertT=${sum(tr).alert?.t}")
            if (hit != null) fails += "첫 감지+IN_ZONE 에서 특수경보 ${hit.fmt()}"
        }
        assertTrue(fails.joinToString(" | "), fails.isEmpty())
    }

    // ─────────────────────────────── Already alerting ───────────────────────────────

    @Test
    fun alertedDeviceSwitchingToReverseGetsSpecialImmediately() {
        for (state in listOf(BleConstants.PSTATE_REVERSE, BleConstants.PSTATE_LOADING)) {
            val service = H.newService()
            var clock = 1_000L
            repeat(10) {
                H.callProcessAlert(service, id, -45, remoteState = forkliftPayload(IDLE), payloadPresent = true, nowMs = clock)
                clock += 120L
            }
            assertNotNull("IDLE 로 먼저 경보에 들어가 있어야 한다", H.alertLevelOf(service, id))
            assertFalse(labels(service).containsKey(id))

            val broadcasts = H.alertBroadcasts().size
            H.callProcessAlert(service, id, -45, remoteState = forkliftPayload(state), payloadPresent = true, nowMs = clock)
            assertTrue("state=$state: 경보 중 후진·하역 전환은 같은 프레임에 특수경보", labels(service).containsKey(id))
            assertEquals(BleConstants.LEVEL_DANGER, H.alertLevelOf(service, id))
            assertTrue("state=$state: 그 프레임에 경보 브로드캐스트", H.alertBroadcasts().size > broadcasts)
        }

        // Alerting at WARNING (-78), then moving in to -45 while switching to reverse: DANGER comes no later than in the IDLE control
        fun dangerFrameAfterWarning(state: Int): Int? {
            val service = H.newService()
            var clock = 1_000L
            repeat(15) {
                H.callProcessAlert(service, id, -78, remoteState = forkliftPayload(IDLE), payloadPresent = true, nowMs = clock)
                clock += 120L
            }
            assertNotNull("전환 전에 경보 중이어야 한다", H.alertLevelOf(service, id))
            for (f in 0 until 15) {
                H.callProcessAlert(service, id, -45, remoteState = forkliftPayload(state), payloadPresent = true, nowMs = clock)
                if (H.alertLevelOf(service, id) == BleConstants.LEVEL_DANGER) return f
                clock += 120L
            }
            return null
        }
        val rev = dangerFrameAfterWarning(REV)
        val idle = dangerFrameAfterWarning(IDLE)
        assertNotNull("WARNING 경보 중 후진 전환은 DANGER 에 도달해야 한다", rev)
        assertTrue("후진 전환 DANGER 가 IDLE 대조군보다 늦다 rev=$rev idle=$idle", idle == null || rev!! <= idle)
    }

    // ─────────────────────────────── Reverse never delays an alert ───────────────────────────────

    /**
     * Same approach, reverse vs IDLE: the reversing forklift's TTC pre-alert or DANGER is never later than IDLE's TTC
     * pre-alert, its DANGER never later than IDLE's DANGER, and holding a fixed distance gives the same cooldown re-alarms.
     * The TTC pre-alert is found by its log line, so the IDLE run must show it at least once; otherwise a changed log
     * text would silently skip the TTC comparison.
     */
    @Test
    fun reverseNeverDelaysTtcDangerOrCooldownRealarm() {
        val fails = mutableListOf<String>()
        for ((nm, seq) in listOf<Pair<String, (Int) -> Int>>(
            "ttc8dBps" to { f -> minOf(-80 + f, -40) },
            "ttc4dBps" to { f -> minOf(-80 + f / 2, -40) },
        )) {
            val r = mutableMapOf<Int, Sum>()
            for (st in listOf(REV, IDLE)) {
                val sm = sum(runSeq(st, seq, 100))
                r[st] = sm
                out("ttc seq=$nm st=$st tTtc=${sm.ttc?.t} tFirstAlert=${sm.alert?.t} tDanger=${sm.danger?.t} tSpecial=${sm.special?.t} ttcFrame=${sm.ttc?.fmt()}")
            }
            val rev = r[REV]!!; val idle = r[IDLE]!!
            if (idle.ttc == null) fails += "$nm: IDLE 에서 TTC 선발령 로그가 한 번도 없다 (로그 문구 'TTC 선발령' 이 바뀌었으면 이 테스트도 고칠 것)"
            else if (rev.ttc?.t != idle.ttc.t && (rev.danger == null || rev.danger.t > idle.ttc.t))
                fails += "$nm: 후진 TTC/DANGER(${rev.ttc?.t}/${rev.danger?.t}) 가 IDLE TTC(${idle.ttc.t}) 보다 늦음"
            if (idle.danger != null && (rev.danger == null || rev.danger.t > idle.danger.t))
                fails += "$nm: 후진 DANGER(${rev.danger?.t}) 가 IDLE(${idle.danger.t}) 보다 늦음"
        }
        // Cooldown re-alarm: hold a fixed distance (8.4s) — sequence of alert broadcast times
        for (rssi in listOf(-78, -45)) {
            val bcs = mutableMapOf<Int, List<Long>>()
            for (st in listOf(REV, IDLE)) {
                val tr = runSeq(st, { rssi }, 70)
                bcs[st] = tr.filter { it.bc > 0 }.map { it.t }
                val times = bcs[st]!!
                out("cooldown rssi=$rssi st=$st bcCount=${times.size} first=${times.firstOrNull()} " +
                    "gaps=${times.zipWithNext { a, b -> b - a }.distinct().take(8)} times=${times.take(12)}")
            }
            if (rssi == -78 && bcs[REV] != bcs[IDLE]) fails += "rssi=-78(특수 후보 아님) 후진/IDLE 경보 시각열 불일치 rev=${bcs[REV]} idle=${bcs[IDLE]}"
            if (rssi == -45) {
                val idleRe = bcs[IDLE]!!.drop(1).firstOrNull()
                val revRe = bcs[REV]!!.drop(1).firstOrNull()
                if (idleRe != null && (revRe == null || revRe > idleRe)) fails += "rssi=-45 후진 재알람($revRe) 이 IDLE($idleRe) 보다 늦음"
            }
        }
        assertTrue(fails.joinToString(" | "), fails.isEmpty())
    }

    /** A reversing forklift first detected while approaching fast gets its first alert broadcast before its closest point. */
    @Test
    fun fastReversingForkliftAlertsBeforeClosestPoint() {
        val fails = mutableListOf<String>()
        val profiles = mutableListOf<Pair<String, (Int) -> Int>>()
        for (r in listOf(1, 3, 5)) {
            val fp = (52 + r - 1) / r + 5
            profiles += "vee${r}dBpf" to { f: Int -> maxOf(-90, -38 - r * kotlin.math.abs(f - fp)) }
        }
        val occl = intArrayOf(-52, -46, -40, -38, -42, -48, -56, -64, -72, -80)
        profiles += "occlusion" to { f: Int -> if (f < 10 || f >= 20) -90 else occl[f - 10] }
        val popIn = intArrayOf(-48, -42, -38, -44, -52, -62, -75)
        profiles += "popIn" to { f: Int -> if (f < 10 || f >= 17) -90 else popIn[f - 10] }

        for ((nm, seq) in profiles) {
            val tr = runSeq(REV, seq, 140)
            val sm = sum(tr)
            out("cpa seq=$nm tCpa=${sm.cpa.t} alertRel=${rel(sm.alert, sm.cpa)} bcRel=${rel(sm.bc, sm.cpa)} " +
                "specialRel=${rel(sm.special, sm.cpa)} dangerRel=${rel(sm.danger, sm.cpa)} tAlert=${sm.alert?.t} tSpecial=${sm.special?.t} tDanger=${sm.danger?.t} " +
                "specialRoute=${sm.special?.let { route(it) }}")
            tr.filter { it.f in (sm.cpa.f - 6)..(sm.cpa.f + 2) }.forEach { out("cpa-trace seq=$nm ${it.fmt()}") }
            if (sm.bc == null || sm.bc.t > sm.cpa.t) fails += "$nm: 후진 첫 발령(${sm.bc?.t})이 CPA(${sm.cpa.t}) 이후"
        }
        assertTrue(fails.joinToString(" | "), fails.isEmpty())
    }

    // ─────────────────────────────── Time-Gate (evalTimeGate) ───────────────────────────────

    private fun evalGate(asm: Any, vel: Double, now: Long) = ReflectionHelpers.callInstanceMethod<Any>(
        asm, "evalTimeGate",
        ClassParameter.from(String::class.java, id),
        ClassParameter.from(Double::class.javaPrimitiveType, vel),
        ClassParameter.from(Long::class.javaPrimitiveType, now),
        ClassParameter.from(Int::class.javaPrimitiveType, 10),
    )

    private fun streakMs(gate: Any) = ReflectionHelpers.getField<Long>(gate, "streakMs")

    /**
     * A non-approach evaluation within 300 ms of the last approach frame keeps the approach streak (its start and its
     * running streakMs), up to and including 300 ms; past that the streak resets (streakMs 0). Rows 1-4 are the steps of
     * one sequence (approach 1000, one non-approach frame at 1120, approach 1240, non-approach 1600 = 360 ms after 1240);
     * rows 5-7 are the boundary; row 8 shows the 300 ms counts from the last approach frame, not the first. Each row
     * replays its calls on a new service and checks the last call; a null column is not checked for that row.
     */
    @Test
    fun approachStreakGraceIs300MsFromTheLastApproachFrame() {
        class Row(
            val label: String, val calls: List<Pair<Double, Long>>,
            val start: Long? = null, val kept: Boolean? = null, val streakMs: Long? = null,
        )
        val approach = 50.0
        val none = 0.0
        val rows = listOf(
            Row("row 1 approach at 1000", listOf(approach to 1_000L), start = 1_000L),
            Row("row 2 짧은 끊김은 streak 유지 (one non-approach frame, 120ms)", listOf(approach to 1_000L, none to 1_120L),
                start = 1_000L, streakMs = 120L),
            Row("row 3 approach again at 1240", listOf(approach to 1_000L, none to 1_120L, approach to 1_240L), streakMs = 240L),
            Row("row 4 유예 초과는 streak 리셋 (last approach 1240 → 360ms > 300ms)",
                listOf(approach to 1_000L, none to 1_120L, approach to 1_240L, none to 1_600L), kept = false),
            Row("row 5 gap=299", listOf(approach to 1_000L, none to 1_299L), kept = true, streakMs = 299L),
            Row("row 6 gap=300", listOf(approach to 1_000L, none to 1_300L), kept = true, streakMs = 300L),
            Row("row 7 gap=301", listOf(approach to 1_000L, none to 1_301L), kept = false, streakMs = 0L),
            Row("row 8 approach 1000 and 1200, none 1450 (250ms after the last approach)",
                listOf(approach to 1_000L, approach to 1_200L, none to 1_450L), kept = true),
        )
        for (r in rows) {
            val service = H.newService()
            val asm = asmOf(service)
            val streaks = fieldOf<Map<String, Long>>(service, "approachStreakStartMap")
            var last: Any? = null
            for ((vel, now) in r.calls) last = evalGate(asm, vel, now)
            r.start?.let { assertEquals("${r.label}: streak start", it, streaks[id]) }
            r.kept?.let { assertEquals("${r.label}: streak kept", it, streaks.containsKey(id)) }
            r.streakMs?.let { assertEquals("${r.label}: streakMs", it, streakMs(last!!)) }
        }
    }

    /**
     * A kept streak also keeps the confirmation time: approaching at 1.0 dBm/s (below the fast-approach bypass) every
     * 100ms, sustained comes at 1500 (1000 + the 500ms Time-Gate) with or without non-approach frames at 1100..1300.
     */
    @Test
    fun shortNonApproachGapsDoNotDelayConfirmation() {
        fun sustainedAt(dipFrames: Int): Long? {
            val asm = asmOf(H.newService())
            var now = 1_000L
            evalGate(asm, 1.0, now)
            repeat(dipFrames) { now += 100L; evalGate(asm, 0.0, now) }
            repeat(20) { now += 100L; if (ReflectionHelpers.getField<Boolean>(evalGate(asm, 1.0, now), "sustained")) return now }
            return null
        }
        assertEquals(1_500L, sustainedAt(0))
        assertEquals("300ms 이하 끊김은 확인 시각을 늦추지 않는다", 1_500L, sustainedAt(3))
    }

    /**
     * Fast-approach bypass in evalTimeGate: kfVel at or above DevSettings.fastApproachBypassVelDbm on two evaluations in a
     * row passes the Time-Gate while the plain approach streak is still under it; one such frame alone does not, and a
     * frame just below the threshold resets the count.
     */
    @Test
    fun twoFastApproachFramesPassTimeGateEarly() {
        val asm = asmOf(H.newService())
        val v = DevSettings.fastApproachBypassVelDbm
        fun fastFrames(g: Any) = ReflectionHelpers.getField<Int>(g, "fastFrames")
        fun sustained(g: Any) = ReflectionHelpers.getField<Boolean>(g, "sustained")

        val first = evalGate(asm, v, 1_000L)
        assertEquals(1, fastFrames(first))
        assertFalse("빠른접근 한 프레임으로는 우회하지 않는다", sustained(first))

        val second = evalGate(asm, v, 1_120L)
        assertEquals(2, fastFrames(second))
        assertTrue("빠른접근 두 프레임이면 Time-Gate 를 우회한다", sustained(second))
        assertTrue("일반 Time-Gate 는 아직 미충족", streakMs(second) < ReflectionHelpers.getField<Long>(second, "ms"))

        val below = evalGate(asm, v - 0.01, 1_240L)
        assertEquals(0, fastFrames(below))
        assertFalse("문턱 아래 프레임은 빠른접근을 끊는다", sustained(below))
    }
}
