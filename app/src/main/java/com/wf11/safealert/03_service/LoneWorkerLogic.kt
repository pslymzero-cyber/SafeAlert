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
 * 동료 SOS 수신은 LoneWorkerPeers 가 회차(bleId, ep) 단위 항목으로 다룬다(서버 기록과 BLE 비트가 같은 회차면 한 항목).
 *
 * 내 서버 기록은 작성자 uid 로 LoneWorkerSosSync 가 걸러내고, BLE 스캐너는 자기 광고를 받지 못한다.
 * 그래서 여기서는 bleId 로 나를 걸러내지 않는다 — 같은 장비 ID 를 나눠 쓰는 폰끼리도 서로 경보한다 (v1.1.99).
 */
class LoneWorkerLogic(var myBleId: String) {

    enum class Mode { WATCHING, CHECKING, SOS }

    companion object {
        const val ZONE_SETTLE_MS = 60_000L
        const val BEACON_HINT_MS = 60_000L
        /** 꽂은 뒤 이 시간이 지나 나온 움직임만 '몸에 지님'으로 본다 (꽂는 순간의 흔들림 제외). */
        const val CHARGE_SETTLE_MS = 15_000L
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
    val peers: Collection<LoneWorkerPeers.Peer> get() = peerStore.all

    private var enabled = true
    private var startedAt = 0L
    private var lastMovedAt = Long.MIN_VALUE
    private var lastAckAt = Long.MIN_VALUE
    private var enabledAt = Long.MIN_VALUE
    private var zoneLeftAt = Long.MIN_VALUE
    private var zoneInside = false
    private var zoneInsideSince = 0L
    private var pendingFall = false
    /**
     * 외부 전원 연결 상태(원본 값). 충전 중이면서 가만히 있으면 거치대에 놓인 것으로 보고 무동작·낙상 확인을 쉰다.
     * 꽂은 지 15초 뒤에도 움직임이 계속 나오면 몸에 지닌 것(carried)으로 보고 뽑을 때까지 평소처럼 감시한다.
     * 구조 요청(SOS)과 동료 경보에는 영향이 없다.
     */
    var charging = false
        private set
    private var chargeAt = Long.MIN_VALUE
    /** 이번 충전 중 꽂은 뒤 15초가 지나 움직임 판정이 나왔다 = 몸에 지님. 뽑을 때까지 유지. */
    private var carried = false
    /** 몸에 지니지 않은 채 전원이 빠졌다: 첫 움직임까지 무동작을 세지 않는다 (거치대 전원 차단 오경보 방지). */
    private var awaitPickup = false

    /** 무동작·낙상 확인을 쉬는 중: 충전 거치 또는 전원이 빠진 뒤 아직 움직이지 않음. */
    val resting: Boolean get() = (charging && !carried) || awaitPickup

    private val peerStore = LoneWorkerPeers()

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

    /**
     * 꽂으면 열린 확인은 답한 것으로 닫고 낙상 대기를 버린다. 거치 상태로 시작한다.
     * 뽑을 때 몸에 지니지 않았다면 첫 움직임이 나올 때까지 무동작을 세지 않고, 지니고 있었다면 그대로 감시한다.
     */
    fun setCharging(on: Boolean, nowMs: Long) {
        if (on == charging) return
        charging = on
        if (on) {
            chargeAt = nowMs
            carried = false
            awaitPickup = false
            pendingFall = false
            if (mode == Mode.CHECKING) {
                lastAckAt = nowMs
                toWatching(nowMs)
            }
        } else {
            awaitPickup = !carried
            carried = false
        }
    }

    /** 움직임은 타이머만 갱신한다. 열린 확인은 절대 닫지 않는다 (D-02, D-07). */
    fun onMoved(nowMs: Long) {
        if (nowMs > lastMovedAt) lastMovedAt = nowMs
        awaitPickup = false
        if (charging && !carried && nowMs - chargeAt >= CHARGE_SETTLE_MS) carried = true
    }

    fun onFall(nowMs: Long) {
        if (!enabled || zoneSettled || resting || mode != Mode.WATCHING) return
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
        if (enabled && !zoneSettled && !resting) {
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
        peerStore.tick(nowMs)
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

    // ── 동료 SOS (D-06): 회차 단위 항목은 LoneWorkerPeers 가 맡는다 ─────────

    fun onPeerServer(
        key: String, bleId: String, name: String, role: String, trigger: String,
        beacon: String, createdAtMs: Long, active: Boolean, nowMs: Long, ep: Int = 0
    ) = peerStore.onServer(key, bleId, name, role, trigger, beacon, createdAtMs, active, nowMs, ep)

    fun onPeerBle(bleId: String, sos: Boolean, nowMs: Long, episode: Int = 0, beacon: String = "") =
        peerStore.onBle(bleId, sos, nowMs, episode, beacon)

    /** 확인 버튼. ids(bleId#ep) 가 없으면 지금 목록의 모든 항목, 있으면 그 회차만 묵음으로 만든다. */
    fun silencePeers(nowMs: Long, ids: Collection<String>? = null) = peerStore.silence(nowMs, ids)

    fun audiblePeers(): List<LoneWorkerPeers.Peer> = peerStore.audible()

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
