package com.wf11.safealert.firebase

import com.wf11.safealert.firebase.FirebaseManager.EchoCalibNode
import com.wf11.safealert.firebase.FirebaseManager.EchoPeerStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.85 (echo_calib 전역 경로 이동) · v1.1.86 (구·신 경로 중복 제거) 검증.
 * downloadEchoCalibAll 이 만들어낼 노드 리스트를 손으로 재현해
 * mergeEchoNodes → aggregateEchoPriors 로 흘린다(프로덕션과 같은 순서).
 */
class EchoCalibGlobalPathSimTest {

    private val MY = "SM-A536N"      // 내 모델
    private val PEER = "SM-G991N"    // 상대 모델
    private val IQR_GATE = 6.0
    private val CAP = 3000            // (v1.1.97) 표본 비중 상한 = echoCalMinTicks 기본값. 아래 S1~S4 의 n(100)은 상한 아래

    private fun node(id: String, model: String, vararg peers: Pair<String, EchoPeerStat>) =
        EchoCalibNode(id, model, peers.toMap())

    private fun stat(m: Double, n: Int, iqr: Double = 1.0) = EchoPeerStat(m, n, iqr)

    /** S1. 전출 기기 — v1.1.84(사업장별 분리) 에서는 버려지고, v1.1.85(전역) 에서는 살아난다. */
    @Test
    fun s1_전출기기_피어행이_전역풀에서_살아난다() {
        val myNode = node("A", MY, "B" to stat(3.0, 100))

        // v1.1.84: 내 사업장 노드만 보인다 — B 는 옛 사업장에 남아 modelById[B] == null
        val v84 = FirebaseManager.aggregateEchoPriors(listOf(myNode), MY, IQR_GATE, CAP)
        assertEquals("v1.1.84 에서는 피어 모델 미상으로 통째 폐기", 0, v84.size)

        // v1.1.85: 전역 풀이라 B 노드가 같이 보인다
        val v85 = FirebaseManager.aggregateEchoPriors(
            listOf(myNode, node("B", PEER)), MY, IQR_GATE, CAP)
        assertEquals(1, v85.size)
        assertEquals(3.0, v85[PEER]!!.first, 1e-9)
        assertEquals(100, v85[PEER]!!.second)
    }

    /**
     * S2b. 롤아웃 중 신구 혼재 — 같은 기기가 구 경로(폴백 파싱)와 신 경로 양쪽에 존재한다. 구 경로 노드는
     *  업그레이드해도 삭제되지 않으므로, 하나로 접히지 않으면 표본이 두 번 세어지고 옛 측정값이 신규 값을 끌어당긴다.
     */
    @Test
    fun s2b_구경로_스테일값이_현재값을_오염시키지_않는다() {
        val 구경로_A = node("A", MY, "B" to stat(9.0, 100))   // 옛 측정 (오차 큰 시절)
        val 신경로_A = node("A", MY, "B" to stat(3.0, 100))   // 현재 측정
        val B = node("B", PEER)

        val merged = FirebaseManager.mergeEchoNodes(listOf(구경로_A), listOf(신경로_A, B))
        assertEquals("같은 기기ID 노드는 하나로 접힌다", 2, merged.size)
        val r = FirebaseManager.aggregateEchoPriors(merged, MY, IQR_GATE, CAP)
        assertEquals("신 경로 현재값만 반영돼야 한다(오염되면 6.0)", 3.0, r[PEER]!!.first, 1e-9)
        assertEquals("같은 기기 표본이 두 번 세어지면 안 된다", 100, r[PEER]!!.second)
    }

    /** S2c. 구 경로에만 있는 기기(아직 업그레이드 안 한 단말)는 그대로 살아남는다. */
    @Test
    fun s2c_구경로_단독기기는_보존된다() {
        val 구경로_A = node("A", MY, "B" to stat(3.0, 100))
        val merged = FirebaseManager.mergeEchoNodes(listOf(구경로_A), listOf(node("B", PEER)))
        val r = FirebaseManager.aggregateEchoPriors(merged, MY, IQR_GATE, CAP)
        assertEquals(100, r[PEER]!!.second)
        assertEquals(3.0, r[PEER]!!.first, 1e-9)
    }

    /** S3. 반대칭 fold — 상대 노드가 나를 잰 표본은 부호가 뒤집혀 상쇄된다(회귀 확인). */
    @Test
    fun s3_양방향_fold_상쇄() {
        val a = node("A", MY, "B" to stat(3.0, 100))
        val b = node("B", PEER, "A" to stat(-3.0, 100))
        val r = FirebaseManager.aggregateEchoPriors(listOf(a, b), MY, IQR_GATE, CAP)
        assertEquals(3.0, r[PEER]!!.first, 1e-9)
        assertEquals(200, r[PEER]!!.second)
    }

    /** S4. 산포 게이트 — iqr 초과 표본은 전역 이동 후에도 여전히 걸러진다. */
    @Test
    fun s4_산포게이트_유지() {
        val a = node("A", MY, "B" to stat(3.0, 100, iqr = 99.0))
        val r = FirebaseManager.aggregateEchoPriors(listOf(a, node("B", PEER)), MY, IQR_GATE, CAP)
        assertTrue("노이즈 표본은 제외", r.isEmpty())
    }

    /** S5. (v1.1.97) n=500000 극단값 노드 1개 + 정상 노드 3개(n=상한).
     *  n 가중 평균이면 (2×9000 + 40×500000)/509000 ≈ 39.3dB 로 극단값이 결과를 가져간다.
     *  비중 상한 + 가중 중앙값이면 극단값 1개는 결과를 움직이지 못한다. */
    @Test
    fun s5_극단값_노드_하나는_결과를_끌지_못한다() {
        val normal = (1..3).map { node("A$it", MY, "B" to stat(2.0, CAP)) }
        val extreme = node("X", MY, "B" to stat(40.0, 500_000))
        val r = FirebaseManager.aggregateEchoPriors(normal + extreme + node("B", PEER), MY, IQR_GATE, CAP)
        val (m, n) = r[PEER]!!
        assertEquals("정상 노드 값 그대로", 2.0, m, 1e-9)
        assertEquals("Σn 은 같은 비중의 합(게이트 3000 통과)", 4 * CAP, n)
    }

    /** S7. (v1.1.97) 비중이 같은 표본 2개면 두 값의 가운데 — 평균과 같다. */
    @Test
    fun s7_표본_2개면_두_값의_가운데() {
        val r = FirebaseManager.aggregateEchoPriors(listOf(
            node("A1", MY, "B" to stat(2.0, CAP)),
            node("A2", MY, "B" to stat(4.0, CAP)),
            node("B", PEER)), MY, IQR_GATE, CAP)
        assertEquals(3.0, r[PEER]!!.first, 1e-9)
    }

    /** S6. (v1.1.97) 상한 = 게이트면 게이트 통과 여부는 그대로 — 큰 n 노드 1개도 Σn=상한으로 통과, 작은 n 은 그대로. */
    @Test
    fun s6_상한과_게이트가_같으면_통과_여부가_바뀌지_않는다() {
        val big = FirebaseManager.aggregateEchoPriors(
            listOf(node("A", MY, "B" to stat(3.0, 500_000)), node("B", PEER)), MY, IQR_GATE, CAP)
        assertEquals(CAP, big[PEER]!!.second)
        val small = FirebaseManager.aggregateEchoPriors(
            listOf(node("A", MY, "B" to stat(3.0, 2_999)), node("B", PEER)), MY, IQR_GATE, CAP)
        assertEquals(2_999, small[PEER]!!.second)
    }
}
