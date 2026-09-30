package com.wf11.safealert.service

/**
 * 단독 작업자 사고·무동작 SOS 상태기계 (v1.1.99).
 *
 * 안드로이드 의존이 없는 순수 로직이다. 시각은 전부 호출자가 넘기는 elapsedRealtime 기준 ms 다
 * (센서 시각은 호출자가 이 기준으로 바꿔 넘긴다).
 *
 *   WATCHING --사고 30초 무움직임 / 무동작 stillMs--> CHECKING --응답 없이 1분 / responseMs--> SOS --괜찮음--> WATCHING
 *
 * 규칙 1(사고): 낙상 신호 하나로 그 충격 시각부터 5분 동안 사고를 의심한다(직전 움직임 조건 없음).
 * 그 안에서 뚜렷한 움직임이 30초 동안 없으면 사고 확인 창("fall", 1분)을 연다. 거치·안전구역과 무관하지만,
 * 안전구역 안(들어서자마자, 원시 안쪽)에서 충전 중이면 낙상을 무시한다. 트리거 전 10초 안의 실제 전원 연결은
 * 거치대에 꽂는 동작으로 보고 그 트리거를 버린다. 의심 중 실제 연결은 사람이 있다는 뜻이라 의심을 끝낸다.
 * 사고 확인 창을 [괜찮음]으로 닫으면 의심이 끝나고, 뚜렷한 움직임으로 닫히면 5분이 끝날 때까지 계속 지켜본다.
 *
 * 규칙 2(무동작): 지님(Rest.NONE)일 때만 stillMs 무동작이면 무동작 확인 창("still", responseMs)을 연다.
 * 충전 안 함은 시작·전원 해제 뒤 첫 뚜렷한 움직임(또는 센서 1분 무응답)부터 지님이고 그 전은 대기(WAIT)다.
 * 충전 중은 최근 30초 안 10걸음(걸음 센서가 없으면 30초 안 걷는 모양 창 5개)부터 다음 연결까지 지님, 그 전은 거치(DOCKED)다.
 * 정착한 안전구역(원시 안쪽 60초 연속)에서는 무동작 확인 창이 열리지 않고, 정착 시 열린 무동작 확인 창은 거둔다.
 *
 * 걸음: 걸음 센서가 낸 걸음 가운데 그 시각을 덮는 1초 가속도 창이 걷는 모양이고 앱 진동 구간이 아닌 것(WalkingSteps).
 * 뚜렷한 움직임: 최근 10초 안 5걸음. 걸음 센서를 쓸 수 없으면 3초 이상 이어진 걷는 모양 창.
 * 확인 창(두 종류)은 [괜찮음]·뚜렷한 움직임·실제 전원 연결로 닫힌다. 실제 연결은 사고 의심도 끝낸다.
 * 사고 30초 무움직임→확인 창, 확인 창→SOS 마감은 마감 시각까지의 센서 데이터가 들어온 뒤(없으면 LATE_MS 뒤) 판정한다.
 * SOS 는 구역 진입·기능 끄기·전원 변화로 끝나지 않고 오직 cancelSos 로만 끝난다.
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
        const val ACCIDENT_WATCH_MS = 300_000L
        const val ACCIDENT_STILL_MS = 30_000L
        const val ACCIDENT_RESPONSE_MS = 60_000L
        /** 트리거 전 이 시간 안의 실제 전원 연결은 거치대에 꽂는 동작이다. */
        const val PLUG_EXCEPT_MS = 10_000L
        /** 걸음은 미끄러지는 시간 창으로 센다: 뚜렷한 움직임 = 최근 10초 안 5걸음, 충전 중 지님 = 최근 30초 안 10걸음. */
        const val DISTINCT_STEPS = 5
        const val DISTINCT_STEP_WINDOW_MS = 10_000L
        const val CARRY_STEPS = 10
        const val CARRY_STEP_WINDOW_MS = 30_000L
        /** 걸음 센서를 쓸 수 없을 때: 뚜렷한 움직임 = 걷는 모양 창 3개 연속, 충전 중 지님 = 최근 30초 안 걷는 모양 창 5개. */
        const val STRONG_RUN_MS = 3_000L
        const val CARRY_FALLBACK_WINDOWS = 5
        /** 마감 시각까지의 센서 데이터가 이만큼 지나도 오지 않으면 도착한 것만으로 판정한다(배치 지연 5초 + 창 1초). */
        const val LATE_MS = 6_000L
        private const val BEACON_SAMPLE_CAP = 256
    }

    /** 설정에서 라이브로 바꾼다 (기본 3분 / 2분). 무동작 확인 전용 — 사고 확인은 30초 / 1분 고정. */
    var stillMs = 180_000L
    var responseMs = 120_000L
    /** 걸음 센서가 등록돼 있다(센서·신체 활동 권한 있음). 아니면 걷는 모양 창으로 대신한다. */
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
    /** 무동작 시간을 세는 기준 시각: 시작·움직임·확인 창 닫힘·다시 켬·정착 구역 이탈·지님 시작 중 가장 늦은 것. */
    private var stillBase = 0L
    private var zoneInside = false
    private var zoneInsideSince = 0L

    // 지님: 충전 안 함이면 대기가 끝났고, 충전 중이면 걸음으로 지님이 확인됐다
    private var charging = false
    private var chargeAt = 0L
    private var carried = false

    // 걸음·걷는 모양 창 기록. 뚜렷한 움직임은 floorAt 뒤 걸음만 센다
    private val walk = WalkingSteps()
    private var floorAt = Long.MIN_VALUE
    private var lastDistinctAt = Long.MIN_VALUE

    // 사고 의심
    private var accidentFrom = Long.MIN_VALUE
    private var accidentUntil = Long.MIN_VALUE
    private var lastPlugAt = Long.MIN_VALUE

    /** 무동작 확인을 쉬는 이유. */
    val rest: Rest get() = when {
        carried -> Rest.NONE
        charging -> Rest.DOCKED
        else -> Rest.WAIT
    }

    private val peerStore = LoneWorkerPeers()

    private class BeaconSample(val label: String, val rssi: Int, val tMs: Long, val sid: Int)
    private val beaconSamples = ArrayDeque<BeaconSample>()

    // ── 본인 상태 ──────────────────────────────────────────────

    /** charging 은 시작 시 전원 상태: 충전 중이면 거치, 아니면 첫 뚜렷한 움직임 대기로 시작한다. */
    fun start(nowMs: Long, zoneInside: Boolean, charging: Boolean = false) {
        stillBase = nowMs
        this.charging = charging
        chargeAt = nowMs
        carried = false
        walk.reset()
        floorAt = nowMs
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
            raiseStillBase(nowMs)
        } else {
            clearAccident()
            if (mode == Mode.CHECKING) toWatching(nowMs)
        }
    }

    /**
     * 디바운스를 통과한 실제 전원 변화. atMs 는 디바운스 전 첫 변화 시각.
     * 연결: 새 거치 — 지님을 지우고, 열린 확인 창은 응답으로 보고 닫으며, SOS 가 아니면 사고 의심을 끝낸다
     * (꽂는 행위 = 사람이 있음). 해제: 첫 뚜렷한 움직임 대기. SOS 는 전원 변화로 끝나지 않는다.
     */
    fun setCharging(on: Boolean, atMs: Long) {
        if (on == charging) return
        charging = on
        chargeAt = atMs
        carried = false
        if (on) {
            lastPlugAt = atMs
            if (mode == Mode.CHECKING) closeCheck(atMs, true)
            if (mode != Mode.SOS) clearAccident()
        } else {
            floorAt = atMs
        }
    }

    /** 닫힌 1초 가속도 창(endMs 는 이 기준으로 바꾼 시각). 걸음을 판정하고, 걸음 센서가 없으면 걷는 모양 창으로 대신한다. */
    fun onWindow(w: MotionAnalyzer.Window) {
        val accepted = walk.onWindow(w.endMs, w.strong) ?: return
        for (t in accepted) acceptStep(t)
        if (stepsAvailable || !w.strong) return
        val end = w.endMs
        if (walk.runSince(floorAt) >= STRONG_RUN_MS) onDistinct(end)
        if (charging && !carried &&
            walk.strongIn(maxOf(chargeAt, end - CARRY_STEP_WINDOW_MS), end) >= CARRY_FALLBACK_WINDOWS) carry(end)
    }

    /** 걸음 감지 1건(센서 시각을 바꾼 값). vibrating = 그 시각이 앱 진동 구간이다. 걷는 모양일 때만 센다. */
    fun onStep(tMs: Long, vibrating: Boolean = false) {
        walk.onStep(tMs, vibrating)?.let { acceptStep(it) }
    }

    /** 걸음 센서 flush 완료: 요청 시각(tMs)까지의 걸음은 다 들어왔다. */
    fun stepsFlushed(tMs: Long) = walk.stepsFlushed(tMs)

    /** 받아들인 걸음. 연결 시각·floorAt(트리거·확인 창 열림 등) 이하의 걸음은 세지 않는다. */
    private fun acceptStep(t: Long) {
        if (charging && !carried && t > chargeAt &&
            walk.stepsIn(maxOf(chargeAt + 1, t - CARRY_STEP_WINDOW_MS), t) >= CARRY_STEPS) carry(t)
        if (t > floorAt && walk.stepsIn(maxOf(floorAt + 1, t - DISTINCT_STEP_WINDOW_MS), t) >= DISTINCT_STEPS) {
            onDistinct(t)
        }
    }

    /** 움직임(MOVED)은 무동작 타이머만 갱신한다. 열린 확인 창은 닫지 않는다 (D-02, D-07). */
    fun onMoved(nowMs: Long) = raiseStillBase(nowMs)

    /** 가속도 센서가 1분 동안 응답하지 않았다: 움직임 대기를 끝내고 지님으로 센다. */
    fun sensorSilent(nowMs: Long) {
        if (charging || carried) return
        carried = true
        raiseStillBase(nowMs)
    }

    /**
     * 낙상(trigMs = 충격 표본 시각). 꺼짐·SOS·사고 확인 중이거나, 안전구역 안에서 충전 중이거나,
     * 트리거 전 10초 안(또는 트리거 뒤)에 실제 전원 연결이 있었으면 무시한다. 의심 중 새 트리거는 의심 끝만 늘린다.
     */
    fun onAccident(trigMs: Long) {
        if (!enabled || mode == Mode.SOS) return
        if (mode == Mode.CHECKING && trigger == "fall") return
        if (charging && zoneInside) return
        if (lastPlugAt != Long.MIN_VALUE && trigMs - lastPlugAt <= PLUG_EXCEPT_MS) return
        if (accidentUntil == Long.MIN_VALUE) accidentFrom = trigMs
        accidentUntil = maxOf(accidentUntil, trigMs + ACCIDENT_WATCH_MS)
        if (trigMs > floorAt) floorAt = trigMs
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
                raiseStillBase(nowMs)
            }
        }
    }

    fun tick(nowMs: Long) {
        updateSettle(nowMs)
        accidentTick(nowMs)
        if (enabled && !zoneSettled && rest == Rest.NONE && mode == Mode.WATCHING && nowMs - stillBase >= stillMs) {
            toChecking("still", stillBase + stillMs, nowMs)
        }
        // 확인 창 → SOS 는 거치·대기·안전구역과 무관하다
        if (mode == Mode.CHECKING && due(modeSinceMs + respFor(trigger), nowMs)) toSos(nowMs)
        peerStore.tick(nowMs)
    }

    /** [괜찮음]: 열린 확인 창을 닫고 진행 중인 사고 의심도 끝낸다. */
    fun ackWorking(nowMs: Long): Boolean {
        if (mode != Mode.CHECKING) return false
        closeCheck(nowMs, true)
        return true
    }

    fun cancelSos(nowMs: Long): Boolean {
        if (mode != Mode.SOS) return false
        closeCheck(nowMs, true)
        return true
    }

    /**
     * 저장된 본인 SOS 로 복원한다 (서비스 재시작·프로세스 사망 뒤). start() 뒤에 호출한다.
     * SOS 는 cancelSos 로만 끝나므로 구역 정착·기능 끄기·ackWorking 으로는 벗어나지 않는다 (v1.1.99).
     */
    fun restoreSos(trigger: String, nowMs: Long) {
        this.trigger = trigger
        toSos(nowMs)
    }

    /** 재시작 이어가기 저장값. SOS 는 확인 창으로 저장하지 않는다(본인 SOS 는 SosLedger 가 복원). */
    fun snapshot(): LoneWorkerResume.State {
        val suspected = accidentUntil != Long.MIN_VALUE
        val checking = mode == Mode.CHECKING
        return LoneWorkerResume.State(
            if (suspected) accidentFrom else null,
            if (suspected) accidentUntil else null,
            if (suspected) maxOf(accidentFrom, lastDistinctAt) else null,
            if (checking) trigger else "",
            if (checking) modeSinceMs else null,
            charging, carried, stillBase
        )
    }

    /**
     * 저장 상태로 이어간다. start() 뒤, 전원 차이 적용·SOS 복원 전에 호출한다.
     * 끝(트리거 뒤 5분)이 지난 사고 의심은 버리고, 열린 확인 창은 응답 시간을 처음부터 다시 센다.
     */
    fun resume(s: LoneWorkerResume.State, nowMs: Long) {
        if (s.accidentFrom != null && s.accidentUntil != null && s.accidentUntil > nowMs) {
            accidentFrom = s.accidentFrom
            accidentUntil = s.accidentUntil
            lastDistinctAt = s.accidentHold ?: s.accidentFrom
        }
        charging = s.charging
        carried = s.carried
        stillBase = s.stillBase
        if (s.check.isNotEmpty()) toChecking(s.check, nowMs, nowMs)
    }

    fun responseLeftMs(nowMs: Long): Long =
        if (mode == Mode.CHECKING) (respFor(trigger) - (nowMs - modeSinceMs)).coerceAtLeast(0L) else 0L

    /** 다음에 tick 이 필요한 시각: 기다리는 마감(아직 안 됐으면 그 시각, 지났으면 LATE_MS 뒤) 중 가장 이른 것. */
    fun nextCheckAt(nowMs: Long): Long? =
        deadlines().map { if (nowMs < it) it else it + LATE_MS }.filter { it > nowMs }.minOrNull()

    /** 지난 마감이 센서 데이터를 기다리고 있다(모니터가 flush 를 요청한다). */
    fun waitingOnSensors(nowMs: Long): Boolean = deadlines().any { nowMs >= it && !due(it, nowMs) }

    private fun respFor(trig: String): Long = if (trig == "fall") ACCIDENT_RESPONSE_MS else responseMs

    /** 센서 데이터가 들어온 끝 시각: 닫힌 가속도 창 끝, 걸음 센서를 쓰면 걸음 전달 시각과 둘 중 이른 쪽. */
    private fun sensedTo(): Long = if (stepsAvailable) minOf(walk.closedTo, walk.stepSeenTo) else walk.closedTo

    /** 마감 at 을 지금 판정해도 되나: 마감까지의 데이터가 들어왔거나 LATE_MS 가 지났다. */
    private fun due(at: Long, nowMs: Long): Boolean = nowMs >= at && (sensedTo() >= at || nowMs >= at + LATE_MS)

    /** 사고 확인 창을 여는 마감: 마지막 뚜렷한 움직임(없으면 트리거)부터 30초. 의심 5분을 넘으면 없음. */
    private fun accidentOpenAt(): Long? {
        if (accidentUntil == Long.MIN_VALUE) return null
        val at = maxOf(accidentFrom, lastDistinctAt) + ACCIDENT_STILL_MS
        return if (at <= accidentUntil) at else null
    }

    private fun deadlines(): List<Long> {
        val out = ArrayList<Long>(2)
        if (mode == Mode.WATCHING || (mode == Mode.CHECKING && trigger == "still")) accidentOpenAt()?.let { out.add(it) }
        if (mode == Mode.CHECKING) out.add(modeSinceMs + respFor(trigger))
        return out
    }

    /**
     * 사고 의심 판정. 사고 마감이 되면 사고 확인 창을 연다. 무동작 확인 창이 이미 열려 있으면 두 마감 중
     * 이른 쪽을 남긴다. 5분 안에 못 차면 의심을 끝낸다.
     */
    private fun accidentTick(nowMs: Long) {
        if (accidentUntil == Long.MIN_VALUE) return
        val openAt = accidentOpenAt()
        if (openAt == null) {
            if (nowMs >= accidentUntil) clearAccident()
            return
        }
        if (!due(openAt, nowMs)) return
        if (mode == Mode.WATCHING ||
            (mode == Mode.CHECKING && trigger == "still" && nowMs + ACCIDENT_RESPONSE_MS < modeSinceMs + responseMs)) {
            toChecking("fall", openAt, nowMs)
        }
    }

    /** 뚜렷한 움직임: 대기를 끝내고, 무동작 시간을 새로 세며, 열린 확인 창을 닫는다(사고 의심은 계속). */
    private fun onDistinct(t: Long) {
        if (t > lastDistinctAt) lastDistinctAt = t
        raiseStillBase(t)
        floorAt = t
        if (!charging) carried = true
        if (mode == Mode.CHECKING) closeCheck(t, false)
    }

    private fun carry(t: Long) {
        carried = true
        raiseStillBase(t)
    }

    private fun raiseStillBase(t: Long) {
        if (t > stillBase) stillBase = t
    }

    /** 확인 창·SOS 를 닫는다. endSuspicion 이면 사고 의심도 끝낸다(뚜렷한 움직임으로 닫힐 때만 의심 유지). */
    private fun closeCheck(t: Long, endSuspicion: Boolean) {
        raiseStillBase(t)
        toWatching(t)
        if (endSuspicion) clearAccident()
    }

    private fun clearAccident() {
        accidentFrom = Long.MIN_VALUE
        accidentUntil = Long.MIN_VALUE
    }

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

    /** floor = 걸음 셈 기준(센서 시각의 마감), nowMs = 실제로 연 시각(응답 시간 시작). */
    private fun toChecking(trig: String, floor: Long, nowMs: Long) {
        mode = Mode.CHECKING
        trigger = trig
        modeSinceMs = nowMs
        floorAt = floor
    }

    private fun toSos(nowMs: Long) {
        mode = Mode.SOS
        modeSinceMs = nowMs
        clearAccident()
    }

    // ── 동료 SOS (D-06): 회차 단위 항목은 LoneWorkerPeers 가 맡는다 ─────────

    fun onPeerServer(rec: LoneWorkerPeers.ServerRec, nowMs: Long) = peerStore.onServer(rec, nowMs)

    fun onPeerBle(bleId: String, sos: Boolean, nowMs: Long, episode: Int = 0, beacon: String = "") =
        peerStore.onBle(bleId, sos, nowMs, episode, beacon)

    /** 확인 버튼. targets = 항목 id -> 회차 ID. 그 항목만 묵음으로 만든다. */
    fun silencePeers(nowMs: Long, targets: Map<String, String>) = peerStore.silence(nowMs, targets)

    fun audiblePeers(): List<LoneWorkerPeers.Peer> = peerStore.audible()

    /** 경보 진동은 동료 구조 요청 사이렌에서만. 확인 창·본인 SOS 중인 요구조자 의심 기기는 소리·화면만 쓴다. */
    val alarmVibrates: Boolean get() = mode == Mode.WATCHING && peerStore.audible().isNotEmpty()

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
