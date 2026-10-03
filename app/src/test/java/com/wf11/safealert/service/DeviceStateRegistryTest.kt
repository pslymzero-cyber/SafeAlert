package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** Warm loss = only immediate is cleared. deferred (filter warm-up) and teardown (preserved snapshot) survive. */
    @Test
    fun purgeWarm_keepsDeferredAndTeardown() {
        val reg = newRegistry()
        seed("A")

        reg.purge("A", cold = false)

        assertNull("웜 소실은 immediate 를 지운다", immediate["A"])
        assertTrue("웜 소실은 flags 를 지운다", "A" !in flags)
        assertEquals("웜 소실은 deferred 를 보존한다(필터 워밍)", 1, deferred["A"])
        assertEquals("기기별 purge 는 teardown 을 건드리지 않는다", 1, teardown["A"])
    }

    /** Cold loss = immediate + deferred cleared. teardown is cleared only by clearAll, so it stays. */
    @Test
    fun purgeCold_alsoClearsDeferred_butNotTeardown() {
        val reg = newRegistry()
        seed("A")

        reg.purge("A", cold = true)

        assertNull(immediate["A"])
        assertNull("콜드 소실은 deferred 까지 지운다", deferred["A"])
        assertEquals("콜드여도 teardown 은 기기별 purge 대상이 아니다", 1, teardown["A"])
    }

    /** TTL-expiry prune path = deferred only. */
    @Test
    fun purgeDeferred_touchesDeferredOnly() {
        val reg = newRegistry()
        seed("A")

        reg.purgeDeferred("A")

        assertEquals("immediate 는 그대로", 1, immediate["A"])
        assertNull("deferred 만 지운다", deferred["A"])
        assertEquals("teardown 은 그대로", 1, teardown["A"])
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
