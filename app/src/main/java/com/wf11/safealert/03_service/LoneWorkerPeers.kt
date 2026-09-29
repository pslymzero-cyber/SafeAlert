package com.wf11.safealert.service

/**
 * 동료 구조 요청 저장소 (v1.1.99). 안드로이드 의존이 없는 순수 로직이며 시각은 호출자가 넘기는 elapsed ms 다.
 *
 * 동료 구조 요청을 회차(bleId, ep) 단위 항목으로 다룬다.
 * - 서버 기록과 BLE 광고가 같은 회차면 한 항목, 회차나 서버 키가 다르면 다른 항목이다.
 *   다른 항목은 이름·비콘·묵음·해제를 각자 가진다.
 * - 회차 0 기록은 서버 키로만 다룬다(BLE 항목을 가져가거나 끝내지 않는다).
 * - 30초 공백 규칙: 회차와 무관하게, 서버 기록이 없는 항목에서 광고가 30초 이상 끊겼다 다시 들리면
 *   묵음만 푼다(발생 시각 유지). 같은 ID·같은 회차를 쓰는 다른 폰은 이 규칙과 해제 뒤 30초 가드가 맡는다.
 * - BLE 전용 항목은 false 가 10초 연속으로 보여야 해제한다.
 * - 해제된 항목은 60초 보관한다. 그동안 같은 회차 광고 잔상(해제 뒤 30초 이내)은 무시한다.
 * - 확인은 넘겨받은 회차 ID 만 묵음으로 만든다. 아직 항목이 없는 ID 는 60초 안에 생기는 그 항목만 묵음이다.
 */
class LoneWorkerPeers {

    data class Peer(
        val bleId: String,
        val key: String?,
        val name: String,
        val role: String,
        val trigger: String,
        val beacon: String,
        val createdAtMs: Long,
        val firstSeenMs: Long,
        val active: Boolean,
        val silenced: Boolean,
        val resolvedAtMs: Long,
        /** SOS 회차 번호 (1..255, 0 = 모름). */
        val episode: Int,
        /** 이 항목의 마지막 BLE sos=true 시각. */
        val lastBleMs: Long = Long.MIN_VALUE
    ) {
        val fromServer: Boolean get() = key != null
        /** 저장소 키. */
        val id: String get() = if (key != null) "k:$key" else "b:$bleId#$episode"
        /** 확인 대상 ID. 서버 키를 얻어도 바뀌지 않는다. */
        val epId: String get() = "$bleId#$episode"
    }

    companion object {
        const val PEER_RESOLVE_GUARD_MS = 30_000L
        const val RESOLVED_KEEP_MS = 60_000L
        const val PEER_BLE_GAP_MS = 30_000L
        const val PEER_BLE_FALL_MS = 10_000L
        const val PENDING_SILENCE_MS = 60_000L
    }

    private val map = LinkedHashMap<String, Peer>()
    /** bleId 별 첫 false 시각. BLE 전용 활성 항목이 있을 때만 기록한다. */
    private val falseSince = HashMap<String, Long>()
    /** 항목 없이 확인된 회차 ID -> 만료 시각. */
    private val pendingSilence = HashMap<String, Long>()

    val all: Collection<Peer> get() = map.values

    fun onServer(
        key: String, bleId: String, name: String, role: String, trigger: String,
        beacon: String, createdAtMs: Long, active: Boolean, nowMs: Long, ep: Int
    ) {
        val kId = "k:$key"
        val bId = "b:$bleId#$ep"
        if (!active) {
            val cur = map[kId]
            if (cur != null && cur.active) {
                map[kId] = resolve(cur, nowMs)
            } else if (ep != 0) {
                val b = map[bId]
                if (b != null && b.active) map[bId] = resolve(b, nowMs)
            }
            // 추적하지 않는 해제(접속 때 재생된 옛 기록)는 항목을 만들지 않는다.
            return
        }
        val cur = map[kId]
        if (cur != null) {
            // 해제된 같은 기록의 늦은 중복 전달은 되살리지 않는다.
            if (cur.active) {
                map[kId] = cur.copy(
                    name = name, role = role, trigger = trigger,
                    beacon = beacon.ifEmpty { cur.beacon }, createdAtMs = createdAtMs
                )
            }
            return
        }
        val b = if (ep != 0) map[bId] else null
        if (b != null) {
            // 같은 회차를 BLE 로 먼저 봤다: 서버 키를 붙인다. BLE 로 해제됐어도 서버가 켜져 있다면 다시 울린다.
            map.remove(bId)
            map[kId] = Peer(
                bleId, key, name, role, trigger, beacon.ifEmpty { b.beacon }, createdAtMs,
                firstSeenMs = b.firstSeenMs, active = true, silenced = b.active && b.silenced,
                resolvedAtMs = 0L, episode = ep, lastBleMs = b.lastBleMs
            )
            return
        }
        map[kId] = Peer(
            bleId, key, name, role, trigger, beacon, createdAtMs,
            firstSeenMs = nowMs, active = true, silenced = takePending("$bleId#$ep", nowMs),
            resolvedAtMs = 0L, episode = ep
        )
    }

