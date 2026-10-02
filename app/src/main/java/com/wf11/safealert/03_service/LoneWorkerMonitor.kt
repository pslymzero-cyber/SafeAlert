package com.wf11.safealert.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.wf11.safealert.firebase.SosRemote
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings

/**
 * 단독 작업자 사고·무동작 SOS 의 안드로이드 접착부 (v1.1.99).
 *
 * 판정은 LoneWorkerLogic/MotionAnalyzer(순수)가 하고, 여기서는 센서(LoneWorkerSensors)·전원·화면·소리·알림·서버·BLE 를 잇는다.
 * BleService 는 이 클래스의 진입점만 부른다. 충돌 판정 경로와 광고 첫 바이트는 건드리지 않는다.
 *
 * 생성자는 참조만 저장한다(시스템 서비스 호출 없음). start() 전에는 모든 진입점이 즉시 반환한다.
 * 모든 진입점은 메인 스레드에서 불린다(스캔 콜백·서비스 명령·센서 메인 루퍼·서버 콜백은 main 으로 게시).
 */
class LoneWorkerMonitor(
    private val ctx: Context,
    private val advertiseSos: (Boolean, Int, Int) -> Unit,
    setAlarmVolume: (Int) -> Unit
) {

    /** 확인 화면이 그리는 상태. mode 가 WATCHING 이고 동료 줄이 없으면 uiState() 가 null 을 준다. */
    data class UiState(
        val mode: LoneWorkerLogic.Mode,
        val responseLeftSec: Int,
        val peers: List<PeerRow>,  // 그린 동료 줄(그린 순서)
        val serverStatus: String?, // 내 SOS 서버 전송 상태(SOS 가 아니면 null)
        val alarmFault: String? = null, // 경보음 볼륨을 올리지 못했을 때의 안내(v1.1.99)
        val trigger: String = "",       // 확인 창·SOS 이유("still" 무동작, "fall" 낙상)
        val responseTotalSec: Int = 0,  // 이 확인 창의 전체 응답 시간(남은 시간 링의 기준)
        val stillMin: Int = 0,          // 무동작 확인까지의 분(이유 칩 문구)
        val responseLeftMs: Long = 0L,  // 남은 응답 시간(ms) — 화면이 초 경계에 맞춰 다시 그린다
        val stepsAvailable: Boolean = true // 걸음 센서로 셈(아니면 걷는 모양 이어짐으로 셈) — 걸음 안내 문구
    ) {
        val peerActive: Boolean get() = peers.any { it.active }
    }

    companion object {
        private const val REFRESH_MS = 5_000L
        private const val TICK_MIN_MS = 1_000L
        private const val BEACON_NOTE_MS = 2_000L
        private const val SID_MISS_MS = 30_000L
        private const val WAKE_LOCK_MS = 10 * 60_000L
        private const val SYNC_TICK_MS = 10_000L

        @Volatile var current: LoneWorkerMonitor? = null
        var uiListener: (() -> Unit)? = null
    }

    private var logic = LoneWorkerLogic("")
    private val alarm = LoneWorkerAlarm(ctx, setAlarmVolume)
    private val handler = Handler(Looper.getMainLooper())
    private val sync = LoneWorkerSosSync(ctx, handler,
        { rec ->
            if (started) {
                val t = now()
                logic.onPeerServer(LoneWorkerPeers.ServerRec(
                    rec.key, rec.bleId, rec.name, rec.role, rec.trigger, rec.beacon, rec.createdAt, rec.active, rec.ep,
                    LoneWorkerPeers.resolvedLocalMs(rec.resolvedAt, SosRemote.serverNowMs(), System.currentTimeMillis(), t)
                ), t)
                render()
            }
        },
        { render() })

    private var started = false
    /** 걸음 센서는 있는데 신체 활동 권한이 없다(메인 화면 경고용). */
    val stepPermissionMissing: Boolean get() = started && sensors.stepPermissionMissing
    private var name = ""
    private var roleName = ""
    /** 장비 모드: BleConstants.categoryName 의 장비 선택 — 역할로만 정한다 (B1). */
    private val equipment: Boolean get() = roleName == "FORKLIFT" || roleName == "EPJ"
    private var lastMode = LoneWorkerLogic.Mode.WATCHING
    private var lastAudible: Set<String> = emptySet()
    private val watchdog = LoneWorkerWatchdog(ctx, { onWatchdog() }, { onNotificationDismissed() })
    private val notifier = LoneWorkerNotifier(ctx) { watchdog.dismissPi() }
    private var lastTickAt = 0L
    /** 스로틀 중에도 즉시 판정을 보는 시각 — 직전 tick 에서 지난 마감이 데이터를 기다렸으면 그 tick, 아니면 그때의 nextCheckAt (v1.1.99). */
    private var dueFrom = Long.MAX_VALUE
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopOn = false
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val beaconNoteAt = HashMap<String, Long>()

    val sosActive: Boolean get() = started && logic.sosActive
    // (v1.1.99) 광고에 실을 구조 요청 회차·비콘 짧은 ID — 구조 요청 중이 아니면 0
    val sosEpisode: Int get() = if (sosActive) sync.episode() else 0
    val sosHint: Int get() = if (sosActive) sync.hint() else 0
    private val sidLabels = HashMap<Int, String>()   // (v1.1.99) 짧은 ID → 비콘 라벨 캐시(찾은 것만 저장), stop 에서 비움
    private val sidMissUntil = HashMap<Int, Long>()  // 라벨 없는 짧은 ID → 다시 찾을 수 있는 시각
    private val power = LoneWorkerPower(ctx) { onPowerRaw(it, false) }
    private val sensors = LoneWorkerSensors(ctx, handler, { logic }) { onSensorEvent(it) }
    private val resume = LoneWorkerResume(ctx)
    private var lastRest = LoneWorkerLogic.Rest.NONE
    /** (v1.1.99) 메인 화면 안내 줄: 센서 없음이면 감시 불가, 아니면 무동작 확인을 쉬는 이유(거치·움직임 대기). */
    val banner: String? get() = when {
        !started -> null
        sensors.noSensor -> "사고·무동작 감시 불가 — 가속도 센서 없음 (동료 구조 요청 수신은 계속)"
        else -> logic.rest.banner
    }
    private var rendering = false
    private var renderAgain = false

    private fun now() = SystemClock.elapsedRealtime()

    // ── 시작·종료 ──────────────────────────────────────────────

    fun start(bleId: String, name: String, roleName: String, zoneInside: Boolean) {
        this.name = name
        this.roleName = roleName
        logic.myBleId = bleId
        logic.setEquipment(equipment, now()) // 복원(startFrom)보다 먼저 넣어야 복원이 장비 거치를 안다(H2), 역할이 바뀌면 applyMode 가 새로 시작한다 (B1)
        if (started) return
        started = true
        // 충전 중이면 거치로, 아니면 첫 뚜렷한 움직임 대기로 시작한다 (v1.1.99)
        val plugged = power.start()
        val t0 = now()
        // 저장 상태로 이어간다 — 지금 전원과 다르면 재시작 전원 보류(RestartHold) (v1.1.99, B6)
        // 진동기가 없으면 사이렌 진동도 그 동안의 무동작 셈 멈춤도 없다 (v1.1.99, D2)
        logic.canVibrate = VibrationHelper.vibrator(ctx)?.hasVibrator() == true
        logic.startFrom(t0, zoneInside, plugged, resume.load(t0))
        // 저장된 본인 SOS 가 있으면 첫 렌더 전에 되살린다 — 같은 서버 키로 사이렌·광고 bit1 이 다시 켜진다 (v1.1.99, R3)
        sync.restoredTrigger()?.let { logic.restoreSos(it, now()) }
        notifier.createChannel()
        notifier.cancel() // 이전 프로세스가 남긴 알림을 한 번 치운다 (v1.1.99)
        watchdog.start()
        SirenGenerator.prewarm() // 사이렌·확인음 PCM 을 백그라운드에서 미리 만든다 (v1.1.99)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == DevSettings.KEY_LW_ENABLED || key == DevSettings.KEY_LW_STILL_MIN ||
                key == DevSettings.KEY_LW_RESPONSE_MIN || key == DevSettings.KEY_LW_ZONE_FALL_CM ||
                key == DevSettings.KEY_LW_ZONE_FALL_G || key == DevSettings.KEY_LW_ZONE_FALL_DEG) handler.post { applySettings() }
        }
        prefsListener = l
        DevSettings.registerOnChange(l)
        applySettings()
        handler.post(syncRunnable)
        current = this
        tickNow() // 첫 판정 tick 으로 전원 확정 확인·보류 끝을 예약한다 (v1.1.99)
    }

    /** 10초마다: 동료 수신 재연결·내 SOS 전송 재시도·센서 공백 검사(등록 실패도 백오프로 재시도) (v1.1.99). */
    private val syncRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            sync.tick()
            sync.heartbeat(DevSettings.lwEnabled, roleName) // (v1.2.2) 살아 있음 기록만 — 판정과 무관
            onPowerRaw(power.plugged(), true) // 방송을 놓쳐도 스티키 배터리 상태로 보정(대기 중이면 버림, 같은 2초 디바운스)
            sensors.refreshSteps() // 신체 활동 권한이 바뀌었으면 걸음 센서 등록을 맞춘다
            checkStall(now())
            handler.postDelayed(this, SYNC_TICK_MS)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensors.gyroLog(false, false) // 자이로 측정은 사이렌 끝이나 여기서만 끈다 (v1.1.99)
        sensors.unregister()
        prefsListener?.let { DevSettings.unregisterOnChange(it) }
        prefsListener = null
        sync.endHeartbeat()
        sync.stopListening()
        alarm.stop(final = true)
        releaseWakeLock()
        handler.removeCallbacksAndMessages(null)
        loopOn = false
        watchdog.stop()
        power.stop()
        notifier.cancel()
        lastMode = LoneWorkerLogic.Mode.WATCHING
        lastAudible = emptySet()
        sidLabels.clear()
        sidMissUntil.clear()
        // 서버의 active 기록과 저장된 내 SOS 는 그대로 둔다 — 해제는 본인 [괜찮아요]뿐 (D-05, R3)
        logic = LoneWorkerLogic("") // 재시작 때 지난 동료 항목이 되살아나지 않게 비운다
        if (current === this) current = null
        uiListener?.invoke()
    }

    private fun applySettings() {
        if (!started) return
        val t = now()
        logic.stillMs = DevSettings.lwStillMin * 60_000L
        logic.responseMs = DevSettings.lwResponseMin * 60_000L
        // 센서가 아예 없으면 판정을 끈다. 등록만 실패한 경우는 판정을 유지한다: 확인은 그대로 열리고 신호 없음은 움직임 없음으로 센다 (v1.1.99)
        if (DevSettings.lwEnabled) {
            if (!sensors.registered) sensors.resetStall(t)
            sensors.register()
            if (sensors.noSensor) watchdog.disarm() else watchdog.arm()
        } else {
            sensors.unregister()
            watchdog.disarm()
        }
        // 세이프존 낙상 기준(개발자 설정). 충격은 기본 임계와 같은 센서 범위 보정(2 G 센서)을 거친다 (D-02, D-03)
        logic.zoneFall = MotionAnalyzer.ZoneFall(
            MotionAnalyzer.freeFallMsFor(DevSettings.lwZoneFallCm),
            MotionAnalyzer.impactGFor(sensors.rangeMs2, DevSettings.lwZoneFallG),
            DevSettings.lwZoneFallDeg.toDouble()
        )
        logic.setEnabled(DevSettings.lwEnabled && !sensors.noSensor, t)
        render()
    }

    // ── 센서 ──────────────────────────────────────────────────

    /**
     * 센서 콜백 뒤: 상태·쉼 이유가 바뀌었으면 바로, 아니면 1초에 한 번 tick·render 한다. 마감 시각이 지난 뒤(깊은 잠으로
     * 예약이 늦어도) 그 마감을 센서 데이터가 덮으면 스로틀과 무관하게 이 자리에서 판정한다 — 같은 배치의 뒤 데이터보다 먼저(C5) (v1.1.99).
     * 콜백마다 판정 순서 장치에 끝을 알린다 — 전원 대기로만 막힌 마감 뒤 입력은 판정 뒤로(N1).
     */
    private fun onSensorEvent(t: Long) {
        if (!started) return
        logic.sensorEventEnd(t)
        if (logic.mode == lastMode && logic.rest == lastRest && t - lastTickAt < TICK_MIN_MS &&
            !(t >= dueFrom && logic.dueNow(t))) return
        lastTickAt = t
        tick(t)
        render()
    }

    /**
     * 판정 tick 한 곳. 지난 마감이 센서 데이터를 기다리면 flush 를 요청하고, 다음 마감(없으면 LATE_MS 백스톱)에
     * tick 이 한 번 더 돌도록 예약한다 (v1.1.99).
     */
    private fun tick(t: Long) {
        logic.tick(t)
        val next = logic.nextCheckAt(t)
        val waiting = logic.waitingOnSensors(t)
        if (waiting) sensors.flush()
        dueFrom = if (waiting) t else next ?: Long.MAX_VALUE
        handler.removeCallbacks(deadlineRunnable)
        next?.let { handler.postDelayed(deadlineRunnable, (it - t).coerceAtLeast(0L)) }
    }

    /** 예약한 마감·전원 확정 시각에 판정 tick 과 렌더를 한 번 (v1.1.99). */
    private fun tickNow() {
        if (!started) return
        val t = now()
        lastTickAt = t
        tick(t)
        render()
    }

    private val deadlineRunnable = Runnable { tickNow() }

    /** 전원 원시 값: 판정 로직 디바운스에 넣고, 적용했거나 대기가 바뀌었으면 판정·렌더 한 경로(tickNow) (v1.1.99). */
    private fun onPowerRaw(on: Boolean, sticky: Boolean) {
        if (!started) return
        val changed = logic.powerRaw(on, now(), sticky)
        if (changed) tickNow()
    }

    /**
     * 센서 신호 공백 검사 (RR08). 끊긴 동안에도 움직임이 없는 것으로 보고 무동작 시간을 계속 센다.
     * 다시 등록할 때는 무동작 시작 시각·확인 중 상태를 건드리지 않는다(열려 있는 확인을 취소하지 않기 위해).
     * 약 1분 응답이 없으면(두 번째 재등록 검사) 움직임 대기를 끝낸다.
     */
    private fun checkStall(t: Long) {
        if (!started) return
        // 등록에 실패해 센서가 없는 동안에도 같은 백오프로 다시 등록한다
        if (DevSettings.lwEnabled && !sensors.noSensor) sensors.checkStall(t)
        if (sensors.stalled) logic.sensorSilent(t)
        tick(t)
        render()
    }

    private fun onWatchdog() {
        if (!started) return
        checkStall(now())
        if (DevSettings.lwEnabled && !sensors.noSensor) watchdog.arm()
    }

    /** 알림을 쓸어 내렸다: 큰 알림이면 다시 올린다 (RR13). */
    private fun onNotificationDismissed() {
        if (!started) return
        notifier.forget()
        render()
    }

    // ── BleService 진입점 ─────────────────────────────────────

    fun onZoneChanged(inside: Boolean) {
        if (!started) return
        val t = now()
        logic.onZone(inside, t)
        tick(t)
        render()
    }

    /** BleService 회전 폴링(1.5초, 직진 아닐 때만). 장비 모드에서만 넘긴다 — 보행 모드는 그대로 (B2·B5). */
    fun onTurn() {
        if (!started || !equipment) return
        val t = now()
        logic.onTurn(t)
        tick(t)
        render()
    }

    /** 스캔마다 불리므로 가볍게: 동료 항목이 바뀐 때만 다시 그린다. */
    fun onPeerBle(bleId: String, sos: Boolean, episode: Int = 0, hint: Int = 0) {
        if (!started) return
        val before = peerSig()
        val t = now()
        val label = if (sos && hint != 0) sidLabel(hint, t) else ""
        logic.onPeerBle(bleId, sos, t, episode, label)
        if (peerSig() != before) render()
    }

    /** 찾은 라벨은 캐시하고, 못 찾은 짧은 ID 는 30초 동안 다시 찾지 않는다. */
    private fun sidLabel(hint: Int, t: Long): String =
        sidLabels[hint] ?: if ((sidMissUntil[hint] ?: Long.MIN_VALUE) > t) "" else
            BeaconRegistry.labelForShortId(hint)?.takeIf { it.isNotEmpty() }?.also { sidLabels[hint] = it }
                ?: "".also { sidMissUntil[hint] = t + SID_MISS_MS }

    fun noteBeacon(deviceId: String, rssi: Int) {
        if (!started || !BeaconRegistry.isBeaconFullId(deviceId)) return
        val t = now()
        val last = beaconNoteAt[deviceId]
        if (last != null && t - last < BEACON_NOTE_MS) return
        beaconNoteAt[deviceId] = t
        // (v1.1.99) 등록 비콘만 짧은 ID 를 싣는다(미등록 0)
        val sid = if (BeaconRegistry.findProfileByFullId(deviceId) != null)
            com.wf11.safealert.ble.SosAdvert.beaconShortId(deviceId.substringAfter("BEA_")) else 0
        logic.noteBeacon(BeaconRegistry.labelForFullId(deviceId), rssi, t, sid)
    }

    fun ack() {
        if (!started) return
        logic.ackWorking(now())
        render()
    }

    fun cancelSos() {
        if (!started) return
        logic.cancelSos(now())
        render()
    }

    /** targets(항목 id -> 회차 ID) 의 항목만 묵음으로 만든다. */
    fun silencePeers(targets: Map<String, String>) {
        if (!started) return
        logic.silencePeers(now(), targets)
        render()
    }

    private fun peerSig(): Int {
        var h = 0
        for (p in logic.peers) {
            h = h * 31 + p.id.hashCode()
            h = h * 31 + (if (p.active) 1 else 0) + (if (p.silenced) 2 else 0)
        }
        return h
    }

    // ── 화면 상태 ─────────────────────────────────────────────

    fun uiState(): UiState? {
        if (!started) return null
        val t = now()
        val shown = logic.peers.filter { !it.silenced }  // 해제된 항목은 [닫기] 전까지 보인다
        if (logic.mode == LoneWorkerLogic.Mode.WATCHING && shown.isEmpty()) return null
        val left = logic.responseLeftMs(t)
        return UiState(
            logic.mode,
            ((left + 999L) / 1000L).toInt(),
            shown.map { PeerRow(it.id, it.epId, it.line(t), it.active) },
            if (logic.mode == LoneWorkerLogic.Mode.SOS) sync.statusText() else null,
            alarm.volumeFault,
            logic.trigger,
            (logic.responseTotalMs() / 1000L).toInt(),
            (logic.stillMs / 60_000L).toInt(),
            left,
            logic.stepsAvailable
        )
    }

    // ── 렌더링: 상태 전환·소리·알림·화면 ────────────────────────

    private val keepText: String get() = logic.rest.keepText

    /** 조용한 안내 알림. 우선순위: 센서 없음, 센서 등록 실패, 센서 신호 끊김, 해제 미전송, 걸음 권한 없음. */
    private fun notice(): Pair<String, String>? = when {
        DevSettings.lwEnabled && sensors.noSensor ->
            "무동작 감시 불가" to "이 기기에는 가속도 센서가 없어 사고·무동작 감시를 하지 않습니다. 동료 구조 요청 수신은 계속됩니다"
        DevSettings.lwEnabled && !sensors.registered ->
            "무동작 감시 불가" to "가속도 센서를 등록하지 못했습니다. " + keepText + " 센서를 다시 등록합니다"
        DevSettings.lwEnabled && sensors.stalled ->
            "무동작 감시 불가" to "가속도 센서 신호가 끊겼습니다. " + keepText + " 센서를 다시 등록합니다"
        sync.resolveFailing() ->
            "구조 요청 해제 미전송" to "서버에 해제를 기록하지 못했습니다. 계속 다시 보냅니다"
        DevSettings.lwEnabled && sensors.stepPermissionMissing ->
            "걸음 감지 꺼짐" to "신체 활동 권한이 없어 걸음 대신 강한 움직임으로 판단합니다. 앱 설정에서 신체 활동을 허용하세요"
        else -> null
    }

    /** SosLedger.begin/resolve 가 onChange 로 render 를 동기 호출하므로 다시 들어오면 한 번 더 돌 표시만 남긴다. */
    private fun render() {
        if (!started) return
        if (rendering) {
            renderAgain = true
            return
        }
        rendering = true
        try {
            do {
                renderAgain = false
                renderOnce()
            } while (renderAgain && started)
        } finally {
            rendering = false
        }
    }

    private fun renderOnce() {
        val t = now()
        val mode = logic.mode
        var showScreen = false

        if (mode == LoneWorkerLogic.Mode.CHECKING && lastMode != LoneWorkerLogic.Mode.CHECKING) showScreen = true
        if (mode == LoneWorkerLogic.Mode.SOS && lastMode != LoneWorkerLogic.Mode.SOS) {
            val hint = logic.beaconHint(t)
            sync.begin(logic.myBleId, name, roleName, logic.trigger, hint?.first, hint?.second, logic.beaconSid(t))
            advertiseSos(true, sync.episode(), sync.hint())
            showScreen = true
        }
        if (mode != LoneWorkerLogic.Mode.SOS && lastMode == LoneWorkerLogic.Mode.SOS) {
            sync.resolve()
            advertiseSos(false, 0, 0)
        }
        lastMode = mode
        lastRest = logic.rest

        val audible = logic.audiblePeers()
        val ids = audible.mapTo(HashSet()) { it.epId }
        if (ids.any { it !in lastAudible }) showScreen = true
        lastAudible = ids

        // 소리 우선순위: 본인 SOS > 확인 창(확인음, 진동 없음) > 동료 사이렌 (v1.1.99, F2)
        val vibrates = logic.alarmVibrates
        val p = LoneWorkerAlarm.Pattern.of(mode, audible.isNotEmpty())
        if (p != null) alarm.play(p, vibrates) else alarm.stop()
        sensors.gyroLog(audible.isNotEmpty(), vibrates)

        notifier.update(mode, audible, logic.peers.filter { !it.active && !it.silenced }, notice(), showScreen)
        if (showScreen) notifier.openScreen()
        updateWakeLock(false)
        scheduleLoop()
        resume.save(logic.snapshot(t), t)
        uiListener?.invoke()
    }

    // ── 5초 갱신·웨이크락 ─────────────────────────────────────

    private fun needLoop() =
        logic.mode != LoneWorkerLogic.Mode.WATCHING || logic.peers.isNotEmpty() || sensors.needsWake

    private fun scheduleLoop() {
        if (loopOn || !needLoop()) return
        loopOn = true
        handler.postDelayed(refreshRunnable, REFRESH_MS)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            loopOn = false
            if (!started) return
            tickNow()
            alarm.refresh()
            updateWakeLock(true)
            scheduleLoop()
        }
    }

    private fun updateWakeLock(renew: Boolean) {
        // 지난 마감이 판정을 기다리는 동안(센서 데이터·그 전에 시작한 전원 대기)도 잡아 LATE_MS 백스톱·전원 확정 확인을 보장한다
        val need = sensors.needsWake || logic.mode != LoneWorkerLogic.Mode.WATCHING ||
            logic.audiblePeers().isNotEmpty() || logic.waitingToJudge(now())
        if (!need) {
            releaseWakeLock()
            return
        }
        val wl = wakeLock ?: runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafeAlert:LoneWorker")
                .apply { setReferenceCounted(false) }
        }.getOrNull()?.also { wakeLock = it } ?: return
        if (renew || !wl.isHeld) runCatching { wl.acquire(WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
    }
}
