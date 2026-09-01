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
import com.wf11.safealert.firebase.FirebaseManager
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
        const val ACTION_MUTE_DEVICE   = "ACTION_MUTE_DEVICE"   // v1.0.25: 사이드바 행 터치 → 특정 기기 ACK 음소거(v1.1.65: 30초)
        const val ACTION_MUTE_ALL      = "ACTION_MUTE_ALL"     // (v1.1.65) 사이드바를 끝까지 드래그해 닫음 → 현재 위험 기기 일괄 ACK 음소거
        const val ACTION_TEST_STATE    = "ACTION_TEST_STATE"   // [v1.0.34] 개발자 수동 STATE 주입(후진/하역 예약비트 송신 테스트)
        const val ACTION_REAPPLY_UWB   = "ACTION_REAPPLY_UWB"  // (v1.1.38 A) 권한 부여·강제 토글 직후 UWB 세션 재평가 넛지
        // (v1.1.67) 상시 알림 → MainActivity 역할 전환 확인 다이얼로그 직행. 서비스가 아니라
        //   Activity 가 받는 액션이다. 전환 자체는 정지→재시작(v1.1.60)이라 서비스 단독 처리 시
        //   prefs·UI 상태가 이원화된다. 기존 confirmSwitchRole() 재사용이 유일 안전 경로.
        const val ACTION_OPEN_SWITCH_ROLE = "ACTION_OPEN_SWITCH_ROLE"
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
    // (v1.1.65) ACK(확인) 뮤트 전용 지속시간 — 화면 터치 임시 무음(MUTE_DURATION_MS 10초)과 분리한다.
    //   사이드바 행 탭(muteDevice)·끝까지 드래그(muteAllHazards)가 이 값을 쓴다. 계획서 2-2 "ACK 뮤트 10초 → 30초".
    private val ACK_MUTE_DURATION_MS = 30_000L
    // (v1.1.61) 항목4: 같은 경보레벨(WARNING/DANGER) 연속 체류 → 자동 뮤트까지의 시간.
    //   하드코드 상수 — 옵션 UI 는 사용자가 '추후'로 보류(임의 설정 노출 금지).
    private val DWELL_MUTE_MS = 5_000L
    // (v1.1.62) 항목5: 존 비콘(안전구역) 상태 머신 상수.
    //   진입=enterRssi 이상 연속 ZONE_MIN_SAMPLES 표본(순간 스파이크 오진입 방지),
    //   이탈=enterRssi−ZONE_EXIT_HYST_DB 미만 즉시(히스테리시스 데드밴드로 경계 플랩 방지)
    //        또는 신호 두절 ZONE_LOST_GRACE_MS 초과,
    //   엔트리 폐기=ZONE_SIGNAL_STALE_MS 초과(맵 누수 방지). 존 판정은 raw RSSI(게인 미적용).
    private val ZONE_MIN_SAMPLES     = 3
    private val ZONE_EXIT_HYST_DB    = 5
    private val ZONE_LOST_GRACE_MS   = 3_000L
    private val ZONE_SIGNAL_STALE_MS = 4_000L
    private val muteHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // [v1.0.46 #11] forceAlarmVolume 의 ignoringVolumeChange 해제(300ms) 전용 핸들러.
    //   muteHandler 공용이던 시절, muteTemporarily()의 removeCallbacksAndMessages(null)가 해제
    //   콜백까지 지워 ignoringVolumeChange=true 고착(볼륨버튼 무음 영구 무력화) 레이스가 있었다.
    private val volumeGuardHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var isMuted = false

    @Volatile private var activeSoundLevel = BleConstants.LEVEL_SAFE

    // (v1.1.65) 세이프존 안이면 광고 위험레벨을 SAFE 로 고정 — '존 안에서는 알림을 보내지도 않는다'.
    //   호출처 4곳이 전부 bleAdvertiser.updateRisk() 전용이라 판정·표시에는 영향이 없다.
    //   IN_ZONE 비트를 모르는 구버전(v1.1.61 이하) 상대도 SAFE 레벨은 해석하므로 하위호환된다.
    //   광고 자체는 유지 — 존 밖 기기 화면에서 내가 사라지지는 않는다(존재는 보이되 무해로 보임).
    private fun getCurrentMaxLevel() =
        if (myZoneInside) BleConstants.LEVEL_SAFE
        else alertState.values.maxOfOrNull { it.first } ?: BleConstants.LEVEL_SAFE

    // (v1.1.61) '가청' 최대레벨 — dwell 뮤트된 기기를 제외한 소리 소유권 판정 전용.
    //   무음(뮤트) 기기가 canonical globalMax/잔여 재정합을 점유해 신규·잔여 기기의 가청 경보까지
    //   틀어막는 것을 방지한다. 위험 재광고(updateRisk)·목록·오버레이는 여전히 raw 레벨 사용
    //   (뮤트=소리·진동만 억제, 위험 '상태'는 불변이라는 스펙 그대로).
    private fun getAudibleMaxLevel() =
        // (v1.1.62) 내가 존 비콘 접촉 중이면 가청 최대레벨=SAFE — 존 안=전 기기 소리·진동 억제.
        //   resyncSoundToRemaining/fail-quiet 재정합이 이 값을 쓰므로 존 진입=능동 정지, 이탈=즉시 복원.
        if (myZoneInside) BleConstants.LEVEL_SAFE
        else alertState.entries.filter { !isDwellMuted(it.key, it.value.first) }
            .maxOfOrNull { it.value.first } ?: BleConstants.LEVEL_SAFE

    // [v1.1.37 ③] UWB↔RSSI 보정 학습·조회 키 — 역할쌍 세그먼트(본문은 CalibrationEngine 소유).
    //   상대 카테고리 미상(스캔 캐시 없음)이면 가장 보수적인 보행자로 간주.
    private fun uwbPairKeyFor(deviceId: String): String =
        CalibrationEngine.uwbPairKeyFor(myCategory, deviceCategoryMap[deviceId] ?: BleConstants.CAT_WALKER)

    /**
     * [v1.1.37 ②] 부분 이탈 사운드 하향 정합 — 기기 '일부'만 제거된 뒤(alertState 비어있지 않음)
     *   남은 기기들의 실제 최대 레벨로 재생 중인 사운드를 즉시 맞춘다. 사이렌을 소유하던 상위(DANGER) 기기가
     *   이탈했는데 남은 기기는 더 낮은 레벨이면, 기존엔 canonical/fail-quiet 정정이 '남은 기기의 다음 프레임'
     *   에서야 동작해 상위 사이렌이 수 초~사실상 영구 잔존했다(사용자: '스쳐 지나갔으면 신호를 끄라고').
     *   소리를 '낮추는' 방향(remainingMax < activeSoundLevel)에서만 동작 → 남은 기기가 동급 이상이면 무개입
     *   = 경보 누락 0 보장. fail-quiet 강등 정정(processAlert L1625)의 teardown 판(版).
     */
    private fun resyncSoundToRemaining() {
        val remainingMax = getAudibleMaxLevel()   // (v1.1.61) dwell 뮤트 기기 제외 — 뮤트만 남으면 무음까지 하향
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
                    sendStatusBroadcast("블루투스 꺼짐")
                    stopBle()
                    // (v1.1.64 패치3-4) 기존에는 브로드캐스트만 보냈다 = 화면을 열어 둔 사람만 인지.
                    //   주머니 속에서 BT 가 꺼지면 무방비인데 아무 통지가 없었다 → 상시 알림으로 승격.
                    checkSystemHealth()
                }
            }
        }
    }

    /**
     * (v1.1.64 패치3-4) 위치 기능 on/off 감시.
     * API 30 이하에서는 위치 서비스가 꺼지면 BLE 스캔 결과가 나오지 않는다(스캔은 '성공'인데 무결과).
     * API 31+ 는 BLUETOOTH_SCAN 에 neverForLocation 을 선언(AndroidManifest:7-8)해 해당 없음.
     */
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            checkSystemHealth()
        }
    }



    private val SCAN_HEALTH_CHECK_MS   = 15_000L

    // ── (v1.1.64 패치3-3) 무성 실패 표면화 ────────────────────────────────
    //   기존에는 상시 알림이 '송신 ON · 수신 ON' 문구로 고정돼, 블루투스가 꺼지든
    //   권한이 회수되든 광고가 실패하든 알림은 계속 정상이라고 표시했다.
    //   = 보호가 끊긴 사실을 착용자가 알 방법이 없었다.
    //   각 서브시스템의 이상 사유를 아래로 모아 같은 알림 ID 를 갱신한다. 정상이면 null.
    @Volatile private var systemFault:  String? = null   // BT·권한·위치 (checkSystemHealth)
    @Volatile private var txFault:      String? = null   // BleAdvertiser.onTxFault
    @Volatile private var soundFault:   String? = null   // AlertSoundPlayer.onSoundFault
    @Volatile private var overlayFault: String? = null   // OverlayManager.onOverlayFault
    @Volatile private var faultBeeped   = false          // 이상 진입 시 1회만 경고음

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


    // (v1.1.61) 항목4: 기기별·레벨별 체류(dwell) 자동 뮤트 — 같은 레벨(WARNING/DANGER)에 DWELL_MUTE_MS
    //   연속 체류하면 그 기기·그 레벨의 소리·진동만 억제한다(표시·목록·오버레이·Firebase·판정·재광고 유지).
    //   해제(전체 리셋)=존 이탈(SAFE 정리·이탈 확정·미추적 강등·기기 소실·서비스 중지) → 재진입=정상 발령+새 5초.
    //   W→D 상승 전이=DANGER 뮤트 해제(격상 발령 항상 가청)+타이머 재시작. DANGER 5초 체류=DANGER+WARNING
    //   동시 뮤트(D→W 후퇴 시 WARNING 도 조용히 유지 — 사용자 승인 스펙). 추적은 alertState 등록(발령) 중에만.
    private val dwellLevelMap       = mutableMapOf<String, Int>()             // deviceId → 추적 중인 레벨
    private val dwellSinceMap       = mutableMapOf<String, Long>()            // deviceId → 그 레벨 진입 시각(ms)
    private val dwellMutedLevelsMap = mutableMapOf<String, MutableSet<Int>>() // deviceId → 뮤트된 레벨 집합

    // (v1.1.62) 항목5: 존 비콘(안전구역) 상태 — 존 비콘은 detectedDevices/판정에 넣지 않고
    //   BleScanner.onZoneBeaconSignal 별도 경로로만 흐른다. beaconKey="ZONE_"+uuid8/MAC.
    //   myZoneInside=내가 존 안(어느 존이든 1개 이상 inside) → 자기 소리·진동 억제+광고 IN_ZONE 비트.
    //   peerInZoneMap=상대의 IN_ZONE 선언 수신 캐시 → 그 기기를 무해(SAFE) 판정(억제 전용).
    private val zoneSampleMap    = mutableMapOf<String, Int>()     // beaconKey → 진입 연속 표본 수
    private val zoneEnterRssiMap = mutableMapOf<String, Int>()     // beaconKey → 프로파일 진입 임계(dBm)
    private val zoneLastSeenMap  = mutableMapOf<String, Long>()    // beaconKey → 마지막 수신 시각(ms)
    private val zoneInsideMap    = mutableMapOf<String, Boolean>() // beaconKey → 현재 존 안 여부
    @Volatile private var myZoneInside = false









    // #3 게이트 보류 기기 목록 표시: 워밍업/기하학 보류로 alertState 등록 전인 기기를 '감지됨(SAFE)'
    //    행으로 하단 목록에 노출 — 경보 발령 전 불가시 구간 제거. 오버레이(hazardListForOverlay)는
    //    경보 전용 의미를 지키기 위해 제외. TTL(스캐너 타임아웃 정렬) 경과 시 목록에서 자동 제거.
    private val PENDING_DISPLAY_TTL_MS = 6000L



    // ── [v1.1.41] UWB/RSSI 양방향 조건부 판단 분리(Case A/B) ─────────────────────────────
    //   Case A(UWB↔UWB): UWB 실측 신호가 흐르는 페어는 UWB 거리 '전용' 판정 — RSSI 는 판단에 절대 불개입.
    //   Case B(UWB↔Non-UWB): UWB 실측 신호가 없는 페어는 기존 RSSI 판정.
    //   [v1.1.46] Case A 성립 권위 = 실측 신호 신선도 하나(마지막 표본 ≤ UWB_MEAS_FRESH_MS).
    //   신호가 신선하면 UWB 거리로 판정하고, 아니면 그 순간부터 RSSI 가 판정한다 — 어느 쪽으로도
    //   판정 공백 없음. 실측이 다시 흐르면 첫 표본부터 즉시 UWB 복귀. 레인징 배관(세션)은 UwbRanger
    //   가 스캔응답·백오프로 알아서 잇고 끊는 내부 사정일 뿐, 판정은 배관을 절대 건드리지 않는다 —
    //   v1.1.43/44 의 '무표본 1s=좀비 세션 철거(onDeviceLost)'는 마지널 신호 페어에서 철거(1s)→
    //   재합류(250ms)→철거 무한 반복(플랩)+컨트롤러 철거 시 전 세션 연쇄 붕괴를 일으켜 폐지.
    //   [v1.1.45] RSSI 시작 게이트(듀티사이클) 철폐 유지 — UWB 선언 피어는 BLE 시야에 들면 거리
    //   불문 무조건 페어 시도·상시 유지. (v1.1.42 의 0x9ABC 광고 단독 권위는 스택 가동 '선언'일 뿐
    //   실측 증거가 아니어서 판정 전면 공백을 만든 회귀로 폐지 — 광고는 발견·진단용으로만 유지.)
    private val uwbDist = UwbDistanceManager { uwbRanger }

    /** [Phase 3 T3] 판정 경로는 AlertStateMachine 이 소유한다. 부작용/조회는 아래 Effects 로 되돌아온다. */
    private val asm: AlertStateMachine = AlertStateMachine(object : AlertStateMachine.Effects {
        override val myId get() = this@BleService.myId
        override val myMode get() = this@BleService.myMode
        override val myCategory get() = this@BleService.myCategory
        override val isMuted get() = this@BleService.isMuted
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
        override fun updateDwellMute(deviceId: String, level: Int, now: Long) = this@BleService.updateDwellMute(deviceId, level, now)
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

    // [Phase 3 T3] 판정 상태는 AlertStateMachine 소유 - 아래는 동일 인스턴스 별칭(리플렉션 테스트/잔여 호출부용)
    private val wasStationaryMap = asm.wasStationaryMap
    private val alertState = asm.alertState
    private val kalmanFilters = asm.kalmanFilters
    private val lastKfVelMap = asm.lastKfVelMap
    private val filterPreserveMap = asm.filterPreserveMap
    private val timeGateWaiveSet = asm.timeGateWaiveSet
    private val shadowFusionMap = asm.shadowFusionMap
    private val rushFrameMap = asm.rushFrameMap
    private val dangerContactStreakMap = asm.dangerContactStreakMap
    private val warningContactStreakMap = asm.warningContactStreakMap
    private val warningMissRefMap = asm.warningMissRefMap
    private val trackingStateMap = asm.trackingStateMap
    private val crossingStartMap = asm.crossingStartMap
    private val departingStartMap = asm.departingStartMap
    private val recedingStartMap = asm.recedingStartMap
    private val recedeRefMap = asm.recedeRefMap
    private val recedePeakMap = asm.recedePeakMap
    private val deviceRssiMap = asm.deviceRssiMap
    private val mutedDevices = asm.mutedDevices
    private val peerInZoneMap = asm.peerInZoneMap
    private val suddenLabelMap = asm.suddenLabelMap
    private val deviceCategoryMap = asm.deviceCategoryMap
    private val deviceStateMap = asm.deviceStateMap
    private val deviceTurnMap = asm.deviceTurnMap
    private val reverseRssiHist = asm.reverseRssiHist
    private val reversePrepUntil = asm.reversePrepUntil
    private val firebaseLastSaveMap = asm.firebaseLastSaveMap
    private val pendingDisplayMap = asm.pendingDisplayMap
    private val approachStreakStartMap = asm.approachStreakStartMap
    private val fastApproachStreakMap = asm.fastApproachStreakMap
    private val forwardBiasLatchMap = asm.forwardBiasLatchMap
    private val KF_VEL_SEED_TTL_MS get() = asm.KF_VEL_SEED_TTL_MS

    // 아래 3개는 별칭 — 소유는 UwbDistanceManager 이고 같은 인스턴스를 가리킨다(호출부 diff 0 +
    //   테스트 ReflectionHelpers.getField 가 읽는 백킹 필드 유지).
    private val peerUwbSeenMap   = uwbDist.peerUwbSeenMap
    private val uwbSampleAtMsMap = uwbDist.uwbSampleAtMsMap
    private val uwbSafeStreakMap = uwbDist.uwbSafeStreakMap

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
            reevaluateZones()       // (v1.1.62) 존 신호 두절 이탈·스테일 엔트리 폐기 폴링(+IN_ZONE self-heal)
            pushRssiEcho()          // [v1.1.53 상호RSSI] 내가 측정한 상대 RSSI 표를 스캔응답 에코에 실어 되돌려 송출
            broadcastLocalState()   // [v1.0.42 Req2] 주기 갱신 — Local UI(상태/회전) 폴링 소스 최신 유지
            speedPushHandler.postDelayed(this, SPEED_PUSH_INTERVAL_MS)
        }
    }

    // [v1.1.53 상호RSSI] 내가 측정한 각 상대의 RSSI(pEma=deviceRssiMap)를 (해시,값) 표로 만들어
    //   BleAdvertiser 스캔응답 에코 블록(0xE0C0)에 실어 되돌려 송출한다. 상대는 자기 해시로 자신의
    //   '상대가 측정한 나' 값을 찾아 대칭 판정(sym)에 쓴다. 가까운(강한 RSSI) 상위 8기만 —
    //   updateRssiEcho 내부에서 UWB 공존 시 5기(15B)로 다시 절단(스캔응답 31B 예산).
    private fun pushRssiEcho() {
        if (!DevSettings.reciprocalRssiEnabled) return
        val snapshot = deviceRssiMap.toList()   // [(fullId, pEma)] — 동시수정 방지 스냅샷
        if (snapshot.isEmpty()) {
            bleAdvertiser?.updateRssiEcho(ByteArray(0))   // 상대 없음 → 낡은 에코 잔존 방지(내부 contentEquals no-op)
            return
        }
        val entries = snapshot
            .sortedByDescending { it.second }    // 강한 RSSI(가까운) 우선 상위 K
            .take(8)
            .map { (fullId, rssi) -> BleConstants.shortHash(fullId) to rssi }
        bleAdvertiser?.updateRssiEcho(BleConstants.encodeEchoTable(entries, 8))
    }

    override fun onCreate() {
        super.onCreate()
        // [Phase 4 T1] BleService 소유 상태를 제거 레지스트리에 등록 — 제거는 asm.registry 단일 경로.
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
        // deferred — [v1.1.58 fix4] 웜 필터 보존 대상. 콜드 클리어·TTL 만료 때만 비운다.
        asm.registry.addDeferred("rssiPreFilter", { id -> rssiPreFilter.clear(id) }, { rssiPreFilter.clearAll() })
        asm.registry.addDeferred("medianFilter",  { id -> medianFilter.clear(id) },  { medianFilter.clearAll() })
        asm.registry.addDeferred("pEmaFilter",    { id -> pEmaFilter.clear(id) },    { pEmaFilter.clearAll() })
        // [Phase 4 T2] 개발자 설정 계기(STATE-03) 노출 — onDestroy 에서 해제한다.
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
        // (v1.1.64 패치3-4) API 30 이하에서는 위치 기능이 꺼지면 BLE 스캔이 '성공'인 채 결과만 0건이 된다.
        //   콜백도 오류도 없는 완전한 무성 실패 → provider 변경을 직접 구독해 즉시 재평가한다.
        registerReceiver(locationReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))

        // (v1.1.64 패치3-3) 경보음·화면 경보 이상을 상시 알림으로 승격.
        //   두 대상은 싱글턴 object 라 이 람다가 서비스 인스턴스를 붙든다 → stopAll() 에서 반드시 null 로 끊는다.
        AlertSoundPlayer.onSoundFault = { reason -> soundFault   = reason; refreshNotification() }
        OverlayManager.onOverlayFault = { reason -> overlayFault = reason; refreshNotification() }
        // (v1.1.69) 접힘 상태 사이드바의 헤더 탭 = 공정 변경. 사이드바가 상시 노출이므로 이 경로는 항상 살아 있다.
        //   즉시 전환이 아니라 MainActivity 확인 다이얼로그를 거친다 — 주머니 속 오탭이 곧 감시 공백이다.
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
        //   싱글턴은 프로세스가 살아 있는 한 사유를 유지한다. setFault 는 같은 사유의 반복 통지를 막으므로,
        //   여기서 현재 값을 이어받지 않으면 서비스 재생성 전에 발생한 이상은 새 서비스에 영원히 전달되지 않는다.
        soundFault   = AlertSoundPlayer.soundFaultReason
        overlayFault = OverlayManager.overlayFaultReason

        CalibrationEngine.loadEchoPriors()   // [v1.1.55] FB 에코 프라이어 — 캐시 즉시 복원+비동기 갱신(판정 경로는 메모리만 읽음)
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
                return START_STICKY
            }
            // (v1.1.62 버그C) 복원 근거 없음(사용자 중지 상태에서 시스템 재기동) — BLE 없이
            //   포그라운드 알림만 띄운 유령 인스턴스가 STICKY 로 영구 잔존하는 것을 차단.
            stopSelf(startId)
            return START_NOT_STICKY
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
            ACTION_MUTE_ALL    -> muteAllHazards()
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
        // (v1.1.69) 감시가 도는 동안 사이드바는 상시 노출이다. 위험 대상이 0대여도 접힘 상태로 떠 있어야
        //   헤더 탭으로 공정을 바꿀 수 있다. 블루투스 점검보다 먼저 띄운다 — BT 가 꺼져 아래에서 조기
        //   반환하더라도 공정 변경 진입점은 살아 있어야 하기 때문이다.
        updateFloatingOverlay()
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter

        val modeStr = "칼만(위험 ${BleConstants.rssiDanger}dBm / 경고 ${BleConstants.rssiWarning}dBm)"
        Log.i(TAG, "=== BLE 임계값 확인: $modeStr ===")
        sendStatusBroadcast("설정: $modeStr")

        if (btAdapter == null) {
            sendStatusBroadcast("블루투스 미지원 기기")
            checkSystemHealth()   // (v1.1.64 패치3-4) 브로드캐스트는 화면을 연 사람만 본다 → 상시 알림 승격
            return
        }
        if (!btAdapter.isEnabled) {
            sendStatusBroadcast("블루투스 꺼짐 — 켜주세요")
            checkSystemHealth()
            return
        }

        // (v1.1.64 패치3-3) 여기부터 새 광고자·스캐너를 만든다. 이전 인스턴스가 남긴 송신 이상
        //   사유는 유효하지 않으므로 먼저 지운다(새 광고자가 또 실패하면 콜백이 다시 채운다).
        txFault = null

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
                // (v1.1.64 패치3-3) 광고 실패가 로그로만 끝나던 경로 → 상시 알림으로 승격
                bleAdv.onTxFault = { reason -> txFault = reason; refreshNotification() }
                bleAdv.startAdvertising(myId)
                // (v1.1.30) UWB 는 광고 시작 후 별도 적용 — 모드 전환 대비 이전 세션 정리 후 재생성
                uwbRanger?.stop(); uwbRanger = null
                applyUwbLiveState()
                sendStatusBroadcast("TX 송출 요청: $myId")
                broadcastLocalState()   // [v1.0.42 Req2] 송출 시작 시점 Local 상태 초기 전파
                startAdvPowerManager()  // [v1.0.42 Req3] RSSI 기반 송출 전력 관리(슬립/웨이크) 시작
                Log.d(TAG, "TX 시작: $prefix$myId")
            } else {
                sendStatusBroadcast("TX 오류: 이 기기는 BLE 광고 미지원")
                Log.e(TAG, "TX: bluetoothLeAdvertiser null")
                txFault = "이 기기는 BLE 송신을 지원하지 않음"   // (v1.1.64 패치3-3)
            }
        }

        if (doRx) {
            val scanner = btAdapter.bluetoothLeScanner
            if (scanner != null) {
                lastScanResultMs = System.currentTimeMillis()
                startScanHealthCheck()
                bleScanner = BleScanner(scanner).also { s ->
                    s.onStatusUpdate = { msg -> sendStatusBroadcast(msg) }
                    // [v1.1.53 상호RSSI] 내 에코 해시 = 상대가 나를 저장하는 키(prefix+14B 절단 id)의 해시.
                    //   상대는 이 해시로 태그된 '상대가 측정한 나의 RSSI'를 에코에 실어 되돌려준다.
                    //   BleAdvertiser 가 광고 시 id 를 UTF-8 14바이트로 절단하므로(BleAdvertiser:196) 여기서도
                    //   동일 절단해야 상대 스캐너가 만든 fullId 와 해시가 일치한다(ASCII·14자 이하면 그대로).
                    val myWireId = String(myId.toByteArray(Charsets.UTF_8).take(14).toByteArray(), Charsets.UTF_8)
                    val myPrefix = if (myMode == "DEVICE") BleConstants.DEVICE_PREFIX else BleConstants.WALKER_PREFIX
                    s.myEchoHash = BleConstants.shortHash(myPrefix + myWireId)
                    // [v1.1.47] BLE 신호 타임아웃(전투 2s/휴식 6s)이어도 신선한 UWB 실측(≤1s)이
                    //   흐르는 기기는 소실 판정 유예 — 광고 일시 유실만으로 onDeviceLost 가 실측
                    //   중인 UWB 세션을 강제 철거하지 않게 한다. 실측까지 끊기면 다음 스윕에서
                    //   정상 소실(27개 상태맵 정리 포함) — 유예는 '연기'일 뿐 경로 자체는 불변.
                    s.uwbMeasuringCheck = { id -> uwbDist.freshUwbDistM(id) != null }
                    s.startScanning(object : BleScanCallback {
                        override fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int, remoteState: Int, remoteTurn: Int, payloadPresent: Boolean, peerEchoRssi: Int, peerInZone: Boolean) {
                            lastScanResultMs = System.currentTimeMillis()

                            if (myMode == "WALKER"
                                && deviceId.startsWith(BleConstants.WALKER_PREFIX)
                                && !deviceId.contains("BEA_")   // [v1.1.58 fix1] 비콘(BEA_)은 walker 게이트 면제 — 보행자도 비콘 경보 수신(기존: 100% 차단)
                                && !DevSettings.walkerDetectsWalker) return

                            // (v1.1.65) 세이프존 전면 억제 — 존 안에서는 '존 비콘 신호만' 받는다.
                            //   존 비콘은 BleScanner 가 onZoneBeaconSignal 전용 경로로 흘려보내 여기 도달하지
                            //   않으므로, 이 리턴이 존 수신을 막지는 않는다(존 진입·이탈 판정 정상 동작).
                            //   진입 시점의 잔존 기기는 refreshMyZoneInside 의 forceLoseAll 이 이미 정리했다.
                            if (myZoneInside) return

                            val effectiveRssi = if (DevSettings.debugMode) DevSettings.simulatedRssi else rssi
                            noteRssiForWake(deviceId, effectiveRssi)   // [v1.0.42 Req3] 근접 신호 → 즉시 웨이크 판단
                            acquireDetectionWakeLock(effectiveRssi)   // [v1.1.13] 화면 꺼짐+근접(>=WAKE) → 처리체인 완주용 짧은 CPU 점유
                            // [v1.1.23] 동일 게이트로 스캔 배칭도 0ms 즉시 전달 승격 — wakelock 으로 CPU 를 깨워도
                            //   배칭 500ms 면 BLE 칩이 0.5s 모아 효과 반감되므로 함께 0ms 로 내린다(false 복귀는 평가주기 집계).
                            if (effectiveRssi >= WAKE_RSSI_DBM) bleScanner?.setHazardNear(true)
                            // (v1.1.62) 상대 IN_ZONE 선언 캐시 — processAlert 호출 전에 갱신해야 이번 표본 판정에 반영
                            peerInZoneMap[deviceId] = peerInZone
                            try {
                                processAlert(deviceId, effectiveRssi, remoteState, remoteTurn, payloadPresent, peerEchoRssi)
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
                            // [v1.1.58 fix4] 필터 defer-clear — 마지막 RSSI 스냅샷이 있으면 즉시 지우지 않고 보존.
                            //   30s 내 ±10dB 밴드로 재발견되면 processAlert 가 웜 필터 복원+TimeGate 1회 면제,
                            //   불충족 재발견은 processAlert 가·TTL 만료는 healthCheck prune 이 콜드 클리어 확정.
                            // [Phase 4 T1] 스냅샷 읽기는 purge 보다 반드시 먼저(deviceRssiMap 도 immediate 슬롯).
                            val lastRssi = deviceRssiMap[deviceId]
                            if (lastRssi != null) {
                                filterPreserveMap[deviceId] = AlertStateMachine.FilterPreserveState(lastRssi, android.os.SystemClock.elapsedRealtime())
                            }
                            uwbRanger?.onDeviceLost(deviceId)    // (v1.1.30) UWB 후보·세션 정리 — 맵 제거 앞(원본 순서 보존)
                            // [Phase 4 T1] 기기 상태 제거 단일 경로. cold=스냅샷 없음 → 필터(deferred)까지 즉시 콜드 클리어.
                            //   흡수 항목: alertState·ASM 상태맵 전체·BleService 5맵(dwell 3맵 = clearDwellMute 동치)·
                            //   uwb 3맵·echoDiffLive(누적 저장 후 정리). 등록은 ASM init 과 onCreate 참조.
                            asm.registry.purge(deviceId, cold = lastRssi == null)
                            sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                            if (alertState.isEmpty()) {
                                AlertSoundPlayer.stopSound()
                                VibrationHelper.stopVibration(this@BleService)
                                collapseOverlay()
                                activeSoundLevel = BleConstants.LEVEL_SAFE
                                // (v1.1.65) 존 안에서 마지막 기기가 빠지면 '경보 중지' 가 세이프존 표기를 덮어쓴다 — 분기.
                                sendStatusBroadcast(if (myZoneInside) "세이프존 — 경보 억제 중" else "기기 이탈 → 경보 중지")
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
                            // (v1.1.62 버그A) walker 게이트 미러 — 판정(onDeviceDetected)이 거른 보행자끼리
                            //   UWB 세션만 열리면 judgeUwbOnly 가 걸러진 기기를 되살린다. 세션 개설 자체를 차단.
                            if (myMode == "WALKER" && deviceId.startsWith(BleConstants.WALKER_PREFIX)
                                && !deviceId.contains("BEA_") && !DevSettings.walkerDetectsWalker) return
                            // [v1.1.43] 0x9ABC 관측 기록(진단용 — 판정 불사용) + 주소 전달 = 세션 (재)개설 경로
                            peerUwbSeenMap[deviceId] = System.currentTimeMillis()
                            uwbRanger?.onPeerUwbAddressReceived(deviceId, uwbAddress)
                        }
                        override fun onZoneBeaconSignal(beaconKey: String, rssi: Int, enterRssi: Int) {
                            // (v1.1.62) 존 비콘 신호 → 서비스 존 상태 머신으로 배선(인터페이스 디폴트=no-op라 명시 필수)
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

        // (v1.1.64 패치3-4) 주기 점검은 스캔 여부와 무관하게 돌려야 한다.
        //   기존에는 startScanHealthCheck() 가 if (doRx) 안에서만 호출돼, 송신 전용 기기는
        //   주기 점검 자체가 없었다 = 권한 회수·BT off 를 영원히 알아채지 못한다.
        //   러너블 첫 줄이 removeCallbacksAndMessages(null) 이라 이중 호출은 무해.
        startScanHealthCheck()
        checkSystemHealth()
        // (v1.1.64 패치3-3) startForeground 가 심어 둔 알림은 이 시점 상태를 모른다.
        //   checkSystemHealth 가 '변화 없음'으로 끝나도(예: 시작부터 정상) 한 번은 현재 상태로 다시 그린다.
        refreshNotification()
    }

    private fun processAlert(deviceId: String, rssi: Int, remoteState: Int = 0x00, remoteTurn: Int = BleConstants.TURN_STRAIGHT, payloadPresent: Boolean = false, peerEchoRssi: Int = BleConstants.NO_ECHO_RSSI, nowMs: () -> Long = { System.currentTimeMillis() }) =
        asm.processAlert(deviceId, rssi, remoteState, remoteTurn, payloadPresent, peerEchoRssi, nowMs)

    // [Phase 3 T2] 본문 소유는 UwbDistanceManager — 시그니처만 보존한다(UwbSessionGoldenTest 가
    //   ReflectionHelpers.callInstanceMethod 로 이 이름을 직접 호출하고, 내부 호출부도 그대로 둔다).
    private fun uwbJudgeModeExclusive(deviceId: String, now: Long): Boolean =
        uwbDist.uwbJudgeModeExclusive(deviceId, now)

    // [v1.1.41] UWB 실측 표본 즉시 드라이버 — UwbRanger.handleResult(메인 스레드)에서 직결 호출.
    //   판정 주기를 BLE 스캔 수신 품질에서 분리해 UWB 보고 주기(FREQUENT ~120ms)로 단축한다.
    //   Case A 페어만 여기서 판정하고, 그 외에는 표본 시각만 기록한다(Case A 신선도 근거).
    private fun onUwbSampleReceived(deviceId: String, distM: Float) {
        val now = System.currentTimeMillis()
        uwbSampleAtMsMap[deviceId] = now
        if (!uwbJudgeModeExclusive(deviceId, now)) return
        acquireDetectionWakeLock(0)   // 0(강한 값) — 화면 꺼짐이면 항상 획득: UWB 실측 자체가 근접 증거
        try {
            judgeUwbOnly(deviceId, distM, now)
            broadcastDeviceList()
            bleAdvertiser?.updateRisk(getCurrentMaxLevel())
        } finally {
            releaseDetectionWakeLock()
        }
    }

    // ── [v1.1.41] Case A 전용 판정 — UWB 실측 거리 단독 권위(RSSI 절대 불개입) ─────────────
    //   RSSI 파이프라인(median/EMA/칼만/워밍업/Time-Gate/streak/1초평균)을 전혀 쓰지 않는다.
    //   실측 거리와 역할쌍 반경(지게차쌍 15/8m · 그외 5/3m)만으로 레벨을 정하고, 발령·소리·
    //   표시·Firebase 는 processAlert canonical 레시피를 그대로 미러한다(Case A 에선 processAlert
    //   가 조기 반환하므로 이중 발령 없음). 격상=표본 1개 즉시(지연 최소), 격하=연속 3표본 확증
    //   또는 이탈 운동학(separatingStreak≥3·closing<0) + 히스테리시스(임계+0.5m 유지).
    private fun judgeUwbOnly(deviceId: String, uwbD: Float, now: Long) = asm.judgeUwbOnly(deviceId, uwbD, now)

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
    // [v1.1.47] 선획득 여유 마진 — WAKE_RSSI_DBM '도달 전' 약신호 구간부터 CPU 를 미리 잡아,
    //   임계를 넘는 경계 프레임이 Doze 상태에서도 첫 처리부터 완주하도록 한다(지연/누락 방어).
    //   비용은 마이크로락(500ms) 획득 빈도 증가뿐 — 스캔 듀티·배칭·경보 판정 임계는 불변.
    private val DETECTION_WAKE_MARGIN_DB = 10
    private val detectionWakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafeAlert:DetectionWakeLock")
            .apply { setReferenceCounted(false) }
    }

    // [v1.1.13] 화면 꺼짐 + 근접 수신 프레임에서만 CPU 를 짧게 확보 — 처리체인이 Doze 로
    //   끊기지 않게 한다. 화면 켜짐(사용자 인지 중)이거나 충분히 약한 신호면 잡지 않는다.
    //   [v1.1.47] WAKE_RSSI_DBM 보다 10dB 여유를 두고 선획득 — 임계 근방(WAKE-10 ≤ rssi < WAKE)
    //   프레임부터 미리 잡아 임계 돌파 순간 CPU 가 이미 깨어 있게 한다. setHazardNear(배칭 0ms
    //   승격)와 광고 웨이크 게이트는 기존 WAKE_RSSI_DBM 임계 그대로 — 이 마진은 wakelock 전용.
    private fun acquireDetectionWakeLock(rssi: Int) {
        if (isScreenOn || rssi < WAKE_RSSI_DBM - DETECTION_WAKE_MARGIN_DB) return
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
        // (v1.1.69) 전체 무음 = 목록만 접는다. 사이드바는 남아 공정 변경 진입점이 유지된다.
        //   isDeviceMuted 가 전체 무음을 보지 않으므로 updateFloatingOverlay() 로는 접히지 않는다.
        collapseOverlay()
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
    /** 사이드바 행을 터치한 운전자가 육안 확인 → 해당 기기 알림(사이렌·사이드바 행)을 30초간 중지. */
    private fun muteDevice(deviceId: String?) {
        if (deviceId.isNullOrEmpty()) return
        mutedDevices[deviceId] = System.currentTimeMillis() + ACK_MUTE_DURATION_MS
        // 현재 울리는 소리·진동 즉시 정지 (다른 위험 기기가 남아있으면 다음 스캔에서 재발령됨)
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        activeSoundLevel = BleConstants.LEVEL_SAFE
        // 이 기기를 제외한 최우선 기기로 플로팅 갱신(없으면 숨김)
        updateFloatingOverlay()
        sendStatusBroadcast("${extractDisplayName(deviceId)} 확인됨 — 30초 무음")
        Log.d(TAG, "기기 음소거(Acknowledge): $deviceId (30초)")
    }

    /**
     * (v1.1.65) 사이드바를 끝까지 드래그해 닫음 = 전체 뮤트.
     *   현재 경보 중(WARNING 이상)인 기기 전부에 ACK 뮤트를 일괄 적용한다. 새 상태를 만들지 않고
     *   기존 mutedDevices 맵을 재사용하므로 30초 자동 만료·기기 소실/SAFE 확정 시 정리가 그대로 적용된다.
     *   뮤트 이후 새로 진입하는 기기는 맵에 없으므로 정상 발령되고, TTC 선발령은 뮤트를 해제하고 재알림한다.
     */
    private fun muteAllHazards() {
        val until = System.currentTimeMillis() + ACK_MUTE_DURATION_MS
        val targets = alertState.entries
            .filter { it.value.first >= BleConstants.LEVEL_WARNING }
            .map { it.key }
        targets.forEach { mutedDevices[it] = until }
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        activeSoundLevel = BleConstants.LEVEL_SAFE
        updateFloatingOverlay()
        sendStatusBroadcast("전체 확인됨 (${targets.size}대) — 30초 무음")
        Log.d(TAG, "전체 음소거(Acknowledge): ${targets.size}대 (30초)")
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

    // ── (v1.1.61) 항목4: 레벨 체류(dwell) 자동 뮤트 ──────────────────────────────
    /** 매 판정 프레임 호출(alertState 등록 기기 한정 — RSSI canonical·UWB 미러·특수경보 공용).
     *  레벨 전이=타이머 재시작, 상승 전이(W→D)=DANGER 뮤트 해제(격상 발령 항상 가청 — 승인 예외),
     *  DWELL_MUTE_MS 연속 체류=해당 레벨 뮤트(DANGER 체류는 WARNING 도 함께 — D→W 후퇴 시 조용 유지).
     *  새로 뮤트되는 순간 재생 중인 소리·진동을 잔여 '가청' 최대레벨로 즉시 재정합한다
     *  (resyncSoundToRemaining — v1.1.61부터 뮤트 제외 산정이라 뮤트만 남으면 무음까지 하향). */
    private fun updateDwellMute(deviceId: String, level: Int, now: Long) {
        val prev = dwellLevelMap[deviceId]
        if (prev != level) {
            dwellLevelMap[deviceId] = level
            dwellSinceMap[deviceId] = now
            if (prev != null && level > prev) {
                // 상승 전이(W→D): DANGER 뮤트 해제 — 위험 격상은 뮤트 중에도 반드시 들린다(승인 예외).
                //   WARNING 뮤트는 유지(경고 존을 벗어난 적 없음 = 리셋 조건 미충족).
                dwellMutedLevelsMap[deviceId]?.remove(BleConstants.LEVEL_DANGER)
            }
            // 하강 후퇴(D→W): 집합 유지 — DANGER 체류로 WARNING 이 뮤트됐으면 후퇴 후에도 조용(승인 스펙).
            return
        }
        val since = dwellSinceMap.getOrPut(deviceId) { now }
        if (now - since < DWELL_MUTE_MS) return
        val set = dwellMutedLevelsMap.getOrPut(deviceId) { mutableSetOf() }
        var engaged = set.add(level)
        if (level == BleConstants.LEVEL_DANGER && set.add(BleConstants.LEVEL_WARNING)) engaged = true
        if (engaged) {
            resyncSoundToRemaining()   // 이 기기가 소리를 견인 중이었으면 잔여 가청 레벨로 즉시 하향(무음 포함)
            Log.d(TAG, "(v1.1.61) dwell 뮤트 발동: $deviceId level=$level (${DWELL_MUTE_MS}ms 체류) — 소리·진동만 억제")
        }
    }

    /** 해당 기기·레벨이 dwell 뮤트 중인지 — 소리·진동 억제 판정 전용(표시·목록·판정에는 불사용). */
    private fun isDwellMuted(deviceId: String, level: Int): Boolean =
        dwellMutedLevelsMap[deviceId]?.contains(level) == true

    /** 존 이탈(SAFE 정리·이탈 확정·미추적 강등·소실·중지) — dwell 추적·뮤트 전부 리셋 → 재진입=정상 발령. */
    private fun clearDwellMute(deviceId: String) {
        dwellLevelMap.remove(deviceId)
        dwellSinceMap.remove(deviceId)
        dwellMutedLevelsMap.remove(deviceId)
    }

    // ── (v1.1.62) 항목5: 존 비콘(안전구역) 상태 머신 ─────────────────────────
    //   진입 = enterRssi 이상 ZONE_MIN_SAMPLES 연속 표본, 이탈 = enterRssi-5dB 미만 즉시
    //   또는 신호 두절 GRACE 초과(reevaluateZones 폴링). 존 판정은 raw RSSI —
    //   전역 게인·rssiOffset 미적용(BleScanner 가 경보 파이프라인 진입 전에 원신호로 배선).

    /** 존 비콘 스캔 표본 1건 처리 — BleScanCallback.onZoneBeaconSignal 에서 배선. */
    private fun onZoneBeaconSignal(beaconKey: String, rssi: Int, enterRssi: Int) {
        zoneLastSeenMap[beaconKey] = System.currentTimeMillis()
        zoneEnterRssiMap[beaconKey] = enterRssi
        when {
            rssi >= enterRssi -> {
                val n = (zoneSampleMap[beaconKey] ?: 0) + 1
                zoneSampleMap[beaconKey] = n
                if (n >= ZONE_MIN_SAMPLES && zoneInsideMap[beaconKey] != true) {
                    zoneInsideMap[beaconKey] = true
                    Log.i(TAG, "(v1.1.62) 존 진입: $beaconKey rssi=$rssi (임계 $enterRssi, ${n}표본)")
                }
            }
            rssi < enterRssi - ZONE_EXIT_HYST_DB -> {
                zoneSampleMap[beaconKey] = 0
                if (zoneInsideMap[beaconKey] == true) {
                    zoneInsideMap[beaconKey] = false
                    Log.i(TAG, "(v1.1.62) 존 이탈(세기 미달): $beaconKey rssi=$rssi < ${enterRssi - ZONE_EXIT_HYST_DB}")
                }
            }
            else -> zoneSampleMap[beaconKey] = 0   // 데드밴드 — 상태 유지, 진입 연속성만 끊음
        }
        refreshMyZoneInside()
    }

    /** 평가주기 폴링 — 신호 두절 이탈(GRACE)·스테일 엔트리 폐기(STALE) + IN_ZONE 광고 self-heal. */
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
        bleAdvertiser?.updateInZone(myZoneInside)   // self-heal — 동일값이면 no-op
    }

    /**
     * 존 접촉 총괄 갱신 — (v1.1.65) 세이프존 '전면' 억제 전이.
     *   진입: 감지 중인 전 기기를 정상 소실 경로로 정리(판정·표시·오버레이·UWB 세션 일괄 해제).
     *         이후 onDeviceDetected 가 조기 리턴하므로 존 안에서는 존 비콘 신호만 처리된다.
     *         광고 위험레벨도 getCurrentMaxLevel 클램프로 SAFE 고정 — 보내지도, 받지도 않는다.
     *   이탈: detectedDevices 가 비어 있어 다음 광고부터 신규 기기처럼 깨끗하게 재개된다.
     *   ※ myZoneInside 를 먼저 세우고 정리에 들어가야 onDeviceLost 안의 상태 문구·
     *      getCurrentMaxLevel(SAFE) 이 존 기준으로 동작한다(순서 의존).
     */
    private fun refreshMyZoneInside() {
        val inside = zoneInsideMap.values.any { it }
        if (inside == myZoneInside) return
        myZoneInside = inside
        if (inside) {
            bleScanner?.forceLoseAll()          // 전 기기 정상 소실 — 27종 상태맵·필터·UWB 정리
            AlertSoundPlayer.stopSound()        // 잔존 사이렌 즉시 정지(이중 안전)
            VibrationHelper.stopVibration(this)
            collapseOverlay()                   // (v1.1.69) 목록은 접는다(사이드바 자체는 유지)
            activeSoundLevel = BleConstants.LEVEL_SAFE
        } else {
            updateFloatingOverlay()             // 이탈 — 오버레이 상태 재동기
        }
        resyncSoundToRemaining()   // getAudibleMaxLevel 이 존 안=SAFE 반환 → 진입=능동 정지, 이탈=즉시 복원
        bleAdvertiser?.updateInZone(inside)
        bleAdvertiser?.updateRisk(getCurrentMaxLevel())   // 진입=SAFE 송출, 이탈=실제 레벨 복귀
        broadcastDeviceList(force = true)
        sendStatusBroadcast(if (inside) "세이프존 — 경보 억제 중(존 비콘 접촉)" else "존 이탈 — 경보 복원")
        refreshNotification()
        Log.i(TAG, "(v1.1.65) myZoneInside=$inside (세이프존 전면 억제)")
    }

    /**
     * (v1.1.65) 사이드바에 표시할 위험 기기 전체 목록.
     *   경보 중(WARNING 이상)이며 Acknowledge 무음이 아닌 기기를 위험도 → RSSI(가까운 순)으로 정렬한다.
     *   기존 topPriorityDevice() 의 정렬 기준은 그대로 두고 1대 제한만 없앴다.
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
                // (v1.1.31) 거리 문자열(빈값=기존 dBm 폴백) — 목록과 동일 표기 규칙.
                //   (v1.1.46) 신선한 실측만 ·UWB 표기(freshUwbDistM) — 죽은 숫자를 실측으로 오인하지 않게.
                OverlayManager.HazardItem(
                    deviceId = id,
                    name     = suddenLabelMap[id] ?: makeApproachLabel(id),
                    rssi     = rssi,
                    danger   = e.value.first >= BleConstants.LEVEL_DANGER,
                    distText = UwbCalibrator.distanceTextFor(uwbPairKeyFor(id), rssi, uwbDist.freshUwbDistM(id))
                )
            }

    /**
     * (v1.1.69) 위험 기기 전체를 사이드바에 표시. 대상이 0대면 걷는 것이 아니라 '접힘' 으로 간다
     *   (헤더만 남아 현재 공정명 + [공정 변경] 배지를 띄운다). 사이드바 자체는 감시 중 늘 떠 있다.
     */
    private fun updateFloatingOverlay() {
        OverlayManager.showSidebar(this, hazardListForOverlay(), categoryRoleName(myCategory))
    }

    /**
     * (v1.1.69) 사이드바를 접힘 상태로 되돌린다 — 철거가 아니다.
     *   hazardListForOverlay() 는 전체 무음(isMuted)·세이프존을 반영하지 않는다. 그 경로에서
     *   updateFloatingOverlay() 를 부르면 목록이 그대로 남아 접히지 않으므로, 빈 목록을 명시한다.
     */
    private fun collapseOverlay() {
        OverlayManager.showSidebar(this, emptyList(), categoryRoleName(myCategory))
    }

    private fun startScanHealthCheck() {
        healthCheckHandler.removeCallbacksAndMessages(null)
        healthCheckHandler.postDelayed(object : Runnable {
            override fun run() {
                // [v1.1.58 fix4] 보존 스냅샷 TTL 만료 prune(15s 주기) — 만료 기기는 콜드 클리어로 확정
                if (filterPreserveMap.isNotEmpty()) {
                    val nowEl = android.os.SystemClock.elapsedRealtime()
                    filterPreserveMap.filterValues { nowEl - it.atMs > KF_VEL_SEED_TTL_MS }.keys.toList().forEach { id ->
                        filterPreserveMap.remove(id)
                        asm.registry.purgeDeferred(id)   // [Phase 4 T1] 필터 3종+칼만 콜드 클리어(deferred 슬롯)
                        timeGateWaiveSet.remove(id)   // [Phase 4 T1] immediate 소속 — deferred purge 에 없으므로 명시 유지
                    }
                }
                val elapsed = System.currentTimeMillis() - lastScanResultMs
                // (v1.1.64 패치3-4) bleScanner 가드 — 송신 전용 구성에서도 이 루프가 돌게 되면서
                //   스캐너가 없는 기기가 15초마다 "결과 없음" 경고를 찍는 로그 스팸이 생긴다.
                if (bleScanner != null && elapsed > SCAN_HEALTH_CHECK_MS) {
                    // [v1.0.46 #9] stopBle()+applyMode() 전체 재시작은 TX 광고까지 끊어 상대 기기에서
                    //   내가 사라지는 가시성 갭을 냈다(주변 무기기 정상 상황에서도 15초마다 반복).
                    //   수신(RX) 스캐너만 재시작 — 송신(TX)은 무중단. 상태 브로드캐스트 스팸도 제거.
                    Log.w(TAG, "스캔 헬스체크: ${elapsed / 1000}초간 결과 없음 → RX 스캔 재시작")
                    bleScanner?.restartScan()
                    lastScanResultMs = System.currentTimeMillis()
                }
                // (v1.1.64 패치3-4) 권한 회수는 콜백이 없다 — 주기적으로 직접 확인하는 수밖에 없다.
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
            val dist  = UwbCalibrator.distanceTextFor(uwbPairKeyFor(id), rssi, uwbDist.freshUwbDistM(id))
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
                val dist = UwbCalibrator.distanceTextFor(uwbPairKeyFor(id), rssi, uwbDist.freshUwbDistM(id))
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
            bleAdvertiser?.requestHazardAdv()   // [v1.1.58 fix3] 버스트 성패·burstEnabled 와 무관한 광고 승격(≤250ms) 5s 홀드
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
            },
            // [v1.1.41] 실측 표본 즉시 콜백 — Case A(배타 판정) 드라이버. 판정 주기를 BLE 스캔에서
            //   분리해 UWB 보고 주기(FREQUENT ~120ms)로 단축한다(수신 즉시 판정·버퍼 대기 없음).
            onUwbSample = { id, dist -> onUwbSampleReceived(id, dist) }
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
        // [Phase 4 T1] 등록 슬롯(immediate+deferred+teardown) 일괄 정리 단일 경로.
        //   broadcastDeviceList(force=true) 가 pendingDisplayMap 을 읽어 보류 기기를 SAFE 행으로 병합하므로
        //   clearAll 은 반드시 broadcast 뒤에 온다. 위 7개 clear 는 원본 순서 그대로 유지(멱등).
        CalibrationEngine.persistEchoAll(myId)                 // [v1.1.54] 에코편차 누적 저장(중지 시 유실 방지) — clear 보다 먼저
        asm.registry.clearAll()
        zoneSampleMap.clear(); zoneEnterRssiMap.clear(); zoneLastSeenMap.clear()    // (v1.1.62) 존 상태 일괄 정리 — 미등록
        zoneInsideMap.clear(); myZoneInside = false                                 // peerInZoneMap 은 레지스트리가 정리
        testRunnable?.let { testHandler.removeCallbacks(it) }
        testRunnable = null
        muteHandler.removeCallbacksAndMessages(null)
        volumeGuardHandler.removeCallbacksAndMessages(null)   // [v1.0.46 #11] 볼륨가드 해제 예약 정리
        ignoringVolumeChange = false
        isMuted = false
        isMutedPublic = false
        healthCheckHandler.removeCallbacksAndMessages(null)
        advPowerHandler.removeCallbacksAndMessages(null)   // [v1.0.42 Req3] 송출 전력 평가 루프 중지
        try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(volumeReceiver)  } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver)  } catch (_: Exception) {}
        try { unregisterReceiver(locationReceiver) } catch (_: Exception) {}   // (v1.1.64 패치3-4)
        // (v1.1.64 패치3-3) 싱글턴 object 가 서비스 인스턴스를 캡처한 람다를 계속 붙들면
        //   서비스가 GC 되지 않는다 → 반드시 끊고, 이상 상태도 초기화한다.
        AlertSoundPlayer.onSoundFault = null
        OverlayManager.onOverlayFault = null
        OverlayManager.onHeaderTap    = null
        systemFault  = null
        txFault      = null
        soundFault   = null
        overlayFault = null
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
        DeviceStateRegistry.live = null   // [Phase 4 T2] 계기 라이브 참조 해제(서비스 누수 방지)
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

    // (v1.1.67) 알림 액션 라벨용 전환 대상 — MainActivity.switchTargetLabel() 과 같은 매핑이다.
    //   WALKER→지게차 / DEVICE(레거시 EPJ 포함)→보행자.
    private fun switchTargetName(): String = if (myMode == "WALKER") "지게차" else "보행자"

    private fun buildNotification(title: String, subText: String): android.app.Notification {
        // (v1.1.68) 본문 탭 = 무음. 액션 버튼이 아니라 알림 본문(제목·내용) 전체가 대상이다.
        //   경보가 울리는 순간 손이 가는 곳은 가장 넓은 면이고, 버튼 두 개를 조준할 여유가 없다.
        //   ongoing 알림이라 탭해도 알림은 남는다 — 무음만 걸리고 감시 표시는 유지된다.
        val mutePi = android.app.PendingIntent.getService(
            this, 0,
            Intent(this, BleService::class.java).apply { action = ACTION_MUTE_TEMP },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // (v1.1.68) 앱 열기 액션 — 본문 탭을 무음에 내주면서 앱 진입 경로가 사라졌다.
        //   액션 없이 MainActivity 만 띄운다(handleSwitchRoleIntent 는 액션 불일치로 무시).
        //   requestCode 는 3종이 서로 달라야 한다. FLAG_UPDATE_CURRENT 라 같으면 덮어써진다.
        val openPi = android.app.PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // (v1.1.67) 작업 전환 액션 — 사이드바는 위험 0대면 사라지고(OverlayManager.showSidebar),
        //   역할 전환 버튼은 activity_main 안에만 있어 평상시 공정 변경 경로가 없었다. 상시 알림은
        //   감시 중 항상 떠 있으므로 여기가 유일하게 안정적인 진입점이다.
        //   즉시 전환이 아니라 확인 다이얼로그를 거치게 한다 — 주머니 속 오탭이 곧 감시 공백이다.
        //   CLEAR_TOP|SINGLE_TOP = 기존 인스턴스 재사용(onNewIntent). 중복 생성 방지.
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

    // ── (v1.1.64 패치3-3) 상시 알림 = 보호 상태의 유일한 진실 ────────────────
    //   소스 전역에 NotificationManager.notify 호출이 하나도 없어, 알림 내용은
    //   startForeground 시점에 고정된 뒤 절대 갱신되지 않았다.
    //   이제 이상 사유가 생기거나 사라질 때마다 같은 NOTIF_ID 로 갱신한다.

    /** 현재 살아 있는 이상 사유를 한 줄로 합친다. 모두 정상이면 null. */
    private fun faultSummary(): String? =
        listOfNotNull(systemFault, txFault, soundFault, overlayFault)
            .joinToString(" · ")
            .ifEmpty { null }

    /**
     * 상시 알림을 현재 상태로 다시 그린다.
     * 이상 진입 시 1회 경고음을 울려, 알림을 보지 않는 사용자도 인지하게 한다.
     * (재진입 안전: faultBeeped 를 playWarning 앞에서 세워 두므로,
     *  경보음 생성 실패 → onSoundFault → 이 함수 재진입 시 경고음은 건너뛰고 끝난다.)
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
            // (v1.1.65) 세이프존 명시 — 이상(fault) 다음 우선순위.
            //   보호가 끊긴 사실이 '안전구역' 표기에 가려지면 안 되므로 fault 를 앞에 둔다.
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
            }
        } else {
            faultBeeped = false
        }
    }

    // ── (v1.1.64 패치3-4) BT·권한·위치 이상 감시 ──────────────────────────
    //   기존에는 BT 상태 변화를 브로드캐스트로만 알려 화면을 열어 둔 사람만 볼 수 있었고,
    //   런타임 권한 회수·위치 기능 off 는 아예 감시 대상이 아니었다.
    //   권한이 회수되면 스캔은 조용히 결과 0건이 되고, 앱은 계속 '정상'으로 보인다.

    private fun setSystemFault(reason: String?) {
        if (systemFault == reason) return
        systemFault = reason
        if (reason != null) Log.w(TAG, "시스템 이상: $reason") else Log.i(TAG, "시스템 이상 해소")
        refreshNotification()
    }

    /** BT 어댑터·런타임 권한·위치 기능을 한 번에 재평가한다. 이상이면 상시 알림으로 승격. */
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
            // API 30 이하에서만 BLE 스캔에 위치 권한·위치 기능이 필요하다.
            // API 31+ 는 BLUETOOTH_SCAN 에 neverForLocation 선언(AndroidManifest:7-8) → 해당 없음.
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                setSystemFault("위치 권한 꺼짐 — 감지 중단"); return
            }
            if (!isLocationEnabled()) {
                setSystemFault("위치 기능 꺼짐 — 감지 중단"); return
            }
        }

        setSystemFault(null)
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    /** 확인 자체가 실패하면 정상으로 간주한다 — 없는 이상을 알리는 오탐이 더 나쁘다. */
    private fun isLocationEnabled(): Boolean = runCatching {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
             lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(true)
}
