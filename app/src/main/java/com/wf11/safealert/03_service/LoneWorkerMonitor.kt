package com.wf11.safealert.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.wf11.safealert.firebase.FirebaseConfig
import com.wf11.safealert.firebase.FirebaseManager
import com.wf11.safealert.ui.LoneWorkerActivity
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
    private val advertiseSos: (Boolean) -> Unit,
    setAlarmVolume: (Int) -> Unit
) : SensorEventListener {

    /** 확인 화면이 그리는 상태. mode 가 WATCHING 이고 동료 줄이 없으면 uiState() 가 null 을 준다. */
    data class UiState(
        val mode: LoneWorkerLogic.Mode,
        val responseLeftSec: Int,
        val peerLines: List<String>,
        val peerActive: Boolean
    )

    companion object {
        private const val TAG = "LoneWorkerMonitor"
        const val CHANNEL_ID = "safealert_sos"
        const val NOTIF_ID = 4242
        private const val REFRESH_MS = 5_000L
        private const val TICK_MIN_MS = 1_000L
        private const val BEACON_NOTE_MS = 2_000L
        private const val WAKE_LOCK_MS = 10 * 60_000L
        private const val RESOLVED_NOTIF_MS = 60_000L
        private const val SOS_ATTACH_RETRY_MS = 10_000L

        @Volatile var current: LoneWorkerMonitor? = null
        var uiListener: (() -> Unit)? = null
    }

    private var logic = LoneWorkerLogic("")
    private val analyzer = MotionAnalyzer()
    private val alarm = LoneWorkerAlarm(ctx, setAlarmVolume)
    private val handler = Handler(Looper.getMainLooper())

    private var started = false
    private var name = ""
    private var roleName = ""
    private var lastMode = LoneWorkerLogic.Mode.WATCHING
    private var lastAudible: Set<String> = emptySet()
    private var lastNotifKey: String? = null
    private var lastTickAt = 0L
    private var sosKey: String? = null
    private var sosRemover: (() -> Unit)? = null
    private var sosSite = ""
    private var sensorManager: SensorManager? = null
    private var sensorRegistered = false
    private var fallbackWake = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopOn = false
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val beaconNoteAt = HashMap<String, Long>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.KOREA)

    val sosActive: Boolean get() = started && logic.sosActive

    private fun now() = SystemClock.elapsedRealtime()

    // ── 시작·종료 ──────────────────────────────────────────────

    fun start(bleId: String, name: String, roleName: String, zoneInside: Boolean) {
        this.name = name
        this.roleName = roleName
        logic.myBleId = bleId
        if (started) return
        started = true
        logic.start(now(), zoneInside)
        createChannel()
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == DevSettings.KEY_LW_ENABLED || key == DevSettings.KEY_LW_STILL_MIN ||
                key == DevSettings.KEY_LW_RESPONSE_MIN) handler.post { applySettings() }
        }
        prefsListener = l
        DevSettings.registerOnChange(l)
        applySettings()
        attachSosListener()
        current = this
    }

    /**
     * 동료 구조 요청 수신 — 서버 시각 기준 시작 이후 기록만 (D-06, D-08).
     * 로그인 전이나 사업장 코드 미설정이면 붙이지 못하므로 10초마다 다시 시도하고,
     * 사업장 코드가 바뀌면 새 노드로 다시 붙인다.
     */
    private fun attachSosListener() {
        if (!started) return
        val site = DevSettings.siteCode
        if (sosRemover != null && site != sosSite) {
            sosRemover?.invoke()
            sosRemover = null
        }
        if (sosRemover == null && site.isNotEmpty()) {
            if (runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull() == null) {
                FirebaseConfig.ensureSignedIn()
            } else {
                sosRemover = FirebaseManager.sosListen { rec ->
                    handler.post {
                        if (!started) return@post
                        logic.onPeerServer(rec.key, rec.bleId, rec.name, rec.role, rec.trigger, rec.beacon, rec.createdAt, rec.active, now())
                        render()
                    }
                }
                sosSite = site
            }
        }
        handler.postDelayed({ attachSosListener() }, SOS_ATTACH_RETRY_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        unregisterSensor()
        prefsListener?.let { DevSettings.unregisterOnChange(it) }
        prefsListener = null
        sosRemover?.invoke()
        sosRemover = null
        sosSite = ""
        alarm.stop()
        releaseWakeLock()
        handler.removeCallbacksAndMessages(null)
        loopOn = false
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) }
        lastNotifKey = null
        lastMode = LoneWorkerLogic.Mode.WATCHING
        lastAudible = emptySet()
        // 서버에 남은 active 기록은 그대로 둔다 — 해제는 본인 [괜찮음]뿐 (D-05)
        sosKey = null
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
        Log.i(TAG, "가속도 센서 wakeUp=${s.isWakeUpSensor} fifoMax=${s.fifoMaxEventCount} fifoReserved=${s.fifoReservedEventCount}")
        analyzer.reset()
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
        when (analyzer.add(event.timestamp / 1_000_000L, v[0], v[1], v[2])) {
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

    // ── BleService 진입점 ─────────────────────────────────────

    fun onZoneChanged(inside: Boolean) {
        if (!started) return
        val t = now()
        logic.onZone(inside, t)
        logic.tick(t)
        render()
    }

    /** 스캔마다 불리므로 가볍게: 동료 항목이 바뀐 때만 다시 그린다. */
    fun onPeerBle(bleId: String, sos: Boolean) {
        if (!started) return
        val before = peerSig()
        logic.onPeerBle(bleId, sos, now())
        if (peerSig() != before) render()
    }

    fun noteBeacon(deviceId: String, rssi: Int) {
        if (!started || !BeaconRegistry.isBeaconFullId(deviceId)) return
        val t = now()
        val last = beaconNoteAt[deviceId]
        if (last != null && t - last < BEACON_NOTE_MS) return
        beaconNoteAt[deviceId] = t
        logic.noteBeacon(BeaconRegistry.labelForFullId(deviceId), rssi, t)
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
        logic.silencePeers()
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
            shown.any { it.active }
        )
    }

    private fun displayName(p: LoneWorkerLogic.Peer) =
        p.name.ifEmpty { p.bleId.removePrefix("SAFEALERT_DEVICE_").removePrefix("SAFEALERT_WALKER_") }

    private fun peerLine(p: LoneWorkerLogic.Peer, t: Long): String {
        val role = when (p.role) {
            "WALKER" -> "보행자"
            "FORKLIFT" -> "지게차"
            else -> p.role
        }
        val wall = if (p.fromServer) p.createdAtMs else System.currentTimeMillis() - (t - p.firstSeenMs)
        return listOfNotNull(
            displayName(p), role.ifEmpty { null }, timeFmt.format(Date(wall)),
            p.beacon.ifEmpty { null }?.let { "마지막 위치: $it" }, if (p.active) "구조 요청" else "해제됨"
        ).joinToString(" · ")
    }

    // ── 렌더링: 상태 전환·소리·알림·화면 ────────────────────────

    private fun render() {
        if (!started) return
        val t = now()
        val mode = logic.mode
        var showScreen = false

        if (mode == LoneWorkerLogic.Mode.CHECKING && lastMode != LoneWorkerLogic.Mode.CHECKING) showScreen = true
        if (mode == LoneWorkerLogic.Mode.SOS && lastMode != LoneWorkerLogic.Mode.SOS) {
            ensureSosRecord(t)
            advertiseSos(true)
            showScreen = true
        }
        if (mode != LoneWorkerLogic.Mode.SOS && lastMode == LoneWorkerLogic.Mode.SOS) {
            sosKey?.let { FirebaseManager.sosResolve(it) }
            sosKey = null
            advertiseSos(false)
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

        updateNotification(mode, audible)
        if (showScreen) openScreen()
        updateWakeLock(false)
        scheduleLoop()
        uiListener?.invoke()
    }

    /** SOS 기록이 아직 없으면 생성한다(로그인 전이면 null → 5초 갱신에서 재시도). */
    private fun ensureSosRecord(t: Long) {
        if (sosKey != null || logic.mode != LoneWorkerLogic.Mode.SOS) return
        val hint = logic.beaconHint(t)
        sosKey = FirebaseManager.sosCreate(logic.myBleId, name, roleName, logic.trigger, hint?.first, hint?.second)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val ch = NotificationChannel(CHANNEL_ID, "구조 요청·근무 확인", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun servicePi(req: Int, action: String): PendingIntent =
        PendingIntent.getService(
            ctx, req, Intent(ctx, BleService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun activityPi(): PendingIntent =
        PendingIntent.getActivity(
            ctx, 43, activityIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun activityIntent() = Intent(ctx, LoneWorkerActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun updateNotification(mode: LoneWorkerLogic.Mode, audible: List<LoneWorkerLogic.Peer>) {
        val resolved = logic.peers.values.filter { !it.active }
        val loud = mode != LoneWorkerLogic.Mode.WATCHING || audible.isNotEmpty()
        val nm = runCatching { ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }.getOrNull() ?: return
        if (!loud && resolved.isEmpty()) {
            if (lastNotifKey != null) { nm.cancel(NOTIF_ID); lastNotifKey = null }
            return
        }
        val key = "$mode|${audible.joinToString(",") { it.bleId }}|${resolved.joinToString(",") { it.bleId }}"
        if (key == lastNotifKey) return
        lastNotifKey = key
        val (title, text, act) = when {
            mode == LoneWorkerLogic.Mode.SOS ->
                Triple("구조 요청 중", "[괜찮음]을 눌러야 해제됩니다", "괜찮음" to servicePi(41, BleService.ACTION_LW_CANCEL))
            mode == LoneWorkerLogic.Mode.CHECKING ->
                Triple("근무 중이신가요?", "응답하지 않으면 같은 사업장에 구조 요청이 나갑니다", "근무 중" to servicePi(40, BleService.ACTION_LW_ACK))
            audible.isNotEmpty() ->
                Triple("구조 요청", audible.joinToString(", ") { displayName(it) }, "확인" to servicePi(42, BleService.ACTION_LW_SILENCE))
            else -> Triple("해제됨", resolved.joinToString(", ") { displayName(it) }, null)
        }
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(activityPi())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
        if (loud) {
            b.setOngoing(true)
            if (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) b.setFullScreenIntent(activityPi(), true)
        } else {
            b.setSilent(true).setTimeoutAfter(RESOLVED_NOTIF_MS)
        }
        act?.let { b.addAction(0, it.first, it.second) }
        runCatching { nm.notify(NOTIF_ID, b.build()) }
            .onFailure { Log.w(TAG, "알림 게시 실패: ${it.message}") }
    }

    private fun openScreen() {
        if (!runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)) return
        runCatching { ctx.startActivity(activityIntent()) }
            .onFailure { Log.w(TAG, "확인 화면 직접 실행 실패: ${it.message}") }
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
            ensureSosRecord(t)
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
