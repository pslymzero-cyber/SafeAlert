package com.wf11.safealert.ble

import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness as H
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/**
 * Simulation of the first-detection gate for special alerts (reverse/loading).
 * Measurements print as "[S0914-A1] <scenario> key=value"; assertions are collected and made at
 * the end of each test, so every measurement prints even when an assertion fails.
 * State is read through reflection that fails the test when a field is missing (no fallbacks), and a scenario whose
 * premise does not hold fails instead of printing "unknown".
 * Expected behavior: on first detection the special alert fires only through a confirmation (waiver, a
 * 2-frame contact streak while not departing, or a sustained Time-Gate approach) and never while the peer
 * declares IN_ZONE; reverse never delays the TTC pre-alert, DANGER or the cooldown re-alarm compared with
 * IDLE; and a fast-approaching reversing forklift is alerted before it passes CPA. Sources: the special-alert
 * block comment and the evalTimeGate comment in AlertStateMachine.kt, and SpecialAlertTimeGateTest.
 */
@RunWith(RobolectricTestRunner::class)
class Sim0914SpecialGateTest {

    private val id = "SA-SIM-A1"
    private val dt = 120L
    private val t0 = 1_000L
    private val REV = BleConstants.PSTATE_REVERSE
    private val IDLE = BleConstants.PSTATE_IDLE
    private val DANGER = BleConstants.LEVEL_DANGER

    private fun out(s: String) = println("[S0914-A1] $s")
    private fun payload(state: Int) = BleConstants.encodePayload(BleConstants.CAT_FORKLIFT, state).toInt() and 0xFF
    private fun asm(s: BleService): Any = ReflectionHelpers.getField(s, "asm")
    @Suppress("UNCHECKED_CAST")
    private fun <T> sf(s: BleService, name: String): T = ReflectionHelpers.getField<Any>(s, name) as T
    @Suppress("UNCHECKED_CAST")
    private fun <T> af(s: BleService, name: String): T = ReflectionHelpers.getField<Any>(asm(s), name) as T
    private fun labels(s: BleService) = sf<MutableMap<String, String>>(s, "suddenLabelMap")
    private fun alertMap(s: BleService) = sf<MutableMap<String, Pair<Int, Long>>>(s, "alertState")
    private fun logCount(key: String) = ShadowLog.getLogs().count { it.msg?.contains(key) == true }

    data class Fr(
        val f: Int, val t: Long, val rssi: Int, val state: Int, val before: Int?, val after: Int?,
        val label: Boolean, val specialFire: Boolean, val ttc: Boolean, val bc: Int,
        val ds: Int?, val ws: Int?, val trk: String?, val vel: Double?, val streakStart: Long?, val fast: Any?,
    ) {
        fun fmt() = "f=$f t=$t r=$rssi st=$state lv=$before>$after lab=$label spFire=$specialFire ttc=$ttc bc+$bc " +
            "ds=$ds ws=$ws trk=$trk vel=${vel?.let { "%.2f".format(it) }} asStart=$streakStart fast=$fast"
    }

    private fun step(s: BleService, f: Int, t: Long, rssi: Int, state: Int, peerInZone: Boolean = false, waive: Boolean = false): Fr {
        if (peerInZone) sf<MutableMap<String, Boolean>>(s, "peerInZoneMap")[id] = true
        if (waive) sf<MutableSet<String>>(s, "timeGateWaiveSet").add(id)
        val before = H.alertLevelOf(s, id)
        val bc0 = H.alertBroadcasts().size
        val sp0 = logCount("특수경보(STATE=")
        val ttc0 = logCount("TTC 선발령")
        H.callProcessAlert(s, id, rssi, remoteState = payload(state), payloadPresent = true, nowMs = t)
        return Fr(
            f, t, rssi, state, before, H.alertLevelOf(s, id),
            labels(s).containsKey(id), logCount("특수경보(STATE=") > sp0, logCount("TTC 선발령") > ttc0,
            H.alertBroadcasts().size - bc0,
            sf<Map<String, Int>>(s, "dangerContactStreakMap")[id],
            sf<Map<String, Int>>(s, "warningContactStreakMap")[id],
            sf<Map<String, Any>>(s, "trackingStateMap")[id]?.toString(),
            sf<Map<String, KalmanFilter>>(s, "kalmanFilters")[id]?.estimatedVel,
            sf<Map<String, Long>>(s, "approachStreakStartMap")[id],
            af<Map<String, Any>>(s, "fastApproachStreakMap")[id],
        )
    }

    private fun runSeq(
        state: Int, rssiAt: (Int) -> Int, frames: Int, peerInZone: Boolean = false,
        s: BleService = H.newService(), startF: Int = 0, startT: Long = t0,
    ): List<Fr> = (0 until frames).map { i ->
        step(s, startF + i, startT + i * dt, rssiAt(i), state, peerInZone)
    }

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

