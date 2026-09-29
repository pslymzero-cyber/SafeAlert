package com.wf11.safealert.service

import kotlin.math.abs

/**
 * 단독 작업자 사고·무동작 SOS 상태기계 (v1.1.99).
 *
 * 안드로이드 의존이 없는 순수 로직이다. 시각은 전부 호출자가 넘기는 elapsedRealtime 기준 ms 다
 * (센서 시각은 호출자가 이 기준으로 바꿔 넘긴다).
 *
 *   WATCHING --사고 30초 무움직임 / 무동작 stillMs--> CHECKING --응답 없이 1분 / responseMs--> SOS --괜찮음--> WATCHING
 *
 * 규칙 1(사고): 이동 중(직전 10초 안의 활동 창·걸음)에 낙상 또는 4 G 충격이 오면 그 충격 시각부터 5분 동안
 * 사고를 의심한다. 그 안에서 뚜렷한 움직임이 30초 동안 없으면 사고 확인 창("fall", 1분)을 연다.
 * 거치·충전·안전구역과 무관하다. 충격 전후 10초 안의 실제 전원 연결은 거치대에 꽂는 동작으로 보고 그 트리거를 버린다.
 * 사고 확인 창을 [괜찮음]으로 닫으면 의심이 끝나고, 뚜렷한 움직임으로 닫히면 5분이 끝날 때까지 계속 지켜본다.
 *
 * 규칙 2(무동작): 지님(Rest.NONE)일 때만 stillMs 무동작이면 무동작 확인 창("still", responseMs)을 연다.
 * 충전 안 함은 시작·전원 해제 뒤 첫 뚜렷한 움직임(또는 센서 1분 무응답)부터 지님이고 그 전은 대기(WAIT)다.
 * 충전 중은 걸음 10걸음(걸음 센서가 없으면 30초 연속 강한 움직임)부터 다음 연결까지 지님, 그 전은 거치(DOCKED)다.
 * 정착한 안전구역(원시 안쪽 60초 연속)에서는 무동작 확인 창이 열리지 않고, 정착 시 열린 무동작 확인 창은 거둔다.
 *
 * 뚜렷한 움직임: 걸음 5걸음. 걸음 센서를 쓸 수 없으면 3초 이상 이어진 걷기 수준 강한 움직임 창.
 * 확인 창(두 종류)은 [괜찮음]·뚜렷한 움직임·실제 전원 연결로 닫힌다. 실제 연결은 사고 의심도 끝낸다.
 * SOS 는 구역 진입·기능 끄기로 끝나지 않고 오직 cancelSos 로만 끝난다.
 * 동료 SOS 수신은 LoneWorkerPeers 가 회차(bleId, ep) 단위 항목으로 다룬다(서버 기록과 BLE 비트가 같은 회차면 한 항목).
 *
 * 내 서버 기록은 작성자 uid 로 LoneWorkerSosSync 가 걸러내고, BLE 스캐너는 자기 광고를 받지 못한다.
 * 그래서 여기서는 bleId 로 나를 걸러내지 않는다 — 같은 장비 ID 를 나눠 쓰는 폰끼리도 서로 경보한다 (v1.1.99).
 */
class LoneWorkerLogic(var myBleId: String) {

    enum class Mode { WATCHING, CHECKING, SOS }

    /** 무동작 확인을 쉬는 이유와 그 안내 문구(메인 화면 배너, 알림 문장 조각). 사고 감지는 쉬지 않는다. */
    enum class Rest(val banner: String?, val keepText: String) {
        NONE(null, "움직임이 없는 것으로 보고 확인을 계속하며"),
        DOCKED("거치 중 — 무동작 감시 쉼 (걸으면 다시 시작, 사고 감지는 계속)", "거치 중이라 무동작 확인은 쉬며"),
        WAIT("움직임 대기 — 무동작 감시 쉼 (움직이면 다시 시작, 사고 감지는 계속)", "첫 움직임을 기다리는 중이라 무동작 확인은 쉬며")
    }

