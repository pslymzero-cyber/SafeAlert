package com.wf11.safealert.firebase

import com.wf11.safealert.firebase.FirebaseManager.EchoCalibNode
import com.wf11.safealert.firebase.FirebaseManager.EchoPeerStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the global echo_calib path and de-duplication of old/new path nodes.
 * Rebuilds by hand the node list downloadEchoCalibAll would produce and
 * feeds it through mergeEchoNodes → aggregateEchoPriors (same order as production).
 */
class EchoCalibGlobalPathSimTest {

    private val MY = "SM-A536N"      // my model
    private val PEER = "SM-G991N"    // peer model
    private val IQR_GATE = 6.0
    private val CAP = 3000            // Per-sample weight cap = echoCalMinTicks default. n (100) in S1~S4 below is under the cap

    private fun node(id: String, model: String, vararg peers: Pair<String, EchoPeerStat>) =
        EchoCalibNode(id, model, peers.toMap())

    private fun stat(m: Double, n: Int, iqr: Double = 1.0) = EchoPeerStat(m, n, iqr)

    /** S1. Device moved to another site — dropped under a per-site split, kept with the global pool. */
    @Test
    fun s1_전출기기_피어행이_전역풀에서_살아난다() {
        val myNode = node("A", MY, "B" to stat(3.0, 100))

        // Per-site split: only my site's nodes are visible — B stays at its old site, so modelById[B] == null
        val v84 = FirebaseManager.aggregateEchoPriors(listOf(myNode), MY, IQR_GATE, CAP)
        assertEquals("v1.1.84 에서는 피어 모델 미상으로 통째 폐기", 0, v84.size)

        // Global pool: B's node is visible too
        val v85 = FirebaseManager.aggregateEchoPriors(
            listOf(myNode, node("B", PEER)), MY, IQR_GATE, CAP)
        assertEquals(1, v85.size)
        assertEquals(3.0, v85[PEER]!!.first, 1e-9)
        assertEquals(100, v85[PEER]!!.second)
    }

    /**
     * S2b. Old and new paths mixed during rollout — the same device exists on both the old path (fallback parsing) and the new path.
     *  Old-path nodes are not deleted on upgrade, so unless they fold into one,
     *  samples are counted twice and the old measurement drags the new value.
     */
    @Test
    fun s2b_구경로_스테일값이_현재값을_오염시키지_않는다() {
        val 구경로_A = node("A", MY, "B" to stat(9.0, 100))   // old measurement (from when the error was large)
        val 신경로_A = node("A", MY, "B" to stat(3.0, 100))   // current measurement
        val B = node("B", PEER)

        val merged = FirebaseManager.mergeEchoNodes(listOf(구경로_A), listOf(신경로_A, B))
        assertEquals("같은 기기ID 노드는 하나로 접힌다", 2, merged.size)
        val r = FirebaseManager.aggregateEchoPriors(merged, MY, IQR_GATE, CAP)
        assertEquals("신 경로 현재값만 반영돼야 한다(오염되면 6.0)", 3.0, r[PEER]!!.first, 1e-9)
        assertEquals("같은 기기 표본이 두 번 세어지면 안 된다", 100, r[PEER]!!.second)
    }

    /** S2c. A device only on the old path (a handset not yet upgraded) survives as is. */
    @Test
    fun s2c_구경로_단독기기는_보존된다() {
        val 구경로_A = node("A", MY, "B" to stat(3.0, 100))
        val merged = FirebaseManager.mergeEchoNodes(listOf(구경로_A), listOf(node("B", PEER)))
        val r = FirebaseManager.aggregateEchoPriors(merged, MY, IQR_GATE, CAP)
        assertEquals(100, r[PEER]!!.second)
        assertEquals(3.0, r[PEER]!!.first, 1e-9)
    }

    /**
     * S3. Antisymmetric fold — samples the peer node took of me are sign-flipped, offsetting the antisymmetry (regression check).
     */
    @Test
    fun s3_양방향_fold_상쇄() {
        val a = node("A", MY, "B" to stat(3.0, 100))
        val b = node("B", PEER, "A" to stat(-3.0, 100))
        val r = FirebaseManager.aggregateEchoPriors(listOf(a, b), MY, IQR_GATE, CAP)
        assertEquals(3.0, r[PEER]!!.first, 1e-9)
        assertEquals(200, r[PEER]!!.second)
    }

    /** S4. Spread gate — samples over the iqr gate are still filtered out with the global path. */
    @Test
    fun s4_산포게이트_유지() {
        val a = node("A", MY, "B" to stat(3.0, 100, iqr = 99.0))
        val r = FirebaseManager.aggregateEchoPriors(listOf(a, node("B", PEER)), MY, IQR_GATE, CAP)
        assertTrue("노이즈 표본은 제외", r.isEmpty())
    }

    /**
     * S5. One extreme node with n=500000 + 3 normal nodes (n=cap).
     *  With an n-weighted mean, (2×9000 + 40×500000)/509000 ≈ 39.3dB — the extreme value takes over the result.
     *  With the weight cap + weighted median, one extreme value cannot move the result.
     */
    @Test
    fun s5_극단값_노드_하나는_결과를_끌지_못한다() {
        val normal = (1..3).map { node("A$it", MY, "B" to stat(2.0, CAP)) }
        val extreme = node("X", MY, "B" to stat(40.0, 500_000))
        val r = FirebaseManager.aggregateEchoPriors(normal + extreme + node("B", PEER), MY, IQR_GATE, CAP)
        val (m, n) = r[PEER]!!
        assertEquals("정상 노드 값 그대로", 2.0, m, 1e-9)
        assertEquals("Σn 은 같은 비중의 합(게이트 3000 통과)", 4 * CAP, n)
    }

    /** S7. Two samples of equal weight give the midpoint of the two values — same as the mean. */
    @Test
    fun s7_표본_2개면_두_값의_가운데() {
        val r = FirebaseManager.aggregateEchoPriors(listOf(
            node("A1", MY, "B" to stat(2.0, CAP)),
            node("A2", MY, "B" to stat(4.0, CAP)),
            node("B", PEER)), MY, IQR_GATE, CAP)
        assertEquals(3.0, r[PEER]!!.first, 1e-9)
    }

    /**
     * S6. With cap = gate, passing the gate is unchanged — a single big-n node still passes with Σn=cap, and a small n stays as is.
     */
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