    fun onBle(bleId: String, sos: Boolean, nowMs: Long, ep: Int, beacon: String) {
        if (!sos) {
            if (map.values.none { it.bleId == bleId && it.key == null && it.active }) return
            falseSince.putIfAbsent(bleId, nowMs)
            settle(bleId, nowMs)
            return
        }
        falseSince.remove(bleId)
        val matches = map.values.filter { it.bleId == bleId && it.episode == ep }
        if (matches.any { it.active }) {
            for (p in matches) {
                if (!p.active) continue
                val gap = p.key == null && p.lastBleMs != Long.MIN_VALUE && nowMs - p.lastBleMs >= PEER_BLE_GAP_MS
                map[p.id] = p.copy(
                    lastBleMs = nowMs,
                    silenced = p.silenced && !gap,
                    beacon = if (p.key == null) p.beacon.ifEmpty { beacon } else p.beacon
                )
            }
            return
        }
        // 해제 직후 남은 광고 잔상은 가드 시간 동안 무시한다.
        if (matches.any { nowMs - it.resolvedAtMs <= PEER_RESOLVE_GUARD_MS }) return
        val bId = "b:$bleId#$ep"
        map.remove(bId)
        map[bId] = Peer(
            bleId, null, "", "", "", beacon, nowMs,
            firstSeenMs = nowMs, active = true, silenced = takePending("$bleId#$ep", nowMs),
            resolvedAtMs = 0L, episode = ep, lastBleMs = nowMs
        )
    }

    /** ids == null 이면 지금 목록의 모든 항목을 묵음으로 만든다. 해제된 항목도 지우지 않는다(잔상 가드 유지). */
    fun silence(nowMs: Long, ids: Collection<String>?) {
        if (ids == null) {
            for (p in map.values.toList()) map[p.id] = p.copy(silenced = true)
            return
        }
        for (id in ids) {
            val hit = map.values.filter { it.epId == id }
            if (hit.isEmpty()) pendingSilence[id] = nowMs + PENDING_SILENCE_MS
            for (p in hit) map[p.id] = p.copy(silenced = true)
        }
    }

    fun audible(): List<Peer> = map.values.filter { it.active && !it.silenced }

    fun tick(nowMs: Long) {
        for (id in falseSince.keys.toList()) settle(id, nowMs)
        map.values.removeAll { !it.active && nowMs - it.resolvedAtMs >= RESOLVED_KEEP_MS }
        val ids = map.values.mapTo(HashSet()) { it.bleId }
        falseSince.keys.retainAll(ids)
        pendingSilence.values.removeAll { it <= nowMs }
    }

    private fun settle(bleId: String, nowMs: Long) {
        val since = falseSince[bleId] ?: return
        if (nowMs - since < PEER_BLE_FALL_MS) return
        for (p in map.values.toList()) {
            if (p.bleId == bleId && p.key == null && p.active) map[p.id] = resolve(p, nowMs)
        }
        falseSince.remove(bleId)
    }

    /** 해제 시 묵음을 풀어 '해제됨' 줄이 보이게 한다. */
    private fun resolve(p: Peer, nowMs: Long) = p.copy(active = false, resolvedAtMs = nowMs, silenced = false)

    private fun takePending(epId: String, nowMs: Long): Boolean {
        val until = pendingSilence.remove(epId) ?: return false
        return until > nowMs
    }
}
