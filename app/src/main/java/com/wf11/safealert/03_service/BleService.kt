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
        const val EXTRA_LOCAL_STATE    = "extra_local_state"    // [v1.0.42 Req2] 내 장비(Local) 직렬화 상태
        const val BROADCAST_ALERT      = "com.wf11.safealert.ALERT"
        const val BROADCAST_DETECTED   = "com.wf11.safealert.DETECTED"
        const val BROADCAST_BLE_STATUS = "com.wf11.safealert.BLE_STATUS"
        const val BROADCAST_LOCAL_STATE = "com.wf11.safealert.LOCAL_STATE"   // [v1.0.42 Req2] 내 장비(Local) 상태 전파
        private const val CHANNEL_ID   = "safealert_channel"
        private const val NOTIF_ID     = 1001

        @Volatile var lastStatus: String   = ""
        @Volatile var bleScanCount: Int    = 0
        @Volatile var safeAlertFound: Int  = 0
        @Volatile var isRunning: Boolean   = false
        @Volatile var isMutedPublic: Boolean = false
        // [v1.0.42] Broadcast 누락 대비 폴백 — broadcastDeviceList 와 '동일 직렬화' 스냅샷을
        //   static 으로도 노출. MainActivity 가 800ms 폴링으로 직접 읽어 목록을 채운다.
        //   (브로드캐스트가 RECEIVER_NOT_EXPORTED/암시적 전달 실패로 누락돼도 '주변 감지 기기 N건'이
        //    반드시 화면에 뜨도록 — 오버레이는 뜨는데 목록만 비던 증상의 구조적 차단.)
        //   직렬화: 레코드 구분 U+001E, 필드 구분 U+001F, 필드 순서 level/rssi/name.
        @Volatile var detectedSnapshot: String = ""   // "levelrssiname" 레코드, 구분 
        @Volatile var detectedCount: Int       = 0    // 현재 경보 중(alertState) 기기 수
        // [v1.0.42 Req2] 내 장비(Local) 상태 스냅샷 — 수신(Target) 경로와 완전 분리된 단일 소스.
        //   직렬화 필드 순서 = category / state / speedKmh (필드 구분 U+001F).
        //   오직 내 송출 상태(myCategory + bleAdvertiser TX)에서만 갱신 — 상대 페이로드가 절대 못 건드린다.
        @Volatile var localSnapshot: String    = ""
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

    // [v1.0.39] 최근 windowMs(기본 0.5초) 동안 받은 RSSI 중 최댓값(가장 가까운 신호)을 반환.
    //   oneSecBuffer 는 매 프레임 oneSecAvgRssi() 에서 (시각,rssi)로 채워지므로 그대로 재활용한다.
    //   TTC 긴급 선발령 게이트 전용 — '직전 0.5초 피크'가 위험권(rssiDanger=-55)일 때만 긴급을 허용해
    //   멀리서 칼만 속도 추정만으로 긴급이 새어 나오는 것을 차단한다. (버퍼 비면 null)
    private fun recentPeakRssi(deviceId: String, windowMs: Long = 500L): Int? {
        val buf = oneSecBuffer[deviceId] ?: return null
        val now = System.currentTimeMillis()
        return buf.filter { now - it.first <= windowMs }.maxOfOrNull { it.second }
    }

    // [v1.0.42] ttcFeedbackMap / LEARN_RATE 제거 — pathLossExp 온라인 학습(거리 모델 자가학습) 폐지.

    private val recedingStartMap = mutableMapOf<String, Long>()
    private val RECEDING_CLEAR_MS = 2500L

    private val peakAlertRssiMap  = mutableMapOf<String, Int>()
    private val RECEDING_DBM_DROP = 5
    // 기기별 마지막 avgRssi 보관 — 플로팅 위젯 최우선 기기 선정·정렬에 사용
    private val deviceRssiMap     = mutableMapOf<String, Int>()

    // ── [v1.0.42 Req3] RSSI 동적 슬립/웨이크 (송출 전력 관리) ─────────────────
    //   모든 타겟 RSSI ≤ SLEEP_RSSI_DBM(-90)/신호 없음 → 광고 슬립(연속 송출 중단, 하트비트만).
    //   하나라도 RSSI ≥ WAKE_RSSI_DBM(-89) → 0ms 즉시 웨이크(연속 광고 재개 + LocalState 강송출).
    //   스캔(RX)은 절대 멈추지 않으므로 접근 감지/웨이크는 항상 살아 있다.
    private val WAKE_RSSI_DBM    = -89          // 이 값 이상(가까움)이면 즉시 웨이크
    private val SLEEP_RSSI_DBM   = -90          // 모든 신호가 이 값 이하면 슬립 (경계: -89/-90 정수 간격 0)
    private val SIGNAL_STALE_MS  = 6_000L       // 이보다 오래된 RSSI 표본은 '신호 없음'으로 간주
    private val ADV_POWER_EVAL_MS = 2_500L      // 송출 전력 주기 평가 간격
    // deviceId → (최근 effectiveRssi, 기록 시각ms). 웨이크 판단/슬립 평가의 단일 소스.
    private val wakeRssiMap = mutableMapOf<String, Pair<Int, Long>>()
    private val advPowerHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // [v1.0.42 Req5] dev_settings 변경 라이브 전파 리스너 — 강한 참조로 보관(필드).
    //   SharedPreferences 가 리스너를 WeakReference 로 들고 있어 지역변수로 두면 GC 되어 끊긴다.
    private val devPrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> applyLiveSettings(key) }

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

    // ── [v1.0.35 민감도 지연(Time-Gate)] + [v1.0.36 코너링 연장 · 충돌 기하학 필터] ──────────
    // Time-Gate: 위험권 진입 후에도 2D 칼만 미분(kfVel, dBm/s)이 APPROACH_TIMEGATE_VEL_DBM 이상
    //   '가까워짐'을 APPROACH_TIMEGATE_MS(0.5초) 연속 유지할 때만 신규/격상 경보를 발령한다.
    //   → 전파 튐(single-frame spike)으로 인한 즉각 오알람을 차단. 쿨다운 재알람·0x02 특수경보·
    //     TTC 선발령에는 적용하지 않는다(끊김 방지/즉각 안전 — 각 경로가 위에서 먼저 return).
    // [v1.0.42 Req5] Time-Gate 지연 시간 — DevSettings 에서 라이브로 읽는다(앱 재시작 없이 반영,
    //   기본 500L=기존값 그대로). 게이트 판정 로직(아래 processAlert)은 일절 손대지 않고 '값의 출처'만
    //   상수→설정으로 옮긴다 → 칼만/3중 하드게이트/기하학 판정 보존.
    private val APPROACH_TIMEGATE_MS: Long get() = DevSettings.timeGateMs   // 신규/격상 경보 전 최소 연속 접근 시간(평상)
    private val APPROACH_TIMEGATE_VEL_DBM = 0.5    // '가까워짐' 판정 최소 접근속도(dBm/s)
    // [v1.0.36] 코너링 중 Time-Gate 연장 — 내 장비가 급회전 중이면 전파가 일시 출렁이므로
    //   오작동 방지를 위해 0.5초 → 1.0초로 일시 연장한다(ImuFusion.isCornering 으로 판정).
    private val APPROACH_TIMEGATE_CORNERING_MS = 1000L
    // [v1.0.36] 충돌 기하학 필터(Collision Geometry) 파라미터.
    //   합산 접근속도(내속도+상대속도, km/h)를 RSSI 변화율(dBm/s)로 환산해 실제 kfVel 과 대조한다.
    //   단위 환산계수는 위험권(~6m)·경로손실지수(n≈2.5) 근사 — 현장 튜닝 대상.
    private val CLOSING_KMH_TO_DBMS        = 0.5   // 합산속도(km/h) → 예상 접근(dBm/s) 환산계수
    private val COLLISION_MIN_CLOSING_KMH  = 1.0   // 합산속도 이 미만이면 기하 판정 불가(보류 안 함)
    private val COLLISION_HEAD_ON_RATIO    = 0.6   // 실제/예상 접근비 이상 → 정면충돌(Time-Gate 즉시통과)
    private val COLLISION_SIDE_RATIO       = 0.3   // 실제/예상 접근비 이하 → 측면/나란히(보류 후보)
    private val COLLISION_ABS_SAFE_VEL_DBM = 2.0   // 이 이상 빠른 접근이면 측면판정 무시(false negative 방지)
    private val approachStreakStartMap    = mutableMapOf<String, Long>()  // 연속 접근 시작 시각(ms)

    // [v1.0.36] 속도 송신 폴링 — ImuFusion.estimatedSpeedKmh 를 주기적으로 advertiser(Speed 4비트)에 push.
    //   advertiser 내부 2초 throttle·미세변화 무시와 맞물려 실제 재광고는 드물게 일어난다.
    private val SPEED_PUSH_INTERVAL_MS    = 1500L
    private val speedPushHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val speedPushRunnable = object : Runnable {
        override fun run() {
            // [v1.0.39] EPJ 는 물리 최고속도 3km/h 가정 → 송출 속도를 cap (충돌예측 과대추정 방지).
            val txSpeed = if (myCategory == BleConstants.CAT_EPJ)
                minOf(ImuFusion.estimatedSpeedKmh, BleConstants.EPJ_MAX_SPEED_KMH.toFloat())
            else ImuFusion.estimatedSpeedKmh
            bleAdvertiser?.updateSpeed(txSpeed)
            broadcastLocalState()   // [v1.0.42 Req2] 주기 갱신 — Local UI(상태/속도) 폴링 소스 최신 유지
            speedPushHandler.postDelayed(this, SPEED_PUSH_INTERVAL_MS)
        }
    }

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
        // [v1.0.42 의미 재정의] IMU 3-State 모션 변화 → 송신 STATE(PSTATE_*) 매핑 후 광고 갱신.
        //   정지(0x00) → PSTATE_IDLE(정지·일반), 일반이동(0x01)·급변(0x02) → PSTATE_FORWARD(전진·주행).
        //   IMU 는 후진/하역을 판별할 수 없으므로 PSTATE_REVERSE / PSTATE_LOADING 는
        //   ACTION_TEST_STATE(또는 차량 통합)로만 수동 주입한다.
        //   ※ 급정거로 인한 빠른 접근은 State 가 아니라 충돌 기하학 필터(Speed+kfVel)가 감지한다.
        ImuFusion.onMotionStateChanged = { code ->
            val pState = if (code == BleConstants.MOTION_STATE_STATIONARY)
                BleConstants.PSTATE_IDLE else BleConstants.PSTATE_FORWARD
            bleAdvertiser?.updateState(pState)
            broadcastLocalState()   // [v1.0.42 Req2] 내 상태 변화 즉시 Local UI 전파
        }
        // [v1.0.36] 속도 송신 폴링 시작 — 주기적으로 내 예상속도를 광고 Speed 4비트로 공유.
        speedPushHandler.post(speedPushRunnable)
        DevSettings.registerOnChange(devPrefsListener)   // [v1.0.42 Req5] 설정 라이브 전파 구독
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
            // [v1.0.42] 개발자 수동 STATE 주입 — 후진(PSTATE_REVERSE=2)/하역(PSTATE_LOADING=3) 특수상태.
            //   송신 무결성을 2-기기 현장 테스트로 검증하기 위한 훅(평상 복귀=PSTATE_IDLE).
            //   예) adb shell am startservice -n .../BleService -a ACTION_TEST_STATE --ei extra_pstate 2
            ACTION_TEST_STATE -> {
                val s = intent.getIntExtra(EXTRA_PSTATE, BleConstants.PSTATE_IDLE)
                bleAdvertiser?.updateState(s)
                broadcastLocalState()   // [v1.0.42 Req2] 수동 STATE 주입 즉시 Local UI 반영
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
            "칼만(위험 ${BleConstants.rssiDanger}dBm / 경고 ${BleConstants.rssiWarning}dBm)"
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
                broadcastLocalState()   // [v1.0.42 Req2] 송출 시작 시점 Local 상태 초기 전파
                startAdvPowerManager()  // [v1.0.42 Req3] RSSI 기반 송출 전력 관리(슬립/웨이크) 시작
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
                        override fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int, remoteState: Int, remoteSpeedKmh: Double) {
                            lastScanResultMs = System.currentTimeMillis()

                            if (myMode == "WALKER"
                                && deviceId.startsWith(BleConstants.WALKER_PREFIX)
                                && !DevSettings.walkerDetectsWalker) return

                            val effectiveRssi = if (DevSettings.debugMode) DevSettings.simulatedRssi else rssi
                            noteRssiForWake(deviceId, effectiveRssi)   // [v1.0.42 Req3] 근접 신호 → 즉시 웨이크 판단
                            processAlert(deviceId, effectiveRssi, remoteState, remoteSpeedKmh)
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
                            wakeRssiMap.remove(deviceId)              // [v1.0.42 Req3] 슬립/웨이크 표본 정리
                            approachStreakStartMap.remove(deviceId)   // [v1.0.35] Time-Gate 접근 추적 정리
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

    private fun processAlert(deviceId: String, rssi: Int, remoteState: Int = 0x00, remoteSpeedKmh: Double = 0.0) {
        // [v1.0.36] 수신 1바이트 페이로드 언패킹 → Category / State / Speed(4비트).
        //   remoteState 는 BleScanner 가 ServiceData 1바이트를 0~255 로 그대로 넘긴 값.
        //   remoteSpeedKmh = 상대 송신 예상속도(km/h). 충돌 기하학 필터의 합산속도에 사용.
        val rCategory = BleConstants.decodeCategory(remoteState)
        val rState    = BleConstants.decodeState(remoteState)
        deviceCategoryMap[deviceId] = rCategory   // 표시 라벨(보행자/EPJ/지게차) 판별용 캐시

        // [v1.0.42] UWB 거리→RSSI 환산(calibRssiAt1m/pathLossExp 의존) 제거.
        //   거리 추정은 칼만 필터(RSSI)만으로 수행 — 수신 raw RSSI 를 그대로 전처리 파이프라인에 투입.
        //   (UWB 주소 교환 세션은 유지하되, ToF 거리는 더 이상 경보 판정에 사용하지 않는다.)
        val inputRssi: Int = rssi

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
            rssiPreFilter.clear(deviceId)     // [v1.0.38 클린업] 미추적 기기 EMA 전처리 상태 정리
            kalmanFilters.remove(deviceId)    // [v1.0.38 클린업] 미추적 기기 칼만 인스턴스 정리(stale 재등장 방지)
            return
        }

        // ── [v1.0.42] 후진·하역 특수경보 (최우선 분기 · 하이브리드 교차검증) ──────────
        // 상대 remoteState 가 후진(REVERSE=10)/하역(LOADING=11)이고 'smoothedRssi(EMA)와 avg1sec(raw 1초평균)'이
        // 둘 다 위험권(rssiDanger=-55) 이상(가까움)일 때만 TTC·속도·방향·절대거리 가드를 무시하고 즉시 DANGER 격상.
        //   ★ v1.0.32: 거리 판정을 kfRssi(칼만) → smoothedRssi(=preFiltered, EMA 출력)로 변경.
        //     칼만 lag 로 실제보다 가깝게 떠 있는 원거리 오발을 줄이고 지침(smoothedRssi 기준)을 준수.
        //   ★ v1.0.33: smoothedRssi 에 avg1sec(raw) 를 논리곱으로 결합(하이브리드). 이탈 중 기기는
        //     fall α=0.05 의 EMA 지연(lag)으로 smoothedRssi 가 한동안 위험권 위로 떠 있어 'DANGER 잔상
        //     (Ghost Danger)'을 낼 수 있는데, 반응이 빠른 raw 1초평균이 이미 멀어졌으면(rssiDanger 미만)
        //     즉시 차단해 이탈 기기의 과경보 잔상을 완전히 제거한다.
        //   ★ v1.0.39: 즉시 격상 임계를 구 SUDDEN_ALERT_RSSI_THRESHOLD(-60) → rssiDanger(-55)로 통일.
        // 표시문자열을 makeStateLabel(후진·하역 경보 문구)로 덮어써 오버레이·목록에 출력.
        if ((rState == BleConstants.PSTATE_REVERSE || rState == BleConstants.PSTATE_LOADING)
            && preFiltered >= BleConstants.rssiDanger
            && avg1sec     >= BleConstants.rssiDanger) {
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
        //   ★ v1.0.39: 긴급(DANGER) 선발령 게이트 추가 — '직전 0.5초 동안 받은 RSSI 중 최댓값(피크)'이
        //     위험권(rssiDanger=-55) 이상일 때만 긴급을 허용한다. 이로써 멀리(-75 부근)서 칼만 속도
        //     추정만으로 긴급이 새어 나오던 문제를 차단한다. (버퍼 비면 avg1sec 로 폴백)
        if (stableLevel == BleConstants.LEVEL_WARNING
            && newState == TrackingState.APPROACHING
            && !ImuFusion.isStationary
            && avg1sec >= BleConstants.rssiWarning
            && (recentPeakRssi(deviceId, 500L) ?: avg1sec) >= BleConstants.rssiDanger) {
            val ttc = estimateTTC(kfRssi, kfVel)
            if (ttc != null && ttc <= TTC_THRESHOLD_SEC) {
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

        // ── [v1.0.35 Time-Gate] + [v1.0.36 코너링 연장 · 충돌 기하학 필터] — 신규/격상 경보 한정 ──
        // 여기 도달 = shouldAlert(신규/격상/쿨다운경과) 통과. 이 중 '신규(첫 감지)·격상'에만
        // 아래 두 보수 조건을 추가로 요구한다:
        //   (1) Time-Gate: 2D 칼만 미분(kfVel)이 0.5dBm/s 이상 '가까워짐'을 일정시간 연속 유지할 것.
        //       전파 튐(1프레임 spike)으로 위험권에 잠깐 닿은 것만으론 소리/화면 경보하지 않는다.
        //       [v1.0.36] 내 장비가 코너링(급회전) 중이면 전파가 출렁이므로 0.5→1.0초로 일시 연장.
        //   (2) 충돌 기하학 필터: 합산 접근속도(내속도+상대속도, km/h)를 dBm/s 로 환산한
        //       '예상 접근속도'와 실제 kfVel 을 대조한다.
        //         · 근접(실제/예상 ≥ 0.6) = 정면충돌 코스 → Time-Gate 즉시 통과(강한 발령).
        //         · 현저히 낮음(≤ 0.3) = 나란히/직각 교차 안전 코스 → 신규·격상 경보 보류.
        //       단 빠르게 접근 중(kfVel ≥ 2.0dBm/s)이면 측면판정 무시 — 오인억제(사고) 방지.
        //       합산속도가 미미(<1km/h, 양쪽 거의 정지)하면 기하 판정을 건너뛰고 순수 Time-Gate 로 폴백.
        // ※ 신규 기기는 통과 전까지 alertState 에 등록되지 않으므로(아래 Pair 할당이 이 블록 뒤),
        //   매 프레임 isFirstDetection=true 로 재평가되며 approachStreak 이 자연히 누적된다.
        // ※ 0x02 특수경보·TTC 선발령은 위에서 이미 즉시 발령·return → 본 게이트 영향을 받지 않는다.
        // ※ 쿨다운 재알람(추적중·동급)은 isEscalation=false → 면제(기존 동작 유지, 끊김 방지).
        // ※ 3중 하드게이트(min(칼만,raw,EMA))는 위에서 이미 통과 — 본 필터는 그와 독립적으로
        //   '신규 격상'의 발령 타이밍만 조정할 뿐, 경보 레벨은 오직 RSSI 게이트가 결정한다.
        val timeGateMs = if (ImuFusion.isCornering) APPROACH_TIMEGATE_CORNERING_MS else APPROACH_TIMEGATE_MS
        val kfApproaching = kfVel >= APPROACH_TIMEGATE_VEL_DBM
        if (kfApproaching) {
            approachStreakStartMap.putIfAbsent(deviceId, now)
        } else {
            approachStreakStartMap.remove(deviceId)   // 접근 끊김 → streak 리셋
        }
        val approachStreakMs = if (kfApproaching) now - (approachStreakStartMap[deviceId] ?: now) else 0L

        // [v1.0.36] 충돌 기하학 — 합산 접근속도(km/h)를 dBm/s 로 환산해 실제 kfVel 과 대조.
        // [v1.0.39] EPJ 3km/h cap — 내가 EPJ면 내 속도를, 상대가 EPJ면 상대 속도를 3km/h 로 가정해
        //   합산 접근속도를 과대추정하지 않도록 양단 모두 제한한다.
        val mySpeedKmh      = (if (myCategory == BleConstants.CAT_EPJ)
                                  minOf(ImuFusion.estimatedSpeedKmh, BleConstants.EPJ_MAX_SPEED_KMH.toFloat())
                              else ImuFusion.estimatedSpeedKmh).toDouble()
        val remoteSpeedCap  = if (rCategory == BleConstants.CAT_EPJ)
                                  minOf(remoteSpeedKmh, BleConstants.EPJ_MAX_SPEED_KMH) else remoteSpeedKmh
        val closingSpeedKmh = mySpeedKmh + remoteSpeedCap                      // 예상 최대 접근속도(km/h)
        val expectedKfVel   = closingSpeedKmh * CLOSING_KMH_TO_DBMS            // → 예상 RSSI 접근속도(dBm/s)
        val closingRatio    = if (expectedKfVel > 0.01) kfVel / expectedKfVel else 0.0
        val geometryValid   = closingSpeedKmh >= COLLISION_MIN_CLOSING_KMH     // 양쪽 거의 정지면 판정 불가
        // 정면충돌 코스: 실제 접근이 예상의 60% 이상 → Time-Gate 즉시 통과(강한 발령).
        val headOnCourse    = geometryValid && closingRatio >= COLLISION_HEAD_ON_RATIO
        // 측면/나란히: 실제 접근이 예상의 30% 이하 + 절대 접근속도도 느림(<2.0) → 보류(경계 격하).
        val sideCourse      = geometryValid && closingRatio <= COLLISION_SIDE_RATIO &&
                              kfVel < COLLISION_ABS_SAFE_VEL_DBM

        // headOn 이면 Time-Gate 즉시 통과, 아니면 평상/코너링 Time-Gate 충족 필요.
        val approachSustained = headOnCourse || (kfApproaching && approachStreakMs >= timeGateMs)

        val isEscalation = isFirstDetection || levelEscalated
        if (isEscalation && (sideCourse || !approachSustained)) {
            Log.d(TAG, "[v1.0.36] 경보 보류 ${extractDisplayName(deviceId)}: side=$sideCourse 접근지속=${approachStreakMs}ms(<${timeGateMs}) ratio=%.2f 합산=%.1fkm/h vel=%.2f".format(closingRatio, closingSpeedKmh, kfVel))
            return   // 소리/화면 경보 보류 — 다음 프레임 재평가(접근지속 충족 또는 정면충돌 코스 시 발령)
        }

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

    // [v1.0.42] learnFromTTCFeedback() 제거 — pathLossExp(경로손실지수) 온라인 학습 폐지.
    //   거리 추정은 칼만 필터(RSSI)만으로 수행하므로 거리 모델 자가학습 루프는 불필요.
    //   (호출처 없는 死코드였으며 TTC 선발령 경보 동작에는 영향 없음.)

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
     * v1.0.42 특수상태(후진·하역) 경보 표시문자열 - Category/State 조합.
     *   후진(REVERSE): 지게차는 "{이름} 지게차 후진 중! 주의!", 그 외 "{이름} {역할} 후진 중! 주의!"
     *   하역·작업(LOADING): 지게차는 "{이름} 상부 고소 작업 중! 낙하물 주의!", 그 외 "{이름} {역할} 하역·작업 중! 주의!"
     *   ※ 정지·일반(IDLE)·전진·주행(FORWARD)은 특수경보가 아니므로 이 함수는 호출되지 않는다(폴백만).
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

        // [v1.0.42] 폴백 동기화 소스 갱신 — 브로드캐스트가 누락돼도 MainActivity 폴링이 이 값을 읽는다.
        detectedSnapshot = sb.toString()
        detectedCount    = sorted.size

        // [v1.0.42] setPackage 로 '명시적' 브로드캐스트화 → RECEIVER_NOT_EXPORTED 수신자와 확실히 호환.
        sendBroadcast(Intent(BROADCAST_DETECTED).setPackage(packageName).apply {
            putExtra(EXTRA_DEVICE_LIST, sb.toString())
            putExtra(EXTRA_DEVICE_COUNT, sorted.size)
        })
    }

    // ── [v1.0.42 Req2] 내 장비(Local) 상태 전파 — 수신(Target) 경로와 완전 분리 ──────────
    private var lastLocalSnapshot = ""

    /**
     * 내 장비(Local) 상태 스냅샷 갱신 + 전파.
     *   bleAdvertiser 가 '실제 송출 중'인 category/state/speed 를 읽어 직렬화한다(필드 구분 U+001F).
     *   값 변화가 있을 때만 브로드캐스트(중복 억제). 폴백용 static localSnapshot 은 항상 최신으로 유지.
     *   ※ 이 함수는 오직 내 송출 상태에서만 값을 만든다 — 상대 페이로드(Target)가 끼어들 여지가 구조적으로 없다.
     */
    private fun broadcastLocalState() {
        val adv = bleAdvertiser
        val cat = adv?.txCategory ?: myCategory
        val st  = adv?.txState   ?: BleConstants.PSTATE_IDLE
        val sp  = adv?.txSpeedKmh ?: 0.0
        val snap = "$cat${31.toChar()}$st${31.toChar()}${"%.1f".format(sp)}"
        localSnapshot = snap
        if (snap == lastLocalSnapshot) return
        lastLocalSnapshot = snap
        sendBroadcast(Intent(BROADCAST_LOCAL_STATE).setPackage(packageName)
            .putExtra(EXTRA_LOCAL_STATE, snap))
    }

    // ── [v1.0.42 Req3] RSSI 동적 슬립/웨이크 구동부 ──────────────────────────
    /** onDeviceDetected 마다 호출 — 최근 RSSI 표본 기록 + 근접(≥WAKE)이면 0ms 즉시 웨이크. */
    private fun noteRssiForWake(deviceId: String, rssi: Int) {
        wakeRssiMap[deviceId] = Pair(rssi, System.currentTimeMillis())
        if (rssi >= WAKE_RSSI_DBM) wakeAdvertiser()
    }

    /** 슬립 중인 광고자를 즉시 깨운다(연속 광고 재개 + 최신 LocalState 강송출). */
    private fun wakeAdvertiser() {
        val adv = bleAdvertiser ?: return
        if (adv.isPaused) {
            adv.resumeAdvertising()
            broadcastLocalState()
            Log.d(TAG, "RSSI 웨이크: 근접 신호 → 광고 즉시 재개")
        }
    }

    /** ADV_POWER_EVAL_MS 주기로 송출 전력(슬립/웨이크)을 재평가하는 루프 시작. */
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
     * 근접 신호 유무로 광고자를 슬립/웨이크 재평가.
     *  - 신선한 표본 중 하나라도 RSSI ≥ WAKE 거나 활성 경보 존재 → 웨이크(연속 광고).
     *  - 모두 ≤ SLEEP(또는 표본 없음) + 경보 없음 → 슬립(하트비트 모드).
     *  오래된(>SIGNAL_STALE_MS) 표본은 평가하며 제거한다.
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
        when {
            anyNear || hasAlert -> if (adv.isPaused) {
                adv.resumeAdvertising(); broadcastLocalState()
                Log.d(TAG, "RSSI 웨이크(평가): 근접/경보 → 연속 광고 재개")
            }
            else -> if (!adv.isPaused) {
                adv.pauseAdvertising()
                Log.d(TAG, "RSSI 슬립(평가): 근접 신호 없음 → 하트비트 모드")
            }
        }
    }

    /**
     * [v1.0.42 Req5] dev_settings(SharedPreferences) 변경을 앱/서비스 재시작 없이 즉시 반영.
     *   · 타겟별 차등 반경(rssiWarning/rssiDanger), Time-Gate 지연(timeGateMs), 검출모드/블렌드는
     *     processAlert 가 매 프레임 라이브 getter 로 읽으므로 그 자체로 자동 반영된다.
     *   · 여기서는 추가로 KalmanFilter 인스턴스에 프리셋을 즉시 재주입한다 — 현재 미검출(대기)이라
     *     다음 프레임을 못 받는 필터까지 곧바로 갱신(BleService + KalmanFilter 양쪽 라이브 적용).
     *   ※ 칼만/3중 하드게이트/기하학 판정 로직은 건드리지 않는다 — '파라미터 값'만 라이브로 바꾼다.
     */
    private fun applyLiveSettings(changedKey: String?) {
        val preset = DevSettings.kalmanPreset
        kalmanFilters.values.forEach { it.updatePreset(preset) }
        Log.d(TAG, "[Req5] 설정 라이브 반영(key=$changedKey): KF프리셋=$preset 위험=${BleConstants.rssiDanger}dBm 경고=${BleConstants.rssiWarning}dBm TimeGate=${DevSettings.timeGateMs}ms")
        sendStatusBroadcast("설정 라이브 반영됨")
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
        speedPushHandler.removeCallbacks(speedPushRunnable)   // [v1.0.36] 속도 송신 폴링 중지
        ImuFusion.stop()
        uwbRanger?.stop(); uwbRanger = null
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        alertState.clear()
        suddenLabelMap.clear()
        deviceCategoryMap.clear()
        broadcastDeviceList(force = true)   // [v1.0.26 Req2] 서비스 중지 → 빈 목록 송출('감지 없음' 반영)
        localSnapshot = ""; lastLocalSnapshot = ""   // [v1.0.42 Req2] 내 장비(Local) 스냅샷 초기화
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
        approachStreakStartMap.clear()   // [v1.0.35] Time-Gate 접근 추적 정리
        mutedDevices.clear()
        firebaseLastSaveMap.clear()
        testRunnable?.let { testHandler.removeCallbacks(it) }
        testRunnable = null
        muteHandler.removeCallbacksAndMessages(null)
        isMuted = false
        isMutedPublic = false
        healthCheckHandler.removeCallbacksAndMessages(null)
        advPowerHandler.removeCallbacksAndMessages(null)   // [v1.0.42 Req3] 송출 전력 평가 루프 중지
        wakeRssiMap.clear()
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
        DevSettings.unregisterOnChange(devPrefsListener)   // [v1.0.42 Req5] 설정 라이브 전파 해제
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
