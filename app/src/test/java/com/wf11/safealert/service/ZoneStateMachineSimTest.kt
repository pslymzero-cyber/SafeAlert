package com.wf11.safealert.service

import com.wf11.safealert.support.BleServiceTestHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.Random
import kotlin.math.roundToInt

/**
 * Safe zone state machine simulation.
 *
 * A simulation built on a re-implemented model (such as a Python model) only verifies its own model once the
 * re-implementation drifts from the real Kotlin, and lets app defects through. So this test has three layers.
 *   1) Drives the real BleService.onZoneBeaconSignal / reevaluateZones directly via reflection.
 *   2) Cross-checks that the mirror matches the real code exactly, sample by sample (mismatch = immediate failure).
 *   3) Reads the constants and the dead-band branch from the source file and asserts them
 *   — if the main code changes, this fails and flags the mirror as stale.
 *
 * Timing: the entry decision is time-independent (sample count only); only reevaluateZones depends on time.
 * Pushing zoneLastSeenMap into the past via reflection reproduces GRACE and STALE without waiting in real time.
 */
@RunWith(RobolectricTestRunner::class)
class ZoneStateMachineSimTest {

    private companion object {
        const val KEY = "AA:BB:CC:DD:EE:FF"
        const val ENTER = -80            // default zone-beacon entry threshold (dBm)
        const val MIN_SAMPLES = 1        // one sample: safe mode holds while the signal is received
        const val HYST = 5
        const val EXIT_SAMPLES = 3       // exit debounce (a single fade must not break suppression)
        const val GRACE_MS = 10_000L     // outlasts the gap between slow beacon samples
        const val NEW_STALE_MS = 30_000L
        const val XCHECK_SEEDS = 10
        const val MC_SEEDS = 200
    }

    private val sideFx = ArrayList<Throwable>()

    // ── Scenario definitions ─────────────────────────────────────────────────
    private data class Scn(
        val name: String, val mean: Double, val sigma: Double,
        val stepMs: Long, val n: Int, val gapEvery: Int = 0, val gapMs: Long = 0L
    )

    private val scenarios = listOf(
        Scn("A 경계요동(-78, s6, 1s)", -78.0, 6.0, 1_000L, 40),
        Scn("B 데드밴드체류(-82, s4, 1s)", -82.0, 4.0, 1_000L, 40),
        Scn("C 존중앙(-70, s5, 1s)", -70.0, 5.0, 1_000L, 40),
        Scn("D 존밖(-92, s5, 1s)", -92.0, 5.0, 1_000L, 40),
        Scn("E 느린비콘(-76, s5, 5s주기)", -76.0, 5.0, 5_000L, 20),
        Scn("F 스캔공백(-76, s5, 5표본마다 45s)", -76.0, 5.0, 1_000L, 40, gapEvery = 5, gapMs = 45_000L)
    )

    /**
     * Frame sequence of (ms since the previous sample, rssi) — a pure function, so the real code and the mirror see the same input.
     */
    private fun frames(s: Scn, seed: Long): List<Pair<Long, Int>> {
        val r = Random(seed)
        return (0 until s.n).map { i ->
            val dt = if (s.gapEvery > 0 && i > 0 && i % s.gapEvery == 0) s.gapMs else s.stepMs
            dt to (s.mean + r.nextGaussian() * s.sigma).roundToInt()
        }
    }

    private data class Res(val enterAt: Int, val finalInside: Boolean, val trace: List<Pair<Int, Boolean>>)

    // ── 1) Drive the real BleService ─────────────────────────────────────────
    @Suppress("UNCHECKED_CAST")
    private fun zoneMap(svc: BleService, name: String): MutableMap<String, Any?> =
        ReflectionHelpers.getField<Any>(svc, name) as MutableMap<String, Any?>

    private fun insideOf(svc: BleService) = zoneMap(svc, "zoneInsideMap")[KEY] == true
    private fun sampleOf(svc: BleService) = (zoneMap(svc, "zoneSampleMap")[KEY] as? Int) ?: 0

    private fun resetZone(svc: BleService) {
        listOf("zoneInsideMap", "zoneSampleMap", "zoneLastSeenMap", "zoneEnterRssiMap")
            .forEach { zoneMap(svc, it).clear() }
        runCatching { ReflectionHelpers.setField(svc, "myZoneInside", false) }.onFailure { sideFx += it }
    }

