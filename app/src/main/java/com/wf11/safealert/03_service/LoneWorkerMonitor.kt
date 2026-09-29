package com.wf11.safealert.service

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 단독 작업자 무동작·낙상 SOS 의 안드로이드 접착부 (v1.1.99).
 *
 * 판정은 LoneWorkerLogic/MotionAnalyzer(순수)가 하고, 여기서는 센서·화면·소리·알림·서버·BLE 를 잇는다.
 * BleService 는 이 클래스의 진입점만 부른다. 충돌 판정 경로와 광고 첫 바이트는 건드리지 않는다.
 *
 * 생성자는 참조만 저장한다(시스템 서비스 호출 없음). start() 전에는 모든 진입점이 즉시 반환한다.
 * 모든 진입점은 메인 스레드에서 불린다(스캔 콜백·서비스 명령·센서 메인 루퍼·서버 콜백은 main 으로 게시).
 */
class LoneWorkerMonitor(
    private val ctx: Context,
    private val advertiseSos: (Boolean, Int, Int) -> Unit,
    setAlarmVolume: (Int) -> Unit
) : SensorEventListener {

    /** 확인 화면이 그리는 상태. mode 가 WATCHING 이고 동료 줄이 없으면 uiState() 가 null 을 준다. */
    data class UiState(
        val mode: LoneWorkerLogic.Mode,
        val responseLeftSec: Int,
        val peerLines: List<String>,
        val peerActive: Boolean,
        val serverStatus: String?, // 내 SOS 서버 전송 상태(SOS 가 아니면 null)
        val alarmFault: String? = null // 경보음 볼륨을 올리지 못했을 때의 안내(v1.1.99)
    )

    companion object {
        private const val TAG = "LoneWorkerMonitor"
        private const val REFRESH_MS = 5_000L
        private const val TICK_MIN_MS = 1_000L
        private const val BEACON_NOTE_MS = 2_000L
        private const val WAKE_LOCK_MS = 10 * 60_000L
        private const val SYNC_TICK_MS = 10_000L

        @Volatile var current: LoneWorkerMonitor? = null
        var uiListener: (() -> Unit)? = null
    }

    private var logic = LoneWorkerLogic("")
    private var analyzer = MotionAnalyzer() // 센서 등록마다 새로 만든다(충격 임계값 주입)
    private val alarm = LoneWorkerAlarm(ctx, setAlarmVolume)
    private val handler = Handler(Looper.getMainLooper())
    private val sync = LoneWorkerSosSync(ctx, handler,
        { rec ->
            if (started) {
                logic.onPeerServer(rec.key, rec.bleId, rec.name, rec.role, rec.trigger, rec.beacon, rec.createdAt, rec.active, now(), rec.ep)
                render()
            }
        },
        { render() })

    private var started = false
    private var name = ""
    private var roleName = ""
    private var lastMode = LoneWorkerLogic.Mode.WATCHING
    private var lastAudible: Set<String> = emptySet()
    private val stall = SensorStall()
    private var stallLogged = false
    private val watchdog = LoneWorkerWatchdog(ctx, { onWatchdog() }, { onNotificationDismissed() })
    private val notifier = LoneWorkerNotifier(ctx) { watchdog.dismissPi() }
    private var lastTickAt = 0L
    private var sensorManager: SensorManager? = null
    private var sensorRegistered = false
    private var fallbackWake = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopOn = false
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val beaconNoteAt = HashMap<String, Long>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.KOREA)

    val sosActive: Boolean get() = started && logic.sosActive
    // (v1.1.99) 광고에 실을 구조 요청 회차·비콘 짧은 ID — 구조 요청 중이 아니면 0
    val sosEpisode: Int get() = if (sosActive) sync.episode() else 0
    val sosHint: Int get() = if (sosActive) sync.hint() else 0
    private val sidLabels = HashMap<Int, String>()   // (v1.1.99) 짧은 ID → 비콘 라벨 캐시(빈 문자열=없음), stop 에서 비움

    private fun now() = SystemClock.elapsedRealtime()

    // ── 시작·종료 ──────────────────────────────────────────────

    fun start(bleId: String, name: String, roleName: String, zoneInside: Boolean) {
        this.name = name
        this.roleName = roleName
        logic.myBleId = bleId
        if (started) return
        started = true
        logic.start(now(), zoneInside)
        // 저장된 본인 SOS 가 있으면 첫 렌더 전에 되살린다 — 같은 서버 키로 사이렌·광고 bit1 이 다시 켜진다 (v1.1.99, R3)
        sync.restoredTrigger()?.let { logic.restoreSos(it, now()) }
        notifier.createChannel()
        watchdog.start()
        SirenGenerator.prewarm() // 사이렌·확인음 PCM 을 백그라운드에서 미리 만든다 (v1.1.99)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == DevSettings.KEY_LW_ENABLED || key == DevSettings.KEY_LW_STILL_MIN ||
                key == DevSettings.KEY_LW_RESPONSE_MIN) handler.post { applySettings() }
        }
        prefsListener = l
        DevSettings.registerOnChange(l)
        applySettings()
        handler.post(syncRunnable)
        current = this
    }

    /** 10초마다: 동료 수신 재연결·내 SOS 전송 재시도 (v1.1.99). */
    private val syncRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            sync.tick()
            if (DevSettings.lwEnabled && !sensorRegistered) applySettings() // 센서 등록 재시도 (v1.1.99)
            checkStall(now())
            handler.postDelayed(this, SYNC_TICK_MS)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        unregisterSensor()
        prefsListener?.let { DevSettings.unregisterOnChange(it) }
        prefsListener = null
        sync.stopListening()
        alarm.stop()
        releaseWakeLock()
        handler.removeCallbacksAndMessages(null)
        loopOn = false
        watchdog.stop()
        notifier.cancel()
        lastMode = LoneWorkerLogic.Mode.WATCHING
        lastAudible = emptySet()
        sidLabels.clear()
        // 서버의 active 기록과 저장된 내 SOS 는 그대로 둔다 — 해제는 본인 [괜찮음]뿐 (D-05, R3)
        logic = LoneWorkerLogic("") // 재시작 때 지난 동료 항목이 되살아나지 않게 비운다
        if (current === this) current = null
        uiListener?.invoke()
    }

    private fun applySettings() {
        if (!started) return
        val t = now()
        logic.stillMs = DevSettings.lwStillMin * 60_000L
        logic.responseMs = DevSettings.lwResponseMin * 60_000L
        if (DevSettings.lwEnabled) {
            if (!sensorRegistered) stall.reset(t)
            registerSensor()
            logic.setEnabled(sensorRegistered, t)
        } else {
            unregisterSensor()
            logic.setEnabled(false, t)
        }
        render()
    }

    // ── 센서 ──────────────────────────────────────────────────

    private fun registerSensor() {
        if (sensorRegistered) return
        val sm = runCatching { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }.getOrNull()
        if (sm == null) {
            Log.w(TAG, "센서 서비스 없음 — 무동작 판정 끔")
            return
        }
        var s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        var wake = true
        if (s == null) {
            s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            wake = false
        }
        if (s == null) {
            Log.w(TAG, "가속도 센서 없음 — 무동작 판정 끔")
            return
        }
        val impactG = MotionAnalyzer.impactGFor(s.maximumRange)
        Log.i(TAG, "가속도 센서 wakeUp=${s.isWakeUpSensor} fifoMax=${s.fifoMaxEventCount} fifoReserved=${s.fifoReservedEventCount} range=${s.maximumRange} impactG=$impactG")
        analyzer = MotionAnalyzer(impactG)
        val ok = sm.registerListener(this, s, 20_000, 5_000_000, handler)
        if (!ok) {
            Log.w(TAG, "가속도 센서 등록 실패 — 무동작 판정 끔")
            return
        }
        sensorManager = sm
        sensorRegistered = true
        fallbackWake = !wake
    }

    private fun unregisterSensor() {
        if (sensorRegistered) runCatching { sensorManager?.unregisterListener(this) }
        sensorRegistered = false
        fallbackWake = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started) return
        val v = event.values
        val t = now()
        val evMs = event.timestamp / 1_000_000L
        stall.onEvent(t)
        // 이 앱의 진동 구간 표본은 활동 통계에서만 뺀다. 낙상 감지에는 그대로 넣는다 (v1.1.99, F07·RR02)
        when (analyzer.add(evMs, v[0], v[1], v[2], masked = VibrationHelper.window.covers(evMs))) {
            MotionAnalyzer.Signal.MOVED -> logic.onMoved(t)
            MotionAnalyzer.Signal.FALL -> logic.onFall(t)
            MotionAnalyzer.Signal.NONE -> {}
        }
        if (t - lastTickAt >= TICK_MIN_MS) {
            lastTickAt = t
            logic.tick(t)
            render()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * 센서 신호 공백 검사 (RR08). 끊긴 동안에도 움직임이 없는 것으로 보고 무동작 시간을 계속 센다.
     * 다시 등록할 때는 무동작 시작 시각·확인 중 상태를 건드리지 않는다(열려 있는 확인을 취소하지 않기 위해).
     */
    private fun checkStall(t: Long) {
        if (!started || !sensorRegistered) return
        when (stall.check(t)) {
            SensorStall.Action.REREGISTER -> {
                Log.w(TAG, "가속도 센서 신호 ${SensorStall.STALL_MS / 1000}초 없음 — 다시 등록")
                unregisterSensor()
                registerSensor()
            }
            SensorStall.Action.STALLED -> if (!stallLogged) Log.w(TAG, "가속도 센서 신호 끊김 지속")
            SensorStall.Action.OK -> {}
        }
        stallLogged = stall.stalled
        logic.tick(t)
        render()
    }

    private fun onWatchdog() {
        if (!started) return
        checkStall(now())
        watchdog.arm()
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
        logic.tick(t)
        render()
    }

    /** 스캔마다 불리므로 가볍게: 동료 항목이 바뀐 때만 다시 그린다. */
    fun onPeerBle(bleId: String, sos: Boolean, episode: Int = 0, hint: Int = 0) {
        if (!started) return
        val before = peerSig()
        val label = if (sos && hint != 0) sidLabels.getOrPut(hint) { BeaconRegistry.labelForShortId(hint) ?: "" } else ""
        logic.onPeerBle(bleId, sos, now(), episode, label)
        if (peerSig() != before) render()
    }

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

    fun silencePeers() {
        if (!started) return
        logic.silencePeers(now())
        render()
    }

    private fun peerSig(): Int {
        var h = 0
        for (p in logic.peers.values) {
            h = h * 31 + p.bleId.hashCode()
            h = h * 31 + (if (p.active) 1 else 0) + (if (p.silenced) 2 else 0)
        }
        return h
    }

    // ── 화면 상태 ─────────────────────────────────────────────

    fun uiState(): UiState? {
        if (!started) return null
        val t = now()
        val shown = logic.peers.values.filter { !it.active || !it.silenced }
        if (logic.mode == LoneWorkerLogic.Mode.WATCHING && shown.isEmpty()) return null
        val lines = shown.map { peerLine(it, t) }
        return UiState(
            logic.mode,
            ((logic.responseLeftMs(t) + 999L) / 1000L).toInt(),
            lines,
            shown.any { it.active },
            if (logic.mode == LoneWorkerLogic.Mode.SOS) sync.statusText() else null,
            alarm.volumeFault
        )
    }

    private fun peerLine(p: LoneWorkerLogic.Peer, t: Long): String {
        val role = when (p.role) {
            "WALKER" -> "보행자"
            "FORKLIFT" -> "지게차"
            else -> p.role
        }
        val wall = if (p.fromServer) p.createdAtMs else System.currentTimeMillis() - (t - p.firstSeenMs)
        return listOfNotNull(
            p.displayName(), role.ifEmpty { null }, timeFmt.format(Date(wall)),
            p.beacon.ifEmpty { null }?.let { if (p.fromServer) "마지막 위치: $it" else "${it} 근처" }, if (p.active) "구조 요청" else "해제됨"
        ).joinToString(" · ")
    }

    // ── 렌더링: 상태 전환·소리·알림·화면 ────────────────────────

    /** 조용한 안내 알림. 우선순위: 센서 불가, 센서 신호 끊김, 해제 미전송. */
    private fun notice(): Pair<String, String>? = when {
        DevSettings.lwEnabled && !sensorRegistered ->
            "무동작 감시 불가" to "가속도 센서를 쓸 수 없어 무동작·낙상 확인이 꺼져 있습니다. 계속 다시 등록을 시도합니다"
        DevSettings.lwEnabled && stall.stalled ->
            "무동작 감시 불가" to "가속도 센서 신호가 끊겼습니다. 움직임이 없는 것으로 보고 확인을 계속하며 센서를 다시 등록합니다"
        sync.resolveFailing() ->
            "구조 요청 해제 미전송" to "서버에 해제를 기록하지 못했습니다. 계속 다시 보냅니다"
        else -> null
    }

    private fun render() {
        if (!started) return
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

        val audible = logic.audiblePeers()
        val ids = audible.mapTo(HashSet()) { it.bleId }
        if (ids.any { it !in lastAudible }) showScreen = true
        lastAudible = ids

        when {
            mode == LoneWorkerLogic.Mode.SOS || audible.isNotEmpty() -> alarm.play(LoneWorkerAlarm.Pattern.SIREN)
            mode == LoneWorkerLogic.Mode.CHECKING -> alarm.play(LoneWorkerAlarm.Pattern.CHECK)
            else -> alarm.stop()
        }

        notifier.update(mode, audible, logic.peers.values.filter { !it.active }, notice(), showScreen)
        if (showScreen) notifier.openScreen()
        updateWakeLock(false)
        scheduleLoop()
        uiListener?.invoke()
    }

    // ── 5초 갱신·웨이크락 ─────────────────────────────────────

    private fun needLoop() =
        logic.mode != LoneWorkerLogic.Mode.WATCHING || logic.peers.isNotEmpty() || (fallbackWake && sensorRegistered)

    private fun scheduleLoop() {
        if (loopOn || !needLoop()) return
        loopOn = true
        handler.postDelayed(refreshRunnable, REFRESH_MS)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            loopOn = false
            if (!started) return
            val t = now()
            lastTickAt = t
            logic.tick(t)
            render()
            alarm.refresh()
            updateWakeLock(true)
            scheduleLoop()
        }
    }

    private fun updateWakeLock(renew: Boolean) {
        val need = (fallbackWake && sensorRegistered) || logic.mode != LoneWorkerLogic.Mode.WATCHING ||
            logic.audiblePeers().isNotEmpty()
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
