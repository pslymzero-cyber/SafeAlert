package com.wf11.safealert.service

/**
 * 동료 구조 요청 저장소 (v1.1.99). 안드로이드 의존이 없는 순수 로직이며 시각은 호출자가 넘기는 elapsed ms 다.
 *
 * 동료 구조 요청을 회차(bleId, ep) 단위 항목으로 다룬다.
 * - 서버 기록과 BLE 광고가 같은 회차면 한 항목, 회차나 서버 키가 다르면 다른 항목이다.
 *   다른 항목은 이름·비콘·묵음·해제를 각자 가진다.
 * - 회차 0 기록은 서버 키로만 다룬다(BLE 항목을 가져가거나 끝내지 않는다).
 * - 30초 공백 규칙: 서버 기록 유무와 무관하게, 광고가 처음 들리거나 30초 이상 끊겼다 다시 들리면
 *   묵음만 푼다(발생 시각 유지). 같은 ID·같은 회차를 쓰는 다른 폰은 이 규칙과 해제 뒤 30초 가드가 맡는다.
 * - 서버 기록이 BLE 항목을 가져갈 때 묵음은 넘겨받지 않는다(이름과 함께 다시 울린다).
 * - 추적하지 않는 서버 해제 기록은 같은 회차 BLE 항목을, 해제 시각(이 기기 elapsed 로 환산)이 그 항목을
 *   처음 들은 시각 이후이고 광고가 15초 이상 들리지 않았을 때 끝낸다. 광고가 들려서만 거부된 해제는 보관했다가
 *   tick 에서 다시 판정한다. 재생된 옛 해제(처음 들은 시각 이전)는 들리는 항목을 끝내지 않는다.
 * - BLE 전용 항목은 false 가 10초 연속으로 보여야 해제한다.
 * - 해제된 항목은 60초 보관한다. 그동안 같은 회차 광고 잔상(해제 뒤 30초 이내)은 무시한다.
 * - 확인은 넘겨받은 항목 id 만 묵음으로 만든다(같은 회차의 다른 항목은 그대로 울린다).
 *   아직 없는 항목의 확인은 60초 동안 대기 묵음으로 남는다: 새 BLE 항목은 같은 회차면 묵음,
 *   서버 항목(생성·흡수·첫 BLE 청취)은 그 서버 키나 흡수한 BLE 항목의 대기 묵음일 때만 묵음이다.
 */