    /**
     * The decision maps are updated at the start of onZoneBeaconSignal; exceptions from the propagation
     * at its tail (overlay/notification/advertising) are only collected and swallowed.
     */
    private fun signal(svc: BleService, rssi: Int) {
        runCatching {
            ReflectionHelpers.callInstanceMethod<Any?>(
                svc, "onZoneBeaconSignal",
                ClassParameter.from(String::class.java, KEY),
                ClassParameter.from(Int::class.javaPrimitiveType, rssi),
                ClassParameter.from(Int::class.javaPrimitiveType, ENTER)
            )
        }.onFailure { sideFx += it }
    }

    /** Push lastSeen dtMs into the past and poll once — GRACE/STALE are idempotent, so one poll per interval is equivalent. */
    private fun elapse(svc: BleService, dtMs: Long) {
        val ls = zoneMap(svc, "zoneLastSeenMap")
        if (ls.isEmpty()) return
        val now = System.currentTimeMillis()
        ls.entries.forEach { it.setValue(now - dtMs) }
        runCatching { ReflectionHelpers.callInstanceMethod<Any?>(svc, "reevaluateZones") }
            .onFailure { sideFx += it }
    }

    private fun runReal(svc: BleService, f: List<Pair<Long, Int>>): Res {
        resetZone(svc)
        var enterAt = -1
        val trace = ArrayList<Pair<Int, Boolean>>(f.size)
        f.forEachIndexed { i, (dt, rssi) ->
            elapse(svc, dt)
            signal(svc, rssi)
            val inside = insideOf(svc)
            trace += sampleOf(svc) to inside
            if (inside && enterAt < 0) enterAt = i + 1
        }
        return Res(enterAt, insideOf(svc), trace)
    }

    // ── 2) Mirror (cross-checked per sample against the real code) ───────────
    private class Mirror(val staleMs: Long) {
        var sample = 0
        var inside: Boolean? = null
        var lastSeen = 0L
        var present = false

        fun signal(now: Long, rssi: Int) {
            lastSeen = now; present = true
            when {
                rssi >= ENTER -> {
                    sample = maxOf(sample, 0) + 1
                    if (sample >= MIN_SAMPLES && inside != true) inside = true
                }
                rssi < ENTER - HYST -> {
                    sample = minOf(sample, 0) - 1
                    if (-sample >= EXIT_SAMPLES && inside == true) inside = false
                }
                else -> Unit
            }
        }

        fun reevaluate(now: Long) {
            if (!present) return
            if (now - lastSeen > GRACE_MS && inside == true) { inside = false; sample = 0 }
            if (now - lastSeen > staleMs) { inside = null; sample = 0; present = false }
        }
    }

