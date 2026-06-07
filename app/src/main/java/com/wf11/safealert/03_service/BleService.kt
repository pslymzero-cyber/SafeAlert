package com.wf11.safealert.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.wf11.safealert.ble.BleAdvertiser
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.ble.BleScanner
import com.wf11.safealert.ble.BleScanCallback
import com.wf11.safealert.ble.BlePendingScanner
import com.wf11.safealert.ble.KalmanFilter
import com.wf11.safealert.ble.RssiPreFilter
import com.wf11.safealert.firebase.FirebaseManager
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.ImuFusion
import com.wf11.safealert.utils.OverlayManager
import com.wf11.safealert.utils.UwbRanger
import androidx.lifecycle.lifecycleScope
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
        const val EXTRA_ID             = "extra_id"
        const val EXTRA_ALERT_LEVEL    = "extra_alert_level"
        const val EXTRA_DISPLAY_NAME   = "extra_display_name"
        const val EXTRA_RSSI           = "extra_rssi"
        const val EXTRA_STATUS         = "extra_status"
        const val EXTRA_FROM_PENDING        = "extra_from_pending"         // PendingIntent에서 기동됨 표시
        const val EXTRA_INITIAL_DEVICE_IDS = "extra_initial_device_ids"  // 기상 원인 기기 로컬 네임 목록
        const val EXTRA_INITIAL_RSSIS      = "extra_initial_rssis"       // 기상 원인 RSSI 목록 (IntArray)
        const val BROADCAST_ALERT      = "com.wf11.safealert.ALERT"
        const val BROADCAST_DETECTED   = "com.wf11.safealert.DETECTED"
        const val BROADCAST_BLE_STATUS = "com.wf11.safealert.BLE_STATUS"
        private const val CHANNEL_ID   = "safealert_channel"
        private const val NOTIF_ID     = 1001

        @Volatile var lastStatus: String   = ""
        @Volatile var bleScanCount: Int    = 0
        @Volatile var safeAlertFound: Int  = 0
        @Volatile var isRunning: Boolean   = false
        @Volatile var isMutedPublic: Boolean = false
    }

    private var bleAdvertiser: BleAdvertiser? = null
    private var bleScanner: BleScanner? = null
    private var uwbRanger: UwbRanger? = null
    private var myId   = ""
    private var myMode = ""
    private var testRunnable: Runnable? = null
    private val testHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val MUTE_DURATION_MS = 10_000L
    private val muteHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var isMuted = false

    @Volatile private var activeSoundLevel = BleConstants.LEVEL_SAFE

    private fun getCurrentMaxLevel() =
        alertState.values.maxOfOrNull { it.first } ?: BleConstants.LEVEL_SAFE

    @Volatile private var ignoringVolumeChange = false

    // ── RssiPreFilter: IQR → Max-Hold 전처리 파이프라인 ──────────────
    // 칼만 필터 입력 전 다중경로 페이딩·신체 차폐 노이즈 제거
    // 파라미터: windowSize=8, minSamples=4, maxHoldRatio=0.20, iqrMultiplier=1.5, bypass=6
    private val rssiPreFilter = RssiPreFilter()

    // ── 대기 모드 전환: IDLE_TO_PENDING_MS 경과 시 PendingIntent 스캔으로 전환 ──
    // 기기 소실 후 30초 무감지 → BleService 자동 정지 + BlePendingScanner 기동
    // 목적: 8시간 교대 중 휴식·이동 구간에서 ForegroundService 자원 완전 해방
    private val IDLE_TO_PENDING_MS = 30_000L
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleRunnable = Runnable { handleIdleTransition() }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isMuted || ignoringVolumeChange) return
            muteTemporarily("볼륨 버튼")
        }
    }

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

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "블루투스 켜짐 → BLE 재시작")
                    sendStatusBroadcast("블루투스 켜짐 → BLE 재시작")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        stopBle()
                        applyMode()
                    }, 1000)
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.d(TAG, "블루투스 꺼짐")
                    sendStatusBroadcast("⚠ 블루투스 꺼짐")
                    stopBle()
                }
            }
        }
    }

    private val alertState = mutableMapOf<String, Pair<Int, Long>>()

    private val WARNING_COOLDOWN_MS    = 3000L
    private val DANGER_COOLDOWN_MS     = 2000L
    private val SCAN_HEALTH_CHECK_MS   = 15_000L

    @Volatile private var lastScanResultMs = 0L
    private val healthCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ── 2D 칼만 필터 맵 (v1.0.20: KalmanFilter, 거리+속도 동시 추적) ──
    private val kalmanFilters = mutableMapOf<String, KalmanFilter>()

    // ── TTC 파라미터 ──────────────────────────────────────────────────
    private val TTC_THRESHOLD_SEC      = 8.0
    private val MIN_APPROACH_SPEED_MS  = 0.3   // TTC 계산 최소 접근 속도 (m/s)

    // ── 기기별 추적 상태 머신 (v1.0.20) ──────────────────────────────
    enum class TrackingState { APPROACHING, CROSSING, DEPARTING }
    private val trackingStateMap   = mutableMapOf<String, TrackingState>()
    private val crossingStartMap   = mutableMapOf<String, Long>()    // CROSSING 진입 시각
    private val departingStartMap  = mutableMapOf<String, Long>()    // DEPARTING 진입 시각

    // 상태 전환 파라미터
    private val CPA_VEL_THRESHOLD             = 0.2   // CPA 판정 속도 임계 (m/s)
    private val CROSSING_CONFIRM_MS           = 1500L // CROSSING → DEPARTING 확정 대기
    private val DEPARTING_REENTRY_COOLDOWN_MS = 5000L // DEPARTING 후 재진입 최소 대기
    private val DEPARTING_HYSTERESIS_DBM      = 8     // DEPARTING 중 재경보 추가 마진 (dBm)

    // ── 1초 평균 버퍼 ──────────────────────────────────────────────
    private val oneSecBuffer = mutableMapOf<String, ArrayDeque<Pair<Long, Int>>>()

    private fun oneSecAvgRssi(deviceId: String, rssi: Int): Int {
        val now = System.currentTimeMillis()
        val buf = oneSecBuffer.getOrPut(deviceId) { ArrayDeque() }
        buf.addLast(Pair(now, rssi))
        while (buf.isNotEmpty() && now - buf.first().first > 1000L) buf.removeFirst()
        return if (buf.isEmpty()) rssi else buf.map { it.second }.average().toInt()
    }

    private val ttcFeedbackMap = mutableMapOf<String, Pair<Double, Long>>()
    private val LEARN_RATE = 0.05f

    private val detectedThrottleMap = mutableMapOf<String, Long>()

    private val recedingStartMap = mutableMapOf<String, Long>()
    private val RECEDING_CLEAR_MS = 2500L

    private val peakAlertRssiMap  = mutableMapOf<String, Int>()
    private val RECEDING_DBM_DROP = 5

    private val HYSTERESIS_DBM = 5

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(volumeReceiver,  IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)
        ImuFusion.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // null intent = START_STICKY 재시작 → SharedPrefs 복원
        if (intent?.action == null && myMode.isEmpty()) {
            val prefs     = getSharedPreferences("safealert_prefs", MODE_PRIVATE)
            val savedMode = prefs.getString("running_mode", null)
            if (savedMode != null) {
                myId   = prefs.getString("device_id", "SA-DEFAULT") ?: "SA-DEFAULT"
                myMode = savedMode
                val title = if (savedMode == "DEVICE") "장비 작업자 실행 중" else "보행자 실행 중"
                startForeground(NOTIF_ID, buildNotification(title, "재시작됨"))
                applyMode()
            }
            return START_STICKY
        }

        when (intent?.action) {
            ACTION_START_DEVICE -> {
                myId   = intent.getStringExtra(EXTRA_ID) ?: "DEVICE_001"
                myMode = "DEVICE"
                // 모드 저장: 대기 전환 후 재기동 시 BleDetectedReceiver가 복원에 사용
                saveRunningMode(myMode, myId)
                startForeground(NOTIF_ID, buildNotification(
                    "장비 작업자 실행 중",
                    buildSubText(DevSettings.deviceTx, DevSettings.deviceRx)
                ))
                injectFromPendingIntent(intent)  // Cold Start 딜레이 제거: 필터 즉시 웜업
                applyMode()
            }
            ACTION_START_WALKER -> {
                myId   = intent.getStringExtra(EXTRA_ID) ?: "WALKER_001"
                myMode = "WALKER"
                saveRunningMode(myMode, myId)
                startForeground(NOTIF_ID, buildNotification(
                    "보행자 실행 중",
                    buildSubText(DevSettings.walkerTx, DevSettings.walkerRx)
                ))
                injectFromPendingIntent(intent)  // Cold Start 딜레이 제거: 필터 즉시 웜업
                applyMode()
            }
            ACTION_STOP       -> stopAll()
            ACTION_TEST_START -> startTestAlert()
            ACTION_TEST_STOP  -> stopTestAlert()
            ACTION_MUTE_TEMP  -> muteTemporarily("화면 터치")
            ACTION_UNMUTE     -> unmuteImmediately()
        }
        return START_STICKY
    }

    /** 모드와 ID를 SharedPrefs에 저장 (BleDetectedReceiver 복원용) */
    private fun saveRunningMode(mode: String, id: String) {
        getSharedPreferences("safealert_prefs", MODE_PRIVATE).edit()
            .putString("running_mode", mode)
            .putString("device_id", id)
            .apply()
    }

    /**
     * PendingIntent 기상 시 BleDetectedReceiver가 전달한 초기 스캔 데이터를 필터에 즉시 주입.
     * Cold Start 딜레이 제거: 콜백 스캐너의 첫 수신 결과가 도착하기 전에
     * RssiPreFilter 윈도우와 KalmanFilter를 미리 웜업 → 서비스 기동 즉시 경보 판단 가능.
     */
    private fun injectFromPendingIntent(intent: Intent) {
        val ids   = intent.getStringArrayListExtra(EXTRA_INITIAL_DEVICE_IDS) ?: return
        val rssis = intent.getIntArrayExtra(EXTRA_INITIAL_RSSIS)              ?: return
        if (ids.size != rssis.size) return
        Log.d(TAG, "필터 웜업 주입 시작: ${ids.size}개 기기")
        ids.forEachIndexed { i, deviceId -> injectInitialSample(deviceId, rssis[i]) }
    }

    /**
     * 단일 기기의 초기 RSSI 샘플을 RssiPreFilter와 KalmanFilter에 직접 주입.
     *
     * RssiPreFilter: MIN_SAMPLES_DEFAULT(4)개 동일 값으로 채움 → 샘플 부족 폴백 없이 즉시 IQR 통과.
     * KalmanFilter: errorCovariance=5.0으로 초기화 → 기본값(100.0) 대비 초기값 신뢰도 높음.
     */
    private fun injectInitialSample(deviceId: String, rssi: Int) {
        repeat(RssiPreFilter.MIN_SAMPLES_DEFAULT) {
            rssiPreFilter.push(deviceId, rssi, 0.0)
        }
        val kf = KalmanFilter(DevSettings.kalmanPreset)
        kf.injectWarmup(rssi)
        kalmanFilters[deviceId] = kf
        trackingStateMap[deviceId] = TrackingState.APPROACHING
        Log.d(TAG, "  웜업 완료 [$deviceId] rssi=$rssi")
    }

    private fun applyMode() {
        // 오버랩 스캔: stopPendingScanner()는 콜백 스캐너 startScanning() 직후에 호출
        // → PendingIntent와 콜백 스캔이 순간 동시 활성 → 블라인드 타임 = 0
        // 오류 경로(BT null/꺼짐)에서는 즉시 중지

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter

        val modeStr = if (DevSettings.detectionMode == DevSettings.MODE_FIXED_AVG)
            "고정값(위험|${DevSettings.fixedDangerAbs}|경고|${DevSettings.fixedWarningAbs})"
        else
            "칼만(위험${DevSettings.dangerDistM.toInt()}m≈${BleConstants.rssiDanger}dBm 경고${DevSettings.warningDistM.toInt()}m≈${BleConstants.rssiWarning}dBm)"
        Log.i(TAG, "=== BLE 임계값 확인: $modeStr ===")
        sendStatusBroadcast("설정: $modeStr")

        if (btAdapter == null) {
            stopPendingScanner()
            sendStatusBroadcast("❌ 블루투스 미지원 기기")
            return
        }
        if (!btAdapter.isEnabled) {
            stopPendingScanner()
            sendStatusBroadcast("❌ 블루투스 꺼짐 — 켜주세요")
            return
        }

        val doTx = if (myMode == "DEVICE") DevSettings.deviceTx else DevSettings.walkerTx
        val doRx = if (myMode == "DEVICE") DevSettings.deviceRx else DevSettings.walkerRx

        if (doTx) {
            val advertiser = btAdapter.bluetoothLeAdvertiser
            if (advertiser != null) {
                val prefix = if (myMode == "DEVICE") BleConstants.DEVICE_PREFIX else BleConstants.WALKER_PREFIX
                val bleAdv = BleAdvertiser(advertiser, prefix,
                    onStatusUpdate = { msg -> sendStatusBroadcast(msg) }
                )
                bleAdvertiser = bleAdv
                if (UwbRanger.isHardwareSupported(this)) {
                    val isDevMode = myMode == "DEVICE"
                    uwbRanger = UwbRanger(this, lifecycleScope, isDevMode)
                    lifecycleScope.launch {
                        val uwbAddr = uwbRanger?.initSession()
                        if (uwbAddr != null) {
                            bleAdv.restartWithUwbAddress(uwbAddr)
                            sendStatusBroadcast("UWB 활성: ${uwbAddr.joinToString("") { "%02X".format(it) }}")
                        } else {
                            bleAdv.startAdvertising(myId)
                        }
                    }
                } else {
                    bleAdv.startAdvertising(myId)
                }
                sendStatusBroadcast("TX 송출 요청: $myId")
                Log.d(TAG, "TX 시작: $prefix$myId")
            } else {
                sendStatusBroadcast("❌ TX 오류: 이 기기는 BLE 광고 미지원")
                Log.e(TAG, "TX: bluetoothLeAdvertiser null")
            }
        }

        if (doRx) {
            val scanner = btAdapter.bluetoothLeScanner
            if (scanner != null) {
                lastScanResultMs = System.currentTimeMillis()
                startScanHealthCheck()
                bleScanner = BleScanner(scanner).also { s ->
                    s.onStatusUpdate = { msg -> sendStatusBroadcast(msg) }
                    s.roleIsWalker = (myMode == "WALKER")
                    s.startScanning(object : BleScanCallback {
                        override fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int) {
                            lastScanResultMs = System.currentTimeMillis()
                            cancelIdleTimer()  // 기기 감지 → 대기 전환 타이머 취소

                            if (myMode == "WALKER"
                                && deviceId.startsWith(BleConstants.WALKER_PREFIX)
                                && !DevSettings.walkerDetectsWalker) return

                            val effectiveRssi = if (DevSettings.debugMode) DevSettings.simulatedRssi else rssi
                            val shortId = deviceId
                                .replace(BleConstants.DEVICE_PREFIX, "D:")
                                .replace(BleConstants.WALKER_PREFIX, "W:")
                            sendStatusBroadcast("감지 $shortId  RSSI $effectiveRssi dBm")
                            processAlert(deviceId, effectiveRssi)
                        }
                        override fun onDeviceLost(deviceId: String) {
                            Log.d(TAG, "신호 소실: $deviceId")
                            alertState.remove(deviceId)
                            rssiPreFilter.clear(deviceId)
                            kalmanFilters[deviceId]?.reset()
                            kalmanFilters.remove(deviceId)
                            trackingStateMap.remove(deviceId)
                            crossingStartMap.remove(deviceId)
                            departingStartMap.remove(deviceId)
                            wasStationaryMap.remove(deviceId)
                            recedingStartMap.remove(deviceId)
                            peakAlertRssiMap.remove(deviceId)
                            sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                            if (alertState.isEmpty()) {
                                AlertSoundPlayer.stopSound()
                                VibrationHelper.stopVibration(this@BleService)
                                OverlayManager.hideOverlay()
                                activeSoundLevel = BleConstants.LEVEL_SAFE
                                sendStatusBroadcast("기기 이탈 → 경보 중지")
                                scheduleIdleTransition()  // 30초 후 PendingIntent 전환
                            }
                        }
                        override fun onScanError(errorCode: Int) { Log.e(TAG, "스캔 오류: $errorCode") }
                        override fun onUwbAddressReceived(deviceId: String, uwbAddress: ByteArray) {
                            uwbRanger?.onPeerUwbAddressReceived(deviceId, uwbAddress)
                        }
                    })
                }
                // 오버랩 스캔: 콜백 스캐너 start 직후 PendingIntent 중지
                // → 두 스캐너가 순간 동시 활성 → 블라인드 타임 0
                stopPendingScanner()
                sendStatusBroadcast("RX 스캔 시작")
                Log.d(TAG, "RX 시작")
            } else {
                stopPendingScanner()  // 콜백 스캐너 기동 실패 시에도 PendingIntent 정리
                sendStatusBroadcast("❌ RX 오류: BluetoothLeScanner null")
                Log.e(TAG, "RX: bluetoothLeScanner null")
            }
        } else {
            stopPendingScanner()  // RX 비활성 시에도 PendingIntent 정리
        }
    }

    private fun calcLevelWithHysteresis(deviceId: String, rssi: Int, rssiOffset: Int = 0): Int {
        val prevLevel = alertState[deviceId]?.first ?: BleConstants.LEVEL_SAFE
        val warning   = BleConstants.rssiWarning - rssiOffset
        return when {
            rssi >= warning -> BleConstants.LEVEL_WARNING
            prevLevel == BleConstants.LEVEL_WARNING && rssi >= warning - HYSTERESIS_DBM -> BleConstants.LEVEL_WARNING
            else -> BleConstants.LEVEL_SAFE
        }
    }

    private val wasStationaryMap  = mutableMapOf<String, Boolean>()

    /**
     * TTC 추정 — 2D 칼만 속도(m/s) 직접 사용 (v1.0.20)
     * 10샘플 선형 회귀 방식 대체.
     * @param kfDist 추정 거리 (m)
     * @param kfVel  추정 속도 (m/s, 음수=접근)
     */
    private fun estimateTTC(kfDist: Double, kfVel: Double): Double? {
        if (kfVel >= -MIN_APPROACH_SPEED_MS) return null  // 접근 중 아님 or 속도 미달
        val calib      = DevSettings.calibRssiAt1m.toDouble()
        val n          = DevSettings.pathLossExp.toDouble()
        val dangerDist = Math.pow(10.0, (calib - BleConstants.rssiDanger) / (10.0 * n))
        val remaining  = kfDist - dangerDist
        if (remaining <= 0) return 0.0
        val ttc = remaining / (-kfVel)
        Log.d(TAG, "TTC: dist=%.1fm vel=%.2fm/s TTC=%.1fs".format(kfDist, kfVel, ttc))
        return ttc
    }

    /** 추적 상태 머신 갱신 (v1.0.20) */
    private fun updateTrackingState(deviceId: String, kfVel: Double, now: Long) {
        val current = trackingStateMap[deviceId] ?: TrackingState.APPROACHING
        when (current) {
            TrackingState.APPROACHING -> {
                if (kfVel > CPA_VEL_THRESHOLD) {
                    crossingStartMap[deviceId] = now
                    trackingStateMap[deviceId] = TrackingState.CROSSING
                    Log.d(TAG, "[$deviceId] APPROACHING → CROSSING (vel=%.2fm/s)".format(kfVel))
                }
            }
            TrackingState.CROSSING -> {
                when {
                    kfVel <= 0.0 -> {
                        // 속도 다시 음수 → CPA 오판, APPROACHING 복귀
                        crossingStartMap.remove(deviceId)
                        trackingStateMap[deviceId] = TrackingState.APPROACHING
                        Log.d(TAG, "[$deviceId] CROSSING → APPROACHING (vel=%.2fm/s)".format(kfVel))
                    }
                    now - (crossingStartMap[deviceId] ?: now) >= CROSSING_CONFIRM_MS -> {
                        // 양수 속도 지속 확인 → DEPARTING 확정
                        crossingStartMap.remove(deviceId)
                        departingStartMap[deviceId] = now
                        trackingStateMap[deviceId] = TrackingState.DEPARTING
                        Log.d(TAG, "[$deviceId] CROSSING → DEPARTING 확정")
                        sendStatusBroadcast("↗ 이탈 확인: ${extractDisplayName(deviceId)}")
                    }
                }
            }
            TrackingState.DEPARTING -> {
                val timeDep = now - (departingStartMap[deviceId] ?: now)
                // 쿨다운 경과 후 강한 재접근 속도 지속 → APPROACHING 복귀
                if (timeDep >= DEPARTING_REENTRY_COOLDOWN_MS && kfVel < -(CPA_VEL_THRESHOLD * 3)) {
                    departingStartMap.remove(deviceId)
                    trackingStateMap[deviceId] = TrackingState.APPROACHING
                    Log.d(TAG, "[$deviceId] DEPARTING → APPROACHING (재진입 vel=%.2fm/s)".format(kfVel))
                }
            }
        }
    }

    /**
     * DEPARTING 상태 재진입 히스테리시스 (v1.0.20, 기둥 반사 신호 바운스 차단)
     * - 쿨다운 중: 모든 경보 억제
     * - 쿨다운 후: WARNING 허용, DANGER는 DEPARTING_HYSTERESIS_DBM 추가 마진 필요
     */
    private fun applyDepartingHysteresis(
        deviceId: String, rawLevel: Int, blended: Int, offset: Int, now: Long
    ): Int {
        val state = trackingStateMap[deviceId] ?: return rawLevel
        if (state != TrackingState.DEPARTING) return rawLevel
        val timeDep = now - (departingStartMap[deviceId] ?: now)
        return when {
            timeDep < DEPARTING_REENTRY_COOLDOWN_MS -> BleConstants.LEVEL_SAFE
            rawLevel >= BleConstants.LEVEL_DANGER -> {
                val thresh = BleConstants.rssiDanger + DEPARTING_HYSTERESIS_DBM - offset
                if (blended >= thresh) rawLevel else BleConstants.LEVEL_WARNING
            }
            else -> rawLevel
        }
    }

    private fun processAlert(deviceId: String, rssi: Int) {
        // UWB 거리 우선: ToF 거리를 RSSI 등가값으로 변환
        val inputRssi: Int = run {
            val uwbDist = uwbRanger?.uwbDistances?.get(deviceId)
            if (uwbDist != null && uwbDist > 0f) {
                val calib = DevSettings.calibRssiAt1m.toDouble()
                val n     = DevSettings.pathLossExp.toDouble()
                (calib - 10.0 * n * Math.log10(uwbDist.toDouble())).toInt()
            } else rssi
        }

        val mode     = DevSettings.detectionMode
        val auxRatio = DevSettings.blendRatio / 100.0
        val now      = System.currentTimeMillis()

        // ── 현재 추적 상태 (RssiPreFilter 억제 판단용) ─────────────────
        val currentState = trackingStateMap[deviceId] ?: TrackingState.APPROACHING
        val isDeparting  = currentState == TrackingState.DEPARTING

        // ── 2D 칼만 필터 가져오기 또는 생성 ──────────────────────────────
        val kf = kalmanFilters.getOrPut(deviceId) {
            KalmanFilter(DevSettings.kalmanPreset)
        }
        kf.updatePreset(DevSettings.kalmanPreset)

        // ── RssiPreFilter: isStationary + isDeparting 전달 (v1.0.20) ────
        // 지게차 정지 중 or 이탈 방향이면 급접근 바이패스 억제
        val kalmanEstRssi = if (kf.isInitialized) kf.estimatedRssi().toDouble() else 0.0
        val preFiltered = rssiPreFilter.push(
            deviceId, inputRssi, kalmanEstRssi,
            ImuFusion.isStationary, isDeparting
        )

        // ── 2D 칼만 필터 업데이트 ────────────────────────────────────────
        val (kfDist, kfVel) = kf.update(preFiltered, ImuFusion.adaptiveQFactor)
        val kalmanRssi = kf.estimatedRssi()

        // ── 추적 상태 머신 갱신 (v1.0.20) ───────────────────────────────
        updateTrackingState(deviceId, kfVel, now)
        val newState    = trackingStateMap[deviceId] ?: TrackingState.APPROACHING
        val isNowDepart = newState == TrackingState.DEPARTING

        val avg1sec = oneSecAvgRssi(deviceId, inputRssi)   // 1초 평균은 raw 기준 유지

        val stableLevel: Int
        val avgRssi: Int

        if (mode == DevSettings.MODE_FIXED_AVG) {
            val blended = (avg1sec * (1.0 - auxRatio) + kalmanRssi * auxRatio).toInt()
            avgRssi = blended
            val warningThresh = -DevSettings.fixedWarningAbs
            // DEPARTING 쿨다운 중: 경보 완전 억제 / 이후: 강화된 임계 적용
            val effectiveThresh = if (isNowDepart) {
                val timeDep = now - (departingStartMap[deviceId] ?: now)
                if (timeDep < DEPARTING_REENTRY_COOLDOWN_MS) Int.MIN_VALUE
                else warningThresh - DEPARTING_HYSTERESIS_DBM
            } else warningThresh
            stableLevel = if (blended >= effectiveThresh) BleConstants.LEVEL_WARNING else BleConstants.LEVEL_SAFE
        } else {
            val blended = (kalmanRssi * (1.0 - auxRatio) + avg1sec * auxRatio).toInt()
            avgRssi = blended
            val beaconOffset = runCatching { BeaconRegistry.getRssiOffsetForFullId(deviceId) }.getOrDefault(0)
            val rawLevel = calcLevelWithHysteresis(deviceId, blended, beaconOffset)
            stableLevel = applyDepartingHysteresis(deviceId, rawLevel, blended, beaconOffset, now)
        }

        sendDetectedBroadcast(deviceId, stableLevel, avgRssi)

        // ── SAFE 처리 ───────────────────────────────────────────────────
        if (stableLevel == BleConstants.LEVEL_SAFE) {
            if (alertState.containsKey(deviceId)) {
                alertState.remove(deviceId)
                rssiPreFilter.clear(deviceId)
                kf.reset()
                kalmanFilters.remove(deviceId)
                trackingStateMap.remove(deviceId)
                crossingStartMap.remove(deviceId)
                departingStartMap.remove(deviceId)
                wasStationaryMap.remove(deviceId)
                recedingStartMap.remove(deviceId)
                peakAlertRssiMap.remove(deviceId)
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    activeSoundLevel = BleConstants.LEVEL_SAFE
                    VibrationHelper.stopVibration(this)
                    OverlayManager.hideOverlay()
                    scheduleIdleTransition()
                }
            }
            return
        }

        // 무음 중 — 상태 추적만 유지
        if (isMuted) {
            alertState[deviceId] = Pair(stableLevel, alertState[deviceId]?.second ?: now)
            return
        }

        // IMU 정지→이동 전환 기록
        val nowStationary  = ImuFusion.isStationary
        val prevStationary = wasStationaryMap.getOrDefault(deviceId, false)
        wasStationaryMap[deviceId] = nowStationary
        if (prevStationary && !nowStationary) {
            Log.d(TAG, "IMU 정지→이동 전환 [$deviceId]")
        }

        // 피크 RSSI 갱신
        val peakPrev = peakAlertRssiMap[deviceId]
        if (peakPrev == null || avgRssi > peakPrev) peakAlertRssiMap[deviceId] = avgRssi
        val peakRssi = peakAlertRssiMap[deviceId]!!

        // 이탈 방향 감지: 피크 대비 RECEDING_DBM_DROP 하락
        val isReceding = (peakRssi - avgRssi) >= RECEDING_DBM_DROP

        if (isReceding) {
            val justStartedReceding = !recedingStartMap.containsKey(deviceId)
            if (justStartedReceding) {
                recedingStartMap[deviceId] = now
                val hasOtherAlerts = alertState.any { (id, pair) ->
                    id != deviceId && pair.first >= BleConstants.LEVEL_WARNING
                }
                if (!hasOtherAlerts) {
                    AlertSoundPlayer.stopSound()
                    VibrationHelper.stopVibration(this)
                    OverlayManager.hideOverlay()
                    activeSoundLevel = BleConstants.LEVEL_SAFE
                }
                Log.d(TAG, "이탈 감지 즉시 소리 중지: $deviceId (peak=$peakRssi, curr=$avgRssi, drop=${peakRssi - avgRssi} dBm)")
                sendStatusBroadcast("↗ 이탈 감지 → 경보 일시 해제: ${extractDisplayName(deviceId)}")
            }
            val recedingMs = now - (recedingStartMap[deviceId] ?: now)
            if (recedingMs >= RECEDING_CLEAR_MS && alertState.containsKey(deviceId)) {
                alertState.remove(deviceId)
                recedingStartMap.remove(deviceId)
                peakAlertRssiMap.remove(deviceId)
                trackingStateMap.remove(deviceId)
                crossingStartMap.remove(deviceId)
                departingStartMap.remove(deviceId)
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    VibrationHelper.stopVibration(this)
                    OverlayManager.hideOverlay()
                    activeSoundLevel = BleConstants.LEVEL_SAFE
                    scheduleIdleTransition()
                }
                sendStatusBroadcast("↗ 이탈 확인 → 경보 해제: ${extractDisplayName(deviceId)}")
                Log.d(TAG, "이탈 경보 해제: $deviceId (${recedingMs}ms 연속 이탈)")
                return
            }
        } else {
            recedingStartMap.remove(deviceId)
        }

        // ── TTC 기반 선발령 (KF2D velocity 직접 사용, v1.0.20) ────────────
        // DEPARTING/CROSSING 상태에서는 억제 (이탈 중 = 충돌 없음)
        if (stableLevel == BleConstants.LEVEL_WARNING
            && newState == TrackingState.APPROACHING) {
            val ttc = estimateTTC(kfDist, kfVel)
            if (ttc != null && ttc <= TTC_THRESHOLD_SEC) {
                val speedKmh = (-kfVel) * 3.6
                ttcFeedbackMap[deviceId] = Pair(ttc, now)
                Log.w(TAG, "TTC 위험 선발령: $deviceId TTC=%.1fs speed=%.1fkm/h (KF2D)".format(ttc, speedKmh))
                forceAlarmVolume()
                if (DevSettings.vibrationEnabled && !isScreenOn) VibrationHelper.vibrateDanger(this)
                if (DevSettings.soundEnabled)     AlertSoundPlayer.playDanger(this)
                val ttcName = extractDisplayName(deviceId)
                OverlayManager.showDangerOverlay(this, danger = true, approachingName = ttcName)
                sendDetectedBroadcast(deviceId, BleConstants.LEVEL_DANGER, avgRssi)
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
                sendStatusBroadcast("⚡ 충돌 예측 %.0f초: $ttcName (%.0fkm/h)".format(ttc, speedKmh))
                alertState[deviceId] = Pair(BleConstants.LEVEL_DANGER, now)
                return
            }
        }

        Log.d(TAG, "RSSI raw=$rssi → preFiltered=$preFiltered → kfRssi=$kalmanRssi " +
            "vel=%.2fm/s state=$newState stableLevel=$stableLevel".format(kfVel))

        val prev = alertState[deviceId]
        val prevLevel     = prev?.first  ?: BleConstants.LEVEL_SAFE
        val lastAlertTime = prev?.second ?: 0L
        val baseCooldown  = if (stableLevel == BleConstants.LEVEL_DANGER) DANGER_COOLDOWN_MS else WARNING_COOLDOWN_MS
        // DEPARTING 상태: 쿨다운 2배 적용 (핑퐁 방지)
        val cooldown = if (isNowDepart) baseCooldown * 2 else baseCooldown

        val isFirstDetection = prev == null
        val levelEscalated   = stableLevel > prevLevel
        val cooldownPassed   = now - lastAlertTime >= cooldown
        val shouldAlert = isFirstDetection || levelEscalated || (cooldownPassed && !isReceding)
        if (!shouldAlert) return

        alertState[deviceId] = Pair(stableLevel, now)
        if (isMuted) return

        forceAlarmVolume()
        val globalMax = getCurrentMaxLevel()
        if (stableLevel < globalMax) {
            Log.d(TAG, "우선순위 무시: $stableLevel < $globalMax (활성)")
            return
        }
        if (stableLevel > activeSoundLevel) AlertSoundPlayer.stopSound()
        activeSoundLevel = stableLevel

        when (stableLevel) {
            BleConstants.LEVEL_WARNING -> {
                if (DevSettings.vibrationEnabled && !isScreenOn)
                    VibrationHelper.vibrateWarning(this)
                if (DevSettings.soundEnabled)
                    AlertSoundPlayer.playWarning(this)
                if (DevSettings.autoSaveAlerts)
                    FirebaseManager.saveAlert(deviceId, myId, avgRssi, "WARNING")
                val name = extractDisplayName(deviceId)
                OverlayManager.showDangerOverlay(this, danger = false, approachingName = name)
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_WARNING)
                Log.d(TAG, "경고 발생: $deviceId ($name) avgRssi=$avgRssi state=$newState vel=%.2fm/s".format(kfVel))
            }
        }
    }

    private val isScreenOn: Boolean
        get() = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun forceAlarmVolume() {
        ignoringVolumeChange = true
        try {
            val am     = getSystemService(AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = (maxVol * DevSettings.alarmVolume / 100f).toInt().coerceIn(0, maxVol)
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            Log.d(TAG, "알람 볼륨: $target/$maxVol (${DevSettings.alarmVolume}%)")
        } catch (e: Exception) { Log.w(TAG, "볼륨 강제 설정 실패: ${e.message}") }
        muteHandler.postDelayed({ ignoringVolumeChange = false }, 300)
    }

    private fun muteTemporarily(source: String) {
        isMuted = true
        isMutedPublic = true
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        OverlayManager.hideOverlay()
        val now = System.currentTimeMillis()
        alertState.entries.forEach { (key, value) ->
            alertState[key] = Pair(value.first, now)
        }
        sendStatusBroadcast("무음 ($source) — 탭하여 즉시 재개")
        sendAlertBroadcast("", BleConstants.LEVEL_SAFE)
        Log.d(TAG, "임시 무음: $source")
        muteHandler.removeCallbacksAndMessages(null)
        muteHandler.postDelayed({
            isMuted = false
            isMutedPublic = false
            sendStatusBroadcast("무음 해제 — 재경보 준비")
            Log.d(TAG, "무음 해제")
        }, MUTE_DURATION_MS)
    }

    private fun unmuteImmediately() {
        muteHandler.removeCallbacksAndMessages(null)
        isMuted = false
        isMutedPublic = false
        sendStatusBroadcast("즉시 재개됨")
        Log.d(TAG, "즉시 무음 해제")
    }

    private fun startScanHealthCheck() {
        healthCheckHandler.removeCallbacksAndMessages(null)
        healthCheckHandler.postDelayed(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - lastScanResultMs
                if (elapsed > SCAN_HEALTH_CHECK_MS) {
                    Log.w(TAG, "스캔 헬스체크: ${elapsed / 1000}초간 결과 없음 → 재시작")
                    sendStatusBroadcast("스캔 응답 없음 → 자동 재시작")
                    stopBle()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isRunning) applyMode()
                    }, 500)
                    lastScanResultMs = System.currentTimeMillis()
                }
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
                if (isMuted) { testHandler.postDelayed(this, 3000); return }
                forceAlarmVolume()
                if (DevSettings.vibrationEnabled) VibrationHelper.vibrateRepeat(this@BleService, DevSettings.vibrationDangerCount)
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

    private fun learnFromTTCFeedback(deviceId: String) {
        val feedback = ttcFeedbackMap.remove(deviceId) ?: return
        val (predictedTtc, alertTime) = feedback
        val actualTtc = (System.currentTimeMillis() - alertTime) / 1000.0
        if (actualTtc < 0.5 || actualTtc > 30.0) return
        val ratio = (actualTtc / predictedTtc).coerceIn(0.5, 2.0)
        val currentN = DevSettings.pathLossExp
        val correction = LEARN_RATE * (1.0 - ratio).toFloat() * currentN
        val newN = (currentN + correction).coerceIn(1.5f, 4.5f)
        if (Math.abs(newN - currentN) > 0.01f) {
            DevSettings.pathLossExp = newN
            Log.i(TAG, "TTC 학습: n %.2f → %.2f (예측 %.1fs, 실제 %.1fs)".format(
                currentN, newN, predictedTtc, actualTtc))
            sendStatusBroadcast("거리 모델 학습: n=%.2f".format(newN))
        }
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
            suffix.startsWith("BEA_") -> {
                val macKey = suffix.removePrefix("BEA_").chunked(2).take(6).joinToString(":").uppercase()
                BeaconRegistry.getAll().firstOrNull {
                    it.uuid.equals(macKey, ignoreCase = true)
                }?.label ?: suffix
            }
            suffix.isBlank() -> "알 수 없음"
            else -> suffix
        }
    }

    private fun sendDetectedBroadcast(deviceId: String, level: Int, rssi: Int) {
        val now = System.currentTimeMillis()
        if (now - (detectedThrottleMap[deviceId] ?: 0L) < 500L) return
        detectedThrottleMap[deviceId] = now
        val displayName = extractDisplayName(deviceId)
        val type = if (deviceId.startsWith(BleConstants.DEVICE_PREFIX)) "장비" else "보행자"
        sendBroadcast(Intent(BROADCAST_DETECTED).apply {
            putExtra(EXTRA_ID, deviceId)
            putExtra(EXTRA_ALERT_LEVEL, level)
            putExtra(EXTRA_DISPLAY_NAME, "$displayName ($type)")
            putExtra(EXTRA_RSSI, rssi)
        })
    }

    private fun sendAlertBroadcast(deviceId: String, level: Int) {
        val displayName = if (level == BleConstants.LEVEL_SAFE) "" else extractDisplayName(deviceId)
        val type = when {
            deviceId.startsWith(BleConstants.DEVICE_PREFIX) -> "장비"
            else -> "보행자"
        }
        sendBroadcast(Intent(BROADCAST_ALERT).apply {
            putExtra(EXTRA_ID, deviceId)
            putExtra(EXTRA_ALERT_LEVEL, level)
            putExtra(EXTRA_DISPLAY_NAME, if (displayName.isNotEmpty()) "$displayName ($type)" else "")
        })
    }

    // ── 대기 모드 전환 헬퍼 ────────────────────────────────────────────
    /** IDLE_TO_PENDING_MS 후 PendingIntent 모드로 전환 (타이머 재설정) */
    private fun scheduleIdleTransition() {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, IDLE_TO_PENDING_MS)
        Log.d(TAG, "대기 타이머 시작: ${IDLE_TO_PENDING_MS / 1000}초 후 PendingIntent 전환")
    }

    /** 기기 감지 시 대기 타이머 취소 */
    private fun cancelIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
    }

    /**
     * 대기 전환 실행: BleService 정지 + BlePendingScanner 기동.
     * 30초 무감지 경과 후 idleRunnable이 이 함수를 호출.
     */
    private fun handleIdleTransition() {
        if (!isRunning || myMode.isEmpty() || alertState.isNotEmpty()) return
        Log.d(TAG, "대기 전환: BlePendingScanner 기동 후 BleService 정지")
        sendStatusBroadcast("대기 모드 전환 (PendingIntent 스캔)")
        startPendingScanner()
        stopAll()           // 클린업 + stopSelf()
    }

    /** PendingIntent 스캔 시작 (BleService 정지 전 호출) */
    private fun startPendingScanner() {
        if (myMode.isNotEmpty()) BlePendingScanner.start(this)
    }

    /** PendingIntent 스캔 중지 (applyMode 진입 시 호출) */
    private fun stopPendingScanner() {
        BlePendingScanner.stop(this)
    }

    // ── BLE만 중지 (서비스 유지) ───────────────────────────────────────
    private fun stopBle() {
        bleAdvertiser?.stopAdvertising(); bleAdvertiser = null
        bleScanner?.stopScanning();       bleScanner    = null
    }

    private fun stopAll() {
        stopBle()
        ImuFusion.stop()
        uwbRanger?.stop(); uwbRanger = null
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        alertState.clear()
        rssiPreFilter.clearAll()
        kalmanFilters.clear()
        trackingStateMap.clear()
        crossingStartMap.clear()
        departingStartMap.clear()
        wasStationaryMap.clear()
        oneSecBuffer.clear()
        detectedThrottleMap.clear()
        recedingStartMap.clear()
        peakAlertRssiMap.clear()
        testRunnable?.let { testHandler.removeCallbacks(it) }
        testRunnable = null
        muteHandler.removeCallbacksAndMessages(null)
        isMuted = false
        isMutedPublic = false
        healthCheckHandler.removeCallbacksAndMessages(null)
        idleHandler.removeCallbacks(idleRunnable)   // 대기 전환 타이머 정리
        try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(volumeReceiver)  } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver)  } catch (_: Exception) {}
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
        if (isRunning) stopAll()
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

    private fun buildNotification(title: String, subText: String): android.app.Notification {
        val mutePi = android.app.PendingIntent.getService(
            this, 0,
            Intent(this, BleService::class.java).apply { action = ACTION_MUTE_TEMP },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subText)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_lock_silent_mode, "무음", mutePi)
            .build()
    }
}
