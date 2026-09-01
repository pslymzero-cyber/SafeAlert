package com.wf11.safealert.service

/**
 * [Phase 4 T1] 기기별 상태 제거 단일 경로(BUG-01 해소).
 *
 * 기존에는 상태 제거가 세 갈래로 흩어져 서로 협조하지 않았다.
 *   - BleService.onDeviceLost   — 나열식 `.remove(deviceId)` 40여 줄
 *   - startScanHealthCheck prune — 보존 스냅샷 TTL 만료분만 콜드 클리어
 *   - BleService.stopAll        — 나열식 `.clear()` 40여 줄
 * 맵을 하나 추가할 때 세 곳 모두 고쳐야 했고, 실제로 누락이 반복됐다.
 * 이 레지스트리는 "어떤 상태가 어느 시점에 지워지는가"를 선언 1줄로 모은다.
 *
 * 슬롯 그룹(제거 시점이 다르다 — 합치면 판정 동작이 바뀐다):
 *   - immediate : 신호 소실 즉시 제거. clearAll 에도 포함.
 *   - deferred  : 콜드 클리어(보존 스냅샷 없음) 또는 TTL 만료 때만 제거.
 *                 [v1.1.58 fix4] 웜 필터 보존 — 재발견 시 필터 워밍 상태를 살려두는 경로다.
 *   - teardown  : clearAll 전용. 기기별 purge 에서 제외한다.
 *                 filterPreserveMap 이 여기 속한다 — onDeviceLost 가 개별 타임아웃과 일괄
 *                 forEach 두 경로에서 발화하므로, 기기별 purge 가 이 맵을 지우면 2차 소실이
 *                 콜드 분기로 떨어져 1차가 세팅한 보존 스냅샷을 파괴한다. 누수는 없다 —
 *                 TTL 만료 prune 이 30s 후 무조건 제거한다.
 *
 * 판정 로직은 건드리지 않는다. 이 클래스는 "지우는 순서와 대상"만 소유한다.
 */
class DeviceStateRegistry {

    private class Slot(
        val name: String,
        val purge: (String) -> Unit,
        val clear: () -> Unit,
        val size: (() -> Int)?
    )

    private val immediateSlots = mutableListOf<Slot>()
    private val deferredSlots  = mutableListOf<Slot>()
    private val teardownSlots  = mutableListOf<Slot>()
    private val names          = mutableSetOf<String>()

    /** [Phase 4 T2 계기 대비] purge/purgeDeferred 호출 누적 횟수. */
    var purgeCount: Long = 0L
        private set

    private fun add(group: MutableList<Slot>, slot: Slot) {
        require(names.add(slot.name)) { "DeviceStateRegistry: 중복 등록 슬롯 '${slot.name}'" }
        group.add(slot)
    }

    private fun mapSlot(name: String, map: MutableMap<String, *>) =
        Slot(name, { id -> map.remove(id) }, { map.clear() }, { map.size })

    private fun setSlot(name: String, set: MutableSet<String>) =
        Slot(name, { id -> set.remove(id) }, { set.clear() }, { set.size })

    // ── 등록 ──────────────────────────────────────────────────────────────
    fun addImmediate(name: String, map: MutableMap<String, *>) = add(immediateSlots, mapSlot(name, map))
    fun addImmediate(name: String, set: MutableSet<String>)    = add(immediateSlots, setSlot(name, set))
    fun addImmediate(name: String, purge: (String) -> Unit, clear: () -> Unit, size: (() -> Int)? = null) =
        add(immediateSlots, Slot(name, purge, clear, size))

    fun addDeferred(name: String, map: MutableMap<String, *>) = add(deferredSlots, mapSlot(name, map))
    fun addDeferred(name: String, purge: (String) -> Unit, clear: () -> Unit, size: (() -> Int)? = null) =
        add(deferredSlots, Slot(name, purge, clear, size))

    /** clearAll 전용 — 기기별 purge 에서 제외된다(위 teardown 주석 참조). */
    fun addTeardown(name: String, map: MutableMap<String, *>) = add(teardownSlots, mapSlot(name, map))

    // ── 제거 ──────────────────────────────────────────────────────────────
    /**
     * 기기 상태 제거. [cold] = 보존 스냅샷 없음(콜드 클리어) → deferred 그룹까지 함께 제거.
     * 웜(스냅샷 있음)이면 immediate 만 지우고 필터·칼만은 TTL prune 에 맡긴다.
     */
    fun purge(deviceId: String, cold: Boolean) {
        purgeCount++
        immediateSlots.forEach { it.purge(deviceId) }
        if (cold) deferredSlots.forEach { it.purge(deviceId) }
    }

    /** TTL 만료 확정 — deferred 그룹만 제거(healthCheck prune 경로). */
    fun purgeDeferred(deviceId: String) {
        purgeCount++
        deferredSlots.forEach { it.purge(deviceId) }
    }

    /** 서비스 정지 — 세 그룹 전부 비운다. */
    fun clearAll() {
        immediateSlots.forEach { it.clear() }
        deferredSlots.forEach { it.clear() }
        teardownSlots.forEach { it.clear() }
    }

    // ── 계기(STATE-03) ─────────────────────────────────────────────────────
    fun slotCount(): Int = immediateSlots.size + deferredSlots.size + teardownSlots.size

    /** size 노출 슬롯의 엔트리 합계 — 필터류(size 접근자 없음)는 집계에서 빠진다. */
    fun entryCount(): Int =
        (immediateSlots + deferredSlots + teardownSlots).sumOf { it.size?.invoke() ?: 0 }

    /** [Phase 4 T2] 슬롯 하나의 엔트리 수 — 미등록이거나 size 미노출이면 null. */
    fun sizeOf(name: String): Int? =
        (immediateSlots + deferredSlots + teardownSlots).firstOrNull { it.name == name }?.size?.invoke()

    companion object {
        /**
         * [Phase 4 T2] 개발자 설정 계기용 라이브 참조(STATE-03).
         * BleService.onCreate 가 등록 직후 세팅하고 onDestroy 가 해제한다(서비스 누수 방지).
         * 읽기 전용 계기 경로다 — 판정은 이 참조를 쓰지 않는다.
         */
        @Volatile
        @JvmStatic
        var live: DeviceStateRegistry? = null
    }
}