    companion object {
        const val ZONE_SETTLE_MS = 60_000L
        const val BEACON_HINT_MS = 60_000L
        /** 이동 중 판정: 충격 전 이 시간 안에 끝난 활동 창 또는 걸음. */
        const val MOVING_LOOKBACK_MS = 10_000L
        const val ACCIDENT_WATCH_MS = 300_000L
        const val ACCIDENT_STILL_MS = 30_000L
        const val ACCIDENT_RESPONSE_MS = 60_000L
        /** 충격 전후 이 시간 안의 실제 전원 연결은 거치대에 꽂는 동작이다. */
        const val PLUG_EXCEPT_MS = 10_000L
        const val DISTINCT_STEPS = 5
        const val CARRY_STEPS = 10
        /** 걸음 센서를 쓸 수 없을 때: 뚜렷한 움직임 = 강한 움직임 창 3개, 충전 중 지님 = 30개 연속. */
        const val STRONG_RUN_MS = 3_000L
        const val CARRY_RUN_MS = 30_000L
        private const val RECENT_KEEP_MS = 60_000L
        private const val BEACON_SAMPLE_CAP = 256
    }

    /** 설정에서 라이브로 바꾼다 (기본 3분 / 2분). 무동작 확인 전용 — 사고 확인은 30초 / 1분 고정. */
    var stillMs = 180_000L
    var responseMs = 120_000L
    /** 걸음 센서가 등록돼 있다(센서·신체 활동 권한 있음). 아니면 강한 움직임 창으로 대신한다. */
    var stepsAvailable = true

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

    // 지님
    private var charging = false
    private var chargeAt = 0L
    private var chargeSteps = 0
    private var stepCarry = false
    private var waitMove = false
    private var carriedSince = Long.MIN_VALUE

    // 움직임 기록: 최근 활동 창 끝·걸음 시각, 뚜렷한 움직임 셈(floorAt 뒤만 센다), 연속 강한 창 구간
    private val recentActive = ArrayDeque<Long>()
    private val recentSteps = ArrayDeque<Long>()
    private var floorAt = Long.MIN_VALUE
    private var floorSteps = 0
    private var lastDistinctAt = Long.MIN_VALUE
    private var runStart = Long.MIN_VALUE
    private var runEnd = Long.MIN_VALUE

    // 사고 의심
    private var accidentFrom = Long.MIN_VALUE
    private var accidentUntil = Long.MIN_VALUE
    private var lastTrigAt = Long.MIN_VALUE
    private var lastPlugAt = Long.MIN_VALUE

    /** 무동작 확인을 쉬는 이유. */
    val rest: Rest get() = when {
        charging && !stepCarry -> Rest.DOCKED
        !charging && waitMove -> Rest.WAIT
        else -> Rest.NONE
    }

    private val peerStore = LoneWorkerPeers()

    private class BeaconSample(val label: String, val rssi: Int, val tMs: Long, val sid: Int)
    private val beaconSamples = ArrayDeque<BeaconSample>()

    // ── 본인 상태 ──────────────────────────────────────────────

    /** charging 은 시작 시 전원 상태: 충전 중이면 거치, 아니면 첫 뚜렷한 움직임 대기로 시작한다. */
    fun start(nowMs: Long, zoneInside: Boolean, charging: Boolean = false) {
        startedAt = nowMs
        this.charging = charging
        chargeAt = nowMs
        chargeSteps = 0
        stepCarry = false
        waitMove = !charging
        carriedSince = Long.MIN_VALUE
        recentActive.clear()
        recentSteps.clear()
        floorAt = nowMs
        floorSteps = 0
        runEnd = Long.MIN_VALUE
        clearAccident()
        lastPlugAt = Long.MIN_VALUE
        mode = Mode.WATCHING
        trigger = ""
        modeSinceMs = nowMs
        zoneSettled = false
        this.zoneInside = zoneInside
        zoneInsideSince = nowMs
    }

    /** 기능 끄기: 열린 확인 창과 사고 의심은 거두고, 진행 중인 SOS 는 유지한다 (D-05). */
    fun setEnabled(on: Boolean, nowMs: Long) {
        if (on == enabled) return
        enabled = on
        if (on) {
            enabledAt = nowMs
        } else {
            clearAccident()
            if (mode == Mode.CHECKING) toWatching(nowMs)
        }
    }

