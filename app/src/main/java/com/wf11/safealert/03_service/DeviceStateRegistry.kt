package com.wf11.safealert.service

/**
 * Single path for removing per-device state.
 *
 * This registry declares, one line per map, "which state is cleared at which point", so adding a map
 * means registering it here instead of editing BleService.onDeviceLost, the startScanHealthCheck prune
 * and BleService.stopAll in three places.
 *
 * Slot groups (removal timing differs — merging them would change decision behavior):
 *   - immediate : removed as soon as the signal is lost. Also included in clearAll.
 *   - deferred  : removed only on a cold clear (no preserved snapshot) or TTL expiry.
 *                 Warm filter preservation — the path that keeps filter warm-up state for rediscovery.
 *   - teardown  : clearAll only. Excluded from per-device purge.
 *                 filterPreserveMap belongs here — onDeviceLost fires from two paths (individual
 *                 timeout and a bulk forEach), so if per-device purge cleared this map the second
 *                 loss would fall into the cold branch and destroy the preserved snapshot set by the
 *                 first. No leak — the TTL expiry prune always removes it after 30s.
 *
 * Decision logic is untouched. This class owns only "what is cleared, and in what order".
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

    /** Cumulative purge/purgeDeferred call count (for instrumentation). */
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

    // ── Registration ──────────────────────────────────────────────────────────────
    fun addImmediate(name: String, map: MutableMap<String, *>) = add(immediateSlots, mapSlot(name, map))
    fun addImmediate(name: String, set: MutableSet<String>)    = add(immediateSlots, setSlot(name, set))
    fun addImmediate(name: String, purge: (String) -> Unit, clear: () -> Unit, size: (() -> Int)? = null) =
        add(immediateSlots, Slot(name, purge, clear, size))

    fun addDeferred(name: String, map: MutableMap<String, *>) = add(deferredSlots, mapSlot(name, map))
    fun addDeferred(name: String, purge: (String) -> Unit, clear: () -> Unit, size: (() -> Int)? = null) =
        add(deferredSlots, Slot(name, purge, clear, size))

    /** clearAll only — excluded from per-device purge (see the teardown note above). */
    fun addTeardown(name: String, map: MutableMap<String, *>) = add(teardownSlots, mapSlot(name, map))

    // ── Removal ──────────────────────────────────────────────────────────────
    /**
     * Removes device state. [cold] = no preserved snapshot (cold clear) → the deferred group is removed too.
     * If warm (snapshot exists), only immediate is cleared and filters/Kalman are left to the TTL prune.
     */
    fun purge(deviceId: String, cold: Boolean) {
        purgeCount++
        immediateSlots.forEach { it.purge(deviceId) }
        if (cold) deferredSlots.forEach { it.purge(deviceId) }
    }

    /** TTL expiry confirmed — removes only the deferred group (healthCheck prune path). */
    fun purgeDeferred(deviceId: String) {
        purgeCount++
        deferredSlots.forEach { it.purge(deviceId) }
    }

    /** Service stop — empties all three groups. */
    fun clearAll() {
        immediateSlots.forEach { it.clear() }
        deferredSlots.forEach { it.clear() }
        teardownSlots.forEach { it.clear() }
    }

    // ── Instrumentation ─────────────────────────────────────────────────────
    fun slotCount(): Int = immediateSlots.size + deferredSlots.size + teardownSlots.size

    /** Total entries across slots that expose size — filters (no size accessor) are left out. */
    fun entryCount(): Int =
        (immediateSlots + deferredSlots + teardownSlots).sumOf { it.size?.invoke() ?: 0 }

    /** Entry count of one slot — null if unregistered or size is not exposed. */
    fun sizeOf(name: String): Int? =
        (immediateSlots + deferredSlots + teardownSlots).firstOrNull { it.name == name }?.size?.invoke()

    companion object {
        /**
         * Live reference for developer-settings instrumentation.
         * Set by BleService.onCreate right after registration and cleared by onDestroy (prevents a service leak).
         * Read-only instrumentation path — decisions never use this reference.
         */
        @Volatile
        @JvmStatic
        var live: DeviceStateRegistry? = null
    }
}
