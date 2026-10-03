package com.wf11.safealert.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.wf11.safealert.firebase.SosRemote
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings

/**
 * Android glue for the lone-worker accident / no-motion SOS.
 *
 * Judgment is done by LoneWorkerLogic / MotionAnalyzer (pure); this class wires up sensors (LoneWorkerSensors), power,
 * screen, sound, notifications, server and BLE.
 * BleService calls only this class's entry points. The collision judgment path and the first advertisement byte are untouched.
 *
 * The constructor only stores references (no system service calls). Before start(), every entry point returns immediately.
 * All entry points are called on the main thread (scan callbacks, service commands, the sensor main looper and server
 * callbacks are posted to main).
 */
class LoneWorkerMonitor(
    private val ctx: Context,
    private val advertiseSos: (Boolean, Int, Int) -> Unit,
    setAlarmVolume: (Int) -> Unit
) {

    /** State drawn by the check screen. uiState() returns null when mode is WATCHING and there are no peer rows. */
    data class UiState(
        val mode: LoneWorkerLogic.Mode,
        val responseLeftSec: Int,
        val peers: List<PeerRow>,  // Peer rows as drawn (in draw order)
        val serverStatus: String?, // Own SOS server upload status (null when not in SOS)
        val alarmFault: String? = null, // Notice shown when the alarm volume could not be raised
        val trigger: String = "",       // Check window / SOS reason ("still" no-motion, "fall" fall)
        val responseTotalSec: Int = 0,  // Full response time of this check window (remaining-time ring basis)
        val stillMin: Int = 0,          // Minutes until the no-motion check (reason chip text)
        val responseLeftMs: Long = 0L,  // Remaining response time (ms); the screen redraws on second boundaries
        val stepsAvailable: Boolean = true, // Counted by step sensor (else by walk-like runs), for the step hint text
        val closesByTurn: Boolean = false   // Mounted no-motion window: closes by turn, 3 s shake or "괜찮아요"
    ) {
        val peerActive: Boolean get() = peers.any { it.active }
    }

    companion object {
        private const val REFRESH_MS = 5_000L
        private const val TICK_MIN_MS = 1_000L
        private const val BEACON_NOTE_MS = 2_000L
        private const val SID_MISS_MS = 30_000L
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
    /** Step sensor present but physical activity permission missing (for the main screen warning). */
    val stepPermissionMissing: Boolean get() = started && sensors.stepPermissionMissing
    private var name = ""
    private var roleName = ""
    /** Equipment mode: an equipment selection in BleConstants.categoryName — decided by role only. */
    private val equipment: Boolean get() = roleName == "FORKLIFT" || roleName == "EPJ"
    private var lastMode = LoneWorkerLogic.Mode.WATCHING
    private var lastAudible: Set<String> = emptySet()
    private val watchdog = LoneWorkerWatchdog(ctx, { onWatchdog() }, { onNotificationDismissed() })
    private val notifier = LoneWorkerNotifier(ctx) { watchdog.dismissPi() }
    private var lastTickAt = 0L
    /**
     * Time from which an immediate judgment is checked even while throttled — the previous tick if a
     * passed deadline was waiting for data then, otherwise that tick's nextCheckAt.
     */
    private var dueFrom = Long.MAX_VALUE
    private val wake = LoneWorkerWakeLock(ctx)
    private var loopOn = false
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val beaconNoteAt = HashMap<String, Long>()

    val sosActive: Boolean get() = started && logic.sosActive
    // Rescue-request episode and beacon short ID carried in the advertisement — 0 when not requesting rescue
    val sosEpisode: Int get() = if (sosActive) sync.episode() else 0
    val sosHint: Int get() = if (sosActive) sync.hint() else 0
    private val sidLabels = HashMap<Int, String>()   // Short ID → beacon label cache (found labels only), cleared in stop
    private val sidMissUntil = HashMap<Int, Long>()  // Unlabeled short ID → time it may be looked up again
    private val power = LoneWorkerPower(ctx) { onPowerRaw(it, false) }
    private val sensors = LoneWorkerSensors(ctx, handler, { logic }) { onSensorEvent(it) }
    private val resume = LoneWorkerResume(ctx)
    private var lastRest = LoneWorkerLogic.Rest.NONE
    /**
     * Main screen notice line: monitoring unavailable without a sensor, otherwise why
     * no-motion checking is paused (docked / waiting for movement).
     */
    val banner: String? get() = when {
        !started -> null
        sensors.noSensor -> "사고·무동작 감시 불가 — 가속도 센서 없음 (동료 구조 요청 수신은 계속)"
        else -> logic.rest.banner
    }
    private var rendering = false
    private var renderAgain = false

    private fun now() = SystemClock.elapsedRealtime()

    // ── Start / stop ───────────────────────────────────────────

    fun start(bleId: String, name: String, roleName: String, zoneInside: Boolean) {
        this.name = name
        this.roleName = roleName
        logic.myBleId = bleId
        logic.setEquipment(equipment, now()) // Before startFrom so restore sees the mount; a role change restarts via applyMode
        if (started) return
        started = true
        // Start docked if charging, otherwise waiting for the first clear movement
        val plugged = power.start()
        val t0 = now()
        // Resume from the saved state — restart power hold (RestartHold) if it differs from the current power
        // Without a vibrator there is no siren vibration and no no-motion count pause during it
        logic.canVibrate = VibrationHelper.vibrator(ctx)?.hasVibrator() == true
        logic.startFrom(t0, zoneInside, plugged, resume.load(t0))
        // Revive a saved own SOS before the first render — the siren and advertisement bit1 come back on with the same server key
        sync.restoredTrigger()?.let { logic.restoreSos(it, now()) }
        notifier.createChannel()
        notifier.cancel() // Clear notifications left by a previous process, once
        watchdog.start()
        SirenGenerator.prewarm() // Pre-build siren and check-tone PCM in the background
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
        tickNow() // First judge tick schedules the power confirm check and hold end
    }

    /**
     * Every 10 s: reconnect peer reception, retry own SOS upload, check sensor gaps (failed registration is also retried with backoff).
     */
    private val syncRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            sync.tick()
            sync.heartbeat(DevSettings.lwEnabled, roleName) // Liveness record only; unrelated to judgment
            onPowerRaw(power.plugged(), true) // Sticky battery fixes missed broadcasts (dropped if pending, same 2 s debounce)
            sensors.refreshSteps() // Sync step sensor registration if activity permission changed
            checkStall(now())
            handler.postDelayed(this, SYNC_TICK_MS)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensors.gyroLog(false, false) // Gyro measurement is turned off only here or when the siren ends
        sensors.unregister()
        prefsListener?.let { DevSettings.unregisterOnChange(it) }
        prefsListener = null
        sync.endHeartbeat()
        sync.stopListening()
        alarm.stop(final = true)
        wake.release()
        handler.removeCallbacksAndMessages(null)
        loopOn = false
        watchdog.stop()
        power.stop()
        notifier.cancel()
        lastMode = LoneWorkerLogic.Mode.WATCHING
        lastAudible = emptySet()
        sidLabels.clear()
        sidMissUntil.clear()
        // Keep the server's active record and the saved own SOS — only the user's own "괜찮아요" clears it
        logic = LoneWorkerLogic("") // Clear so stale peer entries don't revive on restart
        if (current === this) current = null
        uiListener?.invoke()
    }

    private fun applySettings() {
        if (!started) return
        val t = now()
        logic.stillMs = DevSettings.lwStillMin * 60_000L
        logic.responseMs = DevSettings.lwResponseMin * 60_000L
        // No sensor at all turns judgment off. If only registration failed, judging
        // continues: checks still open and no signal counts as no movement
        if (DevSettings.lwEnabled) {
            if (!sensors.registered) sensors.resetStall(t)
            sensors.register()
            if (sensors.noSensor) watchdog.disarm() else watchdog.arm()
        } else {
            sensors.unregister()
            watchdog.disarm()
        }
        // Safe-zone fall thresholds (developer setting). Impact gets the same sensor-range correction as the default threshold (2 G sensors)
        logic.zoneFall = MotionAnalyzer.ZoneFall(
            MotionAnalyzer.freeFallMsFor(DevSettings.lwZoneFallCm),
            MotionAnalyzer.impactGFor(sensors.rangeMs2, DevSettings.lwZoneFallG),
            DevSettings.lwZoneFallDeg.toDouble()
        )
        logic.setEnabled(DevSettings.lwEnabled && !sensors.noSensor, t)
        render()
    }

    // ── Sensors ───────────────────────────────────────────────

    /**
     * After a sensor callback: tick and render right away if the state or rest reason changed, otherwise once per second. Once a
     * deadline has passed (even if deep sleep delayed the schedule) and sensor data covers it,
     * judge right here regardless of throttling — before later data in the same batch.
     * Each callback reports its end to the judge-order unit — input after a deadline
     * blocked only by a pending power change goes after the judgment.
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
     * Single judge tick. If a passed deadline is waiting for sensor data, requests a flush, and schedules one more tick at the
     * next deadline (or the LATE_MS backstop if none).
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

    /** One judge tick and render at a scheduled deadline or power confirm time. */
    private fun tickNow() {
        if (!started) return
        val t = now()
        lastTickAt = t
        tick(t)
        render()
    }

    private val deadlineRunnable = Runnable { tickNow() }

    /**
     * Raw power value: fed into the judge logic's debounce; if applied or the
     * pending state changed, judge and render through one path (tickNow).
     */
    private fun onPowerRaw(on: Boolean, sticky: Boolean) {
        if (!started) return
        val changed = logic.powerRaw(on, now(), sticky)
        if (changed) tickNow()
    }

    /**
     * Sensor signal gap check. While the signal is lost, it counts as no movement and no-motion time keeps accruing.
     * Re-registration leaves the no-motion start time and the checking state alone (so an open check is not cancelled).
     * After about 1 min without response (second re-registration check), the movement wait ends.
     */
    private fun checkStall(t: Long) {
        if (!started) return
        // Keep re-registering with the same backoff while registration has failed and there is no sensor
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

    /** Notification swiped away: re-post it if it is a major alert. */
    private fun onNotificationDismissed() {
        if (!started) return
        notifier.forget()
        render()
    }

    // ── BleService entry points ───────────────────────────────

    fun onZoneChanged(inside: Boolean) {
        if (!started) return
        val t = now()
        logic.onZone(inside, t)
        tick(t)
        render()
    }

    /**
     * BleService turn polling (TX polling, default 0.5 s; only when not going
     * straight). Forwarded only in equipment mode — walker mode is unaffected.
     */
    fun onTurn() {
        if (!started || !equipment) return
        val t = now()
        logic.onTurn(t)
        tick(t)
        render()
    }

    /** Called on every scan, so kept light: redraws only when peer entries change. */
    fun onPeerBle(bleId: String, sos: Boolean, episode: Int = 0, hint: Int = 0) {
        if (!started) return
        val before = peerSig()
        val t = now()
        val label = if (sos && hint != 0) sidLabel(hint, t) else ""
        logic.onPeerBle(bleId, sos, t, episode, label)
        if (peerSig() != before) render()
    }

    /** Caches found labels; a short ID that was not found is not looked up again for 30 s. */
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
        // Only registered beacons carry a short ID (unregistered = 0)
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

    /** Mutes only the entries in targets (entry id -> episode ID). */
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

    // ── Screen state ──────────────────────────────────────────

    fun uiState(): UiState? {
        if (!started) return null
        val t = now()
        val shown = logic.peers.filter { !it.silenced }  // Resolved entries stay visible until "닫기"
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
            logic.stepsAvailable,
            logic.closesByTurn
        )
    }

    // ── Rendering: state changes, sound, notifications, screen ───

    private val keepText: String get() = logic.rest.keepText

    /**
     * Quiet notice notification. Priority: no sensor, sensor registration failed, sensor signal lost, clear not sent, no step permission.
     */
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

    /** SosLedger.begin/resolve call render synchronously via onChange, so a re-entrant call only flags one more pass. */
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

        // Sound priority: own SOS > check window (check tone, no vibration) > peer siren
        val vibrates = logic.alarmVibrates
        val p = LoneWorkerAlarm.Pattern.of(mode, audible.isNotEmpty())
        if (p != null) alarm.play(p, vibrates) else alarm.stop()
        sensors.gyroLog(audible.isNotEmpty(), vibrates)

        notifier.update(mode, logic.closesByTurn, audible, logic.peers.filter { !it.active && !it.silenced }, notice(), showScreen)
        if (showScreen) notifier.openScreen()
        updateWakeLock(false)
        scheduleLoop()
        resume.save(logic.snapshot(t), t)
        uiListener?.invoke()
    }

    // ── 5 s refresh / wake lock ───────────────────────────────

    private fun needLoop() = logic.loopNeeded(sensors.needsWake)

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
        // Also hold the wake lock while a passed deadline awaits judgment (sensor data, or a power change
        // pending since before it) to guarantee the LATE_MS backstop and power confirm check
        // Also hold it during an equipment mount with monitoring on (charging, so no
        // battery cost) so turn polling and ImuFusion run with the screen off
        val need = logic.wakeNeeded(sensors.needsWake, now())
        wake.hold(need, renew)
    }
}