    /**
     * 디바운스를 통과한 실제 전원 변화. atMs 는 디바운스 전 첫 변화 시각.
     * 연결: 새 거치 — 걸음 지님을 지우고, 열린 확인 창은 응답으로 보고 닫으며 사고 의심도 끝낸다.
     * 확인 창이 없으면 충격 전후 10초 안의 연결만 사고 의심을 거둔다. 해제: 첫 뚜렷한 움직임 대기.
     */
    fun setCharging(on: Boolean, atMs: Long) {
        if (on == charging) return
        charging = on
        chargeAt = atMs
        stepCarry = false
        if (on) {
            waitMove = false
            lastPlugAt = atMs
            chargeSteps = recentSteps.count { it >= atMs }
            if (mode == Mode.CHECKING) {
                lastAckAt = atMs
                toWatching(atMs)
                clearAccident()
            } else if (accidentUntil != Long.MIN_VALUE && abs(lastTrigAt - atMs) <= PLUG_EXCEPT_MS) {
                clearAccident()
            }
        } else {
            waitMove = true
            resetFloor(atMs)
        }
    }

    /** 닫힌 1초 센서 창(endMs 는 이 기준으로 바꾼 시각). 활동 창은 이동 중 판정에, 강한 창은 걸음 대체에 쓴다. */
    fun onWindow(w: MotionAnalyzer.Window) {
        val end = w.endMs
        if (w.has && w.active) remember(recentActive, end)
        if (!(w.has && w.strong)) {
            runEnd = Long.MIN_VALUE
            return
        }
        if (runEnd == Long.MIN_VALUE || end - 1000 != runEnd) runStart = end - 1000
        runEnd = end
        if (stepsAvailable) return
        if (runMs(floorAt) >= STRONG_RUN_MS) onDistinct(end)
        if (charging && !stepCarry && runMs(chargeAt) >= CARRY_RUN_MS) carry(end)
    }

    /** 걸음 감지 1건(센서 시각을 바꾼 값). */
    fun onStep(tMs: Long) {
        remember(recentSteps, tMs)
        if (charging && !stepCarry && tMs >= chargeAt && ++chargeSteps >= CARRY_STEPS) carry(tMs)
        if (tMs > floorAt && ++floorSteps >= DISTINCT_STEPS) onDistinct(tMs)
    }

    /** 움직임(MOVED)은 무동작 타이머만 갱신한다. 열린 확인 창은 닫지 않는다 (D-02, D-07). */
    fun onMoved(nowMs: Long) {
        if (nowMs > lastMovedAt) lastMovedAt = nowMs
    }

    /** 가속도 센서가 1분 동안 응답하지 않았다: 움직임 대기를 끝내고 지님으로 센다. */
    fun sensorSilent(nowMs: Long) {
        if (!waitMove) return
        waitMove = false
        carriedSince = nowMs
    }

    /**
     * 낙상 또는 4 G 충격(trigMs = 충격 표본 시각). 이동 중이 아니었거나, 꺼짐·SOS·사고 확인 중이거나,
     * 전원 연결이 충격 전후 10초 안이면 무시한다. 의심 중 새 트리거는 의심 끝만 늘린다.
     */
    fun onAccident(trigMs: Long) {
        if (!enabled || mode == Mode.SOS) return
        if (mode == Mode.CHECKING && trigger == "fall") return
        val moving = recentActive.any { it in trigMs - MOVING_LOOKBACK_MS..trigMs } ||
            recentSteps.any { it in trigMs - MOVING_LOOKBACK_MS..trigMs }
        if (!moving) return
        if (lastPlugAt != Long.MIN_VALUE && abs(trigMs - lastPlugAt) <= PLUG_EXCEPT_MS) return
        if (accidentUntil == Long.MIN_VALUE) accidentFrom = trigMs
        accidentUntil = maxOf(accidentUntil, trigMs + ACCIDENT_WATCH_MS)
        lastTrigAt = trigMs
        if (trigMs > floorAt) resetFloor(trigMs)
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
        accidentTick(nowMs)
        if (enabled && !zoneSettled && rest == Rest.NONE && mode == Mode.WATCHING && nowMs - stillStart() >= stillMs) {
            toChecking("still", nowMs)
        }
        // 확인 창 → SOS 는 거치·대기·안전구역과 무관하다
        if (mode == Mode.CHECKING && nowMs - modeSinceMs >= respFor(trigger)) {
            mode = Mode.SOS
            modeSinceMs = nowMs
            clearAccident()
        }
        peerStore.tick(nowMs)
    }

    /** [괜찮음]: 열린 확인 창을 닫고 진행 중인 사고 의심도 끝낸다. */
    fun ackWorking(nowMs: Long): Boolean {
        if (mode != Mode.CHECKING) return false
        lastAckAt = nowMs
        toWatching(nowMs)
        clearAccident()
        return true
    }

