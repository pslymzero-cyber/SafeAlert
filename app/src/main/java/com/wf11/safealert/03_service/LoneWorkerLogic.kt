package com.wf11.safealert.service

/**
 * 단독 작업자 무동작·낙상 SOS 상태기계 (v1.1.99).
 *
 * 안드로이드 의존이 없는 순수 로직이다. 시각은 전부 호출자가 넘기는 elapsedRealtime 기준 ms 다.
 *
 *   WATCHING --무동작 stillMs / 낙상--> CHECKING --응답 없이 responseMs--> SOS --괜찮음--> WATCHING
 *
 * 정착한 안전구역(원시 안쪽 60초 연속) 안에서는 트리거·승격이 없고, 정착 시 열린 확인은 취소된다.
 * SOS 는 구역 진입·기능 끄기로 끝나지 않고 오직 cancelSos 로만 끝난다.
 * 동료 SOS 수신은 서버(RTDB) 기록과 BLE 비트를 bleId 별 한 항목으로 합친다.
 */
class LoneWorkerLogic(var myBleId: String) {

    enum class Mode { WATCHING, CHECKING, SOS }

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
        val fromServer: Boolean,
        val resolvedAtMs: Long
    )

    companion object {
        const val ZONE_SETTLE_MS = 60_000L
        const val BEACON_HINT_MS = 60_000L
        const val PEER_RESOLVE_GUARD_MS = 30_000L
        const val RESOLVED_KEEP_MS = 60_000L
        /** BLE 로만 보이던 동료가 이 시간 이상 안 보이다 다시 SOS 비트를 켜면 새 신호로 본다 (v1.1.99). */
        const val PEER_BLE_GAP_MS = 30_000L
        private const val BEACON_SAMPLE_CAP = 256
    }

    /** 설정에서 라이브로 바꾼다 (기본 3분 / 2분). */
    var stillMs = 180_000L
    var responseMs = 120_000L

    var mode = Mode.WATCHING
        private set
    /** "still" 또는 "fall". WATCHING 에서는 빈 문자열. */
    var trigger = ""
        private set
    var modeSinceMs = 0L
        private set
    var zoneSettled = false
        private set

    val sosActive: Boolean get() = mode == Mode.SOS
    val peers: Map<String, Peer> get() = peerMap

    private var enabled = true
    private var startedAt = 0L
    private var lastMovedAt = Long.MIN_VALUE
    private var lastAckAt = Long.MIN_VALUE
    private var enabledAt = Long.MIN_VALUE
    private var zoneLeftAt = Long.MIN_VALUE
    private var zoneInside = false
    private var zoneInsideSince = 0L
    private var pendingFall = false

    private val peerMap = LinkedHashMap<String, Peer>()
    private val bleLast = HashMap<String, Boolean>()
    private val bleSeenAt = HashMap<String, Long>()

    private class BeaconSample(val label: String, val rssi: Int, val tMs: Long)
    private val beaconSamples = ArrayDeque<BeaconSample>()

    // ── 본인 상태 ──────────────────────────────────────────────

    fun start(nowMs: Long, zoneInside: Boolean) {
        startedAt = nowMs
        mode = Mode.WATCHING
        trigger = ""
        modeSinceMs = nowMs
        pendingFall = false
        zoneSettled = false
        this.zoneInside = zoneInside
        zoneInsideSince = nowMs
    }

    fun setEnabled(on: Boolean, nowMs: Long) {
        if (on == enabled) return
        enabled = on
        if (on) {
            enabledAt = nowMs
        } else {
            pendingFall = false
            // 열린 확인은 취소, 진행 중인 SOS 는 유지 (D-05)
            if (mode == Mode.CHECKING) toWatching(nowMs)
        }
    }

    /** 움직임은 타이머만 갱신한다. 열린 확인은 절대 닫지 않는다 (D-02, D-07). */
    fun onMoved(nowMs: Long) {
        if (nowMs > lastMovedAt) lastMovedAt = nowMs
    }

    fun onFall(nowMs: Long) {
        if (!enabled || zoneSettled || mode != Mode.WATCHING) return
        pendingFall = true
    }

    fun onZone(inside: Boolean, nowMs: Long) {
        updateSettle(nowMs)
        if (inside) {
            if (!zoneInside) {
                zoneInside = true
                zoneInsideSince = nowMs
            }
        } else if (zoneInside) {
            zoneInside = false
            if (zoneSettled) {
                zoneSettled = false
                zoneLeftAt = nowMs
            }
        }
    }

    fun tick(nowMs: Long) {
        updateSettle(nowMs)
        if (enabled && !zoneSettled) {
            when (mode) {
                Mode.WATCHING -> {
                    if (pendingFall) {
                        pendingFall = false
                        toChecking("fall", nowMs)
                    } else if (nowMs - stillStart() >= stillMs) {
                        toChecking("still", nowMs)
                    }
                }
                Mode.CHECKING -> {
                    if (nowMs - modeSinceMs >= responseMs) {
                        mode = Mode.SOS
                        modeSinceMs = nowMs
                    }
                }
                Mode.SOS -> {}
            }
        }
        // 해제된 동료 항목은 잠시 보여준 뒤 정리
        peerMap.entries.removeAll { !it.value.active && nowMs - it.value.resolvedAtMs >= RESOLVED_KEEP_MS }
    }

    fun ackWorking(nowMs: Long): Boolean {
        if (mode != Mode.CHECKING) return false
        lastAckAt = nowMs
        toWatching(nowMs)
        return true
    }

    fun cancelSos(nowMs: Long): Boolean {
        if (mode != Mode.SOS) return false
        lastAckAt = nowMs
        toWatching(nowMs)
        return true
    }

    /**
     * 저장된 본인 SOS 로 복원한다 (서비스 재시작·프로세스 사망 뒤). start() 뒤에 호출한다.
     * SOS 는 cancelSos 로만 끝나므로 구역 정착·기능 끄기·ackWorking 으로는 벗어나지 않는다 (v1.1.99).
     */
    fun restoreSos(trigger: String, nowMs: Long) {
        mode = Mode.SOS
        this.trigger = trigger
        modeSinceMs = nowMs
        pendingFall = false
    }

    fun responseLeftMs(nowMs: Long): Long =
        if (mode == Mode.CHECKING) (responseMs - (nowMs - modeSinceMs)).coerceAtLeast(0L) else 0L

    private fun stillStart(): Long =
        maxOf(startedAt, lastMovedAt, lastAckAt, enabledAt, zoneLeftAt)

    private fun updateSettle(nowMs: Long) {
        if (zoneInside && !zoneSettled && nowMs - zoneInsideSince >= ZONE_SETTLE_MS) {
            zoneSettled = true
            pendingFall = false
            if (mode == Mode.CHECKING) toWatching(nowMs)
        }
    }

    private fun toWatching(nowMs: Long) {
        mode = Mode.WATCHING
        trigger = ""
        modeSinceMs = nowMs
        pendingFall = false
    }

    private fun toChecking(trig: String, nowMs: Long) {
        mode = Mode.CHECKING
        trigger = trig
        modeSinceMs = nowMs
    }

    // ── 동료 SOS (D-06) ────────────────────────────────────────

    fun onPeerServer(
        key: String, bleId: String, name: String, role: String, trigger: String,
        beacon: String, createdAtMs: Long, active: Boolean, nowMs: Long
    ) {
        if (bleId == myBleId) return
        val cur = peerMap[bleId]
        if (active) {
            if (cur != null && cur.active) {
                if (cur.key == null || cur.key == key) {
                    // 같은 에피소드 병합: 묵음 상태 유지
                    peerMap[bleId] = cur.copy(
                        key = key, name = name, role = role, trigger = trigger,
                        beacon = beacon, createdAtMs = createdAtMs, fromServer = true
                    )
                } else if (createdAtMs < cur.createdAtMs) {
                    // 더 오래된 에피소드 기록은 새 것을 덮지 못한다 (서버 기록끼리만 시각 비교)
                } else {
                    // 다른 키 = 새 에피소드: 묵음 해제, 다시 울린다
                    peerMap[bleId] = Peer(
                        bleId, key, name, role, trigger, beacon, createdAtMs,
                        firstSeenMs = nowMs, active = true, silenced = false,
                        fromServer = true, resolvedAtMs = 0L
                    )
                }
            } else if (cur != null && cur.key == key) {
                // 이미 해제된 같은 기록의 늦은 중복 전달 — 되살리지 않는다
            } else {
                peerMap[bleId] = Peer(
                    bleId, key, name, role, trigger, beacon, createdAtMs,
                    firstSeenMs = nowMs, active = true, silenced = false,
                    fromServer = true, resolvedAtMs = 0L
                )
            }
        } else {
            if (cur == null) {
                // 해제 기록만 먼저 온 경우도 BLE 잔상 가드용으로 남긴다
                peerMap[bleId] = Peer(
                    bleId, key, name, role, trigger, beacon, createdAtMs,
                    firstSeenMs = nowMs, active = false, silenced = false,
                    fromServer = true, resolvedAtMs = nowMs
                )
            } else if (cur.key == key) {
                peerMap[bleId] = cur.copy(active = false, resolvedAtMs = nowMs)
            }
            // 키가 다르거나 BLE 전용(키 없음)이면 무시: 접속 때 재생된 옛 해제가 살아 있는 BLE 경보를 끄면 안 된다
        }
    }

    fun onPeerBle(bleId: String, sos: Boolean, nowMs: Long) {
        if (bleId == myBleId) return
        val prev = bleLast[bleId]
        val tracked = sos || prev != null
        val unseenMs = bleSeenAt[bleId]?.let { nowMs - it }
        if (tracked) { bleLast[bleId] = sos; bleSeenAt[bleId] = nowMs }
        // 30초 이상 안 보이다 다시 켜진 비트도 상승 에지다 (D-06). 하강 판정은 원시 이전 상태를 쓴다.
        val rising = sos && (prev != true || (unseenMs != null && unseenMs >= PEER_BLE_GAP_MS))
        val falling = !sos && prev == true
        val cur = peerMap[bleId]
        if (rising) {
            // 해제 직후 남은 광고 잔상(이력 없는 첫 true)은 가드 시간 동안 무시.
            // 관측된 false 를 거쳐 다시 true 가 되면 새 에피소드다.
            val revive = cur == null || (!cur.active &&
                (prev == false || nowMs - cur.resolvedAtMs > PEER_RESOLVE_GUARD_MS))
            if (revive) {
                peerMap[bleId] = Peer(
                    bleId, null, "", "", "", "", nowMs,
                    firstSeenMs = nowMs, active = true, silenced = false,
                    fromServer = false, resolvedAtMs = 0L
                )
            } else if (cur != null && cur.active && cur.key == null) {
                // BLE 전용 항목이 공백 뒤 다시 켜짐: 새 에피소드
                peerMap[bleId] = cur.copy(silenced = false, firstSeenMs = nowMs, createdAtMs = nowMs)
            }
        } else if (falling) {
            if (cur != null && cur.active && !cur.fromServer) {
                peerMap[bleId] = cur.copy(active = false, resolvedAtMs = nowMs)
            }
        }
    }

    fun silencePeers() {
        peerMap.entries.removeAll { !it.value.active }
        for ((id, p) in peerMap.entries.toList()) {
            if (p.active) peerMap[id] = p.copy(silenced = true)
        }
    }

    fun audiblePeers(): List<Peer> = peerMap.values.filter { it.active && !it.silenced }

    // ── 최근 가장 강한 비콘 힌트 ───────────────────────────────

    fun noteBeacon(label: String, rssi: Int, nowMs: Long) {
        beaconSamples.addLast(BeaconSample(label, rssi, nowMs))
        while (beaconSamples.isNotEmpty() &&
            (beaconSamples.size > BEACON_SAMPLE_CAP || nowMs - beaconSamples.first().tMs > BEACON_HINT_MS)
        ) beaconSamples.removeFirst()
    }

    fun beaconHint(nowMs: Long): Pair<String, Int>? {
        var best: BeaconSample? = null
        for (s in beaconSamples) {
            if (nowMs - s.tMs > BEACON_HINT_MS) continue
            if (best == null || s.rssi > best.rssi) best = s
        }
        return best?.let { it.label to it.rssi }
    }
}