/** 화면·확인 게이트가 쓰는 동료 한 줄. id = 항목 id, epId = 회차 ID. */
data class PeerRow(val id: String, val epId: String, val line: String, val active: Boolean)

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

    /** 서버 기록 하나. resolvedLocalMs = 해제 시각을 이 기기 elapsed 로 환산한 값(모르면 null). */
    data class ServerRec(
        val key: String, val bleId: String, val name: String, val role: String, val trigger: String,
        val beacon: String, val createdAtMs: Long, val active: Boolean, val ep: Int, val resolvedLocalMs: Long?
    )

    companion object {
        const val PEER_RESOLVE_GUARD_MS = 30_000L
        const val RESOLVED_KEEP_MS = 60_000L
        const val PEER_BLE_GAP_MS = 30_000L
        const val PEER_BLE_FALL_MS = 10_000L
        const val PENDING_SILENCE_MS = 60_000L
        /** 추적하지 않는 해제로 BLE 항목을 끝내려면 광고가 이만큼 끊겨 있어야 한다. */
        const val PEER_LIVE_AD_MS = 15_000L
        /** 서버 시각 오프셋을 모를 때 시각 비교에 더하는 여유. */
        const val CLOCK_SLACK_MS = 10_000L

        /** 서버 해제 시각을 elapsed 로 환산한다. 서버 시각을 모르면 벽시계로 보고 여유만큼 늦춘다. */
        fun resolvedLocalMs(resolvedAtMs: Long, serverNowMs: Long?, wallNowMs: Long, nowMs: Long): Long? =
            if (resolvedAtMs <= 0L) null
            else nowMs - ((serverNowMs ?: wallNowMs) - resolvedAtMs) - (if (serverNowMs == null) CLOCK_SLACK_MS else 0L)
    }

    private val map = LinkedHashMap<String, Peer>()
    /** bleId 별 첫 false 시각. BLE 전용 활성 항목이 있을 때만 기록한다. */
    private val falseSince = HashMap<String, Long>()
    /** 항목 없이 확인된 항목 id / 회차 ID -> 만료 시각. */
    private val pendingIds = HashMap<String, Long>()
    private val pendingEps = HashMap<String, Long>()
    /** 광고가 들려서만 거부된 해제: BLE 항목 id -> 환산 해제 시각. */
    private val pendingResolve = HashMap<String, Long>()

    val all: Collection<Peer> get() = map.values

    fun onServer(rec: ServerRec, nowMs: Long) {
        val (key, bleId, name, role, trigger, beacon, createdAtMs, active, ep) = rec
        val kId = "k:$key"
        val epId = "$bleId#$ep"
        val bId = "b:$epId"
        if (!active) {
            val cur = map[kId]
            val b = if (ep != 0) map[bId] else null
            val r = rec.resolvedLocalMs
            if (cur != null && cur.active) {
                map[kId] = resolve(cur, nowMs)
            } else if (b != null && b.active && r != null && r >= b.firstSeenMs) {
                if (adGone(b, nowMs)) map[bId] = resolve(b, nowMs) else pendingResolve[bId] = r
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
            // 같은 회차를 BLE 로 먼저 봤다: 서버 키를 붙이고 이름과 함께 다시 울린다. BLE 로 해제됐어도 서버가 켜져 있으면 울린다.
            map.remove(bId)
            map[kId] = Peer(
                bleId, key, name, role, trigger, beacon.ifEmpty { b.beacon }, createdAtMs,
                firstSeenMs = b.firstSeenMs, active = true, silenced = coldServer(kId, epId, nowMs),
                resolvedAtMs = 0L, episode = ep, lastBleMs = b.lastBleMs
            )
            return
        }
        map[kId] = Peer(
            bleId, key, name, role, trigger, beacon, createdAtMs,
            firstSeenMs = nowMs, active = true, silenced = coldServer(kId, epId, nowMs),
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
                val first = p.lastBleMs == Long.MIN_VALUE
                val gap = first || nowMs - p.lastBleMs >= PEER_BLE_GAP_MS
                // 첫 청취라도 서비스 재시작 전의 확인(대기 묵음)이 이 서버 항목을 가리키면 묵음을 유지한다.
                val keep = !gap || (first && p.key != null && coldServer(p.id, p.epId, nowMs))
                map[p.id] = p.copy(
                    lastBleMs = nowMs,
                    silenced = p.silenced && keep,
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
            firstSeenMs = nowMs, active = true, silenced = live(pendingEps["$bleId#$ep"], nowMs),
            resolvedAtMs = 0L, episode = ep, lastBleMs = nowMs
        )
    }

    /** targets = 항목 id -> 회차 ID. 있는 항목은 그 id 만 묵음(해제된 항목도 지우지 않는다 — 잔상 가드 유지), 없으면 대기 묵음. */
    fun silence(nowMs: Long, targets: Map<String, String>) {
        for ((id, epId) in targets) {
            val p = map[id]
            if (p != null) {
                map[id] = p.copy(silenced = true)
            } else {
                pendingIds[id] = nowMs + PENDING_SILENCE_MS
                pendingEps[epId] = nowMs + PENDING_SILENCE_MS
            }
        }
    }

    fun audible(): List<Peer> = map.values.filter { it.active && !it.silenced }

    fun tick(nowMs: Long) {
        for (id in falseSince.keys.toList()) settle(id, nowMs)
        val pr = pendingResolve.entries.iterator()
        while (pr.hasNext()) {
            val (bId, r) = pr.next()
            val b = map[bId]
            if (b == null || !b.active || r < b.firstSeenMs) pr.remove()
            else if (adGone(b, nowMs)) { map[bId] = resolve(b, nowMs); pr.remove() }
        }
        map.values.removeAll { !it.active && nowMs - it.resolvedAtMs >= RESOLVED_KEEP_MS }
        val ids = map.values.mapTo(HashSet()) { it.bleId }
        falseSince.keys.retainAll(ids)
        pendingIds.values.removeAll { it <= nowMs }
        pendingEps.values.removeAll { it <= nowMs }
    }

    private fun settle(bleId: String, nowMs: Long) {
        val since = falseSince[bleId] ?: return
        if (nowMs - since < PEER_BLE_FALL_MS) return
        for (p in map.values.toList()) {
            if (p.bleId == bleId && p.key == null && p.active) map[p.id] = resolve(p, nowMs)
        }
        falseSince.remove(bleId)
    }

    /** 광고가 15초 이상 들리지 않았는가. */
    private fun adGone(b: Peer, nowMs: Long) = b.lastBleMs == Long.MIN_VALUE || nowMs - b.lastBleMs >= PEER_LIVE_AD_MS

    /** 해제 시 묵음을 풀어 '해제됨' 줄이 보이게 한다. */
    private fun resolve(p: Peer, nowMs: Long) = p.copy(active = false, resolvedAtMs = nowMs, silenced = false)

    private fun live(until: Long?, nowMs: Long) = until != null && until > nowMs

    /** 서버 항목의 대기 묵음: 그 서버 키나 같은 회차 BLE 항목 id 가 확인됐는가. */
    private fun coldServer(kId: String, epId: String, nowMs: Long) =
        live(pendingIds[kId], nowMs) || live(pendingIds["b:$epId"], nowMs)
}

// ── LoneWorkerLogic 의 동료 SOS 위임(D-06): 회차 단위 항목은 LoneWorkerPeers 가 맡는다 ─────────
/** 서버 기록 수신 — LoneWorkerLogic 의 위임, 500줄 제한으로 여기 둔다. */
fun LoneWorkerLogic.onPeerServer(rec: LoneWorkerPeers.ServerRec, nowMs: Long) = peerStore.onServer(rec, nowMs)
/** BLE 비트 수신 — LoneWorkerLogic 의 위임, 500줄 제한으로 여기 둔다. */
fun LoneWorkerLogic.onPeerBle(bleId: String, sos: Boolean, nowMs: Long, episode: Int = 0, beacon: String = "") =
    peerStore.onBle(bleId, sos, nowMs, episode, beacon)
/** 확인 버튼. targets = 항목 id -> 회차 ID. 그 항목만 묵음으로 만든다 — LoneWorkerLogic 의 위임, 500줄 제한으로 여기 둔다. */
fun LoneWorkerLogic.silencePeers(nowMs: Long, targets: Map<String, String>) = peerStore.silence(nowMs, targets)
/** 울리는 동료 항목 — LoneWorkerLogic 의 위임, 500줄 제한으로 여기 둔다. */
fun LoneWorkerLogic.audiblePeers(): List<LoneWorkerPeers.Peer> = peerStore.audible()
