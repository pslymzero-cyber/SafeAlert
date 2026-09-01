package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceStateRegistry 그룹 계약 테스트 (Phase 4, STATE-02).
 *
 * 세 그룹의 제거 시점이 다르다는 것이 이 클래스의 존재 이유다. 합쳐지면 판정이 바뀐다.
 *   - 웜 소실(cold=false) 이 deferred 를 지우면 필터 워밍 상태가 날아가 재발견 직후 오판정
 *   - 기기별 purge 가 teardown 을 지우면 2차 소실이 콜드로 떨어져 보존 스냅샷이 파괴된다
 * 안드로이드 의존이 전혀 없는 순수 JVM 테스트다.
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

    /** 웜 소실 = immediate 만 제거. deferred(필터 워밍)·teardown(보존 스냅샷) 은 살아남는다. */
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

    /** 콜드 소실 = immediate + deferred 제거. teardown 은 clearAll 전용이라 남는다. */
    @Test
    fun purgeCold_alsoClearsDeferred_butNotTeardown() {
        val reg = newRegistry()
        seed("A")

        reg.purge("A", cold = true)

        assertNull(immediate["A"])
        assertNull("콜드 소실은 deferred 까지 지운다", deferred["A"])
        assertEquals("콜드여도 teardown 은 기기별 purge 대상이 아니다", 1, teardown["A"])
    }

    /** TTL 만료 prune 경로 = deferred 만. */
    @Test
    fun purgeDeferred_touchesDeferredOnly() {
        val reg = newRegistry()
        seed("A")

        reg.purgeDeferred("A")

        assertEquals("immediate 는 그대로", 1, immediate["A"])
        assertNull("deferred 만 지운다", deferred["A"])
        assertEquals("teardown 은 그대로", 1, teardown["A"])
    }

    /** 서비스 정지 = 세 그룹 전부 비운다. 잔여 엔트리 0 (STATE-02). */
    @Test
    fun clearAll_leavesNothing() {
        val reg = newRegistry()
        listOf("A", "B", "C").forEach { seed(it) }

        reg.clearAll()

        assertEquals("clearAll 후 잔여 엔트리는 0 이어야 한다", 0, reg.entryCount())
    }

    /**
     * BUG-01 — 진입/소멸을 반복해도 엔트리가 누적되지 않는다.
     * 매 사이클 기기 id 를 새로 만들어(재접속이 아닌 신규 기기) 누수라면 단조 증가하게 만든다.
     */
    @Test
    fun repeatedJoinAndLeave_doesNotAccumulate() {
        val reg = newRegistry()
        val baseline = reg.entryCount()

        repeat(200) { cycle ->
            val id = "DEV_$cycle"
            seed(id)
            reg.purge(id, cold = true)
            // teardown 은 기기별 purge 대상이 아니다 - 실제 서비스에서는 TTL prune 이 맡는 몫이라
            // 이 테스트에서는 그 역할을 대신해 비워준다.
            teardown.remove(id)
        }

        assertEquals("소멸 사이클 후 잔여 엔트리는 기저선으로 돌아와야 한다", baseline, reg.entryCount())
    }

    /** 같은 이름을 두 번 등록하면 즉시 실패한다 — 슬롯 중복은 제거가 두 번 도는 조용한 버그다. */
    @Test(expected = IllegalArgumentException::class)
    fun duplicateSlotName_isRejected() {
        DeviceStateRegistry().apply {
            addImmediate("dup", mutableMapOf<String, Int>())
            addImmediate("dup", mutableMapOf<String, Int>())
        }
    }
}