    private val noise = intArrayOf(0, -3, 2, -1, 3, -2)
    private val seqs: List<Pair<String, (Int) -> Int>> = listOf(
        "slow" to { f -> minOf(-90 + f / 2, -40) },
        "fast" to { f -> minOf(-90 + f * 2, -40) },
        "jitter" to { f -> minOf(-90 + f, -40) + noise[f % noise.size] },
        "step" to { _ -> -45 },
        "weakThenStep" to { f -> if (f < 10) -90 else -45 },
    )

    // ─────────────────────────────── a. Reverse on first detection — 5 e2e sequences ───────────────────────────────
    @Test
    fun a_e2e_firstDetectionReverse() {
        val fails = mutableListOf<String>()
        for ((name, seq) in seqs) {
            val trFire = runSeq(REV, seq, 150)
            val fire = sum(trFire)
            val sp = fire.special
            out("a-e2e seq=$name tFire=${sp?.t} route=${sp?.let { route(it) }} revFirstAlert=${fire.alert?.t} revDanger=${fire.danger?.t}")
            trFire.filter { sp != null && it.f in (sp.f - 3)..sp.f }.forEach { out("a-e2e-trace seq=$name ${it.fmt()}") }
            if (sp == null) fails += "$name: 특수경보 미발령"
            else if (sp.before == null && route(sp).startsWith("none")) fails += "$name: 확인 근거 없이 첫 감지 특수경보 ${sp.fmt()}"
        }
        assertTrue(fails.joinToString(" | "), fails.isEmpty())
    }

