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
        const val ACTION_MUTE_DEVICE   = "ACTION_MUTE_DEVICE"   // v1.0.25: 플로팅 터치 → 특정 기기 10초 음소거
        const val ACTION_TEST_STATE    = "ACTION_TEST_STATE"   // [v1.0.34] 개발자 수동 STATE 주입(후진/하역 예약비트 송신 테스트)
        const val EXTRA_ID             = "extra_id"
        const val EXTRA_CATEGORY       = "extra_category"       // [v1.0.34] 송신자 역할 Category(CAT_*)
        const val EXTRA_PSTATE         = "extra_pstate"         // [v1.0.34] ACTION_TEST_STATE 용 STATE 값(PSTATE_*)
        const val EXTRA_ALERT_LEVEL    = "extra_alert_level"
        const val EXTRA_DISPLAY_NAME   = "extra_display_name"
        const val EXTRA_RSSI           = "extra_rssi"
        const val EXTRA_STATUS         = "extra_status"
        const val EXTRA_DEVICE_LIST    = "extra_device_list"    // [v1.0.26 Req2] 직렬화된 감지 기기 목록(최대 10)
        const val EXTRA_DEVICE_COUNT   = "extra_device_count"   // [v1.0.26 Req2] 목록 기기 수(0=감지 없음)
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
    // [v1.0.34] 내 역할(Category) — 광고 페이로드 bits[1:0] 에 패킹된다. 기본 보행자.
    private var myCategory = BleConstants.CAT_WALKER
    private var testRunnable: Runnable? = null
    private val testHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val MUTE_DURATION_MS = 10_000L
    private val muteHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var isMuted = false

    @Volatile private var activeSoundLevel = BleConstants.LEVEL_SAFE

    private fun getCurrentMaxLevel() =
        alertState.values.maxOfOrNull { it.first } ?: BleConstants.LEVEL_SAFE

    @Volatile private var ignoringVolumeChange = false

    // ── [v1.0.32] RssiPreFilter: 비대칭 비례제어(Asymmetric P-Control) EMA 전처리 ──
    // 칼만 필터 입력 전 1차 LPF. S_t = S_{t-1} + α·(R_t − S_{t-1}).
    //   비대칭 α: 강해짐(접근)=0.3 빠름 / 약해짐(난수)=0.05 느림 / D-Boost(prevVel>+2.0)=0.4 빗장개방.
    private val rssiPreFilter = RssiPreFilter()

    // ── Always-On 정책 (v1.0.24) ──────────────────────────────────────
    // PendingIntent 대기 모드 완전 폐기: 주변 기기 유무(SAFE 상태 포함)와 무관하게
    // 서비스는 사용자가 직접 '중지'를 누르기 전까지 절대 자동 종료(stopAll())하지 않고
    // 살아서 100% LOW_LATENCY 스캔을 유지한다. (현장 5초 기상 지연 → 0초 보장)

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

    // ── [v1.0.27] IMU 연동 동적 스캔 모드 (휴식/전투) ───────────────────────
    // 정지 5초 확정 → BALANCED 절전(휴식). 이동 즉시 → LOW_LATENCY 원복(전투).
    private val STATIONARY_ECO_DELAY_MS = 5_000L
    private val ecoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ecoDowngradeRunnable = Runnable {
        // 5초 뒤에도 여전히 정지 + 활성 경보 없음일 때만 절전 진입 (위험 존재 시 전투 유지)
        if (ImuFusion.isStationary && alertState.isEmpty()) {
            bleScanner?.setEcoMode(true)
            Log.d(TAG, "정지 5초 경과 + 경보 없음 → BALANCED 절전(휴식 모드)")
        }
    }

    // ── 2D 칼만 필터 맵 (v1.0.20: KalmanFilter, 거리+속도 동시 추적) ──
    private val kalmanFilters = mutableMapOf<String, KalmanFilter>()

    // ── TTC 파라미터 ──────────────────────────────────────────────────
    // [v1.0.25 Req2] 현장 초민감 오발령 해결 — 8.0초 → 3.0초로 대폭 강화 (충돌 임박 시에만 선발령)
    private val TTC_THRESHOLD_SEC      = 3.0
    // ★ RSSI 공간 부호 규칙: vel > 0 = RSSI 증가 = 접근 / vel < 0 = RSSI 감소 = 이탈
    private val MIN_APPROACH_VEL_DBM   = 0.5  // TTC 계산 최소 접근 속도 (dBm/s)

    // ── 기기별 추적 상태 머신 (v1.0.20) ──────────────────────────────
    enum class TrackingState { APPROACHING, CROSSING, DEPARTING }
    private val trackingStateMap   = mutableMapOf<String, TrackingState>()
    private val crossingStartMap   = mutableMapOf<String, Long>()    // CROSSING 진입 시각
    private val departingStartMap  = mutableMapOf<String, Long>()    // DEPARTING 진입 시각

    // 상태 전환 파라미터 (RSSI 공간 기준)
    private val CPA_VEL_THRESHOLD             = 0.5   // CPA 판정 속도 임계 (dBm/s)
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

    private val recedingStartMap = mutableMapOf<String, Long>()
    private val RECEDING_CLEAR_MS = 2500L

    private val peakAlertRssiMap  = mutableMapOf<String, Int>()
    private val RECEDING_DBM_DROP = 5
    // 기기별 마지막 avgRssi 보관 — 플로팅 위젯 최우선 기기 선정·정렬에 사용
    private val deviceRssiMap     = mutableMapOf<String, Int>()

    // [v1.0.25 Req4] 기기별 음소거(Acknowledge) — deviceId → 음소거 해제 시각(ms). 플로팅 터치 시 등록.
    private val mutedDevices      = mutableMapOf<String, Long>()

    // [v1.0.29 다이나믹 페이로드] 0x02(급정거/급회전) 특수경보 기기의 표시문자열 덮어쓰기 맵.
    //   값 = "{이름}이(가) 급정거 또는 급회전 중입니다." → 오버레이/목록에서 일반 이름 대신 출력.
    private val suddenLabelMap    = mutableMapOf<String, String>()

    // [v1.0.34] 수신한 상대 기기의 Category(역할) 캐시 — 디코드된 CAT_* 보관.
    //   접두어(prefix)만으론 EPJ(01)·지게차(10)가 둘 다 DEVICE 라 구분 불가하므로,
    //   1바이트 페이로드에서 언패킹한 Category 로 표시 라벨(보행자/EPJ/지게차)을 판별한다.
    private val deviceCategoryMap = mutableMapOf<String, Int>()

    // [v1.0.30 Req3] Firebase 경보 저장 모바일데이터 방어 — 기기별 마지막 저장 시각(ms).
    //   같은 기기에 대해 FIREBASE_SAVE_THROTTLE_MS(1분) 안에는 재업로드하지 않는다.
    private val firebaseLastSaveMap = mutableMapOf<String, Long>()
    private val FIREBASE_SAVE_THROTTLE_MS = 60_000L

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
        // [v1.0.27] IMU 정지↔이동 전환 구독 → 동적 스캔 모드 제어 (DEVICE·WALKER 공통)
        ImuFusion.onStationaryChanged = { stationary ->
            // 센서 스레드 → 메인 핸들러로 위임(스캔 재시작은 메인 루퍼 기준)
            ecoHandler.post {
                ecoHandler.removeCallbacks(ecoDowngradeRunnable)
                if (stationary) {
                    // 정지 진입 → 5초 디바운스 후 절전(그 사이 이동하면 취소됨)
                    ecoHandler.postDelayed(ecoDowngradeRunnable, STATIONARY_ECO_DELAY_MS)
                } else {
                    // 이동 감지 즉시(0초) → LOW_LATENCY 원복(전투 모드)
                    bleScanner?.setEcoMode(false)
                    Log.d(TAG, "IMU 이동 감지 → 즉시 LOW_LATENCY 복귀(전투 모드)")
                }
            }
        }
        // [v1.0.34 다이나믹 페이로드] IMU 3-State 모션 변화 → 송신 STATE(PSTATE_*) 매핑 후 광고 갱신.
        //   v1.0.34 는 급변(0x02)만 PSTATE_SUDDEN 으로 싣고, 정지(0x00)·일반이동(0x01)은 PSTATE_NORMAL 로 통합.
        //   (후진 PSTATE_REVERSE / 하역 PSTATE_EMERGENCY 는 예약 — ACTION_TEST_STATE 로만 수동 주입)
        ImuFusion.onMotionStateChanged = { code ->
            val pState = if (code == BleConstants.MOTION_STATE_SUDDEN)
                BleConstants.PSTATE_SUDDEN else BleConstants.PSTATE_NORMAL
            bleAdvertiser?.updateState(pState)
        }
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
                myCategory = prefs.getInt("running_category",
                    if (savedMode == "DEVICE") BleConstants.CAT_FORKLIFT else BleConstants.CAT_WALKER)
                val title = "${categoryRoleName(myCategory)} 실행 중"
                startForeground(NOTIF_ID, buildNotification(title, "재시작됨"))
                applyMode()
            }
            return START_STICKY
        }

        when (intent?.action) {
            ACTION_START_DEVICE -> {
                myId   = intent.getStringExtra(EXTRA_ID) ?: "DEVICE_001"
                myMode = "DEVICE"
                // [v1.0.34] DEVICE 모드는 EPJ(01) 또는 지게차(10) — Category EXTRA 로 구분(기본 지게차)
                myCategory = intent.getIntExtra(EXTRA_CATEGORY, BleConstants.CAT_FORKLIFT)
                // 모드 저장: START_STICKY 재시작 시 onStartCommand 복원에 사용
                saveRunningMode(myMode, myId, myCategory)
                startForeground(NOTIF_ID, buildNotification(
                    "${categoryRoleName(myCategory)} 실행 중",
                    buildSubText(DevSettings.deviceTx, DevSettings.deviceRx)
                ))
                applyMode()
            }
            ACTION_START_WALKER -> {
                myId   = intent.getStringExtra(EXTRA_ID) ?: "WALKER_001"
                myMode = "WALKER"
                myCategory = BleConstants.CAT_WALKER   // [v1.0.34] 보행자 고정
                saveRunningMode(myMode, myId, myCategory)
                startForeground(NOTIF_ID, buildNotification(
                    "보행자 실행 중",
                    buildSubText(DevSettings.walkerTx, DevSettings.walkerRx)
                ))
                applyMode()
            }
            ACTION_STOP       -> stopAll()
            ACTION_TEST_START -> startTestAlert()
            ACTION_TEST_STOP  -> stopTestAlert()
            ACTION_MUTE_TEMP   -> muteTemporarily("화면 터치")
            ACTION_UNMUTE      -> unmuteImmediately()
            ACTION_MUTE_DEVICE -> muteDevice(intent.getStringExtra(EXTRA_ID))
            // [v1.0.34] 개발자 수동 STATE 주입 — 후진(PSTATE_REVERSE)/하역(PSTATE_EMERGENCY) 예약비트
            //   송신 무결성을 2-기기 현장 테스트로 검증하기 위한 훅(평상 복귀=PSTATE_NORMAL).
            //   예) adb shell am startservice -n .../BleService -a ACTION_TEST_STATE --ei extra_pstate 2
            ACTION_TEST_STATE -> {
                val s = intent.getIntExtra(EXTRA_PSTATE, BleConstants.PSTATE_NORMAL)
                bleAdvertiser?.updateState(s)
                sendStatusBroadcast("수동 STATE 주입: $s")
            }
        }
        return START_STICKY
    }

    /** 모드와 ID를 SharedPrefs에 저장 (START_STICKY 재시작 복원용) */
    private fun saveRunningMode(mode: String, id: String, category: Int) {
        getSharedPreferences("safealert_prefs", MODE_PRIVATE).edit()
            .putString("running_mode", mode)
            .putString("device_id", id)
            .putInt("running_category", category)   // [v1.0.34] 역할 복원용
            .apply()
    }

    private fun applyMode() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter

        val modeStr = if (DevSettings.detectionMode == DevSettings.MODE_FIXED_AVG)
            "고정값(위험|${DevSettings.fixedDangerAbs}|경고|${DevSettings.fixedWarningAbs})"
        else
            "칼만(위험${DevSettings.dangerDistM.toInt()}m≈${BleConstants.rssiDanger}dBm 경고${DevSettings.warningDistM.toInt()}m≈${BleConstants.rssiWarning}dBm)"
        Log.i(TAG, "=== BLE 임계값 확인: $modeStr ===")
        sendStatusBroadcast("설정: $modeStr")

        if (btAdapter == null) {
            sendStatusBroadcast("❌ 블루투스 미지원 기기")
            return
        }
        if (!btAdapter.isEnabled) {
            sendStatusBroadcast("❌ 블루투스 꺼짐 — 켜주세요")
            return
        }

        val doTx = if (myMode == "DEVICE") DevSettings.deviceTx else DevSettings.walkerTx
        val doRx = if (myMode == "DEVICE") DevSettings.deviceRx else DevSettings.walkerRx

        if (doTx) {
            val advertiser = btAdapter.bluetoothLeAdvertiser
            if (advertiser != null) {
                val prefix = if (myMode == "DEVICE") BleConstants.DEVICE_PREFIX else BleConstants.WALKER_PREFIX
                // [v1.0.34] 역할(Category)을 광고자에 전달 → 1바이트 페이로드 bits[1:0] 패킹
                val bleAdv = BleAdvertiser(advertiser, prefix, myCategory,
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
                    s.startScanning(object : BleScanCallback {
                        override fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int, remoteState: Int) {
                            lastScanResultMs = System.currentTimeMillis()

                            if (myMode == "WALKER"
                                && deviceId.startsWith(BleConstants.WALKER_PREFIX)
                                && !DevSettings.walkerDetectsWalker) return

                            val effectiveRssi = if (DevSettings.debugMode) DevSettings.simulatedRssi else rssi
                            processAlert(deviceId, effectiveRssi, remoteState)
                            // [v1.0.26 Req2] processAlert 가 alertState 를 어떻게 바꿨든(추가·격상·SAFE 제거·TTC 선발령)
                            // 그 직후 전체 스냅샷을 한 번에 송출 → 하단 목록이 플로팅·알람과 절대 어긋나지 않는다.
                            broadcastDeviceList()
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
                            deviceRssiMap.remove(deviceId)
                            oneSecBuffer.remove(deviceId)   // [v1.0.31] 게이트가 raw도 push → 신호소실 시 함께 정리
                            mutedDevices.remove(deviceId)
                            suddenLabelMap.remove(deviceId)
                            deviceCategoryMap.remove(deviceId)
                            firebaseLastSaveMap.remove(deviceId)
                            sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                            if (alertState.isEmpty()) {
                                AlertSoundPlayer.stopSound()
                                VibrationHelper.stopVibration(this@BleService)
                                OverlayManager.hideOverlay()
                                activeSoundLevel = BleConstants.LEVEL_SAFE
                                sendStatusBroadcast("기기 이탈 → 경보 중지")
                            } else {
                                updateFloatingOverlay()   // 다른 위험 기기로 플로팅 전환
                            }
                            // [v1.0.26 Req2] 신호 소실 직후 목록 재송출(빈 목록도 강제 전송 → '감지 없음' 즉시 반영)
                            broadcastDeviceList(force = true)
                        }
                        override fun onScanError(errorCode: Int) { Log.e(TAG, "스캔 오류: $errorCode") }
                        override fun onUwbAddressReceived(deviceId: String, uwbAddress: ByteArray) {
                            uwbRanger?.onPeerUwbAddressReceived(deviceId, uwbAddress)
                        }
                    })
                }
                sendStatusBroadcast("RX 스캔 시작")
                Log.d(TAG, "RX 시작")
            } else {
                sendStatusBroadcast("❌ RX 오류: BluetoothLeScanner null")
                Log.e(TAG, "RX: bluetoothLeScanner null")
            }
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
     * TTC 추정 — RSSI 공간 2D 칼만 vel 직접 사용 (v1.0.20)
     *
     * ★ RSSI 부호 규칙: vel > 0 = RSSI 증가 = 접근
     * remaining = (위험 임계 RSSI) - (현재 추정 RSSI)
     * TTC = remaining / vel  (vel > MIN_APPROACH_VEL_DBM 일 때만)
     *
     * @param kfRssi 추정 RSSI (dBm)
     * @param kfVel  추정 변화율 (dBm/s, 양수=접근)
     */
    private fun estimateTTC(kfRssi: Double, kfVel: Double): Double? {
        if (kfVel <= MIN_APPROACH_VEL_DBM) return null  // 접근 중 아님 or 속도 미달
        val remaining = BleConstants.rssiDanger.toDouble() - kfRssi
        if (remaining <= 0) return 0.0                  // 이미 위험 구역
        val ttc = remaining / kfVel
        Log.d(TAG, "TTC: kfRssi=%.1f rssiDanger=%d vel=%.2fdBm/s TTC=%.1fs"
            .format(kfRssi, BleConstants.rssiDanger, kfVel, ttc))
        return ttc
    }

    /**
     * 추적 상태 머신 갱신 (v1.0.20 — RSSI 공간)
     *
     * ★ RSSI vel 부호 규칙:
     *   vel > 0 = RSSI 증가 = 보행자 접근 (APPROACHING)
     *   vel < 0 = RSSI 감소 = 보행자 이탈 (CPA 통과 → DEPARTING)
     *
     * CPA 감지: vel이 양수(+)에서 음수(-)로 꺾이는 순간
     */
    private fun updateTrackingState(deviceId: String, kfVel: Double, now: Long) {
        val current = trackingStateMap[deviceId] ?: TrackingState.APPROACHING
        when (current) {
            TrackingState.APPROACHING -> {
                // vel이 음수 임계 이하로 꺾이면 → CPA 후보 (CROSSING 진입)
                if (kfVel < -CPA_VEL_THRESHOLD) {
                    crossingStartMap[deviceId] = now
                    trackingStateMap[deviceId] = TrackingState.CROSSING
                    Log.d(TAG, "[$deviceId] APPROACHING → CROSSING (vel=%.2fdBm/s)".format(kfVel))
                }
            }
            TrackingState.CROSSING -> {
                when {
                    kfVel >= 0.0 -> {
                        // vel 다시 양수 → CPA 오판, APPROACHING 복귀
                        crossingStartMap.remove(deviceId)
                        trackingStateMap[deviceId] = TrackingState.APPROACHING
                        Log.d(TAG, "[$deviceId] CROSSING → APPROACHING 복귀 (vel=%.2fdBm/s)".format(kfVel))
                    }
                    now - (crossingStartMap[deviceId] ?: now) >= CROSSING_CONFIRM_MS -> {
                        // 음수 vel 지속 확인 → DEPARTING 확정
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
                // 쿨다운 경과 후 vel이 강한 양수로 복귀 → 재접근
                if (timeDep >= DEPARTING_REENTRY_COOLDOWN_MS
                    && kfVel > (CPA_VEL_THRESHOLD * 3)) {
                    departingStartMap.remove(deviceId)
                    trackingStateMap[deviceId] = TrackingState.APPROACHING
                    Log.d(TAG, "[$deviceId] DEPARTING → APPROACHING 재진입 (vel=%.2fdBm/s)".format(kfVel))
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

    private fun processAlert(deviceId: String, rssi: Int, remoteState: Int = 0x00) {
        // [v1.0.34] 수신 1바이트 페이로드 언패킹 → Category / State (Speed 는 v1.0.34 미사용)
        //   remoteState 는 BleScanner 가 ServiceData 1바이트를 0~255 로 그대로 넘긴 값.
        val rCategory = BleConstants.decodeCategory(remoteState)
        val rState    = BleConstants.decodeState(remoteState)
        deviceCategoryMap[deviceId] = rCategory   // 표시 라벨(보행자/EPJ/지게차) 판별용 캐시

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

        // ── 2D 칼만 필터 가져오기 또는 생성 ──────────────────────────────
        val kf = kalmanFilters.getOrPut(deviceId) {
            KalmanFilter(DevSettings.kalmanPreset)
        }
        kf.updatePreset(DevSettings.kalmanPreset)

        // ── [v1.0.32] RssiPreFilter: 비대칭 비례제어(Asymmetric P-Control) EMA 전처리 ──
        // 직전 프레임 칼만 추정속도(estimatedVel)를 D-Boost 피드백으로 전달한다.
        //   ※ kf.update()는 바로 아래에서 호출되므로, 지금 읽는 estimatedVel 은 '직전 프레임'
        //     속도 = 1-step 미분 피드백. 강한 돌진(prevVel>+2.0)이면 α 빗장을 열어 지연을 없앤다.
        //   정제 출력(smoothedRssi=preFiltered)만 칼만 입력으로 주입(raw 직접 입력 금지).
        val prevVel     = kf.estimatedVel
        val preFiltered = rssiPreFilter.push(deviceId, inputRssi, prevVel)

        // ── 2D 칼만 필터 업데이트 (RSSI 공간) ────────────────────────────
        // kfRssi: 추정 RSSI(dBm) / kfVel: 변화율(dBm/s), 양수=접근 / 음수=이탈
        val (kfRssi, kfVel) = kf.update(preFiltered, ImuFusion.adaptiveQFactor)
        val kalmanRssi = kfRssi.toInt()

        // [v1.0.31] raw 1초평균 — 하드게이트/2차게이트/TTC 교차검증용으로 게이트 '앞'에서 1회만 계산.
        //   oneSecAvgRssi 는 호출마다 버퍼에 push(부작용) → 프레임당 1회 호출 후 변수 재사용한다.
        val avg1sec = oneSecAvgRssi(deviceId, inputRssi)   // 1초 평균은 raw 기준 유지

        // ── [v1.0.30 → v1.0.31 raw 이중가드] 최상단 하드 게이트 (절대 거리 선차단 · 음수 부호 주의) ─────
        // RSSI는 음수다. '칼만 정제값(kalmanRssi)'과 'raw 1초평균(avg1sec)' 중 더 먼(더 음수) 쪽을
        // 기준(gateRssi)으로 잡아, 둘 중 하나라도 경고 임계(rssiWarning, 예 -65)보다 멀면 차단한다.
        //   ★ v1.0.30 버그: 게이트가 kalmanRssi 단독 → 칼만이 순간 반사(multipath spike)나 평활화
        //     지연(lag)으로 실제보다 가깝게 떠 있으면 게이트가 같이 속아 원거리 오발을 통과시켰다.
        //   → raw 실측(avg1sec)으로 교차검증: 칼만이 거짓으로 가까워도 raw가 멀면 차단된다.   예) -74 < -65.
        // 이때 '기존에 가까웠다가 멀어지는 중(쿨다운 = 이미 alertState 추적 중)'이 아니라면, 접근 속도·
        // TTC·0x02 특수경보까지 전부 무시하고 즉시 정리 후 return 한다.
        // 이미 추적 중(alertState 존재)인 기기는 게이트를 통과시켜, 아래 이탈(receding) 페이드아웃
        // 로직이 부드럽게 해제하도록 맡긴다(급단절 방지).
        //   ★ v1.0.32 3중 가드: 칼만(kalmanRssi)·raw1초평균(avg1sec)에 더해 전처리 EMA 출력
        //     (smoothedRssi=preFiltered)까지 세 경로 중 가장 먼(가장 음수) 값을 기준으로 잡는다.
        //     어느 한 경로라도 경고 임계보다 멀면 속도·TTC와 무관하게 신규 격상을 차단(SAFE).
        val gateRssi = minOf(kalmanRssi, avg1sec, preFiltered)
        if (gateRssi < BleConstants.rssiWarning && !alertState.containsKey(deviceId)) {
            deviceRssiMap.remove(deviceId)
            suddenLabelMap.remove(deviceId)
            deviceCategoryMap.remove(deviceId)
            firebaseLastSaveMap.remove(deviceId)
            return
        }

        // ── [v1.0.29 → v1.0.33] 0x02 특수경보 (최우선 분기 · 하이브리드 교차검증) ──────────
        // 상대 remoteState 가 0x02(급정거/급회전)이고 'smoothedRssi(EMA)와 avg1sec(raw 1초평균)'이
        // 둘 다 임계(-60) 이상(가까움)일 때만 TTC·속도·방향·절대거리 가드를 무시하고 즉시 DANGER 격상.
        //   ★ v1.0.32: 거리 판정을 kfRssi(칼만) → smoothedRssi(=preFiltered, EMA 출력)로 변경.
        //     칼만 lag 로 실제보다 가깝게 떠 있는 원거리 오발을 줄이고 지침(smoothedRssi 기준)을 준수.
        //   ★ v1.0.33: smoothedRssi 에 avg1sec(raw) 를 논리곱으로 결합(하이브리드). 이탈 중 기기는
        //     fall α=0.05 의 EMA 지연(lag)으로 smoothedRssi 가 한동안 −60 위로 떠 있어 'DANGER 잔상
        //     (Ghost Danger)'을 낼 수 있는데, 반응이 빠른 raw 1초평균이 이미 멀어졌으면(−60 미만)
        //     즉시 차단해 이탈 기기의 과경보 잔상을 완전히 제거한다.
        // 표시문자열을 "{이름}이(가) 급정거 또는 급회전 중입니다."로 덮어써 오버레이·목록에 출력.
        if (rState != BleConstants.PSTATE_NORMAL
            && preFiltered >= BleConstants.SUDDEN_ALERT_RSSI_THRESHOLD
            && avg1sec     >= BleConstants.SUDDEN_ALERT_RSSI_THRESHOLD) {
            deviceRssiMap[deviceId]  = kalmanRssi
            suddenLabelMap[deviceId] = makeStateLabel(extractDisplayName(deviceId), rCategory, rState)
            alertState[deviceId]     = Pair(BleConstants.LEVEL_DANGER, now)
            bleScanner?.setEcoMode(false)   // 즉시 전투 모드(LOW_LATENCY)
            Log.w(TAG, "특수경보(STATE=$rState CAT=$rCategory): $deviceId smoothed=$preFiltered kfRssi=%.1f".format(kfRssi))
            // 무음(전역/개별)은 존중 — 상태·표시는 유지하되 소리/진동만 억제
            if (isMuted || isDeviceMuted(deviceId)) {
                updateFloatingOverlay()
                return
            }
            forceAlarmVolume()
            if (DevSettings.vibrationEnabled && !isScreenOn) VibrationHelper.vibrateDanger(this)
            if (DevSettings.soundEnabled)     AlertSoundPlayer.playDanger(this)
            updateFloatingOverlay()
            sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
            sendStatusBroadcast("⚡ ${suddenLabelMap[deviceId]}")
            return
        }
        // 0x02 해제(또는 미근접) → 특수 라벨 제거 후 일반 경보 로직 진행
        suddenLabelMap.remove(deviceId)

        // ── 추적 상태 머신 갱신 (v1.0.20) ───────────────────────────────
        updateTrackingState(deviceId, kfVel, now)
        val newState    = trackingStateMap[deviceId] ?: TrackingState.APPROACHING
        val isNowDepart = newState == TrackingState.DEPARTING

        // avg1sec(raw 1초평균)은 위 하드게이트 앞에서 이미 계산됨(프레임당 1회).

        var stableLevel: Int
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
            val rawLevel = if (blended >= effectiveThresh) BleConstants.LEVEL_WARNING else BleConstants.LEVEL_SAFE
            // ── isStationary 방어: 지게차 정지 중 DANGER 억제 ──────────────
            stableLevel = if (ImuFusion.isStationary && rawLevel >= BleConstants.LEVEL_DANGER)
                BleConstants.LEVEL_WARNING else rawLevel
        } else {
            val blended = (kalmanRssi * (1.0 - auxRatio) + avg1sec * auxRatio).toInt()
            avgRssi = blended
            val beaconOffset = runCatching { BeaconRegistry.getRssiOffsetForFullId(deviceId) }.getOrDefault(0)
            val rawLevel = calcLevelWithHysteresis(deviceId, blended, beaconOffset)
            val afterHysteresis = applyDepartingHysteresis(deviceId, rawLevel, blended, beaconOffset, now)
            // ── isStationary 방어: 지게차 정지 중 DANGER 억제 ──────────────
            stableLevel = if (ImuFusion.isStationary && afterHysteresis >= BleConstants.LEVEL_DANGER)
                BleConstants.LEVEL_WARNING else afterHysteresis
        }

        deviceRssiMap[deviceId] = avgRssi      // 플로팅 위젯 최우선 기기 선정·정렬에 사용

        // ── [v1.0.25 → v1.0.31 raw 이중가드] 절대 거리 가드 (2차 방어선) ─────────────────────
        // 1차 방어는 위 하드게이트(min(칼만,raw) 기준 + return)가 담당한다.
        // 여기는 '이미 추적 중이라 1차 게이트를 통과한 기기'가 blend(avgRssi) '또는' raw 1초평균
        // (avg1sec) 중 하나라도 경고 임계(rssiWarning)보다 멀면 stableLevel을 SAFE로 강제 → 아래
        // 이탈 페이드아웃/SAFE 처리로 흘려보낸다. 접근 속도·TTC로는 절대 격상 불가.
        //   ★ blend는 칼만 비중 때문에 raw가 멀어도 임계 위로 떠 있을 수 있어 raw를 함께 본다.
        if (avgRssi < BleConstants.rssiWarning || avg1sec < BleConstants.rssiWarning) {
            stableLevel = BleConstants.LEVEL_SAFE
        }

        // [v1.0.26 Req2] 개별 sendDetectedBroadcast 폐지 — 목록은 onDeviceDetected 처리 직후
        // broadcastDeviceList() 가 alertState 전체를 한 번에 송출한다(단일 진실 공급원).

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
                deviceRssiMap.remove(deviceId)
                mutedDevices.remove(deviceId)
                suddenLabelMap.remove(deviceId)
                deviceCategoryMap.remove(deviceId)
                firebaseLastSaveMap.remove(deviceId)
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    activeSoundLevel = BleConstants.LEVEL_SAFE
                    VibrationHelper.stopVibration(this)
                    OverlayManager.hideOverlay()
                } else {
                    updateFloatingOverlay()   // 다른 위험 기기로 플로팅 전환
                }
            }
            return
        }

        // [v1.0.27] 여기 도달 = 비-SAFE(경보 상황). 정지 중이라도 즉시 전투모드(LOW_LATENCY) 보장.
        bleScanner?.setEcoMode(false)

        // 무음 중 — 상태 추적만 유지 (전역 무음 또는 [v1.0.25] 해당 기기 Acknowledge 무음)
        if (isMuted || isDeviceMuted(deviceId)) {
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
                rssiPreFilter.clear(deviceId)
                kalmanFilters[deviceId]?.reset()
                kalmanFilters.remove(deviceId)
                wasStationaryMap.remove(deviceId)
                recedingStartMap.remove(deviceId)
                peakAlertRssiMap.remove(deviceId)
                trackingStateMap.remove(deviceId)
                crossingStartMap.remove(deviceId)
                departingStartMap.remove(deviceId)
                deviceRssiMap.remove(deviceId)
                firebaseLastSaveMap.remove(deviceId)
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    VibrationHelper.stopVibration(this)
                    OverlayManager.hideOverlay()
                    activeSoundLevel = BleConstants.LEVEL_SAFE
                }
                sendStatusBroadcast("↗ 이탈 확인 → 경보 해제: ${extractDisplayName(deviceId)}")
                Log.d(TAG, "이탈 경보 해제: $deviceId (${recedingMs}ms 연속 이탈)")
                return
            }
        } else {
            recedingStartMap.remove(deviceId)
        }

        // ── TTC 기반 선발령 (RSSI 공간 vel 직접 사용, v1.0.20 / v1.0.31 raw 가드) ──────────
        // DEPARTING/CROSSING 상태에서는 억제 (이탈 중 = 충돌 위험 없음)
        // isStationary 시에도 억제 (정지 중 TTC 선발령 오발 방지)
        //   ★ v1.0.31: estimateTTC 는 kfRssi(칼만)로 계산 → 칼만이 spike/lag로 가깝게 떠 있으면
        //     remaining<=0 이 되어 'TTC 0초' 오발이 났다. raw 실측(avg1sec)이 경고권(rssiWarning)
        //     이상으로 실제 가까울 때만 선발령을 허용해, 원거리에서의 TTC 0초 오발을 차단한다.
        if (stableLevel == BleConstants.LEVEL_WARNING
            && newState == TrackingState.APPROACHING
            && !ImuFusion.isStationary
            && avg1sec >= BleConstants.rssiWarning) {
            val ttc = estimateTTC(kfRssi, kfVel)
            if (ttc != null && ttc <= TTC_THRESHOLD_SEC) {
                ttcFeedbackMap[deviceId] = Pair(ttc, now)
                alertState[deviceId] = Pair(BleConstants.LEVEL_DANGER, now)  // ★ 먼저 업데이트 → 목록에 DANGER 반영
                Log.w(TAG, "TTC 선발령: $deviceId TTC=%.1fs kfVel=%.2fdBm/s".format(ttc, kfVel))
                forceAlarmVolume()
                if (DevSettings.vibrationEnabled && !isScreenOn) VibrationHelper.vibrateDanger(this)
                if (DevSettings.soundEnabled)     AlertSoundPlayer.playDanger(this)
                updateFloatingOverlay()
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
                sendStatusBroadcast("⚡ 충돌 예측 %.0f초: ${extractDisplayName(deviceId)}".format(ttc))
                return
            }
        }

        Log.d(TAG, "RSSI raw=$rssi → pre=$preFiltered → kfRssi=%.1f " +
            "vel=%.2fdBm/s state=$newState stable=$stableLevel".format(kfRssi, kfVel))

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
                // [v1.0.30 Req3] Firebase 경보 저장 쓰로틀 — 같은 기기 1분 1회로 제한(모바일데이터 방어)
                if (DevSettings.autoSaveAlerts) {
                    val lastFbSave = firebaseLastSaveMap[deviceId] ?: 0L
                    if (now - lastFbSave >= FIREBASE_SAVE_THROTTLE_MS) {
                        firebaseLastSaveMap[deviceId] = now
                        FirebaseManager.saveAlert(deviceId, myId, avgRssi, "WARNING")
                    }
                }
                val name = extractDisplayName(deviceId)
                updateFloatingOverlay()
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_WARNING)
                Log.d(TAG, "경고 발생: $deviceId ($name) avgRssi=$avgRssi state=$newState vel=%.2fdBm/s".format(kfVel))
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

    // ── [v1.0.25 Req4] 기기별 Acknowledge 무음 + 플로팅 위젯 최우선 기기 ──────────
    /** 플로팅 위젯을 터치한 운전자가 육안 확인 → 해당 기기 알림(사이렌·플로팅)을 10초간 중지. */
    private fun muteDevice(deviceId: String?) {
        if (deviceId.isNullOrEmpty()) return
        mutedDevices[deviceId] = System.currentTimeMillis() + MUTE_DURATION_MS
        // 현재 울리는 소리·진동 즉시 정지 (다른 위험 기기가 남아있으면 다음 스캔에서 재발령됨)
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        activeSoundLevel = BleConstants.LEVEL_SAFE
        // 이 기기를 제외한 최우선 기기로 플로팅 갱신(없으면 숨김)
        updateFloatingOverlay()
        sendStatusBroadcast("✋ ${extractDisplayName(deviceId)} 확인됨 — 10초 무음")
        Log.d(TAG, "기기 음소거(Acknowledge): $deviceId (10초)")
    }

    /** 해당 기기가 현재 Acknowledge 무음 중인지. 만료 시 자동 정리 후 false. */
    private fun isDeviceMuted(deviceId: String): Boolean {
        val until = mutedDevices[deviceId] ?: return false
        if (System.currentTimeMillis() >= until) {
            mutedDevices.remove(deviceId)
            return false
        }
        return true
    }

    /**
     * 현재 경보 중(WARNING 이상)이며 Acknowledge 무음이 아닌 기기 중,
     * 위험도 → RSSI(가까운 순) 우선의 단 1대 최우선 기기 id 반환. 없으면 null.
     */
    private fun topPriorityDevice(): String? =
        alertState.entries
            .filter { it.value.first >= BleConstants.LEVEL_WARNING && !isDeviceMuted(it.key) }
            .maxWithOrNull(
                compareBy<Map.Entry<String, Pair<Int, Long>>> { it.value.first }
                    .thenBy { deviceRssiMap[it.key] ?: -100 }
            )?.key

    /** 최우선 기기 1대만 플로팅 위젯에 표시. 대상이 없으면 위젯을 숨긴다. */
    private fun updateFloatingOverlay() {
        val topId = topPriorityDevice()
        if (topId == null) {
            OverlayManager.hideOverlay()
            return
        }
        val level = alertState[topId]?.first ?: BleConstants.LEVEL_SAFE
        val rssi  = deviceRssiMap[topId] ?: -99
        OverlayManager.showFloating(
            context  = this,
            deviceId = topId,
            name     = suddenLabelMap[topId] ?: makeApproachLabel(topId),
            rssi     = rssi,
            danger   = level >= BleConstants.LEVEL_DANGER
        )
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

    /**
     * v1.0.29 0x02 특수경보용 표시문자열 생성.
     * 예) "Ian이 급정거 또는 급회전 중입니다."
     * 한글 이름은 받침 유무로 조사(이/가)를 고르고, 영문·숫자는 예시에 맞춰 "이"를 쓴다.
     */
    private fun makeSuddenLabel(name: String): String {
        val last = name.trim().lastOrNull()
        val josa = when {
            last == null -> "이"
            last.code in 0xAC00..0xD7A3 -> if ((last.code - 0xAC00) % 28 != 0) "이" else "가"
            else -> "이"
        }
        return "$name$josa 급정거 또는 급회전 중입니다."
    }

    /** v1.0.34 Category(CAT_*) -> 표시용 역할명. */
    private fun categoryRoleName(category: Int): String = when (category) {
        BleConstants.CAT_EPJ      -> "EPJ"
        BleConstants.CAT_FORKLIFT -> "지게차"
        BleConstants.CAT_WALKER   -> "보행자"
        else                      -> "보행자"
    }

    /**
     * v1.0.34 기기 표시용 역할 라벨.
     * 디코드된 Category 캐시가 있으면 보행자/EPJ/지게차로 구분하고,
     * 없으면(비콘 등 페이로드 없는 기기) 접두어 기반 기존 규칙(장비/보행자)으로 폴백한다.
     */
    private fun typeLabelOf(deviceId: String): String {
        val cat = deviceCategoryMap[deviceId]
        return if (cat != null) categoryRoleName(cat)
               else if (deviceId.startsWith(BleConstants.DEVICE_PREFIX)) "장비" else "보행자"
    }

    /**
     * v1.0.34 평상(NORMAL) 접근 표시문자열 - Category 기반 분화 (directive 4).
     *   EPJ 는 스펙 지정 문구 "{이름} EPJ가 접근 중!", 그 외는 "{이름} ({역할})" 간결 표기.
     *   ※ 특수상태(STATE!=평상)는 suddenLabelMap(makeStateLabel)이 우선하며, 이 함수는 그 폴백.
     */
    private fun makeApproachLabel(deviceId: String): String {
        val name = extractDisplayName(deviceId)
        return if (deviceCategoryMap[deviceId] == BleConstants.CAT_EPJ)
            "$name EPJ가 접근 중!"
        else
            "$name (${typeLabelOf(deviceId)})"
    }

    /**
     * v1.0.34 특수상태(STATE!=평상) 경보 표시문자열 - Category/State 조합 (directive 4).
     *   급정거(SUDDEN, 공통): 보행자는 기존 문장, EPJ·지게차는 "{이름} {역할} 급정거·급회전 중! 주의!"
     *   후진(REVERSE, 지게차): "{이름} 지게차 후진 중! 주의!"
     *   비상(EMERGENCY): 지게차는 "{이름} 상부 고소 작업 중! 낙하물 주의!", 그 외 공통 "{이름} {역할} 비상 상황!"
     */
    private fun makeStateLabel(name: String, category: Int, state: Int): String {
        val role = categoryRoleName(category)
        return when (state) {
            BleConstants.PSTATE_SUDDEN ->
                if (category == BleConstants.CAT_WALKER) makeSuddenLabel(name)
                else "$name $role 급정거·급회전 중! 주의!"
            BleConstants.PSTATE_REVERSE   -> "$name 지게차 후진 중! 주의!"
            BleConstants.PSTATE_EMERGENCY ->
                if (category == BleConstants.CAT_FORKLIFT) "$name 상부 고소 작업 중! 낙하물 주의!"
                else "$name $role 비상 상황!"
            else                          -> makeSuddenLabel(name)
        }
    }

    private var lastDeviceListMs = 0L

    /**
     * [v1.0.26 Req2] 단일 진실 공급원 — 현재 alertState(경보 중 기기) 전체를 '하나의' 직렬화 목록으로 브로드캐스트.
     *
     * 핵심: 화면 하단 목록과 플로팅 위젯이 둘 다 동일한 alertState 를 소스로 쓰게 하여
     *       '알람은 울리는데 목록엔 감지 없음' 같은 상태 불일치(Sync)를 구조적으로 차단한다.
     *
     * 정렬 : 위험도(level) 내림차순 → 같은 위험도면 RSSI 강한(가까운) 순. 최대 10개.
     * 직렬화: 레코드 = "level\u001Frssi\u001Fname", 레코드 구분 = "\u001E" (이름에 줄바꿈/구분자가 섞일 일 없음).
     * 빈 목록(count=0)은 throttle 을 무시하고 즉시 전송 → 마지막 기기 이탈 시 '감지 없음'을 지체 없이 반영.
     *
     * 스레드: 스캔 콜백·타임아웃·리시버가 모두 메인 루퍼에서 동작하므로 alertState 접근은 단일 스레드 → race 없음.
     */
    private fun broadcastDeviceList(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val entries = alertState.entries.toList()
        if (!force && entries.isNotEmpty() && now - lastDeviceListMs < 200L) return
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
            if (sb.isNotEmpty()) sb.append('\u001E')
            sb.append(level).append('\u001F').append(rssi).append('\u001F').append(name)
        }

        sendBroadcast(Intent(BROADCAST_DETECTED).apply {
            putExtra(EXTRA_DEVICE_LIST, sb.toString())
            putExtra(EXTRA_DEVICE_COUNT, sorted.size)
        })
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

    // ── BLE만 중지 (서비스 유지) ───────────────────────────────────────
    private fun stopBle() {
        bleAdvertiser?.stopAdvertising(); bleAdvertiser = null
        bleScanner?.stopScanning();       bleScanner    = null
    }

    private fun stopAll() {
        stopBle()
        // [v1.0.27] IMU 동적 스캔 모드 정리 — 콜백 해제 + 디바운스 타이머 취소
        ImuFusion.onStationaryChanged = null
        ImuFusion.onMotionStateChanged = null   // [v1.0.29] 모션 상태 콜백 해제
        ecoHandler.removeCallbacks(ecoDowngradeRunnable)
        ImuFusion.stop()
        uwbRanger?.stop(); uwbRanger = null
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        alertState.clear()
        suddenLabelMap.clear()
        deviceCategoryMap.clear()
        broadcastDeviceList(force = true)   // [v1.0.26 Req2] 서비스 중지 → 빈 목록 송출('감지 없음' 반영)
        rssiPreFilter.clearAll()
        kalmanFilters.clear()
        trackingStateMap.clear()
        crossingStartMap.clear()
        departingStartMap.clear()
        wasStationaryMap.clear()
        oneSecBuffer.clear()
        recedingStartMap.clear()
        peakAlertRssiMap.clear()
        deviceRssiMap.clear()
        mutedDevices.clear()
        firebaseLastSaveMap.clear()
        testRunnable?.let { testHandler.removeCallbacks(it) }
        testRunnable = null
        muteHandler.removeCallbacksAndMessages(null)
        isMuted = false
        isMutedPublic = false
        healthCheckHandler.removeCallbacksAndMessages(null)
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