    private fun runMirror(m: Mirror, f: List<Pair<Long, Int>>): Res {
        var t = 0L
        var enterAt = -1
        val trace = ArrayList<Pair<Int, Boolean>>(f.size)
        f.forEachIndexed { i, (dt, rssi) ->
            t += dt
            m.reevaluate(t)
            m.signal(t, rssi)
            val inside = m.inside == true
            trace += m.sample to inside
            if (inside && enterAt < 0) enterAt = i + 1
        }
        return Res(enterAt, m.inside == true, trace)
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun mirror_matches_the_real_ble_service_sample_by_sample() {
        val svc = BleServiceTestHarness.newService()
        var n = 0
        scenarios.forEach { s ->
            (0 until XCHECK_SEEDS).forEach { seed ->
                val f = frames(s, seed.toLong())
                val real = runReal(svc, f)
                val mir = runMirror(Mirror(staleMs = NEW_STALE_MS), f)
                assertEquals(
                    "${s.name} seed=${seed} 표본별 (sample,inside) 불일치 — 미러 드리프트",
                    real.trace, mir.trace
                )
                n += f.size
            }
        }
        println("[교차검증] 실제 BleService 구동 ${n}표본 — 미러와 전부 일치")
        if (sideFx.isNotEmpty()) {
            println("[교차검증] 전파계층 예외 ${sideFx.size}건(판정 무관, 삼킴) 예: ${sideFx.first()}")
        }
    }

    @Test
    fun signals_outside_the_zone_cannot_keep_suppression() {
        // With entry on a single sample (MIN_SAMPLES = 1), "never enters" no longer holds:
        // mean -92 / s5, rounded to whole dBm, reaches the -80 threshold on about 1.1% of samples (P(N(-92,5) >= -80.5)),
        // and that one sample causes entry.
        // With the exit debounce, suppression from such a spike is released not at once but only after
        // EXIT_SAMPLES samples below the exit line. So the safety property is narrowed to one mechanically exact rule:
        //   while suppression holds, there cannot be EXIT_SAMPLES consecutive samples below the exit line (-85).
        // Suppression that continues while the signal dwells in the dead band (-85..-80) is intended hysteresis, so
        // it is not forbidden; it is only bounded by a cap on the dwell ratio (the spike contribution).
        val svc = BleServiceTestHarness.newService()
        val out = scenarios.first { it.name.startsWith("D") }
        var inSamples = 0; var total = 0; var worstRun = 0; var spikes = 0
        (0 until MC_SEEDS).forEach { seed ->
            val f = frames(out, seed.toLong())
            val r = runReal(svc, f)
            var run = 0; var belowRun = 0
            r.trace.forEachIndexed { i, (_, inside) ->
                total++
                if (f[i].second >= ENTER) spikes++
                if (inside) {
                    inSamples++; run++
                    belowRun = if (f[i].second < ENTER - HYST) belowRun + 1 else 0
                    assertTrue(
                        "이탈선 아래 ${belowRun}표본 연속인데 억제 유지 seed=${seed} i=${i} - 디바운스 누수",
                        belowRun < EXIT_SAMPLES
                    )
                    if (run > worstRun) worstRun = run
                } else { run = 0; belowRun = 0 }
            }
        }
        val pct = inSamples * 100.0 / total
        val spikePct = spikes * 100.0 / total
        val cap = spikePct * (1 + EXIT_SAMPLES) + 1.0
        assertTrue("존 밖 억제 체류 %.2f%% - 스파이크 기여 상한 %.2f%% 초과".format(pct, cap), pct < cap)
        println(("[오진입] 존 밖 ${MC_SEEDS}시드: 스파이크 %.2f%%, 억제 체류 %.2f%%(상한 %.2f%%), " +
                "최장 연속 ${worstRun}표본 - 이탈선 아래 ${EXIT_SAMPLES}표본이면 반드시 해제")
                .format(spikePct, pct, cap))
    }

    /**
     * Source check. The real-code run above already catches a changed entry/exit count, hysteresis or dead-band branch;
     * this is the only check on ZONE_LOST_GRACE_MS (10 s) and ZONE_SIGNAL_STALE_MS (30 s).
     */
    @Test
    fun source_zone_constants_and_dead_band_branch_match_the_mirror() {
        val src = serviceSource("BleService.kt")
        fun has(re: String, what: String) =
            assertTrue("BleService.kt 가 미러와 어긋남 — ${what}", Regex(re).containsMatchIn(src))

        has("""ZONE_MIN_SAMPLES\s*=\s*${MIN_SAMPLES}\b""", "ZONE_MIN_SAMPLES=${MIN_SAMPLES}")
        has("""ZONE_EXIT_HYST_DB\s*=\s*${HYST}\b""", "ZONE_EXIT_HYST_DB=${HYST}")
        has("""ZONE_EXIT_SAMPLES\s*=\s*${EXIT_SAMPLES}\b""", "ZONE_EXIT_SAMPLES=${EXIT_SAMPLES}")
        has("""ZONE_LOST_GRACE_MS\s*=\s*10_000L""", "ZONE_LOST_GRACE_MS=10_000L")
        has("""ZONE_SIGNAL_STALE_MS\s*=\s*30_000L""", "ZONE_SIGNAL_STALE_MS=30_000L")
        // Resetting the sample counter in the dead band would keep 3 samples (EXIT_SAMPLES)
        // from ever accumulating while the signal fluctuates around the boundary.
        has("""else\s*->\s*Unit""", "데드밴드 분기가 else -> Unit 이어야 한다")
        assertTrue(
            "데드밴드에서 zoneSampleMap 리셋이 되살아났다",
            !Regex("""else\s*->\s*zoneSampleMap\[beaconKey\]\s*=\s*0""").containsMatchIn(src)
        )
        println("[소스 가드] ZONE_* 상수 4종 + 데드밴드 분기 일치 — 미러 유효")
    }
}