    // ─────────────────────────── a. Per-path isolation (state surgery) ───────────────────────────
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
        alertMap(s).remove(id)
        af<MutableMap<String, Any>>(s, "pendingDisplayMap").remove(id)
        labels(s).remove(id)
        if (zeroStreaks) {
            sf<MutableMap<String, Int>>(s, "dangerContactStreakMap")[id] = 0
            sf<MutableMap<String, Int>>(s, "warningContactStreakMap")[id] = 0
        }
        if (clearApproach) {
            sf<MutableMap<String, Long>>(s, "approachStreakStartMap").remove(id)
            af<MutableMap<String, Any>>(s, "fastApproachStreakMap").remove(id)
            af<MutableMap<String, Any>>(s, "approachLastSeenMap").remove(id)
        }
    }

    @Test
    fun a_routes_isolated() {
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
            out("a-route case=${c.name} fired=$fired expectFire=${c.expectFire} confirmToFireMs=${if (fired) 0 else "na"} ${fr.fmt()}")
            if (fired != c.expectFire) fails += "${c.name}: fired=$fired expect=${c.expectFire} ${fr.fmt()}"
        }
        assertTrue(fails.joinToString(" | "), fails.isEmpty())
    }

    // ─────────────────────────── b. Reverse frames while departing ───────────────────────────
    @Test
    fun b_departingReverseNotPromoted() {
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
            af<MutableMap<String, Any>>(s, "trackingStateMap")[id] = dep
            af<MutableMap<String, Long>>(s, "departingStartMap")[id] = t
        }
        // b1 surgery: 12@-45 IDLE (ds≥2 occurs naturally) → remove the alert record and approach-streak state, keep contact
        // streaks + trackingState=DEPARTING → -45 REV. Without DEPARTING the same frame fires (a_routes_isolated "2frame").
        run {
            val s = H.newService()
            val t = prepStep(s)
            surgery(s, zeroStreaks = false, clearApproach = true)
            setDeparting(s, t - dt)
            val trkBefore = sf<Map<String, Any>>(s, "trackingStateMap")[id]?.toString()
            judge("b-surgeryDep", trkBefore, step(s, 99, t, -45, REV))
        }
        // b2 natural (no surgery): pass by in IDLE (rise -60→-40, hold 30 frames, fall 0.5dB/frame). The alert is
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
                if (i % 4 == 0) out("b-natural-trace ${fr.fmt()}")
                last = fr
                if (fr.before != null && fr.after == null) released = true
                if (released && fr.after == null && fr.trk == "DEPARTING") { reached = true; break }
            }
            out("b-natural released=$released reached=$reached at ${last?.fmt()}")
            if (!reached) fails += "b-natural 전제 불성립: 해제 뒤 미등록 DEPARTING 에 도달하지 못함 ${last?.fmt()}"
            else {
                var trkBefore = last?.trk
                val rr = maxOf(r.toInt(), -56)
                for (k in 0 until 8) {
                    val fr = step(s, f++, t, rr, REV); t += dt
                    if (k > 0 && !premise(trkBefore, fr)) break   // frame 0 must meet the premise; later ones only while it holds
                    judge("b-natural[$k]", trkBefore, fr)
                    trkBefore = fr.trk
                }
            }
        }
        // b3 kinematic: after a long hold, fall 0.5dB/frame; on the first frame where departing holds
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
            out("b-kinematic pre=${pre?.fmt()}")
            if (pre == null) fails += "b-kinematic 전제 불성립: -56 까지 departing 미성립"
            else {
                surgery(s, zeroStreaks = false, clearApproach = false)
                val fr = step(s, f, t, pre.rssi - 1, REV)
                judge("b-kinematic", pre.trk, fr)
            }
        }
        assertTrue(fails.joinToString(" | "), fails.isEmpty())
    }

    // ─────────────────────────── d. Peer declares IN_ZONE ───────────────────────────
    @Test
    fun d_peerInZoneBlocksSpecial() {
        val fails = mutableListOf<String>()
        run {
            val s = H.newService()
            val t = prepStep(s)
            val lvl0 = H.alertLevelOf(s, id)
            val tr = runSeq(REV, { -45 }, 10, peerInZone = true, s = s, startF = 12, startT = t)
            val hit = tr.firstOrNull { it.label || it.specialFire }
            if (lvl0 == null) fails += "d-alerted 전제 불성립: prep 후 경보 미등록"
            out("d-alerted levelBeforeInZone=$lvl0 specialFrames=${tr.count { it.label || it.specialFire }} levels=${tr.map { it.after }} first=${hit?.fmt()}")
            if (hit != null) fails += "경보 중 기기+IN_ZONE 에서 특수경보 ${hit.fmt()}"
        }
        run {
            val tr = runSeq(REV, { -45 }, 40, peerInZone = true)
            val hit = tr.firstOrNull { it.label || it.specialFire }
            out("d-first specialFrames=${tr.count { it.label || it.specialFire }} firstSpecialT=${hit?.t} firstAlertT=${sum(tr).alert?.t}")
            if (hit != null) fails += "첫 감지+IN_ZONE 에서 특수경보 ${hit.fmt()}"
        }
        assertTrue(fails.joinToString(" | "), fails.isEmpty())
    }

    // ─────────────────────────── e. Regression: TTC pre-alert and cooldown re-alarm ───────────────────────────
    @Test
    fun e_ttcAndCooldownNotDelayed() {
        val fails = mutableListOf<String>()
        for ((nm, seq) in listOf<Pair<String, (Int) -> Int>>(
            "ttc8dBps" to { f -> minOf(-80 + f, -40) },
            "ttc4dBps" to { f -> minOf(-80 + f / 2, -40) },
        )) {
            val r = mutableMapOf<Int, Sum>()
            for (st in listOf(REV, IDLE)) {
                val sm = sum(runSeq(st, seq, 100))
                r[st] = sm
                out("e-ttc seq=$nm st=$st tTtc=${sm.ttc?.t} tFirstAlert=${sm.alert?.t} tDanger=${sm.danger?.t} tSpecial=${sm.special?.t} ttcFrame=${sm.ttc?.fmt()}")
            }
            val rt = r[REV]!!; val it = r[IDLE]!!
            if (it.ttc != null && rt.ttc?.t != it.ttc.t && (rt.danger == null || rt.danger.t > it.ttc.t))
                fails += "$nm: 후진 TTC/DANGER(${rt.ttc?.t}/${rt.danger?.t}) 가 IDLE TTC(${it.ttc.t}) 보다 늦음"
            if (it.danger != null && (rt.danger == null || rt.danger.t > it.danger.t))
                fails += "$nm: 후진 DANGER(${rt.danger?.t}) 가 IDLE(${it.danger.t}) 보다 늦음"
        }
        // Cooldown re-alarm: hold a fixed distance (8.4s) — sequence of alert broadcast times
        for (rssi in listOf(-78, -45)) {
            val bcs = mutableMapOf<Int, List<Long>>()
            for (st in listOf(REV, IDLE)) {
                val tr = runSeq(st, { rssi }, 70)
                bcs[st] = tr.filter { it.bc > 0 }.map { it.t }
                val times = bcs[st]!!
                out("e-cooldown rssi=$rssi st=$st bcCount=${times.size} first=${times.firstOrNull()} " +
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

    // ─────────────────────────── f. Safety: first detection of a fast-approaching reversing forklift vs CPA ───────────────────────────
    @Test
    fun f_fastReverseAlertsBeforeCpa() {
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
            out("f-cpa seq=$nm tCpa=${sm.cpa.t} alertRel=${rel(sm.alert, sm.cpa)} bcRel=${rel(sm.bc, sm.cpa)} " +
                "specialRel=${rel(sm.special, sm.cpa)} dangerRel=${rel(sm.danger, sm.cpa)} tAlert=${sm.alert?.t} tSpecial=${sm.special?.t} tDanger=${sm.danger?.t} " +
                "specialRoute=${sm.special?.let { route(it) }}")
            tr.filter { it.f in (sm.cpa.f - 6)..(sm.cpa.f + 2) }.forEach { out("f-trace seq=$nm ${it.fmt()}") }
            if (sm.bc == null || sm.bc.t > sm.cpa.t) fails += "$nm: 후진 첫 발령(${sm.bc?.t})이 CPA(${sm.cpa.t}) 이후"
        }
        assertTrue(fails.joinToString(" | "), fails.isEmpty())
    }
}