    fun cancelSos(nowMs: Long): Boolean {
        if (mode != Mode.SOS) return false
        lastAckAt = nowMs
        toWatching(nowMs)
        clearAccident()
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
        clearAccident()
    }

    fun responseLeftMs(nowMs: Long): Long =
        if (mode == Mode.CHECKING) (respFor(trigger) - (nowMs - modeSinceMs)).coerceAtLeast(0L) else 0L

    private fun respFor(trig: String): Long = if (trig == "fall") ACCIDENT_RESPONSE_MS else responseMs

    /**
     * 사고 의심 판정. 마지막 뚜렷한 움직임(없으면 충격)부터 30초가 의심 5분 안에 차면 사고 확인 창을 연다.
     * 무동작 확인 창이 이미 열려 있으면 두 마감 중 이른 쪽을 남긴다. 5분 안에 못 차면 의심을 끝낸다.
     */
    private fun accidentTick(nowMs: Long) {
        if (accidentUntil == Long.MIN_VALUE) return
        if (mode == Mode.SOS) {
            clearAccident()
            return
        }
        val openAt = maxOf(accidentFrom, lastDistinctAt) + ACCIDENT_STILL_MS
        if (openAt <= accidentUntil && nowMs >= openAt) {
            if (mode == Mode.WATCHING) {
                toChecking("fall", nowMs)
            } else if (trigger == "still" && nowMs + ACCIDENT_RESPONSE_MS < modeSinceMs + responseMs) {
                trigger = "fall"
                modeSinceMs = nowMs
            }
        } else if (nowMs >= accidentUntil) {
            clearAccident()
        }
    }

    /** 뚜렷한 움직임: 대기를 끝내고, 무동작 시간을 새로 세며, 열린 확인 창을 닫는다(사고 의심은 계속). */
    private fun onDistinct(t: Long) {
        if (t > lastDistinctAt) lastDistinctAt = t
        if (t > lastMovedAt) lastMovedAt = t
        resetFloor(t)
        if (waitMove) {
            waitMove = false
            carriedSince = t
        }
        if (mode == Mode.CHECKING) {
            lastAckAt = t
            toWatching(t)
        }
    }

    /** 지금 이어지는 강한 창 중 from 이후에 시작한 창들의 길이(ms). */
    private fun runMs(from: Long): Long = (runEnd - maxOf(runStart, from)) / 1000 * 1000

    private fun carry(t: Long) {
        stepCarry = true
        carriedSince = t
    }

    private fun resetFloor(t: Long) {
        floorAt = t
        floorSteps = recentSteps.count { it > t }
    }

    private fun remember(q: ArrayDeque<Long>, t: Long) {
        q.addLast(t)
        while (q.isNotEmpty() && t - q.first() > RECENT_KEEP_MS) q.removeFirst()
    }

    private fun clearAccident() {
        accidentFrom = Long.MIN_VALUE
        accidentUntil = Long.MIN_VALUE
    }

    private fun stillStart(): Long =
        maxOf(maxOf(startedAt, lastMovedAt, lastAckAt), maxOf(enabledAt, zoneLeftAt, carriedSince))

    private fun updateSettle(nowMs: Long) {
        if (zoneInside && !zoneSettled && nowMs - zoneInsideSince >= ZONE_SETTLE_MS) {
            zoneSettled = true
            if (mode == Mode.CHECKING && trigger == "still") toWatching(nowMs)
        }
    }

    private fun toWatching(nowMs: Long) {
        mode = Mode.WATCHING
        trigger = ""
        modeSinceMs = nowMs
    }

    private fun toChecking(trig: String, nowMs: Long) {
        mode = Mode.CHECKING
        trigger = trig
        modeSinceMs = nowMs
        resetFloor(nowMs)
    }

    // ── 동료 SOS (D-06): 회차 단위 항목은 LoneWorkerPeers 가 맡는다 ─────────

    fun onPeerServer(rec: LoneWorkerPeers.ServerRec, nowMs: Long) = peerStore.onServer(rec, nowMs)

    fun onPeerBle(bleId: String, sos: Boolean, nowMs: Long, episode: Int = 0, beacon: String = "") =
        peerStore.onBle(bleId, sos, nowMs, episode, beacon)

    /** 확인 버튼. targets = 항목 id -> 회차 ID. 그 항목만 묵음으로 만든다. */
    fun silencePeers(nowMs: Long, targets: Map<String, String>) = peerStore.silence(nowMs, targets)

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
