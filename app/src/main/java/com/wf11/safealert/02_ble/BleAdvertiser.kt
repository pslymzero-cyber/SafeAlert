package com.wf11.safealert.ble

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID

class BleAdvertiser(
    private val advertiser: BluetoothLeAdvertiser,
    private val prefix: String = BleConstants.DEVICE_PREFIX,
    // Sender role (Category), packed into the 1-byte payload at bits 7:6.
    //   Walker=CAT_WALKER / EPJ=CAT_EPJ / Forklift=CAT_FORKLIFT
    private val category: Int = BleConstants.CAT_WALKER,
    private val onStatusUpdate: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "BleAdvertiser"
        // Minimum state-update interval, to stay under Android's advertising rate limit.
        //   STATE and Turn (bits 3:2) re-advertising share this throttle.
        private const val MIN_STATE_UPDATE_INTERVAL_MS = 1000L   // Faster STATE/TURN spread; 1s stop/start = LOW_POWER period, OS rate-limit safe
        // Minimum re-advertise interval for RISK only, independent of STATE/TURN (1s).
        //   Risk is safety-critical, so it propagates fast: a rise re-advertises immediately, ignoring even this
        //   interval; only a fall uses the 0.5s minimum, preventing stop/start storms when the level toggles.
        private const val MIN_RISK_UPDATE_INTERVAL_MS = 500L
        // Wait between stopAdvertising and restart on a state change (gives the OS time to clean up)
        private const val STATE_RESTART_DELAY_MS = 50L
        // Minimum re-advertise interval for the mutual RSSI echo, so echo changes alone cannot cause stop/start storms.
        //   A throttled update is skipped; the first speedPush poll (speedPushIntervalMs, default 0.5s) after the window
        //   retries it if the echo still differs from what is on air (self-heal). Independent of the STATE/RISK throttles.
        private const val MIN_ECHO_UPDATE_INTERVAL_MS = 1500L
        // Echo byte budget in the scan response (shares the 31B packet): 15B (5 entries) alongside UWB,
        //   24B (8 entries) without UWB. Both are multiples of 3, so truncation falls on entry (3B) boundaries.
        private const val ECHO_MAX_BYTES_WITH_UWB = 15
        private const val ECHO_MAX_BYTES_NO_UWB   = 24
        // Sleep advertises continuously at LOW_POWER (~1s) instead of stopping (no wake-up delay): it never fully
        //   stops and always transmits at a low rate, so peer scanners always catch this device and rediscover it at
        //   once. The re-advertise cleanup wait is the shared STATE_RESTART_DELAY_MS.

        // Maps the 'advertise interval' setting (advertiseInterval) to the active advertise mode.
        //   Android's public advertising API accepts no arbitrary ms interval, only 3 presets, so the setting is
        //   quantized to the nearest preset (spinner: 100/200/500/1000ms).
        //     ≤100ms → LOW_LATENCY(~100ms) / ≤250ms → BALANCED(~250ms, default 200) / above → LOW_POWER(~1000ms)
        // Hazard advertising promotion hold (ms): on each reception at or above WAKE (wakeRssiDbm, default -95dBm), keep
        //   my advertising promoted to at least BALANCED (~250ms) for this long, whether or not the burst succeeded.
        //   Set by requestHazardAdv().
        private const val HAZARD_ADV_HOLD_MS = 5000L

        // Retry backoff after an advertising start failure: 1s → 2s → 4s … up to 30s.
        //   Without it, one failure would leave the device transmitting nothing until the next re-advertise trigger
        //   (e.g. a STATE change).
        private const val ADV_RETRY_BASE_MS = 1000L
        private const val ADV_RETRY_MAX_MS  = 30_000L
        // After this many consecutive failures, escalate to a user-visible fault (persistent notification).
        private const val ADV_FAIL_ESCALATE = 3

        private fun mapAdvertiseMode(intervalMs: Int): Int = when {
            intervalMs <= 100 -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            intervalMs <= 250 -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            else              -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        }
    }

    // Consecutive advertising start failures; reset to 0 on success (or ALREADY_STARTED).
    @Volatile private var advFailStreak = 0
    // Whether a retry is already scheduled. pause/resume/stop all call
    //   stateHandler.removeCallbacksAndMessages(null), so each of the three must reset this to false, or retries
    //   would be blocked forever.
    @Volatile private var retryScheduled = false

    // Why this device cannot transmit its signal; null when healthy.
    //   setFault passes it via the onTxFault callback to BleService (txFault), which escalates it to a persistent
    //   notification. The field itself only suppresses duplicate notifications for one reason (no external refs).
    @Volatile private var txFaultReason: String? = null

    /** Transmit fault/recovery callback. A null argument means recovered. */
    var onTxFault: ((String?) -> Unit)? = null

    private fun setFault(reason: String?) {
        if (txFaultReason == reason) return
        txFaultReason = reason
        runCatching { onTxFault?.invoke(reason) }
    }

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "광고 시작 성공 ✓")
            advFailStreak = 0
            cancelRetry()
            setFault(null)
            onStatusUpdate?.invoke("TX 송출 확인됨")
        }
        override fun onStartFailure(errorCode: Int) {
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE       -> "패킷 크기 초과"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "광고 슬롯 부족"
                ADVERTISE_FAILED_ALREADY_STARTED      -> "이미 시작됨 (무시)"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED  -> "BLE 광고 미지원"
                else                                  -> "오류($errorCode)"
            }
            Log.e(TAG, "광고 실패: $reason")
            // ALREADY_STARTED means the previous advertisement is still alive, so transmission is fine.
            //   Count it as success: no retry and no fault escalation.
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                advFailStreak = 0
                setFault(null)
                return
            }
            onStatusUpdate?.invoke("TX 실패: $reason")
            // If the hardware cannot advertise at all, retrying is pointless: escalate to a fault immediately.
            if (errorCode == ADVERTISE_FAILED_FEATURE_UNSUPPORTED) {
                cancelRetry()
                setFault("이 기기는 BLE 송신을 지원하지 않음")
                return
            }
            advFailStreak++
            scheduleRetry()
            if (advFailStreak >= ADV_FAIL_ESCALATE) {
                setFault("내 신호를 보내지 못하는 중 ($reason)")
            }
        }
    }

    // deviceId currently being advertised (for UWB restarts)
    private var currentDeviceId = ""

    // Stop guard: keeps re-advertise callbacks already posted (postDelayed) on the main looper from reviving
    //   advertising after stopAdvertising(). Once true, this instance never advertises again
    //   (safe, because a restart has BleService create a new BleAdvertiser).
    @Volatile private var stopped = false

    // RSSI dynamic sleep guard, independent of stopped (permanent shutdown). When paused=true, continuous active-mode
    //   advertising drops to continuous LOW_POWER advertising (still always transmitting, not a full stop).
    //   startAdvertising reads this flag to choose the advertise mode. resumeAdvertising() promotes back to the
    //   active mode (advertiseInterval mapping) immediately (0ms).
    //   ※ Must stay separate from stopped: stopped is a one-way shutdown (re-advertising blocked forever), so using
    //     it for sleep/wake would deadlock (once asleep, never awake again); pause/resume has its own flag, paused.
    @Volatile private var paused = false

    /** Whether advertising is in RSSI sleep (paused). Read by BleService's power-management evaluation. */
    val isPaused: Boolean get() = paused

    // Advertise mode last actually applied to the OS, for the no-op guard in refreshAdvertiseMode().
    //   The prefs listener fires once per key when settings are saved (~12 times at once), so re-advertise only when
    //   the mode really changed, preventing stop/start storms. -1 = advertising not started yet.
    @Volatile private var lastAppliedAdvertiseMode: Int = -1

    // Warning-zone entry burst: when a nearby peer is received (rssi≥WAKE), speed my advertising up to LOW_LATENCY
    //   (~100ms) so the peer discovers me sooner (mutual protection). LOW_LATENCY is kept until this time
    //   (elapsedRealtime ms). 0 = no burst. Set by requestBurst(); burstExpiryRunnable restores the normal mode.
    @Volatile private var burstUntilMs = 0L

    // Hazard advertising promotion: on WAKE reception, keep advertising at least BALANCED (~250ms) until this time
    //   (elapsedRealtime ms), regardless of burst (LOW_LATENCY) success or burstEnabled (fallback for OEM burst
    //   throttling and doze). 0 = none. Set by requestHazardAdv(); hazardExpiryRunnable restores the normal mode.
    @Volatile private var hazardUntilMs = 0L

    // Dynamic payload: current sender STATE (2 bits, PSTATE_*), packed into one byte with Category and Turn by
    //   encodePayload() and carried as ServiceData.
    @Volatile private var currentState: Int = BleConstants.PSTATE_IDLE
    // Current transmitted turn direction (TURN_*, bits 3:2); startAdvertising packs it as the 2-bit Turn field.
    //   BleService periodically pushes ImuFusion.turnDirection in via updateTurn().
    @Volatile private var currentTurnDir: Int = BleConstants.TURN_STRAIGHT
    // Current transmitted risk state (2-bit RISK, LEVEL_*), packed into encodePayload bits[1:0].
    //   BleService periodically pushes its highest alertState level via updateRisk().
    //   The receiver decodes it with decodeRisk and combines it with its own RSSI gate (a compromise) to escalate
    //   its alert → two-way cooperative alerting.
    @Volatile private var currentRisk: Int = BleConstants.LEVEL_SAFE
    // Zone beacon contact (IN_ZONE) declaration, sent in the ServiceData extension byte (bit0).
    //   Pushed by the BleService zone state machine via updateInZone(). Receivers judge this device harmless (SAFE).
    @Volatile private var currentInZone = false
    @Volatile private var currentSos = false          // Lone-worker rescue request (extension byte bit1)
    @Volatile private var sosEpisode = 0              // Rescue request episode (service data byte2, 0 = none)
    @Volatile private var sosHint = 0                 // Short ID of the latest beacon (byte3-4, 0 = none)
    // Throttle timestamp shared by STATE and Turn re-advertising
    private var lastPayloadUpdateMs = 0L
    // Throttle timestamp for RISK only, independent of the STATE/TURN throttle.
    private var lastRiskUpdateMs = 0L
    // Keeps the UWB address across re-advertising (shared by updateState / restartWithUwbAddress)
    private var lastUwbAddress: ByteArray? = null
    // Mutual RSSI echo: encoded table of 'the RSSI I heard from each peer', carried in the scan response.
    //   BleService.pushRssiEcho pushes it via updateRssiEcho. desiredEcho = what we want to send (up to 8 entries);
    //   startAdvertising truncates it depending on whether UWB shares the packet (15B/24B). lastAdvertisedEcho = the
    //   value the last (re)advertisement carried (duplicate re-advertise guard); lastEchoAdvAt = throttle timestamp
    //   for echo-triggered re-advertising.
    @Volatile private var desiredEcho: ByteArray = ByteArray(0)
    private var lastAdvertisedEcho: ByteArray = ByteArray(0)
    private var lastEchoAdvAt = 0L
    private val stateHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Automatic retry after an advertising start failure.
    //   Advertising itself must continue even when paused (low power), so only stopped is guarded.
    private val retryRunnable = Runnable {
        retryScheduled = false
        if (stopped) return@Runnable
        Log.w(TAG, "광고 재시도 (연속 실패 ${advFailStreak}회)")
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        startAdvertising(currentDeviceId, lastUwbAddress)
    }

    private fun cancelRetry() {
        if (!retryScheduled) return
        stateHandler.removeCallbacks(retryRunnable)
        retryScheduled = false
    }

    private fun scheduleRetry() {
        if (stopped || retryScheduled) return
        val shift = (advFailStreak - 1).coerceIn(0, 5)
        val delay = (ADV_RETRY_BASE_MS shl shift).coerceAtMost(ADV_RETRY_MAX_MS)
        retryScheduled = true
        stateHandler.postDelayed(retryRunnable, delay)
        Log.w(TAG, "광고 재시도 예약: ${delay}ms 후")
    }

    // Rate-limit drop fix: holds the latest STATE the throttle kept from applying (-1 = none).
    //   IMU motion notifications arrive only at the moment of a transition, so a dropped one leaves a stale state
    //   until the next push (e.g. brief stop, moving off within the 1s throttle → FORWARD dropped → IDLE stays on
    //   air, so peers see 'standing by'). Holding it and retrying after the remaining time breaks the stuck state.
    //   All update paths run on the main looper, so no lock is needed.
    @Volatile private var pendingState = -1
    private val pendingStateRunnable = object : Runnable {
        override fun run() {
            val s = pendingState
            if (s < 0) return
            if (s == currentState) { pendingState = -1; return }   // Converged to the same state meanwhile; no re-advertise needed
            val now = SystemClock.elapsedRealtime()
            val remain = MIN_STATE_UPDATE_INTERVAL_MS - (now - lastPayloadUpdateMs)
            if (remain > 0) {
                // A turn re-advertise (updateTurn) refreshed the throttle timestamp meanwhile; wait out the remaining time again
                stateHandler.postDelayed(this, remain)
                return
            }
            pendingState = -1
            lastPayloadUpdateMs = now
            currentState = s
            Log.d(TAG, "보류 STATE 재시도 적용 → $s 재광고")
            restartAdvertise()
        }
    }

    // ── Read-only view of the local state currently being transmitted, for the own-device (Local) UI only ──
    //   BleService builds LocalState from these values and propagates it to MainActivity.
    //   Fully separate from the receive (Target) path; not writable from outside (read-only getters).
    val txCategory: Int  get() = category
    val txState: Int     get() = currentState
    val txTurnDir: Int   get() = currentTurnDir

    /**
     * @param uwbLocalAddress this device's UWB OOB payload: controller 4 bytes (address + channel + preamble),
     *   controlee 2 bytes (address); null = UWB unsupported or not initialized
     */
    fun startAdvertising(deviceId: String, uwbLocalAddress: ByteArray? = null) {
        // Even if a scheduled callback runs late after stop, it is blocked here.
        if (stopped) { Log.d(TAG, "중지 상태 — 광고 시작 생략"); return }
        currentDeviceId = deviceId
        if (uwbLocalAddress != null) lastUwbAddress = uwbLocalAddress
        // Asleep (paused): continuous LOW_POWER (~1s) advertising instead of a full stop, always transmitting at a low
        //   rate. Peer scanners always find me (within ~1s), so there is no wake-up delay. On wake (paused=false),
        //   promote to the active mode.
        // Active mode = mapping of the advertiseInterval setting, re-read at every (re)advertise, so sleep/wake and
        //   STATE/Turn re-advertising always apply the latest value. Sleep ignores the setting (LOW_POWER, the lowest
        //   public-API rate).
        // Priority: a burst (burstUntilMs in the future) sends LOW_LATENCY (~100ms) regardless of paused or settings,
        //   so peers find me immediately; then a hazard hold floors the mode at BALANCED; otherwise sleep = LOW_POWER
        //   and active = advertiseInterval mapping.
        val advertiseMode = when {
            SystemClock.elapsedRealtime() < burstUntilMs -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            // During a hazard hold, floor at BALANCED (~250ms) whether or not paused, so peers still discover me in time
            //   even if the burst failed or under doze. If base is faster (BALANCED or above), keep base.
            SystemClock.elapsedRealtime() < hazardUntilMs -> {
                val base = if (paused) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                           else mapAdvertiseMode(BleConstants.advertiseInterval)
                if (base == AdvertiseSettings.ADVERTISE_MODE_LOW_POWER) AdvertiseSettings.ADVERTISE_MODE_BALANCED else base
            }
            paused -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            else -> mapAdvertiseMode(BleConstants.advertiseInterval)
        }
        lastAppliedAdvertiseMode = advertiseMode   // For the refreshAdvertiseMode no-op check

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advertiseMode)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val companyId = if (prefix == BleConstants.DEVICE_PREFIX)
            BleConstants.COMPANY_ID_DEVICE else BleConstants.COMPANY_ID_WALKER

        // Keep the ID short (max 15 bytes: 5 Hangul or 15 Latin characters) so the whole packet stays ≤ 31 bytes.
        // During a rescue request (episode set) the service data is 5 bytes, so the ID is limited to 12 bytes; otherwise
        //   it stays 15 bytes.
        val sosExt = currentSos && sosEpisode != 0
        val idBytes = SosAdvert.idBytes(deviceId, sosExt)

        // ── Primary advertising packet (includes the ServiceUUID so scan filters still work with the screen off) ──
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID)))
            // ServiceData byte 0: Category+State+Turn+Risk packed 2-2-2-2 into a single byte.
            //   Turn takes 2 bits (bits 3:2); Risk (detected danger state, bits 1:0) enables two-way cooperative alerts.
            //   SERVICE_UUID uses the 16-bit short UUID pattern (0x1234), which keeps the ServiceData small (sizes below).
            .addServiceData(
                ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID)),
                // Extension flag byte: bit0=IN_ZONE (zone beacon contact declaration), bit1=SOS (lone-worker rescue request).
                //   The 1-byte state (2-2-2-2) is full, hence the extra byte. Older receivers read only byte[0], so it is
                //   harmless (backward compatible). ServiceData is 6B; with the 15B ID the total is 29B ≤ 31B.
                //   Only during a rescue request are byte2=episode and byte3-4=beacon short ID appended
                //   (ID limited to 12B → max 31B).
                SosAdvert.serviceData(
                    BleConstants.encodePayload(category, currentState, currentTurnDir, currentRisk),
                    BleConstants.encodeExt(currentInZone, currentSos),
                    if (sosExt) sosEpisode else 0,
                    sosHint
                )
            )
            .addManufacturerData(companyId, idBytes)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        // ── Scan response (separate 31B packet): carries the UWB address (0x9ABC) and the mutual RSSI echo (0xE0C0) ──
        // UWB: controller (DEVICE) = 4 bytes (address 2 + channel + preamble), controlee (WALKER) = 2 bytes (address).
        // Echo: desiredEcho, truncated by whether UWB is present (15B/24B, on 3B entry boundaries), rides alongside.
        val hasUwb = uwbLocalAddress != null && uwbLocalAddress.size >= 2
        val echoBudget = if (hasUwb) ECHO_MAX_BYTES_WITH_UWB else ECHO_MAX_BYTES_NO_UWB
        val echoToSend = if (desiredEcho.size <= echoBudget) desiredEcho
                         else desiredEcho.copyOf(echoBudget)   // 15 and 24 are multiples of 3, so entry boundaries are kept
        lastAdvertisedEcho = desiredEcho                       // Record the echo this (re)advertisement carries
        val scanResponse = if (hasUwb || echoToSend.isNotEmpty()) {
            val b = AdvertiseData.Builder().setIncludeDeviceName(false)
            if (hasUwb) b.addManufacturerData(BleConstants.COMPANY_ID_UWB_EXT, uwbLocalAddress!!.take(4).toByteArray())
            if (echoToSend.isNotEmpty()) b.addManufacturerData(BleConstants.COMPANY_ID_RSSI_ECHO, echoToSend)
            b.build()
        } else null

        // If startAdvertising itself throws (SecurityException = permission revoked, IllegalStateException = BT adapter
        //   shutting down), AdvertiseCallback never fires. Uncaught, the exception would propagate to the caller and
        //   transmission would stop with no retry and no notification.
        try {
            if (scanResponse != null) {
                advertiser.startAdvertising(settings, advertiseData, scanResponse, callback)
                Log.d(TAG, "광고+SR 시작: id=$deviceId uwb=${if (hasUwb) uwbLocalAddress!!.take(4).joinToString("") { "%02X".format(it) } else "-"} echo=${echoToSend.size / BleConstants.ECHO_ENTRY_SIZE}엔트리")
            } else {
                advertiser.startAdvertising(settings, advertiseData, callback)
                Log.d(TAG, "광고 시작: companyId=0x${companyId.toString(16).uppercase()} id=$deviceId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "광고 시작 호출 실패: ${e.message}")
            onStatusUpdate?.invoke("TX 실패: 송출 시작 오류")
            advFailStreak++
            scheduleRetry()
            if (advFailStreak >= ADV_FAIL_ESCALATE) {
                setFault("내 신호를 보내지 못하는 중 (송출 시작 오류)")
            }
        }
    }

    /**
     * Updates the sender STATE (2 bits, PSTATE_*).
     * Only on change, and at least MIN_STATE_UPDATE_INTERVAL_MS (1s) apart (to stay under the OS rate limit), it stops the current
     * advertisement and restarts it about 50ms later with the new payload (Category+State+Turn+Risk).
     * Category is fixed by the constructor and Turn is updated by updateTurn(); only STATE changes here.
     * A rate-limited update is not discarded: it is held (pendingState) and retried automatically after the
     *   remaining time. Dropping it would leave the opposite state on air until the next push, because the IMU notifies
     *   only at the moment of a transition (a moving device would appear to be 'standing by').
     */
    fun updateState(newState: Int) {
        val s = newState and 0b11
        if (s == currentState) {
            // Latest intent == currently transmitted state: a held retry for the opposite state is stale, so cancel it
            //   (e.g. a brief stop holds IDLE → movement resumes within the 1s throttle → the held IDLE must not apply while moving).
            if (pendingState >= 0) {
                pendingState = -1
                stateHandler.removeCallbacks(pendingStateRunnable)
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val wait = MIN_STATE_UPDATE_INTERVAL_MS - (now - lastPayloadUpdateMs)
        if (wait > 0) {
            // No re-update within MIN_STATE_UPDATE_INTERVAL_MS (1s): instead of dropping it, hold it and retry after the remaining time
            pendingState = s
            stateHandler.removeCallbacks(pendingStateRunnable)
            stateHandler.postDelayed(pendingStateRunnable, wait)
            Log.d(TAG, "상태 갱신 보류(Rate-Limit): STATE=$s — ${wait}ms 후 재시도")
            return
        }
        pendingState = -1                                  // Direct apply supersedes the held state
        stateHandler.removeCallbacks(pendingStateRunnable)
        lastPayloadUpdateMs = now
        currentState = s
        Log.d(TAG, "STATE 갱신 → $s (CAT=$category) 재광고")
        restartAdvertise()
    }

    /**
     * Updates the transmitted turn direction (2-bit Turn). BleService periodically pushes ImuFusion.turnDirection.
     *  - Same direction: ignored, avoiding needless stop/start storms (turn is discrete, so compare exactly).
     *  - Shares STATE's 1s rate-limit throttle (MIN_STATE_UPDATE_INTERVAL_MS; avoids colliding re-advertisements).
     *    A throttled update is skipped and retried on the next BleService TX poll (speedPushIntervalMs, default 0.5s).
     *  - On re-advertise, startAdvertising packs the latest currentState + currentTurnDir + currentRisk together.
     */
    fun updateTurn(turn: Int) {
        if (turn == currentTurnDir) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPayloadUpdateMs < MIN_STATE_UPDATE_INTERVAL_MS) return
        lastPayloadUpdateMs = now
        currentTurnDir = turn
        Log.d(TAG, "회전 갱신 → ${BleConstants.turnLabel(turn)} 재광고")
        restartAdvertise()
    }

    /**
     * Updates the transmitted risk state (2-bit RISK, LEVEL_*). BleService pushes its highest alertState
     *  level on every scan result and TX poll. Uses a throttle independent of STATE/TURN's 1s throttle, because
     *  risk is safety-critical.
     *  - Rise (e.g. SAFE→DANGER): ignores the throttle and re-advertises immediately, zero propagation delay.
     *  - Fall: 0.5s minimum interval (blocks stop/start storms when the level toggles). A throttled call is skipped
     *    and currentRisk is left as is; BleService calls again on the next scan result or TX poll (default 0.5s), so
     *    the first call after the throttle clears applies it (no pending runnable needed). A slightly late fall only
     *    keeps alarming longer on the safe side, never makes things more dangerous, so it is harmless.
     *  - restartAdvertise packs the latest currentState+currentTurnDir+currentRisk together.
     */
    fun updateRisk(level: Int) {
        val r = level.coerceIn(BleConstants.LEVEL_SAFE, BleConstants.LEVEL_DANGER)
        if (r == currentRisk) return
        val now = SystemClock.elapsedRealtime()
        val rising = r > currentRisk
        if (!rising && now - lastRiskUpdateMs < MIN_RISK_UPDATE_INTERVAL_MS) return  // A fall is not urgent; retried on the next poll
        currentRisk = r
        lastRiskUpdateMs = now
        Log.d(TAG, "위험상태 송출 갱신 → $r (상승=$rising) 재광고")
        restartAdvertise()
    }

    /**
     * Updates the transmitted zone beacon contact (IN_ZONE) flag. The BleService zone state machine pushes it on
     *  enter/exit transitions. Zone transitions are rare (enter after ZONE_MIN_SAMPLES samples, exit after 3
     *  samples below the 5dB hysteresis or on signal loss), so no separate throttle is needed. While asleep
     *  (paused) restartAdvertise is a no-op, but the field is still updated, so on wake startAdvertising sends the
     *  latest value automatically. Periodic reevaluateZones calls self-heal (same value = no-op).
     */
    fun updateInZone(inZone: Boolean) {
        if (stopped) return
        if (inZone == currentInZone) return
        currentInZone = inZone
        Log.d(TAG, "IN_ZONE 갱신 → $inZone 재광고")
        restartAdvertise()
    }

    /**
     * Updates the rescue request bit (extension byte bit1). No-op for the same value.
     *  Unlike updateInZone, this must re-advertise even while asleep (paused): a lone worker's phone has no
     *  neighbors and is usually in sleep mode, where restartAdvertise does nothing and the new value would never go
     *  out. So it restarts directly, the same way as pauseAdvertising (stop advertising → startAdvertising after a
     *  delay). startAdvertising sends at LOW_POWER when paused, so the sleep state is kept.
     *  If restart is false, only the values are set without re-advertising (to preload the state into a freshly
     *  created advertiser right before startAdvertising).
     */
    fun updateSos(sos: Boolean, episode: Int = 0, hint: Int = 0, restart: Boolean = true) {
        if (stopped) return
        val ep = if (sos) episode else 0
        val h = if (sos) hint else 0
        if (sos == currentSos && ep == sosEpisode && h == sosHint) return
        currentSos = sos
        sosEpisode = ep
        sosHint = h
        if (!restart) return
        Log.d(TAG, "SOS 갱신 → $sos 재광고")
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId, lastUwbAddress)
        }, STATE_RESTART_DELAY_MS)
    }

    /**
     * Updates the mutual RSSI echo: BleService encodes and pushes its table of 'the RSSI I heard from each peer'.
     *  Carried as 0xE0C0 manufacturer data in the scan response; each peer finds its own entry by its hash, so both
     *  sides judge symmetrically with the same sym=(rssi_A→B + rssi_B→A)/2.
     *  - Ignored if the same value is already on air (contentEquals guard).
     *  - 1.5s minimum-interval throttle: a throttled update is skipped and the next speedPush poll
     *    (speedPushIntervalMs, default 0.5s) retries (if the value still differs from what is on air, it passes
     *    then: self-heal). Independent of the STATE/RISK
     *    throttles.
     *  - When it passes, desiredEcho is updated and advertising restarts (restartAdvertise also repacks the latest
     *    STATE/TURN/RISK).
     */
    fun updateRssiEcho(newEcho: ByteArray) {
        if (stopped) return
        if (newEcho.contentEquals(lastAdvertisedEcho)) return   // Same echo already on air
        val now = SystemClock.elapsedRealtime()
        if (now - lastEchoAdvAt < MIN_ECHO_UPDATE_INTERVAL_MS) return  // Throttled; the next speedPush retries
        desiredEcho = newEcho
        lastEchoAdvAt = now
        restartAdvertise()
    }

    /** Stop, then re-advertise the latest payload after STATE_RESTART_DELAY_MS (shared by updateState/updateTurn). */
    private fun restartAdvertise() {
        if (stopped || paused) return   // While asleep, STATE/Turn updates do not wake advertising
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId, lastUwbAddress)
        }, STATE_RESTART_DELAY_MS)
    }

    // ── Warning-zone entry burst ──────────────────────────────────────────
    //   When a nearby peer is received (rssi≥WAKE), BleService.noteRssiForWake calls requestBurst →
    //   my advertising speeds up to LOW_LATENCY (~100ms) so the peer discovers me sooner (mutual protection).
    //   While the peer stays close, each reception extends it by hold; once apart, it expires to normal mode.

    // Burst expiry: if burstUntilMs has passed, re-advertise in the normal mode (restartAdvertise: the advertiseInterval
    //   mapping when awake — BALANCED at the default 200ms — no-op when paused). If a longer burst extended it (still
    //   in the future), do nothing.
    private val burstExpiryRunnable = Runnable {
        if (stopped) return@Runnable
        if (SystemClock.elapsedRealtime() >= burstUntilMs) restartAdvertise()
    }

    // Warning-zone burst request: speed advertising up to LOW_LATENCY for durationMs.
    //   Ignored if an equal or longer burst is already running (no extension or re-advertise needed). While asleep
    //   (paused), only the expiry timer is set, because on the coming wake startAdvertising sees burstUntilMs and
    //   starts at LOW_LATENCY. When awake and not yet at LOW_LATENCY, re-advertise immediately to speed up.
    fun requestBurst(durationMs: Long) {
        if (stopped) return
        val newUntil = SystemClock.elapsedRealtime() + durationMs
        if (newUntil <= burstUntilMs) return
        burstUntilMs = newUntil
        stateHandler.removeCallbacks(burstExpiryRunnable)
        stateHandler.postDelayed(burstExpiryRunnable, durationMs)
        if (paused) return
        if (lastAppliedAdvertiseMode != AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) restartAdvertise()
    }

    // ── Hazard advertising promotion ──────────────────────────────────────
    //   Even if the burst (LOW_LATENCY) fails due to OEM advertising-set throttling or doze, keep advertising at
    //   least BALANCED (~250ms) while WAKE is being received, recovering the peer's discovery delay
    //   (simulation: burst failure +2.68s→+0.41s, ~85% recovered).
    //   Independent of the burst (burstEnabled does not matter). Its purpose is promotion while asleep (paused), so
    //   there is no paused guard.

    private val hazardExpiryRunnable = Runnable {
        if (stopped) return@Runnable
        if (SystemClock.elapsedRealtime() < hazardUntilMs) return@Runnable   // Extended; the new timer handles it
        // Expired: recompute the normal mode due now; re-advertise only if it differs from the applied mode
        val target = when {
            SystemClock.elapsedRealtime() < burstUntilMs -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            paused -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            else -> mapAdvertiseMode(BleConstants.advertiseInterval)
        }
        if (target != lastAppliedAdvertiseMode) hazardRestart()
    }

    /**
     * Called on every reception at or above the WAKE threshold (wakeRssiDbm, default -95): extends the hazard
     * hold by 5s and, if advertising is stuck at LOW_POWER, re-advertises immediately to promote it to BALANCED
     * or faster. Since it is called on every such reception, it does not repeat stop/start once promoted (it only
     * extends the timer).
     */
    fun requestHazardAdv() {
        if (stopped) return
        val newUntil = SystemClock.elapsedRealtime() + HAZARD_ADV_HOLD_MS
        if (newUntil > hazardUntilMs) {
            hazardUntilMs = newUntil
            stateHandler.removeCallbacks(hazardExpiryRunnable)
            stateHandler.postDelayed(hazardExpiryRunnable, HAZARD_ADV_HOLD_MS)
        }
        if (lastAppliedAdvertiseMode == AdvertiseSettings.ADVERTISE_MODE_LOW_POWER) hazardRestart()
    }

    /** Hazard version of restartAdvertise: re-advertises even while asleep (paused), since promotion is the point. */
    private fun hazardRestart() {
        if (stopped) return
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId, lastUwbAddress)
        }, STATE_RESTART_DELAY_MS)
    }

    /**
     * Applies the advertiseInterval setting live; called by BleService's prefs listener.
     *  Skipped while asleep (paused), which is fixed at LOW_POWER anyway (on wake, startAdvertising applies the new
     *  mapping automatically). Re-advertises only while actively advertising and only if the mapped mode actually
     *  changed (no-op guard: safe for unrelated setting keys and resetToDefault).
     */
    fun refreshAdvertiseMode() {
        if (stopped || paused) return
        val target = mapAdvertiseMode(BleConstants.advertiseInterval)
        if (target == lastAppliedAdvertiseMode) return
        Log.d(TAG, "광고 모드 라이브 갱신 → 간격설정 ${BleConstants.advertiseInterval}ms")
        restartAdvertise()
    }

    /** Restart advertising once the UWB address is ready */
    fun restartWithUwbAddress(uwbLocalAddress: ByteArray) {
        // Record the sticky address before the guard, so an update during sleep (paused) is not lost and on wake
        //   startAdvertising(currentDeviceId, lastUwbAddress) sends the latest address.
        lastUwbAddress = uwbLocalAddress
        if (stopped || paused) return   // While asleep, a UWB restart does not wake advertising either
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        // Use stateHandler rather than a dedicated Handler, so the single removeCallbacksAndMessages(null) in
        //   stopAdvertising() cancels every scheduled re-advertise.
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId, uwbLocalAddress)
        }, 300)
    }

    /** After the UWB session ends, restart advertising without the UWB scan response; also clears the sticky address */
    fun restartWithoutUwbAddress() {
        lastUwbAddress = null                // Clear the address even while asleep so wake re-advertises without UWB
        if (stopped || paused) return
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId)
        }, 300)
    }

    // ── RSSI dynamic sleep/wake ──────────────────
    //   Sleep means continuous LOW_POWER advertising: never a full stop, always transmitting at a low rate (~1s),
    //   so peers rediscover this device immediately and there is no wake-up delay.

    /**
     * Enters RSSI sleep: lowers advertising to continuous LOW_POWER rather than stopping it.
     * After setting paused=true and re-advertising, startAdvertising keeps transmitting in LOW_POWER (~1s) mode.
     * Peer scanners can always catch this device, so there is no rediscovery (→ wake) delay.
     * Independent of stopped (permanent shutdown); resumeAdvertising() promotes back to the active mode immediately at
     * any time.
     */
    fun pauseAdvertising() {
        if (stopped || paused) return
        paused = true
        burstUntilMs = 0L                                  // Entering sleep ends the burst (nearby signal gone)
        stateHandler.removeCallbacksAndMessages(null)      // Cancel all scheduled re-advertise callbacks
        retryScheduled = false                             // Reflect that the retry was cleared above too
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        // Re-advertise after the stop→start OS cleanup wait; since paused=true, startAdvertising sends continuous
        //   LOW_POWER. But while a hazard hold is active, the BALANCED floor stays (sleep entered with a hazard near).
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId, lastUwbAddress)
        }, STATE_RESTART_DELAY_MS)
        // All callbacks were cleared above, so re-arm the expiry timer if a hazard hold is in progress
        val hazardRemain = hazardUntilMs - SystemClock.elapsedRealtime()
        if (hazardRemain > 0) stateHandler.postDelayed(hazardExpiryRunnable, hazardRemain)
        Log.d(TAG, "RSSI 슬립 진입 — LOW_POWER 연속 광고로 전환(상시 저빈도 송출)")
    }

    /**
     * RSSI wake: promotes to continuous active-mode advertising immediately (0ms).
     * After setting paused=false and re-advertising, startAdvertising picks the active mode (advertiseInterval
     * mapping; LOW_LATENCY during a burst). The latest currentState/currentTurnDir at resume time are packed
     * as-is, giving a 'strong LocalState broadcast' (no postDelayed).
     */
    fun resumeAdvertising() {
        if (stopped) return
        val wasPaused = paused
        paused = false
        stateHandler.removeCallbacksAndMessages(null)      // Remove callbacks scheduled by the sleep side
        retryScheduled = false                             // Scheduled retry is cleared as well
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        startAdvertising(currentDeviceId, lastUwbAddress)  // Continuous advertising ON at 0ms (LOW_LATENCY during a burst)
        // All callbacks were cleared above, so re-arm the expiry timer if a burst was in progress.
        val burstRemain = burstUntilMs - SystemClock.elapsedRealtime()
        if (burstRemain > 0) stateHandler.postDelayed(burstExpiryRunnable, burstRemain)
        // Same for the hazard hold: re-arm its expiry timer
        val hazardRemain = hazardUntilMs - SystemClock.elapsedRealtime()
        if (hazardRemain > 0) stateHandler.postDelayed(hazardExpiryRunnable, hazardRemain)
        if (wasPaused) Log.d(TAG, "RSSI 웨이크 — 즉시 연속 광고로 승격(LocalState 강송출)")
    }

    fun stopAdvertising() {
        // Key guard: keeps scheduled re-advertises (postDelayed from restartAdvertise/updateState/updateTurn/
        //   restartWithUwbAddress) from reviving advertising right after stop.
        stopped = true
        stateHandler.removeCallbacksAndMessages(null)
        retryScheduled = false                             // Clear the scheduled retry
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        Log.d(TAG, "광고 중지(예약 재광고 취소 포함)")
    }
}
