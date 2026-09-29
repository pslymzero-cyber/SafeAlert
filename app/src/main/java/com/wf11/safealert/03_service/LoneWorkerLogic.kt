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
 *
 * 내 서버 기록은 작성자 uid 로 LoneWorkerSosSync 가 걸러내고, BLE 스캐너는 자기 광고를 받지 못한다.
 * 그래서 여기서는 bleId 로 나를 걸러내지 않는다 — 같은 장비 ID 를 나눠 쓰는 폰끼리도 서로 경보한다 (v1.1.99).
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
        val resolvedAtMs: Long,
        /** 광고로 받은 SOS 에피소드 번호 (1..255, 0 = 모름). 서버 키 에피소드와는 별개다. */
        val episode: Int = 0
    )

    companion object {
        const val ZONE_SETTLE_MS = 60_000L
        const val BEACON_HINT_MS = 60_000L
        const val PEER_RESOLVE_GUARD_MS = 30_000L
        const val RESOLVED_KEEP_MS = 60_000L
        /** 같은 bleId 의 true 가 이만큼 끊겼다 다시 오면 새 신호(상승 에지)다. */
        const val PEER_BLE_GAP_MS = 30_000L
        /** BLE 전용 항목은 false 가 이만큼 연속으로 보여야 해제한다. */
        const val PEER_BLE_FALL_MS = 10_000L
        /** 빈 목록에서 확인을 누르면 이 시간 동안 새로 생기는 항목을 묵음으로 만든다. */
        const val COLD_SILENCE_MS = 60_000L
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
    /** 외부 전원(PDA 충전 거치대) 연결 중: 무동작·낙상 확인만 쉰다. 구조 요청(SOS)과 동료 경보에는 영향이 없다. */
    var charging = false
        private set
    private var chargeEndAt = Long.MIN_VALUE

    private val peerMap = LinkedHashMap<String, Peer>()
    /** bleId 별 BLE 관측: 마지막 true 시각, 그 뒤 첫 false 시각(Long.MIN_VALUE = 없음), 마지막 에피소드 번호. */
    private class BleTrack(var lastTrueAt: Long, var falseSince: Long, var ep: Int)
    private val bleTrack = HashMap<String, BleTrack>()
    private var coldSilenceUntil = Long.MIN_VALUE

    private class BeaconSample(val label: String, val rssi: Int, val tMs: Long, val sid: Int)
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

    /** 충전 시작이면 열린 확인은 답한 것으로 닫고 낙상 대기를 버린다. 충전 해제면 무동작 시간을 다시 센다. */
    fun setCharging(on: Boolean, nowMs: Long) {
        if (on == charging) return
        charging = on
        if (on) {
            pendingFall = false
            if (mode == Mode.CHECKING) {
                lastAckAt = nowMs
                toWatching(nowMs)
            }
        } else {
            chargeEndAt = nowMs
        }
    }

    /** 움직임은 타이머만 갱신한다. 열린 확인은 절대 닫지 않는다 (D-02, D-07). */
    fun onMoved(nowMs: Long) {
        if (nowMs > lastMovedAt) lastMovedAt = nowMs
    }

    fun onFall(nowMs: Long) {
        if (!enabled || zoneSettled || charging || mode != Mode.WATCHING) return
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
        if (enabled && !zoneSettled && !charging) {
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
        for ((id, t) in bleTrack.entries.toList()) settleBle(id, t, nowMs)
        // 해제된 동료 항목은 잠시 보여준 뒤 정리한다. 항목이 사라지면 그 bleId 의 BLE 기록도 버려 다음 비트는 새 항목이 된다.
        val gone = peerMap.entries
            .filter { !it.value.active && nowMs - it.value.resolvedAtMs >= RESOLVED_KEEP_MS }
            .map { it.key }
        for (id in gone) {
            peerMap.remove(id)
            bleTrack.remove(id)
        }
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
        maxOf(startedAt, lastMovedAt, lastAckAt, enabledAt, zoneLeftAt, chargeEndAt)

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
        beacon: String, createdAtMs: Long, active: Boolean, nowMs: Long, ep: Int = 0
    ) {
        val cur = peerMap[bleId]
        if (active) {
            if (cur != null && cur.active) {
                if (cur.key == key) {
                    // 같은 에피소드 병합: 묵음 상태 유지
                    peerMap[bleId] = cur.copy(
                        name = name, role = role, trigger = trigger,
                        beacon = beacon, createdAtMs = createdAtMs, fromServer = true,
                        episode = if (ep != 0) ep else cur.episode
                    )
                } else if (cur.key == null) {
                    if (ep != 0 && cur.episode != 0 && ep != cur.episode) {
                        // 기록 회차가 BLE 회차와 다르면 다른 SOS 의 기록이다. 병합하지 않는다.
                    } else if (ep != 0 && ep == cur.episode) {
                        // 같은 회차: 서버 키만 얻고 묵음 상태는 유지한다.
                        peerMap[bleId] = cur.copy(
                            key = key, name = name, role = role, trigger = trigger,
                            beacon = beacon, createdAtMs = createdAtMs, fromServer = true
                        )
                    } else {
                        // 회차를 알 수 없는 쪽이 있으면 새 에피소드로 본다 (RR09).
                        // 묵음이던 SOS 가 한 번 더 울릴 수 있으나, 삼켜지는 쪽보다 낫다.
                        peerMap[bleId] = cur.copy(
                            key = key, name = name, role = role, trigger = trigger,
                            beacon = beacon, createdAtMs = createdAtMs, fromServer = true, silenced = false,
                            episode = if (ep != 0) ep else cur.episode
                        )
                    }
                } else if (createdAtMs < cur.createdAtMs) {
                    // 더 오래된 에피소드 기록은 새 것을 덮지 못한다 (서버 기록끼리만 시각 비교)
                } else {
                    // 다른 키 = 새 에피소드: 묵음 해제, 다시 울린다
                    peerMap[bleId] = newServerPeer(key, bleId, name, role, trigger, beacon, createdAtMs, nowMs, ep, false)
                }
            } else if (cur != null && cur.key == key) {
                // 이미 해제된 같은 기록의 늦은 중복 전달 — 되살리지 않는다
            } else {
                peerMap[bleId] = newServerPeer(
                    key, bleId, name, role, trigger, beacon, createdAtMs, nowMs, ep,
                    cur == null && nowMs < coldSilenceUntil
                )
            }
        } else if (cur != null && cur.active && cur.key == key) {
            val t = bleTrack[bleId]
            val ref = if (ep != 0) ep else cur.episode
            if (t != null && ref != 0 && t.ep != 0 && t.ep != ref && nowMs - t.lastTrueAt < PEER_BLE_FALL_MS) {
                // 방금까지 다른 회차의 BLE 비트가 살아 있다 = 해제된 것은 옛 SOS. BLE 항목으로 남긴다.
                peerMap[bleId] = cur.copy(key = null, fromServer = false, episode = t.ep)
            } else {
                peerMap[bleId] = cur.copy(active = false, resolvedAtMs = nowMs)
                bleTrack.remove(bleId)
            }
        }
        // 해제 기록은 추적 중인 같은 키에만 적용한다. 추적하지 않는 해제(접속 때 재생된 옛 기록)를 항목으로 만들면
        // 30분 재생 뒤 '해제됨' 잡음이 생기고 첫 BLE 상승 에지가 잔상 가드에 막힌다 (v1.1.99).
    }

    private fun newServerPeer(
        key: String, bleId: String, name: String, role: String, trigger: String,
        beacon: String, createdAtMs: Long, nowMs: Long, ep: Int, silenced: Boolean
    ) = Peer(
        bleId, key, name, role, trigger, beacon, createdAtMs,
        firstSeenMs = nowMs, active = true, silenced = silenced,
        fromServer = true, resolvedAtMs = 0L, episode = ep
    )

    /**
     * BLE 비트 수신 (v1.1.99). 새 SOS 판단은 에피소드 번호(광고 byte2, 0 = 없음)가 다른지로 한다.
     * 번호 없는 구버전 광고는 같은 bleId 의 true 가 30초 이상 끊겼다 다시 오는 것을 새 신호로 본다.
     * false 는 10초 연속으로 보여야 BLE 전용 항목을 해제한다 (같은 ID 를 나눠 쓰는 폰의 true/false 뒤섞임 방어).
     */
    fun onPeerBle(bleId: String, sos: Boolean, nowMs: Long, episode: Int = 0, beacon: String = "") {
        val t = bleTrack[bleId]
        if (!sos) {
            if (t == null) return
            if (t.falseSince == Long.MIN_VALUE) t.falseSince = nowMs
            settleBle(bleId, t, nowMs)
            return
        }
        val gap = t == null || nowMs - t.lastTrueAt >= PEER_BLE_GAP_MS
        if (t == null) {
            bleTrack[bleId] = BleTrack(nowMs, Long.MIN_VALUE, episode)
        } else {
            t.lastTrueAt = nowMs
            t.falseSince = Long.MIN_VALUE
            if (episode != 0) t.ep = episode
        }
        val cur = peerMap[bleId]
        if (cur == null) {
            val fresh = freshBlePeer(bleId, "", "", beacon, episode, nowMs)
            peerMap[bleId] = if (nowMs < coldSilenceUntil) fresh.copy(silenced = true) else fresh
        } else if (episode != 0 && cur.episode != 0 && cur.episode != episode) {
            // 다른 번호 = 잔상이 아니라 새 SOS. 서버 항목도 기록 회차를 갖는다.
            peerMap[bleId] = freshBlePeer(bleId, cur.name, cur.role, beacon, episode, nowMs)
        } else if (!cur.active) {
            // 해제 직후 남은 광고 잔상은 가드 시간 동안 무시. 그 뒤의 true 는 새 SOS.
            if (nowMs - cur.resolvedAtMs > PEER_RESOLVE_GUARD_MS) {
                peerMap[bleId] = freshBlePeer(bleId, "", "", beacon, episode, nowMs)
            }
        } else if (gap && !cur.fromServer) {
            peerMap[bleId] = cur.copy(silenced = false, firstSeenMs = nowMs, createdAtMs = nowMs)
        } else if (cur.episode == 0 && episode != 0) {
            peerMap[bleId] = cur.copy(episode = episode, beacon = cur.beacon.ifEmpty { beacon })
        }
    }

    private fun settleBle(id: String, t: BleTrack, nowMs: Long) {
        if (t.falseSince == Long.MIN_VALUE || nowMs - t.falseSince < PEER_BLE_FALL_MS) return
        val cur = peerMap[id] ?: return
        if (!cur.active || cur.fromServer) return
        peerMap[id] = cur.copy(active = false, resolvedAtMs = nowMs)
        bleTrack.remove(id)
    }

    private fun freshBlePeer(bleId: String, name: String, role: String, beacon: String, episode: Int, nowMs: Long) =
        Peer(
            bleId, null, name, role, "", beacon, nowMs,
            firstSeenMs = nowMs, active = true, silenced = false,
            fromServer = false, resolvedAtMs = 0L, episode = episode
        )

    /** 확인 버튼. 해제된 항목은 지우고 활성 항목은 묵음. 빈 목록이었다면 60초간 새로 생기는 항목도 묵음(재생된 옛 기록 방어). */
    fun silencePeers(nowMs: Long) {
        val wasEmpty = peerMap.isEmpty()
        val dead = peerMap.entries.filter { !it.value.active }.map { it.key }
        for (id in dead) {
            peerMap.remove(id)
            bleTrack.remove(id)
        }
        if (wasEmpty) coldSilenceUntil = nowMs + COLD_SILENCE_MS
        for ((id, p) in peerMap.entries.toList()) {
            if (p.active) peerMap[id] = p.copy(silenced = true)
        }
    }

    fun audiblePeers(): List<Peer> = peerMap.values.filter { it.active && !it.silenced }

    // ── 최근 가장 강한 비콘 힌트 ───────────────────────────────

    fun noteBeacon(label: String, rssi: Int, nowMs: Long, sid: Int = 0) {
        beaconSamples.addLast(BeaconSample(label, rssi, nowMs, sid))
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

    /** 최근 60초 안 가장 강한 표본 중 짧은 ID(sid)가 0 이 아닌 것의 sid. 없으면 0. 광고 byte3-4 에 싣는다. */
    fun beaconSid(nowMs: Long): Int {
        var best: BeaconSample? = null
        for (s in beaconSamples) {
            if (s.sid == 0 || nowMs - s.tMs > BEACON_HINT_MS) continue
            if (best == null || s.rssi > best.rssi) best = s
        }
        return best?.sid ?: 0
    }
}
