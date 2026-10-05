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
    private val CAP = 3000            // Per-sample weight cap = echoCalMinTicks default. n (100) in the path tests below is under the cap

    private fun node(id: String, model: String, vararg peers: Pair<String, EchoPeerStat>) =
        EchoCalibNode(id, model, peers.toMap())

    private fun stat(m: Double, n: Int, iqr: Double = 1.0) = EchoPeerStat(m, n, iqr)

    /** Device moved to another site — dropped under a per-site split, kept with the global pool. */
    @Test
    fun 전출기기_피어행이_전역풀에서_살아난다() {
        val myNode = node("A", MY, "B" to stat(3.0, 100))

        // Per-site split: only my site's nodes are visible — B stays at its old site, so modelById[B] == null
        val perSite = FirebaseManager.aggregateEchoPriors(listOf(myNode), MY, IQR_GATE, CAP)
        assertEquals("사업장별로 나누면 피어 모델을 몰라 통째 폐기된다", 0, perSite.size)

        // Global pool: B's node is visible too
        val global = FirebaseManager.aggregateEchoPriors(
            listOf(myNode, node("B", PEER)), MY, IQR_GATE, CAP)
        assertEquals(1, global.size)
        assertEquals(3.0, global[PEER]!!.first, 1e-9)
        assertEquals(100, global[PEER]!!.second)
    }

    /** Old-path and new-path nodes merged, then aggregated. mergedSize = null: that row does not check the node count. */
    private class MergeRow(
        val label: String, val oldPath: List<EchoCalibNode>, val newPath: List<EchoCalibNode>, val mergedSize: Int?,
        val mean: Double, val n: Int
    )

    /**
     * Old and new paths mixed during rollout: old-path nodes (fallback parsing) are not deleted on upgrade. A device on
     * both paths folds into one node with the new-path value, so its samples are not counted twice and the old
     * measurement does not drag the result; a device only on the old path (a handset not yet upgraded) survives as is.
     */
    @Test
    fun 구경로_노드는_기기ID로_신경로에_접히고_신경로_값이_이기며_구경로에만_있는_기기는_남는다() {
        val rows = listOf(
            // old measurement 9.0 (from when the error was large) vs current 3.0: 6.0 if the old value leaked in
            MergeRow("같은 기기가 두 경로에 있다", listOf(node("A", MY, "B" to stat(9.0, 100))),
                listOf(node("A", MY, "B" to stat(3.0, 100)), node("B", PEER)), mergedSize = 2, mean = 3.0, n = 100),
            MergeRow("구경로에만 있는 기기", listOf(node("A", MY, "B" to stat(3.0, 100))), listOf(node("B", PEER)),
                mergedSize = null, mean = 3.0, n = 100)
        )
        for ((i, r) in rows.withIndex()) {
            val at = "row $i ${r.label}"
            val merged = FirebaseManager.mergeEchoNodes(r.oldPath, r.newPath)
            r.mergedSize?.let { assertEquals("$at: 같은 기기ID 노드는 하나로 접힌다", it, merged.size) }
            val res = FirebaseManager.aggregateEchoPriors(merged, MY, IQR_GATE, CAP)
            assertEquals("$at: 신경로 현재값만 반영된다", r.mean, res[PEER]!!.first, 1e-9)
            assertEquals("$at: 같은 기기 표본은 한 번만 센다", r.n, res[PEER]!!.second)
        }
    }

    /**
     * Antisymmetric fold — samples the peer node took of me are sign-flipped, offsetting the antisymmetry.
     */
    @Test
    fun 양방향_fold_상쇄() {
        val a = node("A", MY, "B" to stat(3.0, 100))
        val b = node("B", PEER, "A" to stat(-3.0, 100))
        val r = FirebaseManager.aggregateEchoPriors(listOf(a, b), MY, IQR_GATE, CAP)
        assertEquals(3.0, r[PEER]!!.first, 1e-9)
        assertEquals(200, r[PEER]!!.second)
    }

    /** Spread gate — samples over the iqr gate are still filtered out with the global path. */
    @Test
    fun 산포게이트_유지() {
        val a = node("A", MY, "B" to stat(3.0, 100, iqr = 99.0))
        val r = FirebaseManager.aggregateEchoPriors(listOf(a, node("B", PEER)), MY, IQR_GATE, CAP)
        assertTrue("노이즈 표본은 제외", r.isEmpty())
    }

    /**
     * One extreme node with n=500000 + 3 normal nodes (n=cap).
     *  With an n-weighted mean, (2×9000 + 40×500000)/509000 ≈ 39.3dB — the extreme value takes over the result.
     *  With the weight cap + weighted median, one extreme value cannot move the result.
     */
    @Test
    fun 극단값_노드_하나는_결과를_끌지_못한다() {
        val normal = (1..3).map { node("A$it", MY, "B" to stat(2.0, CAP)) }
        val extreme = node("X", MY, "B" to stat(40.0, 500_000))
        val r = FirebaseManager.aggregateEchoPriors(normal + extreme + node("B", PEER), MY, IQR_GATE, CAP)
        val (m, n) = r[PEER]!!
        assertEquals("정상 노드 값 그대로", 2.0, m, 1e-9)
        assertEquals("Σn 은 같은 비중의 합(게이트 3000 통과)", 4 * CAP, n)
    }

    /** Two samples of equal weight give the midpoint of the two values — same as the mean. */
    @Test
    fun 표본_2개면_두_값의_가운데() {
        val r = FirebaseManager.aggregateEchoPriors(listOf(
            node("A1", MY, "B" to stat(2.0, CAP)),
            node("A2", MY, "B" to stat(4.0, CAP)),
            node("B", PEER)), MY, IQR_GATE, CAP)
        assertEquals(3.0, r[PEER]!!.first, 1e-9)
    }

    /**
     * A device alone in its model pair: a huge n stops exactly at the cap (= the gate, so it still passes), and an n just
     * under the cap stays as is (so it still falls short). The gate comparison itself is not exercised here.
     */
    @Test
    fun 혼자인_기기의_표본_합은_상한에서_멈추고_상한_아래는_그대로() {
        val big = FirebaseManager.aggregateEchoPriors(
            listOf(node("A", MY, "B" to stat(3.0, 500_000)), node("B", PEER)), MY, IQR_GATE, CAP)
        assertEquals(CAP, big[PEER]!!.second)
        val small = FirebaseManager.aggregateEchoPriors(
            listOf(node("A", MY, "B" to stat(3.0, 2_999)), node("B", PEER)), MY, IQR_GATE, CAP)
        assertEquals(2_999, small[PEER]!!.second)
    }
}
