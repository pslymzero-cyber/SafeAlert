package com.wf11.safealert.service

/**
 * 단독 작업자 사고·무동작 SOS 상태기계 (v1.1.99).
 *
 * 안드로이드 의존이 없는 순수 로직이다. 시각은 전부 호출자가 넘기는 elapsedRealtime 기준 ms 다
 * (센서 시각은 호출자가 이 기준으로 바꿔 넘긴다).
 *
 *   WATCHING --사고 30초 무움직임 / 무동작 stillMs--> CHECKING --응답 없이 1분 / responseMs--> SOS --괜찮아요--> WATCHING
 *
 * 규칙 1(사고): 낙상 신호 하나로 그 충격 시각부터 5분 동안 사고를 의심한다(직전 움직임 조건 없음).
 * 그 안에서 뚜렷한 움직임이 30초 동안 없으면 사고 확인 창("fall", 1분)을 연다. 거치·안전구역과 무관하지만,
 * 충격 순간(FALL 이 온 시각 아님, A1) 안전구역 안(들어서자마자, 원시 안쪽, 충격 뒤 12초 안에 이탈이 확정되면 밖 — H8 "밖 기준")이고 충전 중이었으면(충격 뒤 실제 해제는 충전 중이었다, 충격 전 10초 안 실제 해제도 포함 — 크래들에서 떨어짐,
 * 재시작 때 적용한 해제는 빼고) 낙상을 무시한다(N2, FALL 처리 시각과 무관). 안전구역 안 낙상은 zoneFall(높이·충격·자세, 개발자 설정, 자세 모르면 넘은 것)을 모두 넘어야 센다. 트리거 전 10초 안(또는 트리거 뒤)의 실제 전원 연결(재시작 때 적용한 연결은 빼고)은 거치대에 꽂는 동작으로 보고 그 트리거를 버린다.
 * 의심 중 실제 연결은 사람이 있다는 뜻이라 의심을 끝낸다.
 * 사고 확인 창을 [괜찮아요]로 닫으면 의심이 끝나고, 뚜렷한 움직임으로 닫히면 5분이 끝날 때까지 계속 지켜본다.
 *
 * 규칙 2(무동작): 지님(Rest.NONE)일 때만 stillMs 무동작이면 무동작 확인 창("still", responseMs)을 연다.
 * 충전 안 함은 시작·전원 해제 뒤 첫 뚜렷한 움직임(또는 센서 1분 무응답)부터 지님이고 그 전은 대기(WAIT)다.
 * 충전 중은 최근 30초 안 10걸음(걸음 센서가 없으면 30초 안 걷는 모양 창 5개)부터 다음 연결까지 지님, 그 전은 거치(DOCKED)다 — 장비 모드(지게차·EPJ) 거치는 쉬지 않고 Rest.NONE 이다. 장비 모드는 충전 중 걸음·걷는 모양 창으로 지님이 되지 않는다 — 전원 해제로만(H4).
 * 장비 거치는 MOVED·회전으로 다시 세고, 열린 무동작 창은 회전·3초 걷기 수준 흔들림·[괜찮아요]로 닫힌다(걸음은 안 씀). 낙상 규칙·보행 모드 거치는 그대로(B1~B5).
 * 정착한 안전구역(원시 안쪽 60초 연속)에서는 무동작을 세지 않고 벗어난 시각부터 센다. 정착 시 열린 무동작 확인 창은 거둔다.
 * 동료 사이렌이 이 기기에서 진동하는 동안도 셈을 멈추고, 끝나면 쌓인 시간에 이어서 센다(멈춘 시간이 stillMs 에 이르면 사이렌이 계속 울려도 다시 세고, 진동기가 없는 기기는 멈추지 않는다).
 *
 * 걸음: 걸음 센서가 낸 걸음 가운데 그 시각을 덮는 1초 가속도 창이 걷는 모양이고 앱 진동 구간이 아닌 것(WalkingSteps).
 * 뚜렷한 움직임: 최근 10초 안 5걸음. 걸음 센서를 쓸 수 없으면 3초 이상 이어진 걷는 모양 창. 확인 창을 닫는 셈은 창이 뜬 뒤 것만(전원 해제로 다시 세지 않음), 해제 뒤 지님은 뺀 시각 뒤 것으로 따로 센다(M2).
 * 확인 창(두 종류)은 [괜찮아요]·뚜렷한 움직임·실제 전원 연결로 닫힌다. 실제 연결은 사고 의심도 끝낸다.
 * 마감 판정(무동작·사고 창 열기, SOS)은 마감까지의 센서 데이터가 들어온 뒤(없으면 LATE_MS 뒤), 그 전(같은 시각 포함)에 시작한 전원 변화가 확정·버림될 때까지 기다리고(M1), 그동안 들어온 센서 입력은 판정 뒤 반영하며(N1), 마감 뒤 시작한 변화는 판정 뒤 적용한다(Q1) — 순서는 JudgeOrder 한 곳. 회전은 폴링 시각 실시간 값이라 늦게만 오고, 이미 지난 SOS 마감보다 늦은 회전·흔들림은 그 창을 닫지 않는다(H7).
 * SOS 는 구역 진입·기능 끄기·전원 변화로 끝나지 않고 오직 cancelSos 로만 끝난다.
 * 재시작 뒤 전원·구역 보류는 RestartHold.
 * 동료 SOS 수신은 LoneWorkerPeers 가 회차(bleId, ep) 단위 항목으로 다룬다(서버 기록과 BLE 비트가 같은 회차면 한 항목).
 *
 * 내 서버 기록은 작성자 uid 로 LoneWorkerSosSync 가 걸러내고, BLE 스캐너는 자기 광고를 받지 못한다 — 그래서 여기서는 bleId 로 나를 걸러내지 않는다(같은 장비 ID 를 나눠 쓰는 폰끼리도 서로 경보, v1.1.99).
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
        const val ACCIDENT_WATCH_MS = 300_000L
        const val ACCIDENT_STILL_MS = 30_000L
        const val ACCIDENT_RESPONSE_MS = 60_000L
        /** 트리거 전 이 시간 안의 실제 전원 연결은 거치대에 꽂는 동작이다. */
        const val PLUG_EXCEPT_MS = 10_000L
        /** 충격 전 이 시간 안의 실제 해제도 충격 때 충전 중으로 본다(크래들에서 떨어지며 빠진 경우, C1). 충격 뒤 해제는 충격 때 충전 중(N2). */
        const val UNPLUG_FALL_MS = 10_000L
        /** 걸음은 미끄러지는 시간 창으로 센다: 뚜렷한 움직임 = 최근 10초 안 5걸음, 충전 중 지님 = 최근 30초 안 10걸음. */
        const val DISTINCT_STEPS = 5
        const val DISTINCT_STEP_WINDOW_MS = 10_000L
        const val CARRY_STEPS = 10
        const val CARRY_STEP_WINDOW_MS = 30_000L
        /** 걸음 센서를 쓸 수 없을 때: 뚜렷한 움직임 = 걷는 모양 창 3개 연속, 충전 중 지님 = 최근 30초 안 걷는 모양 창 5개. */
        const val STRONG_RUN_MS = 3 * MotionAnalyzer.WINDOW_MS
        const val CARRY_FALLBACK_WINDOWS = 5
        /** 센서 배치 최대 지연 — 가속도·걸음 등록 값과 LATE_MS 의 근거. */
        const val MAX_BATCH_MS = 5_000L
        /** 마감 시각까지의 센서 데이터가 이만큼 지나도 오지 않으면 도착한 것만으로 판정한다(배치 지연 + 창 1개). */
        const val LATE_MS = MAX_BATCH_MS + MotionAnalyzer.WINDOW_MS
        /** 재시작 뒤 복원한 세이프존 안 상태를 구역 보고 없이 유지하는 한도(BleService 신호 두절 이탈 10초와 같은 규칙, C3). */
        const val ZONE_RESUME_HOLD_MS = 10_000L
    }

    /** 설정에서 라이브로 바꾼다 (기본 3분 / 2분). 무동작 확인 전용 — 사고 확인은 30초 / 1분 고정. */
    var stillMs = 180_000L
    var responseMs = 120_000L
    var zoneFall = MotionAnalyzer.ZoneFall() // 세이프존 안 낙상 기준(높이·충격·자세) — 개발자 설정, 모니터가 넣는다 (D-02, D-03)
    /** 걸음 센서가 등록돼 있다(센서·신체 활동 권한 있음). 아니면 걷는 모양 창으로 대신한다. */
    var stepsAvailable = true
    /** 이 기기에 진동기가 있다 — 없으면 사이렌 진동도, 그 동안의 무동작 셈 멈춤도 없다(D2). */
    var canVibrate = true

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
    /** 무동작 시간을 세는 기준 시각: 시작·움직임·확인 창 닫힘·다시 켬·정착 구역 이탈·지님 시작·장비 거치 시작(연결·장비 모드)·장비 거치 중 회전 중 가장 늦은 것. 장비 거치 중엔 걸음이 올리지 않는다. */
    private var stillBase = 0L
    private val zone = ZoneHistory() // 세이프존 원시 안/밖과 들어선 시각, 낙상은 충격 순간 상태로 본다(A1)

    // 지님: 충전 안 함이면 대기가 끝났고, 충전 중이면 걸음으로 지님이 확인됐다
    private var charging = false // 로직에 적용한 전원 — JudgeOrder 가 마감 순서에 맞춰 옮기므로 디바운스 확정값(PowerDebounce.reported)과 미루는 동안 다르다
    private var chargeAt = 0L
    private var carried = false
    private var equipment = false // 장비 모드(지게차·EPJ 선택, 모니터가 역할로 넣음)
    val mounted: Boolean get() = equipment && charging && !carried // 장비 거치 = 장비 모드 + 충전 중 + 지님 아님 (B1)

    // 걸음·걷는 모양 창 기록. floorAt = 창 닫기·사고 리셋 걸음 셈 기준(시작·트리거·창 열림·뚜렷한 움직임), 지님은 chargeAt 기준(M2)
    private val walk = WalkingSteps()
    private var floorAt = Long.MIN_VALUE
    private var shakeFloorAt = Long.MIN_VALUE // 장비 거치 3초 흔들기 닫기 기준 — 창이 열릴 때만 정하고 걸음은 옮기지 않는다(H5)
    private var lastDistinctAt = Long.MIN_VALUE

    // 사고 의심
    private var accidentFrom = Long.MIN_VALUE
    private var accidentUntil = Long.MIN_VALUE
    private var lastPlugAt = Long.MIN_VALUE
    private var lastUnplugAt = Long.MIN_VALUE
    /** 동료 사이렌 진동 중 무동작 셈 멈춤(C2), 재시작 뒤 전원·구역 보류, 판정 순서(전원 디바운스 포함, JudgeOrder). */
    private val siren = SirenPause()
    private val hold = RestartHold()
    private val order = JudgeOrder(hold, ::deadlines, ::sensedTo, ::setCharging)

    /** 무동작 확인을 쉬는 이유. */
    val rest: Rest get() = when {
        carried || mounted -> Rest.NONE // 장비 거치는 쉬지 않는다 (B1)
        charging -> Rest.DOCKED
        else -> Rest.WAIT
    }

    internal val peerStore = LoneWorkerPeers()

    internal val beacons = BeaconHints()

    // ── 본인 상태 ──────────────────────────────────────────────

    /** charging 은 시작 시 전원 상태: 충전 중이면 거치, 아니면 첫 뚜렷한 움직임 대기로 시작한다. */
    fun start(nowMs: Long, zoneInside: Boolean, charging: Boolean = false) {
        stillBase = nowMs
        this.charging = charging
        chargeAt = nowMs
        carried = false
        walk.reset()
        floorAt = nowMs
        shakeFloorAt = nowMs
        clearAccident()
        lastPlugAt = Long.MIN_VALUE
        lastUnplugAt = Long.MIN_VALUE
        siren.reset()
        order.reset(charging)
        hold.reset()
        mode = Mode.WATCHING
        trigger = ""
        modeSinceMs = nowMs
        zoneSettled = false
        zone.reset(zoneInside, nowMs)
    }

    /** 기능 끄기: 열린 확인 창(들고 있는 복원 창 포함)과 사고 의심은 거두고, 진행 중인 SOS 는 유지한다 (D-05). */
    fun setEnabled(on: Boolean, nowMs: Long) {
        if (on == enabled) return
        enabled = on
        if (on) {
            raiseStillBase(nowMs)
        } else {
            clearAccident()
            closeCheck(nowMs)
        }
    }
    /** 장비 모드(지게차·EPJ 선택)를 모니터가 역할로 넣는다 — 바뀌면 그 시각부터 센다, 옛 기준으로 창을 바로 열지 않는다 (B1). */
    fun setEquipment(on: Boolean, nowMs: Long) { if (on != equipment) { equipment = on; raiseStillBase(nowMs) } }

    /**
     * 디바운스가 확정한 실제 전원 변화(JudgeOrder 가 마감 순서대로 적용한다, atMs = 디바운스 전 첫 변화 시각). 연결: 새 거치 — 지님을 지우고, 열린 확인 창은 응답으로 보고 닫으며,
     * SOS 가 아니면 사고 의심을 끝낸다(꽂는 행위 = 사람이 있음). 해제: 첫 뚜렷한 움직임 대기(확정 전 해제 뒤 걸음으로 이미 성립했으면 그 걸음부터 지님, 걸음 셈 기준은 바꾸지 않는다). SOS 는 전원 변화로 끝나지 않는다.
     */
    private fun setCharging(on: Boolean, atMs: Long) {
        val restart = hold.powerSettled(atMs)
        charging = on
        chargeAt = atMs
        carried = false
        // 재시작 때 적용한 변화는 거치 동작·크래들 낙하 기준이 아니다(H4·L2)
        if (on) {
            if (!restart) lastPlugAt = atMs
            if (mounted) raiseStillBase(atMs) // 장비에 거치하면 그때부터 장비가 멈춘 시간을 센다 (B1)
            closeCheck(atMs)
            if (mode != Mode.SOS) clearAccident()
        } else {
            if (!restart) lastUnplugAt = atMs
            // 확정 전에 받은 뺀 뒤 걸음(걸음 센서가 없으면 걷는 모양 창 3초)으로 이미 뚜렷했으면 그때부터 지님, 걸음 셈 기준(floorAt)은 그대로(M2)
            firstDistinct(atMs)?.let { carry(it) }
        }
    }

    /** 닫힌 1초 가속도 창(endMs 는 이 기준으로 바꾼 시각). 걸음을 판정하고, 걸음 센서가 없으면 걷는 모양 창으로 대신한다. 장비 거치 무동작 창은 창이 열린 뒤(shakeFloorAt) 3초 연속 흔들림으로 닫는다(H5·H7). */
    fun onWindow(w: MotionAnalyzer.Window) {
        if (order.keep { onWindow(w) }) return
        val accepted = walk.onWindow(w.endMs, w.strong) ?: return
        for (t in accepted) acceptStep(t)
        // 장비 거치: 창이 열린 뒤(shakeFloorAt, 걸음이 옮기지 않음 H5) 3초 연속 걷기 수준 흔들림이면 무동작 창을 닫는다(걸음은 안 씀 B4, 지난 SOS 마감 뒤면 안 닫음 H7)
        if (mounted && w.strong) walk.firstRunEnd(shakeFloorAt, STRONG_RUN_MS)?.let { closeMounted(it) }
        if (stepsAvailable || !w.strong) return
        val end = w.endMs
        if (!carried && !mounted) (if (charging) end.takeIf { walk.strongIn(maxOf(chargeAt, end - CARRY_STEP_WINDOW_MS), end) >= CARRY_FALLBACK_WINDOWS } else firstDistinct(chargeAt))?.let { carry(it) }
        firstDistinct(floorAt)?.let { onDistinct(it) }
    }

    /** 걸음 감지 1건(센서 시각을 바꾼 값). vibrating = 그 시각이 앱 진동 구간이다. 걷는 모양일 때만 센다. */
    fun onStep(tMs: Long, vibrating: Boolean = false) {
        if (!order.keep { onStep(tMs, vibrating) }) walk.onStep(tMs, vibrating)?.let { acceptStep(it) }
    }

    /** 걸음 센서 flush 완료: 요청 시각(tMs)까지의 걸음은 다 들어왔다. */
    fun stepsFlushed(tMs: Long) { if (!order.keep { stepsFlushed(tMs) }) walk.stepsFlushed(tMs) }

    /** 센서 콜백 하나가 끝났다(모니터가 콜백마다) — 막힌 마감이 생겼으면 뒤 입력을 보관, 풀렸으면 판정 뒤 재생(N1). */
    fun sensorEventEnd(nowMs: Long) = order.eventEnd(nowMs, ::decide)

    /** 받아들인 걸음. 지님은 chargeAt 뒤 걸음(충전 중 30초 안 10걸음, 뺀 뒤 첫 뚜렷한 움직임), 창 닫기·사고 리셋은 floorAt 뒤 걸음으로 센다(M2). */
    private fun acceptStep(t: Long) {
        if (!carried && !mounted) (if (charging) t.takeIf { walk.within(chargeAt, t, CARRY_STEPS, CARRY_STEP_WINDOW_MS) } else firstDistinct(chargeAt))?.let { carry(it) }
        firstDistinct(floorAt)?.let { onDistinct(it) }
    }

    /** 뚜렷한 움직임(10초 안 5걸음, 걸음 센서가 없으면 3초 연속 걷는 모양 창)이 after 뒤 기록으로 처음 성립한 시각 — 정의 한 곳(창 닫기는 floorAt, 지님은 chargeAt 기준, M2). */
    private fun firstDistinct(after: Long): Long? =
        if (stepsAvailable) walk.firstWithin(after, DISTINCT_STEPS, DISTINCT_STEP_WINDOW_MS) else walk.firstRunEnd(after, STRONG_RUN_MS)

    /** 움직임(MOVED)은 무동작 타이머만 갱신한다. 열린 확인 창은 닫지 않는다 (D-02, D-07). */
    fun onMoved(nowMs: Long) { if (!order.keep { onMoved(nowMs) }) raiseStillBase(nowMs) }
    /**
     * 장비 회전(BleService 1.5초 폴링, 직진 아닐 때만): 장비 거치면 무동작을 다시 세고 무동작 창을 닫는다(B2·B4). 폴링 시각이 찍힌 실시간 값이라 받는 즉시 실시간 순서로 적용돼
     * 마감 이하 회전은 모두 그 마감 판정 전에 반영되고, 이미 지난 SOS 마감보다 늦은 회전은 그 창을 닫지 않는다(H7). sensedTo 에 안 보태고, MOVED 처럼 막힌 마감 뒤엔 보관했다 판정 뒤 재생(N1).
     */
    fun onTurn(nowMs: Long) { if (!order.keep { onTurn(nowMs) } && mounted) { raiseStillBase(nowMs); closeMounted(nowMs) } }

    /** 장비 거치 무동작 창을 회전·3초 흔들림(시각 t)으로 닫는다; 이미 지난 SOS 마감보다 늦은 입력은 마감 판정(센서 데이터 대기 중)이 먼저라 닫지 않는다(H7). */
    private fun closeMounted(t: Long) { if (t <= (sosAt() ?: Long.MAX_VALUE)) closeCheck(t, "still") }

    /** 가속도 센서가 1분 동안 응답하지 않았다: 움직임 대기를 끝내고 지님으로 센다. */
    fun sensorSilent(nowMs: Long) {
        if (order.keep { sensorSilent(nowMs) } || charging || carried) return
        carried = true
        raiseStillBase(nowMs)
    }

    /** 낙상(trigMs = 충격 표본 시각). 꺼짐·SOS·사고 확인 중이면 버리고, 그 밖의 무시 조건은 규칙 1(클래스 KDoc). 의심 중 새 트리거는 의심 끝만 늘린다. 충격 순간 안전구역 안이었고 충격 뒤 12초(EXIT_LAG_MS) 안에 이탈이 확정되지 않았으면(A1·H8) shape 가 zoneFall 을 넘어야 센다. */
    fun onAccident(trigMs: Long, shape: MotionAnalyzer.FallShape = MotionAnalyzer.FallShape.ANY) {
        if (order.keep { onAccident(trigMs, shape) } || !enabled || mode == Mode.SOS) return
        if (mode == Mode.CHECKING && trigger == "fall") return
        hold.zoneExpired(trigMs + ZoneHistory.EXIT_LAG_MS)?.let { leaveZone(it) } // FALL 은 충격 12초 뒤에야 오므로 그새 끝난 재시작 구역 보류(이탈)를 먼저 적용(H8)
        if (zone.insideAtImpact(trigMs) && (charging || !zoneFall.passes(shape) ||
                (lastUnplugAt != Long.MIN_VALUE && trigMs - lastUnplugAt <= UNPLUG_FALL_MS))) return
        if (lastPlugAt != Long.MIN_VALUE && trigMs - lastPlugAt <= PLUG_EXCEPT_MS) return
        if (accidentUntil == Long.MIN_VALUE) accidentFrom = trigMs
        accidentUntil = maxOf(accidentUntil, trigMs + ACCIDENT_WATCH_MS)
        // 충격 뒤 기록만으로 뚜렷한 움직임을 다시 센다 — 충격 전 걸음에 기댄 성립은 사고 기준에서 빼고 닫힌 창은 그대로(Y5)
        var base = trigMs
        while (true) base = firstDistinct(base) ?: break
        lastDistinctAt = if (base > trigMs) base else minOf(lastDistinctAt, trigMs)
        if (mode != Mode.CHECKING || floorAt <= trigMs) floorAt = base
    }

    /** 구역 보고. 재시작 구역 보류는 여기서 풀리고, 한도 안에 보류가 막 풀린 안쪽 보고는 저장한 진입 시각으로 정착을 본다(한도가 지났으면 한도 시각에 먼저 벗어난다). */
    fun onZone(inside: Boolean, nowMs: Long) {
        updateSettle(nowMs)
        hold.zoneReported()
        if (!inside) {
            leaveZone(nowMs)
            return
        }
        if (!zone.inside) zone.mark(true, nowMs)
        updateSettle(nowMs)
    }

    private fun leaveZone(t: Long) {
        if (!zone.inside) return
        zone.mark(false, t)
        if (zoneSettled) {
            zoneSettled = false
            raiseStillBase(t)
        }
    }

    fun tick(nowMs: Long) {
        settlePower(nowMs) // 미룰 것 없는 안정 전원 변화를 먼저 적용한다(JudgeOrder)
        stillBase = siren.update(alarmVibrates, nowMs, stillBase, stillMs)
        updateSettle(nowMs)
        // 정착한 안전구역에서는 무동작을 세지 않는다(기준을 계속 지금으로, C3)
        if (zoneSettled) raiseStillBase(nowMs)
        order.judge(nowMs, ::decide)
        peerStore.tick(nowMs)
    }

    /** 판정 한 벌: 보류 게이트·사고·무동작 창 열기와 SOS 마감 — 순서·반복·재생은 JudgeOrder.judge. */
    private fun decide(nowMs: Long) {
        // 보류 게이트: 재시작 전원 보류 중, 그리고 들고 있던 창을 열기 전에는 확인 창을 새로 열지 않는다(E9·L3)
        val gate = hold.gate(nowMs)
        if (gate?.let { order.due(it, nowMs) } != false) {
            if (gate != null) hold.takeCheck()?.let { if (enabled && mode == Mode.WATCHING) toChecking(it, gate, nowMs) }
            accidentTick(nowMs)
            stillOpenAt()?.let { if (order.due(it, nowMs)) toChecking("still", it, nowMs) }
        }
        // 확인 창 → SOS 는 거치·대기·안전구역과 무관하다
        sosAt()?.let { if (order.due(it, nowMs)) toSos(nowMs) }
    }

    /** [괜찮아요]: 열린 확인 창을 닫고 진행 중인 사고 의심도 끝낸다. */
    fun ackWorking(nowMs: Long): Boolean {
        if (mode != Mode.CHECKING) return false
        closeCheck(nowMs)
        clearAccident()
        return true
    }

    /** SOS 진입 때 의심은 이미 지워졌고 SOS 중 낙상은 버리므로 의심을 따로 끝낼 것이 없다. */
    fun cancelSos(nowMs: Long): Boolean {
        if (mode != Mode.SOS) return false
        toWatching(nowMs)
        return true
    }

    /**
     * 저장된 본인 SOS 로 복원한다 (서비스 재시작·프로세스 사망 뒤). start() 뒤에 호출한다.
     * SOS 는 cancelSos 로만 끝나므로 구역 정착·기능 끄기·ackWorking 으로는 벗어나지 않는다. 들고 있거나 연 확인 창은 버린다 (v1.1.99).
     */
    fun restoreSos(trigger: String, nowMs: Long) {
        closeCheck(nowMs)
        this.trigger = trigger
        toSos(nowMs)
    }

    /** 재시작 이어가기 저장값. SOS 는 확인 창으로 저장하지 않고(본인 SOS 는 SosLedger 가 복원) 전원 보류 중엔 들고 있는 복원 창을 저장한다. 기준은 사이렌 멈춤을 뺀 값(C2). */
    fun snapshot(nowMs: Long): LoneWorkerResume.State {
        val suspected = accidentUntil != Long.MIN_VALUE
        return LoneWorkerResume.State(
            if (suspected) maxOf(accidentFrom, lastDistinctAt) else null,
            if (suspected) accidentUntil else null,
            when (mode) { Mode.CHECKING -> trigger; Mode.WATCHING -> hold.check; else -> "" },
            charging, carried, siren.base(stillBase, nowMs, stillMs), zoneSettled,
            if (zone.inside) zone.since else null, mounted
        )
    }

    /** 시작하고 저장 상태가 있으면 이어간다. 저장한 충전 값과 지금 전원(plugged)이 다르면 재시작 전원 보류다(RestartHold). */
    fun startFrom(nowMs: Long, zoneInside: Boolean, plugged: Boolean, saved: LoneWorkerResume.State?) {
        start(nowMs, zoneInside, saved?.charging ?: plugged)
        if (saved != null && saved.charging != plugged) hold.holdPower(nowMs)
        powerRaw(plugged, nowMs)
        saved?.let { resume(it, nowMs) }
    }

    /** 전원 원시 값(sticky = 스티키 배터리 보정, 대기 중이면 버림). 적용했거나 대기가 바뀌었으면 true(모니터가 판정·렌더). */
    fun powerRaw(on: Boolean, tMs: Long, sticky: Boolean = false): Boolean = order.raw(on, tMs, sticky)

    /** 2초 안정된 전원 변화 가운데 미룰 것 없는 것을 적용했으면 true(JudgeOrder.settle). */
    fun settlePower(t: Long): Boolean = order.settle(t)

    /**
     * 끝(트리거 뒤 5분)이 지난 사고 의심은 버리고, 열린 확인 창은 응답 시간을 처음부터 다시 센다. 장비 거치로 복원되는데 거치 중 저장이 아니면 무동작은 재시작부터 센다(H2).
     * 저장 때 구역 안이었으면 구역 안·진입 시각·정착을 이어가고, 시작 때 구역 밖이면 ZONE_RESUME_HOLD_MS 안에 보고를 기다린다.
     */
    private fun resume(s: LoneWorkerResume.State, nowMs: Long) {
        if (s.accidentHold != null && s.accidentUntil != null && s.accidentUntil > nowMs) {
            accidentFrom = s.accidentHold
            lastDistinctAt = s.accidentHold
            accidentUntil = s.accidentUntil
        }
        carried = s.carried
        stillBase = if (mounted && !s.mounted) nowMs else s.stillBase // 거치 셈을 저장하지 않은 상태(v2·거치 아님)면 기준을 믿지 않고 재시작부터 센다(H2)
        s.zoneSince?.let {
            if (!zone.inside) hold.holdZone(nowMs + ZONE_RESUME_HOLD_MS)
            zone.reset(true, it)
            zoneSettled = s.zoneSettled
        }
        if (hold.powerHeld(nowMs)) hold.holdCheck(s.check) else if (s.check.isNotEmpty()) toChecking(s.check, nowMs, nowMs)
        if (zoneSettled) closeCheck(nowMs, "still") // 정착 상태 복원이면 무동작 창을 열지도 들지도 않는다
    }

    fun responseLeftMs(nowMs: Long): Long = sosAt()?.let { (it - nowMs).coerceAtLeast(0L) } ?: 0L
    fun responseTotalMs(): Long = respFor(trigger)  // 지금 창의 전체 응답 시간(화면 링의 기준, 마감과 같은 규칙)

    /** 모니터 예약(nextCheckAt)·웨이크락(waitingToJudge)·flush(waitingOnSensors)·즉시 판정(dueNow) — JudgeOrder. */
    fun nextCheckAt(nowMs: Long): Long? = order.nextCheckAt(nowMs)
    fun waitingToJudge(nowMs: Long): Boolean = order.waitingToJudge(nowMs)
    fun waitingOnSensors(nowMs: Long): Boolean = order.waitingOnSensors(nowMs)
    fun dueNow(nowMs: Long): Boolean = order.dueNow(nowMs)

    private fun respFor(trig: String): Long = if (trig == "fall") ACCIDENT_RESPONSE_MS else responseMs

    /** 센서 데이터가 들어온 끝 시각: 닫힌 가속도 창 끝, 걸음 센서를 쓰면 걸음 전달 시각과 둘 중 이른 쪽. */
    private fun sensedTo(): Long = if (stepsAvailable) minOf(walk.closedTo, walk.stepSeenTo) else walk.closedTo

    /** 사고 확인 창을 여는 마감: 마지막 뚜렷한 움직임(없으면 트리거)부터 30초. 의심 5분을 넘으면 없음. */
    private fun accidentOpenAt(): Long? {
        if (accidentUntil == Long.MIN_VALUE) return null
        val at = maxOf(accidentFrom, lastDistinctAt) + ACCIDENT_STILL_MS
        return if (at <= accidentUntil) at else null
    }

    /** 확인 창 → SOS 마감. */
    private fun sosAt(): Long? = if (mode == Mode.CHECKING) modeSinceMs + respFor(trigger) else null

    /**
     * 사고 확인 창을 여는 마감: 지켜보는 중이거나, 무동작 확인 창이 열려 있고 사고 확인 창의 SOS 마감(여는 시각 +
     * ACCIDENT_RESPONSE_MS)이 그 창의 SOS 마감보다 이를 때만(열 수 없는 마감은 판정·대기 대상이 아니다).
     */
    private fun fallOpenAt(nowMs: Long): Long? {
        val at = accidentOpenAt() ?: return null
        val open = mode == Mode.WATCHING || (trigger == "still" &&
            sosAt()?.let { maxOf(nowMs, at) + respFor("fall") < it } == true)
        return if (open) at else null
    }

    /** 무동작 확인 창을 여는 마감: 지님, 정착 구역 밖, 사이렌 멈춤이 가리지 않을 때(멈춤 전에 지난 마감은 판정), 지켜보는 중일 때 기준 + stillMs. */
    private fun stillOpenAt(): Long? = (stillBase + stillMs).takeIf {
        enabled && !zoneSettled && !siren.covers(it) && rest == Rest.NONE && mode == Mode.WATCHING }

    /** 판정을 기다리는 마감. 보류 게이트(RestartHold.gate)가 있으면 그 시각 하나, 없으면 무동작·사고 창 열기와 SOS 마감. */
    private fun deadlines(nowMs: Long): List<Long> = hold.gate(nowMs)?.let { listOf(it) } ?: listOfNotNull(stillOpenAt(), fallOpenAt(nowMs), sosAt())

    /**
     * 사고 의심 판정. 사고 마감이 되면 사고 확인 창을 연다. 무동작 확인 창이 이미 열려 있으면 두 마감 중
     * 이른 쪽을 남긴다. 5분 안에 못 차면 의심을 끝낸다.
     */
    private fun accidentTick(nowMs: Long) {
        if (accidentUntil == Long.MIN_VALUE) return
        if (accidentOpenAt() == null) {
            if (nowMs >= accidentUntil) clearAccident()
            return
        }
        val openAt = fallOpenAt(nowMs) ?: return
        if (order.due(openAt, nowMs)) toChecking("fall", openAt, nowMs)
    }

    /** 뚜렷한 움직임: 사고 창을 닫고 사고 셈 기준을 옮긴다; 장비 거치가 아니면 무동작도 새로 세고 열린 무동작 창도 닫는다(장비 거치는 걸음을 안 씀, B4). 사고 의심은 계속, 대기 끝(지님)은 chargeAt 기준 규칙이 따로 본다. */
    private fun onDistinct(t: Long) {
        if (t > lastDistinctAt) lastDistinctAt = t
        floorAt = t
        if (mounted) return closeCheck(t, "fall") // 장비 거치: 걸음은 무동작을 다시 세지도 닫지도 않고, 사고 창만 전처럼 닫는다 (B4·B5)
        raiseStillBase(t)
        closeCheck(t)
    }

    private fun carry(t: Long) {
        carried = true
        raiseStillBase(t)
    }

    private fun raiseStillBase(t: Long) {
        if (t > stillBase) stillBase = t
    }

    /** 닫힘 규칙 한 곳: 열린 확인 창(kind 가 있으면 그 종류만)과 전원 보류 중 들고 있는 복원 창을 같이 닫는다. SOS 는 cancelSos 로만, 사고 의심은 호출처가 끝낸다. */
    private fun closeCheck(t: Long, kind: String = "") {
        hold.dropCheck(kind)
        if (mode == Mode.CHECKING && (kind.isEmpty() || trigger == kind)) toWatching(t)
    }

    private fun clearAccident() {
        accidentFrom = Long.MIN_VALUE
        accidentUntil = Long.MIN_VALUE
    }

    /** 재시작 구역 보류 한도가 지났으면 그 시각에 정착 계산 없이 벗어난 것으로 먼저 보고(C3), 보류 중에는 정착으로 올리지 않는다 — 정착은 안쪽 보고로만. */
    private fun updateSettle(nowMs: Long) {
        hold.zoneExpired(nowMs)?.let { leaveZone(it) }
        if (!hold.zoneHeld && zone.inside &&!zoneSettled && nowMs - zone.since >= ZONE_SETTLE_MS) {
            zoneSettled = true
            closeCheck(nowMs, "still")
        }
    }

    private fun toWatching(nowMs: Long) {
        raiseStillBase(nowMs)
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
        shakeFloorAt = floor
    }

    private fun toSos(nowMs: Long) {
        mode = Mode.SOS
        modeSinceMs = nowMs
        clearAccident()
    }

    /** 경보 진동은 진동기가 있을 때 동료 구조 요청 사이렌에서만. 확인 창·본인 SOS·사고 의심 중인 요구조자 의심 기기는 진동 없이 소리·화면만 쓴다. */
    val alarmVibrates: Boolean
        get() = canVibrate && mode == Mode.WATCHING && accidentUntil == Long.MIN_VALUE && peerStore.audible().isNotEmpty()
}
