package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Group contract tests for DeviceStateRegistry.
 *
 * The three groups being cleared at different times is why this class exists. Merging them changes judging.
 *   - if a warm loss (cold=false) cleared deferred, the filter warm-up state
 *   would be lost and the device misjudged right after rediscovery
 *   - if a per-device purge cleared teardown, a second loss would fall to cold and destroy the preserved snapshot
 * A plain JVM test with no Android dependency at all.
 */
class DeviceStateRegistryTest {

    private val immediate = mutableMapOf<String, Int>()
    private val deferred  = mutableMapOf<String, Int>()
    private val teardown  = mutableMapOf<String, Int>()
    private val flags     = mutableSetOf<String>()

    private fun newRegistry() = DeviceStateRegistry().apply {
        addImmediate("immediate", immediate)
        addImmediate("flags", flags)
        addDeferred("deferred", deferred)
        addTeardown("teardown", teardown)
    }

    private fun seed(id: String) {
        immediate[id] = 1
        deferred[id] = 1
        teardown[id] = 1
        flags.add(id)
    }

    /** One per-device clearing path and the groups it must leave. flagsKept = null: that row does not check flags. */
    private class PurgeRow(
        val label: String, val purge: (DeviceStateRegistry) -> Unit,
        val immediateKept: Boolean, val flagsKept: Boolean?, val deferredKept: Boolean
    )

    /**
     * Warm loss clears only immediate, so deferred (filter warm-up) survives; cold loss clears immediate and deferred;
     * the TTL-expiry prune clears deferred only. No per-device path touches teardown (the preserved snapshot): only
     * clearAll does.
     */
    @Test
    fun purgePaths_clearOnlyTheirGroups_andNeverTeardown() {
        val rows = listOf(
            PurgeRow("warm loss", { it.purge("A", cold = false) }, immediateKept = false, flagsKept = false, deferredKept = true),
            PurgeRow("cold loss", { it.purge("A", cold = true) }, immediateKept = false, flagsKept = null, deferredKept = false),
            PurgeRow("TTL prune", { it.purgeDeferred("A") }, immediateKept = true, flagsKept = null, deferredKept = false)
        )
        for ((i, r) in rows.withIndex()) {
            immediate.clear(); deferred.clear(); teardown.clear(); flags.clear()
            val reg = newRegistry()
            seed("A")

            r.purge(reg)

            val at = "row $i ${r.label}"
            if (r.immediateKept) assertEquals("$at: immediate kept", 1, immediate["A"])
            else assertNull("$at: immediate cleared", immediate["A"])
            r.flagsKept?.let { assertEquals("$at: A still in flags", it, "A" in flags) }
            if (r.deferredKept) assertEquals("$at: deferred kept", 1, deferred["A"])
            else assertNull("$at: deferred cleared", deferred["A"])
            assertEquals("$at: teardown is left to clearAll", 1, teardown["A"])
        }
    }

    /** Service stop = all three groups emptied. Zero entries left. */
    @Test
    fun clearAll_leavesNothing() {
        val reg = newRegistry()
        listOf("A", "B", "C").forEach { seed(it) }

        reg.clearAll()

        assertEquals("clearAll 후 잔여 엔트리는 0 이어야 한다", 0, reg.entryCount())
    }

    /** Registering the same name twice fails at once — a duplicate slot is a silent bug where removal runs twice. */
    @Test(expected = IllegalArgumentException::class)
    fun duplicateSlotName_isRejected() {
        DeviceStateRegistry().apply {
            addImmediate("dup", mutableMapOf<String, Int>())
            addImmediate("dup", mutableMapOf<String, Int>())
        }
    }
}
