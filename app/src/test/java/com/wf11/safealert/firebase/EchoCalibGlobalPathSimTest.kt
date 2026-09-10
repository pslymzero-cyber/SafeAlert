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

    private fun node(id: String, model: String, vararg peers: Pair<String, EchoPeerStat>) =
        EchoCalibNode(id, model, peers.toMap())

    private fun stat(m: Double, n: Int, iqr: Double = 1.0) = EchoPeerStat(m, n, iqr)

    /** S1. 전출 기기 — v1.1.84(사업장별 분리) 에서는 버려지고, v1.1.85(전역) 에서는 살아난다. */
    @Test
    fun s1_전출기기_피어행이_전역풀에서_살아난다() {
        val myNode = node("A", MY, "B" to stat(3.0, 100))

        // v1.1.84: 내 사업장 노드만 보인다 — B 는 옛 사업장에 남아 modelById[B] == null
        val v84 = FirebaseManager.aggregateEchoPriors(listOf(myNode), MY, IQR_GATE)
        assertEquals("v1.1.84 에서는 피어 모델 미상으로 통째 폐기", 0, v84.size)

        // v1.1.85: 전역 풀이라 B 노드가 같이 보인다
        val v85 = FirebaseManager.aggregateEchoPriors(
            listOf(myNode, node("B", PEER)), MY, IQR_GATE)
        assertEquals(1, v85.size)
        assertEquals(3.0, v85[PEER]!!.first, 1e-9)
        assertEquals(100, v85[PEER]!!.second)
    }

    /** S2. 롤아웃 중 신구 혼재 — 같은 기기가 구 경로(폴백 파싱)와 신 경로 양쪽에 존재.
     *  구 경로 노드는 업그레이드해도 삭제되지 않으므로 걸러내지 않으면 표본이 두 번 세어진다. */
    @Test
    fun s2_같은기기_구경로잔존_중복집계_안됨() {
        val 구경로_A = node("A", MY, "B" to stat(3.0, 100))   // echo_calib/SITE1/A (폴백 파싱)
        val 신경로_A = node("A", MY, "B" to stat(3.0, 100))   // echo_calib/A
        val B = node("B", PEER)

        val merged = FirebaseManager.mergeEchoNodes(listOf(구경로_A), listOf(신경로_A, B))
        assertEquals("같은 기기ID 노드는 하나로 접힌다", 2, merged.size)

        val r = FirebaseManager.aggregateEchoPriors(merged, MY, IQR_GATE)
        assertEquals("같은 기기 표본이 두 번 세어지면 안 된다", 100, r[PEER]!!.second)
    }

    /** S2b. 스테일 오염 — 구 경로에 남은 옛 측정값이 신규 측정값을 끌어당기면 안 된다. */
    @Test
    fun s2b_구경로_스테일값이_현재값을_오염시키지_않는다() {
        val 구경로_A = node("A", MY, "B" to stat(9.0, 100))   // 옛 측정 (오차 큰 시절)
        val 신경로_A = node("A", MY, "B" to stat(3.0, 100))   // 현재 측정
        val B = node("B", PEER)

        val merged = FirebaseManager.mergeEchoNodes(listOf(구경로_A), listOf(신경로_A, B))
        val r = FirebaseManager.aggregateEchoPriors(merged, MY, IQR_GATE)
        assertEquals("신 경로 현재값만 반영돼야 한다(오염되면 6.0)", 3.0, r[PEER]!!.first, 1e-9)
    }

    /** S2c. 구 경로에만 있는 기기(아직 업그레이드 안 한 단말)는 그대로 살아남는다. */
    @Test
    fun s2c_구경로_단독기기는_보존된다() {
        val 구경로_A = node("A", MY, "B" to stat(3.0, 100))
        val merged = FirebaseManager.mergeEchoNodes(listOf(구경로_A), listOf(node("B", PEER)))
        val r = FirebaseManager.aggregateEchoPriors(merged, MY, IQR_GATE)
        assertEquals(100, r[PEER]!!.second)
        assertEquals(3.0, r[PEER]!!.first, 1e-9)
    }

    /** S3. 반대칭 fold — 상대 노드가 나를 잰 표본은 부호가 뒤집혀 상쇄된다(회귀 확인). */
    @Test
    fun s3_양방향_fold_상쇄() {
        val a = node("A", MY, "B" to stat(3.0, 100))
        val b = node("B", PEER, "A" to stat(-3.0, 100))
        val r = FirebaseManager.aggregateEchoPriors(listOf(a, b), MY, IQR_GATE)
        assertEquals(3.0, r[PEER]!!.first, 1e-9)
        assertEquals(200, r[PEER]!!.second)
    }

    /** S4. 산포 게이트 — iqr 초과 표본은 전역 이동 후에도 여전히 걸러진다. */
    @Test
    fun s4_산포게이트_유지() {
        val a = node("A", MY, "B" to stat(3.0, 100, iqr = 99.0))
        val r = FirebaseManager.aggregateEchoPriors(listOf(a, node("B", PEER)), MY, IQR_GATE)
        assertTrue("노이즈 표본은 제외", r.isEmpty())
    }
}
