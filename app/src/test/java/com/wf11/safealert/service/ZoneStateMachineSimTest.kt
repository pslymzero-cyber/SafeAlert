package com.wf11.safealert.service

import com.wf11.safealert.support.BleServiceTestHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.util.Random
import kotlin.math.roundToInt

/**
 * (v1.1.79 검증) 세이프존 상태 머신 시뮬레이션.
 *
 * 과거 시뮬은 Python 재구현 모델이었다 — 재구현이 실제 Kotlin 과 갈라지면 시뮬은 자기 모델만
 * 검증하고 앱 결함은 그대로 통과시킨다. 그래서 이 테스트는 4단으로 짠다.
 *   1) 실제 BleService.onZoneBeaconSignal / reevaluateZones 를 리플렉션으로 직접 구동한다.
 *   2) 통계용 미러가 실제 코드와 표본 단위로 완전 일치하는지 먼저 교차검증한다(불일치 = 즉시 실패).
 *   3) 검증된 미러로만 "수정 전(구)" 대조군을 다시드 돌려 개선폭을 수치화한다.
 *   4) 소스의 상수·데드밴드 분기를 파일에서 읽어 assert — 본체가 바뀌면 미러가 낡았다고 여기서 터진다.
 *
 * 시간 제어: 진입 판정은 시간 무관(표본 카운트만)이고, 시간 의존은 reevaluateZones 뿐이다.
 * zoneLastSeenMap 을 리플렉션으로 과거로 밀면 실시간 대기 없이 GRACE·STALE 를 재현할 수 있다.
 */
@RunWith(RobolectricTestRunner::class)
class ZoneStateMachineSimTest {

    private companion object {
        const val KEY = "AA:BB:CC:DD:EE:FF"
        const val ENTER = -80            // 존 비콘 기본 진입 임계(dBm)
        const val MIN_SAMPLES = 1        // v1.1.82: 3 -> 1(신호 받는 동안 안전 모드)
        const val HYST = 5
        const val EXIT_SAMPLES = 3       // v1.1.83: 이탈 디바운스(단발 페이드로 억제가 끊기던 증상)
        const val GRACE_MS = 10_000L     // v1.1.82: 3s -> 10s(느린 비콘 표본 사이 유지)
        const val NEW_STALE_MS = 30_000L // v1.1.79 현재
        const val OLD_STALE_MS = 4_000L  // v1.1.79 직전(대조군)
        const val XCHECK_SEEDS = 10
        const val MC_SEEDS = 200
    }

    private val sideFx = ArrayList<Throwable>()

    // ── 시나리오 정의 ────────────────────────────────────────────────────────
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

    /** (직전 표본 이후 경과ms, rssi) 프레임 열 — 순수함수라 실제/미러가 같은 입력을 본다. */
    private fun frames(s: Scn, seed: Long): List<Pair<Long, Int>> {
        val r = Random(seed)
        return (0 until s.n).map { i ->
            val dt = if (s.gapEvery > 0 && i > 0 && i % s.gapEvery == 0) s.gapMs else s.stepMs
            dt to (s.mean + r.nextGaussian() * s.sigma).roundToInt()
        }
    }

    private data class Res(val enterAt: Int, val finalInside: Boolean, val trace: List<Pair<Int, Boolean>>)

    // ── 1) 실제 BleService 구동 ──────────────────────────────────────────────
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

    /** 판정 맵 갱신은 함수 앞머리에서 끝난다 — 꼬리의 전파(오버레이/알림/광고) 예외는 수집만 하고 삼킨다. */
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

    /** lastSeen 을 dtMs 만큼 과거로 밀고 폴링 1회 — GRACE/STALE 는 멱등이라 구간당 1회로 등가. */
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

    // ── 2) 미러 (교차검증 통과 후에만 통계에 쓴다) ───────────────────────────
    private class Mirror(val deadbandResets: Boolean, val staleMs: Long) {
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
                else -> if (deadbandResets) sample = 0
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

    // ── 테스트 ───────────────────────────────────────────────────────────────

