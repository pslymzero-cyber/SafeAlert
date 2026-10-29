package com.wf11.safealert.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Measure-only UWB keep: the pure keep rule and the sample split that keeps measured samples out of every
 * decision-feeding store (distance map, kinematics, onUwbSample, status line).
 */
@RunWith(RobolectricTestRunner::class)
class UwbMeasureTest {

    @Test
    fun measureKeep_rule_gate_start_gate_and_yield() {
        val rssi = mapOf("A" to -90, "B" to -97, "C" to -96, "D" to -95, "E" to -79, "X" to -84, "Y" to -86)
        val of: (String) -> Int? = { rssi[it] }
        fun keep(members: List<String>, others: List<String>, gate: Int?) = UwbRanger.measureKeep(members, others, of, gate)

        assertEquals(emptyList<String>(), keep(listOf("A"), emptyList(), null))              // switches off → nothing
        assertEquals(listOf("A"), keep(listOf("A"), emptyList(), -95))                       // -90 > -95
        assertEquals(emptyList<String>(), keep(listOf("C", "D"), emptyList(), -95))          // -96 and -95 are dropped
        assertEquals(emptyList<String>(), keep(listOf("Z"), emptyList(), -95))               // unknown RSSI
        assertEquals(emptyList<String>(), keep(listOf("E"), emptyList(), -95))               // -79: above the start gate = judge peer
        assertEquals(emptyList<String>(), keep(listOf("A"), listOf("X"), -95))               // outside peer at -84 → yield to standby
        assertEquals(listOf("A"), keep(listOf("A"), listOf("Y"), -95))                       // outside peer at -86 → keep
        assertEquals(listOf("A"), keep(listOf("A", "B"), emptyList(), -95))                  // -97 is dropped, -90 stays
    }

    @Test
    fun deliverSample_measured_peer_never_reaches_decision_stores() {
        val judged = ArrayList<Pair<String, Float>>()
        val measured = ArrayList<Pair<String, Float>>()
        val ranger = UwbRanger(
            context = RuntimeEnvironment.getApplication(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            myFullId = "SAFEALERT_WALKER_SELFTEST",
            myIsVehicle = false,
            onUwbSample = { id, d -> judged.add(id to d) },
            onMeasureSample = { id, d -> measured.add(id to d) }
        )
        ranger.measureIds.add("P")

        ranger.deliverSample("P", 3f, 1_000)
        assertEquals(listOf("P" to 3f), measured)
        assertEquals(0, judged.size)
        assertFalse(ranger.uwbDistances.containsKey("P"))
        assertNull(ranger.uwbKinematics["P"])

        ranger.deliverSample("Q", 3f, 1_000)
        assertEquals(listOf("Q" to 3f), judged)
        assertEquals(1, measured.size)
        assertEquals(3f, ranger.uwbDistances["Q"])
    }
}
