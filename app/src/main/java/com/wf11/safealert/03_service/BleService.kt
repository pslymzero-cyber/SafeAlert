package com.wf11.safealert.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.wf11.safealert.ble.BleAdvertiser
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.ble.BleScanner
import com.wf11.safealert.ble.BleScanCallback
import com.wf11.safealert.ble.KalmanFilter
import com.wf11.safealert.ble.MedianFilter
import com.wf11.safealert.ble.RssiPreFilter
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.ui.MainActivity
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.ImuFusion
import com.wf11.safealert.utils.OverlayManager
import com.wf11.safealert.utils.UwbCalibrator
import com.wf11.safealert.utils.UwbRanger
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BleService : LifecycleService() {

    companion object {
        const val TAG                  = "BleService"
        const val ACTION_START_DEVICE  = "ACTION_START_DEVICE"
        const val ACTION_START_WALKER  = "ACTION_START_WALKER"
        const val ACTION_STOP          = "ACTION_STOP"
        const val ACTION_TEST_START    = "ACTION_TEST_START"
        const val ACTION_TEST_STOP     = "ACTION_TEST_STOP"
        const val ACTION_MUTE_TEMP     = "ACTION_MUTE_TEMP"
        const val ACTION_UNMUTE        = "ACTION_UNMUTE"
        const val ACTION_MUTE_DEVICE   = "ACTION_MUTE_DEVICE"   // sidebar row tap → ACK-mute that device (30 s)
        const val ACTION_MUTE_ALL      = "ACTION_MUTE_ALL"     // sidebar dragged fully closed → ACK-mute all current hazards
        const val ACTION_TEST_STATE    = "ACTION_TEST_STATE"   // dev manual STATE injection (TX test of reverse/loading reserved bits)
        const val ACTION_REAPPLY_UWB   = "ACTION_REAPPLY_UWB"  // nudge: re-evaluate the UWB session right after permission grant/force toggle
        // Lone-worker check / SOS notification buttons
        const val ACTION_LW_ACK        = "ACTION_LW_ACK"
        const val ACTION_LW_SILENCE    = "ACTION_LW_SILENCE"
        // Persistent notification → straight to MainActivity's role-switch confirmation dialog. This action is received
        //   by the Activity, not the service. A switch is a stop→restart, so handling it in the service alone would split
        //   prefs and UI state. Reusing the existing confirmSwitchRole() is the only safe path.
        const val ACTION_OPEN_SWITCH_ROLE = "ACTION_OPEN_SWITCH_ROLE"
        const val EXTRA_ID             = "extra_id"
        const val EXTRA_CATEGORY       = "extra_category"       // sender role Category (CAT_*)
        const val EXTRA_PSTATE         = "extra_pstate"         // STATE value for ACTION_TEST_STATE (PSTATE_*)
        const val EXTRA_ALERT_LEVEL    = "extra_alert_level"
        const val EXTRA_DISPLAY_NAME   = "extra_display_name"
        const val EXTRA_RSSI           = "extra_rssi"
        const val EXTRA_STATUS         = "extra_status"
        const val EXTRA_DEVICE_LIST    = "extra_device_list"    // serialized detected-device list (max 10)
        const val EXTRA_DEVICE_COUNT   = "extra_device_count"   // number of listed devices (0 = none detected)
        const val EXTRA_LOCAL_STATE    = "extra_local_state"    // serialized state of my device (Local)
        const val BROADCAST_ALERT      = "com.wf11.safealert.ALERT"
        const val BROADCAST_DETECTED   = "com.wf11.safealert.DETECTED"
        const val BROADCAST_BLE_STATUS = "com.wf11.safealert.BLE_STATUS"
        const val BROADCAST_LOCAL_STATE = "com.wf11.safealert.LOCAL_STATE"   // broadcast of my device (Local) state
        private const val CHANNEL_ID   = "safealert_channel"
        private const val NOTIF_ID     = 1001

        @Volatile var lastStatus: String   = ""
        @Volatile var bleScanCount: Int    = 0
        @Volatile var safeAlertFound: Int  = 0
        @Volatile var isRunning: Boolean   = false
        @Volatile var isMutedPublic: Boolean = false
        // Fallback for missed broadcasts — the snapshot with the same serialization as broadcastDeviceList is also
        //   exposed statically. MainActivity polls it every 800ms to fill the list.
        //   (Even if the broadcast is lost to RECEIVER_NOT_EXPORTED / implicit-delivery failure, "주변 감지 기기 N건" must
        //    always show — structurally prevents an empty list while the overlay is up.)
        //   Serialization: record separator U+001E, field separator U+001F, field order level/rssi/name.
        @Volatile var detectedSnapshot: String = ""   // "levelrssiname" records, separated by 
        @Volatile var detectedCount: Int       = 0    // number of devices currently alerting (alertState)
        // My device (Local) state snapshot — a single source fully separate from the receive (Target) path.
        //   Serialized field order = category / state / turnDir (field separator U+001F).
        //   Updated only from my own TX state (myCategory + bleAdvertiser TX) — a peer payload can never touch it.
        @Volatile var localSnapshot: String    = ""
    }

    private var bleAdvertiser: BleAdvertiser? = null
    private var bleScanner: BleScanner? = null
    private var uwbRanger: UwbRanger? = null
    private var myId   = ""
    private var myMode = ""
    // My role (Category) — packed into advertising payload bits[1:0]. Defaults to walker.
    private var myCategory = BleConstants.CAT_WALKER
    private var testRunnable: Runnable? = null
    private val testHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val MUTE_DURATION_MS = 10_000L
    // Duration for ACK mutes only — longer than the temporary mute (volume key / notification tap, MUTE_DURATION_MS).
    //   Used by a sidebar row tap (muteDevice) and a full drag (muteAllHazards).
    private val ACK_MUTE_DURATION_MS = 30_000L
    // How long a device must stay at the same alert level (WARNING/DANGER) before it is auto-muted.
    //   Hard-coded on purpose — no options UI for it (don't expose it as a setting).
    private val DWELL_MUTE_MS = 5_000L
    // Zone beacon (safe area) state machine constants.
    //   Entry = ZONE_MIN_SAMPLES consecutive samples at or above enterRssi,
    //   exit  = ZONE_EXIT_SAMPLES consecutive samples below enterRssi−ZONE_EXIT_HYST_DB (the hysteresis deadband
    //           prevents boundary flapping) or signal lost for longer than ZONE_LOST_GRACE_MS,
    //   entry discarded after ZONE_SIGNAL_STALE_MS (prevents map leaks). Zones are judged on raw RSSI (no gain applied).
    // ZONE_MIN_SAMPLES = 1: safe mode must hold while the zone beacon's adverts are being received. Requiring several
    //   consecutive samples broke continuity on sites with slow advert intervals or scan gaps, so the zone was never
    //   entered. Cost: a single spike enters; it is undone once ZONE_EXIT_SAMPLES weak samples follow.
    private val ZONE_MIN_SAMPLES     = 1
    private val ZONE_EXIT_HYST_DB    = 5
    // Exit also requires consecutive samples. With a single-sample exit, one RSSI dip at the boundary released
    //   suppression immediately and it came back on the next sample (the safe zone dropped out intermittently).
    //   A real exit keeps the signal low, so requiring consecutive samples separates noise from a real exit.
    //   Why 3: dwelling at the boundary (-80~-78dBm received), 2 still leaves 5~21 false exits per hour; 3 leaves 0.6~5.
    //   The cost is confirming an exit 2 advert intervals (~10 s) later; on site 10 s is shorter than getting back to
    //   work or boarding equipment, so lingering suppression is not dangerous.
    private val ZONE_EXIT_SAMPLES    = 3
    // Signal-loss grace. It must exceed the beacon advert interval: with 3 s against a 5 s interval, polling cleared
    //   inside and reset zoneSampleMap to 0 between samples, so the zone never entered or flapped even while samples kept
    //   coming (only the first signal seemed to register). A normal exit is handled by the weak-signal branch; this grace
    //   applies only to total signal loss — cost: in a blackout, suppression lifts up to 10 s late.
    private val ZONE_LOST_GRACE_MS   = 10_000L
    // Entry disposal only prevents map leaks — the exit decision was already made by GRACE above. A short TTL (4 s)
    // wiped the counter before entry samples could accumulate (slow-advertising beacons, 45 s scan-restart gaps), so the
    // zone was never entered.
    private val ZONE_SIGNAL_STALE_MS = 30_000L
    private val muteHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // Dedicated handler for releasing forceAlarmVolume's ignoringVolumeChange (300ms).
    //   Don't share muteHandler: muteTemporarily()'s removeCallbacksAndMessages(null) would also drop the release
    //   callback, leaving ignoringVolumeChange=true stuck (volume-button mute permanently disabled).
    private val volumeGuardHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // Lone-worker no-motion/fall SOS — the constructor only stores references; every entry point is a no-op before start()
    private val loneWorker by lazy {
        LoneWorkerMonitor(this, advertiseSos = { sos, ep, hint -> bleAdvertiser?.updateSos(sos, ep, hint) }, setAlarmVolume = { setAlarmVolumeGuarded(it) })
    }
    // Set when started by a background restore (STICKY, boot, update, notification
    // button) — for the Android 11 location "Allow all the time" check
    private var bgStarted = false
    private var fgsApplied = 0   // foreground service type applied (for re-specifying the type)

    @Volatile private var activeSoundLevel = BleConstants.LEVEL_SAFE

    // Inside a safe zone, pin the advertised risk level to SAFE — inside the zone we don't even send alerts.
    //   Every caller only feeds bleAdvertiser.updateRisk(), so judgment and display are unaffected.
    //   Older peers that don't know the IN_ZONE bit still understand the SAFE level, so it stays backward compatible.
    //   Advertising itself continues — I don't vanish from screens outside the zone (still visible, shown as harmless).
    private fun getCurrentMaxLevel() =
        if (myZoneInside) BleConstants.LEVEL_SAFE
        else alertState.values.maxOfOrNull { it.first } ?: BleConstants.LEVEL_SAFE

    // 'Audible' max level — used only to decide sound ownership, excluding dwell-muted and Acknowledge-muted devices.
    //   Keeps a muted device from occupying the canonical globalMax / remaining-device resync and blocking audible
    //   alerts of new or remaining devices. Risk re-advertising (updateRisk), the list and the overlay still use the raw
    //   level (per spec: muting suppresses only sound and vibration; the risk state is unchanged).
    private fun getAudibleMaxLevel() =
        // While I'm touching a zone beacon the audible max level is SAFE — inside a zone, sound and vibration are
        //   suppressed for all devices. resyncSoundToRemaining and the canonical alert (globalMax) read this value, so
        //   entering the zone actively stops the sound, and leaving lifts the suppression at once.
        if (myZoneInside) BleConstants.LEVEL_SAFE
        else alertState.entries.filter { !isDwellMuted(it.key, it.value.first) && !isDeviceMuted(it.key) }
            .maxOfOrNull { it.value.first } ?: BleConstants.LEVEL_SAFE

    // UWB↔RSSI calibration learning/lookup key — role-pair segment (implementation owned by CalibrationEngine).
    //   If the peer category is unknown (no scan cache), assume walker, the most conservative choice.
    private fun uwbPairKeyFor(deviceId: String): String =
        CalibrationEngine.uwbPairKeyFor(myCategory, deviceCategoryMap[deviceId] ?: BleConstants.CAT_WALKER)

    /**
     * Partial-departure sound downgrade — after only some devices are removed (alertState not empty), immediately
     *   match the playing sound to the remaining devices' actual max level. If the higher (DANGER) device that owned the
     *   siren left and the rest are lower, the canonical/fail-quiet fix would only run on a remaining device's next frame,
     *   leaving the higher siren for seconds or effectively forever.
     *   Acts only when lowering the sound (remainingMax < activeSoundLevel) → no action if a remaining device is at an
     *   equal or higher level = never drops an alert. The teardown counterpart of the fail-quiet demotion fix in processAlert.
     */
    private fun resyncSoundToRemaining() {
        val remainingMax = getAudibleMaxLevel()   // excludes dwell-muted devices — drops to silence if only muted ones remain
        if (remainingMax >= activeSoundLevel) return          // a remaining device is at the same or higher level — keep the siren
        AlertSoundPlayer.stopSound()                          // stop the departed higher device's stale siren now
        activeSoundLevel = remainingMax
        if (remainingMax == BleConstants.LEVEL_WARNING) {
            if (DevSettings.vibrationEnabled) VibrationHelper.vibrateWarning(this)
            if (DevSettings.soundEnabled)     AlertSoundPlayer.playWarning(this)
        } else if (remainingMax <= BleConstants.LEVEL_SAFE) {
            VibrationHelper.stopVibration(this)
        }
        Log.d(TAG, "[v1.1.37 ②] 부분 이탈 사운드 하향 정합: activeSoundLevel→$remainingMax (남은 최대레벨)")
    }

    @Volatile private var ignoringVolumeChange = false

    // ── RssiPreFilter: asymmetric P-control EMA pre-filter ──
    // First-order LPF ahead of the Kalman filter. S_t = S_{t-1} + α·(R_t − S_{t-1}).
    //   Asymmetric α (defaults; live values from DevSettings via applyEmaAlphas): stronger (approach)=0.3 fast /
    //   weaker (noise)=0.12 slow / D-Boost (prevVel>+2.0)=0.4 opens the latch.
    private val rssiPreFilter = RssiPreFilter()

    // ── MedianFilter: non-linear rank-order pre-filter (impulse removal, ahead of the EMA) ──
    // Structurally removes single multipath reflections off steel racks (one-frame positive spikes) before the linear
    // stages (EMA→Kalman). Window N=3 → group delay ~1 frame. Basis for the gate's third leg (medianValue) and the warm-up guard.
    private val medianFilter = MedianFilter()

    // ── Post-filter P-EMA: asymmetric smoothing of the Kalman output (kfRssi), distance (P) term only ──
    // P-D split: the D term (kfVel) lives on phase lead, so it bypasses the post-filter (feeds the Time-Gate directly);
    // the P term (distance) may be smoothed.
    // Rise α=0.4 (tracks approach fast) / fall α=0.15 (softens lingering on departure); no D-Boost (the Kalman already
    // accounts for velocity).
    private val pEmaFilter = RssiPreFilter(alphaRise = 0.4, alphaFall = 0.15, dBoostEnabled = false)

    // ── Always-On policy ──────────────────────────────────────
    // No PendingIntent standby mode: regardless of nearby devices (including the SAFE state), the service never stops
    // itself (stopAll()) until the user presses "중지"; it stays alive and keeps scanning (no wake-up delay on site).

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ignoringVolumeChange || isOwnAlarmVolume(intent)) return
            muteTemporarily("볼륨 버튼")
        }
    }

    // Alarm volume this service last set. A broadcast reporting the alarm stream at exactly this value is its own change
    //   arriving after the 300 ms guard, not a press (a press moves the volume off it); taken as a press it would mute
    //   whatever is alerting, a device that just came in during a mute included.
    @Volatile private var appliedAlarmVolume = -1

    private fun isOwnAlarmVolume(intent: Intent) =
        intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_ALARM &&
            intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -2) == appliedAlarmVolume

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "화면 꺼짐 → BLE 절전 스캔 모드 전환")
                    bleScanner?.notifyScreenOff()
                    sendStatusBroadcast("화면 꺼짐 — BLE 절전 유지 중")
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "화면 켜짐 → BLE 적응형 스캔 복귀")
                    bleScanner?.notifyScreenOn()
                }
            }
        }
    }

    // Restart after Bluetooth comes back (1 s settle). At most one is pending: STATE_OFF, a newer STATE_ON and stopAll
    //   cancel it, so a stale restart neither runs with Bluetooth off nor revives a stopped service.
    private val btRestartHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val btRestart = Runnable {
        val btOn = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
        if (!isRunning || !btOn) return@Runnable
        checkSystemHealth()   // clears the Bluetooth fault first, so the losses below don't post it as their status
        stopBle()
        applyMode()
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "블루투스 켜짐 → BLE 재시작")
                    sendStatusBroadcast("블루투스 켜짐 → BLE 재시작")
                    btRestartHandler.removeCallbacks(btRestart)
                    btRestartHandler.postDelayed(btRestart, 1000)
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.d(TAG, "블루투스 꺼짐")
                    btRestartHandler.removeCallbacks(btRestart)
                    sendStatusBroadcast("블루투스 꺼짐")
                    // No radio, but the scanner stays: its loss sweep still retires devices the normal way (BLE timeout,
                    //   deferred while UWB ranging continues), so no alert outlives its device and a live UWB session keeps
                    //   protecting both phones while it lasts. STATE_ON replaces the scanner.
                    bleAdvertiser?.stopAdvertising(); bleAdvertiser = null
                    bleScanner?.suspendRadio()
                    // A broadcast alone only reaches someone with the screen open; BT turning off in a pocket would leave the wearer
                    //   unprotected with no notice → surface it in the persistent notification.
                    checkSystemHealth()
                }
            }
        }
    }

    /**
     * Watches location on/off.
     * On API 30 and below, BLE scans return no results while location services are off (the scan 'succeeds' but finds nothing).
     * API 31+ declares neverForLocation on BLUETOOTH_SCAN (AndroidManifest:7-8), so it doesn't apply.
     */
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            checkSystemHealth()
        }
    }



    private val SCAN_HEALTH_CHECK_MS   = 15_000L

    // ── Surfacing silent failures ────────────────────────────────
    //   A persistent notification fixed at "송신 ON · 수신 ON" would keep showing OK while Bluetooth is off,
    //   permissions are revoked or advertising fails — the wearer couldn't know protection had stopped.
    //   Each subsystem's fault reason is collected below and updates the same notification ID. null = OK.
    @Volatile private var systemFault:  String? = null   // BT, permissions, location (checkSystemHealth)
    @Volatile private var txFault:      String? = null   // BleAdvertiser.onTxFault
    @Volatile private var soundFault:   String? = null   // AlertSoundPlayer.onSoundFault
    @Volatile private var overlayFault: String? = null   // OverlayManager.onOverlayFault
    // [Critical] setStreamVolume throws no exception when blocked by Do Not Disturb or device policy (silently ignored).
    //   The catch never runs, so only a read-back detects it. Missed, the alarm plays at volume 0 — the only fully silent
    //   path that the three independent channels (sound/vibration/overlay) can't catch.
    //   systemFault (periodically overwritten with null by checkSystemHealth) and soundFault (overwritten by the
    //   AlertSoundPlayer callback) can't be reused, hence a dedicated slot.
    @Volatile private var volumeFault:  String? = null   // forceAlarmVolume read-back check
    @Volatile private var faultBeeped   = false          // beep only once when entering a fault

    @Volatile private var lastScanResultMs = 0L
    private val healthCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ── IMU-driven rest/combat flag ───────────────────────
    // Stationary for 5 s with no hazard signal → rest flag; movement → back to combat at once. Scanning is not
    // downgraded either way (rest scan mode = active scan mode).
    private val STATIONARY_ECO_DELAY_MS = 5_000L
    // Advertising sleep grace for equipment (DEVICE = forklift, EPJ) — time when no-contact began (0 = grace not started).
    //   Updated by evaluateAdvertiserPower; reset to 0 on proximity, alert or movement.
    private val DEVICE_SLEEP_GRACE_MS = 60_000L
    @Volatile private var advIdleSinceMs = 0L
    private val ecoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // Last time an approach (kfVel>0) was observed (ms). A persistent signal so a device that was closing just before we
    //   stopped isn't missed by entering power saving.
    //   Updated every frame by processAlert; isDangerPresent() checks it against
    //   SIGNAL_STALE_MS freshness. (@Volatile Long, like lastScanResultMs)
    @Volatile private var lastApproachAtMs = 0L
    @Volatile private var lastNearSampleMs = 0L   // last RSSI sample at or above WAKE_RSSI_DBM (scan batching promotion)
    private val ecoDowngradeRunnable = Runnable {
        // Set the rest flag only if still stationary after 5 s with no danger signal at all (stay in combat on proximity/alert/approach)
        if (ImuFusion.isStationary && !isDangerPresent()) {
            bleScanner?.setEcoMode(true)
            // No scan-only eco: scanning stays LOW_LATENCY (prevents a constant-speed misjudgment from delaying the first warning).
            //   Power saving is decided independently by advertising (TX, evaluateAdvertiserPower) and batching (screen off).
            Log.d(TAG, "정지 5초 경과 + 위험신호 없음 → 휴식 플래그(스캔은 LOW_LATENCY 유지)")
        }
    }









    // ── 1-second average buffer ──────────────────────────────────────────────
    private val oneSecBuffer = mutableMapOf<String, ArrayDeque<Pair<Long, Int>>>()

    private fun oneSecAvgRssi(deviceId: String, rssi: Int): Int {
        val now = System.currentTimeMillis()
        val buf = oneSecBuffer.getOrPut(deviceId) { ArrayDeque() }
        buf.addLast(Pair(now, rssi))
        while (buf.isNotEmpty() && now - buf.first().first > 1000L) buf.removeFirst()
        return if (buf.isEmpty()) rssi else buf.map { it.second }.average().toInt()
    }

    // Returns the max RSSI (closest signal) received in the last windowMs (default 0.5 s).
    //   Reuses oneSecBuffer, which oneSecAvgRssi() fills with (time, rssi) every frame.
    //   Only for the TTC pre-alert peak gate — allowed only when the peak of the last 0.5 s is within the warning distance
    //   (effWarning), so a distant device can't leak an urgent alert from the Kalman velocity estimate alone.
    //   (null if the buffer is empty)
    private fun recentPeakRssi(deviceId: String, windowMs: Long = 500L): Int? {
        val buf = oneSecBuffer[deviceId] ?: return null
        val now = System.currentTimeMillis()
        return buf.filter { now - it.first <= windowMs }.maxOfOrNull { it.second }
    }




    // ── RSSI dynamic sleep/wake (TX power management) ─────────────────
    //   No fresh signal ≥ WAKE_RSSI_DBM, no alert and not moving (keepAdvertiseWhileMoving) → advertising sleeps (LOW_POWER
    //   heartbeat; equipment only after DEVICE_SLEEP_GRACE_MS) — see evaluateAdvertiserPower.
    //   Any RSSI ≥ WAKE_RSSI_DBM (default -95) → wake at once, 0ms (continuous advertising resumes + LocalState force-sent).
    //   Scanning (RX) never stops, so approach detection and wake-up are always alive.
    // Decision parameters: WAKE/STALE — read live from DevSettings (defaults -95/6000L).
    //   SLEEP_RSSI_DBM (-90) is unused in code (a documented boundary) — kept as a constant, not exposed in settings.
    private val WAKE_RSSI_DBM: Int get() = DevSettings.wakeRssiDbm  // wake at once at or above this (close)
    private val SLEEP_RSSI_DBM   = -90          // doc-only, unused: real sleep = no fresh signal ≥ WAKE_RSSI_DBM (evaluateAdvertiserPower)
    private val SIGNAL_STALE_MS: Long get() = DevSettings.signalStaleMs  // RSSI samples older than this count as 'no signal'
    private val ADV_POWER_EVAL_MS = 2_500L      // TX power evaluation interval
    // deviceId → (latest RSSI, record time ms). Single source for wake decisions and sleep evaluation.
    private val wakeRssiMap = mutableMapOf<String, Pair<Int, Long>>()
    private val advPowerHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Listener that propagates dev_settings changes live — held by a strong reference (field).
    //   SharedPreferences keeps listeners as WeakReferences, so as a local variable it would be GC'd and disconnected.
    private val devPrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> applyLiveSettings(key) }


    // Per-device, per-level dwell auto-mute — after DWELL_MUTE_MS continuously at the same level (WARNING/DANGER),
    //   suppress only that device's sound and vibration at that level (display, list, overlay, Firebase, judgment and
    //   re-advertising continue).
    //   Release (full reset) = leaving the alert zone (SAFE cleanup, confirmed departure, demotion to untracked, device
    //   lost, service stop) → re-entry = normal alert + a fresh 5 s.
    //   W→D escalation = DANGER mute released (escalation alerts are always audible) + timer restarts. A 5 s DANGER dwell
    //   mutes DANGER and WARNING together (on D→W retreat WARNING stays quiet too — by spec). Tracked only while
    //   registered in alertState (alerting).
    private val dwellLevelMap       = mutableMapOf<String, Int>()             // deviceId → level being tracked
    private val dwellSinceMap       = mutableMapOf<String, Long>()            // deviceId → time that level was entered (ms)
    private val dwellMutedLevelsMap = mutableMapOf<String, MutableSet<Int>>() // deviceId → set of muted levels

    // Zone beacon (safe area) state — zone beacons never enter detectedDevices or judgment; they flow only through
    //   the separate BleScanner.onZoneBeaconSignal path. beaconKey="ZONE_"+uuid8/MAC.
    //   myZoneInside = I'm inside a zone (inside at least one) → suppress my own sound/vibration + IN_ZONE advert bit.
    //   peerInZoneMap = cache of received peer IN_ZONE declarations → that device is judged harmless (SAFE) (suppression only).
    //   The 4 maps below are keyed by beaconKey, not deviceId, so they are deliberately not registered with
    //   DeviceStateRegistry. Cleanup is double-covered by the reevaluateZones() TTL hard removal + manual clear in stopAll().
    private val zoneSampleMap    = mutableMapOf<String, Int>()     // beaconKey → consecutive sample count (+ entry / − exit)
    private val zoneEnterRssiMap = mutableMapOf<String, Int>()     // beaconKey → profile entry threshold (dBm)
    private val zoneLastSeenMap  = mutableMapOf<String, Long>()    // beaconKey → last received time (ms)
    private val zoneInsideMap    = mutableMapOf<String, Boolean>() // beaconKey → currently inside the zone
    @Volatile private var myZoneInside = false









    // Display TTL for detected devices not in alertState (below the alert range or held by the gate): they are shown as a
    //    detected (SAFE) row in the bottom list — no invisible window before the alert fires. The overlay
    //    (hazardListForOverlay) excludes them to stay alert-only. Removed from the list once the TTL (aligned with the
    //    scanner timeout) passes.
    private val PENDING_DISPLAY_TTL_MS = 6000L



    // ── UWB/RSSI conditional judgment split (Case A/B) ─────────────────────────────
    //   Case A (UWB↔UWB): a pair with live UWB measurements is judged by UWB distance only — RSSI never takes part.
    //   Case B (UWB↔Non-UWB): a pair without UWB measurements uses RSSI judgment.
    //   Case A holds on measurement freshness alone (last sample ≤ UWB_MEAS_FRESH_MS; kill switch
    //   uwbExclusiveJudgeEnabled). Fresh → judge by UWB distance; otherwise RSSI judges from that moment — no
    //   judgment gap either way. When measurements resume, UWB takes over again from the first sample.
    //   Ranging sessions are UwbRanger's internal business (connect/drop via scan responses and backoff); judgment
    //   never touches them — tearing a session down after 1 s without samples (onDeviceLost) made marginal pairs
    //   flap endlessly (teardown 1s → rejoin 250ms), and a controller teardown collapsed every session.
    //   Session start is RSSI-gated in UwbRanger: only peers stronger than UWB_START_RSSI_GATE_DBM (-80 dBm) get a
    //   UWB pairing; weaker peers stay on RSSI (Case B). The 0x9ABC advert alone is not authority — it only declares
    //   the stack is running, not a measurement; adverts are kept for discovery and diagnostics only.
    private val uwbDist = UwbDistanceManager { uwbRanger }

    /** AlertStateMachine owns the judgment path. Side effects and queries come back through the Effects below. */
    private val asm: AlertStateMachine = AlertStateMachine(object : AlertStateMachine.Effects {
        override val myId get() = this@BleService.myId
        override val myMode get() = this@BleService.myMode
        override val myCategory get() = this@BleService.myCategory
        override val myZoneInside get() = this@BleService.myZoneInside
        override var activeSoundLevel: Int
            get() = this@BleService.activeSoundLevel
            set(v) { this@BleService.activeSoundLevel = v }
        override var lastApproachAtMs: Long
            get() = this@BleService.lastApproachAtMs
            set(v) { this@BleService.lastApproachAtMs = v }
        override val bleScanner get() = this@BleService.bleScanner
        override val uwbRanger get() = this@BleService.uwbRanger
        override val rssiPreFilter get() = this@BleService.rssiPreFilter
        override val medianFilter get() = this@BleService.medianFilter
        override val pEmaFilter get() = this@BleService.pEmaFilter
        override fun getAudibleMaxLevel() = this@BleService.getAudibleMaxLevel()
        override fun uwbPairKeyFor(deviceId: String) = this@BleService.uwbPairKeyFor(deviceId)
        override fun resyncSoundToRemaining() = this@BleService.resyncSoundToRemaining()
        override fun forceAlarmVolume() = this@BleService.forceAlarmVolume()
        override fun isDeviceMuted(deviceId: String) = this@BleService.isDeviceMuted(deviceId)
        override fun updateDwellMute(deviceId: String, level: Int, now: Long, quiet: Boolean) =
            this@BleService.updateDwellMute(deviceId, level, now, quiet)
        override fun isDwellMuted(deviceId: String, level: Int) = this@BleService.isDwellMuted(deviceId, level)
        override fun clearDwellMute(deviceId: String) = this@BleService.clearDwellMute(deviceId)
        override fun updateFloatingOverlay() = this@BleService.updateFloatingOverlay()
        override fun collapseOverlay() = this@BleService.collapseOverlay()
        override fun sendStatusBroadcast(status: String) = this@BleService.sendStatusBroadcast(status)
        override fun extractDisplayName(deviceId: String) = this@BleService.extractDisplayName(deviceId)
        override fun makeStateLabel(name: String, category: Int, state: Int) = this@BleService.makeStateLabel(name, category, state)
        override fun sendAlertBroadcast(deviceId: String, level: Int) = this@BleService.sendAlertBroadcast(deviceId, level)
        override fun broadcastDeviceList() = this@BleService.broadcastDeviceList()
        override fun oneSecAvgRssi(deviceId: String, rssi: Int) = this@BleService.oneSecAvgRssi(deviceId, rssi)
        override fun recentPeakRssi(deviceId: String, windowMs: Long) = this@BleService.recentPeakRssi(deviceId, windowMs)
        override fun vibrateDanger() = VibrationHelper.vibrateDanger(this@BleService)
        override fun vibrateWarning() = VibrationHelper.vibrateWarning(this@BleService)
        override fun vibrateRapidApproach() = VibrationHelper.vibrateRapidApproach(this@BleService)
        override fun stopVibration() = VibrationHelper.stopVibration(this@BleService)
        override fun playDanger() = AlertSoundPlayer.playDanger(this@BleService)
        override fun playWarning() = AlertSoundPlayer.playWarning(this@BleService)
    }, uwbDist)

    // Judgment state is owned by AlertStateMachine - the fields below alias the same instances (for reflection tests and remaining call sites)
    private val alertState = asm.alertState
    private val kalmanFilters = asm.kalmanFilters
    private val filterPreserveMap = asm.filterPreserveMap
    private val timeGateWaiveSet = asm.timeGateWaiveSet
    private val dangerContactStreakMap = asm.dangerContactStreakMap
    private val warningContactStreakMap = asm.warningContactStreakMap
    private val trackingStateMap = asm.trackingStateMap
    private val recedingStartMap = asm.recedingStartMap
    private val deviceRssiMap = asm.deviceRssiMap
    private val mutedDevices = asm.mutedDevices
    private val peerInZoneMap = asm.peerInZoneMap
    private val suddenLabelMap = asm.suddenLabelMap
    private val deviceCategoryMap = asm.deviceCategoryMap
    private val deviceStateMap = asm.deviceStateMap
    private val deviceTurnMap = asm.deviceTurnMap
    private val reverseRssiHist = asm.reverseRssiHist
    private val reversePrepUntil = asm.reversePrepUntil
    private val pendingDisplayMap = asm.pendingDisplayMap
    private val approachStreakStartMap = asm.approachStreakStartMap
    private val fastApproachStreakMap = asm.fastApproachStreakMap
    private val KF_VEL_SEED_TTL_MS get() = asm.KF_VEL_SEED_TTL_MS

    // The 3 below are aliases — owned by UwbDistanceManager, pointing at the same instances (call sites unchanged +
    //   keeps the backing fields that tests read via ReflectionHelpers.getField).
    private val peerUwbSeenMap   = uwbDist.peerUwbSeenMap
    private val uwbSampleAtMsMap = uwbDist.uwbSampleAtMsMap
    private val uwbSafeStreakMap = uwbDist.uwbSafeStreakMap

    // TX polling — periodically pushes STATE (stopped/moving) + Turn (left/right/straight) to the advertiser.
    //   (The SPEED_PUSH_* constant names are kept and mean the polling interval.)
    //   Combined with the advertiser's internal 1 s throttle and its ignoring of unchanged values, actual re-advertising is rare.
    private val SPEED_PUSH_INTERVAL_MS: Long get() = DevSettings.speedPushIntervalMs  // Decision parameter: default 500L
    private val speedPushHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val speedPushRunnable = object : Runnable {
        override fun run() {
            // STATE self-healing sync — IMU motion notifications arrive only at transitions, so one lost notification can
            //   leave a stale state until the next transition. Push the latest motionState on every poll (a no-op inside the
            //   advertiser if unchanged → no re-advertising cost).
            //   Manually injected special states (reverse/loading — ACTION_TEST_STATE) are not overwritten by the auto sync.
            //   Called before updateTurn so a STATE change gets the shared 1 s throttle slot first.
            val tx = bleAdvertiser?.txState ?: BleConstants.PSTATE_IDLE
            if (tx != BleConstants.PSTATE_REVERSE && tx != BleConstants.PSTATE_LOADING) {
                val pState = if (ImuFusion.motionState == BleConstants.MOTION_STATE_STATIONARY)
                    BleConstants.PSTATE_IDLE else BleConstants.PSTATE_FORWARD
                bleAdvertiser?.updateState(pState)
            }
            // Carry the IMU turn estimate (left/right/straight) in the TX payload.
            val turn = ImuFusion.turnDirection
            bleAdvertiser?.updateTurn(turn)
            if (turn != BleConstants.TURN_STRAIGHT) loneWorker.onTurn()   // equipment-mount no-motion watch: a turn counts as movement
            // Polling safety net — keep advertising my highest alert level as the risk state (RISK) even if scanning pauses.
            //   (The main send is on the onDeviceDetected scan cycle; same-level calls are no-ops, so duplicates are harmless.)
            bleAdvertiser?.updateRisk(getCurrentMaxLevel())
            reevaluateZones()       // poll zone signal-loss exits and stale-entry disposal (+IN_ZONE self-heal)
            bleAdvertiser?.updateSos(loneWorker.sosActive, loneWorker.sosEpisode, loneWorker.sosHint)   // self-heal a recreated advertiser — no-op if the values are unchanged
            pushRssiEcho()          // reciprocal RSSI: echo my measured peer RSSI table in the scan response
            broadcastLocalState()   // periodic refresh — keeps the Local UI (state/turn) polling source current
            speedPushHandler.postDelayed(this, SPEED_PUSH_INTERVAL_MS)
        }
    }

    // Reciprocal RSSI: build a (hash, value) table of the RSSI I measured for each peer (pEma = deviceRssiMap) and
    //   send it back in BleAdvertiser's scan-response echo block (0xE0C0). Each peer looks up its own hash to find how
    //   the other side measured it, for the symmetric judgment (sym). Only the 8 closest (strongest RSSI) — the advertiser
    //   cuts it to 5 (15B) when packing the scan response alongside UWB (31B scan-response budget).
    private fun pushRssiEcho() {
        if (!DevSettings.reciprocalRssiEnabled) return
        val snapshot = deviceRssiMap.toList()   // [(fullId, pEma)] — snapshot against concurrent modification
        if (snapshot.isEmpty()) {
            bleAdvertiser?.updateRssiEcho(ByteArray(0))   // no peers → don't leave a stale echo (contentEquals no-op inside)
            return
        }
        val entries = snapshot
            .sortedByDescending { it.second }    // top K, strongest (closest) RSSI first
            .take(8)
            .map { (fullId, rssi) -> BleConstants.shortHash(fullId) to rssi }
        bleAdvertiser?.updateRssiEcho(BleConstants.encodeEchoTable(entries, 8))
    }

    /** Registers BleService-owned per-device state with the removal registry — removal goes only through asm.registry. */
    private fun registerDeviceState() {
        asm.registry.addImmediate("oneSecBuffer", oneSecBuffer)
        asm.registry.addImmediate("wakeRssiMap", wakeRssiMap)
        asm.registry.addImmediate("dwellLevelMap", dwellLevelMap)
        asm.registry.addImmediate("dwellSinceMap", dwellSinceMap)
        asm.registry.addImmediate("dwellMutedLevelsMap", dwellMutedLevelsMap)
        asm.registry.addImmediate(
            "echoDiffLive",
            { id -> CalibrationEngine.echoDiffLive.remove(id)?.let { CalibrationEngine.persistEchoEntry(id, it) } },
            { CalibrationEngine.echoDiffLive.clear() },
            { CalibrationEngine.echoDiffLive.size }
        )
        // deferred — warm filters to preserve. Cleared only on a cold clear or TTL expiry.
        asm.registry.addDeferred("rssiPreFilter", { id -> rssiPreFilter.clear(id) }, { rssiPreFilter.clearAll() })
        asm.registry.addDeferred("medianFilter",  { id -> medianFilter.clear(id) },  { medianFilter.clearAll() })
        asm.registry.addDeferred("pEmaFilter",    { id -> pEmaFilter.clear(id) },    { pEmaFilter.clearAll() })
    }

    override fun onCreate() {
        super.onCreate()
        registerDeviceState()
        // Expose to the developer-settings gauge — released in onDestroy.
        DeviceStateRegistry.live = asm.registry
        isRunning = true
        createNotificationChannel()
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(volumeReceiver,  IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)
        // On API 30 and below, with location off a BLE scan 'succeeds' but returns 0 results.
        //   A completely silent failure with no callback or error → subscribe to provider changes and re-evaluate at once.
        registerReceiver(locationReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))

        // Surface alarm-sound and on-screen-alert faults in the persistent notification.
        //   Both are singleton objects, so these lambdas hold the service instance → stopAll() must set them to null.
        AlertSoundPlayer.onSoundFault = { reason -> soundFault   = reason; refreshNotification() }
        OverlayManager.onOverlayFault = { reason -> overlayFault = reason; refreshNotification() }
        // A header tap on the collapsed sidebar = work-process (role) change. The sidebar is always shown, so this path is always live.
        //   It goes through MainActivity's confirmation dialog instead of switching at
        //   once — an accidental tap in a pocket would mean a monitoring gap.
        OverlayManager.onHeaderTap = {
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    action = ACTION_OPEN_SWITCH_ROLE
                    flags  = Intent.FLAG_ACTIVITY_NEW_TASK or
                             Intent.FLAG_ACTIVITY_CLEAR_TOP or
                             Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }.onFailure { Log.w(TAG, "공정 변경 화면 열기 실패: ${it.message}") }
        }
        // The singletons keep the fault reason for as long as the process lives. setFault suppresses repeat notices of the
        //   same reason, so unless the current value is inherited here, a fault raised before the service was recreated never
        //   reaches the new service.
        soundFault   = AlertSoundPlayer.soundFaultReason
        overlayFault = OverlayManager.overlayFaultReason

        CalibrationEngine.loadEchoPriors()   // FB echo priors — restore cache now + async refresh (judgment reads memory only)
        ImuFusion.init(this)
        // Subscribe to IMU stationary↔moving transitions → dynamic scan mode control (DEVICE and WALKER alike)
        ImuFusion.onStationaryChanged = { stationary ->
            // Sensor thread → hand off to the main handler (scan restarts run on the main looper)
            ecoHandler.post {
                ecoHandler.removeCallbacks(ecoDowngradeRunnable)
                if (stationary) {
                    // Became stationary → power saving after a 5 s debounce (cancelled if it moves meanwhile)
                    ecoHandler.postDelayed(ecoDowngradeRunnable, STATIONARY_ECO_DELAY_MS)
                } else {
                    // Movement detected → back to ACTIVE at once (0 s, combat mode)
                    bleScanner?.setEcoMode(false)
                    // Also wake advertising as soon as movement starts — switch straight to continuous advertising without waiting for
                    //   the next evaluateAdvertiserPower (periodic) tick, removing first-contact TX delay (no-op unless asleep).
                    if (DevSettings.keepAdvertiseWhileMoving) wakeAdvertiser()
                    Log.d(TAG, "IMU 이동 감지 → 즉시 ACTIVE 복귀(전투 모드)")
                }
            }
        }
        // IMU 3-state motion change → map to TX STATE (PSTATE_*) and update the advert.
        //   Stationary (0x00) → PSTATE_IDLE (stopped/normal); normal motion (0x01) and sudden change (0x02) → PSTATE_FORWARD (forward/driving).
        //   The IMU can't detect reversing/loading, so PSTATE_REVERSE / PSTATE_LOADING are injected manually only,
        //   via ACTION_TEST_STATE (or vehicle integration).
        //   ※ A fast approach from hard braking is detected by the collision-geometry filter (Speed+kfVel), not by State.
        ImuFusion.onMotionStateChanged = { code ->
            val pState = if (code == BleConstants.MOTION_STATE_STATIONARY)
                BleConstants.PSTATE_IDLE else BleConstants.PSTATE_FORWARD
            bleAdvertiser?.updateState(pState)
            broadcastLocalState()   // push my state change to the Local UI at once
        }
        // Start TX polling (speedPushRunnable) — periodically pushes my STATE and turn to the advertiser.
        speedPushHandler.post(speedPushRunnable)
        DevSettings.registerOnChange(devPrefsListener)   // subscribe to live settings propagation
        // Decision parameters: the front-end EMA alphas are instance fields, not getters, so they must be injected once at
        //   start (later changes are applied by devPrefsListener → applyLiveSettings)
        applyEmaAlphas()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // null intent = START_STICKY restart → restore from SharedPrefs
        // A lone-worker notification button that wakes an empty process goes through the
        // same restore — monitoring and any saved SOS are revived first
        val lwAction = intent?.action == ACTION_LW_ACK || intent?.action == ACTION_LW_SILENCE
        if ((intent?.action == null || lwAction) && myMode.isEmpty()) {
            val prefs     = getSharedPreferences("safealert_prefs", MODE_PRIVATE)
            val savedMode = prefs.getString("running_mode", null)
            val canStart  = ServiceStartGate.canStart(this)
            if (savedMode != null && canStart) {
                myId   = prefs.getString("device_id", "SA-DEFAULT") ?: "SA-DEFAULT"
                myCategory = prefs.getInt("running_category",
                    if (savedMode == "DEVICE") BleConstants.CAT_FORKLIFT else BleConstants.CAT_WALKER)
                if (!startForegroundTyped("${categoryRoleName(myCategory)} 실행 중", "재시작됨")) return failStart(startId)
                myMode = savedMode
                // An instance started from a notification button is exempt from the while-in-use
                // permission restriction, so it doesn't count as a background start
                bgStarted = intent == null || intent.getBooleanExtra(BootRestoreReceiver.EXTRA_BOOT_RESTORE, false)
                prefs.edit().putLong(BootRestoreReceiver.K_STARTED_AT, System.currentTimeMillis()).apply()
                applyMode()
                if (intent?.action == null) return START_STICKY   // notification action: continue into the when below
            } else {
                // Nothing to restore (system restarted us while the user had stopped) — prevents a ghost instance showing only the
                //   foreground notification without BLE from lingering forever via STICKY.
                // A restore lacking start permission also stops here. We may have been launched by a foreground-start request, so if
                //   possible go foreground first, then drop it.
                if (savedMode == null && canStart && startForegroundTyped("SafeAlert", "중지됨")) stopForeground(STOP_FOREGROUND_REMOVE)
                Log.w(TAG, if (savedMode == null) "복원할 실행 상태 없음 — 중지" else "서비스 시작 권한 없음 — 복원 중지")
                if (savedMode != null) return failStart(startId, permission = true)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        when (intent?.action) {
            ACTION_START_DEVICE -> {
                myId   = intent.getStringExtra(EXTRA_ID) ?: "DEVICE_001"
                myMode = "DEVICE"
                // DEVICE mode is EPJ (01) or forklift (10) — told apart by the Category extra (default forklift)
                myCategory = intent.getIntExtra(EXTRA_CATEGORY, BleConstants.CAT_FORKLIFT)
                // Save the mode: used by onStartCommand to restore after a START_STICKY restart
                saveRunningMode(myMode, myId, myCategory)
                bgStarted = false
                if (!startForegroundTyped("${categoryRoleName(myCategory)} 실행 중",
                        buildSubText(DevSettings.deviceTx, DevSettings.deviceRx))) return failStart(startId)
                applyMode()
            }
            ACTION_START_WALKER -> {
                myId   = intent.getStringExtra(EXTRA_ID) ?: "WALKER_001"
                myMode = "WALKER"
                myCategory = BleConstants.CAT_WALKER   // always walker
                saveRunningMode(myMode, myId, myCategory)
                bgStarted = false
                if (!startForegroundTyped("보행자 실행 중", buildSubText(DevSettings.walkerTx, DevSettings.walkerRx))) return failStart(startId)
                applyMode()
            }
            ACTION_STOP       -> if (loneWorker.sosActive) {
                // No stop or role switch during an SOS — only the wearer's own "괜찮아요" clears it
                Log.d(TAG, "구조 요청 중 정지 요청 무시")
                // Restore the running state the screen already cleared — the next restore and screen return use it
                saveRunningMode(myMode, myId, myCategory)
                val sp = getSharedPreferences("safealert_prefs", MODE_PRIVATE)
                if (sp.getLong("running_since", 0L) == 0L) sp.edit().putLong("running_since", System.currentTimeMillis()).commit()
                sendStatusBroadcast("[괜찮아요]로 먼저 해제하세요")
            } else {
                // User stopped explicitly → remove the START_STICKY restore keys synchronously (.commit).
                //   Cleared only here, not inside stopAll(): the onDestroy→stopAll() path (system kill, app exit) needs the prefs to
                //   remain so the Always-On restore works. device_id is the user identifier, so it is kept.
                //   The lone-worker resume state is cleared as well.
                LoneWorkerResume.clearOnUserStop(getSharedPreferences("safealert_prefs", MODE_PRIVATE).edit()).commit()
                stopAll()
            }
            ACTION_TEST_START -> startTestAlert()
            ACTION_TEST_STOP  -> stopTestAlert()
            ACTION_MUTE_TEMP   -> muteTemporarily("화면 터치")
            ACTION_UNMUTE      -> unmuteImmediately()
            ACTION_MUTE_DEVICE -> muteDevice(intent.getStringExtra(EXTRA_ID))
            ACTION_MUTE_ALL    -> muteAllHazards()
            // Developer manual STATE injection — special states reverse (PSTATE_REVERSE=2) / loading (PSTATE_LOADING=3).
            //   Hook to verify TX integrity in a 2-device field test (back to normal = PSTATE_IDLE).
            //   e.g. adb shell am startservice -n .../BleService -a ACTION_TEST_STATE --ei extra_pstate 2
            ACTION_TEST_STATE -> {
                val s = intent.getIntExtra(EXTRA_PSTATE, BleConstants.PSTATE_IDLE)
                bleAdvertiser?.updateState(s)
                broadcastLocalState()   // reflect manual STATE injection in the Local UI at once
                sendStatusBroadcast("수동 STATE 주입: $s")
            }
            // Re-evaluate right after a UWB permission grant or force toggle — writing the same value to SharedPreferences
            //   doesn't fire the change listener, so an explicit intent calls applyUwbLiveState directly.
            //   With no role set (myMode empty) the instance was started without startForeground, so stop safely.
            ACTION_REAPPLY_UWB -> {
                if (myMode.isNotEmpty()) applyUwbLiveState() else stopSelf(startId)
            }
            // Lone-worker notification buttons
            ACTION_LW_ACK -> loneWorker.ack()
            ACTION_LW_SILENCE -> loneWorker.silencePeers(LoneWorkerNotifier.peerTargets(intent))
        }
        return START_STICKY
    }

    /** Save mode and ID to SharedPrefs (for restore after a START_STICKY restart) */
    private fun saveRunningMode(mode: String, id: String, category: Int) {
        getSharedPreferences("safealert_prefs", MODE_PRIVATE).edit()
            .putString("running_mode", mode)
            .putString("device_id", id)
            .putInt("running_category", category)   // for restoring the role
            .putLong(BootRestoreReceiver.K_STARTED_AT, System.currentTimeMillis())   // stop records older than this are ignored
            .commit()   // synchronous save — async .apply() loss would desync restore and stop
    }

    /**
     * Every foreground start goes through here: sets the type explicitly and catches
     * start failures (permissions, background-start limits), returning false.
     */
    private fun startForegroundTyped(title: String, sub: String): Boolean = try {
        val type = ServiceStartGate.fgsType(this)
        androidx.core.app.ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(title, sub), type)
        fgsApplied = type
        ServiceStopNotice.cancel(this)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "포그라운드 시작 실패: ${e.javaClass.simpleName}")
        false
    }

    /**
     * Start failure (foreground start failed, or restore without start permission): post a notice that
     * opens the app when tapped, then stop. Returning to the screen starts it again.
     */
    private fun failStart(startId: Int, permission: Boolean = false): Int {
        ServiceStopNotice.show(this, permission)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun applyMode() {
        // While monitoring runs the sidebar is always shown. Even with 0 hazards it must stay up (collapsed) so a header tap
        //   can change the work process. Show it before the Bluetooth check — even if BT is off and we return early below,
        //   the process-change entry point must stay alive.
        updateFloatingOverlay()
        // Always on for every role — no-motion and fall checks must run even with Bluetooth off
        loneWorker.start(
            (if (myMode == "DEVICE") BleConstants.DEVICE_PREFIX else BleConstants.WALKER_PREFIX) + myId,
            myId, BleConstants.categoryName(myCategory), myZoneInside
        )
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter

        val modeStr = "칼만(위험 ${BleConstants.rssiDanger}dBm / 경고 ${BleConstants.rssiWarning}dBm)"
        Log.i(TAG, "=== BLE 임계값 확인: $modeStr ===")
        sendStatusBroadcast("설정: $modeStr")

        if (btAdapter == null) {
            sendStatusBroadcast("블루투스 미지원 기기")
            checkSystemHealth()   // broadcasts reach only open screens → surface in the persistent notification
            return
        }
        if (!btAdapter.isEnabled) {
            sendStatusBroadcast("블루투스 꺼짐 — 켜주세요")
            checkSystemHealth()
            return
        }

        // A new advertiser and scanner are created from here. Retire any previous pair first (a repeated start while
        //   running would otherwise leave it advertising and scanning beside the new one, and nothing would stop it).
        //   A TX fault reason left by the previous instance is no longer valid either (if the new advertiser fails too,
        //   its callback sets it again).
        stopBle()
        txFault = null

        val doTx = if (myMode == "DEVICE") DevSettings.deviceTx else DevSettings.walkerTx
        val doRx = if (myMode == "DEVICE") DevSettings.deviceRx else DevSettings.walkerRx

        if (doTx) {
            val advertiser = btAdapter.bluetoothLeAdvertiser
            if (advertiser != null) {
                val prefix = if (myMode == "DEVICE") BleConstants.DEVICE_PREFIX else BleConstants.WALKER_PREFIX
                // Pass the role (Category) to the advertiser → packed into 1-byte payload bits[1:0]
                val bleAdv = BleAdvertiser(advertiser, prefix, myCategory,
                    onStatusUpdate = { msg -> sendStatusBroadcast(msg) }
                )
                bleAdvertiser = bleAdv
                // Advertising failures go to the persistent notification, not just the log
                bleAdv.onTxFault = { reason -> txFault = reason; refreshNotification() }
                bleAdv.updateSos(loneWorker.sosActive, loneWorker.sosEpisode, loneWorker.sosHint, restart = false)   // load the current SOS state into the new advertiser first
                bleAdv.startAdvertising(myId)
                // UWB is applied separately after advertising starts — tear down the previous session first (mode switches), then recreate
                uwbRanger?.stop(); uwbRanger = null
                applyUwbLiveState()
                sendStatusBroadcast("TX 송출 요청: $myId")
                broadcastLocalState()   // initial Local state broadcast when TX starts
                startAdvPowerManager()  // start RSSI-based TX power management (sleep/wake)
                Log.d(TAG, "TX 시작: $prefix$myId")
            } else {
                sendStatusBroadcast("TX 오류: 이 기기는 BLE 광고 미지원")
                Log.e(TAG, "TX: bluetoothLeAdvertiser null")
                txFault = "이 기기는 BLE 송신을 지원하지 않음"
            }
        }

        if (doRx) {
            val scanner = btAdapter.bluetoothLeScanner
            if (scanner != null) {
                lastScanResultMs = System.currentTimeMillis()
                startScanHealthCheck()
                bleScanner = BleScanner(scanner).also { s ->
                    s.onStatusUpdate = { msg -> sendStatusBroadcast(msg) }
                    // Reciprocal RSSI: my echo hash = hash of the key a peer stores me under (prefix + id truncated to 15 bytes).
                    //   The peer echoes back how it measured my RSSI, tagged with this hash.
                    //   BleAdvertiser truncates the id to 15 UTF-8 bytes when advertising, so the same truncation is needed here for the
                    //   hash to match the fullId built by the peer's scanner (ASCII ids of up to 15 chars are unchanged).
                    //   The width must equal BleAdvertiser's.
                    val myWireId = String(myId.toByteArray(Charsets.UTF_8).take(15).toByteArray(), Charsets.UTF_8)
                    val myPrefix = if (myMode == "DEVICE") BleConstants.DEVICE_PREFIX else BleConstants.WALKER_PREFIX
                    s.myEchoHash = BleConstants.shortHash(myPrefix + myWireId)
                    // Even past the BLE signal timeout (combat 2s / rest 6s), a device with fresh UWB measurements (≤1s) is not yet
                    //   declared lost — a brief advert loss alone must not let onDeviceLost tear down a measuring UWB session.
                    //   If measurements stop too, it is lost normally on the next sweep (including the registry state-map purge) — the
                    //   grace only postpones; the path itself is unchanged.
                    s.uwbMeasuringCheck = { id -> uwbDist.freshUwbDistM(id) != null }
                    s.startScanning(object : BleScanCallback {
                        override fun onDeviceDetected(deviceId: String, rssi: Int, remoteState: Int, remoteTurn: Int, payloadPresent: Boolean, peerEchoRssi: Int, peerInZone: Boolean) {
                            lastScanResultMs = System.currentTimeMillis()
                            loneWorker.noteBeacon(deviceId, rssi)   // latest strongest beacon for the SOS — recorded before the gate

                            if (myMode == "WALKER"
                                && deviceId.startsWith(BleConstants.WALKER_PREFIX)
                                && !(deviceId.contains("BEA_") && !BeaconRegistry.isVisitorBeacon(deviceId))   // only visitor beacons are treated as walkers; equipment/unregistered ones alert
                                && !DevSettings.walkerDetectsWalker) return

                            // Full safe-zone suppression — inside a zone only zone-beacon signals are received.
                            //   BleScanner routes zone beacons through the dedicated onZoneBeaconSignal path, so they never reach here and this
                            //   return doesn't block zone reception (zone entry/exit still work).
                            //   Devices left over at entry were already cleared by refreshMyZoneInside's forceLoseAll.
                            if (myZoneInside) return

                            noteRssiForWake(deviceId, rssi)   // near signal → immediate wake decision
                            acquireDetectionWakeLock(rssi)   // screen off + near (>=WAKE) → brief CPU hold so the chain completes
                            // The same gate also switches scan batching to 0ms immediate delivery — even with the CPU awake on a wakelock,
                            //   500ms batching makes the BLE chip hold results for 0.5s and halves the benefit, so drop it to 0ms too
                            //   (going back to false is aggregated per evaluation cycle).
                            if (rssi >= WAKE_RSSI_DBM) bleScanner?.setHazardNear(true)
                            // Peer IN_ZONE declaration cache — must be updated before processAlert so this sample's judgment sees it
                            peerInZoneMap[deviceId] = peerInZone
                            try {
                                processAlert(deviceId, rssi, remoteState, remoteTurn, payloadPresent, peerEchoRssi)
                                // However processAlert changed alertState (add, escalate, SAFE removal, TTC early alert), send the full snapshot
                                // right after in one go → the bottom list never disagrees with the floating overlay and alarms.
                                broadcastDeviceList()
                                // Re-advertise my highest detected alert level as the risk state (RISK) at once → the peer can sound first via
                                //   cooperative escalation (compromise) before we cross. Sent from the scan path, without waiting for the TX poll (earlier onset).
                                //   updateRisk is a no-op at the same level, immediate on a rise and throttled 0.5s on a fall, so calling it every scan is safe.
                                bleAdvertiser?.updateRisk(getCurrentMaxLevel())
                            } finally {
                                releaseDetectionWakeLock()   // release as soon as the chain ends (alertWakeLock takes over on alert)
                            }
                        }
                        override fun onDeviceLost(deviceId: String) = handleDeviceLost(deviceId)
                        override fun onScanError(errorCode: Int) { Log.e(TAG, "스캔 오류: $errorCode") }
                        override fun onUwbAddressReceived(deviceId: String, uwbAddress: ByteArray) {
                            // Walker gate mirror — if a UWB session opened between walkers that judgment (onDeviceDetected) filtered out,
                            //   judgeUwbOnly would revive the filtered device. Block session setup itself.
                            if (myMode == "WALKER" && deviceId.startsWith(BleConstants.WALKER_PREFIX)
                                && !(deviceId.contains("BEA_") && !BeaconRegistry.isVisitorBeacon(deviceId)) && !DevSettings.walkerDetectsWalker) return
                            // Record the 0x9ABC sighting (diagnostics only — not used for judgment) + pass the address = session (re)open path
                            peerUwbSeenMap[deviceId] = System.currentTimeMillis()
                            uwbRanger?.onPeerUwbAddressReceived(deviceId, uwbAddress)
                        }
                        // Peer SOS bit — a path separate from the judgment gate so phones inside a zone and walker phones get it too
                        override fun onPeerSos(deviceId: String, sos: Boolean, episode: Int, hint: Int) {
                            loneWorker.onPeerBle(deviceId, sos, episode, hint)
                        }
                        override fun onZoneBeaconSignal(beaconKey: String, rssi: Int, enterRssi: Int) {
                            // A zone beacon is a genuine scan result too. Without updating here, on a site with only a zone beacon and no
                            //   devices nearby, the health check restarts the RX scan every 15 s and samples keep getting cut off (only the
                            //   first signal seems to arrive).
                            lastScanResultMs = System.currentTimeMillis()
                            // Zone beacon signal → wired to the service's zone state machine (the interface default is a no-op, so this is required)
                            this@BleService.onZoneBeaconSignal(beaconKey, rssi, enterRssi)
                        }
                    })
                }
                sendStatusBroadcast("RX 스캔 시작")
                Log.d(TAG, "RX 시작")
            } else {
                sendStatusBroadcast("RX 오류: BluetoothLeScanner null")
                Log.e(TAG, "RX: bluetoothLeScanner null")
            }
        }

        // The periodic check must run whether or not we scan. If startScanHealthCheck() ran only inside if (doRx),
        //   TX-only devices would have no periodic check at all and never notice a revoked permission or BT off.
        //   startScanHealthCheck() first calls removeCallbacksAndMessages(null), so a double call is harmless.
        startScanHealthCheck()
        checkSystemHealth()
        // The notification posted by startForeground doesn't know the current state.
        //   Even if checkSystemHealth ends with 'no change' (e.g. healthy from the start), redraw it once with the current state.
        refreshNotification()
    }

    private fun processAlert(deviceId: String, rssi: Int, remoteState: Int = 0x00, remoteTurn: Int = BleConstants.TURN_STRAIGHT, payloadPresent: Boolean = false, peerEchoRssi: Int = BleConstants.NO_ECHO_RSSI, nowMs: () -> Long = { System.currentTimeMillis() }) =
        asm.processAlert(deviceId, rssi, remoteState, remoteTurn, payloadPresent, peerEchoRssi, nowMs)

    /** The scan callback's signal-lost handler (applyMode); a named method so tests can drive the real path. */
    private fun handleDeviceLost(deviceId: String) {
        Log.d(TAG, "신호 소실: $deviceId")
        // Filter defer-clear — if a last RSSI snapshot exists, keep the filters instead of clearing them now.
        //   Rediscovered within 30s inside a ±10dB band → processAlert restores the warm filters + waives the TimeGate once;
        //   a rediscovery that doesn't qualify (processAlert) or TTL expiry (healthCheck prune) commits the cold clear.
        // Read the snapshot before purge (deviceRssiMap is an immediate slot too).
        val lastRssi = deviceRssiMap[deviceId]
        if (lastRssi != null) {
            filterPreserveMap[deviceId] = AlertStateMachine.FilterPreserveState(lastRssi, android.os.SystemClock.elapsedRealtime())
        }
        uwbRanger?.onDeviceLost(deviceId)    // clean up UWB candidates/sessions — before map removal (original order kept)
        // Single path for removing device state. cold = no snapshot → cold-clear at once, (deferred) filters included.
        //   Covers: alertState, all ASM state maps, the 5 BleService maps (the 3 dwell maps = clearDwellMute),
        //   the 3 uwb maps, echoDiffLive (persisted, then cleared). Registration: see ASM init and registerDeviceState.
        asm.registry.purge(deviceId, cold = lastRssi == null)
        sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
        if (alertState.isEmpty()) {
            AlertSoundPlayer.stopSound()
            VibrationHelper.stopVibration(this@BleService)
            collapseOverlay()
            activeSoundLevel = BleConstants.LEVEL_SAFE
            // Inside a zone, the last device leaving would overwrite the safe-zone status with "경보 중지" — so branch.
            //   A system fault (Bluetooth, a permission or location off) comes first, as in the notification: it is what
            //   the user must fix, and "기기 이탈" would read as all clear.
            sendStatusBroadcast(systemFault ?: if (myZoneInside) "세이프존 — 경보 억제 중" else "기기 이탈 → 경보 중지")
        } else {
            resyncSoundToRemaining()  // higher device left → lower the sound to the remaining max level
            updateFloatingOverlay()   // switch the floating overlay to another hazard
        }
        // alertState shrank on loss, so re-send the risk state (RISK) at once (SAFE if empty).
        bleAdvertiser?.updateRisk(getCurrentMaxLevel())
        // Re-send the list right after signal loss (an empty list is forced too → the empty state shows at once)
        broadcastDeviceList(force = true)
    }

    // Body owned by UwbDistanceManager — only the signature is kept (UwbSessionGoldenTest calls this name directly via
    //   ReflectionHelpers.callInstanceMethod, and internal call sites stay as they are).
    private fun uwbJudgeModeExclusive(deviceId: String, now: Long): Boolean =
        uwbDist.uwbJudgeModeExclusive(deviceId, now)

    // Immediate driver for UWB measurement samples — called directly from UwbRanger.handleResult (main thread).
    //   Decouples the judgment rate from BLE scan reception quality, shortening it to the UWB report period (FREQUENT ~120ms).
    //   Only Case A pairs are judged here; otherwise only the sample time is recorded (Case A freshness evidence).
    private fun onUwbSampleReceived(deviceId: String, distM: Float) {
        // Only for devices the scanner still tracks. A controller drops a lost peer only a moment later, and one more sample
        //   in that window would bring the device back as a first detection that no loss can end.
        if (bleScanner?.isTracked(deviceId) == false) return
        val now = System.currentTimeMillis()
        uwbSampleAtMsMap[deviceId] = now
        // Full safe-zone suppression — block UWB as strictly as the RSSI path (the early return before processAlert).
        //   UwbRanger calls this callback directly, bypassing processAlert's zone gate; without blocking here, judgment would
        //   run to completion inside a zone and devices would stay in the list/overlay (only audio was suppressed).
        //   The sample time was already recorded above, so after leaving the zone Case A freshness recovers on the next sample.
        //   Devices left over at entry are cleared by refreshMyZoneInside's forceLoseAll → onDeviceLost.
        if (myZoneInside) return
        if (!uwbJudgeModeExclusive(deviceId, now)) return
        acquireDetectionWakeLock(0)   // 0 (strong) — always acquire with screen off: a UWB measurement proves proximity
        try {
            judgeUwbOnly(deviceId, distM, now)
            broadcastDeviceList()
            bleAdvertiser?.updateRisk(getCurrentMaxLevel())
        } finally {
            releaseDetectionWakeLock()
        }
    }

    // ── Case A decision — measured UWB distance is the sole authority (RSSI never involved) ─────────────
    //   Uses none of the RSSI pipeline (median/EMA/Kalman/warm-up/Time-Gate/streak/1s average).
    //   Level comes only from the measured distance and the role-pair radii (forklift pair 15/8m, others
    //   5/3m); alerting, sound, display and Firebase mirror the processAlert canonical recipe as-is
    //   (processAlert returns early in Case A, so no double alert). Escalate = 1 sample, immediately
    //   (minimum latency); de-escalate = 3 consecutive confirming samples or separating kinematics
    //   (separatingStreak≥3, closing<0) + hysteresis (keep threshold+0.5m).
    private fun judgeUwbOnly(deviceId: String, uwbD: Float, now: Long) = asm.judgeUwbOnly(deviceId, uwbD, now)

    private val isScreenOn: Boolean
        get() = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    // ── Screen-off wake-up: PARTIAL_WAKE_LOCK held only around alerts ──────────────────
    //   Why: with the screen off the device enters Doze and the CPU sleeps intermittently. The
    //     foreground scan stays alive, but if the CPU sleeps mid-callback
    //     (Median→EMA→Kalman→alert→vibration/sound/overlay) the alert is felt late.
    //   Design: instead of holding it all the time, take it briefly only when an alert actually fires
    //     (forceAlarmVolume — the single choke point for special/TTC/mute-recovery/regular/test
    //     alerts) and let the OS release it by timeout (no battery leak even if release is missed).
    //     While a hazard stays near, each alert renews the timeout, so it is held only during the
    //     alert period → near-zero idle cost, suitable for walker handsets.
    private val ALERT_WAKELOCK_MS = 3000L   // window guaranteed from alert to the user noticing it (screen on)
    private val alertWakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafeAlert:AlertWakeLock")
            .apply { setReferenceCounted(false) }
    }

    // Wakes the CPU for ALERT_WAKELOCK_MS when an alert fires so vibration, sound and overlay complete.
    //   Not taken with the screen on (user aware/interacting = not in Doze). Timeout-based: calling again
    //   extends it with a new timeout, and it frees itself once the hazard is gone (no reference counting).
    private fun acquireAlertWakeLock() {
        if (isScreenOn) return
        try { alertWakeLock.acquire(ALERT_WAKELOCK_MS) } catch (e: Exception) { Log.w(TAG, "WakeLock 획득 실패: ${e.message}") }
    }

    // Releases a held WakeLock immediately — on full alert shutdown (stopAll/onDestroy) it is freed
    //   right away instead of waiting for the timeout, saving battery. No-op if not held.
    private fun releaseAlertWakeLock() {
        try { if (alertWakeLock.isHeld) alertWakeLock.release() } catch (e: Exception) { Log.w(TAG, "WakeLock 해제 실패: ${e.message}") }
    }

    // ── Detection-stage micro wakelock: the processing chain 'before' an alert must complete ──────────────
    //   alertWakeLock holds the CPU only from the moment of the alert (forceAlarmVolume). With the screen
    //   off (Doze), if the CPU sleeps during callback→Median→EMA→Kalman→'alert decision', the alert itself
    //   can be delayed or missed (especially on cold start / the first frame of an approach). This closes
    //   that gap.
    //   Design: taken briefly only when the screen is off + received RSSI ≥ WAKE_RSSI_DBM − DETECTION_WAKE_MARGIN_DB
    //     (pre-acquire margin; scan batching and the advertising wake gate keep
    //     WAKE_RSSI_DBM) and released in finally as soon as the chain ends. If an alert does
    //     fire, alertWakeLock (3s) takes over independently inside it, so there is no gap (separate locks —
    //     no interference). The timeout is a safety net for a missed release (the main looper is
    //     single-threaded, so the normal path releases within ms).
    private val DETECTION_WAKELOCK_MS = 500L
    // Pre-acquire margin — grab the CPU already in the weak band 'before' WAKE_RSSI_DBM is reached, so the
    //   boundary frame that crosses the threshold is fully processed from the start even in Doze (guards
    //   against delay/miss). The only cost is more frequent micro-lock (500ms) acquisition — scan duty,
    //   batching and alert thresholds are unchanged.
    private val DETECTION_WAKE_MARGIN_DB = 10
    private val detectionWakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafeAlert:DetectionWakeLock")
            .apply { setReferenceCounted(false) }
    }

    // Briefly holds the CPU only for frames received near while the screen is off — so Doze does not cut
    //   the processing chain. Not taken with the screen on (user aware) or for a weak enough signal.
    //   Pre-acquires with a 10dB margin below WAKE_RSSI_DBM — frames near the threshold
    //   (WAKE-10 ≤ rssi < WAKE) already take it, so the CPU is awake when the threshold is crossed.
    //   setHazardNear (batching promoted to 0ms) and the advertising wake gate keep the WAKE_RSSI_DBM
    //   threshold — this margin applies to the wakelock only.
    private fun acquireDetectionWakeLock(rssi: Int) {
        if (isScreenOn || rssi < WAKE_RSSI_DBM - DETECTION_WAKE_MARGIN_DB) return
        try { detectionWakeLock.acquire(DETECTION_WAKELOCK_MS) } catch (e: Exception) { Log.w(TAG, "DetectWakeLock 획득 실패: ${e.message}") }
    }

    // Released right after the processing chain ends — the CPU sleeps between frames (scan interval) to save battery.
    //   No-op if not held. If an alert fired, alertWakeLock is a separate lock and is unaffected.
    private fun releaseDetectionWakeLock() {
        try { if (detectionWakeLock.isHeld) detectionWakeLock.release() } catch (e: Exception) { Log.w(TAG, "DetectWakeLock 해제 실패: ${e.message}") }
    }

    private fun forceAlarmVolume() {
        acquireAlertWakeLock()   // so vibration/sound/overlay complete with the screen off (Doze)
        ignoringVolumeChange = true
        try {
            val am     = getSystemService(AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur    = am.getStreamVolume(AudioManager.STREAM_ALARM)
            // Never lower the alarm volume while the rescue-request alarm is sounding.
            val target = AlarmVolumeShare.collisionTarget(
                (maxVol * DevSettings.alarmVolume / 100f).toInt().coerceIn(0, maxVol), cur, AlarmVolumeShare.sosSounding)
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            val actual = am.getStreamVolume(AudioManager.STREAM_ALARM)   // read back to verify it was actually applied
            appliedAlarmVolume = actual
            AlarmVolumeShare.noteCollision(android.os.SystemClock.elapsedRealtime())
            Log.d(TAG, "알람 볼륨: $actual/$maxVol (요청 $target, ${DevSettings.alarmVolume}%)")
            // target == 0 means the user deliberately set the alarm volume to 0%, so it is not a fault.
            if (target > 0 && actual == 0)
                setVolumeFault("알람 볼륨 0 — 방해금지·기기정책에 막힘, 경보음이 들리지 않을 수 있음")
            else
                setVolumeFault(null)
        } catch (e: Exception) {
            Log.w(TAG, "볼륨 강제 설정 실패: ${e.message}")
            setVolumeFault("알람 볼륨 설정 실패 — 경보음이 들리지 않을 수 있음")
        }
        volumeGuardHandler.removeCallbacksAndMessages(null)   // on repeated calls, replace the previously scheduled release
        volumeGuardHandler.postDelayed({ ignoringVolumeChange = false }, 300)
    }

    // Siren volume changes only — applies the same protection so the volume-change broadcast is not
    //   mistaken for a volume-button mute. Does not take the alert wake lock (the lone-worker monitor
    //   manages its own wake lock).
    private fun setAlarmVolumeGuarded(level: Int) {
        ignoringVolumeChange = true
        runCatching {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, level, 0)
            appliedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        }.onFailure { Log.w(TAG, "사이렌 볼륨 설정 실패: ${it.message}") }
        volumeGuardHandler.removeCallbacksAndMessages(null)
        volumeGuardHandler.postDelayed({ ignoringVolumeChange = false }, 300)
    }

    // Expiry of the latest temporary mute, so '즉시 재개' lifts exactly the mutes it set (never a longer Acknowledge)
    private var tempMuteUntil = 0L

    /**
     * Volume key or notification tap = the user has seen what is sounding: the devices alerting now (WARNING or above)
     *   are acknowledge-muted for MUTE_DURATION_MS, like muteAllHazards. A device that comes in during the mute is not one
     *   the user saw, so it alerts as usual. Nothing alerting and no test alert = nothing to mute.
     */
    private fun muteTemporarily(source: String) {
        val targets = alertingDevices()
        if (targets.isEmpty() && testRunnable == null) return
        tempMuteUntil = acknowledge(targets, MUTE_DURATION_MS)
        isMutedPublic = true
        sendStatusBroadcast("무음 ($source) — 탭하여 즉시 재개")
        sendAlertBroadcast("", BleConstants.LEVEL_SAFE)
        Log.d(TAG, "임시 무음: $source (${targets.size}대)")
        muteHandler.removeCallbacksAndMessages(null)
        muteHandler.postDelayed({
            isMutedPublic = false
            sendStatusBroadcast("무음 해제 — 재경보 준비")
            Log.d(TAG, "무음 해제")
        }, MUTE_DURATION_MS)
    }

    private fun unmuteImmediately() {
        muteHandler.removeCallbacksAndMessages(null)
        mutedDevices.values.removeAll { it == tempMuteUntil }
        isMutedPublic = false
        sendStatusBroadcast("즉시 재개됨")
        Log.d(TAG, "즉시 무음 해제")
    }

    // ── Per-device Acknowledge mute + floating widget top-priority device ──────────
    /** Devices alerting now (WARNING or above): what a mute-all or a temporary mute covers. */
    private fun alertingDevices() = alertState.entries.filter { it.value.first >= BleConstants.LEVEL_WARNING }.map { it.key }

    /**
     * Acknowledge-mutes the devices for ms (never shortening a longer mute), stops the current sound/vibration at once (if
     *   other hazard devices remain, the next scan re-fires) and moves the floating widget to the top-priority device left.
     *   The expiry is on the elapsed clock, so a wall-clock change neither shortens nor stretches a mute. Returns it.
     */
    private fun acknowledge(ids: Collection<String>, ms: Long): Long {
        val until = android.os.SystemClock.elapsedRealtime() + ms
        ids.forEach { mutedDevices[it] = maxOf(mutedDevices[it] ?: 0L, until) }
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        activeSoundLevel = BleConstants.LEVEL_SAFE
        updateFloatingOverlay()
        return until
    }

    /** A driver who tapped a sidebar row has visually confirmed → that device's alerts (siren, sidebar row) stop for 30s. */
    private fun muteDevice(deviceId: String?) {
        if (deviceId.isNullOrEmpty()) return
        acknowledge(listOf(deviceId), ACK_MUTE_DURATION_MS)
        sendStatusBroadcast("${extractDisplayName(deviceId)} 확인됨 — 30초 무음")
        Log.d(TAG, "기기 음소거(Acknowledge): $deviceId (30초)")
    }

    /**
     * Dragging the sidebar fully closed = mute all.
     *   Applies ACK mute in bulk to every device currently alerting (WARNING or above). Reuses the existing
     *   mutedDevices map instead of new state, so the 30s auto-expiry and the cleanup on a departure/SAFE release or
     *   a cold loss apply as-is (a short loss keeps it: the user saw that device). Devices entering after the mute are
     *   not in the map and alert normally; a TTC early alert clears the mute and re-alerts.
     */
    private fun muteAllHazards() {
        val targets = alertingDevices()
        acknowledge(targets, ACK_MUTE_DURATION_MS)
        sendStatusBroadcast("전체 확인됨 (${targets.size}대) — 30초 무음")
        Log.d(TAG, "전체 음소거(Acknowledge): ${targets.size}대 (30초)")
    }

    /** Whether the device is currently Acknowledge-muted. Expired entries are cleaned up and return false. */
    private fun isDeviceMuted(deviceId: String): Boolean {
        val until = mutedDevices[deviceId] ?: return false
        if (android.os.SystemClock.elapsedRealtime() >= until) {
            mutedDevices.remove(deviceId)
            return false
        }
        return true
    }

    // ── Level dwell auto-mute ──────────────────────────────
    /**
     * Called every decision frame (only devices registered in alertState — shared by RSSI canonical,
     *  UWB mirror and special alerts).
     *  Level transition = restart the timer; upward transition (W→D) = clear DANGER mute (escalation is
     *  always audible — approved exception); continuous dwell for DWELL_MUTE_MS = mute that level
     *  (DANGER dwell mutes WARNING too — stays quiet on a D→W retreat).
     *  Only time this device is heard counts: a frame kept quiet by the caller (quiet), muted by an Acknowledge or a
     *  temporary mute, inside a zone, below a higher device's sound, or with nothing at its level playing (a siren stopped as
     *  the device moved away) restarts the clock and keeps the mutes earned.
     *  The moment a new mute applies, playing sound/vibration is re-synced to the highest remaining
     *  'audible' level (resyncSoundToRemaining — computed excluding muted devices, so if only muted
     *  ones remain it drops to silence).
     */
    private fun updateDwellMute(deviceId: String, level: Int, now: Long, quiet: Boolean) {
        val prev = dwellLevelMap[deviceId]
        if (prev != level) {
            dwellLevelMap[deviceId] = level
            dwellSinceMap[deviceId] = now
            if (prev != null && level > prev) {
                // Upward transition (W→D): clear DANGER mute — escalation to danger must be audible even while muted
                //   (approved exception). WARNING mute stays (never left the warning zone = reset condition not met).
                dwellMutedLevelsMap[deviceId]?.remove(BleConstants.LEVEL_DANGER)
            }
            // Downward retreat (D→W): keep the set — if DANGER dwell muted WARNING, it stays quiet after the retreat (approved spec).
            return
        }
        if (quiet || isDeviceMuted(deviceId) || myZoneInside || level < getAudibleMaxLevel() ||
            activeSoundLevel < level) {
            dwellSinceMap.remove(deviceId); return
        }
        val since = dwellSinceMap.getOrPut(deviceId) { now }
        if (now - since < DWELL_MUTE_MS) return
        val set = dwellMutedLevelsMap.getOrPut(deviceId) { mutableSetOf() }
        var engaged = set.add(level)
        if (level == BleConstants.LEVEL_DANGER && set.add(BleConstants.LEVEL_WARNING)) engaged = true
        if (engaged) {
            resyncSoundToRemaining()   // if this device drove the sound, drop to remaining audible level (or silence)
            Log.d(TAG, "(v1.1.61) dwell 뮤트 발동: $deviceId level=$level (${DWELL_MUTE_MS}ms 체류) — 소리·진동만 억제")
        }
    }

    /**
     * Whether this device/level is dwell-muted — only for suppressing sound/vibration (not used for display, list or decisions).
     */
    private fun isDwellMuted(deviceId: String, level: Int): Boolean =
        dwellMutedLevelsMap[deviceId]?.contains(level) == true

    /**
     * Zone exit (SAFE cleanup, confirmed departure, untracked demotion, loss, stop) —
     * resets all dwell tracking and mutes → re-entry alerts normally.
     */
    private fun clearDwellMute(deviceId: String) {
        dwellLevelMap.remove(deviceId)
        dwellSinceMap.remove(deviceId)
        dwellMutedLevelsMap.remove(deviceId)
    }

    // ── Zone beacon (safe zone) state machine ─────────────────────────
    //   Enter = ZONE_MIN_SAMPLES consecutive samples at or above enterRssi; exit = ZONE_EXIT_SAMPLES consecutive samples
    //   below enterRssi-5dB, or signal lost longer than GRACE (reevaluateZones polling). Zone decisions use raw
    //   RSSI — no global gain or rssiOffset (BleScanner routes the raw signal here before the alert pipeline).

    /** Handles one zone-beacon scan sample — wired from BleScanCallback.onZoneBeaconSignal. */
    private fun onZoneBeaconSignal(beaconKey: String, rssi: Int, enterRssi: Int) {
        zoneLastSeenMap[beaconKey] = System.currentTimeMillis()
        zoneEnterRssiMap[beaconKey] = enterRssi
        // Raw zone sample log — the only way in the field to tell "beacon not picked up at all" from "picked up but below threshold".
        Log.d(TAG, "존 표본: ${beaconKey} rssi=${rssi} 임계=${enterRssi} " +
                   "n=${zoneSampleMap[beaconKey] ?: 0} inside=${zoneInsideMap[beaconKey]}")
        // zoneSampleMap is a signed streak counter — positive = consecutive samples at/above the threshold (for entry),
        //   negative = consecutive samples below the deadband (for exit). It is signed only to avoid a second map.
        when {
            rssi >= enterRssi -> {
                val n = (zoneSampleMap[beaconKey] ?: 0).coerceAtLeast(0) + 1
                zoneSampleMap[beaconKey] = n
                if (n >= ZONE_MIN_SAMPLES && zoneInsideMap[beaconKey] != true) {
                    zoneInsideMap[beaconKey] = true
                    Log.i(TAG, "(v1.1.62) 존 진입: $beaconKey rssi=$rssi (임계 $enterRssi, ${n}표본)")
                }
            }
            rssi < enterRssi - ZONE_EXIT_HYST_DB -> {
                val n = (zoneSampleMap[beaconKey] ?: 0).coerceAtMost(0) - 1
                zoneSampleMap[beaconKey] = n
                if (-n >= ZONE_EXIT_SAMPLES && zoneInsideMap[beaconKey] == true) {
                    zoneInsideMap[beaconKey] = false
                    Log.i(TAG, "(v1.1.62) 존 이탈(세기 미달): $beaconKey rssi=$rssi < ${enterRssi - ZONE_EXIT_HYST_DB} (${-n}표본)")
                }
            }
            // Deadband (enterRssi-5 <= rssi < enterRssi) — keep both state and samples.
            // Resetting the counter to 0 here would let normal real-world RSSI swings (±5~10dB) near the boundary
            // break the streak every time, so 3 samples would never accumulate while hovering around the threshold.
            else -> Unit
        }
        refreshMyZoneInside()
    }

    /** Evaluation-cycle polling — exit on signal loss (GRACE), drop stale entries (STALE) + IN_ZONE advertising self-heal. */
    private fun reevaluateZones() {
        val now = System.currentTimeMillis()
        val iter = zoneLastSeenMap.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (now - e.value > ZONE_LOST_GRACE_MS && zoneInsideMap[e.key] == true) {
                zoneInsideMap[e.key] = false; zoneSampleMap[e.key] = 0
                Log.i(TAG, "(v1.1.62) 존 이탈(신호 두절): ${e.key}")
            }
            if (now - e.value > ZONE_SIGNAL_STALE_MS) {
                zoneInsideMap.remove(e.key); zoneSampleMap.remove(e.key)
                zoneEnterRssiMap.remove(e.key); iter.remove()
            }
        }
        refreshMyZoneInside()
        bleAdvertiser?.updateInZone(myZoneInside)   // self-heal — no-op if unchanged
    }

    /**
     * Overall zone-contact update — safe zone 'full' suppression transition.
     *   Enter: clears every detected device via the normal loss path (decision, display, overlay and UWB
     *          sessions all released). onDeviceDetected then returns early, so inside the zone only
     *          zone-beacon signals are processed. The advertised hazard level is pinned to SAFE by the
     *          getCurrentMaxLevel clamp — hazards are neither sent nor received.
     *   Exit: detectedDevices is empty, so tracking resumes cleanly from the next advertisement, as for new devices.
     *   ※ myZoneInside must be set before the cleanup so the status text inside onDeviceLost and
     *      getCurrentMaxLevel(SAFE) act on the zone state (order-dependent).
     */
    private fun refreshMyZoneInside() {
        val inside = zoneInsideMap.values.any { it }
        if (inside == myZoneInside) return
        myZoneInside = inside
        loneWorker.onZoneChanged(inside)   // skip the no-motion check while settled in a safe zone
        if (inside) {
            bleScanner?.forceLoseAll()          // lose all devices via the normal loss path (registry purge, UWB cleanup)
            AlertSoundPlayer.stopSound()        // stop any lingering siren at once (double safety)
            VibrationHelper.stopVibration(this)
            collapseOverlay()                   // collapse the list (the sidebar itself stays)
            activeSoundLevel = BleConstants.LEVEL_SAFE
        } else {
            updateFloatingOverlay()             // exit — resync overlay state
        }
        resyncSoundToRemaining()   // getAudibleMaxLevel is SAFE in zone → entry stops sound (exit has nothing to restore)
        bleAdvertiser?.updateInZone(inside)
        bleAdvertiser?.updateRisk(getCurrentMaxLevel())   // entry = advertise SAFE, exit = back to the real level
        broadcastDeviceList(force = true)
        broadcastLocalState()      // mirror zone transition into my state snapshot (change detection dedups)
        sendStatusBroadcast(if (inside) "세이프존 — 경보 억제 중(존 비콘 접촉)" else "존 이탈 — 경보 복원")
        refreshNotification()
        Log.i(TAG, "(v1.1.65) myZoneInside=$inside (세이프존 전면 억제)")
    }

    /**
     * Full list of hazard devices to show in the sidebar.
     *   Devices alerting (WARNING or above) and not Acknowledge-muted, sorted by risk → RSSI (nearest first).
     */
    private fun hazardListForOverlay(): List<OverlayManager.HazardItem> =
        alertState.entries
            .filter { it.value.first >= BleConstants.LEVEL_WARNING && !isDeviceMuted(it.key) }
            .sortedWith(
                compareByDescending<Map.Entry<String, Pair<Int, Long>>> { it.value.first }
                    .thenByDescending { deviceRssiMap[it.key] ?: -100 }
            )
            .map { e ->
                val id   = e.key
                val rssi = deviceRssiMap[id] ?: -99
                // Distance string (empty = fall back to dBm) — same notation rules as the list.
                //   Only fresh measurements get the ·UWB label (freshUwbDistM) — so a dead number is not mistaken for a live measurement.
                OverlayManager.HazardItem(
                    deviceId = id,
                    name     = suddenLabelMap[id] ?: makeApproachLabel(id),
                    rssi     = rssi,
                    danger   = e.value.first >= BleConstants.LEVEL_DANGER,
                    distText = UwbCalibrator.distanceTextFor(uwbPairKeyFor(id), rssi, uwbDist.freshUwbDistM(id))
                )
            }

    /**
     * Shows all hazard devices in the sidebar. With 0 targets it 'collapses' rather than being removed
     *   (only the header remains, showing the current process name + the "공정 변경" badge). The sidebar
     *   itself is always shown while monitoring.
     */
    private fun updateFloatingOverlay() {
        OverlayManager.showSidebar(this, hazardListForOverlay(), categoryRoleName(myCategory))
    }

    /**
     * Returns the sidebar to its collapsed state — it is not removed.
     *   hazardListForOverlay() does not account for the safe zone. Calling
     *   updateFloatingOverlay() on that path would leave the list showing, so pass an empty list explicitly.
     */
    private fun collapseOverlay() {
        OverlayManager.showSidebar(this, emptyList(), categoryRoleName(myCategory))
    }

    private fun startScanHealthCheck() {
        healthCheckHandler.removeCallbacksAndMessages(null)
        healthCheckHandler.postDelayed(object : Runnable {
            override fun run() {
                // Prune preserved snapshots past their TTL (every 15s) — expired devices are finalized with a cold clear
                if (filterPreserveMap.isNotEmpty()) {
                    val nowEl = android.os.SystemClock.elapsedRealtime()
                    filterPreserveMap.filterValues { nowEl - it.atMs > KF_VEL_SEED_TTL_MS }.keys.toList().forEach { id ->
                        filterPreserveMap.remove(id)
                        asm.registry.purgeDeferred(id)   // cold clear of the 3 filters + Kalman (deferred slots)
                        timeGateWaiveSet.remove(id)   // an immediate-group entry — not in the deferred purge, so removed explicitly
                    }
                }
                val elapsed = System.currentTimeMillis() - lastScanResultMs
                // bleScanner guard — this loop also runs in transmit-only configurations, where a device without a
                //   scanner would otherwise log a "no results" warning every 15s (log spam).
                if (bleScanner != null && elapsed > SCAN_HEALTH_CHECK_MS) {
                    // Restart only the receive (RX) scanner — transmit (TX) is never interrupted. A full stopBle()+applyMode()
                    //   restart would also cut TX advertising, making this device vanish from peers (a visibility gap every
                    //   15s, even when no devices are around). No status broadcast spam either.
                    Log.w(TAG, "스캔 헬스체크: ${elapsed / 1000}초간 결과 없음 → RX 스캔 재시작")
                    bleScanner?.restartScan()
                    lastScanResultMs = System.currentTimeMillis()
                }
                // Recover a dropped anonymous sign-in — retry if signed out, skip if in progress. Rides this 15s cycle instead of a new timer
                com.wf11.safealert.firebase.FirebaseConfig.ensureSignedIn()
                // Permission revocation has no callback — checking periodically is the only option.
                checkSystemHealth()
                if (isRunning) healthCheckHandler.postDelayed(this, SCAN_HEALTH_CHECK_MS)
            }
        }, SCAN_HEALTH_CHECK_MS)
    }

    private fun startTestAlert() {
        stopTestAlert()
        sendAlertBroadcast("TEST", BleConstants.LEVEL_DANGER)
        sendStatusBroadcast("테스트 경보 실행 중")
        testRunnable = object : Runnable {
            override fun run() {
                if (isMutedPublic) { testHandler.postDelayed(this, 3000); return }
                forceAlarmVolume()
                if (DevSettings.vibrationEnabled) VibrationHelper.vibrateDanger(this@BleService)
                if (DevSettings.soundEnabled)     AlertSoundPlayer.playDanger(this@BleService)
                testHandler.postDelayed(this, 3000)
            }
        }
        testHandler.post(testRunnable!!)
        Log.d(TAG, "테스트 경보 시작")
    }

    private fun stopTestAlert() {
        testRunnable?.let { testHandler.removeCallbacks(it) }
        testRunnable = null
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        sendAlertBroadcast("TEST", BleConstants.LEVEL_SAFE)
        sendStatusBroadcast("테스트 중지")
        Log.d(TAG, "테스트 경보 중지")
    }

    private var lastStatusBroadcastMs = 0L
    private fun sendStatusBroadcast(status: String) {
        lastStatus = status
        val now = System.currentTimeMillis()
        if (now - lastStatusBroadcastMs >= 1000L) {
            lastStatusBroadcastMs = now
            sendBroadcast(Intent(BROADCAST_BLE_STATUS).putExtra(EXTRA_STATUS, status))
        }
        Log.d(TAG, "상태: $status")
    }


    private fun extractDisplayName(deviceId: String): String {
        val suffix = when {
            deviceId.startsWith(BleConstants.DEVICE_PREFIX) ->
                deviceId.removePrefix(BleConstants.DEVICE_PREFIX)
            deviceId.startsWith(BleConstants.WALKER_PREFIX) ->
                deviceId.removePrefix(BleConstants.WALKER_PREFIX)
            else -> deviceId
        }
        return when {
            suffix.startsWith("BEA_") -> BeaconRegistry.labelForFullId(deviceId)   // UUID and MAC beacons alike: full-ID match per type
            suffix.isBlank() -> "알 수 없음"
            else -> suffix
        }
    }

    /** Category (CAT_*) -> role name for display. */
    private fun categoryRoleName(category: Int): String = when (category) {
        BleConstants.CAT_EPJ      -> "EPJ"
        BleConstants.CAT_FORKLIFT -> "지게차"
        BleConstants.CAT_WALKER   -> "보행자"
        else                      -> "보행자"
    }

    /**
     * Role label for device display.
     * With a decoded Category cache it distinguishes walker/EPJ/forklift;
     * without one (payload-less devices such as beacons) it falls back to the prefix rule (equipment/walker).
     */
    private fun typeLabelOf(deviceId: String): String {
        val cat = deviceCategoryMap[deviceId]
        return if (cat != null) categoryRoleName(cat)
               else if (deviceId.startsWith(BleConstants.DEVICE_PREFIX)) "장비" else "보행자"
    }

    /**
     * Display text for a normal (NORMAL) approach.
     *   ※ Special states (reverse, loading) take priority via suddenLabelMap (makeStateLabel); this is the fallback.
     *   Movement is judged by STATE (STATE!=IDLE = moving).
     *     moving = "{role}{name}이(가) {turn}이동 중입니다." / stopped = "{role}{name}이(가) 주변에 있습니다."
     *     {role} = bracketed role tag, only with a cached payload; {turn} = "좌회전하며 " / "우회전하며 " / empty.
     *   Only while moving with the reversePrepUntil latch alive is it prefixed: "후진(전진)을 대비해주세요 · {base}".
     */
    private fun makeApproachLabel(deviceId: String): String {
        val name = extractDisplayName(deviceId)
        val moving = (deviceStateMap[deviceId] ?: BleConstants.PSTATE_IDLE) != BleConstants.PSTATE_IDLE
        // Shows the decoded hex (role and turn bits) in readable form — the receiver's popup/overlay/list shows
        //   the sender's role (forklift/EPJ/walker) and turn direction. Role prefix only with a payload cache (no beacon fallback).
        val rolePrefix = deviceCategoryMap[deviceId]?.let { "[${categoryRoleName(it)}] " } ?: ""
        val turnWord = if (moving) when (deviceTurnMap[deviceId] ?: BleConstants.TURN_STRAIGHT) {
            BleConstants.TURN_LEFT  -> "좌회전하며 "
            BleConstants.TURN_RIGHT -> "우회전하며 "
            else -> ""
        } else ""
        val base = if (moving) "${rolePrefix}${name}이(가) ${turnWord}이동 중입니다."
                   else        "${rolePrefix}${name}이(가) 주변에 있습니다."
        // While the reverse(forward)-prep latch is alive, prepend the advisory (RSSI trend reversal detected).
        //   Double guard — even with the latch alive, suppress the prefix if the peer is currently advertising
        //   stopped (IDLE = !moving). If the peer stops while the latch holds (default 4s), the payload state wins.
        return if (moving && (reversePrepUntil[deviceId] ?: 0L) > System.currentTimeMillis())
                   "후진(전진)을 대비해주세요 · $base"
               else base
    }

    /**
     * Special-state (reverse, loading) alert display text — by Category/State.
     *   Reverse (REVERSE): forklift "{name} 지게차 후진 중! 주의!", others "{name} {role} 후진 중! 주의!"
     *   Loading/work (LOADING): forklift "{name} 상부 고소 작업 중! 낙하물 주의!", others "{name} {role} 하역·작업 중! 주의!"
     *   ※ Stopped/normal (IDLE) and forward/driving (FORWARD) are not special alerts, so this is not called for them (fallback only).
     */
    private fun makeStateLabel(name: String, category: Int, state: Int): String {
        val role = categoryRoleName(category)
        return when (state) {
            BleConstants.PSTATE_REVERSE ->
                if (category == BleConstants.CAT_FORKLIFT) "$name 지게차 후진 중! 주의!"
                else "$name $role 후진 중! 주의!"
            BleConstants.PSTATE_LOADING ->
                if (category == BleConstants.CAT_FORKLIFT) "$name 상부 고소 작업 중! 낙하물 주의!"
                else "$name $role 하역·작업 중! 주의!"
            else -> "$name $role 주행 중! 주의!"
        }
    }

    private var lastDeviceListMs = 0L

    /**
     * Single source of truth — broadcasts the whole current alertState (devices alerting) as 'one' serialized list,
     * followed by detected non-alerting devices as SAFE rows.
     *
     * Key: the bottom-of-screen list and the floating widget both use the same alertState as their source,
     *      structurally preventing state mismatches (Sync) such as the alarm sounding while the list shows no detection.
     *
     * Sort     : level descending → for equal level, stronger (nearer) RSSI first. Max 10 rows in total.
     * Serialize: record = "level\u001Frssi\u001Fname\u001Fdist" (dist may be empty), record separator = "\u001E"
     *            (names never contain newlines/separators).
     * An empty list (count=0) ignores the throttle and is sent at once → 'no detection' shows without delay when the last device leaves.
     *
     * Threads: scan callbacks, timeouts and receivers all run on the main looper, so alertState is accessed from one thread → no race.
     */
    private fun broadcastDeviceList(force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Clean up stale pending devices — remove those past the TTL (aligned with the scanner timeout).
        pendingDisplayMap.entries.removeIf { now - it.value > PENDING_DISPLAY_TTL_MS }
        val entries = alertState.entries.toList()
        // Throttle condition — even with no alerts, pending devices make the list
        // subject to the 200ms throttle (only an empty list is sent at once).
        if (!force && (entries.isNotEmpty() || pendingDisplayMap.isNotEmpty()) && now - lastDeviceListMs < 200L) return
        lastDeviceListMs = now

        val sorted = entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Pair<Int, Long>>> { it.value.first }
                    .thenByDescending { deviceRssiMap[it.key] ?: -100 }
            )
            .take(10)

        val sb = StringBuilder()
        sorted.forEach { entry ->
            val id    = entry.key
            val level = entry.value.first
            val rssi  = deviceRssiMap[id] ?: -99
            val name  = suddenLabelMap[id] ?: makeApproachLabel(id)
            // 4th field = distance string (may be empty) — backward compatible, since older parsers only check f.size>=3.
            val dist  = UwbCalibrator.distanceTextFor(uwbPairKeyFor(id), rssi, uwbDist.freshUwbDistM(id))
            if (sb.isNotEmpty()) sb.append('\u001E')
            sb.append(level).append('\u001F').append(rssi).append('\u001F').append(name).append('\u001F').append(dist)
        }

        // Merge gate-pending devices — devices not in alertState (no alert fired yet) are added as SAFE-level rows.
        //   MainActivity renders SAFE level in the 'detected' style (light blue, compact), so they stand out without any UI change.
        //   Alert rows first; remaining slots are filled by stronger (nearer) RSSI — the total stays capped at 10.
        //   Separators match the main serialization: 30.toChar()=U+001E (record), 31.toChar()=U+001F (field).
        var mergedCount = sorted.size
        pendingDisplayMap.keys
            .filter { it !in alertState }
            .sortedByDescending { deviceRssiMap[it] ?: -100 }
            .take(10 - sorted.size)
            .forEach { id ->
                val rssi = deviceRssiMap[id] ?: -99
                val name = suddenLabelMap[id] ?: makeApproachLabel(id)
                val dist = UwbCalibrator.distanceTextFor(uwbPairKeyFor(id), rssi, uwbDist.freshUwbDistM(id))
                if (sb.isNotEmpty()) sb.append(30.toChar())
                sb.append(BleConstants.LEVEL_SAFE).append(31.toChar()).append(rssi).append(31.toChar()).append(name).append(31.toChar()).append(dist)
                mergedCount++
            }

        // Update the fallback sync source — MainActivity polling reads this value even if a broadcast is missed.
        detectedSnapshot = sb.toString()
        detectedCount    = mergedCount

        // setPackage makes this an 'explicit' broadcast → reliably compatible with RECEIVER_NOT_EXPORTED receivers.
        sendBroadcast(Intent(BROADCAST_DETECTED).setPackage(packageName).apply {
            putExtra(EXTRA_DEVICE_LIST, sb.toString())
            putExtra(EXTRA_DEVICE_COUNT, mergedCount)
        })
    }

    // ── My device (Local) state propagation — fully separate from the receive (Target) path ──────────
    private var lastLocalSnapshot = ""

    /**
     * Updates and propagates my device (Local) state snapshot.
     *   Serializes the category/state/turn that bleAdvertiser is 'actually advertising' (field separator U+001F).
     *   Broadcasts only when a value changes (dedup). The static fallback localSnapshot is always kept current.
     *   ※ Values come only from my own advertised state — a peer payload (Target) structurally cannot leak in.
     */
    private fun broadcastLocalState() {
        val adv = bleAdvertiser
        val cat = adv?.txCategory ?: myCategory
        val st  = adv?.txState   ?: BleConstants.PSTATE_IDLE
        val turn = adv?.txTurnDir ?: BleConstants.TURN_STRAIGHT
        val snap = "$cat${31.toChar()}$st${31.toChar()}$turn${31.toChar()}${if (myZoneInside) 1 else 0}"
        localSnapshot = snap
        if (snap == lastLocalSnapshot) return
        lastLocalSnapshot = snap
        sendBroadcast(Intent(BROADCAST_LOCAL_STATE).setPackage(packageName)
            .putExtra(EXTRA_LOCAL_STATE, snap))
    }

    // ── RSSI-driven dynamic sleep/wake ──────────────────────────
    /** Called on every onDeviceDetected — records the latest RSSI sample + wakes immediately (0ms) if near (≥WAKE). */
    private fun noteRssiForWake(deviceId: String, rssi: Int) {
        wakeRssiMap[deviceId] = Pair(rssi, System.currentTimeMillis())
        if (rssi >= WAKE_RSSI_DBM) {
            lastNearSampleMs = System.currentTimeMillis()
            // Peer entered the warning range (WAKE) → speed up my advertising with a LOW_LATENCY burst so the peer
            //   finds me sooner (mutual protection). Request it before waking: even if I was asleep, the following
            //   resumeAdvertising/startAdvertising sees burstUntilMs and starts in LOW_LATENCY.
            if (DevSettings.burstEnabled) bleAdvertiser?.requestBurst(DevSettings.burstHoldMs)
            bleAdvertiser?.requestHazardAdv()   // 5s advertising promotion (≤250ms), independent of burst outcome or burstEnabled
            wakeAdvertiser()
        }
    }

    /** Wakes a sleeping advertiser at once (resumes continuous advertising + force-sends the latest LocalState). */
    private fun wakeAdvertiser() {
        val adv = bleAdvertiser ?: return
        if (adv.isPaused) {
            adv.resumeAdvertising()
            broadcastLocalState()
            Log.d(TAG, "RSSI 웨이크: 근접 신호 → 광고 즉시 재개")
        }
    }

    /** Starts the loop that re-evaluates advertising power (sleep/wake) every ADV_POWER_EVAL_MS. */
    private fun startAdvPowerManager() {
        advPowerHandler.removeCallbacksAndMessages(null)
        advPowerHandler.postDelayed(object : Runnable {
            override fun run() {
                evaluateAdvertiserPower()
                if (isRunning) advPowerHandler.postDelayed(this, ADV_POWER_EVAL_MS)
            }
        }, ADV_POWER_EVAL_MS)
    }

    /**
     * Re-evaluates advertiser sleep/wake.
     *  - Any fresh sample with RSSI ≥ WAKE, an active alert, or moving (keepAdvertiseWhileMoving) → wake (continuous advertising).
     *  - Otherwise → sleep (LOW_POWER heartbeat); equipment enters it only after DEVICE_SLEEP_GRACE_MS.
     *  Old (>SIGNAL_STALE_MS) samples are removed during evaluation.
     */
    private fun evaluateAdvertiserPower() {
        val adv = bleAdvertiser ?: return
        val now = System.currentTimeMillis()
        var anyNear = false
        val iter = wakeRssiMap.entries.iterator()
        while (iter.hasNext()) {
            val (r, ts) = iter.next().value
            if (now - ts > SIGNAL_STALE_MS) { iter.remove(); continue }
            if (r >= WAKE_RSSI_DBM) anyNear = true
        }
        val hasAlert = alertState.isNotEmpty()
        // While moving (IMU not stationary), keep advertising awake even with nothing near and no alert — the key
        //   cold-start lever: it never drops into LOW_POWER (~1s) sleep while moving, so it transmits on first contact.
        //   Removes the groggy first-wake delay. Returns to normal sleep once stopped.
        val moving = DevSettings.keepAdvertiseWhileMoving && !ImuFusion.isStationary
        when {
            anyNear || hasAlert || moving -> {
                advIdleSinceMs = 0L                        // reset the no-contact grace counter
                if (adv.isPaused) {
                    adv.resumeAdvertising(); broadcastLocalState()
                    Log.d(TAG, "RSSI 웨이크(평가): 근접/경보/이동 → 연속 광고 재개")
                }
            }
            // Advertising sleep grace for the equipment role — if equipment sleeps at LOW_POWER (~1s), an approaching
            //   walker receives its first advertisement up to 1s late (the transmit-side half of cold-start delay).
            //   Battery trade-off: sleep is kept; only 'entering' it is delayed by DEVICE_SLEEP_GRACE_MS.
            //   Walkers (WALKER) still sleep immediately — no battery impact.
            else -> {
                if (advIdleSinceMs == 0L) advIdleSinceMs = now
                val grace = if (myMode == "DEVICE") DEVICE_SLEEP_GRACE_MS else 0L
                if (now - advIdleSinceMs >= grace && !adv.isPaused) {
                    adv.pauseAdvertising()
                    Log.d(TAG, "RSSI 슬립(평가): 근접 신호 없음 → 하트비트 모드(유예 ${grace}ms 경과)")
                }
            }
        }
        // Scan batching promotion/return — near/alert ongoing → stay at 0ms; all stale + no alert → back to 500ms power
        //   saving. Moving, however, is excluded from scan batching — merely moving does not justify 0ms scanning.
        //   Only advertising is kept awake; scan batching speeds up only for real proximity/alerts.
        //   Near here = any sample at or above WAKE within SIGNAL_STALE_MS, not each device's latest one as for
        //   advertising above: a peer hovering around WAKE would otherwise flip it every cycle, and every flip restarts
        //   the scan (Android allows 5 per 30 s).
        bleScanner?.setHazardNear(now - lastNearSampleMs <= SIGNAL_STALE_MS || hasAlert)
    }

    /**
     * Safety gate for eco (power-saving) downgrade — true if any hazard signal exists → hold the downgrade (stay fully active).
     *   anyNear   : a fresh (≤SIGNAL_STALE_MS) wakeRssiMap sample with RSSI ≥ WAKE → a nearby device exists
     *   hasAlert  : an active alert (alertState) exists
     *   approach  : a fresh approaching (kfVel>0) sample exists — a device approaching just before the stop is not lost to eco entry
     *  ※ Read-only (no map changes) — stale sample cleanup is owned by evaluateAdvertiserPower (2.5s cycle).
     *  ※ Duty unchanged: does not touch scan/advertising radio settings; only tightens ecoDowngradeRunnable's
     *     'is it OK to downgrade' check (strictly more conservative than an alertState.isEmpty() gate alone —
     *     no added miss risk, only fewer eco entries).
     */
    private fun isDangerPresent(): Boolean {
        val now = System.currentTimeMillis()
        val anyNear  = wakeRssiMap.values.any { (r, ts) -> now - ts <= SIGNAL_STALE_MS && r >= WAKE_RSSI_DBM }
        val hasAlert = alertState.isNotEmpty()
        val approach = lastApproachAtMs != 0L && now - lastApproachAtMs <= SIGNAL_STALE_MS
        return anyNear || hasAlert || approach
    }

    /**
     * Applies dev_settings (SharedPreferences) changes immediately, without restarting the app/service.
     *   · Per-target radii (rssiWarning/rssiDanger), Time-Gate delay (timeGateMs) and the other decision parameters
     *     are read by processAlert every frame via live getters, so they apply on their own.
     *   · Here the Kalman preset is re-injected into every KalmanFilter instance at once — even filters not getting
     *     frames right now (undetected/waiting) — and the EMA alphas, scan/advertise modes, UWB state and site
     *     calibration profile are refreshed.
     *   ※ Decision logic is untouched — only 'parameter values' change live.
     */
    private fun applyLiveSettings(changedKey: String?) {
        val preset = DevSettings.kalmanPreset
        kalmanFilters.values.forEach { it.updatePreset(preset) }
        // Decision parameters: live-update the front-end EMA alpha — swaps only α, keeping emaState (convergence state).
        //   Other decision parameters need nothing here: their 'private val getters' read DevSettings directly every frame.
        applyEmaAlphas()
        // Scan period and advertising interval also apply live — scanPeriodMs/advertiseInterval map to the
        //   scan/advertise modes (BleScanner/BleAdvertiser), so they reach the radio as soon as they are saved.
        //   Called unconditionally, without key filtering — both sides have a no-op guard that restarts only when
        //   the mapped mode actually changes, so it is cheap, and it also covers the null key from resetToDefault (clear).
        bleScanner?.refreshScanMode()
        bleAdvertiser?.refreshAdvertiseMode()
        applyUwbLiveState()   // apply the UWB toggle live
        UwbCalibrator.applySite()   // site code change → switch Δ calibration profile (no-op if unchanged)
        Log.d(TAG, "[Req5] 설정 라이브 반영(key=$changedKey): KF프리셋=$preset 위험=${BleConstants.rssiDanger}dBm 경고=${BleConstants.rssiWarning}dBm TimeGate=${DevSettings.timeGateMs}ms 스캔주기=${BleConstants.scanPeriodMs}ms 광고간격=${BleConstants.advertiseInterval}ms")
        sendStatusBroadcast("설정 라이브 반영됨")
    }

    /**
     * Aligns UWB operation with the current settings — called both at startup and on live settings changes.
     * If conditions are unmet or the toggle is OFF, sessions are torn down and advertising reverts to no-UWB
     * (BLE fallback always stays).
     */
    private fun applyUwbLiveState() {
        if (bleAdvertiser == null) { uwbRanger?.stop(); uwbRanger = null; return }
        val want = UwbRanger.isHardwareSupported(this)
                && (DevSettings.uwbEnabled || DevSettings.uwbForce)   // force-enable runs UWB even when uwbEnabled is OFF
                && ContextCompat.checkSelfPermission(this, Manifest.permission.UWB_RANGING) ==
                       PackageManager.PERMISSION_GRANTED
        if (!want) {
            if (uwbRanger != null) {
                uwbRanger?.stop()
                uwbRanger = null
                bleAdvertiser?.restartWithoutUwbAddress()
                sendStatusBroadcast("UWB 비활성 — BLE 전용")
            }
            return
        }
        // Keep a healthy ranger; if initialization failed (isSupported=false), discard and recreate it — otherwise one
        //   init failure (system UWB OFF, etc.) would latch forever and REAPPLY/settings-change calls would all be no-ops.
        //   After creation, a backoff retry loop (5s→×2→60s cap) runs until it succeeds.
        uwbRanger?.let { existing ->
            if (existing.isSupported) return   // healthy — keep (applyLiveSettings calls this on every settings change)
            existing.stop()
            uwbRanger = null
        }
        // My full advertising ID (prefix+id) — tiebreak for controller election within the same role pair.
        //   It must follow the same prefix rule (DEVICE_/WALKER_) as the fullId BleScanner assigns to peers, so the
        //   peerOutranksMe (id < myFullId) and peerIsVehicle (startsWith DEVICE_PREFIX) comparisons are consistent.
        val myFullId = (if (myMode == "DEVICE") BleConstants.DEVICE_PREFIX
                        else BleConstants.WALKER_PREFIX) + myId
        val ranger = UwbRanger(this, lifecycleScope, myFullId, myMode == "DEVICE",
            onStatus = { msg -> sendStatusBroadcast(msg) },
            onLocalAddressChanged = { payload -> bleAdvertiser?.restartWithUwbAddress(payload) },
            // Smoothed RSSI (pEma) provider for session priority and the start gate — null for untracked devices
            rssiOf = { id -> deviceRssiMap[id] },
            // Detects pairs involving a forklift — for the relaxed start gate (-90) and a session priority bonus.
            //   True if I am a forklift (DEVICE mode default category) or the peer's cached category is forklift.
            forkliftPairOf = { id ->
                myCategory == BleConstants.CAT_FORKLIFT ||
                    deviceCategoryMap[id] == BleConstants.CAT_FORKLIFT
            },
            // Immediate callback per measured sample — drives Case A (exclusive decision). Decouples the decision
            //   cycle from the BLE scan and shortens it to the UWB report cycle (FREQUENT ~120ms) (decide on receipt, no buffering).
            onUwbSample = { id, dist -> onUwbSampleReceived(id, dist) }
        )
        uwbRanger = ranger
        lifecycleScope.launch {
            // Init retry loop — retries with backoff until success so a single failure (permission timing, system UWB
            //   toggle, etc.) does not harden into a permanent BLE fallback. Exits as soon as the ranger is replaced or torn down.
            var backoffMs = 5_000L
            while (true) {
                if (uwbRanger !== ranger) return@launch   // replaced or torn down — abandon this loop
                val payload = ranger.initSession()
                if (payload != null) {
                    bleAdvertiser?.restartWithUwbAddress(payload)
                    sendStatusBroadcast("UWB 활성: ${payload.joinToString("") { "%02X".format(it) }}")
                    return@launch
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    // Decision parameters: inject the front-end EMA (rssiPreFilter) asymmetric alphas from DevSettings.
    //   The post-stage P-EMA (pEmaFilter, 0.4/0.15) keeps fixed alphas — design values for smoothing the Kalman P term only.
    // The warm-up symmetric push count is injected into both front-end and post-stage — correcting the restart
    //   bias (first-sample anchor) needs both EMA instances (fixing only the front-end leaves the post-stage
    //   P-EMA's own anchor residue). Service start (onCreate) and live settings changes both go through this function.
    private fun applyEmaAlphas() {
        rssiPreFilter.alphaRise   = DevSettings.emaAlphaRise
        rssiPreFilter.alphaFall   = DevSettings.emaAlphaFall
        rssiPreFilter.alphaDBoost = DevSettings.emaAlphaDBoost
        rssiPreFilter.warmupSymmetricPushes = DevSettings.emaWarmupPushes
        pEmaFilter.warmupSymmetricPushes    = DevSettings.emaWarmupPushes
    }

    private fun sendAlertBroadcast(deviceId: String, level: Int) {
        val displayName = if (level == BleConstants.LEVEL_SAFE) "" else extractDisplayName(deviceId)
        val type = typeLabelOf(deviceId)
        sendBroadcast(Intent(BROADCAST_ALERT).apply {
            putExtra(EXTRA_ID, deviceId)
            putExtra(EXTRA_ALERT_LEVEL, level)
            putExtra(EXTRA_DISPLAY_NAME, if (displayName.isNotEmpty()) "$displayName ($type)" else "")
        })
    }

    // ── Stop BLE only (service keeps running) ───────────────────────────────────────
    //   stopScanning forgets detected devices without a loss callback, so they are reported lost first: their alert state,
    //   siren, overlay and RISK broadcast must not outlive the scanner. UWB stops before that (no advertiser, no ranger),
    //   so no late sample brings a device back.
    private fun stopBle() {
        bleAdvertiser?.stopAdvertising(); bleAdvertiser = null
        uwbRanger?.stop(); uwbRanger = null
        bleScanner?.forceLoseAll()
        bleScanner?.stopScanning();       bleScanner    = null
    }

    private fun stopAll() {
        btRestartHandler.removeCallbacks(btRestart)
        // Silent BLE teardown, not stopBle: everything below is cleared in bulk (echo calibration is persisted once)
        bleAdvertiser?.stopAdvertising(); bleAdvertiser = null
        bleScanner?.stopScanning();       bleScanner    = null
        // Clean up IMU dynamic scan mode — unregister callbacks + cancel the debounce timer
        ImuFusion.onStationaryChanged = null
        ImuFusion.onMotionStateChanged = null   // unregister the motion state callback
        ecoHandler.removeCallbacks(ecoDowngradeRunnable)
        speedPushHandler.removeCallbacks(speedPushRunnable)   // stop speed transmit polling
        ImuFusion.stop()
        loneWorker.stop()
        uwbRanger?.stop(); uwbRanger = null
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        releaseAlertWakeLock()   // alerts ended → release WakeLock now (no waiting for timeout)
        releaseDetectionWakeLock()   // bounded (500ms), but same release rule as alertWakeLock
        alertState.clear()
        suddenLabelMap.clear()
        deviceCategoryMap.clear()
        deviceStateMap.clear()
        deviceTurnMap.clear(); reverseRssiHist.clear(); reversePrepUntil.clear()
        broadcastDeviceList(force = true)   // service stopped → send an empty list (shows 'no detection')
        localSnapshot = ""; lastLocalSnapshot = ""   // reset my device (Local) snapshot
        // Single path that clears all registered slots (immediate+deferred+teardown).
        //   broadcastDeviceList(force=true) reads pendingDisplayMap to merge pending devices as SAFE rows,
        //   so clearAll must come after the broadcast. The 7 clears above keep their original order (idempotent).
        CalibrationEngine.persistEchoAll(myId)                 // save echo-deviation totals (avoid loss on stop) — before clear
        asm.registry.clearAll()
        zoneSampleMap.clear(); zoneEnterRssiMap.clear(); zoneLastSeenMap.clear()    // clear all zone state — not in the registry
        zoneInsideMap.clear(); myZoneInside = false                                 // peerInZoneMap is cleared by the registry
        testRunnable?.let { testHandler.removeCallbacks(it) }
        testRunnable = null
        muteHandler.removeCallbacksAndMessages(null)
        volumeGuardHandler.removeCallbacksAndMessages(null)   // cancel the pending volume-guard release
        ignoringVolumeChange = false
        isMutedPublic = false
        healthCheckHandler.removeCallbacksAndMessages(null)
        advPowerHandler.removeCallbacksAndMessages(null)   // stop the advertising power evaluation loop
        try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(volumeReceiver)  } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver)  } catch (_: Exception) {}
        try { unregisterReceiver(locationReceiver) } catch (_: Exception) {}
        // If a singleton object keeps holding a lambda that captured the service instance, the service is never
        //   garbage-collected → always detach it, and reset the fault state too.
        AlertSoundPlayer.onSoundFault = null
        OverlayManager.onOverlayFault = null
        OverlayManager.onHeaderTap    = null
        systemFault  = null
        txFault      = null
        soundFault   = null
        overlayFault = null
        volumeFault  = null
        faultBeeped  = false
        isRunning  = false
        lastStatus = ""
        bleScanCount   = 0
        safeAlertFound = 0
        OverlayManager.hideOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "서비스 완전 중지")
    }

    override fun onDestroy() {
        DeviceStateRegistry.live = null   // drop the instrumentation live reference (avoids leaking the service)
        DevSettings.unregisterOnChange(devPrefsListener)   // unregister live settings propagation
        if (isRunning) stopAll()
        releaseAlertWakeLock()   // release even when stopAll was skipped (e.g. the !isRunning path)
        releaseDetectionWakeLock()   // same as above
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    private fun buildSubText(tx: Boolean, rx: Boolean) =
        listOfNotNull(if (tx) "송신 ON" else null, if (rx) "수신 ON" else null)
            .joinToString(" · ").ifEmpty { "TX/RX 모두 비활성" }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "SafeAlert 실행 중", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    // Switch target for the notification action label — same mapping as MainActivity.switchTargetLabel().
    //   WALKER→"지게차" / DEVICE (incl. legacy EPJ)→"보행자".
    private fun switchTargetName(): String = if (myMode == "WALKER") "지게차" else "보행자"

    private fun buildNotification(title: String, subText: String): android.app.Notification {
        // Tapping the body = mute. The target is the whole notification body (title and text), not an action button.
        //   When an alarm goes off the hand reaches for the widest area — there is no time to aim at one of two buttons.
        //   It is an ongoing notification, so it stays after a tap — only mute is applied and the monitoring indicator remains.
        val mutePi = android.app.PendingIntent.getService(
            this, 0,
            Intent(this, BleService::class.java).apply { action = ACTION_MUTE_TEMP },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // Open-app action — tapping the body is taken by mute, so this action is the way into the app.
        //   Launches only MainActivity, with no action (handleSwitchRoleIntent ignores it since the action does not match).
        //   The 3 requestCodes must all differ: with FLAG_UPDATE_CURRENT, identical ones overwrite each other.
        val openPi = android.app.PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // Switch-task action — the role switch button exists only inside activity_main, while the persistent
        //   notification is always shown during monitoring, so this is a stable entry point for a process change.
        //   Goes through a confirmation dialog instead of switching at once — an accidental tap in a pocket means a
        //   monitoring gap. CLEAR_TOP|SINGLE_TOP = reuse the existing instance (onNewIntent), avoiding duplicates.
        val switchPi = android.app.PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_SWITCH_ROLE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subText)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mutePi)
            .addAction(android.R.drawable.ic_menu_view, "앱 열기", openPi)
            .addAction(android.R.drawable.ic_menu_rotate, "${switchTargetName()}로 전환", switchPi)
            .build()
    }

    // ── Persistent notification = the single source of truth for protection status ────────────────
    //   Re-posted with the same NOTIF_ID whenever a fault reason appears or clears, so its content never
    //   stays frozen at what startForeground posted.

    /** Joins the currently active fault reasons into one line. null if everything is normal. */
    private fun faultSummary(): String? =
        listOfNotNull(systemFault, txFault, soundFault, overlayFault, volumeFault)
            .joinToString(" · ")
            .ifEmpty { null }

    /**
     * Redraws the persistent notification with the current state.
     * On entering a fault, plays one warning beep so users who don't look at the notification still notice.
     * (Re-entry safe: faultBeeped is set before playWarning, so if
     *  tone generation fails → onSoundFault → re-entering this function skips the beep and finishes.)
     */
    private fun refreshNotification() {
        if (!isRunning || myMode.isEmpty()) return

        val fault        = faultSummary()
        val fallbackNote = if (AlertSoundPlayer.isUsingMusicFallback) " · 경보음 미디어 대체 중" else ""

        val title: String
        val body:  String
        if (fault != null) {
            title = "SafeAlert 이상 — 보호가 끊겼습니다"
            body  = fault + fallbackNote
        } else if (myZoneInside) {
            // Show the safe zone explicitly — priority right after a fault.
            //   A loss of protection must not be hidden behind the "안전구역" label, so the fault comes first.
            title = "세이프존 — ${categoryRoleName(myCategory)}"
            body  = "안전구역 안입니다 · 경보 송수신 중지" + fallbackNote
        } else {
            val doTx = if (myMode == "DEVICE") DevSettings.deviceTx else DevSettings.walkerTx
            val doRx = if (myMode == "DEVICE") DevSettings.deviceRx else DevSettings.walkerRx
            title = "${categoryRoleName(myCategory)} 실행 중"
            body  = buildSubText(doTx, doRx) + fallbackNote
        }

        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(title, body))
        }.onFailure { Log.w(TAG, "알림 갱신 실패: ${it.message}") }

        if (fault != null) {
            if (!faultBeeped) {
                faultBeeped = true
                runCatching { AlertSoundPlayer.playWarning(this) }
                    .onFailure { Log.w(TAG, "결함 경고음 실패: ${it.message}") }
            }
        } else {
            faultBeeped = false
        }
    }

    // ── BT / permission / location fault monitoring ──────────────────────────
    //   A BT state broadcast alone is seen only by someone with the screen open, and runtime permission
    //   revocation or location turned off must be watched too: with a permission revoked, scanning
    //   silently returns 0 results while the app keeps looking 'normal'.

    private fun setSystemFault(reason: String?) {
        if (systemFault == reason) return
        systemFault = reason
        if (reason != null) Log.w(TAG, "시스템 이상: $reason") else Log.i(TAG, "시스템 이상 해소")
        refreshNotification()
    }

    /** Alarm volume fault. Only forceAlarmVolume()'s read-back verification writes this slot. */
    private fun setVolumeFault(reason: String?) {
        if (volumeFault == reason) return
        volumeFault = reason
        if (reason != null) Log.w(TAG, "볼륨 이상: $reason") else Log.i(TAG, "볼륨 이상 해소")
        refreshNotification()
    }

    /**
     * Re-evaluates the BT adapter, runtime permissions and location in one pass. A fault is promoted to the persistent notification.
     */
    private fun checkSystemHealth() {
        if (!isRunning || myMode.isEmpty()) return

        val doTx = if (myMode == "DEVICE") DevSettings.deviceTx else DevSettings.walkerTx
        val doRx = if (myMode == "DEVICE") DevSettings.deviceRx else DevSettings.walkerRx

        val adapter = runCatching {
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        }.getOrNull()

        if (adapter == null) {
            setSystemFault("이 기기는 블루투스를 지원하지 않음"); return
        }
        if (!adapter.isEnabled) {
            setSystemFault("블루투스 꺼짐 — 감지 중단"); return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (doRx && !hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                setSystemFault("주변 기기 검색 권한 꺼짐 — 감지 중단"); return
            }
            if (doTx && !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                setSystemFault("주변 기기 알림 권한 꺼짐 — 내 신호 송출 중단"); return
            }
        } else if (doRx) {
            // Only on API 30 and below does BLE scanning need location permission and location enabled.
            // API 31+ declares neverForLocation on BLUETOOTH_SCAN (AndroidManifest:7-8) → not applicable.
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                setSystemFault("위치 권한 꺼짐 — 감지 중단"); return
            }
            if (!isLocationEnabled()) {
                setSystemFault("위치 기능 꺼짐 — 감지 중단"); return
            }
            // Apply precise location granted after start to the foreground service type, and redraw the notification text with the current state
            if (ServiceStartGate.needsRetype(Build.VERSION.SDK_INT, fgsApplied, true) && startForegroundTyped("SafeAlert", "")) refreshNotification()
            if (ServiceStartGate.bgLocationLimited(Build.VERSION.SDK_INT, true, bgStarted) {
                    hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                setSystemFault("위치 '항상 허용' 꺼짐 — 재시작 뒤 감지 제한"); return
            }
        }

        setSystemFault(null)
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    /** If the check itself fails, treat it as normal — a false alarm about a nonexistent fault is worse. */
    private fun isLocationEnabled(): Boolean = runCatching {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
             lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(true)
}