    @Test
    fun `01 교차검증 - 미러가 실제 BleService 와 표본단위로 일치한다`() {
        val svc = BleServiceTestHarness.newService()
        var n = 0
        scenarios.forEach { s ->
            (0 until XCHECK_SEEDS).forEach { seed ->
                val f = frames(s, seed.toLong())
                val real = runReal(svc, f)
                val mir = runMirror(Mirror(deadbandResets = false, staleMs = NEW_STALE_MS), f)
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
    fun `02 실제 코드 - 존 밖 신호로는 억제가 지속되지 않는다`() {
        // v1.1.82 로 진입 표본이 1이 되면서 '절대 미진입'은 더 이상 성립하지 않는다 —
        // -92 평균/s5 는 표본당 0.8% 로 임계(-80)를 스치고, 그 1표본은 진입을 만든다.
        // v1.1.83 이탈 디바운스가 붙으면서 그 스파이크 억제는 즉시가 아니라 이탈선 아래
        // EXIT_SAMPLES 표본을 받아야 풀린다. 그래서 안전 속성을 기계적으로 정확한 하나로 좁힌다:
        //   억제가 유지되는 동안 이탈선(-85) 아래 표본이 EXIT_SAMPLES 개 연속될 수 없다.
        // 데드밴드(-85..-80) 체류로 억제가 이어지는 것은 히스테리시스의 의도된 동작이므로
        // 그쪽은 금지하지 않고 체류 비율 상한(스파이크 기여분)으로만 묶는다.
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

    @Test
    fun `03 대조 몬테카를로 - 수정 전후 진입 성공률`() {
        val sb = StringBuilder("\n[세이프존 진입 성공률 — 수정 전(구) vs v1.1.79]\n")
        sb.append(String.format("%-32s %8s %8s %14s%n", "시나리오", "구", "현재", "진입표본 구>현"))
        val regressions = ArrayList<String>()

        scenarios.forEach { s ->
            var oldOk = 0; var newOk = 0; var oldSum = 0; var newSum = 0
            (0 until MC_SEEDS).forEach { seed ->
                val f = frames(s, seed.toLong())
                val o = runMirror(Mirror(deadbandResets = true, staleMs = OLD_STALE_MS), f)
                val nw = runMirror(Mirror(deadbandResets = false, staleMs = NEW_STALE_MS), f)
                if (o.enterAt > 0) { oldOk++; oldSum += o.enterAt }
                if (nw.enterAt > 0) { newOk++; newSum += nw.enterAt }
            }
            val oldPct = oldOk * 100.0 / MC_SEEDS
            val newPct = newOk * 100.0 / MC_SEEDS
            val oldAvg = if (oldOk > 0) "%.1f".format(oldSum.toDouble() / oldOk) else "-"
            val newAvg = if (newOk > 0) "%.1f".format(newSum.toDouble() / newOk) else "-"
            sb.append(String.format("%-32s %7.1f%% %7.1f%% %14s%n", s.name, oldPct, newPct, "${oldAvg}>${newAvg}"))
            // 존 밖(D)은 양쪽 0% 가 정상. 그 외에서 현재가 구보다 나빠지면 회귀다.
            if (!s.name.startsWith("D") && newPct < oldPct) regressions += "${s.name}: ${oldPct}% -> ${newPct}%"
        }
        println(sb)
        assertTrue("수정이 진입 성공률을 떨어뜨린 시나리오: ${regressions}", regressions.isEmpty())
    }

    @Test
    fun `04 소스 가드 - 존 상수와 데드밴드 분기가 미러와 같은지`() {
        val f = listOf(
            File("src/main/java/com/wf11/safealert/03_service/BleService.kt"),
            File("app/src/main/java/com/wf11/safealert/03_service/BleService.kt")
        ).first { it.exists() }
        val src = f.readText()
        fun has(re: String, what: String) =
            assertTrue("BleService.kt 가 미러와 어긋남 — ${what}", Regex(re).containsMatchIn(src))

        has("""ZONE_MIN_SAMPLES\s*=\s*${MIN_SAMPLES}\b""", "ZONE_MIN_SAMPLES=${MIN_SAMPLES}")
        has("""ZONE_EXIT_HYST_DB\s*=\s*${HYST}\b""", "ZONE_EXIT_HYST_DB=${HYST}")
        has("""ZONE_EXIT_SAMPLES\s*=\s*${EXIT_SAMPLES}\b""", "ZONE_EXIT_SAMPLES=${EXIT_SAMPLES}")
        has("""ZONE_LOST_GRACE_MS\s*=\s*10_000L""", "ZONE_LOST_GRACE_MS=10_000L")
        has("""ZONE_SIGNAL_STALE_MS\s*=\s*30_000L""", "ZONE_SIGNAL_STALE_MS=30_000L")
        // 데드밴드에서 표본 카운터를 리셋하면 경계 요동 구간에서 3표본이 영원히 모이지 않는다.
        has("""else\s*->\s*Unit""", "데드밴드 분기가 else -> Unit 이어야 한다")
        assertTrue(
            "데드밴드에서 zoneSampleMap 리셋이 되살아났다",
            !Regex("""else\s*->\s*zoneSampleMap\[beaconKey\]\s*=\s*0""").containsMatchIn(src)
        )
        println("[소스 가드] ZONE_* 상수 4종 + 데드밴드 분기 일치 — 미러 유효")
    }
}
