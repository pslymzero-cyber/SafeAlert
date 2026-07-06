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
import android.media.AudioManager
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
import com.wf11.safealert.firebase.FirebaseManager
import com.wf11.safealert.utils.BeaconRegistry
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
        const val ACTION_MUTE_DEVICE   = "ACTION_MUTE_DEVICE"   // v1.0.25: 플로팅 터치 → 특정 기기 10초 음소거
        const val ACTION_TEST_STATE    = "ACTION_TEST_STATE"   // [v1.0.34] 개발자 수동 STATE 주입(후진/하역 예약비트 송신 테스트)
        const val ACTION_REAPPLY_UWB   = "ACTION_REAPPLY_UWB"  // (v1.1.38 A) 권한 부여·강제 토글 직후 UWB 세션 재평가 넛지
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
        //   직렬화 필드 순서 = category / state / turnDir (필드 구분 U+001F).   // [v1.1.7 #1] 속도→회전
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
    // [v1.0.46 #11] forceAlarmVolume 의 ignoringVolumeChange 해제(300ms) 전용 핸들러.
    //   muteHandler 공용이던 시절, muteTemporarily()의 removeCallbacksAndMessages(null)가 해제
    //   콜백까지 지워 ignoringVolumeChange=true 고착(볼륨버튼 무음 영구 무력화) 레이스가 있었다.
    private val volumeGuardHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var isMuted = false

    @Volatile private var activeSoundLevel = BleConstants.LEVEL_SAFE

    private fun getCurrentMaxLevel() =
        alertState.values.maxOfOrNull { it.first } ?: BleConstants.LEVEL_SAFE

    // [v1.1.37 ③] UWB↔RSSI 보정 학습·조회 키 — 역할쌍(카테고리쌍) 세그먼트.
    //   내 카테고리와 상대(스캔 캐시) 카테고리를 토큰화해 순서 무관하게 정렬·결합("×").
    //   같은 역할쌍(예 FORKLIFT×WALKER)은 안테나 높이·차폐 특성이 유사하다는 물리 모델 →
    //   한 지게차와 UWB로 학습한 편차를, 아직 UWB로 못 만난 다른 지게차의 RSSI 역산·임계 넛지에
    //   즉시 적용(사용자: "역할에 따른 데이터를 따로 저장 / 그 역할에 따른 데이터로 보정").
    //   상대 카테고리 미상(스캔 캐시 없음)이면 가장 보수적인 보행자로 간주.
    private fun uwbPairKeyFor(deviceId: String): String {
        val mine   = categoryToken(myCategory)
        val theirs = categoryToken(deviceCategoryMap[deviceId] ?: BleConstants.CAT_WALKER)
        return listOf(mine, theirs).sorted().joinToString("×")   // "×"
    }

    private fun categoryToken(cat: Int): String = when (cat) {
        BleConstants.CAT_FORKLIFT -> "FORKLIFT"
        BleConstants.CAT_EPJ      -> "EPJ"
        else                      -> "WALKER"
    }

    /**
     * [v1.1.37 ②] 부분 이탈 사운드 하향 정합 — 기기 '일부'만 제거된 뒤(alertState 비어있지 않음)
     *   남은 기기들의 실제 최대 레벨로 재생 중인 사운드를 즉시 맞춘다. 사이렌을 소유하던 상위(DANGER) 기기가
     *   이탈했는데 남은 기기는 더 낮은 레벨이면, 기존엔 canonical/fail-quiet 정정이 '남은 기기의 다음 프레임'
     *   에서야 동작해 상위 사이렌이 수 초~사실상 영구 잔존했다(사용자: '스쳐 지나갔으면 신호를 끄라고').
     *   소리를 '낮추는' 방향(remainingMax < activeSoundLevel)에서만 동작 → 남은 기기가 동급 이상이면 무개입
     *   = 경보 누락 0 보장. fail-quiet 강등 정정(processAlert L1625)의 teardown 판(版).
     */
    private fun resyncSoundToRemaining() {
        val remainingMax = getCurrentMaxLevel()
        if (remainingMax >= activeSoundLevel) return          // 남은 기기가 동급 이상 — 사이렌 유지
        AlertSoundPlayer.stopSound()                          // 이탈한 상위 기기의 stale 사이렌 즉시 정지
        activeSoundLevel = remainingMax
        if (remainingMax == BleConstants.LEVEL_WARNING && !isMuted) {
            if (DevSettings.vibrationEnabled) VibrationHelper.vibrateWarning(this)
            if (DevSettings.soundEnabled)     AlertSoundPlayer.playWarning(this)
        } else if (remainingMax <= BleConstants.LEVEL_SAFE) {
            VibrationHelper.stopVibration(this)
        }
        Log.d(TAG, "[v1.1.37 ②] 부분 이탈 사운드 하향 정합: activeSoundLevel→$remainingMax (남은 최대레벨)")
    }

    @Volatile private var ignoringVolumeChange = false

    // ── [v1.0.32] RssiPreFilter: 비대칭 비례제어(Asymmetric P-Control) EMA 전처리 ──
    // 칼만 필터 입력 전 1차 LPF. S_t = S_{t-1} + α·(R_t − S_{t-1}).
    //   비대칭 α: 강해짐(접근)=0.3 빠름 / 약해짐(난수)=0.05 느림 / D-Boost(prevVel>+2.0)=0.4 빗장개방.
    private val rssiPreFilter = RssiPreFilter()

    // ── [v1.0.45] MedianFilter: 비선형 순위통계 전처리 (임펄스 제거, EMA '앞단') ──
    // 철제랙 다중경로 단발 반사(+값 1프레임 튐)를 선형 단계(EMA→칼만) 진입 전에 구조적으로 제거.
    // 윈도우 N=3 → 그룹지연 약 1프레임. 게이트 3번째 다리(medianValue) 및 워밍업 가드의 기준.
    private val medianFilter = MedianFilter()

    // ── [v1.0.45] 후처리 P-EMA: 칼만 출력(kfRssi)의 거리(P)항 전용 비대칭 평활 ──
    // P-D 분리: D항(kfVel)은 위상선행이 생명이라 후필터 우회(Time-Gate 직결), P항(거리)은 평활 허용.
    // 상승α=0.4(접근 빠른 추종)/하강α=0.15(이탈 잔상 완화), D-Boost 미사용(속도는 칼만이 이미 반영).
    private val pEmaFilter = RssiPreFilter(alphaRise = 0.4, alphaFall = 0.15, dBoostEnabled = false)

    // ── Always-On 정책 (v1.0.24) ──────────────────────────────────────
    // PendingIntent 대기 모드 완전 폐기: 주변 기기 유무(SAFE 상태 포함)와 무관하게
    // 서비스는 사용자가 직접 '중지'를 누르기 전까지 절대 자동 종료(stopAll())하지 않고
    // 살아서 스캔을 유지한다(v1.0.37부터 상시 BALANCED). (현장 5초 기상 지연 → 0초 보장)

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
                        // [v1.0.46 #10] '중지' 직후 BT 토글 레이스 — 서비스가 이미 멈췄으면 BLE 부활 금지
                        if (!isRunning) return@postDelayed
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

    // [판정 파라미터] 쿨다운 — DevSettings 라이브 읽기(기본값=기존 하드코딩값, timeGateMs 선례)
    private val WARNING_COOLDOWN_MS: Long get() = DevSettings.warningCooldownMs
    private val DANGER_COOLDOWN_MS:  Long get() = DevSettings.dangerCooldownMs
    private val SCAN_HEALTH_CHECK_MS   = 15_000L

    @Volatile private var lastScanResultMs = 0L
    private val healthCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ── [v1.0.27] IMU 연동 동적 스캔 모드 (휴식/전투) ───────────────────────
    // 정지 5초 확정 → REST 절전(휴식). 이동 즉시 → ACTIVE 원복(전투).
    private val STATIONARY_ECO_DELAY_MS = 5_000L
    private val ecoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // [v1.1.12 L1] 접근(kfVel>0) 마지막 관측 시각(ms). 정지 직전 다가오던 기기를 절전 진입으로 놓치지 않기 위한 영속 신호.
    //   processAlert 가 매 프레임 갱신, isDangerPresent() 가 SIGNAL_STALE_MS 신선도로 평가. (lastScanResultMs 선례와 동일하게 @Volatile Long)
    @Volatile private var lastApproachAtMs = 0L
    private val ecoDowngradeRunnable = Runnable {
        // 5초 뒤에도 여전히 정지 + 위험 신호 전무일 때만 휴식 플래그 (근접/경보/접근 중이면 전투 유지)
        if (ImuFusion.isStationary && !isDangerPresent()) {
            bleScanner?.setEcoMode(true)
            // [v1.1.27] 스캔 한정 eco 폐지 → 스캔은 LOW_LATENCY 유지(등속 오판→첫 경고 지연 차단).
            //   절전은 광고(TX, evaluateAdvertiserPower)·배칭(화면 꺼짐)이 독립적으로 판단.
            Log.d(TAG, "정지 5초 경과 + 위험신호 없음 → 휴식 플래그(스캔은 LOW_LATENCY 유지)")
        }
    }

    // ── 2D 칼만 필터 맵 (v1.0.20: KalmanFilter, 거리+속도 동시 추적) ──
    private val kalmanFilters = mutableMapOf<String, KalmanFilter>()

    // ── [v1.0.45] 돌진 시 칼만 FAST 조건부 승격 ─────────────────────────
    // prevVel(직전 칼만 속도) > RUSH_FAST_VEL_DBM 이 '연속 RUSH_FAST_MIN_FRAMES 프레임' 지속되거나,
    // IMU 실가속(adaptiveQFactor ≥ RUSH_FAST_IMU_QFACTOR)이 동반될 때만 NORMAL→FAST 로 승격한다.
    //   ★ 가드레일: 단발 임펄스는 1프레임만 가짜속도를 만들므로(연속 2프레임 불충족) FAST 를 못 켠다
    //     → Median 의 임펄스 제거를 되돌리지 못한다. 돌진 종료 시 사용자 프리셋으로 자동 환원.
    private val rushFrameMap = mutableMapOf<String, Int>()
    private val RUSH_FAST_VEL_DBM     = 2.0   // 돌진 후보 프레임 판정: prevVel 이 이 값 초과
    private val RUSH_FAST_MIN_FRAMES  = 2     // 연속 N프레임 지속 시에만 FAST 승격(임펄스 차단)
    private val RUSH_FAST_IMU_QFACTOR = 2.0   // IMU 실가속 동반: adaptiveQFactor 이 이 값 이상이면 즉시 허용

    // ── [v1.1.16 D] 첫 접촉 DANGER 고속 발령(2프레임 확증) ────────────────────
    //   비콘은 페이로드가 없어 워밍업(Median 미충전, 약 1프레임) 중 신규 DANGER 진입이 발령 보류된다.
    //   raw(칼만·1초평균)가 2연속 프레임 위험권이면(단발 임펄스 차단) 워밍업·접근속도 게이트를 우회해
    //   즉시 1회 발령을 허용한다 → '가까이 두면 늦게/안 울림'을 근접 즉시 발령으로 전환.
    private val dangerContactStreakMap = mutableMapOf<String, Int>()
    // [v1.1.18] WARNING 거리도 동일한 raw 2프레임 확증 카운터 — 정지 근접도 Time-Gate·워밍업 우회하고 즉시 발령.
    //   effDanger ⊂ effWarning 이라 DANGER 거리도 자동 포함(fastDangerContact 상위호환). 단발 임펄스는 streak 1 에서 끊김.
    private val warningContactStreakMap = mutableMapOf<String, Int>()

    // ── TTC 파라미터 ──────────────────────────────────────────────────
    // [v1.0.25 Req2] 현장 초민감 오발령 해결 — 8.0초 → 3.0초로 대폭 강화 (충돌 임박 시에만 선발령)
    // [판정 파라미터] DevSettings 라이브 읽기(기본 3.0/0.5 = 기존값)
    private val TTC_THRESHOLD_SEC: Double get() = DevSettings.ttcThresholdSec
    // ★ RSSI 공간 부호 규칙: vel > 0 = RSSI 증가 = 접근 / vel < 0 = RSSI 감소 = 이탈
    private val MIN_APPROACH_VEL_DBM: Double get() = DevSettings.minApproachVelDbm  // TTC 계산 최소 접근 속도 (dBm/s)

    // ── 기기별 추적 상태 머신 (v1.0.20) ──────────────────────────────
    enum class TrackingState { APPROACHING, CROSSING, DEPARTING }
    private val trackingStateMap   = mutableMapOf<String, TrackingState>()
    private val crossingStartMap   = mutableMapOf<String, Long>()    // CROSSING 진입 시각
    private val departingStartMap  = mutableMapOf<String, Long>()    // DEPARTING 진입 시각

    // 상태 전환 파라미터 (RSSI 공간 기준)
    private val CPA_VEL_THRESHOLD             = 0.5   // CPA 판정 속도 임계 (dBm/s)
    private val CROSSING_CONFIRM_MS           = 1500L // CROSSING → DEPARTING 확정 대기
    private val DEPARTING_REENTRY_COOLDOWN_MS = 5000L // DEPARTING 후 재진입 최소 대기
    // [판정 파라미터] DevSettings 라이브 읽기(기본 8 = 기존값)
    private val DEPARTING_HYSTERESIS_DBM: Int get() = DevSettings.departingHysteresisDbm // DEPARTING 중 재경보 추가 마진 (dBm)

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
    // [판정 파라미터] 페이드아웃 해제 — DevSettings 라이브 읽기(기본 1500L/4, v1.1.14 교행후 잔존 단축)
    private val RECEDING_CLEAR_MS: Long get() = DevSettings.recedingClearMs

    // [v1.1.6] 이탈 판정 재설계 — raw 절대최대 피크는 초근접 BLE 노이즈(±5~10dBm)에 고착돼
    //   '위험 시 가짜 이탈 → 소리 꺼짐'(v1.1.5 회귀)을 유발했다. 중간평활 EMA 레퍼런스(recedeRefMap)
    //   로 노이즈를 흡수하고, 피크는 정체 시 ref 로 느리게 감쇠(recedePeakMap)시켜 가짜 이탈을 자동 해소.
    private val recedeRefMap   = mutableMapOf<String, Double>()  // 이탈 판정 전용 중간평활(EMA)
    private val recedePeakMap  = mutableMapOf<String, Double>()  // 피크 홀드 + 느린 감쇠
    private val RECEDE_REF_ALPHA = 0.3   // avg1sec → 중간평활 EMA 계수(초근접 노이즈 흡수)
    private val PEAK_DECAY_ALPHA = 0.05  // 피크 정체 시 ref 로 수렴하는 감쇠 계수(가짜 이탈 자동 해소)
    private val RECEDING_DBM_DROP: Int get() = DevSettings.recedingDbmDrop
    // 기기별 마지막 avgRssi 보관 — 플로팅 위젯 최우선 기기 선정·정렬에 사용
    private val deviceRssiMap     = mutableMapOf<String, Int>()

    // ── [v1.0.42 Req3] RSSI 동적 슬립/웨이크 (송출 전력 관리) ─────────────────
    //   모든 타겟 RSSI ≤ SLEEP_RSSI_DBM(-90)/신호 없음 → 광고 슬립(연속 송출 중단, 하트비트만).
    //   하나라도 RSSI ≥ WAKE_RSSI_DBM(-89) → 0ms 즉시 웨이크(연속 광고 재개 + LocalState 강송출).
    //   스캔(RX)은 절대 멈추지 않으므로 접근 감지/웨이크는 항상 살아 있다.
    // [판정 파라미터] WAKE/STALE — DevSettings 라이브 읽기(기본 -89/6000L = 기존값).
    //   슬립 판정은 '웨이크 조건 불충족'(아래 evalAdvPower)으로 구현돼 SLEEP_RSSI_DBM 은 실코드 미사용
    //   (문서 경계값) — 설정 노출에서 제외하고 상수로 둔다.
    private val WAKE_RSSI_DBM: Int get() = DevSettings.wakeRssiDbm  // 이 값 이상(가까움)이면 즉시 웨이크
    private val SLEEP_RSSI_DBM   = -90          // 모든 신호가 이 값 이하면 슬립 (경계: 웨이크-1, 정수 간격 0)
    private val SIGNAL_STALE_MS: Long get() = DevSettings.signalStaleMs  // 이보다 오래된 RSSI 표본은 '신호 없음'으로 간주
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

    // [v1.0.44] 수신한 상대 기기의 State(동적 상태) 캐시 — 디코드된 PSTATE_* 보관.
    //   평상 표시문구를 '정지 중(IDLE)=주변 대기' / '이동 중(FORWARD)=접근'으로 분기하는 데 쓴다.
    //   ※ 후진/하역(특수경보)은 suddenLabelMap(makeStateLabel)이 우선하므로 이 캐시에 의존하지 않는다.
    private val deviceStateMap    = mutableMapOf<String, Int>()

    // [v1.1.7 #1] 수신한 상대 기기의 회전 방향(TURN_*, bits 3:2 디코드) 캐시.
    //   (구 deviceSpeedMap: 속도 4비트 → v1.1.7 에서 회전 2비트로 재패킹. 표시 라벨/디버그용.)
    private val deviceTurnMap     = mutableMapOf<String, Int>()

    // [v1.1.7 #2] 후진(전진) 대비 — RX 측 RSSI 추세 반전 추론용 상태.
    //   reverseRssiHist: 기기별 (시각ms, avg1sec) 표본 윈도우. '안정/약화 → 급강세' 패턴 탐지.
    //   reversePrepUntil: 감지 시 now+holdMs 로 latch — 그 시각까지 "후진(전진)을 대비해주세요" 표시.
    private val reverseRssiHist   = mutableMapOf<String, ArrayDeque<Pair<Long, Int>>>()
    private val reversePrepUntil  = mutableMapOf<String, Long>()

    // [v1.0.30 Req3] Firebase 경보 저장 모바일데이터 방어 — 기기별 마지막 저장 시각(ms).
    //   같은 기기에 대해 FIREBASE_SAVE_THROTTLE_MS(1분) 안에는 재업로드하지 않는다.
    private val firebaseLastSaveMap = mutableMapOf<String, Long>()
    // [판정 파라미터] DevSettings 라이브 읽기(기본 60_000L/5 = 기존값)
    private val FIREBASE_SAVE_THROTTLE_MS: Long get() = DevSettings.firebaseThrottleMs

    private val HYSTERESIS_DBM: Int get() = DevSettings.hysteresisDbm

    // ── [v1.0.35 민감도 지연(Time-Gate)] + [v1.0.36 코너링 연장 · 충돌 기하학 필터] ──────────
    // Time-Gate: 위험권 진입 후에도 2D 칼만 미분(kfVel, dBm/s)이 APPROACH_TIMEGATE_VEL_DBM 이상
    //   '가까워짐'을 APPROACH_TIMEGATE_MS(0.5초) 연속 유지할 때만 신규/격상 경보를 발령한다.
    //   → 전파 튐(single-frame spike)으로 인한 즉각 오알람을 차단. 쿨다운 재알람·0x02 특수경보·
    //     TTC 선발령에는 적용하지 않는다(끊김 방지/즉각 안전 — 각 경로가 위에서 먼저 return).
    // [v1.0.42 Req5] Time-Gate 지연 시간 — DevSettings 에서 라이브로 읽는다(앱 재시작 없이 반영,
    //   기본 500L=기존값 그대로). 게이트 판정 로직(아래 processAlert)은 일절 손대지 않고 '값의 출처'만
    //   상수→설정으로 옮긴다 → 칼만/3중 하드게이트/기하학 판정 보존.
    private val APPROACH_TIMEGATE_MS: Long get() = DevSettings.timeGateMs   // 신규/격상 경보 전 최소 연속 접근 시간(평상)
    private val APPROACH_TIMEGATE_VEL_DBM: Double get() = DevSettings.timeGateVelDbm  // '가까워짐' 판정 최소 접근속도(dBm/s)
    // [v1.0.36] 코너링 중 Time-Gate 연장 — 내 장비가 급회전 중이면 전파가 일시 출렁이므로
    //   오작동 방지를 위해 0.5초 → 1.0초로 일시 연장한다(ImuFusion.isCornering 으로 판정).
    private val APPROACH_TIMEGATE_CORNERING_MS: Long get() = DevSettings.corneringTimeGateMs
    // [v1.0.36] 충돌 기하학 필터(Collision Geometry) 파라미터.
    //   합산 접근속도(내속도+상대속도, km/h)를 RSSI 변화율(dBm/s)로 환산해 실제 kfVel 과 대조한다.
    //   단위 환산계수는 위험권(~6m)·경로손실지수(n≈2.5) 근사 — 현장 튜닝 대상.
    //   [판정 파라미터] 환산계수·접근비 2종 — DevSettings 라이브 읽기(기본 0.5/0.6/0.3 = 기존값)
    private val CLOSING_KMH_TO_DBMS: Double get() = DevSettings.closingKmhToDbms  // 합산속도(km/h) → 예상 접근(dBm/s) 환산계수
    private val COLLISION_MIN_CLOSING_KMH  = 1.0   // 합산속도 이 미만이면 기하 판정 불가(보류 안 함)
    private val COLLISION_HEAD_ON_RATIO: Double get() = DevSettings.collisionHeadOnRatio  // 실제/예상 접근비 이상 → 정면충돌(Time-Gate 즉시통과)
    private val COLLISION_SIDE_RATIO:    Double get() = DevSettings.collisionSideRatio    // 실제/예상 접근비 이하 → 측면/나란히(보류 후보)
    private val COLLISION_ABS_SAFE_VEL_DBM = 2.0   // 이 이상 빠른 접근이면 측면판정 무시(false negative 방지)
    // [v1.1.21] 빠른 정면접근 Time-Gate 즉시통과 임계(dBm/s) — DevSettings 라이브 읽기(기본 2.0).
    //   headOnCourse(합산 km/h 미산출로 영구 false)를 칼만 접근속도로 대체하는 경로의 임계.
    private val FAST_APPROACH_BYPASS_VEL_DBM: Double get() = DevSettings.fastApproachBypassVelDbm

    // ── [v1.0.49 A/B 신규 기기 경보 지연 수정] ──────────────────────────────────────
    // #1 콜드 칼만 기하학 유예: 칼만 update 횟수가 이 값 미만이면 vel 이 아직 초기값(0.0) 부근이라
    //    closingRatio≈0 → sideCourse(측면) 오판정으로 돌진 기기를 보류시킨다. 워밍업 동안은
    //    측면판정만 무효화 — headOn 즉시통과·Time-Gate 는 그대로 둔다(보수 방향 유지).
    private val KALMAN_GEOMETRY_MIN_UPDATES = 5
    // #2 경고권 밖 필터 보존 밴드: 게이트(rssiWarning) 미달이라도 이 폭(dB) 안이면 필터 상태
    //    (Median·EMA·칼만·P-EMA)를 삭제하지 않고 보존 — 경고권 진입 '전'에 미리 수렴시켜 신규 기기의
    //    콜드스타트(Median 3프레임 + 칼만 vel 수렴 수초)를 제거한다. 경보 로직은 여전히 스킵(return)
    //    하므로 오경보 없음. 밴드 밖(원거리)은 기존대로 전삭제. 소실 기기 정리는 onDeviceLost 담당.
    private val FILTER_PRESERVE_BAND_DB: Int get() = DevSettings.filterPreserveBandDb  // [판정 파라미터] 기본 10 = 기존값
    // #3 게이트 보류 기기 목록 표시: 워밍업/기하학 보류로 alertState 등록 전인 기기를 '감지됨(SAFE)'
    //    행으로 하단 목록에 노출 — 경보 발령 전 불가시 구간 제거. 오버레이(topPriorityDevice)는
    //    경보 전용 의미를 지키기 위해 제외. TTL(스캐너 타임아웃 정렬) 경과 시 목록에서 자동 제거.
    private val PENDING_DISPLAY_TTL_MS = 6000L
    private val pendingDisplayMap = mutableMapOf<String, Long>()   // deviceId → 마지막 보류 시각(ms)

    private val approachStreakStartMap    = mutableMapOf<String, Long>()  // 연속 접근 시작 시각(ms)
    private val fastApproachStreakMap     = mutableMapOf<String, Int>()   // [v1.1.21] 빠른 정면접근 연속 프레임 수(2프레임 확증)

    // [v1.1.11 C1] 전진-접근 가산(forwardApproachBias) 히스테리시스 래치 — deviceId별 ON/OFF 상태.
    //   kfVel 이 APPROACH_TIMEGATE_VEL_DBM 근처를 떨릴 때 payloadOffset(±3dB)이 프레임마다 토글되어
    //   임계 부근에서 WARNING↔SAFE 가 깜빡이던 결함을 막는다. 진입은 임계 즉시(페일세이프),
    //   해제는 임계×RELEASE_FRAC 미만(데드밴드)으로만 — 비대칭 래치.
    private val forwardBiasLatchMap       = mutableMapOf<String, Boolean>()  // deviceId → 전진가산 래치 상태
    private val FORWARD_BIAS_VEL_RELEASE_FRAC = 0.5  // 해제 데드밴드 = 임계속도의 50%

    // [v1.0.36→v1.1.7 #1] 송신 폴링 — STATE(정지/이동) + Turn(좌/우/직진)을 주기적으로 advertiser 에 push.
    //   (구: Speed 4비트 → v1.1.7 회전 2비트로 재패킹. 상수명 SPEED_PUSH_* 는 폴링 주기 의미로 유지.)
    //   advertiser 내부 2초 throttle·미세변화 무시와 맞물려 실제 재광고는 드물게 일어난다.
    private val SPEED_PUSH_INTERVAL_MS: Long get() = DevSettings.speedPushIntervalMs  // [판정 파라미터] 기본 1500L = 기존값
    private val speedPushHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val speedPushRunnable = object : Runnable {
        override fun run() {
            // [v1.0.51 #2] STATE 자가 치유 동기화 — IMU 모션 통지는 '전이 순간'에만 오므로 한 번
            //   유실되면 다음 전이까지 낡은 상태로 고착될 수 있다. 매 폴링마다 최신 motionState 를
            //   다시 밀어 넣는다(동일 상태면 advertiser 내부에서 no-op → 재광고 비용 없음).
            //   단, 수동 주입 특수상태(후진/하역 — ACTION_TEST_STATE)는 자동 동기화가 덮지 않는다.
            //   updateTurn 보다 '먼저' 호출 — 공용 2초 throttle 슬롯을 STATE 변화가 우선 차지.
            val tx = bleAdvertiser?.txState ?: BleConstants.PSTATE_IDLE
            if (tx != BleConstants.PSTATE_REVERSE && tx != BleConstants.PSTATE_LOADING) {
                val pState = if (ImuFusion.motionState == BleConstants.MOTION_STATE_STATIONARY)
                    BleConstants.PSTATE_IDLE else BleConstants.PSTATE_FORWARD
                bleAdvertiser?.updateState(pState)
            }
            // [v1.1.7 #1] 속도 비트 제거 → IMU 회전(좌/우/직진) 추정값을 송출 페이로드에 탑재.
            bleAdvertiser?.updateTurn(ImuFusion.turnDirection)
            // [v1.1.14] 폴링 안전망 — 스캔이 잠시 끊겨도 내 최고 경보레벨을 위험상태(RISK)로 유지 송출.
            //   (주 송출은 onDeviceDetected 스캔주기. 동일레벨 no-op 라 중복 호출 무해.)
            bleAdvertiser?.updateRisk(getCurrentMaxLevel())
            broadcastLocalState()   // [v1.0.42 Req2] 주기 갱신 — Local UI(상태/회전) 폴링 소스 최신 유지
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
                    // 이동 감지 즉시(0초) → ACTIVE 원복(전투 모드)
                    bleScanner?.setEcoMode(false)
                    // [v1.1.26 A] 이동 시작 즉시 광고도 깨운다 — 다음 evaluateAdvertiserPower(주기 평가)
                    //   틱을 기다리지 않고 곧장 연속 광고로 올려 첫 접촉 송신 지연을 없앤다(슬립 아니면 no-op).
                    if (DevSettings.keepAdvertiseWhileMoving) wakeAdvertiser()
                    Log.d(TAG, "IMU 이동 감지 → 즉시 ACTIVE 복귀(전투 모드)")
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
        // [판정 파라미터] 전단 EMA 알파는 게터가 아닌 인스턴스 필드라 시작 시 1회 명시 주입 필요
        //   (이후 변경은 devPrefsListener → applyLiveSettings 가 갱신)
        applyEmaAlphas()
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
            ACTION_STOP       -> {
                // [v1.0.46 중지버그] 사용자가 직접 중지 → START_STICKY 복원 키를 동기(.commit) 제거.
                //   stopAll() 내부가 아닌 여기서만 지운다: onDestroy→stopAll() 경로(시스템 킬·앱 종료)는
                //   prefs 가 남아 있어야 Always-On 복원이 동작한다. device_id 는 사용자 식별자라 보존.
                getSharedPreferences("safealert_prefs", MODE_PRIVATE).edit()
                    .remove("running_mode")
                    .remove("running_since")
                    .remove("running_category")
                    .commit()
                stopAll()
            }
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
            // (v1.1.38 A) UWB 권한 부여·강제 토글 직후 재평가 — 동일값 SharedPreferences 쓰기는
            //   변경 리스너를 발화시키지 않으므로 명시 인텐트로 applyUwbLiveState 를 직접 호출한다.
            //   역할 미지정(myMode 공백) 상태면 startForeground 없이 부팅된 인스턴스이므로 안전 종료.
            ACTION_REAPPLY_UWB -> {
                if (myMode.isNotEmpty()) applyUwbLiveState() else stopSelf(startId)
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
            .commit()   // [v1.0.46 중지버그] 동기 저장 — .apply() 비동기 유실로 인한 복원/중지 불일치 방지
    }

    private fun applyMode() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter

        val modeStr = "칼만(위험 ${BleConstants.rssiDanger}dBm / 경고 ${BleConstants.rssiWarning}dBm)"
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
                bleAdv.startAdvertising(myId)
                // (v1.1.30) UWB 는 광고 시작 후 별도 적용 — 모드 전환 대비 이전 세션 정리 후 재생성
                uwbRanger?.stop(); uwbRanger = null
                applyUwbLiveState()
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
                        override fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int, remoteState: Int, remoteTurn: Int, payloadPresent: Boolean) {
                            lastScanResultMs = System.currentTimeMillis()

                            if (myMode == "WALKER"
                                && deviceId.startsWith(BleConstants.WALKER_PREFIX)
                                && !DevSettings.walkerDetectsWalker) return

                            val effectiveRssi = if (DevSettings.debugMode) DevSettings.simulatedRssi else rssi
                            noteRssiForWake(deviceId, effectiveRssi)   // [v1.0.42 Req3] 근접 신호 → 즉시 웨이크 판단
                            acquireDetectionWakeLock(effectiveRssi)   // [v1.1.13] 화면 꺼짐+근접(>=WAKE) → 처리체인 완주용 짧은 CPU 점유
                            // [v1.1.23] 동일 게이트로 스캔 배칭도 0ms 즉시 전달 승격 — wakelock 으로 CPU 를 깨워도
                            //   배칭 500ms 면 BLE 칩이 0.5s 모아 효과 반감되므로 함께 0ms 로 내린다(false 복귀는 평가주기 집계).
                            if (effectiveRssi >= WAKE_RSSI_DBM) bleScanner?.setHazardNear(true)
                            try {
                                processAlert(deviceId, effectiveRssi, remoteState, remoteTurn, payloadPresent)
                                // [v1.0.26 Req2] processAlert 가 alertState 를 어떻게 바꿨든(추가·격상·SAFE 제거·TTC 선발령)
                                // 그 직후 전체 스냅샷을 한 번에 송출 → 하단 목록이 플로팅·알람과 절대 어긋나지 않는다.
                                broadcastDeviceList()
                                // [v1.1.14] 내가 감지한 최고 경보레벨을 위험상태(RISK)로 즉시 재광고 → 상대 기기가
                                //   '교행 전에' 협력 격상(절충)으로 먼저 울리도록. 폴링(1.5s)보다 빠른 스캔주기 송출(onset↑).
                                //   updateRisk 는 동일레벨 no-op·상승 즉시·하강 0.5s throttle 라 매 스캔 호출 안전.
                                bleAdvertiser?.updateRisk(getCurrentMaxLevel())
                            } finally {
                                releaseDetectionWakeLock()   // [v1.1.13] 체인 종료 즉시 해제(발령 시 alertWakeLock 이 별도 인계)
                            }
                        }
                        override fun onDeviceLost(deviceId: String) {
                            Log.d(TAG, "신호 소실: $deviceId")
                            alertState.remove(deviceId)
                            rssiPreFilter.clear(deviceId)
                            medianFilter.clear(deviceId)      // [v1.0.45] Median 윈도우 정리
                            pEmaFilter.clear(deviceId)        // [v1.0.45] 후처리 P-EMA 상태 정리
                            rushFrameMap.remove(deviceId)     // [v1.0.45] 돌진 프레임 카운터 정리
                            dangerContactStreakMap.remove(deviceId)   // [v1.1.16 D] 첫접촉 DANGER 카운터 정리
                            warningContactStreakMap.remove(deviceId)  // [v1.1.18] 첫접촉 WARNING 카운터 정리
                            kalmanFilters[deviceId]?.reset()
                            kalmanFilters.remove(deviceId)
                            trackingStateMap.remove(deviceId)
                            crossingStartMap.remove(deviceId)
                            departingStartMap.remove(deviceId)
                            wasStationaryMap.remove(deviceId)
                            recedingStartMap.remove(deviceId)
                            recedeRefMap.remove(deviceId)
                            recedePeakMap.remove(deviceId)
                            deviceRssiMap.remove(deviceId)
                            wakeRssiMap.remove(deviceId)              // [v1.0.42 Req3] 슬립/웨이크 표본 정리
                            approachStreakStartMap.remove(deviceId)   // [v1.0.35] Time-Gate 접근 추적 정리
                            fastApproachStreakMap.remove(deviceId)    // [v1.1.21] 빠른접근 연속카운터 정리
                            forwardBiasLatchMap.remove(deviceId)      // [v1.1.11 C1] 전진가산 래치 정리(소실 → 누수 방지)
                            oneSecBuffer.remove(deviceId)   // [v1.0.31] 게이트가 raw도 push → 신호소실 시 함께 정리
                            mutedDevices.remove(deviceId)
                            suddenLabelMap.remove(deviceId)
                            deviceCategoryMap.remove(deviceId)
                            deviceStateMap.remove(deviceId)
                            deviceTurnMap.remove(deviceId); reverseRssiHist.remove(deviceId); reversePrepUntil.remove(deviceId)   // [v1.1.7 #1/#2]
                            firebaseLastSaveMap.remove(deviceId)
                            pendingDisplayMap.remove(deviceId)   // [v1.0.49 #3] 소실 기기 보류 표시 정리
                            uwbRanger?.onDeviceLost(deviceId)    // (v1.1.30) UWB 후보·세션 정리
                            sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                            if (alertState.isEmpty()) {
                                AlertSoundPlayer.stopSound()
                                VibrationHelper.stopVibration(this@BleService)
                                OverlayManager.hideOverlay()
                                activeSoundLevel = BleConstants.LEVEL_SAFE
                                sendStatusBroadcast("기기 이탈 → 경보 중지")
                            } else {
                                resyncSoundToRemaining()  // [v1.1.37 ②] 상위 기기 이탈 → 남은 최대레벨로 사운드 하향 정합
                                updateFloatingOverlay()   // 다른 위험 기기로 플로팅 전환
                            }
                            // [v1.1.14] 소실로 alertState 가 줄었으니 위험상태(RISK)도 즉시 갱신 송출(비었으면 SAFE).
                            bleAdvertiser?.updateRisk(getCurrentMaxLevel())
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
        val danger    = BleConstants.rssiDanger  - rssiOffset
        return when {
            // [v1.0.46 #1] 거리 기반 DANGER 복원 — v1.0.20 전면 재작성 때 문서화 없이 사라진 회귀.
            //   서행 접근(kfVel 미달 → TTC 미발동, 후진/하역 아님)이라도 위험권 진입이면 DANGER.
            rssi >= danger -> BleConstants.LEVEL_DANGER
            prevLevel >= BleConstants.LEVEL_DANGER && rssi >= danger - HYSTERESIS_DBM -> BleConstants.LEVEL_DANGER
            rssi >= warning -> BleConstants.LEVEL_WARNING
            prevLevel >= BleConstants.LEVEL_WARNING && rssi >= warning - HYSTERESIS_DBM -> BleConstants.LEVEL_WARNING
            else -> BleConstants.LEVEL_SAFE
        }
    }

    /**
     * v1.1.10 디코드된 16진수(역할·상태)로 경보 임계 위험 오프셋(dB)을 산출한다.
     *   반환값(+) 만큼 경고·위험 임계를 '먼 거리'로 당겨 조기 경보한다(fail-safe 방향).
     *   · Phase1(역할): 내가 보행자 ↔ 상대가 중장비(지게차/EPJ), 또는 그 반대면 walkerVsEquipBiasDb 가산(상호 보호).
     *   · Phase2(상태): 상대가 전진(FORWARD)하며 접근(kfVel) 중이면 forwardApproachBiasDb 추가 가산.
     * v1.1.11 C1: Phase2 가산을 deviceId별 히스테리시스 래치로 보호 — kfVel 이 임계 부근을 떨려도
     *   forwardBiasLatchMap 으로 가산이 한 번 켜지면 임계×RELEASE_FRAC 미만으로 떨어질 때까지 유지된다.
     *   payloadOffset(±forwardApproachBiasDb)이 프레임마다 토글되어 WARNING↔SAFE 가 깜빡이던 결함 제거.
     *   진입은 임계 즉시(페일세이프), 해제는 데드밴드 통과로만 — 비대칭.
     *   토글(categoryBiasEnabled/stateModulationEnabled)이 꺼져 있거나 해당 쌍·상태가 아니면 0(기존 거동).
     */
    private fun computePayloadRiskOffset(deviceId: String, rCategory: Int, rState: Int, kfVel: Double): Int {
        var offset = 0
        if (DevSettings.categoryBiasEnabled) {
            val iAmWalker   = myCategory == BleConstants.CAT_WALKER
            val iAmForklift = myCategory == BleConstants.CAT_FORKLIFT
            val iAmEpj      = myCategory == BleConstants.CAT_EPJ
            val rIsWalker   = rCategory == BleConstants.CAT_WALKER
            val rIsForklift = rCategory == BleConstants.CAT_FORKLIFT
            val rIsEpj      = rCategory == BleConstants.CAT_EPJ
            // [v1.1.14] 역할쌍 분리: 보행자↔지게차는 강한 조기경보(+6), 보행자↔EPJ는 완화(+2).
            //   EPJ 는 저속·동일공간 작업이라 지게차와 같은 임계를 쓰면 과경보 → 별도 오프셋으로 분리.
            //   [v1.1.24] 장비↔장비는 아래 equipVsEquipBiasDb 로 별도 부여, 보행자↔보행자만 0(기존과 동일).
            if ((iAmWalker && rIsForklift) || (iAmForklift && rIsWalker)) {
                offset += DevSettings.walkerVsEquipBiasDb
            }
            if ((iAmWalker && rIsEpj) || (iAmEpj && rIsWalker)) {
                offset += DevSettings.walkerVsEpjBiasDb
            }
            // [v1.1.24] 장비↔장비(지게차/EPJ 상호) — 양쪽 다 장비일 때만(보행자 분기와 상호배타).
            //   보행자 오프셋이 전부 보행자 전용이라 비어 있던 사각지대를 메움. 금속 캐빈 차폐 대응.
            val iAmEquip = iAmForklift || iAmEpj
            val rIsEquip = rIsForklift || rIsEpj
            if (iAmEquip && rIsEquip) {
                // [v1.1.25] EPJ↔EPJ 분리: 양쪽 다 EPJ(지게차 미포함)면 거리 변별용 별도 오프셋.
                //   EPJ 는 약차폐·저속(3km/h)·5m 공존 정상이라 +8 을 쓰면 과경보 → epjVsEpjBiasDb(기본 -2)로 낮춤.
                //   지게차가 한쪽이라도 끼면(강차폐·위험원) 기존 equipVsEquipBiasDb(+8) 유지.
                if (iAmEpj && rIsEpj) {
                    offset += DevSettings.epjVsEpjBiasDb
                } else {
                    offset += DevSettings.equipVsEquipBiasDb
                }
            }
        }
        // [v1.1.11 C1] 전진-접근 가산: kfVel 임계를 히스테리시스 래치로 감싼다.
        if (DevSettings.stateModulationEnabled && rState == BleConstants.PSTATE_FORWARD) {
            val wasLatched = forwardBiasLatchMap[deviceId] ?: false
            val latched = when {
                kfVel >= APPROACH_TIMEGATE_VEL_DBM                              -> true   // 진입: 임계 즉시(fail-safe)
                kfVel <  APPROACH_TIMEGATE_VEL_DBM * FORWARD_BIAS_VEL_RELEASE_FRAC -> false  // 해제: 데드밴드 통과
                else                                                           -> wasLatched  // 중간: 유지
            }
            forwardBiasLatchMap[deviceId] = latched
            if (latched) offset += DevSettings.forwardApproachBiasDb
        } else {
            forwardBiasLatchMap.remove(deviceId)  // FORWARD 아님/토글 OFF → 래치 리셋
        }
        return offset
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
        if (DevSettings.logVerbose)   // [v1.0.46 배터리(g)] 프레임당 로그 → verbose 게이트
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

    private fun processAlert(deviceId: String, rssi: Int, remoteState: Int = 0x00, remoteTurn: Int = BleConstants.TURN_STRAIGHT, payloadPresent: Boolean = false) {
        // [v1.0.36→v1.1.7 #1] 수신 1바이트 페이로드 언패킹 → Category / State / Turn(2비트).
        //   remoteState 는 BleScanner 가 ServiceData 1바이트를 0~255 로 그대로 넘긴 값.
        //   remoteTurn = 상대 송신 회전 방향(TURN_*, bits 3:2). 표시 라벨/디버그용(속도 비트는 제거됨).
        val rCategory = BleConstants.decodeCategory(remoteState)
        val rState    = BleConstants.decodeState(remoteState)
        val rRisk     = BleConstants.decodeRisk(remoteState)   // [v1.1.14] 상대가 송출한 위험 감지 레벨(LEVEL_*) — 양방향 협력 알림 수신측
        deviceCategoryMap[deviceId] = rCategory   // 표시 라벨(보행자/EPJ/지게차) 판별용 캐시
        deviceStateMap[deviceId]    = rState      // [v1.0.44] 표시문구 분기용(정지=대기/이동=접근) 상태 캐시
        deviceTurnMap[deviceId]     = remoteTurn   // [v1.1.7 #1] 회전 방향 캐시(표시/디버그)

        // [v1.0.42] UWB 거리→RSSI 환산(calibRssiAt1m/pathLossExp 의존) 제거.
        //   거리 추정은 칼만 필터(RSSI)만으로 수행 — 수신 raw RSSI 를 그대로 전처리 파이프라인에 투입.
        //   (UWB 주소 교환 세션은 유지하되, ToF 거리는 더 이상 경보 판정에 사용하지 않는다.)
        val inputRssi: Int = rssi

        val now      = System.currentTimeMillis()

        // ── 2D 칼만 필터 가져오기 또는 생성 ──────────────────────────────
        val kf = kalmanFilters.getOrPut(deviceId) {
            // [v1.1.8 #3] Cold-Start 웜업 주입 — 신규/재획득 기기를 첫 raw RSSI 로 즉시 초기화(공분산↓)해
            //   재획득 시 칼만 속도(D) 수렴 지연을 단축한다. (신규 '발령' 자체는 아래 Median N=3 워밍업
            //   게이트가 계속 방어하므로 콜드스타트 오발 위험 없이 추정 수렴만 앞당긴다)
            KalmanFilter(DevSettings.kalmanPreset).apply { injectWarmup(inputRssi) }
        }
        // 직전 프레임 칼만 추정속도(estimatedVel) — 돌진 FAST 판정·D-Boost 피드백 공용.
        //   ※ kf.update()는 아래에서 호출되므로 지금 값은 '직전 프레임' 속도 = 1-step 미분 피드백.
        val prevVel = kf.estimatedVel

        // ── [v1.0.45] 돌진 시 칼만 FAST 조건부 승격 (가드레일 포함) ────────
        // 이번 프레임 kf.update() 가 쓸 프리셋을 '직전 속도'로 결정한다(인과 정합).
        //   조건: (prevVel>임계가 연속 2프레임 지속) OR (IMU 실가속 동반). 둘 다 단발 임펄스로는
        //   성립 불가 → Median 임펄스 제거를 훼손하지 않는다. 미충족 시 사용자 프리셋으로 환원.
        val rushFrames = if (prevVel > RUSH_FAST_VEL_DBM) (rushFrameMap[deviceId] ?: 0) + 1 else 0
        rushFrameMap[deviceId] = rushFrames
        val imuRealAccel = ImuFusion.adaptiveQFactor >= RUSH_FAST_IMU_QFACTOR
        val promoteFast  = rushFrames >= RUSH_FAST_MIN_FRAMES || imuRealAccel
        kf.updatePreset(if (promoteFast) DevSettings.KALMAN_PRESET_FAST else DevSettings.kalmanPreset)

        // ── [v1.0.45] Median(비선형 임펄스 제거) → 비대칭EMA+D-Boost(선형 평활) 직렬 전처리 ──
        //   파이프라인: Raw → Median(N=3) → 비대칭EMA(D-Boost) → 칼만. 단발 반사 임펄스를 선형
        //   단계 진입 '전'에 순위통계로 제거해 칼만 속도(kfVel) 오염을 차단한다.
        val medianValue = medianFilter.push(deviceId, inputRssi)

        // ── [v1.0.32] RssiPreFilter: 비대칭 비례제어(Asymmetric P-Control) EMA 전처리 ──
        //   강한 돌진(prevVel>+2.0)이면 α 빗장(D-Boost)을 열어 지연을 없앤다.
        //   ★ v1.0.45: EMA 입력을 raw → medianValue 로 변경(Median 직렬 선행). 정제 출력(preFiltered)만
        //     칼만 입력으로 주입(raw 직접 입력 금지).
        val preFiltered = rssiPreFilter.push(deviceId, medianValue, prevVel)

        // ── 2D 칼만 필터 업데이트 (RSSI 공간) ────────────────────────────
        // kfRssi: 추정 RSSI(dBm) / kfVel: 변화율(dBm/s), 양수=접근 / 음수=이탈
        val (kfRssi, kfVel) = kf.update(preFiltered, ImuFusion.adaptiveQFactor)
        if (kfVel > 0.0) lastApproachAtMs = System.currentTimeMillis()  // [v1.1.12 L1] 접근(다가옴) 표본 시각 기록 → isDangerPresent 절전 게이트
        val kalmanRssi = kfRssi.toInt()

        // ── [v1.0.45] 후처리 P-EMA: 거리(P)항 전용 평활 — kfRssi → 비대칭 P-EMA → 거리판정 ──
        //   D항(kfVel)은 위상선행 유지를 위해 후필터 우회(아래 Time-Gate/TTC 에 kfVel 직결).
        //   P항(거리)만 평활(상승0.4/하강0.15). 게이트 1번째 다리는 raw-order kalmanRssi 유지(보수적 min).
        val pEma = pEmaFilter.push(deviceId, kalmanRssi)

        // ── [v1.0.45] 워밍업 가드: Median 윈도우 충전 전(콜드스타트)에는 신규/격상 발령 보류 ──
        //   첫 N프레임은 임펄스로 시작했을 때 거짓 근접으로 보일 수 있어, 필터 상태는 계속 쌓되
        //   '발령'만 보류한다. 윈도우가 차면 Median 임펄스 방어가 완성된다(특수경보·TTC·일반 모두 적용).
        val warmingUp = !medianFilter.isFull(deviceId)

        // [v1.0.31] raw 1초평균 — 하드게이트/2차게이트/TTC 교차검증용으로 게이트 '앞'에서 1회만 계산.
        //   oneSecAvgRssi 는 호출마다 버퍼에 push(부작용) → 프레임당 1회 호출 후 변수 재사용한다.
        val avg1sec = oneSecAvgRssi(deviceId, inputRssi)   // 1초 평균은 raw 기준 유지

        // ── [v1.1.7 #2] 후진(전진) 대비 — RX 측 RSSI 추세 반전 추론 ──────────────
        //   상대 차량 A 에 접근 중, A 의 신호가 '안정/약화'였다가 윈도우(기본 1.2s) 안에서 갑자기
        //   '급강세(가까워짐)'로 반전되면 → A 가 후진/전진으로 내 쪽으로 움직이기 시작했을 가능성.
        //   윈도우를 시간 기준 전·후반으로 나눠 ① 전반부 추세(olderTrend)가 안정/약화(≤tol),
        //   ② 전반부 저점 대비 현재 상승폭(rise)이 임계(riseDbm) 이상이면 latch(now+holdMs).
        //   단조 접근(내가 A 로 다가가는 정상 상황)은 olderTrend 가 큰 양수라 자동 배제된다.
        // [v1.1.9 R6] 상대가 16진수 페이로드로 '정지(IDLE)'를 송신 중이면 후진/전진 추론 자체가 모순이므로
        //   reversePrep 진입을 차단한다. 이 추론은 어디까지나 '상대가 이동(FORWARD) 중'일 때 RSSI 추세
        //   반전으로 접근 시작을 조기 포착하려는 보조 수단 — 상대의 자기-신고 상태(rState, L760 디코드)를
        //   판단 근거로 우선한다. (내가 움직이고 상대는 정지인 상황의 거짓 "후진 대비" 오발 제거.)
        if (DevSettings.reversePrepEnabled && rState != BleConstants.PSTATE_IDLE) {
            val hist = reverseRssiHist.getOrPut(deviceId) { ArrayDeque() }
            hist.addLast(now to avg1sec)
            val cutoff = now - DevSettings.reverseWindowMs
            while (hist.isNotEmpty() && hist.first().first < cutoff) hist.removeFirst()
            val spanMs = if (hist.size >= 2) hist.last().first - hist.first().first else 0L
            if (hist.size >= 3 && spanMs >= DevSettings.reverseWindowMs / 2) {
                val midTime   = hist.first().first + spanMs / 2
                val firstHalf = hist.filter { it.first <= midTime }
                if (firstHalf.size >= 2) {
                    val olderTrend = firstHalf.last().second - firstHalf.first().second  // 양수=강해짐(접근)
                    val troughRssi = firstHalf.minOf { it.second }                        // 전반부 저점
                    val rise       = avg1sec - troughRssi                                 // 저점→현재 상승폭
                    if (olderTrend <= DevSettings.reverseStableTolDb && rise >= DevSettings.reverseRiseDbm) {
                        reversePrepUntil[deviceId] = now + DevSettings.reversePrepHoldMs
                        Log.d(TAG, "후진대비 감지 $deviceId trend=$olderTrend rise=$rise (avg1sec=$avg1sec)")
                    }
                }
            }
        }

        // [v1.1.9 R1/R3] 표시-경보 분리 — 감지된 모든 SafeAlert 기기는 신호세기와 무관하게 '표시 풀'에 등록한다.
        //   · deviceRssiMap : 목록 강도순 정렬용 RSSI(평활 kalmanRssi). 아래 하드게이트에서 경보가 차단돼도
        //     목록엔 남도록 게이트 '앞'에서 채운다. (경보 기기는 이후 일반/특수 경로에서 avgRssi 등으로 덮어씀)
        //   · pendingDisplayMap : 비경보 기기의 표시 멤버십(+TTL). alertState 미등록 기기만 등록.
        //   경보 발령은 아래 하드게이트·판정옵션이 독립 결정(R4/R5). 위젯 최우선(topPriorityDevice)은
        //   alertState 만 보므로 약신호는 위젯에 뜨지 않고 목록에만 SAFE 행으로 노출된다.
        deviceRssiMap[deviceId] = kalmanRssi
        if (!alertState.containsKey(deviceId)) pendingDisplayMap[deviceId] = now

        // ── [v1.1.10] 16진수(역할·상태) 적극 활용 — 페이로드 기반 경보 임계 비대칭 시프트 ──────────
        //   디코드된 CAT(역할)·STATE(상태)로 위험 오프셋을 산출해 모든 임계 게이트(하드게이트·특수·
        //   TTC·calcLevel·SAFE강제)에 effWarning/effDanger 로 '일관' 적용한다. 한 곳만 시프트하면
        //   게이트끼리 충돌(조기경보하려 해도 하드게이트가 차단)하므로 단일 effective 임계로 통일한다.
        //   payloadOffset>0 = 더 약한 신호(먼 거리)에서 경보(fail-safe). 0 이면 기존 거동과 완전 동일.
        val payloadOffset = computePayloadRiskOffset(deviceId, rCategory, rState, kfVel)
        // [v1.1.16 C] 비콘별 보정(rssiOffset)을 페이로드 오프셋과 합산 → 단일 effective 임계로 통일.
        //   기존엔 calcLevel·이탈히스테리시스(거리판정)에만 beaconOffset 이 반영돼, 하드게이트·후진특수·
        //   safeForceFloor·협력격상·TTC피크게이트가 비콘 보정을 무시했다(비콘 관리 보정이 반쪽만 적용).
        //   여기서 합산해 모든 게이트가 같은 totalOffset 을 쓰게 한다. 보정 0 이면 기존 거동과 완전 동일.
        val beaconOffset = runCatching { BeaconRegistry.getRssiOffsetForFullId(deviceId) }.getOrDefault(0)
        // [전역 비콘 수신 강도] BLE설정의 비콘 게인(%)을 공통 dBm 보정으로 환산해 비콘에만 가산한다
        //   (offset 0 비콘 포함). 게인 100%(=0dBm)면 기존 거동과 완전 동일. 일반 SafeAlert 기기엔 미적용.
        val beaconGlobalGain = if (BeaconRegistry.isBeaconFullId(deviceId)) DevSettings.beaconGainDbm else 0
        // (v1.1.31) UWB 델타 보정 — 활성 UWB 세션 페어면 실거리+medianValue(스파이크 제거·지연≈0)로
        //   이 페어의 채널 편차 Δ 를 학습하고, 학습된 보정을 다른 오프셋과 같은 자리에서 합산한다.
        //   경보는 여전히 100% RSSI 구동(UWB 끊겨도 무봉합) — UWB 는 임계를 '보정'만 한다.
        //   비대칭 클램프(지연 −3dB / 조기 +10dB)+24h 감쇠는 UwbCalibrator 내부 불변식. 비활성=0=기존 동일.
        val uwbPairKey = uwbPairKeyFor(deviceId)   // [v1.1.37 ③] 개별 기기 대신 역할쌍 세그먼트로 학습·조회
        uwbRanger?.uwbDistances?.get(deviceId)?.let { UwbCalibrator.onSample(uwbPairKey, medianValue, it) }
        val uwbCalibOffset = UwbCalibrator.offsetDbFor(uwbPairKey)
        val totalOffset = payloadOffset + beaconOffset + beaconGlobalGain + uwbCalibOffset
        val effWarning = BleConstants.rssiWarning - totalOffset
        val effDanger  = BleConstants.rssiDanger  - totalOffset
        // [v1.1.16 D → v1.1.22 C-fix] 첫 접촉 고속 발령용 '근접 2프레임 확증' 카운터(워밍업·Time-Gate 우회).
        //   ★ 게이트 신호를 칼만·1초평균(둘 다 지연) → medianValue(median-of-3, 위상지연≈0 선행)로 교체.
        //   기존엔 칼만(평활)·1초평균(평균)이 둘 다 물리 최근접(CPA)보다 신호 정점이 뒤로 밀려, 가장 가까운
        //   순간엔 streak 가 안 차고 '지나간 뒤(이탈측)'에야 2프레임이 채워졌다(=버그 '붙어도 안 울림/멀어질 때 울림').
        //   medianValue 는 평활 없이 단발 스파이크만 제거 → 접근측(CPA 이전)에서 즉시 2프레임 확증된다(시뮬 검증).
        val inDangerRaw = medianValue >= effDanger
        val dangerStreak = if (inDangerRaw) (dangerContactStreakMap[deviceId] ?: 0) + 1 else 0
        dangerContactStreakMap[deviceId] = dangerStreak
        // [v1.1.18 → v1.1.22] WARNING 거리(effWarning)도 동일하게 medianValue 선행 기준 2프레임 확증(정지 근접 즉시 발령).
        //   effDanger ⊂ effWarning 이라 DANGER 거리도 자동 포함. median-of-3 가 단발 임펄스를 막아 streak 오발을 방지한다.
        val inWarningRaw = medianValue >= effWarning
        val warningStreak = if (inWarningRaw) (warningContactStreakMap[deviceId] ?: 0) + 1 else 0
        warningContactStreakMap[deviceId] = warningStreak
        // [Phase2] IDLE-IDLE 가청 억제 — 내 IMU 정지 + 상대 IDLE 송신(둘 다 정지=충돌동역학 없음)이면
        //   아래 정규 WARNING 가청경보를 억제(표시·목록·위젯은 유지). DANGER 는 억제 대상이 아니며,
        //   둘 중 하나라도 움직이면(rState≠IDLE 또는 IMU 이동) 다음 프레임 즉시 해제된다.
        // [v1.1.11 C2] payloadPresent 필수 — 비콘·구버전(페이로드 부재)은 rState 가 무조건 IDLE 로 디코드되어
        //   '이동 중인 비콘 장비'가 영구 IDLE 로 오인, DANGER 가 WARNING 강등→무음화되는 구멍이 있었다.
        //   실제 1바이트 자기-신고를 보낸 기기에만 억제를 허용해 그 구멍을 막는다.
        val idleIdleQuiet = DevSettings.idleIdleSuppressEnabled && payloadPresent &&
            ImuFusion.isStationary && rState == BleConstants.PSTATE_IDLE

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
        //   ★ v1.0.32 3중 가드: 칼만(kalmanRssi)·raw1초평균(avg1sec)에 더해 전처리 정제경로까지
        //     세 경로 중 가장 먼(가장 음수) 값을 기준으로 잡는다. 어느 한 경로라도 경고 임계보다
        //     멀면 속도·TTC와 무관하게 신규 격상을 차단(SAFE).
        //   ★ v1.0.45: 3번째 다리를 EMA 출력(preFiltered) → Median 출력(medianValue)으로 교체.
        //     preFiltered 는 하강 α=0.05 로 이탈 시 잔상(SAFE 복귀 지연)을 만들지만, medianValue 는
        //     raw-order(지연 약 1프레임)라 임펄스는 제거하면서도 실제 이탈에는 신속히 따라가 게이트가
        //     더 빨리 풀린다(잔상 제거). 보수적 min 원칙·avg1sec raw 교차검증은 그대로 보존.
        // [v1.1.8 #4] 하드게이트 3중가드 결합을 min → median(중앙값)으로 완화.
        //   min 은 세 경로(칼만·raw1초평균·Median) 중 하나라도 BLE 출렁임(±5~10dB)으로 깊은 dip 을
        //   찍으면 게이트가 닫혀, 임계 바로 위(예 -81 vs 경고 -85, 4dB 마진) 신규 기기가 노란 경보로
        //   격상되지 못하는 누락을 낳았다(현장 버그). 중앙값은 세 경로 중 2개가 합의해야 '멀다'로 보아
        //   단발 dip 1개는 무시하되, 거짓근접에는 여전히 2개 경로 합의를 요구한다(avg1sec raw 교차검증은
        //   투표자로 보존 = MASTER 불변식 유지). (오름차순 정렬 후 가운데 1개)
        val gateRssi = listOf(kalmanRssi, avg1sec, medianValue).sorted()[1]
        if (gateRssi < effWarning && !alertState.containsKey(deviceId)) {   // [v1.1.10] effWarning(페이로드 시프트)로 일관
            // [v1.0.49 #2] 필터 보존 밴드 — 경고 임계 바로 아래(밴드 내) 기기는 필터 상태를 지우지 않고
            //   경보 로직만 스킵한다. 위에서 Median·EMA·칼만·P-EMA·1초버퍼가 이미 이번 프레임 값으로
            //   갱신됐으므로 밴드 체류 중 자동 워밍업 → 경고권 진입 프레임부터 웜 상태로 즉시 판정 가능.
            //   (구버전: 매 프레임 전삭제 → 진입 시 콜드스타트로 경보 수초 지연 — A/B 교차 직전 표시의 주원인)
            if (gateRssi >= effWarning - FILTER_PRESERVE_BAND_DB) return
            // [v1.1.9 R1/R3] 표시-경보 분리 — 경보권 밖(밴드 밖) 이라도 deviceRssiMap(목록 정렬)·deviceStateMap
            //   (이동/정지 라벨)·pendingDisplayMap(표시 멤버십) 은 보존해 목록엔 계속 SAFE 행으로 노출한다.
            //   여기서는 '경보 추적' 상태(suddenLabel·필터·칼만 등)만 정리한다. (진짜 소실은 onDeviceLost 가 전삭제)
            suddenLabelMap.remove(deviceId)
            deviceCategoryMap.remove(deviceId)
            deviceTurnMap.remove(deviceId); reverseRssiHist.remove(deviceId); reversePrepUntil.remove(deviceId)   // [v1.1.7 #1/#2]
            firebaseLastSaveMap.remove(deviceId)
            rssiPreFilter.clear(deviceId)     // [v1.0.38 클린업] 미추적 기기 EMA 전처리 상태 정리
            medianFilter.clear(deviceId)      // [v1.0.45] Median 윈도우 정리(워밍업 상태 리셋)
            pEmaFilter.clear(deviceId)        // [v1.0.45] 후처리 P-EMA 상태 정리
            rushFrameMap.remove(deviceId)     // [v1.0.45] 돌진 프레임 카운터 정리
            dangerContactStreakMap.remove(deviceId)   // [v1.1.16 D] 첫접촉 DANGER 카운터 정리
            warningContactStreakMap.remove(deviceId)  // [v1.1.18] 첫접촉 WARNING 카운터 정리
            kalmanFilters.remove(deviceId)    // [v1.0.38 클린업] 미추적 기기 칼만 인스턴스 정리(stale 재등장 방지)
            recedingStartMap.remove(deviceId)    // [v1.1.6 검증 보강] 이탈 판정 상태 누수·stale 피크 재출현 방지
            recedeRefMap.remove(deviceId)        // [v1.1.6 검증 보강] 미추적 기기 중간평활 EMA 정리
            recedePeakMap.remove(deviceId)       // [v1.1.6 검증 보강] 미추적 기기 피크 홀드 정리
            // [v1.1.9 R1/R3] pendingDisplayMap 보존 — 경보권 밖 약신호도 목록(SAFE 행)에 계속 노출.
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
        //   ★ v1.0.45: 거리판정을 preFiltered(전단 EMA) → pEma(칼만 후처리 P-EMA, 거리 P항)로 변경.
        //     P-D 분리 일관성 — 거리(P)는 평활 P-EMA 로 판정, 속도(D=kfVel)는 별도 우회. avg1sec(raw)
        //     하이브리드 교차검증은 유지(이탈 시 잔상 차단). warmingUp(Median 미충전) 구간은 발령 보류.
        // 표시문자열을 makeStateLabel(후진·하역 경보 문구)로 덮어써 오버레이·목록에 출력.
        if ((rState == BleConstants.PSTATE_REVERSE || rState == BleConstants.PSTATE_LOADING)
            && !warmingUp                                   // [v1.0.45] 콜드스타트 임펄스 발령 보류
            && pEma    >= effDanger                          // [v1.0.45/v1.1.10] 거리판정: P-EMA, effDanger(페이로드 시프트)
            && avg1sec >= effDanger) {
            deviceRssiMap[deviceId]  = kalmanRssi
            suddenLabelMap[deviceId] = makeStateLabel(extractDisplayName(deviceId), rCategory, rState)
            alertState[deviceId]     = Pair(BleConstants.LEVEL_DANGER, now)
            pendingDisplayMap.remove(deviceId)   // [v1.0.49 #3] 경보 등록 → 보류 표시 해제
            bleScanner?.setEcoMode(false)   // 즉시 전투 모드(ACTIVE)
            Log.w(TAG, "특수경보(STATE=$rState CAT=$rCategory): $deviceId pEma=$pEma kfRssi=%.1f".format(kfRssi))
            // 무음(전역/개별)은 존중 — 상태·표시는 유지하되 소리/진동만 억제
            if (isMuted || isDeviceMuted(deviceId)) {
                updateFloatingOverlay()
                return
            }
            forceAlarmVolume()
            // [v1.0.46 #7] !isScreenOn 조건 제거 — FLAG_KEEP_SCREEN_ON 탓에 포그라운드 진동이 사망 상태였다
            if (DevSettings.vibrationEnabled) VibrationHelper.vibrateDanger(this)
            if (DevSettings.soundEnabled)     AlertSoundPlayer.playDanger(this)
            activeSoundLevel = BleConstants.LEVEL_DANGER   // [v1.0.46 #2] 사이렌 레벨 동기 — 후속 WARNING 의 조기차단/영구지속 방지
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
        // [v1.1.22 B/C] '멀어지는 중' 단일 판정 — 위상선행 kfVel(거리 권위값 pEma 보다 먼저 이탈 포착)이
        //   CPA 임계 이하(확실히 멀어짐)이거나 추적 상태머신이 DEPARTING 확정이면 이탈로 본다. CPA 정점
        //   (kfVel≈0)에선 false → '바로 붙어 있을 때'는 접근으로 취급(C 즉시발령 유지), CPA 를 넘겨
        //   kfVel<-0.5 로 꺾이는 순간부터 true → 멀어지며 격상·재발령을 막아(B) '지나가고 울림'을 없앤다.
        val isDepartingNow = kfVel < -CPA_VEL_THRESHOLD || isNowDepart

        // avg1sec(raw 1초평균)은 위 하드게이트 앞에서 이미 계산됨(프레임당 1회).

        // [v1.0.47 #2] 정지(isStationary) 시 DANGER→WARNING 격하의 적용 조건 정밀화.
        //   기존엔 내 IMU 가 정지면 상대가 누구든 무조건 격하 → 가만히 서 있는 보행자가 움직이는
        //   장비의 접근에도 사이렌(DANGER)을 못 받았다(정지/이동 여부에 따라 기기별로 울림·침묵이
        //   갈리는 비대칭의 직접 원인). 예외(격하 금지): 내가 보행자 + 상대가 장비(지게차/EPJ)
        //   + 상대가 활동 중(비IDLE 상태). 유지(계속 격하): 장비 운전자 폰의 정지 격하
        //   (주차·대기 중 오발 억제), 보행자끼리 정지 근접(잡담), 주차된 장비(속도0·IDLE) 옆 정지.
        val movingEquipApproach = myCategory == BleConstants.CAT_WALKER &&
            (rCategory == BleConstants.CAT_FORKLIFT || rCategory == BleConstants.CAT_EPJ) &&
            rState != BleConstants.PSTATE_IDLE
        val demoteWhileStationary = ImuFusion.isStationary && !movingEquipApproach

        var stableLevel: Int
        // [v1.1.6 R4-SIL-1] demote/이탈히스테리시스 '이전'의 순수 거리 권위값(pEma 기반, 노이즈 견고).
        //   demoteWhileStationary 가 물리적 DANGER 를 WARNING 으로 인위 격하하면 stableLevel 만으로는
        //   '위험권 밖'으로 오판 → isReceding 오발 → 근접인데 전체 무음(R4-SIL-1). 이탈 가드는 격하 전
        //   거리값(distanceLevel)으로 판정해 무음을 막는다. 복구 게이트는 stableLevel 유지(격하 의도 존중).
        var distanceLevel: Int
        val avgRssi: Int

        // [v1.1.8 ①②] 고정값(1초 평균 고정) 모드·모드 혼합(blend) 전면 제거 → 칼만 단일화.
        //   거리(P) 권위값은 순수 pEma(kalmanRssi → 후처리 비대칭 P-EMA 평활). 속도(D)는 위상선행
        //   유지를 위해 Time-Gate/TTC 에 kfVel 직결로 별도 우회(여기서 평활하지 않음).
        avgRssi = pEma
        val rawLevel = calcLevelWithHysteresis(deviceId, pEma, totalOffset)   // [v1.1.16 C] 비콘+페이로드 합산(상단 totalOffset)
        distanceLevel = rawLevel   // [v1.1.6 R4-SIL-1] 이탈히스테리시스·격하 이전 거리 권위값 보존(이탈 가드용)
        val afterHysteresis = applyDepartingHysteresis(deviceId, rawLevel, pEma, totalOffset, now)
        // ── 정지 격하 방어 ([v1.0.47 #2] 보행자+활동 장비 접근은 예외 — 위 demoteWhileStationary) ──
        // [v1.1.16 B] 내가 장비(지게차/EPJ)이고 상대가 위험권(DANGER) 보행자/비콘이면 내 IMU 가 정지여도
        //   DANGER→WARNING 강등 금지(작업자 위에 멈춘 지게차는 정지여도 위험). 위에서 보존한 distanceLevel
        //   (격하·히스테리시스 이전 순수 거리 권위값)을 기준 삼아 강등 루프 오염을 피한다.
        val iAmEquip = myCategory == BleConstants.CAT_FORKLIFT || myCategory == BleConstants.CAT_EPJ
        val closeWalkerHazard = iAmEquip && rCategory == BleConstants.CAT_WALKER &&
            distanceLevel >= BleConstants.LEVEL_DANGER
        stableLevel = if (demoteWhileStationary && !closeWalkerHazard && afterHysteresis >= BleConstants.LEVEL_DANGER)
            BleConstants.LEVEL_WARNING else afterHysteresis

        deviceRssiMap[deviceId] = avgRssi      // 플로팅 위젯 최우선 기기 선정·정렬에 사용

        // ── [v1.0.25 → v1.0.31 raw 이중가드] 절대 거리 가드 (2차 방어선) ─────────────────────
        // 1차 방어는 위 하드게이트(min(칼만,raw) 기준 + return)가 담당한다.
        // 여기는 '이미 추적 중이라 1차 게이트를 통과한 기기'가 blend(avgRssi) '또는' raw 1초평균
        // (avg1sec) 중 하나라도 경고 임계(rssiWarning)보다 멀면 stableLevel을 SAFE로 강제 → 아래
        // 이탈 페이드아웃/SAFE 처리로 흘려보낸다. 접근 속도·TTC로는 절대 격상 불가.
        //   ★ [v1.1.8] avgRssi(=pEma)는 후처리 평활 지연으로 raw가 멀어도 임계 위로 떠 있을 수 있어 raw(avg1sec)를 함께 본다.
        // [v1.1.11 C1] 이미 추적 중(alertState 존재)인 기기는 강제-SAFE 바닥을 calcLevel 의 하향 히스테리시스
        //   대역(effWarning - HYSTERESIS_DBM)에 맞춘다. 안 그러면 이 가드가 calcLevel 의 자체 히스테리시스를
        //   덮어써 effWarning 부근에서 WARNING↔SAFE 가 깜빡인다. 신규(미추적) 기기는 기존 effWarning 진입
        //   바닥을 유지(더 엄격 = 페일세이프 진입).
        val safeForceFloor = if (alertState.containsKey(deviceId)) effWarning - HYSTERESIS_DBM else effWarning
        // [v1.1.16 A] 단일표본 노이즈로 인한 강제-SAFE 방지 — raw avg1sec(1프레임 dip 에 취약)를
        //   gateRssi(median(칼만,raw1초,Median)=3경로 중앙값)로 교체. 비콘이 단발 −80 dip 을 찍어도
        //   3경로 중앙값이 위험권이면 SAFE 로 떨구지 않는다(정지근접 무음·재워밍업 churn 제거 = 주력 픽스).
        //   여전히 avgRssi(pEma 평활값)와 OR 교차검증하므로 실제 이탈(둘 다 멀어짐)은 정상적으로 SAFE 처리.
        if (avgRssi < safeForceFloor || gateRssi < safeForceFloor) {
            stableLevel = BleConstants.LEVEL_SAFE
        }

        // ── [v1.1.14] 양방향 협력 알림(절충): 상대 위험송출(rRisk) + 내 RSSI 게이트로 경보 '격상' ──
        //   상대가 위험/경고를 '먼저' 감지해 송출(decodeRisk)하고, '내' RSSI(pEma·raw 둘 다)도
        //   경고권(effWarning) 이상으로 가까울 때만 상대가 보낸 레벨까지 끌어올린다(격상 전용 — 절대 격하 안 함).
        //   - onset(교행 전 발령): 양쪽이 접근 중이면 내 RSSI 가 DANGER 임계(-55)에 '닿기 전'에 상대 송출로 먼저 울린다.
        //   - 안전: 먼 곳 상대의 오발(false alarm)은 내 RSSI 게이트(effWarning)가 차단(절충 = 상대송출 ∧ 내 RSSI 근접).
        //   - 하이브리드(avgRssi ∧ avg1sec) 교차검증 → 이탈 잔상 위 거짓 격상도 막는다(특수경보·safeForceFloor 선례).
        if (rRisk > BleConstants.LEVEL_SAFE && rRisk > stableLevel &&
            avgRssi >= effWarning && avg1sec >= effWarning) {
            val beforeCoop = stableLevel
            stableLevel = rRisk.coerceAtMost(BleConstants.LEVEL_DANGER)
            Log.w(TAG, "협력 격상(절충): $deviceId rRisk=$rRisk 내RSSI(pEma=$avgRssi raw=$avg1sec) → $beforeCoop→$stableLevel")
        }

        // ── [v1.1.22 C] '붙었을 때' raw 즉시 격상 — pEma 평활지연을 기다리지 않는다 ──────────────
        //   거리 권위값(pEma)은 다단 비대칭 평활(매 단계 하강 α<상승 α)이라 물리적 최근접(CPA)보다
        //   거리피크가 ~1초 이상 뒤로 밀린다 → 가장 가까운 순간(raw 최강)엔 pEma 가 아직 임계 밑이라
        //   stableLevel 이 못 떠 '바로 붙어 있어도 안 울림'이 났다. 이를 medianValue 선행(평활 없는 median-of-3) 2프레임
        //   확증(dangerStreak/warningStreak — 상단 계산, v1.1.22 C-fix 로 게이트가 medianValue 기준)으로 메운다: 멀어지는 중이 아니면(isDepartingNow
        //   =false, 즉 접근~CPA 정점) raw 위험권을 stableLevel 에 즉시 반영해 평활 lag 없이 발령한다.
        //   멀어지는 중이면 적용 안 함 → 이탈측 재격상(=버그 '지나가고 울림') 금지(B 와 결합).
        if (!isDepartingNow && stableLevel < BleConstants.LEVEL_DANGER && dangerStreak >= 2) {
            stableLevel = BleConstants.LEVEL_DANGER
            Log.w(TAG, "[v1.1.22 C] med 즉시 격상 DANGER: $deviceId (dangerStreak=$dangerStreak med=$medianValue raw1s=$avg1sec pEma=$avgRssi kfVel=%.2f)".format(kfVel))
        } else if (!isDepartingNow && stableLevel < BleConstants.LEVEL_WARNING && warningStreak >= 2) {
            stableLevel = BleConstants.LEVEL_WARNING
            Log.w(TAG, "[v1.1.22 C] med 즉시 격상 WARNING: $deviceId (warningStreak=$warningStreak med=$medianValue raw1s=$avg1sec pEma=$avgRssi kfVel=%.2f)".format(kfVel))
        }

        // ── (v1.1.33) UWB 실거리 역할쌍 차등 승격(promote-only, 기본 OFF) ────────────────────
        //   v1.1.32 의 단일 3m DANGER 승격은 지게차 기준 이미 충돌권이라 폐기. 지게차가 한쪽이라도
        //   낀 쌍은 15m 경고 / 8m 위험, 그 외(EPJ↔보행자 등)는 5m 경고 / 3m 위험으로 2단 승격한다.
        //   모든 RSSI 격상·격하(정지 격하·safeForceFloor 강제-SAFE 포함)가 끝난 최종값 위에 얹으므로
        //   차폐로 RSSI 는 약한데 물리적으로 가까운 사각을 실거리로 메운다. 억제·격하 경로는 없고
        //   (promoteTo 가 현재 stableLevel 보다 높을 때만 대입 — 승격만), UWB 세션이 없거나 끊긴
        //   기기는 이 블록이 없던 것과 동일(무봉합 — 경보는 BLE 상시 가동).
        //   uwbDistances 는 세션 종료·기기 이탈 시 즉시 제거되므로 값이 있으면 항상 라이브 실측.
        //   distanceLevel 도 승격 레벨까지만 함께 상향 — 아래 이탈 가드(isReceding)가 차폐로 낮아진
        //   pEma 기준 distanceLevel<DANGER 를 '위험권 밖 이탈'로 오판해 무음화(조기 return)하는 것을
        //   DANGER 승격 시 막는다(v1.1.32 와 동일). WARNING 승격은 이 가드 밖이지만, 이탈 오판으로
        //   해제돼도 실측이 반경 안이면 다음 프레임 이 블록이 재승격 → 재발령(영구 무음 없음).
        //   진짜 이탈은 OR isDepartingNow(v1.1.22 B) 절이 그대로 잡으므로 이탈측 로직은 무접촉.
        if (DevSettings.uwbPromoteEnabled && stableLevel < BleConstants.LEVEL_DANGER) {
            val uwbD = uwbRanger?.uwbDistances?.get(deviceId)
            if (uwbD != null) {
                val forkliftPair = myCategory == BleConstants.CAT_FORKLIFT ||
                        rCategory == BleConstants.CAT_FORKLIFT
                val warnM = if (forkliftPair) DevSettings.uwbForkliftWarnMeters else DevSettings.uwbPairWarnMeters
                val dangM = if (forkliftPair) DevSettings.uwbForkliftDangerMeters else DevSettings.uwbPairDangerMeters
                val promoteTo = when {
                    uwbD <= dangM -> BleConstants.LEVEL_DANGER
                    uwbD <= warnM -> BleConstants.LEVEL_WARNING
                    else          -> BleConstants.LEVEL_SAFE
                }
                if (promoteTo > stableLevel) {
                    stableLevel = promoteTo
                    if (distanceLevel < promoteTo) distanceLevel = promoteTo
                    val lvName = if (promoteTo == BleConstants.LEVEL_DANGER) "DANGER" else "WARNING"
                    Log.w(TAG, "[v1.1.33] UWB 승격 $lvName: $deviceId d=%.2fm 지게차쌍=$forkliftPair 임계=경고${warnM}m/위험${dangM}m (pEma=$avgRssi)".format(uwbD))
                }
            }
        }

        // ── (v1.1.34) UWB 접근속도 승격(promote-only, 기본 OFF) ────────────────────────────────
        //   실측 거리 미분 접근속도가 임계(uwbApproachSpeedKmh, 기본 6km/h = 지게차 제한속도) 이상
        //   2샘플 지속 + 평활속도 동반 상승(정지 멀티패스 스파이크 오승격 차단)이면 최소 WARNING 으로
        //   조기 승격한다. DANGER 는 거리 승격(위 v1.1.33)의 몫 — 속도만으로 사이렌까지 올리지 않는다.
        //   격하 경로 없음. 운동학 부재(세션 없음/단절/리셋)면 이 블록이 없던 것과 동일.
        //   ※ v1.0.36 충돌기하의 closingSpeedKmh(사망 입력)에는 연결하지 않는다 — 그쪽 sideCourse
        //     경로는 '첫 경보 보류' 방향이라 안전불변식 위반. 독립 승격 블록으로만 쓴다.
        if (DevSettings.uwbVelPromoteEnabled && stableLevel < BleConstants.LEVEL_WARNING) {
            val kin = uwbRanger?.uwbKinematics?.get(deviceId)
            val approachMps = DevSettings.uwbApproachSpeedKmh / 3.6f
            if (kin != null && now - kin.atMs <= 1500L &&   // 라이브 운동학만(레인징 정지 잔상 차단)
                kin.approachStreak >= 2 && kin.closingMps >= approachMps * 0.6f) {
                stableLevel = BleConstants.LEVEL_WARNING
                if (distanceLevel < stableLevel) distanceLevel = stableLevel
                Log.w(TAG, "[v1.1.34] UWB 접근속도 승격 WARNING: $deviceId 평활v=%.1fkm/h streak=${kin.approachStreak} 임계=${DevSettings.uwbApproachSpeedKmh}km/h".format(kin.closingMps * 3.6f))
            }
        }

        // ── (v1.1.34) UWB 지속 이탈 해제 — '멀어질 때 알림 꺼짐'(사용자 명시 요청) ─────────────────
        //   promote-only 불변식의 최초 승인 예외(과신호 피로 저감). 실측 3샘플 연속 이탈 + 평활속도
        //   음수일 때 경보를 실측 거리 기준으로만 캡: 경고 반경 밖=SAFE(완전 해제 — 아래 SAFE 처리
        //   블록이 정리·브로드캐스트·사운드 중지까지 수행), 경고대=WARNING(사이렌만 해제 — 사운드
        //   전환은 fail-quiet 강등정정(v1.1.28)·canonical 디스패치가 처리).
        //   가드: ① 옵트인(기본 꺼짐) ② 실측·운동학 라이브(세션 드랍 = 엔트리 제거 = 기존 RSSI 거동
        //   폴백) ③ 역할쌍 DANGER 반경 안(uwbD ≤ dangM)에서는 개입 금지 ④ RSSI 강접근(kfVel ≥
        //   fastApproachBypassVelDbm) 시 거부권 — 실측과 RSSI 가 상충하면 경보 유지(fail-safe).
        //   위 승격 블록과 streak 이 상호배타(접근/이탈 카운트가 서로 리셋)라 같은 프레임 동시 발동 불가.
        //   distanceLevel 도 캡까지 하향 — 이탈 가드(isReceding)의 자연 페이드아웃이 걸리게 한다.
        if (DevSettings.uwbVelReleaseEnabled && stableLevel > BleConstants.LEVEL_SAFE) {
            val kin = uwbRanger?.uwbKinematics?.get(deviceId)
            val uwbNowD = uwbRanger?.uwbDistances?.get(deviceId)
            if (kin != null && uwbNowD != null && now - kin.atMs <= 1500L &&
                kin.separatingStreak >= 3 && kin.closingMps < 0f &&
                kfVel < DevSettings.fastApproachBypassVelDbm) {
                val forkliftPair = myCategory == BleConstants.CAT_FORKLIFT ||
                        rCategory == BleConstants.CAT_FORKLIFT
                val warnM = if (forkliftPair) DevSettings.uwbForkliftWarnMeters else DevSettings.uwbPairWarnMeters
                val dangM = if (forkliftPair) DevSettings.uwbForkliftDangerMeters else DevSettings.uwbPairDangerMeters
                val cap = when {
                    uwbNowD > warnM -> BleConstants.LEVEL_SAFE      // 경고 반경 밖 — 완전 해제
                    uwbNowD > dangM -> BleConstants.LEVEL_WARNING   // 경고대 — DANGER 만 WARNING 으로
                    else            -> stableLevel                  // DANGER 반경 안 — 개입 금지
                }
                if (cap < stableLevel) {
                    Log.w(TAG, "[v1.1.34] UWB 이탈 해제 ${stableLevel}→${cap}: $deviceId d=%.1fm 평활v=%.1fkm/h streak=${kin.separatingStreak} kfVel=%.2f".format(uwbNowD, kin.closingMps * 3.6f, kfVel))
                    stableLevel = cap
                    if (distanceLevel > cap) distanceLevel = cap
                }
            }
        }

        // ── (v1.1.36) UWB 주 거리 권위 — 세션 활성 페어는 UWB 실측으로 '거리'만 판정, 승격·이탈은 기존 워크플로우에 위임 ──
        //   [설계 원칙 — 사용자 지시] UWB 는 '거리 입력'만 대체한다. 승격(아래 TTC 선발령)·이탈
        //   (isReceding·isDepartingNow)의 판정 로직은 기존 RSSI 파이프라인이 그대로 담당한다.
        //   stableLevel 을 통째로 덮어쓰지 않는다(v1.1.36 초판의 '완전 대체'가 기존 이탈·TTC 를 무력화한
        //   회귀를 정정).
        //   · 격상(promote): UWB 실측이 역할쌍 경고/위험 반경 안이면 그 레벨로 올린다. 금속 캐빈·파렛트
        //     차폐로 RSSI 는 약한데 물리적으로 가까운 사각을 실측으로 메운다(v1.1.33 거동을 상시화).
        //   · 이탈(release): UWB 운동학이 '멀어지는 중'(separatingStreak≥3 · closingMps<0)일 때만 실측
        //     거리 기준으로 강등한다 — 스쳐 지나가면(멀어짐) 끈다. 접근·정지 중엔 강등 금지(위험 유지 =
        //     페일세이프). (v1.1.39) kfVel 거부권 제거 — UWB 활성 페어는 실측 운동학이 主권위. RSSI
        //     멀티패스 반등(가짜 접근 신호)이 해제를 수초 차단하던 지연 원인을 없앤다.
        //   · 접근속도 단독 승격은 두지 않는다 — '멀리서 빠르게 접근'을 거리와 무관하게 경고로 올리면
        //     종일 오경보가 된다(TTC 무시). 조기 위험 예측은 아래 기존 TTC 선발령(경고권 진입 + 충돌 임박
        //     TTC≤임계)이 담당한다.
        //   uwbDistances 는 세션 종료·기기 이탈 시 즉시 제거 → 값이 있으면 항상 라이브 실측. null 이면 이
        //   블록 무동작(위 RSSI stableLevel 유지 = 무봉합 폴백). RSSI 보정 학습(UwbCalibrator, 상단)은 독립 지속.
        if (DevSettings.uwbPrimaryAuthorityEnabled) {
            val uwbPrimD = uwbRanger?.uwbDistances?.get(deviceId)
            if (uwbPrimD != null) {
                val forkliftPair = myCategory == BleConstants.CAT_FORKLIFT ||
                        rCategory == BleConstants.CAT_FORKLIFT
                val warnM = if (forkliftPair) DevSettings.uwbForkliftWarnMeters else DevSettings.uwbPairWarnMeters
                val dangM = if (forkliftPair) DevSettings.uwbForkliftDangerMeters else DevSettings.uwbPairDangerMeters
                val uwbLevel = when {
                    uwbPrimD <= dangM -> BleConstants.LEVEL_DANGER
                    uwbPrimD <= warnM -> BleConstants.LEVEL_WARNING
                    else              -> BleConstants.LEVEL_SAFE
                }
                if (uwbLevel > stableLevel) {
                    // (A) 격상 — 실측이 더 가깝다. 차폐로 약한 RSSI 를 실측 거리로 끌어올린다(promote-only).
                    val lvName = if (uwbLevel == BleConstants.LEVEL_DANGER) "DANGER" else "WARNING"
                    Log.w(TAG, "[v1.1.36] UWB 거리 격상 ${lvName}: ${deviceId} d=%.2fm 지게차쌍=${forkliftPair} 임계=경고${warnM}m/위험${dangM}m (RSSI=${stableLevel} pEma=${avgRssi})".format(uwbPrimD))
                    stableLevel = uwbLevel
                    if (distanceLevel < uwbLevel) distanceLevel = uwbLevel
                } else if (uwbLevel < stableLevel) {
                    // (B) 이탈 강등 — '멀어지는 중'일 때만 실측 거리로 낮춘다(스쳐 지나감 = 끔). 접근·정지는 유지.
                    //     (v1.1.39) kfVel 거부권 제거 — UWB 활성 페어는 실측 운동학이 主권위. RSSI 멀티패스
                    //     반등이 해제를 수초 차단하던 원인 제거(이탈 지연 수초 → ~1s).
                    val kin = uwbRanger?.uwbKinematics?.get(deviceId)
                    if (kin != null && now - kin.atMs <= 1500L &&
                        kin.separatingStreak >= 3 && kin.closingMps < 0f) {
                        Log.w(TAG, "(v1.1.39) UWB 이탈 강등 ${stableLevel}->${uwbLevel}: ${deviceId} d=%.1fm 멀어짐v=%.1fkm/h streak=${kin.separatingStreak}".format(uwbPrimD, kin.closingMps * 3.6f))
                        stableLevel = uwbLevel
                        if (distanceLevel > uwbLevel) distanceLevel = uwbLevel
                    }
                }
            }
        }

        // [v1.0.26 Req2] 개별 sendDetectedBroadcast 폐지 — 목록은 onDeviceDetected 처리 직후
        // broadcastDeviceList() 가 alertState 전체를 한 번에 송출한다(단일 진실 공급원).

        // ── SAFE 처리 ───────────────────────────────────────────────────
        if (stableLevel == BleConstants.LEVEL_SAFE) {
            if (alertState.containsKey(deviceId)) {
                alertState.remove(deviceId)
                rssiPreFilter.clear(deviceId)
                medianFilter.clear(deviceId)      // [v1.0.45]
                pEmaFilter.clear(deviceId)        // [v1.0.45]
                rushFrameMap.remove(deviceId)     // [v1.0.45]
                dangerContactStreakMap.remove(deviceId)   // [v1.1.16 D]
                warningContactStreakMap.remove(deviceId)  // [v1.1.18]
                kf.reset()
                kalmanFilters.remove(deviceId)
                trackingStateMap.remove(deviceId)
                crossingStartMap.remove(deviceId)
                departingStartMap.remove(deviceId)
                approachStreakStartMap.remove(deviceId)   // [v1.0.46 #4] stale 시작시각 → 재접근 시 Time-Gate 즉시통과 방지
                fastApproachStreakMap.remove(deviceId)    // [v1.1.21] stale 카운터 → 재접근 시 1프레임에 즉시통과 방지
                forwardBiasLatchMap.remove(deviceId)      // [v1.1.11 C1] SAFE 강등 → 래치 리셋(재접근 시 fresh)
                wasStationaryMap.remove(deviceId)
                recedingStartMap.remove(deviceId)
                recedeRefMap.remove(deviceId)
                recedePeakMap.remove(deviceId)
                deviceRssiMap.remove(deviceId)
                mutedDevices.remove(deviceId)
                suddenLabelMap.remove(deviceId)
                deviceCategoryMap.remove(deviceId)
                deviceStateMap.remove(deviceId)
                deviceTurnMap.remove(deviceId); reverseRssiHist.remove(deviceId); reversePrepUntil.remove(deviceId)   // [v1.1.7 #1/#2]
                firebaseLastSaveMap.remove(deviceId)
                pendingDisplayMap.remove(deviceId)   // [v1.0.49 #3]
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    activeSoundLevel = BleConstants.LEVEL_SAFE
                    VibrationHelper.stopVibration(this)
                    OverlayManager.hideOverlay()
                } else {
                    resyncSoundToRemaining()  // [v1.1.37 ②] 상위 기기 이탈 → 남은 최대레벨로 사운드 하향 정합
                    updateFloatingOverlay()   // 다른 위험 기기로 플로팅 전환
                }
            }
            return
        }

        // [v1.0.27] 여기 도달 = 비-SAFE(경보 상황). 정지 중이라도 즉시 전투모드(ACTIVE) 보장.
        bleScanner?.setEcoMode(false)

        // 무음 중 — 상태 추적만 유지 (전역 무음 또는 [v1.0.25] 해당 기기 Acknowledge 무음)
        if (isMuted || isDeviceMuted(deviceId)) {
            alertState[deviceId] = Pair(stableLevel, alertState[deviceId]?.second ?: now)
            pendingDisplayMap.remove(deviceId)   // [v1.0.49 #3] 경보 등록 → 보류 표시 해제
            return
        }

        // IMU 정지→이동 전환 기록
        val nowStationary  = ImuFusion.isStationary
        val prevStationary = wasStationaryMap.getOrDefault(deviceId, false)
        wasStationaryMap[deviceId] = nowStationary
        if (prevStationary && !nowStationary) {
            Log.d(TAG, "IMU 정지→이동 전환 [$deviceId]")
        }

        // ── [v1.1.6] 노이즈 견고 이탈 판정 — 중간평활 EMA 레퍼런스 + 느린 감쇠 피크 ──────────────
        //   [회귀 배경] v1.1.5 는 피크·하락을 raw avg1sec(1초평균) 절대최대로 쟀다. 초근접(위험권)에서는
        //   BLE 멀티패스 노이즈로 avg1sec 가 ±5~10dBm 출렁여, 피크가 순간 최댓값에 고착 → (peak-avg1sec)
        //   가 거의 항상 RECEDING_DBM_DROP 를 넘겨 '가짜 이탈'로 판정 → 위험한데도 소리가 꺼졌다.
        //   [수정] avg1sec 를 EMA(RECEDE_REF_ALPHA)로 평활한 recedeRef 로 노이즈를 흡수해 판정한다.
        //   피크는 ref 상승 시 즉시 따라가고(접근), 정체·이탈 시엔 PEAK_DECAY_ALPHA 로 ref 를 향해 느리게
        //   감쇠한다 → '한 번 가까웠다 약간 멀어져 안정'이면 피크가 새 거리에 적응해 가짜 이탈이 풀린다.
        //   진짜 이탈은 ref 가 피크보다 빨리 내려가 (peak-ref) 차가 벌어져 ~1~2초 안에 잡힌다.
        //   Kalman 평활 강도와 독립이라 '강한 평활에서 이탈 미해제'였던 원래 v1.1.5 버그도 함께 해결.
        //   [v1.1.6 검증 보강] (1) 위험권 가드를 평활 권위값(stableLevel)으로 둔다 — stableLevel==DANGER 인
        //   동안은 이탈로 판정하지 않는다. 이탈은 '위험권 밖으로 멀어졌을 때'만 의미가 있고, 위험권 안에서
        //   접근 후 정지하면 α 비대칭(ref 0.3 vs peak 0.05)으로 (peak-ref) 차가 오래 5dBm 을 넘겨 '위험한데
        //   무음'이 되는 경로를 차단한다. ★ raw avg1sec 가드(구버전)는 초근접 ±5~10dBm 노이즈가 -55 를
        //   밑돌면 가짜 이탈을 latch 해 다시 '위험한데 무음'을 냈다(검증 DS-1/2) → 평활 stableLevel 로 교체.
        //   (2) recede 계산·판정은 '이미 경보 중(alertState 등록)' 기기에만 적용한다 — 미등록
        //   (신규·보류) 기기가 가짜 이탈로 전역 stopSound 를 호출해 다른 기기 경보까지 끄는 것을 방지하고,
        //   미등록이면 recede 상태를 비워 재등록 시 깨끗이 재시작(stale 피크 재출현 → 재접근 즉시 가짜 이탈 차단).
        val isReceding: Boolean
        val recedeRef: Double
        val recedePeak: Double
        if (alertState.containsKey(deviceId)) {
            val refPrev = recedeRefMap[deviceId]
            recedeRef =
                if (refPrev == null) avg1sec.toDouble()
                else refPrev + RECEDE_REF_ALPHA * (avg1sec - refPrev)
            recedeRefMap[deviceId] = recedeRef

            val peakPrev = recedePeakMap[deviceId]
            recedePeak = when {
                peakPrev == null     -> recedeRef                                          // 첫 표본: 현재값 초기화
                recedeRef > peakPrev -> recedeRef                                          // 접근: 피크 즉시 상승
                else                 -> peakPrev - PEAK_DECAY_ALPHA * (peakPrev - recedeRef)  // 정체/이탈: 느린 감쇠
            }
            recedePeakMap[deviceId] = recedePeak

            // 이탈 방향 감지: 평활 피크 대비 RECEDING_DBM_DROP 하락 + 위험권 밖(거리 권위값 가드)
            //   [v1.1.6 DS-1/2] 위험권 판정을 raw avg1sec → pEma 기반 평활 권위값으로 교체(노이즈 견고).
            //   avg1sec(1초평균)은 초근접 멀티패스로 ±5~10dBm 출렁여, 한 번의 깊은 dip(~-61)이 isReceding 을
            //   latch → 느린 피크 감쇠(~0.3dBm/frame)가 여러 프레임 '위험한데 무음'을 만들었다(검증 DS-1).
            //   ★[v1.1.6 R4-SIL-1] 가드는 stableLevel 이 아니라 distanceLevel(=demote·이탈히스테리시스 이전
            //   pEma 거리 레벨)로 본다. demoteWhileStationary 가 물리적 DANGER 를 WARNING 으로 인위 격하해도
            //   distanceLevel==DANGER 인 동안은 이탈로 인정하지 않아 '근접인데 전체 무음'을 차단한다(격하된
            //   WARNING 경보는 canonical 경로가 계속 울림). 진짜로 멀어지면 pEma 가 내려가 distanceLevel<DANGER
            //   → 이탈 인정·해제. [v1.1.8] 혼합 제거로 distanceLevel 은 순수 pEma 권위값(raw dip 에 불변).
            //   [v1.1.22 B] 위험권(DANGER) 안에서의 이탈도 잡도록 OR isDepartingNow 보강. 기존 가드
            //   (distanceLevel<DANGER)만으론 pEma 평활지연으로 거리레벨이 DANGER 에 떠 있는 동안의
            //   '멀어짐'을 못 잡아 이탈측 재발령을 허용했다. 단 정지근접(kfVel≈0·비DEPARTING)은
            //   isDepartingNow=false 라 원래 가드로 폴백 → R4-SIL-1(위험권 정지 무음) 방어 보존.
            isReceding = (recedePeak - recedeRef) >= RECEDING_DBM_DROP &&
                (distanceLevel < BleConstants.LEVEL_DANGER || isDepartingNow)
        } else {
            recedeRefMap.remove(deviceId)
            recedePeakMap.remove(deviceId)
            recedeRef = avg1sec.toDouble()
            recedePeak = avg1sec.toDouble()
            isReceding = false
        }

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
                Log.d(TAG, "이탈 감지 즉시 소리 중지: $deviceId (peak=%.1f, ref=%.1f, drop=%.1f dBm)".format(recedePeak, recedeRef, recedePeak - recedeRef))
                sendStatusBroadcast("↗ 이탈 감지 → 경보 일시 해제: ${extractDisplayName(deviceId)}")
            }
            val recedingMs = now - (recedingStartMap[deviceId] ?: now)
            if (recedingMs >= RECEDING_CLEAR_MS && alertState.containsKey(deviceId)) {
                alertState.remove(deviceId)
                rssiPreFilter.clear(deviceId)
                medianFilter.clear(deviceId)      // [v1.0.45]
                pEmaFilter.clear(deviceId)        // [v1.0.45]
                rushFrameMap.remove(deviceId)     // [v1.0.45]
                dangerContactStreakMap.remove(deviceId)   // [v1.1.16 D]
                warningContactStreakMap.remove(deviceId)  // [v1.1.18]
                kalmanFilters[deviceId]?.reset()
                kalmanFilters.remove(deviceId)
                wasStationaryMap.remove(deviceId)
                recedingStartMap.remove(deviceId)
                recedeRefMap.remove(deviceId)
                recedePeakMap.remove(deviceId)
                trackingStateMap.remove(deviceId)
                crossingStartMap.remove(deviceId)
                departingStartMap.remove(deviceId)
                approachStreakStartMap.remove(deviceId)   // [v1.0.46 #4]
                fastApproachStreakMap.remove(deviceId)    // [v1.1.21]
                forwardBiasLatchMap.remove(deviceId)      // [v1.1.11 C1] 이탈 정리 → 래치 리셋
                deviceRssiMap.remove(deviceId)
                firebaseLastSaveMap.remove(deviceId)
                pendingDisplayMap.remove(deviceId)   // [v1.0.49 #3]
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    VibrationHelper.stopVibration(this)
                    OverlayManager.hideOverlay()
                    activeSoundLevel = BleConstants.LEVEL_SAFE
                } else {
                    resyncSoundToRemaining()  // [v1.1.37 ②] 이탈 확인된 상위 기기 → 남은 최대레벨로 사운드 하향 정합
                    updateFloatingOverlay()   // 남은 위험 기기로 플로팅 갱신
                }
                sendStatusBroadcast("↗ 이탈 확인 → 경보 해제: ${extractDisplayName(deviceId)}")
                Log.d(TAG, "이탈 경보 해제: $deviceId (${recedingMs}ms 연속 이탈)")
                return
            }
        } else {
            recedingStartMap.remove(deviceId)
            // [v1.1.6 검증 보강] fail-loud 무음 복구는 아래 shouldAlert 게이트(!shouldAlert 분기)로 이동.
            //   여기서 즉시 재발령하면 같은 프레임에 격상(levelEscalated)·쿨다운경과로 canonical 발령이 또
            //   playDanger 를 호출(비멱등 → 사이렌 끊김 stutter)할 수 있어, 발령을 건너뛰는 프레임에 한해
            //   재발령하도록 canonical 과 상호배타인 !shouldAlert 위치로 옮겼다.
        }

        // ── TTC 기반 선발령 (RSSI 공간 vel 직접 사용, v1.0.20 / v1.0.31 raw 가드) ──────────
        // DEPARTING/CROSSING 상태에서는 억제 (이탈 중 = 충돌 위험 없음)
        //   [v1.0.46 #8] isStationary 억제 제거 — '정지한 작업자'에게 장비가 돌진하는 시나리오가
        //   TTC 의 존재 이유인데 내 IMU 정지가 선발령을 차단하고 있었다. 오발은 아래
        //   avg1sec(경고권)·peak500ms(위험권) 이중 게이트가 그대로 방어한다.
        //   ★ v1.0.31: estimateTTC 는 kfRssi(칼만)로 계산 → 칼만이 spike/lag로 가깝게 떠 있으면
        //     remaining<=0 이 되어 'TTC 0초' 오발이 났다. raw 실측(avg1sec)이 경고권(rssiWarning)
        //     이상으로 실제 가까울 때만 선발령을 허용해, 원거리에서의 TTC 0초 오발을 차단한다.
        //   ★ v1.0.39: 긴급(DANGER) 선발령 게이트 추가 — '직전 0.5초 동안 받은 RSSI 중 최댓값(피크)'이
        //     일정 거리 이상일 때만 긴급을 허용해, 멀리서 칼만 속도 추정만으로 긴급이 새어 나오던
        //     문제를 차단한다. (버퍼 비면 avg1sec 로 폴백)
        //   ★ v1.1.14: 그 피크 게이트를 effDanger(-55, 코앞)→effWarning(경고거리 설정값)으로 연동.
        //     '코앞에 와야 발령'이 예측을 무력화하던 병목 해소 — 경고거리 안에서 빠르게 접근하면
        //     (TTC≤ttcThresholdSec·vel>minApproachVelDbm) 위험거리 닿기 전 먼저 발령한다.
        //     오발은 그 두 다이얼(개발자 설정 스피너)이 방어한다.
        if (!warmingUp                                      // [v1.0.45] 콜드스타트 임펄스 선발령 보류
            && stableLevel == BleConstants.LEVEL_WARNING
            && newState == TrackingState.APPROACHING
            && avg1sec >= effWarning                                            // [v1.1.10] 페이로드 시프트
            && (recentPeakRssi(deviceId, 500L) ?: avg1sec) >= effWarning) {      // [v1.1.14] 피크 게이트=경고거리 설정값(effWarning) 연동
            val ttc = estimateTTC(kfRssi, kfVel)
            if (ttc != null && ttc <= TTC_THRESHOLD_SEC) {
                alertState[deviceId] = Pair(BleConstants.LEVEL_DANGER, now)  // ★ 먼저 업데이트 → 목록에 DANGER 반영
                pendingDisplayMap.remove(deviceId)   // [v1.0.49 #3] 경보 등록 → 보류 표시 해제
                Log.w(TAG, "TTC 선발령: $deviceId TTC=%.1fs kfVel=%.2fdBm/s".format(ttc, kfVel))
                forceAlarmVolume()
                // [v1.0.48 #4] TTC 급접근 진동 — 보행자·EPJ(작업자)는 전용 빠른 패턴(vibrateRapidApproach)으로
                //   일반 DANGER 진동과 촉각 구분(장비가 나에게 돌진 중 = 즉시 회피 신호). 지게차 운전자는
                //   기존 강패턴(vibrateDanger) 유지 — 운전 중 과도한 패턴 분화는 혼란만 준다.
                if (DevSettings.vibrationEnabled) {                                     // [v1.0.46 #7] 포그라운드에서도 진동
                    if (myCategory == BleConstants.CAT_FORKLIFT) VibrationHelper.vibrateDanger(this)
                    else VibrationHelper.vibrateRapidApproach(this)
                }
                if (DevSettings.soundEnabled)     AlertSoundPlayer.playDanger(this)
                activeSoundLevel = BleConstants.LEVEL_DANGER   // [v1.0.46 #2] 사이렌 레벨 동기
                updateFloatingOverlay()
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
                sendStatusBroadcast("⚡ 충돌 예측 %.0f초: ${extractDisplayName(deviceId)}".format(ttc))
                return
            }
        }

        if (DevSettings.logVerbose)   // [v1.0.46 배터리(g)] 프레임당 로그 → verbose 게이트
            Log.d(TAG, ("RSSI raw=$rssi → med=$medianValue → pre=$preFiltered → kf=%.1f → pEma=$pEma " +
                "vel=%.2fdBm/s state=$newState stable=$stableLevel fast=$promoteFast warm=$warmingUp").format(kfRssi, kfVel))

        val prev = alertState[deviceId]
        val prevLevel     = prev?.first  ?: BleConstants.LEVEL_SAFE
        val lastAlertTime = prev?.second ?: 0L
        val baseCooldown  = if (stableLevel == BleConstants.LEVEL_DANGER) DANGER_COOLDOWN_MS else WARNING_COOLDOWN_MS
        // DEPARTING 상태: 쿨다운 2배 적용 (핑퐁 방지)
        val cooldown = if (isNowDepart) baseCooldown * 2 else baseCooldown

        val isFirstDetection = prev == null
        val levelEscalated   = stableLevel > prevLevel
        val cooldownPassed   = now - lastAlertTime >= cooldown
        // [v1.0.45] 워밍업(Median 미충전) 구간은 신규/격상 발령 보류 — 콜드스타트 임펄스 오염 방어.
        // [v1.1.16 D] 단, raw 2프레임 확증된 신규 DANGER 진입은 워밍업이어도 즉시 1회 발령 허용
        //   (비콘이 위험거리로 '쑥' 들어오는 첫 접촉을 Median 충전 대기 없이 선발령). 아래 Time-Gate 도 면제.
        // [v1.1.22 C] warmingUp 한정 → 상시(warm 포함)로 일반화 + 멀어지는 중 제외. medianValue 선행 2프레임 확증
        //   (median-of-3 가 위험권)된 '접근/근접' 접촉은 워밍업이든 추적중이든 Time-Gate(0.5초)를
        //   우회해 즉시 발령한다(붙어 있어도 0.5초 안 울리던 지연 제거). isDepartingNow 면 제외(이탈측 발령 금지).
        //   stableLevel 은 위 [v1.1.22 C] 즉시격상 블록에서 medianValue 위험권이면 이미 끌어올려진 값 → 평활 lag 비의존.
        val fastDangerContact = !isDepartingNow && dangerStreak >= 2 && stableLevel >= BleConstants.LEVEL_DANGER
        // [v1.1.18] WARNING 거리 첫접촉도 raw 2프레임 확증되면 워밍업·Time-Gate 우회하고 즉시 발령(정지 근접 즉시 발령).
        //   stableLevel>=WARNING & warningStreak>=2 가 DANGER 케이스(stableLevel>=DANGER & dangerStreak>=2)를 포함 → 상위호환.
        val fastContact = fastDangerContact ||
            (!isDepartingNow && warningStreak >= 2 && stableLevel >= BleConstants.LEVEL_WARNING)
        // [v1.1.22 B] 멀어지는 중(isDepartingNow) 격상·재발령 차단 — 물리적으로 멀어지면 위험이 커질 수
        //   없으므로 levelEscalated 는 pEma 평활 lag 아티팩트다. 쿨다운 재알람도 이탈측에선 금지해야
        //   '지나가고 울림'을 없앤다. isFirstDetection(브랜드 신규 접촉)만 페일세이프로 무가드 유지.
        val shouldAlert = (!warmingUp || fastContact) &&
            (isFirstDetection ||
             (levelEscalated && !isDepartingNow) ||
             (cooldownPassed && !isReceding && !isDepartingNow))
        if (!shouldAlert) {
            // [v1.0.49 #3] 워밍업 등으로 발령 보류된 '신규' 기기 — 경보는 아니지만 목록엔 '감지됨' 노출.
            if (isFirstDetection) pendingDisplayMap[deviceId] = now
            // [v1.1.6 검증 보강] fail-loud 무음 복구 — '발령을 건너뛰는' 프레임에서만 동작(여기는 곧 return).
            //   배경: 직전 이탈 episode 의 즉시 stopSound(L1100)로 activeSoundLevel=SAFE 가 된 뒤, 기기가
            //   여전히/다시 위험권(stableLevel==DANGER)인데 쿨다운 미경과로 shouldAlert=false 면 최대 한
            //   쿨다운(~2초) 무음이 생긴다. 이 경로를 '추적 중 + 평활 권위값 위험권 + 위험음 미만'일 때 즉시
            //   위험 재발령으로 닫는다(쿨다운 무시). 정규 발령(canonical)은 이 return 이후라 상호배타 →
            //   playDanger 중복호출(비멱등 stutter) 없음.
            //   [v1.1.6 DS-1/3] 판정 기준을 raw avg1sec → 평활 stableLevel 로 통일. (a) canonical 과 동일한
            //   거리 권위값(pEma 기반 stableLevel)을 써, '평활은 DANGER 인데 raw 노이즈 dip 으로 무음'이던
            //   불일치(DS-3)를 제거한다. (b) isReceding 가드도 stableLevel<DANGER(L1085)라, 이탈 프레임은
            //   여기 stableLevel>=DANGER 와 정확한 여집합으로 상호배타 → 진짜 이탈 즉시정지는 유지되고,
            //   genuine 이탈로 stableLevel 이 이미 위험권 밖이면 복구가 되살리지 않아 ghost-danger 과알람도 없다.
            if (!isMuted && !isDeviceMuted(deviceId) && alertState.containsKey(deviceId) &&
                stableLevel >= BleConstants.LEVEL_DANGER && !isDepartingNow &&   // [v1.1.22 B] 이탈측 무음복구 재발령 금지
                activeSoundLevel < BleConstants.LEVEL_DANGER) {
                forceAlarmVolume()
                activeSoundLevel = BleConstants.LEVEL_DANGER
                if (DevSettings.vibrationEnabled) VibrationHelper.vibrateDanger(this)
                if (DevSettings.soundEnabled)     AlertSoundPlayer.playDanger(this)
                updateFloatingOverlay()
                Log.d(TAG, "위험권 유지·무음 감지 → 즉시 재발령(쿨다운 무시): $deviceId (stableLevel=$stableLevel avg1sec=$avg1sec)")
            }
            // [v1.1.28] fail-quiet 강등 정정 — 위 fail-loud 재발령(쿨다운 무시)의 대칭(거울상).
            //   배경: 추적 중인 기기가 demoteWhileStationary(정지 근접)·히스테리시스로 DANGER→WARNING
            //   격하되면 재생 중인 DANGER 사이렌(루프)이 stale 가 된다. canonical 의 stopSound 는 격상에서만
            //   호출되고(L1568 은 v1.1.28 에서 '!=' 로 고쳤으나, 쿨다운 미경과면 이 프레임은 canonical 에
            //   닿지도 못한다), playWarning 은 danger 루프가 isPlaying 인 동안 no-op 이라 소리가 WARNING 으로
            //   바뀌지도 않는다. 결과: 격하됐는데 DANGER 사이렌이 최대 한 쿨다운(~수초)~사실상 영구 지속
            //   (사용자: '위험 알림이 꺼지지 않아'). 여기서 즉시 멈추고 현재(낮아진) 레벨 소리로 정정한다.
            //   단 '다른' 기기가 아직 동급 이상이면 그 경보를 끊지 않도록 보존 — 이 시점 alertState[deviceId]
            //   는 옛 레벨(canonical L1559 에서야 갱신)이라 getCurrentMaxLevel() 대신 '이 기기 제외' otherMax
            //   로 직접 판정한다. 소리를 줄이는 방향이라 isDepartingNow 와 무관하게 항상 안전(이탈이면 더 바람직).
            else if (!isMuted && !isDeviceMuted(deviceId) && alertState.containsKey(deviceId) &&
                     activeSoundLevel >= BleConstants.LEVEL_DANGER && stableLevel < activeSoundLevel) {
                val otherMax = alertState.entries
                    .filter { it.key != deviceId }
                    .maxOfOrNull { it.value.first } ?: BleConstants.LEVEL_SAFE
                if (otherMax < activeSoundLevel) {
                    AlertSoundPlayer.stopSound()
                    activeSoundLevel = stableLevel
                    if (stableLevel == BleConstants.LEVEL_WARNING && !idleIdleQuiet) {
                        if (DevSettings.vibrationEnabled) VibrationHelper.vibrateWarning(this)
                        if (DevSettings.soundEnabled)     AlertSoundPlayer.playWarning(this)
                    }
                    updateFloatingOverlay()
                    Log.d(TAG, "강등 정정 → stale 상위 사이렌 즉시 정지(쿨다운 무시): $deviceId (stableLevel=$stableLevel < active, otherMax=$otherMax)")
                }
            }
            return
        }

        // ── [v1.0.35 Time-Gate] + [v1.0.36 코너링 연장 · 충돌 기하학 필터] — 신규(첫 감지) 경보 한정 ──
        // 여기 도달 = shouldAlert(신규/격상/쿨다운경과) 통과. 이 중 '신규(첫 감지)'에만
        // 아래 두 보수 조건을 추가로 요구한다 ([v1.0.47 #3] 격상은 면제로 변경):
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
        // ※ 쿨다운 재알람(추적중·동급)·격상(levelEscalated)은 면제 — 게이트는 첫 감지에만 적용.
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

        // [v1.0.36→v1.1.7 #1] 충돌 기하학 — 속도 비트 제거로 합산 접근속도를 산출할 수 없다.
        //   closingSpeedKmh=0 → geometryValid=false → 기하학 필터 자동 비활성, 순수 Time-Gate 동작.
        //   (회전 2비트는 방향 표시용일 뿐 접근속도 추정엔 쓰지 않는다.)
        val closingSpeedKmh = 0.0                                             // 예상 최대 접근속도(km/h) — 미산출
        val expectedKfVel   = closingSpeedKmh * CLOSING_KMH_TO_DBMS            // → 예상 RSSI 접근속도(dBm/s)
        val closingRatio    = if (expectedKfVel > 0.01) kfVel / expectedKfVel else 0.0
        val geometryValid   = closingSpeedKmh >= COLLISION_MIN_CLOSING_KMH     // 양쪽 거의 정지면 판정 불가
        // 정면충돌 코스: 실제 접근이 예상의 60% 이상 → Time-Gate 즉시 통과(강한 발령).
        val headOnCourse    = geometryValid && closingRatio >= COLLISION_HEAD_ON_RATIO
        // 측면/나란히: 실제 접근이 예상의 30% 이하 + 절대 접근속도도 느림(<2.0) → 보류(경계 격하).
        // [v1.0.49 #1] 콜드 칼만 유예 — update 횟수 미달이면 vel 이 초기값(0.0) 부근이라 ratio≈0 으로
        //   돌진 기기도 측면으로 오판된다. 칼만이 웜업되기 전엔 측면판정을 무효화한다(headOn 즉시통과·
        //   Time-Gate 는 영향 없음 — 콜드 ratio≈0 이면 headOn 은 어차피 false, 보수 방향 그대로).
        val kalmanWarm      = kf.updateCount >= KALMAN_GEOMETRY_MIN_UPDATES
        val sideCourse      = kalmanWarm && geometryValid && closingRatio <= COLLISION_SIDE_RATIO &&
                              kfVel < COLLISION_ABS_SAFE_VEL_DBM

        // [v1.1.21] 빠른 정면접근 → Time-Gate 즉시통과. closingSpeedKmh(km/h)를 1바이트 페이로드로
        //   못 구해 headOnCourse 가 영구 false 였던 공백을 칼만 접근속도(kfVel)로 메운다. kfVel 은
        //   Median→EMA→칼만 다단 평활된 위상선행값이라 거리(pEma)·1초평균보다 먼저 접근을 포착 →
        //   '빠르게 다가오는 지게차'가 Time-Gate(0.5초) + 평활 lag 에 막혀 CPA(최근접점)를 지난 뒤에야
        //   울리던 지연을 제거한다. 단발 raw spike 방어: 임계를 '2프레임 연속' 넘어야 확증(다단 평활이라
        //   1프레임 튐으론 임계까지 못 오르며, 추가 확증으로 오발을 한 겹 더 막는다). 측면/나란히 교차는
        //   kfVel 이 낮아 안 걸려 과경보는 거의 안 는다. 임계=DevSettings.fastApproachBypassVelDbm 라이브.
        val fastApproachFrames = if (kfVel >= FAST_APPROACH_BYPASS_VEL_DBM)
                                     (fastApproachStreakMap[deviceId] ?: 0) + 1 else 0
        fastApproachStreakMap[deviceId] = fastApproachFrames
        val fastApproach = fastApproachFrames >= 2

        // headOn(합산 km/h 미산출 → 영구 false) 또는 빠른 정면접근(kfVel 2프레임 확증)이면 Time-Gate
        //   즉시 통과, 아니면 평상/코너링 Time-Gate 충족 필요.
        val approachSustained = headOnCourse || fastApproach || (kfApproaching && approachStreakMs >= timeGateMs)

        // [v1.0.47 #3] 게이트 적용을 '신규(첫 감지)'로 축소 — 격상(levelEscalated)은 면제.
        //   이미 게이트를 통과해 WARNING 경보 중인 기기의 DANGER 승급에까지 kfVel≥0.5 연속을 요구하면,
        //   수신 감도가 낮은 폰(RSSI 동특성 작음 → kfVel 미달)은 위험권에 들어와도 승급이 무기 보류됐다
        //   (위험 경보 지연·기기별 비대칭의 원인). 스파이크 오발은 Median→EMA→칼만→P-EMA 다단 평활과
        //   3중 하드게이트, raw 2차 방어선이 이미 막으므로 격상까지 게이트하는 것은 중복 보수였다.
        if (isFirstDetection && !fastContact && (sideCourse || !approachSustained)) {   // [v1.1.18] 2프레임 확증 WARNING/DANGER 첫접촉은 접근속도 게이트 면제(정지 근접 즉시 발령)
            pendingDisplayMap[deviceId] = now   // [v1.0.49 #3] 보류 중에도 목록엔 '감지됨' 노출
            Log.d(TAG, "[v1.0.36] 경보 보류 ${extractDisplayName(deviceId)}: side=$sideCourse 접근지속=${approachStreakMs}ms(<${timeGateMs}) fast=${fastApproachFrames}/2 vel=%.2f".format(kfVel))
            return   // 소리/화면 경보 보류 — 다음 프레임 재평가(접근지속 충족 또는 정면충돌 코스 시 발령)
        }

        pendingDisplayMap.remove(deviceId)   // [v1.0.49 #3] 게이트 통과 → 보류 표시 해제(아래에서 경보 등록)

        alertState[deviceId] = Pair(stableLevel, now)
        if (isMuted) return

        forceAlarmVolume()
        val globalMax = getCurrentMaxLevel()
        if (stableLevel < globalMax) {
            Log.d(TAG, "우선순위 무시: $stableLevel < $globalMax (활성)")
            return
        }
        // [v1.1.28] 격상뿐 아니라 강등(stableLevel<active)에서도 stale 상위 사이렌을 멈춘다.
        //   기존 '>' 는 격상만 멈춰, demoteWhileStationary·히스테리시스로 DANGER→WARNING 격하 시
        //   재생 중인 DANGER 루프(AlertSoundPlayer)가 안 꺼졌다. 아래 WARNING 분기의 playWarning 은
        //   danger 루프가 isPlaying 인 동안 no-op 이라 소리가 WARNING 으로 바뀌지도 못하고, activeSoundLevel
        //   만 WARNING 으로 낮춰져 위험 사이렌이 영구 지속됐다(사용자: '위험 알림이 꺼지지 않아').
        //   '!=' 로 강등도 정지 — !shouldAlert 의 fail-quiet(쿨다운 미경과 프레임) 와 쌍을 이룬다.
        if (stableLevel != activeSoundLevel) AlertSoundPlayer.stopSound()
        activeSoundLevel = stableLevel

        when (stableLevel) {
            // [v1.0.46 #1] 거리 기반 DANGER 커밋 분기 복원 — v1.0.20 재작성에서 사라진 회귀.
            //   서행 접근(TTC 미발동·특수상태 아님)도 위험권 진입이면 위험 경보+Firebase 기록.
            BleConstants.LEVEL_DANGER -> {
                if (DevSettings.vibrationEnabled)
                    VibrationHelper.vibrateDanger(this)
                if (DevSettings.soundEnabled)
                    AlertSoundPlayer.playDanger(this)
                if (DevSettings.autoSaveAlerts) {
                    val lastFbSave = firebaseLastSaveMap[deviceId] ?: 0L
                    if (now - lastFbSave >= FIREBASE_SAVE_THROTTLE_MS) {
                        firebaseLastSaveMap[deviceId] = now
                        FirebaseManager.saveAlert(deviceId, myId, avgRssi, "DANGER")
                    }
                }
                val name = extractDisplayName(deviceId)
                updateFloatingOverlay()
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
                Log.w(TAG, "위험 발생: $deviceId ($name) avgRssi=$avgRssi state=$newState vel=%.2fdBm/s".format(kfVel))
            }
            BleConstants.LEVEL_WARNING -> {
                // [v1.1.10 Phase2] IDLE-IDLE 가청 억제 — 내 IMU 정지 + 상대 IDLE 송신(둘 다 정지)이면
                //   가청(진동·소리)만 억제하고 표시·오버레이·목록·위젯은 그대로 유지한다. DANGER 는
                //   여기로 오지 않는다(정지 시 demoteWhileStationary 가 DANGER→WARNING 격하 → 항상 WARNING).
                //   둘 중 하나라도 움직이면 다음 프레임 idleIdleQuiet=false 로 즉시 가청 복원. 기본 OFF(옵트인).
                if (DevSettings.vibrationEnabled && !idleIdleQuiet)   // [v1.0.46 #7] 포그라운드(화면 켜짐)에서도 진동
                    VibrationHelper.vibrateWarning(this)
                if (DevSettings.soundEnabled && !idleIdleQuiet)
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

    // ── [v1.1.9 화면 꺼짐 웨이크업] 알림 구간 한정 PARTIAL_WAKE_LOCK ──────────────────
    //   배경(현장 "웨이크업이 늦어"): 화면을 끄면 기기가 Doze 로 진입해 CPU 가 간헐 수면한다.
    //     포그라운드 스캔은 계속 살아 있지만, 콜백 처리(Median→EMA→칼만→발령→진동·소리·오버레이)
    //     도중 CPU 가 자버리면 경보 체감이 늦어진다. WAKE_LOCK 권한은 선언돼 있으나 acquire 가
    //     한 번도 호출되지 않은 것이 근본 원인이었다.
    //   설계: 상시 점유 대신 '경보가 실제로 발령되는 순간'(forceAlarmVolume — 특수·TTC·무음복구·
    //     정규·테스트 모든 발령의 단일 길목)에만 짧게 잡고, timeout 으로 OS 가 자동 해제하게 한다
    //     (release 누락에도 배터리 누수 0). 위험 기기가 계속 근처면 매 발령마다 timeout 이 갱신돼
    //     알림 구간 동안만 유지된다 → 보행자 휴대기기에 적합한 near-zero idle 비용.
    private val ALERT_WAKELOCK_MS = 3000L   // 발령~사용자 인지(화면 켜기) 보장 구간
    private val alertWakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafeAlert:AlertWakeLock")
            .apply { setReferenceCounted(false) }
    }

    // [v1.1.9] 경보 발령 순간 CPU 를 ALERT_WAKELOCK_MS 만큼 깨워 진동·소리·오버레이 완주를 보장.
    //   화면 켜짐(사용자 인지·조작 중 = Doze 아님)이면 불필요하므로 잡지 않는다. timeout 기반이라
    //   재호출하면 새 timeout 으로 연장되고, 위험 소실 후엔 자동 해제된다(reference-count 미사용).
    private fun acquireAlertWakeLock() {
        if (isScreenOn) return
        try { alertWakeLock.acquire(ALERT_WAKELOCK_MS) } catch (e: Exception) { Log.w(TAG, "WakeLock 획득 실패: ${e.message}") }
    }

    // [v1.1.9] 보유 중인 WakeLock 즉시 해제 — 알림 전체 종료(stopAll/onDestroy) 시 timeout 을
    //   기다리지 않고 곧바로 풀어 배터리를 아낀다. 미보유면 무동작.
    private fun releaseAlertWakeLock() {
        try { if (alertWakeLock.isHeld) alertWakeLock.release() } catch (e: Exception) { Log.w(TAG, "WakeLock 해제 실패: ${e.message}") }
    }

    // ── [v1.1.13 탐지단계 마이크로 wakelock] 발령 '이전' 처리체인 완주 보장 ──────────────
    //   v1.1.9 는 발령 '순간'(forceAlarmVolume)부터 CPU 를 잡아 진동·소리·오버레이를 보장했으나,
    //   화면 꺼짐(Doze) 시 콜백→Median→EMA→칼만→'발령 판정' 처리 구간에서 CPU 가 자면 발령 자체가
    //   지연/누락될 수 있다(특히 콜드스타트·근접 진입 첫 프레임). 이 사각을 메운다.
    //   설계: 화면 꺼짐 + 수신 RSSI ≥ WAKE_RSSI_DBM(상시 웨이크 경로와 동일 임계, 불변식②)일 때만
    //     짧게 잡고 체인 종료 즉시 finally 에서 해제한다. 발령이 실제 일어나면 그 안에서
    //     alertWakeLock(3s)이 독립적으로 인계하므로 공백 없이 이어진다(서로 다른 lock — 간섭 0).
    //     timeout 은 release 누락 대비 안전망(주 루퍼 단일스레드라 정상 경로는 ms 내 해제).
    private val DETECTION_WAKELOCK_MS = 500L
    private val detectionWakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafeAlert:DetectionWakeLock")
            .apply { setReferenceCounted(false) }
    }

    // [v1.1.13] 화면 꺼짐 + 근접(>=WAKE) 수신 프레임에서만 CPU 를 짧게 확보 — 처리체인이 Doze 로
    //   끊기지 않게 한다. 화면 켜짐(사용자 인지 중) 또는 약신호(<WAKE)면 불필요하므로 잡지 않는다.
    private fun acquireDetectionWakeLock(rssi: Int) {
        if (isScreenOn || rssi < WAKE_RSSI_DBM) return
        try { detectionWakeLock.acquire(DETECTION_WAKELOCK_MS) } catch (e: Exception) { Log.w(TAG, "DetectWakeLock 획득 실패: ${e.message}") }
    }

    // [v1.1.13] 처리체인 종료 직후 즉시 해제 — 프레임 사이(스캔 간격)에는 CPU 를 재워 배터리를 아낀다.
    //   미보유면 무동작. 경보가 발령된 경우 alertWakeLock 은 별도 lock 이라 영향받지 않는다.
    private fun releaseDetectionWakeLock() {
        try { if (detectionWakeLock.isHeld) detectionWakeLock.release() } catch (e: Exception) { Log.w(TAG, "DetectWakeLock 해제 실패: ${e.message}") }
    }

    private fun forceAlarmVolume() {
        acquireAlertWakeLock()   // [v1.1.9] 화면 꺼짐(Doze) 시 진동·소리·오버레이 완주 보장
        ignoringVolumeChange = true
        try {
            val am     = getSystemService(AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = (maxVol * DevSettings.alarmVolume / 100f).toInt().coerceIn(0, maxVol)
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            Log.d(TAG, "알람 볼륨: $target/$maxVol (${DevSettings.alarmVolume}%)")
        } catch (e: Exception) { Log.w(TAG, "볼륨 강제 설정 실패: ${e.message}") }
        volumeGuardHandler.removeCallbacksAndMessages(null)   // [v1.0.46 #11] 연속 호출 시 직전 해제 예약 갱신
        volumeGuardHandler.postDelayed({ ignoringVolumeChange = false }, 300)
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
        // (v1.1.31) 거리 문자열(빈값=기존 dBm 폴백)을 플로팅에도 전달 — 목록과 동일 표기 규칙.
        val dist  = UwbCalibrator.distanceTextFor(uwbPairKeyFor(topId), rssi, uwbRanger?.uwbDistances?.get(topId))
        OverlayManager.showFloating(
            context  = this,
            deviceId = topId,
            name     = suddenLabelMap[topId] ?: makeApproachLabel(topId),
            rssi     = rssi,
            danger   = level >= BleConstants.LEVEL_DANGER,
            distText = dist
        )
    }

    private fun startScanHealthCheck() {
        healthCheckHandler.removeCallbacksAndMessages(null)
        healthCheckHandler.postDelayed(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - lastScanResultMs
                if (elapsed > SCAN_HEALTH_CHECK_MS) {
                    // [v1.0.46 #9] stopBle()+applyMode() 전체 재시작은 TX 광고까지 끊어 상대 기기에서
                    //   내가 사라지는 가시성 갭을 냈다(주변 무기기 정상 상황에서도 15초마다 반복).
                    //   수신(RX) 스캐너만 재시작 — 송신(TX)은 무중단. 상태 브로드캐스트 스팸도 제거.
                    Log.w(TAG, "스캔 헬스체크: ${elapsed / 1000}초간 결과 없음 → RX 스캔 재시작")
                    bleScanner?.restartScan()
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
     * v1.0.34 평상(NORMAL) 접근 표시문자열.
     *   ※ 특수상태(후진·하역)는 suddenLabelMap(makeStateLabel)이 우선하며, 이 함수는 그 폴백.
     *   [v1.0.51 #3→v1.1.7 #1] 이동 판정은 STATE 기준(STATE!=IDLE=이동). 속도 비트 제거로
     *     문구는 이동="{이름}이(가) 이동 중입니다." / 정지="{이름}이(가) 주변에 있습니다." 로 통일.
     *   [v1.1.7 #2] reversePrepUntil latch 가 살아있으면 "후진(전진)을 대비해주세요 · {기본문구}" 로 선두 안내.
     */
    private fun makeApproachLabel(deviceId: String): String {
        val name = extractDisplayName(deviceId)
        val moving = (deviceStateMap[deviceId] ?: BleConstants.PSTATE_IDLE) != BleConstants.PSTATE_IDLE
        // [v1.1.10] 디코드된 16진수(역할·회전 비트)를 사람이 읽는 표시로 노출 — 수신측 팝업/오버레이/목록에서
        //   송신자의 역할(지게차/EPJ/보행자)·회전방향을 본다. 역할 접두는 페이로드 캐시가 있을 때만(비콘 폴백 X).
        val rolePrefix = deviceCategoryMap[deviceId]?.let { "[${categoryRoleName(it)}] " } ?: ""
        val turnWord = if (moving) when (deviceTurnMap[deviceId] ?: BleConstants.TURN_STRAIGHT) {
            BleConstants.TURN_LEFT  -> "좌회전하며 "
            BleConstants.TURN_RIGHT -> "우회전하며 "
            else -> ""
        } else ""
        val base = if (moving) "${rolePrefix}${name}이(가) ${turnWord}이동 중입니다."
                   else        "${rolePrefix}${name}이(가) 주변에 있습니다."
        // [v1.1.7 #2] 후진(전진) 대비 latch 가 살아있으면 안내문을 선두에 덧붙인다(RSSI 추세 반전 감지).
        //   [v1.1.9 R6] 이중 방어 — latch 가 살아있어도 상대가 현재 정지(IDLE = !moving) 를 송신 중이면
        //   prefix 를 억제한다. latch 유지(기본 4s) 도중 상대가 정지로 전환되면 페이로드 상태를 우선한다.
        return if (moving && (reversePrepUntil[deviceId] ?: 0L) > System.currentTimeMillis())
                   "후진(전진)을 대비해주세요 · $base"
               else base
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
        // [v1.0.49 #3] 보류(pending) 기기 stale 정리 — TTL(스캐너 타임아웃 정렬) 경과분 제거.
        pendingDisplayMap.entries.removeIf { now - it.value > PENDING_DISPLAY_TTL_MS }
        val entries = alertState.entries.toList()
        // [v1.0.49 #3] 쓰로틀 조건 확장 — 경보가 없어도 보류 기기가 있으면 200ms 쓰로틀 대상(빈 목록만 즉시).
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
            // (v1.1.31) 4번째 필드 = 거리 문자열(빈값 가능) — 구버전 파서는 f.size>=3 만 보므로 뒤호환.
            val dist  = UwbCalibrator.distanceTextFor(uwbPairKeyFor(id), rssi, uwbRanger?.uwbDistances?.get(id))
            if (sb.isNotEmpty()) sb.append('\u001E')
            sb.append(level).append('\u001F').append(rssi).append('\u001F').append(name).append('\u001F').append(dist)
        }

        // [v1.0.49 #3] 게이트 보류 기기 병합 — alertState 미등록(경보 발령 전) 기기를 SAFE 레벨 행으로 추가.
        //   MainActivity 가 SAFE 레벨을 '감지됨'(연청·축소) 스타일로 렌더하므로 UI 수정 없이 시각 구분된다.
        //   경보 행 우선, 잔여 슬롯에 RSSI 강한(가까운) 순으로 채움 — 합계 10개 cap 유지.
        //   구분자는 기존 직렬화와 동일: 30.toChar()=U+001E(레코드), 31.toChar()=U+001F(필드).
        var mergedCount = sorted.size
        pendingDisplayMap.keys
            .filter { it !in alertState }
            .sortedByDescending { deviceRssiMap[it] ?: -100 }
            .take(10 - sorted.size)
            .forEach { id ->
                val rssi = deviceRssiMap[id] ?: -99
                val name = suddenLabelMap[id] ?: makeApproachLabel(id)
                val dist = UwbCalibrator.distanceTextFor(uwbPairKeyFor(id), rssi, uwbRanger?.uwbDistances?.get(id))
                if (sb.isNotEmpty()) sb.append(30.toChar())
                sb.append(BleConstants.LEVEL_SAFE).append(31.toChar()).append(rssi).append(31.toChar()).append(name).append(31.toChar()).append(dist)
                mergedCount++
            }

        // [v1.0.42] 폴백 동기화 소스 갱신 — 브로드캐스트가 누락돼도 MainActivity 폴링이 이 값을 읽는다.
        detectedSnapshot = sb.toString()
        detectedCount    = mergedCount

        // [v1.0.42] setPackage 로 '명시적' 브로드캐스트화 → RECEIVER_NOT_EXPORTED 수신자와 확실히 호환.
        sendBroadcast(Intent(BROADCAST_DETECTED).setPackage(packageName).apply {
            putExtra(EXTRA_DEVICE_LIST, sb.toString())
            putExtra(EXTRA_DEVICE_COUNT, mergedCount)
        })
    }

    // ── [v1.0.42 Req2] 내 장비(Local) 상태 전파 — 수신(Target) 경로와 완전 분리 ──────────
    private var lastLocalSnapshot = ""

    /**
     * 내 장비(Local) 상태 스냅샷 갱신 + 전파.
     *   bleAdvertiser 가 '실제 송출 중'인 category/state/turn 을 읽어 직렬화한다(필드 구분 U+001F).
     *   값 변화가 있을 때만 브로드캐스트(중복 억제). 폴백용 static localSnapshot 은 항상 최신으로 유지.
     *   ※ 이 함수는 오직 내 송출 상태에서만 값을 만든다 — 상대 페이로드(Target)가 끼어들 여지가 구조적으로 없다.
     */
    private fun broadcastLocalState() {
        val adv = bleAdvertiser
        val cat = adv?.txCategory ?: myCategory
        val st  = adv?.txState   ?: BleConstants.PSTATE_IDLE
        val turn = adv?.txTurnDir ?: BleConstants.TURN_STRAIGHT
        val snap = "$cat${31.toChar()}$st${31.toChar()}$turn"
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
        if (rssi >= WAKE_RSSI_DBM) {
            // [v1.1.26 B] 상대가 경고권(WAKE) 안에 들어옴 → 내 광고를 LOW_LATENCY 버스트로 가속해
            //   상대가 나를 더 빨리 발견(상호 보호). 웨이크보다 먼저 요청해야 슬립 중이었어도
            //   직후 resumeAdvertising/startAdvertising 이 burstUntilMs 를 보고 LOW_LATENCY 로 시작한다.
            if (DevSettings.burstEnabled) bleAdvertiser?.requestBurst(DevSettings.burstHoldMs)
            wakeAdvertiser()
        }
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
        // [v1.1.26 A] 이동 중(IMU 비정지)에는 근접/경보가 없어도 광고를 깨워 둔다 — 콜드스타트의
        //   핵심 레버: 움직이는 동안 LOW_POWER(~1s) 슬립으로 떨어지지 않아 첫 접촉 즉시 송신.
        //   '자다 깨어 정신 못 차리는' 첫 깨어남 지연 제거. 정지하면 정상 슬립 복귀.
        val moving = DevSettings.keepAdvertiseWhileMoving && !ImuFusion.isStationary
        when {
            anyNear || hasAlert || moving -> if (adv.isPaused) {
                adv.resumeAdvertising(); broadcastLocalState()
                Log.d(TAG, "RSSI 웨이크(평가): 근접/경보/이동 → 연속 광고 재개")
            }
            else -> if (!adv.isPaused) {
                adv.pauseAdvertising()
                Log.d(TAG, "RSSI 슬립(평가): 근접 신호 없음 → 하트비트 모드")
            }
        }
        // [v1.1.23] 스캔 배칭 승격/복귀를 광고 슬립/웨이크와 동일 집계로 동기화 —
        //   근접/경보 유지 → 0ms 유지, 모두 stale + 경보 없음 → 500ms 절전 복귀.
        //   [v1.1.26] 단, 이동(moving)은 스캔 배칭에서 제외 — 혼자 움직일 뿐인데 스캔까지 0ms 로
        //   올리면 과도. 광고만 깨워 두고, 스캔 배칭 가속은 실제 근접/경보일 때만.
        bleScanner?.setHazardNear(anyNear || hasAlert)
    }

    /**
     * [v1.1.12 L1] 절전(eco) 강등 안전 게이트 — 위험 신호가 하나라도 있으면 true → 강등 보류(전투 유지).
     *   anyNear   : 신선한(≤SIGNAL_STALE_MS) wakeRssiMap 표본 중 RSSI ≥ WAKE → 근접 기기 존재
     *   hasAlert  : 활성 경보(alertState) 존재
     *   approach  : 신선한 접근(kfVel>0) 표본 존재 — 정지 직전 다가오던 기기를 절전 진입으로 놓치지 않음
     *  ※ 읽기 전용(맵 변경 없음) — stale 표본 정리는 evaluateAdvertiserPower(2.5s 주기)가 전담.
     *  ※ 듀티 불변: 스캔/광고 라디오 설정을 직접 바꾸지 않고, ecoDowngradeRunnable 의 '강등해도 되는가' 판정만 강화.
     *     (기존 alertState.isEmpty() 게이트를 엄격히 더 보수적으로 — 누락위험 0, 절전 진입만 줄어든다.)
     */
    private fun isDangerPresent(): Boolean {
        val now = System.currentTimeMillis()
        val anyNear  = wakeRssiMap.values.any { (r, ts) -> now - ts <= SIGNAL_STALE_MS && r >= WAKE_RSSI_DBM }
        val hasAlert = alertState.isNotEmpty()
        val approach = lastApproachAtMs != 0L && now - lastApproachAtMs <= SIGNAL_STALE_MS
        return anyNear || hasAlert || approach
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
        // [판정 파라미터] 전단 EMA 알파 라이브 갱신 — emaState(수렴 상태)는 보존한 채 α만 교체.
        //   다른 판정 파라미터는 'private val 게터'가 매 프레임 DevSettings 를 직접 읽어 별도 처리 불요.
        applyEmaAlphas()
        // [v1.0.48 #5] 스캔 주기·광고 간격도 라이브 반영 — 죽은 설정이던 scanPeriodMs/advertiseInterval 이
        //   이제 스캔/광고 모드에 매핑되므로(BleScanner/BleAdvertiser) 저장 즉시 라디오에 적용한다.
        //   키 필터링 없이 무조건 호출 — 양쪽 모두 내부에서 '매핑 모드가 실제로 바뀐 경우'에만
        //   재시작하는 no-op 가드가 있어 저비용이고, resetToDefault(clear) 의 null key 도 자연 커버.
        bleScanner?.refreshScanMode()
        bleAdvertiser?.refreshAdvertiseMode()
        applyUwbLiveState()   // (v1.1.30) UWB 토글 라이브 반영
        UwbCalibrator.applySite()   // (v1.1.34) 사업장 코드 변경 → Δ보정 프로파일 전환(무변경 no-op)
        Log.d(TAG, "[Req5] 설정 라이브 반영(key=$changedKey): KF프리셋=$preset 위험=${BleConstants.rssiDanger}dBm 경고=${BleConstants.rssiWarning}dBm TimeGate=${DevSettings.timeGateMs}ms 스캔주기=${BleConstants.scanPeriodMs}ms 광고간격=${BleConstants.advertiseInterval}ms")
        sendStatusBroadcast("설정 라이브 반영됨")
    }

    /**
     * (v1.1.30) UWB 가동 상태를 현재 설정에 맞춘다 — 시작 시점과 라이브 설정 변경 양쪽에서 호출.
     * 조건 미충족·토글 OFF 면 세션을 정리하고 UWB 없는 광고로 되돌린다(BLE 폴백은 항상 유지).
     */
    private fun applyUwbLiveState() {
        if (bleAdvertiser == null) { uwbRanger?.stop(); uwbRanger = null; return }
        val want = UwbRanger.isHardwareSupported(this)
                && (DevSettings.uwbEnabled || DevSettings.uwbForce)   // (v1.1.38 B) 강제 활성화 시 uwbEnabled OFF 여도 가동
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
        // (v1.1.39 a·b) 종전에는 (want && ranger==null) 1회 생성 분기뿐이라 초기화 실패(시스템 UWB OFF 등)
        //   후 재시도가 전무했고, REAPPLY·설정 변경 호출이 전부 no-op 에 흡수됐다(one-shot 래치).
        //   이제 건강한 ranger 는 유지하고, 초기화 실패 상태(isSupported=false)면 버리고 재생성 —
        //   생성 후에는 성공까지 백오프 재시도 루프(5s→×2→60s cap)가 돈다.
        uwbRanger?.let { existing ->
            if (existing.isSupported) return   // 건강 — 유지(applyLiveSettings 가 매 설정 변경마다 호출)
            existing.stop()
            uwbRanger = null
        }
        // [v1.1.37 ③] 내 전체 광고 ID(prefix+id) — 같은 역할 쌍의 컨트롤러 선출 tiebreak 기준.
        //   BleScanner 가 상대에게 붙이는 fullId 와 동일한 prefix 규칙(DEVICE_/WALKER_)이라야
        //   peerOutranksMe(id < myFullId)·peerIsVehicle(startsWith DEVICE_PREFIX) 비교가 정합.
        val myFullId = (if (myMode == "DEVICE") BleConstants.DEVICE_PREFIX
                        else BleConstants.WALKER_PREFIX) + myId
        val ranger = UwbRanger(this, lifecycleScope, myFullId, myMode == "DEVICE",
            onStatus = { msg -> sendStatusBroadcast(msg) },
            onLocalAddressChanged = { payload -> bleAdvertiser?.restartWithUwbAddress(payload) },
            // (v1.1.32) 세션 우선순위·시작 게이트용 평활 RSSI(pEma) 프로바이더 — 미추적 기기는 null
            rssiOf = { id -> deviceRssiMap[id] },
            // (v1.1.33) 지게차 낀 쌍 판별 — 시작 게이트 완화(-90)·세션 우선순위 가산용.
            //   내가 지게차(DEVICE 모드 기본 카테고리)거나 상대 카테고리 캐시가 지게차면 true.
            forkliftPairOf = { id ->
                myCategory == BleConstants.CAT_FORKLIFT ||
                    deviceCategoryMap[id] == BleConstants.CAT_FORKLIFT
            }
        )
        uwbRanger = ranger
        lifecycleScope.launch {
            // (v1.1.39 a) 초기화 재시도 루프 — 1회 실패(권한 타이밍·시스템 UWB 토글 등)가 영구
            //   BLE 폴백으로 굳지 않게 성공까지 백오프 재시도. ranger 가 교체·정리되면 즉시 종료.
            var backoffMs = 5_000L
            while (true) {
                if (uwbRanger !== ranger) return@launch   // 교체·정리됨 — 이 루프는 폐기
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

    // [판정 파라미터] 전단 EMA(rssiPreFilter) 비대칭 알파를 DevSettings 값으로 주입.
    //   후처리 P-EMA(pEmaFilter, 0.4/0.15)는 칼만 P항 평활 전용 설계값이라 알파는 고정 유지.
    // [v1.1.29] 워밍업 대칭 푸시 수는 전단·후처리 양쪽에 공통 주입 — 재시작 편차(첫 표본 앵커)
    //   교정은 두 EMA 인스턴스 모두에 적용해야 완성된다(전단만 고치면 후처리 P-EMA 의
    //   자체 앵커 잔상이 남는다). 서비스 시작(onCreate)과 설정 라이브 변경 모두 이 함수를 경유.
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
        releaseAlertWakeLock()   // [v1.1.9] 알림 종료 → WakeLock 즉시 해제(timeout 대기 없이)
        alertState.clear()
        suddenLabelMap.clear()
        deviceCategoryMap.clear()
        deviceStateMap.clear()
        deviceTurnMap.clear(); reverseRssiHist.clear(); reversePrepUntil.clear()   // [v1.1.7 #1/#2]
        broadcastDeviceList(force = true)   // [v1.0.26 Req2] 서비스 중지 → 빈 목록 송출('감지 없음' 반영)
        localSnapshot = ""; lastLocalSnapshot = ""   // [v1.0.42 Req2] 내 장비(Local) 스냅샷 초기화
        rssiPreFilter.clearAll()
        medianFilter.clearAll()    // [v1.0.45]
        pEmaFilter.clearAll()      // [v1.0.45]
        rushFrameMap.clear()       // [v1.0.45]
        dangerContactStreakMap.clear()   // [v1.1.16 D]
        warningContactStreakMap.clear()  // [v1.1.18]
        kalmanFilters.clear()
        trackingStateMap.clear()
        crossingStartMap.clear()
        departingStartMap.clear()
        wasStationaryMap.clear()
        oneSecBuffer.clear()
        recedingStartMap.clear()
        recedeRefMap.clear()
        recedePeakMap.clear()
        deviceRssiMap.clear()
        approachStreakStartMap.clear()   // [v1.0.35] Time-Gate 접근 추적 정리
        fastApproachStreakMap.clear()    // [v1.1.21] 빠른접근 연속카운터 정리
        pendingDisplayMap.clear()        // [v1.0.49 #3] 보류 표시 정리
        mutedDevices.clear()
        forwardBiasLatchMap.clear()      // [v1.1.11 C1] 전진가산 래치 일괄 정리(다른 26개 맵과 정합)
        firebaseLastSaveMap.clear()
        testRunnable?.let { testHandler.removeCallbacks(it) }
        testRunnable = null
        muteHandler.removeCallbacksAndMessages(null)
        volumeGuardHandler.removeCallbacksAndMessages(null)   // [v1.0.46 #11] 볼륨가드 해제 예약 정리
        ignoringVolumeChange = false
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
        releaseAlertWakeLock()   // [v1.1.9] !isRunning 경로 등 stopAll 미경유 시에도 확실히 해제
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
