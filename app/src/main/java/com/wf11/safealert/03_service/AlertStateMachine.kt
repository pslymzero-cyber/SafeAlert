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

/**
 * 경보 상태 기계 - processAlert / judgeUwbOnly 판정 경로 (Phase 3 T3, REFACTOR-01).
 * 판정 로직은 BleService 에서 그대로 이동했다. 부작용/조회는 Effects 포트로 위임한다.
 */
class AlertStateMachine(
    private val fx: Effects,
    private val uwbDist: UwbDistanceManager,
) {

    /** BleService 가 소유한 부작용/조회 표면. 판정 로직에서 쓰는 것만 노출한다. */
    interface Effects {
        val myId: String
        val myMode: String
        val myCategory: Int
        val isMuted: Boolean
        val myZoneInside: Boolean
        var activeSoundLevel: Int
        var lastApproachAtMs: Long
        val bleScanner: BleScanner?
        val uwbRanger: UwbRanger?
        val rssiPreFilter: RssiPreFilter
        val medianFilter: MedianFilter
        val pEmaFilter: RssiPreFilter
        fun getAudibleMaxLevel(): Int
        fun uwbPairKeyFor(deviceId: String): String
        fun resyncSoundToRemaining()
        fun forceAlarmVolume()
        fun isDeviceMuted(deviceId: String): Boolean
        fun updateDwellMute(deviceId: String, level: Int, now: Long)
        fun isDwellMuted(deviceId: String, level: Int): Boolean
        fun clearDwellMute(deviceId: String)
        fun updateFloatingOverlay()
        fun collapseOverlay()
        fun sendStatusBroadcast(status: String)
        fun extractDisplayName(deviceId: String): String
        fun makeStateLabel(name: String, category: Int, state: Int): String
        fun sendAlertBroadcast(deviceId: String, level: Int)
        fun broadcastDeviceList()
        fun oneSecAvgRssi(deviceId: String, rssi: Int): Int
        fun recentPeakRssi(deviceId: String, windowMs: Long = 500L): Int?
        fun vibrateDanger()
        fun vibrateWarning()
        fun vibrateRapidApproach()
        fun stopVibration()
        fun playDanger()
        fun playWarning()
    }

    private val TAG = "AlertStateMachine"

    /** UwbDistanceManager 소유 맵의 별칭(동일 인스턴스). */
    private val uwbSafeStreakMap = uwbDist.uwbSafeStreakMap

    internal val alertState = mutableMapOf<String, Pair<Int, Long>>()

    // [판정 파라미터] 쿨다운 — DevSettings 라이브 읽기(기본값=기존 하드코딩값, timeGateMs 선례)
    internal val WARNING_COOLDOWN_MS: Long get() = DevSettings.warningCooldownMs

    internal val DANGER_COOLDOWN_MS:  Long get() = DevSettings.dangerCooldownMs

    // ── 2D 칼만 필터 맵 (v1.0.20: KalmanFilter, 거리+속도 동시 추적) ──
    internal val kalmanFilters = mutableMapOf<String, KalmanFilter>()

    // (v1.1.56 U3) 정리 직전 칼만 추정속도 스냅샷 — SAFE/이탈정리/하드게이트가 칼만을 지우기 직전
    //   estimatedVel 을 보관했다가 재등록 injectWarmup(initVel) 재시드에 1회성 소비(음수만, -1.5 캡).
    // (v1.1.57) TTL — 캡처 시각(elapsedRealtime, 재부팅·시계변경 내성)을 함께 보관, 소비 시 나이가
    //   TTL 을 넘으면 폐기(0.0 재출발). 정상 소실(onDeviceLost)·stopAll 은 스냅샷을 즉시 지우므로
    //   낡은 시드는 onDeviceLost 를 우회하는 경로(하드게이트 보존·UWB 소실유예·스캔 스로틀 창)에서만
    //   생김 — 그 잔존을 시간 상한으로 차단한다(시뮬: 연속 수신 중 나이 1프레임이라 기존 동작 완전 동일).
    internal data class LastKfVelState(val velocity: Double, val timestamp: Long)

    internal val lastKfVelMap = mutableMapOf<String, LastKfVelState>()

    internal val KF_VEL_SEED_TTL_MS = 30_000L   // (v1.1.57) 재시드 스냅샷 유효기간(시뮬상 30s/60s 실효차 +0.3s뿐)

    // ── [v1.1.58 fix4] lost 시 필터 상태 defer-clear 보존 ──────────────────────
    //   onDeviceLost 에서 필터를 즉시 지우지 않고 마지막 RSSI 스냅샷만 남긴다.
    //   30s(KF_VEL_SEED_TTL_MS) 내 ±10dB(FILTER_PRESERVE_BAND_DB) 밴드로 재발견되면
    //   웜 필터 그대로 재사용+TimeGate 1회 면제(재발견 즉시 경보 가능) — 플래핑 소실 -87% (시뮬).
    //   조건 불충족이면 그 자리서 콜드 클리어(기존 lost 경로와 동일 결과).
    internal data class FilterPreserveState(val refRssi: Int, val atMs: Long)

    internal val filterPreserveMap = mutableMapOf<String, FilterPreserveState>()

    internal val timeGateWaiveSet  = mutableSetOf<String>()

    // ── (v1.1.40) 섀도우 IMU 융합 — 정지 관측자 전용 병렬 추적기 ─────────────────
    // 메인 파이프라인(EMA→칼만→P-EMA)과 완전 분리된 '섀도우 칼만'을 median 스트림에만 물려,
    // 내 IMU 정지(관측 플랫폼 안정) + 상대 FORWARD 자기신고일 때만 공정잡음을 열어(0.15)
    // 접근을 병렬 추적한다. 산출물 2개뿐 — ① DANGER 프레임의 EMA 하강 알파 부스트(0.4, 해제
    // 가속) ② 메인 TTC 후보 불성립 시 예비 TTC 후보. 메인 칼만/레벨/streak/게이트/래치는 무접촉.
    // 킬스위치 DevSettings.imuShadowFusionEnabled=false 또는 페이로드 부재 시 전 경로 우회(기존 동일).
    internal data class ShadowFusion(
        val kf: KalmanFilter = KalmanFilter(DevSettings.kalmanPreset),
        var apprStreak: Int = 0,          // 공정잡음 열린 상태의 연속 접근 프레임(TTC 예비 후보 자격)
        var departFrames: Int = 0,        // 연속 이탈 프레임(>=2 → tracking)
        var tracking: Boolean = false,    // 이탈 추적 중(부스트 자격의 전제)
        var relLatch: Boolean = false,    // DANGER 이탈 래치(레벨 SAFE 해제까지 부스트 유지)
        var lastEffWarning: Int = Int.MIN_VALUE,   // 직전 프레임 effWarning 캐시(첫 프레임은 rssiWarning 폴백)
    )

    internal val shadowFusionMap = mutableMapOf<String, ShadowFusion>()

    internal val SHADOW_CROSS_Q_MILD       = 0.15   // 정지+상대 FORWARD+경고권: 공정잡음 완만 개방

    internal val SHADOW_Q_FREEZE           = 0.01   // 그 외: 사실상 동결(잡음 학습 차단)

    internal val SHADOW_DEPART_REENTER_VEL = 1.5    // 재접근 판정 속도(dBm/s) — 이탈 추적 해제

    internal val SHADOW_LIVE_VEL_DBM       = -1.0   // '실제로 멀어지는 중' 판정 속도(직전 프레임)

    // ── [v1.0.45] 돌진 시 칼만 FAST 조건부 승격 ─────────────────────────
    // prevVel(직전 칼만 속도) > RUSH_FAST_VEL_DBM 이 '연속 RUSH_FAST_MIN_FRAMES 프레임' 지속되거나,
    // IMU 실가속(adaptiveQFactor ≥ RUSH_FAST_IMU_QFACTOR)이 동반될 때만 NORMAL→FAST 로 승격한다.
    //   ★ 가드레일: 단발 임펄스는 1프레임만 가짜속도를 만들므로(연속 2프레임 불충족) FAST 를 못 켠다
    //     → Median 의 임펄스 제거를 되돌리지 못한다. 돌진 종료 시 사용자 프리셋으로 자동 환원.
    internal val rushFrameMap = mutableMapOf<String, Int>()

    internal val RUSH_FAST_VEL_DBM     = 2.0   // 돌진 후보 프레임 판정: prevVel 이 이 값 초과

    internal val RUSH_FAST_MIN_FRAMES  = 2     // 연속 N프레임 지속 시에만 FAST 승격(임펄스 차단)

    internal val RUSH_FAST_IMU_QFACTOR = 2.0   // IMU 실가속 동반: adaptiveQFactor 이 이 값 이상이면 즉시 허용

    // ── [v1.1.16 D] 첫 접촉 DANGER 고속 발령(2프레임 확증) ────────────────────
    //   비콘은 페이로드가 없어 워밍업(Median 미충전, 약 1프레임) 중 신규 DANGER 진입이 발령 보류된다.
    //   raw(칼만·1초평균)가 2연속 프레임 위험권이면(단발 임펄스 차단) 워밍업·접근속도 게이트를 우회해
    //   즉시 1회 발령을 허용한다 → '가까이 두면 늦게/안 울림'을 근접 즉시 발령으로 전환.
    internal val dangerContactStreakMap = mutableMapOf<String, Int>()

    // [v1.1.18] WARNING 거리도 동일한 raw 2프레임 확증 카운터 — 정지 근접도 Time-Gate·워밍업 우회하고 즉시 발령.
    //   effDanger ⊂ effWarning 이라 DANGER 거리도 자동 포함(fastDangerContact 상위호환). 단발 임펄스는 streak 1 에서 끊김.
    internal val warningContactStreakMap = mutableMapOf<String, Int>()

    // [v1.1.71 D-3B BUG-02] WARNING streak 미달 시 '단발 잡음 vs 진짜 이탈' 판단용 직전 medianValue/시각.
    //   변화율(dBm/s)로 판단 — release_goldenTimeline(120ms·-1dBm/프레임≈-8.3dBm/s)은 임계보다
    //   훨씬 가팔라 원래대로 즉시 리셋, 저속 잡음 접근(1000ms 간격 최대 ±1dBm/s)은 임계 미만이라
    //   streak 를 보존한다(실측 근거는 WARNING_DEPART_RATE_DBM_PER_SEC 선언부 참고).
    internal val warningMissRefMap = mutableMapOf<String, Pair<Int, Long>>()

    // ── TTC 파라미터 ──────────────────────────────────────────────────
    // [v1.0.25 Req2] 현장 초민감 오발령 해결 — 8.0초 → 3.0초로 대폭 강화 (충돌 임박 시에만 선발령)
    // [판정 파라미터] DevSettings 라이브 읽기(기본 3.0/0.5 = 기존값)
    internal val TTC_THRESHOLD_SEC: Double get() = DevSettings.ttcThresholdSec

    // ★ RSSI 공간 부호 규칙: vel > 0 = RSSI 증가 = 접근 / vel < 0 = RSSI 감소 = 이탈
    internal val MIN_APPROACH_VEL_DBM: Double get() = DevSettings.minApproachVelDbm  // TTC 계산 최소 접근 속도 (dBm/s)

    // ── 기기별 추적 상태 머신 (v1.0.20) ──────────────────────────────
    enum class TrackingState { APPROACHING, CROSSING, DEPARTING }

    internal val trackingStateMap   = mutableMapOf<String, TrackingState>()

    internal val crossingStartMap   = mutableMapOf<String, Long>()    // CROSSING 진입 시각

    internal val departingStartMap  = mutableMapOf<String, Long>()    // DEPARTING 진입 시각

    // 상태 전환 파라미터 (RSSI 공간 기준)
    internal val CPA_VEL_THRESHOLD             = 0.5   // CPA 판정 속도 임계 (dBm/s)

    // [v1.1.71 D-3B BUG-02] WARNING streak 미달 시 '단발 잡음 vs 진짜 이탈' 판단 임계(dBm/s, 하강).
    //   release_goldenTimeline 실측 하강률(120ms 간격 -1dBm/프레임 ≈ -8.3dBm/s)과 LowSpeedApproach
    //   RegressionTest 실측 잡음 최대 하강률(1000ms 간격 median-of-3 통과 후 최대 -1dBm/프레임 =
    //   -1.0dBm/s) 사이, 여유 3배 이상 지점인 3.0 채택 — release 는 임계 초과라 원래대로 즉시 리셋
    //   (골든 무변화), 저속 잡음은 임계 미만이라 streak 보존.
    internal val WARNING_DEPART_RATE_DBM_PER_SEC = 3.0

    internal val CROSSING_CONFIRM_MS           = 1500L // CROSSING → DEPARTING 확정 대기

    internal val DEPARTING_REENTRY_COOLDOWN_MS = 5000L // DEPARTING 후 재진입 최소 대기

    // [판정 파라미터] DevSettings 라이브 읽기(기본 8 = 기존값)
    internal val DEPARTING_HYSTERESIS_DBM: Int get() = DevSettings.departingHysteresisDbm // DEPARTING 중 재경보 추가 마진 (dBm)

    internal val recedingStartMap = mutableMapOf<String, Long>()

    // [판정 파라미터] 페이드아웃 해제 — DevSettings 라이브 읽기(기본 1500L/4, v1.1.14 교행후 잔존 단축)
    internal val RECEDING_CLEAR_MS: Long get() = DevSettings.recedingClearMs

    // [v1.1.6] 이탈 판정 재설계 — raw 절대최대 피크는 초근접 BLE 노이즈(±5~10dBm)에 고착돼
    //   '위험 시 가짜 이탈 → 소리 꺼짐'(v1.1.5 회귀)을 유발했다. 중간평활 EMA 레퍼런스(recedeRefMap)
    //   로 노이즈를 흡수하고, 피크는 정체 시 ref 로 느리게 감쇠(recedePeakMap)시켜 가짜 이탈을 자동 해소.
    internal val recedeRefMap   = mutableMapOf<String, Double>()  // 이탈 판정 전용 중간평활(EMA)

    internal val recedePeakMap  = mutableMapOf<String, Double>()  // 피크 홀드 + 느린 감쇠

    internal val RECEDE_REF_ALPHA = 0.3   // avg1sec → 중간평활 EMA 계수(초근접 노이즈 흡수)

    internal val PEAK_DECAY_ALPHA = 0.05  // 피크 정체 시 ref 로 수렴하는 감쇠 계수(가짜 이탈 자동 해소)

    internal val RECEDING_DBM_DROP: Int get() = DevSettings.recedingDbmDrop

    // 기기별 마지막 avgRssi 보관 — 플로팅 위젯 최우선 기기 선정·정렬에 사용
    internal val deviceRssiMap     = mutableMapOf<String, Int>()

    // [v1.0.25 Req4] 기기별 음소거(Acknowledge) — deviceId → 음소거 해제 시각(ms). 플로팅 터치 시 등록.
    internal val mutedDevices      = mutableMapOf<String, Long>()

    internal val peerInZoneMap    = mutableMapOf<String, Boolean>() // deviceId → 상대 IN_ZONE 선언

    // [v1.0.29 다이나믹 페이로드] 0x02(급정거/급회전) 특수경보 기기의 표시문자열 덮어쓰기 맵.
    //   값 = "{이름}이(가) 급정거 또는 급회전 중입니다." → 오버레이/목록에서 일반 이름 대신 출력.
    internal val suddenLabelMap    = mutableMapOf<String, String>()

    // [v1.0.34] 수신한 상대 기기의 Category(역할) 캐시 — 디코드된 CAT_* 보관.
    //   접두어(prefix)만으론 EPJ(01)·지게차(10)가 둘 다 DEVICE 라 구분 불가하므로,
    //   1바이트 페이로드에서 언패킹한 Category 로 표시 라벨(보행자/EPJ/지게차)을 판별한다.
    internal val deviceCategoryMap = mutableMapOf<String, Int>()

    // [v1.0.44] 수신한 상대 기기의 State(동적 상태) 캐시 — 디코드된 PSTATE_* 보관.
    //   평상 표시문구를 '정지 중(IDLE)=주변 대기' / '이동 중(FORWARD)=접근'으로 분기하는 데 쓴다.
    //   ※ 후진/하역(특수경보)은 suddenLabelMap(makeStateLabel)이 우선하므로 이 캐시에 의존하지 않는다.
    internal val deviceStateMap    = mutableMapOf<String, Int>()

    // [v1.1.7 #1] 수신한 상대 기기의 회전 방향(TURN_*, bits 3:2 디코드) 캐시.
    //   (구 deviceSpeedMap: 속도 4비트 → v1.1.7 에서 회전 2비트로 재패킹. 표시 라벨/디버그용.)
    internal val deviceTurnMap     = mutableMapOf<String, Int>()

    // [v1.1.7 #2] 후진(전진) 대비 — RX 측 RSSI 추세 반전 추론용 상태.
    //   reverseRssiHist: 기기별 (시각ms, avg1sec) 표본 윈도우. '안정/약화 → 급강세' 패턴 탐지.
    //   reversePrepUntil: 감지 시 now+holdMs 로 latch — 그 시각까지 "후진(전진)을 대비해주세요" 표시.
    internal val reverseRssiHist   = mutableMapOf<String, ArrayDeque<Pair<Long, Int>>>()

    internal val reversePrepUntil  = mutableMapOf<String, Long>()

    // [v1.0.30 Req3] Firebase 경보 저장 모바일데이터 방어 — 기기별 마지막 저장 시각(ms).
    //   같은 기기에 대해 FIREBASE_SAVE_THROTTLE_MS(1분) 안에는 재업로드하지 않는다.
    internal val firebaseLastSaveMap = mutableMapOf<String, Long>()

    // [판정 파라미터] DevSettings 라이브 읽기(기본 60_000L/5 = 기존값)
    internal val FIREBASE_SAVE_THROTTLE_MS: Long get() = DevSettings.firebaseThrottleMs

    internal val HYSTERESIS_DBM: Int get() = DevSettings.hysteresisDbm

    // ── [v1.0.35 민감도 지연(Time-Gate)] + [v1.0.36 코너링 연장 · 충돌 기하학 필터] ──────────
    // Time-Gate: 위험권 진입 후에도 2D 칼만 미분(kfVel, dBm/s)이 APPROACH_TIMEGATE_VEL_DBM 이상
    //   '가까워짐'을 APPROACH_TIMEGATE_MS(0.5초) 연속 유지할 때만 신규/격상 경보를 발령한다.
    //   → 전파 튐(single-frame spike)으로 인한 즉각 오알람을 차단. 쿨다운 재알람·0x02 특수경보·
    //     TTC 선발령에는 적용하지 않는다(끊김 방지/즉각 안전 — 각 경로가 위에서 먼저 return).
    // [v1.0.42 Req5] Time-Gate 지연 시간 — DevSettings 에서 라이브로 읽는다(앱 재시작 없이 반영,
    //   기본 500L=기존값 그대로). 게이트 판정 로직(아래 processAlert)은 일절 손대지 않고 '값의 출처'만
    //   상수→설정으로 옮긴다 → 칼만/3중 하드게이트/기하학 판정 보존.
    internal val APPROACH_TIMEGATE_MS: Long get() = DevSettings.timeGateMs   // 신규/격상 경보 전 최소 연속 접근 시간(평상)

    internal val APPROACH_TIMEGATE_VEL_DBM: Double get() = DevSettings.timeGateVelDbm  // '가까워짐' 판정 최소 접근속도(dBm/s)

    // [v1.0.36] 코너링 중 Time-Gate 연장 — 내 장비가 급회전 중이면 전파가 일시 출렁이므로
    //   오작동 방지를 위해 0.5초 → 1.0초로 일시 연장한다(ImuFusion.isCornering 으로 판정).
    internal val APPROACH_TIMEGATE_CORNERING_MS: Long get() = DevSettings.corneringTimeGateMs

    // [v1.0.36] 충돌 기하학 필터(Collision Geometry) 파라미터.
    //   합산 접근속도(내속도+상대속도, km/h)를 RSSI 변화율(dBm/s)로 환산해 실제 kfVel 과 대조한다.
    //   단위 환산계수는 위험권(~6m)·경로손실지수(n≈2.5) 근사 — 현장 튜닝 대상.
    //   [판정 파라미터] 환산계수·접근비 2종 — DevSettings 라이브 읽기(기본 0.5/0.6/0.3 = 기존값)
    internal val CLOSING_KMH_TO_DBMS: Double get() = DevSettings.closingKmhToDbms  // 합산속도(km/h) → 예상 접근(dBm/s) 환산계수

    internal val COLLISION_MIN_CLOSING_KMH  = 1.0   // 합산속도 이 미만이면 기하 판정 불가(보류 안 함)

    internal val COLLISION_HEAD_ON_RATIO: Double get() = DevSettings.collisionHeadOnRatio  // 실제/예상 접근비 이상 → 정면충돌(Time-Gate 즉시통과)

    internal val COLLISION_SIDE_RATIO:    Double get() = DevSettings.collisionSideRatio    // 실제/예상 접근비 이하 → 측면/나란히(보류 후보)

    internal val COLLISION_ABS_SAFE_VEL_DBM = 2.0   // 이 이상 빠른 접근이면 측면판정 무시(false negative 방지)

    // [v1.1.21] 빠른 정면접근 Time-Gate 즉시통과 임계(dBm/s) — DevSettings 라이브 읽기(기본 2.0).
    //   headOnCourse(합산 km/h 미산출로 영구 false)를 칼만 접근속도로 대체하는 경로의 임계.
    internal val FAST_APPROACH_BYPASS_VEL_DBM: Double get() = DevSettings.fastApproachBypassVelDbm

    // ── [v1.0.49 A/B 신규 기기 경보 지연 수정] ──────────────────────────────────────
    // #1 콜드 칼만 기하학 유예: 칼만 update 횟수가 이 값 미만이면 vel 이 아직 초기값(0.0) 부근이라
    //    closingRatio≈0 → sideCourse(측면) 오판정으로 돌진 기기를 보류시킨다. 워밍업 동안은
    //    측면판정만 무효화 — headOn 즉시통과·Time-Gate 는 그대로 둔다(보수 방향 유지).
    internal val KALMAN_GEOMETRY_MIN_UPDATES = 5

    // #2 경고권 밖 필터 보존 밴드: 게이트(rssiWarning) 미달이라도 이 폭(dB) 안이면 필터 상태
    //    (Median·EMA·칼만·P-EMA)를 삭제하지 않고 보존 — 경고권 진입 '전'에 미리 수렴시켜 신규 기기의
    //    콜드스타트(Median 3프레임 + 칼만 vel 수렴 수초)를 제거한다. 경보 로직은 여전히 스킵(return)
    //    하므로 오경보 없음. 밴드 밖(원거리)은 기존대로 전삭제. 소실 기기 정리는 onDeviceLost 담당.
    internal val FILTER_PRESERVE_BAND_DB: Int get() = DevSettings.filterPreserveBandDb  // [판정 파라미터] 기본 10 = 기존값

    internal val pendingDisplayMap = mutableMapOf<String, Long>()   // deviceId → 마지막 보류 시각(ms)

    internal val approachStreakStartMap    = mutableMapOf<String, Long>()  // 연속 접근 시작 시각(ms)

    internal val fastApproachStreakMap     = mutableMapOf<String, Int>()   // [v1.1.21] 빠른 정면접근 연속 프레임 수(2프레임 확증)

    // [v1.1.11 C1] 전진-접근 가산(forwardApproachBias) 히스테리시스 래치 — deviceId별 ON/OFF 상태.
    //   kfVel 이 APPROACH_TIMEGATE_VEL_DBM 근처를 떨릴 때 payloadOffset(±3dB)이 프레임마다 토글되어
    //   임계 부근에서 WARNING↔SAFE 가 깜빡이던 결함을 막는다. 진입은 임계 즉시(페일세이프),
    //   해제는 임계×RELEASE_FRAC 미만(데드밴드)으로만 — 비대칭 래치.
    internal val forwardBiasLatchMap       = mutableMapOf<String, Boolean>()  // deviceId → 전진가산 래치 상태

    internal val FORWARD_BIAS_VEL_RELEASE_FRAC = 0.5  // 해제 데드밴드 = 임계속도의 50%

    internal val UWB_DEMOTE_STREAK    = 3       // 격하 확증 표본 수(FREQUENT ~120ms → 약 0.4s)

    internal val UWB_RELEASE_HYST_M   = 0.5f    // 경계 진동 억제 — 유지 중 임계+0.5m 까지 레벨 유지


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
            val iAmWalker   = fx.myCategory == BleConstants.CAT_WALKER
            val iAmForklift = fx.myCategory == BleConstants.CAT_FORKLIFT
            val iAmEpj      = fx.myCategory == BleConstants.CAT_EPJ
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

    internal val wasStationaryMap  = mutableMapOf<String, Boolean>()

    // ── [Phase 4 T1] 기기 상태 제거 단일 경로 ─────────────────────────────
    /** 상태 슬롯 레지스트리 — BleService 가 `asm.registry` 로 자기 맵·필터를 추가 등록한다. */
    val registry = DeviceStateRegistry()

    init {
        // immediate — 신호 소실 즉시 제거(ASM 자체 29맵)
        registry.addImmediate("alertState", alertState)
        registry.addImmediate("rushFrameMap", rushFrameMap)
        registry.addImmediate("dangerContactStreakMap", dangerContactStreakMap)
        registry.addImmediate("warningContactStreakMap", warningContactStreakMap)
        registry.addImmediate("warningMissRefMap", warningMissRefMap)
        registry.addImmediate("lastKfVelMap", lastKfVelMap)
        registry.addImmediate("timeGateWaiveSet", timeGateWaiveSet)
        registry.addImmediate("shadowFusionMap", shadowFusionMap)
        registry.addImmediate("trackingStateMap", trackingStateMap)
        registry.addImmediate("crossingStartMap", crossingStartMap)
        registry.addImmediate("departingStartMap", departingStartMap)
        registry.addImmediate("wasStationaryMap", wasStationaryMap)
        registry.addImmediate("recedingStartMap", recedingStartMap)
        registry.addImmediate("recedeRefMap", recedeRefMap)
        registry.addImmediate("recedePeakMap", recedePeakMap)
        registry.addImmediate("deviceRssiMap", deviceRssiMap)
        registry.addImmediate("approachStreakStartMap", approachStreakStartMap)
        registry.addImmediate("fastApproachStreakMap", fastApproachStreakMap)
        registry.addImmediate("forwardBiasLatchMap", forwardBiasLatchMap)
        registry.addImmediate("mutedDevices", mutedDevices)
        registry.addImmediate("peerInZoneMap", peerInZoneMap)
        registry.addImmediate("suddenLabelMap", suddenLabelMap)
        registry.addImmediate("deviceCategoryMap", deviceCategoryMap)
        registry.addImmediate("deviceStateMap", deviceStateMap)
        registry.addImmediate("deviceTurnMap", deviceTurnMap)
        registry.addImmediate("reverseRssiHist", reverseRssiHist)
        registry.addImmediate("reversePrepUntil", reversePrepUntil)
        registry.addImmediate("firebaseLastSaveMap", firebaseLastSaveMap)
        registry.addImmediate("pendingDisplayMap", pendingDisplayMap)

        // immediate — UwbDistanceManager 소유 3맵(제거 시점이 ASM 상태와 동일)
        registry.addImmediate("peerUwbSeenMap", uwbDist.peerUwbSeenMap)
        registry.addImmediate("uwbSampleAtMsMap", uwbDist.uwbSampleAtMsMap)
        registry.addImmediate("uwbSafeStreakMap", uwbDist.uwbSafeStreakMap)

        // deferred — 콜드 클리어·TTL 만료 때만. 칼만은 reset 후 제거(원본 순서 그대로).
        registry.addDeferred(
            "kalmanFilters",
            { id -> kalmanFilters[id]?.reset(); kalmanFilters.remove(id) },
            { kalmanFilters.clear() },
            { kalmanFilters.size }
        )

        // teardown — clearAll 전용. 기기별 purge 제외(웜 필터 보존 파괴 방지).
        registry.addTeardown("filterPreserveMap", filterPreserveMap)
    }

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
                    // [v1.1.48 #2] 리셋 데드밴드 — 기존 kfVel >= 0.0 은 전파 반사로 이탈 중 찰나의
                    //   +0.1 양수 튐(Micro-Bounce)에도 1.5s 이탈 카운터를 즉시 리셋해 DEPARTING 에
                    //   영원히 도달하지 못했다(사이렌 안 꺼짐의 근원). 확실한 재접근(kfVel >= 1.0)만
                    //   CPA 오판으로 보고 복귀하고, 0.0~1.0 미세 바운스는 카운터를 유지한 채 무시한다.
                    kfVel >= 1.0 -> {
                        // vel 뚜렷한 양수 → CPA 오판, APPROACHING 복귀
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
                        fx.sendStatusBroadcast("↗ 이탈 확인: ${fx.extractDisplayName(deviceId)}")
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
     * - 쿨다운 중: 모든 경보 억제 (단 강한 재접근 vel 은 즉시 해제 — v1.1.51 속도게이트)
     * - 쿨다운 후: WARNING 허용, DANGER는 DEPARTING_HYSTERESIS_DBM 추가 마진 필요
     */
    private fun applyDepartingHysteresis(
        deviceId: String, rawLevel: Int, blended: Int, offset: Int, now: Long, kfVel: Double
    ): Int {
        val state = trackingStateMap[deviceId] ?: return rawLevel
        if (state != TrackingState.DEPARTING) return rawLevel
        // [v1.1.51 속도게이트] 이탈 억제창 안이라도 강한 재접근(vel > CPA×3 = 1.5dBm/s)이면 즉시 해제 —
        //   이탈정리 후 재획득된 기기가 되돌아오는 지게차라면 실경보를 가리지 않고 통과시킨다.
        //   반사 바운스(Micro-Bounce ~+0.1dBm/s)는 임계 1.5 에 못 미쳐 억제 유지 → 오해제 없음.
        if (kfVel > (CPA_VEL_THRESHOLD * 3)) return rawLevel
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

    fun processAlert(deviceId: String, rssi: Int, remoteState: Int = 0x00, remoteTurn: Int = BleConstants.TURN_STRAIGHT, payloadPresent: Boolean = false, peerEchoRssi: Int = BleConstants.NO_ECHO_RSSI, nowMs: () -> Long = { System.currentTimeMillis() }) {
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

        val now      = nowMs()   // (02-01 D-2C) 골든 하네스 시각 주입 seam — 기본인자 호출 시 기존 동작과 동일

        // [v1.1.58 fix4] lost 후 재발견 복원 판정 — 보존 스냅샷이 있으면 여기서 단 한 번 소비.
        //   신선(30s 내)·연속(±10dB) 충족 → 필터 맵이 산 채로 남아 있어 아래 getOrPut 이 웜 칼만을
        //   그대로 반환(복원)하고 TimeGate 1회 면제권 부여(재발견 즉시 발령 가능 — 플래핑 소실 -87% 시뮬).
        //   불충족 → 그 자리서 콜드 클리어(기존 lost 즉시-클리어와 동일한 초기 상태로 진입).
        filterPreserveMap.remove(deviceId)?.let { snap ->
            val fresh      = android.os.SystemClock.elapsedRealtime() - snap.atMs <= KF_VEL_SEED_TTL_MS
            val contiguous = kotlin.math.abs(inputRssi - snap.refRssi) <= FILTER_PRESERVE_BAND_DB
            if (fresh && contiguous) {
                timeGateWaiveSet.add(deviceId)
            } else {
                fx.rssiPreFilter.clear(deviceId)
                fx.medianFilter.clear(deviceId)
                fx.pEmaFilter.clear(deviceId)
                kalmanFilters[deviceId]?.reset()
                kalmanFilters.remove(deviceId)
            }
        }

        // ── 2D 칼만 필터 가져오기 또는 생성 ──────────────────────────────
        val kf = kalmanFilters.getOrPut(deviceId) {
            // [v1.1.8 #3] Cold-Start 웜업 주입 — 신규/재획득 기기를 첫 raw RSSI 로 즉시 초기화(공분산↓)해
            //   재획득 시 칼만 속도(D) 수렴 지연을 단축한다. (신규 '발령' 자체는 아래 Median N=3 워밍업
            //   게이트가 계속 방어하므로 콜드스타트 오발 위험 없이 추정 수렴만 앞당긴다)
            // (v1.1.56 U3) 직전 정리(SAFE/이탈/하드게이트)에서 캡처한 이탈속도를 재시드(음수만, -1.5 캡) —
            //   얕은 SAFE 딥 직후 재등록 시 속도 0 재출발로 이탈 판정이 원점부터 재시작되는 플랩 억제.
            //   엔트리는 부호 무관 1회성 소비(remove).
            //   (v1.1.57) TTL 검사 — 캡처 후 KF_VEL_SEED_TTL_MS 초과 스냅샷은 무효화(0.0 재출발).
            val seedVel = lastKfVelMap.remove(deviceId)
                ?.takeIf { android.os.SystemClock.elapsedRealtime() - it.timestamp <= KF_VEL_SEED_TTL_MS }
                ?.velocity?.takeIf { it < 0.0 }?.coerceAtLeast(-1.5) ?: 0.0
            KalmanFilter(DevSettings.kalmanPreset).apply { injectWarmup(inputRssi, seedVel) }
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
        val medianValue = fx.medianFilter.push(deviceId, inputRssi)

        // ── (v1.1.40) 섀도우 IMU 융합 갱신 — median 스트림 전용, 메인 파이프라인 무접촉 ──
        //   sPrevVel(직전 프레임 섀도우 속도)·sh.tracking(직전 프레임 이탈추적)·prevLevel(직전
        //   프레임 alertState)로 부스트를 판정한 '뒤' 이번 프레임 관측을 반영한다(인과 정합).
        val shadowOn = DevSettings.imuShadowFusionEnabled && payloadPresent
        var shadowBoost = false
        val sh = if (shadowOn) shadowFusionMap.getOrPut(deviceId) { ShadowFusion() } else null
        if (sh != null) {
            val selfStat = ImuFusion.isStationary
            val peerFwdFresh = rState == BleConstants.PSTATE_FORWARD
            val sPrevVel = sh.kf.estimatedVel
            val effWarnRef = if (sh.lastEffWarning != Int.MIN_VALUE) sh.lastEffWarning else DevSettings.rssiWarning
            val sqf = when {
                !selfStat -> 1.0
                peerFwdFresh && medianValue >= effWarnRef -> SHADOW_CROSS_Q_MILD
                else -> SHADOW_Q_FREEZE
            }
            sh.kf.updatePreset(if (promoteFast) DevSettings.KALMAN_PRESET_FAST else DevSettings.kalmanPreset)
            val (_, sVel) = sh.kf.update(medianValue, sqf)
            sh.apprStreak = if (sqf > SHADOW_Q_FREEZE && sVel >= MIN_APPROACH_VEL_DBM) sh.apprStreak + 1 else 0
            val prevLevel = alertState[deviceId]?.first ?: BleConstants.LEVEL_SAFE
            // (v1.1.56 U4b) 보행자 정지 조건(selfStat) 삭제 — 섀도우 이탈속도 확증이면 이동/정지 무관 부스트.
            val live = sh.tracking && sPrevVel <= SHADOW_LIVE_VEL_DBM && peerFwdFresh
            if (live && prevLevel == BleConstants.LEVEL_DANGER) sh.relLatch = true
            if (!live || prevLevel == BleConstants.LEVEL_SAFE) sh.relLatch = false
            shadowBoost = live && (prevLevel == BleConstants.LEVEL_DANGER || sh.relLatch)
        }

        // ── [v1.0.32] RssiPreFilter: 비대칭 비례제어(Asymmetric P-Control) EMA 전처리 ──
        //   강한 돌진(prevVel>+2.0)이면 α 빗장(D-Boost)을 열어 지연을 없앤다.
        //   ★ v1.0.45: EMA 입력을 raw → medianValue 로 변경(Median 직렬 선행). 정제 출력(preFiltered)만
        //     칼만 입력으로 주입(raw 직접 입력 금지).
        //   (v1.1.40) fallBoost=shadowBoost — DANGER 이탈확증 프레임은 하강 알파 부스트(해제 가속).
        val preFiltered = fx.rssiPreFilter.push(deviceId, medianValue, prevVel, fallBoost = shadowBoost)

        // ── 2D 칼만 필터 업데이트 (RSSI 공간) ────────────────────────────
        // kfRssi: 추정 RSSI(dBm) / kfVel: 변화율(dBm/s), 양수=접근 / 음수=이탈
        val (kfRssi, kfVel) = kf.update(preFiltered, ImuFusion.adaptiveQFactor)
        if (kfVel > 0.0) fx.lastApproachAtMs = nowMs()  // [v1.1.12 L1] 접근(다가옴) 표본 시각 기록 → isDangerPresent 절전 게이트 (02-01 D-2C seam)
        val kalmanRssi = kfRssi.toInt()

        // ── [v1.0.45] 후처리 P-EMA: 거리(P)항 전용 평활 — kfRssi → 비대칭 P-EMA → 거리판정 ──
        //   D항(kfVel)은 위상선행 유지를 위해 후필터 우회(아래 Time-Gate/TTC 에 kfVel 직결).
        //   P항(거리)만 평활(상승0.4/하강0.15). 게이트 1번째 다리는 raw-order kalmanRssi 유지(보수적 min).
        //   (v1.1.40) fallBoost=shadowBoost — 전단 EMA 와 동일한 이탈확증 하강 부스트(P-EMA 잔상 제거).
        val pEma = fx.pEmaFilter.push(deviceId, kalmanRssi, fallBoost = shadowBoost)

        // ── [v1.0.45] 워밍업 가드: Median 윈도우 충전 전(콜드스타트)에는 신규/격상 발령 보류 ──
        //   첫 N프레임은 임펄스로 시작했을 때 거짓 근접으로 보일 수 있어, 필터 상태는 계속 쌓되
        //   '발령'만 보류한다. 윈도우가 차면 Median 임펄스 방어가 완성된다(특수경보·TTC·일반 모두 적용).
        val warmingUp = !fx.medianFilter.isFull(deviceId)

        // [v1.0.31] raw 1초평균 — 하드게이트/2차게이트/TTC 교차검증용으로 게이트 '앞'에서 1회만 계산.
        //   fx.oneSecAvgRssi 는 호출마다 버퍼에 push(부작용) → 프레임당 1회 호출 후 변수 재사용한다.
        val avg1sec = fx.oneSecAvgRssi(deviceId, inputRssi)   // 1초 평균은 raw 기준 유지

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
        //   경보 발령은 아래 하드게이트·판정옵션이 독립 결정(R4/R5). 사이드바(hazardListForOverlay)는
        //   alertState 만 보므로 약신호는 사이드바에 뜨지 않고 목록에만 SAFE 행으로 노출된다.
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
        // (v1.1.31→v1.1.49) UWB 델타 보정 — UWB 실측이 흐르는 페어면 실거리+medianValue(스파이크 제거·
        //   지연≈0)로 이 페어의 채널 편차 Δ 를 학습한다. [v1.1.49] 학습된 보정은 더 이상 판정 오프셋에
        //   합산하지 않는다(totalOffset 에서 제거) — 화면 거리 표시에만 쓴다. 상세 근거는 아래 onSample 주석.
        //   비대칭 클램프(지연 −3dB / 조기 +10dB)+24h 감쇠는 UwbCalibrator 내부 불변식.
        val uwbPairKey = fx.uwbPairKeyFor(deviceId)   // [v1.1.37 ③] 개별 기기 대신 역할쌍 세그먼트로 학습·조회
        // [v1.1.46] 학습 입력=신선한 실측만 — 마지막 표본이 오래된 UWB 거리에 '현재' RSSI 를 짝지으면
        //   Δ 가 오염돼 임계가 영구히 앞당겨진다(즉시 DANGER 증상의 한 축). 거리 표시도 같은 게이트.
        uwbDist.freshUwbDistM(deviceId)?.let { UwbCalibrator.onSample(uwbPairKey, medianValue, it) }
        // [v1.1.49] 학습(onSample)은 유지하되 그 출력(offsetDbFor)은 RSSI 판정에서 완전 분리한다.
        //   역할쌍 키 uwbCalibOffset(최대 +10dB)이 NLOS 잔차로 +클램프까지 표류하면 effDanger 가 밀려
        //   올라가 'RSSI 판정이면 신호 세기와 무관하게 상시 위험'이 되던 회귀(UWB 도입 v1.1.31 이후)를
        //   차단. 학습된 Δ 는 화면 거리 표시(distanceTextFor)에만 남기고 totalOffset 에서 뺀다
        //   → Case B(RSSI)=UWB 도입 이전의 순수 RSSI 임계로 복귀(Case A 는 원래부터 실측 거리로 판정).
        // [v1.1.55 Level2 에코 자동보정] echoCal 은 위 uwbCalibOffset 과 달리 totalOffset 에 합산한다.
        //   구조적 차이: UWB 잔차는 '단방향 절대비교'라 NLOS 감쇠가 그대로 편향으로 쌓여 표류했지만
        //   (v1.1.49 제거 사유), 에코편차는 '양방향 차동'(내가 잰 상대 − 상대가 잰 나)이라 NLOS·거리
        //   감쇠가 공통모드로 1차 상쇄되고 남는 것이 TX/RX 하드웨어 비대칭 — 보정하려는 대상 그 자체.
        //   그래도 안전장구는 동일: 중앙값(스파이크 면역)·±클램프·n/산포 게이트·킬스위치(기본 OFF).
        //   OFF·게이트 미성립·산포 과다 = 0 → 기존 거동과 완전 동일. 파급: effWarning/effDanger 에서
        //   coop·safeForceFloor·urgentBypass 등 파생 임계 전부에 자동 전파(비콘 offset 합산과 같은 원리).
        val echoCalDb = if (DevSettings.echoAutoCalibEnabled) CalibrationEngine.echoCalAppliedDb(deviceId) else 0
        val totalOffset = payloadOffset + beaconOffset + beaconGlobalGain + echoCalDb
        val effWarning = BleConstants.rssiWarning - totalOffset
        val effDanger  = BleConstants.rssiDanger  - totalOffset

        // ── [v1.1.46] Case A(UWB↔UWB) 배타 판정 조기 분기 ─────────────────────────────
        //   UWB 실측 신호가 신선한 페어(uwbJudgeModeExclusive)만 이 기기의 경보 판단이 UWB 실측
        //   전용(judgeUwbOnly, onUwbSampleReceived 구동)이며 RSSI 는 판단에 절대 개입하지 않는다.
        //   여기(필터 워밍·표시 갱신·Calibrator 학습 '후', streak·게이트·레벨 판정 '전')서 반환해
        //   위 전처리는 계속 수렴시킨다 — 실측 신호가 끊기면 다음 프레임부터 자동 Case B(RSSI)
        //   복귀하며 워밍된 필터로 무봉합 인계된다. streak 리셋=폴백 stale 인플레 방지.
        if (uwbDist.uwbJudgeModeExclusive(deviceId, now)) {
            dangerContactStreakMap[deviceId] = 0
            warningContactStreakMap[deviceId] = 0
            warningMissRefMap.remove(deviceId)
            return
        }

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
        // [v1.1.71 D-3B BUG-02] WARNING streak 만 미달 시 변화율(dBm/s) 기준 즉시 리셋 여부를 가른다.
        //   저속·잡음 섞인 접근은 medianValue 가 근접 문턱 언저리를 오르내리며 매 미달 프레임 즉시
        //   0 으로 끊겨(원래 로직) 2프레임 연속 확증이 계속 지연된다(현장 미탐지 근본원인, must_haves
        //   truth#3 "경고 등급 도달"이 대상) — 실측 잡음 하강률은 WARNING_DEPART_RATE_DBM_PER_SEC 미만이라
        //   완만한 미달은 streak 를 보존(잡음 흡수)한다. release_goldenTimeline(D-3D)의 연속 하강은
        //   실측 하강률이 임계를 훨씬 초과해 원래처럼 매 미달 프레임 즉시 0 — 골든 무변화.
        //   DANGER streak(위)는 원래대로 유지 — effDanger ⊂ effWarning 상위호환 구조상 WARNING 만
        //   완화해도 저속 접근의 최초 확증(경고 등급) 목표는 달성되며 DANGER 이탈측 즉시 억제 의미는 보존된다.
        val inWarningRaw = medianValue >= effWarning
        val (prevMedianForWarning, prevAtMsForWarning) = warningMissRefMap[deviceId] ?: (medianValue to now)
        val warningStreak = when {
            inWarningRaw -> (warningContactStreakMap[deviceId] ?: 0) + 1
            else -> {
                val dtSec = (now - prevAtMsForWarning).coerceAtLeast(1L) / 1000.0
                val rateDbmPerSec = (medianValue - prevMedianForWarning) / dtSec
                if (rateDbmPerSec <= -WARNING_DEPART_RATE_DBM_PER_SEC) 0 else (warningContactStreakMap[deviceId] ?: 0)
            }
        }
        warningContactStreakMap[deviceId] = warningStreak
        warningMissRefMap[deviceId] = medianValue to now
        // (v1.1.40) 섀도우 이탈 추적 + effWarning 1프레임 캐시 — 부스트는 '직전 프레임' tracking 을
        //   읽으므로(위 median 직후 블록) 당 프레임 갱신은 다음 프레임부터 반영된다(설계 정합).
        if (sh != null) {
            val sVelNow = sh.kf.estimatedVel
            sh.departFrames = if (sVelNow < -MIN_APPROACH_VEL_DBM) sh.departFrames + 1 else 0
            if (sh.departFrames >= 2) sh.tracking = true
            if (sVelNow > SHADOW_DEPART_REENTER_VEL &&
                (!ImuFusion.isStationary || avg1sec >= effWarning)) sh.tracking = false
            sh.lastEffWarning = effWarning
        }
        // [Phase2] IDLE-IDLE 가청 억제 — 내 IMU 정지 + 상대 IDLE 송신(둘 다 정지=충돌동역학 없음)이면
        //   아래 정규 WARNING 가청경보를 억제(표시·목록·위젯은 유지). DANGER 는 억제 대상이 아니며,
        //   둘 중 하나라도 움직이면(rState≠IDLE 또는 IMU 이동) 다음 프레임 즉시 해제된다.
        // [v1.1.11 C2] payloadPresent 필수 — 비콘·구버전(페이로드 부재)은 rState 가 무조건 IDLE 로 디코드되어
        //   '이동 중인 비콘 장비'가 영구 IDLE 로 오인, DANGER 가 WARNING 강등→무음화되는 구멍이 있었다.
        //   실제 1바이트 자기-신고를 보낸 기기에만 억제를 허용해 그 구멍을 막는다.
        // [v1.1.59] 역할쌍 확장 — EPJ↔EPJ·EPJ↔보행자 쌍(지게차 무관 쌍)은 신규 플래그로 기본 ON.
        //   근거: EPJ 근접작업 시뮬(sim_epj.py quiet) — 통상작업(IMU duty30) WARNING 비프 42~48% 억제,
        //   정지상주→돌진 최악(A5) 가청지연 +0.01s·미탐 0%·DANGER 완전 불변. 이동 중 접근은 게이트 구조상 억제 불가.
        //   전역 플래그 ON=전 쌍 억제(기존 의미 그대로·상위집합), 신규 플래그 OFF=정확히 구 동작(킬스위치).
        val epjQuietPair = (fx.myCategory == BleConstants.CAT_EPJ || rCategory == BleConstants.CAT_EPJ) &&
            fx.myCategory != BleConstants.CAT_FORKLIFT && rCategory != BleConstants.CAT_FORKLIFT
        val quietArmed = DevSettings.idleIdleSuppressEnabled ||
            (DevSettings.idleIdleSuppressEpjPairsEnabled && epjQuietPair)
        val idleIdleQuiet = quietArmed && payloadPresent &&
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
        // [v1.1.48 #1] 긴급 접근 게이트 우회 — 투표자 avg1sec(과거 1초 raw 평균)은 코너 돌진처럼
        //   신호가 급상승하는 국면에서 항상 과거값에 눌려 있어, 칼만·median 이 이미 위험권이어도
        //   중앙값(gateRssi)이 '멀다'를 가리켜 신규 기기 경보가 원천 차단(return)되는 병목이 있었다.
        //   빠른 접근(kfVel >= 2.0 dBm/s) 또는 median 이 이미 위험권(effDanger)이면 게이트를 우회해
        //   판정 기회를 준다. 우회는 발령이 아니다 — 원거리 오발은 아래 2차 방어선(safeForceFloor
        //   강제-SAFE)이 그대로 걸러내고, 발령은 여전히 streak 확증(2프레임)을 거친다.
        val urgentBypass = kfVel >= 2.0 || medianValue >= effDanger
        if (!urgentBypass && gateRssi < effWarning && !alertState.containsKey(deviceId)) {   // [v1.1.10] effWarning(페이로드 시프트)로 일관
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
            timeGateWaiveSet.remove(deviceId) // [v1.1.58 fix4] 미추적 강등 — 미소비 TimeGate 면제권 회수
            fx.rssiPreFilter.clear(deviceId)     // [v1.0.38 클린업] 미추적 기기 EMA 전처리 상태 정리
            fx.medianFilter.clear(deviceId)      // [v1.0.45] Median 윈도우 정리(워밍업 상태 리셋)
            fx.pEmaFilter.clear(deviceId)        // [v1.0.45] 후처리 P-EMA 상태 정리
            rushFrameMap.remove(deviceId)     // [v1.0.45] 돌진 프레임 카운터 정리
            dangerContactStreakMap.remove(deviceId)   // [v1.1.16 D] 첫접촉 DANGER 카운터 정리
            warningContactStreakMap.remove(deviceId)  // [v1.1.18] 첫접촉 WARNING 카운터 정리
            warningMissRefMap.remove(deviceId)     // [v1.1.71] WARNING 미달 카운터 정리
            kalmanFilters[deviceId]?.let { lastKfVelMap[deviceId] = LastKfVelState(it.estimatedVel, android.os.SystemClock.elapsedRealtime()) }   // (v1.1.56 U3) 재시드 캡처 (v1.1.57 시각 동봉)
            kalmanFilters.remove(deviceId)    // [v1.0.38 클린업] 미추적 기기 칼만 인스턴스 정리(stale 재등장 방지)
            shadowFusionMap.remove(deviceId)  // (v1.1.40) 섀도우 융합 상태 정리(미추적 기기)
            recedingStartMap.remove(deviceId)    // [v1.1.6 검증 보강] 이탈 판정 상태 누수·stale 피크 재출현 방지
            recedeRefMap.remove(deviceId)        // [v1.1.6 검증 보강] 미추적 기기 중간평활 EMA 정리
            recedePeakMap.remove(deviceId)       // [v1.1.6 검증 보강] 미추적 기기 피크 홀드 정리
            fx.clearDwellMute(deviceId)             // (v1.1.61) 경보권 밖 강등 = 존 이탈 — dwell 뮤트 리셋
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
        // 표시문자열을 fx.makeStateLabel(후진·하역 경보 문구)로 덮어써 오버레이·목록에 출력.
        if ((rState == BleConstants.PSTATE_REVERSE || rState == BleConstants.PSTATE_LOADING)
            && !warmingUp                                   // [v1.0.45] 콜드스타트 임펄스 발령 보류
            && pEma    >= effDanger                          // [v1.0.45/v1.1.10] 거리판정: P-EMA, effDanger(페이로드 시프트)
            && avg1sec >= effDanger
            && peerInZoneMap[deviceId] != true) {            // (v1.1.62) 상대 IN_ZONE 선언=무해 — 특수경보 진입 자체 차단
            deviceRssiMap[deviceId]  = kalmanRssi
            suddenLabelMap[deviceId] = fx.makeStateLabel(fx.extractDisplayName(deviceId), rCategory, rState)
            alertState[deviceId]     = Pair(BleConstants.LEVEL_DANGER, now)
            pendingDisplayMap.remove(deviceId)   // [v1.0.49 #3] 경보 등록 → 보류 표시 해제
            fx.bleScanner?.setEcoMode(false)   // 즉시 전투 모드(ACTIVE)
            Log.w(TAG, "특수경보(STATE=$rState CAT=$rCategory): $deviceId pEma=$pEma kfRssi=%.1f".format(kfRssi))
            fx.updateDwellMute(deviceId, BleConstants.LEVEL_DANGER, now)   // (v1.1.61) 특수경보도 체류 추적(연속 체류 5s = 뮤트)
            // 무음(전역/개별/dwell/존)은 존중 — 상태·표시는 유지하되 소리/진동만 억제
            if (fx.isMuted || fx.isDeviceMuted(deviceId) || fx.isDwellMuted(deviceId, BleConstants.LEVEL_DANGER) || fx.myZoneInside) {
                fx.updateFloatingOverlay()
                return
            }
            fx.forceAlarmVolume()
            // [v1.0.46 #7] !isScreenOn 조건 제거 — FLAG_KEEP_SCREEN_ON 탓에 포그라운드 진동이 사망 상태였다
            if (DevSettings.vibrationEnabled) fx.vibrateDanger()
            if (DevSettings.soundEnabled)     fx.playDanger()
            fx.activeSoundLevel = BleConstants.LEVEL_DANGER   // [v1.0.46 #2] 사이렌 레벨 동기 — 후속 WARNING 의 조기차단/영구지속 방지
            fx.updateFloatingOverlay()
            fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
            fx.sendStatusBroadcast("${suddenLabelMap[deviceId]}")
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
        val movingEquipApproach = fx.myCategory == BleConstants.CAT_WALKER &&
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
        val afterHysteresis = applyDepartingHysteresis(deviceId, rawLevel, pEma, totalOffset, now, kfVel)
        // ── 정지 격하 방어 ([v1.0.47 #2] 보행자+활동 장비 접근은 예외 — 위 demoteWhileStationary) ──
        // [v1.1.16 B] 내가 장비(지게차/EPJ)이고 상대가 위험권(DANGER) 보행자/비콘이면 내 IMU 가 정지여도
        //   DANGER→WARNING 강등 금지(작업자 위에 멈춘 지게차는 정지여도 위험). 위에서 보존한 distanceLevel
        //   (격하·히스테리시스 이전 순수 거리 권위값)을 기준 삼아 강등 루프 오염을 피한다.
        val iAmEquip = fx.myCategory == BleConstants.CAT_FORKLIFT || fx.myCategory == BleConstants.CAT_EPJ
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
        //   [v1.1.52] 완화 게이트: 폰별 TX/RX 비대칭(내 폰은 안 울리는데 상대는 울림)을 메운다. 수용 문턱을
        //     effWarning 에서 coopSlackDb(기본 8dB)만큼 더 약한 신호까지 낮춰, 내가 경고권에 살짝 못 미쳐도
        //     상대 위험송출을 함께 울린다. 일반 임계(effWarning)는 불변 — 진짜 먼 오발(슬랙 밖)은 여전히 차단.
        // [v1.1.53 상호RSSI] 기준선 없는 coopSlack 완화(v1.1.52)를 '폴백 전용'으로 강등하고, 상대가
        //   되돌려 보낸 상호 실측(peerEchoRssi = rssi_me→peer)이 있으면 대칭 판정을 우선한다.
        //   sym = (내 pEma(avgRssi) + 상대가 측정한 나(peerEchoRssi)) / 2 — 양 폰이 같은 두 값을 평균하므로
        //   결과가 동일 → 단일 effWarning 문턱과 비교해도 양쪽 판정이 구조적으로 일치(2중 보정·비대칭 제거).
        //   정합성 가드: 두 측정이 reciprocalMaxDisagreeDb(기본 25dB) 넘게 어긋나면(부트스트랩·해시충돌·이상치)
        //   대칭을 신뢰하지 않고 coopSlack 폴백으로 되돌린다. 에코 부재(구버전·비콘)도 폴백.
        //   (UWB Case A 는 이 블록 이후 별도 승격 — 상호RSSI 는 Case B(RSSI) 전용, UWB 판정 무접촉.)
        // [v1.1.54 에코편차 집계] 텔레메트리 기록 — 아래 협력 격상 로직과 완전 독립(이 틱 판정에 결과 미사용).
        //   (v1.1.55 Level2 는 '누적' 히스토그램을 이후 틱들의 totalOffset 산출에 사용 — 기록 시점과 분리.)
        //   이 틱의 (내 측정 avgRssi ↔ 상대가 측정한 나 peerEchoRssi) 차이를 히스토그램에 누적만 한다.
        //   25dB 게이트(hasReciprocal)보다 앞·무관하게 기록 — 게이트 밖 극단 비대칭도 관찰 대상.
        //   debugMode 는 제외(시뮬 RSSI 가 avgRssi 에 대입돼 실측 통계를 오염시키므로).
        if (!DevSettings.debugMode) CalibrationEngine.recordEchoDiff(fx.myId, deviceId, avgRssi, peerEchoRssi)

        val coopFloor = effWarning - DevSettings.coopSlackDb
        val hasReciprocal = DevSettings.reciprocalRssiEnabled &&
            peerEchoRssi != BleConstants.NO_ECHO_RSSI &&
            kotlin.math.abs(avgRssi - peerEchoRssi) <= DevSettings.reciprocalMaxDisagreeDb
        val coopStrong = if (hasReciprocal) {
            (avgRssi + peerEchoRssi) / 2 >= effWarning       // 대칭 실측 — 단일 기준선 판정
        } else {
            avgRssi >= coopFloor && avg1sec >= coopFloor      // 폴백 — 기존 coopSlack 완화 게이트
        }
        if (rRisk > BleConstants.LEVEL_SAFE && rRisk > stableLevel && coopStrong) {
            val beforeCoop = stableLevel
            stableLevel = rRisk.coerceAtMost(BleConstants.LEVEL_DANGER)
            if (hasReciprocal) {
                Log.w(TAG, "협력 격상(상호RSSI): $deviceId rRisk=$rRisk sym=${(avgRssi + peerEchoRssi) / 2}(내pEma=$avgRssi+상대측정=$peerEchoRssi)/2 ≥effWarning=$effWarning → $beforeCoop→$stableLevel")
            } else {
                Log.w(TAG, "협력 격상(폴백·slack=${DevSettings.coopSlackDb}): $deviceId rRisk=$rRisk 내RSSI(pEma=$avgRssi raw=$avg1sec ≥$coopFloor) → $beforeCoop→$stableLevel")
            }
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
        //   (v1.1.50) uwbD 는 freshUwbDistM — uwbDistances 맵 직접조회 금지. 맵 엔트리는 세션 단절
        //   시 즉시 제거되지 않고 onDeviceLost/onSessionEnded 까지 지연 잔존하므로(v1.1.43~46 이력),
        //   과거 근접 실측(예: 사라진 지게차의 2m)이 좀비로 남아 매 프레임 DANGER 재승격시키던 벡터를
        //   차단한다. freshUwbDistM 은 uwbSampleAtMsMap 신선도(≤1s) 통과 표본만 반환, 낡으면 null →
        //   이 블록 무동작 → RSSI 폴백으로 자연 해제.
        //   distanceLevel 도 승격 레벨까지만 함께 상향 — 아래 이탈 가드(isReceding)가 차폐로 낮아진
        //   pEma 기준 distanceLevel<DANGER 를 '위험권 밖 이탈'로 오판해 무음화(조기 return)하는 것을
        //   DANGER 승격 시 막는다(v1.1.32 와 동일). WARNING 승격은 이 가드 밖이지만, 이탈 오판으로
        //   해제돼도 실측이 반경 안이면 다음 프레임 이 블록이 재승격 → 재발령(영구 무음 없음).
        //   진짜 이탈은 OR isDepartingNow(v1.1.22 B) 절이 그대로 잡으므로 이탈측 로직은 무접촉.
        if (DevSettings.uwbPromoteEnabled && stableLevel < BleConstants.LEVEL_DANGER) {
            val uwbD = uwbDist.freshUwbDistM(deviceId)
            if (uwbD != null) {
                val forkliftPair = fx.myCategory == BleConstants.CAT_FORKLIFT ||
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
            val kin = fx.uwbRanger?.uwbKinematics?.get(deviceId)
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
            val kin = fx.uwbRanger?.uwbKinematics?.get(deviceId)
            val uwbNowD = uwbDist.freshUwbDistM(deviceId)
            if (kin != null && uwbNowD != null && now - kin.atMs <= 1500L &&
                kin.separatingStreak >= 3 && kin.closingMps < 0f &&
                kfVel < DevSettings.fastApproachBypassVelDbm) {
                val forkliftPair = fx.myCategory == BleConstants.CAT_FORKLIFT ||
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
        //   (v1.1.50) uwbPrimD 는 freshUwbDistM — 신선도(≤1s) 통과 실측만 반환(맵 직접조회 금지).
        //   맵 엔트리는 세션 단절 시 지연 잔존하므로(v1.1.43~46), 사라진 기기의 낡은 근접값이 좀비로
        //   남아 (A)격상에서 매 프레임 DANGER 재승격하던 벡터를 차단. null(신선 실측 없음)이면 이
        //   블록 무동작(위 RSSI stableLevel 유지 = 무봉합 폴백). RSSI 보정 학습(UwbCalibrator, 상단)은 독립 지속.
        if (DevSettings.uwbPrimaryAuthorityEnabled) {
            val uwbPrimD = uwbDist.freshUwbDistM(deviceId)
            if (uwbPrimD != null) {
                val forkliftPair = fx.myCategory == BleConstants.CAT_FORKLIFT ||
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
                    val kin = fx.uwbRanger?.uwbKinematics?.get(deviceId)
                    if (kin != null && now - kin.atMs <= 1500L &&
                        kin.separatingStreak >= 3 && kin.closingMps < 0f) {
                        Log.w(TAG, "(v1.1.39) UWB 이탈 강등 ${stableLevel}->${uwbLevel}: ${deviceId} d=%.1fm 멀어짐v=%.1fkm/h streak=${kin.separatingStreak}".format(uwbPrimD, kin.closingMps * 3.6f))
                        stableLevel = uwbLevel
                        if (distanceLevel > uwbLevel) distanceLevel = uwbLevel
                    }
                }
            }
        }

        // (v1.1.62) 항목5 피어 무해 판정 — 상대가 IN_ZONE(존 비콘 접촉·설정 세기 수신) 선언 중이면
        //   레벨을 SAFE 로 클램프(억제 전용 — 격상 방향 오버라이드 없음). 아래 SAFE 처리가 자연 정리.
        if (peerInZoneMap[deviceId] == true && stableLevel > BleConstants.LEVEL_SAFE) {
            if (alertState.containsKey(deviceId))
                Log.d(TAG, "(v1.1.62) 피어 존 무해 클램프: $deviceId level=$stableLevel -> SAFE")
            stableLevel = BleConstants.LEVEL_SAFE
        }

        // [v1.0.26 Req2] 개별 sendDetectedBroadcast 폐지 — 목록은 onDeviceDetected 처리 직후
        // fx.broadcastDeviceList() 가 alertState 전체를 한 번에 송출한다(단일 진실 공급원).

        // (v1.1.61) 항목4 dwell 추적 — 발령 등록(alertState) 중인 기기가 같은 레벨에 DWELL_MUTE_MS
        //   연속 체류하면 그 레벨 소리·진동을 자동 뮤트(fx.updateDwellMute 내부에서 재정합까지 수행).
        //   SAFE 프레임은 아래 SAFE 처리의 fx.clearDwellMute 가 리셋 담당(존 이탈=해제·재진입=정상 발령).
        if (stableLevel >= BleConstants.LEVEL_WARNING && alertState.containsKey(deviceId))
            fx.updateDwellMute(deviceId, stableLevel, now)

        // ── SAFE 처리 ───────────────────────────────────────────────────
        if (stableLevel == BleConstants.LEVEL_SAFE) {
            if (alertState.containsKey(deviceId)) {
                alertState.remove(deviceId)
                fx.rssiPreFilter.clear(deviceId)
                fx.medianFilter.clear(deviceId)      // [v1.0.45]
                fx.pEmaFilter.clear(deviceId)        // [v1.0.45]
                rushFrameMap.remove(deviceId)     // [v1.0.45]
                dangerContactStreakMap.remove(deviceId)   // [v1.1.16 D]
                warningContactStreakMap.remove(deviceId)  // [v1.1.18]
                warningMissRefMap.remove(deviceId)     // [v1.1.71]
                lastKfVelMap[deviceId] = LastKfVelState(kf.estimatedVel, android.os.SystemClock.elapsedRealtime())   // (v1.1.56 U3) reset 전 이탈속도 캡처(재시드용, v1.1.57 시각 동봉)
                kf.reset()
                kalmanFilters.remove(deviceId)
                shadowFusionMap.remove(deviceId)   // (v1.1.40) 섀도우 융합 상태 정리
                // (v1.1.56 U2) 추적맵(상태·CROSSING·DEPARTING 앵커) 조기삭제 방지 — DEPARTING 진입 후
                //   3000ms 는 무조건 유지(Hold), 그 외엔 pEma 가 경고문턱-5dBm 이하로 충분히 이탈했을
                //   때만 삭제. 얕은 SAFE 딥에서 이탈 앵커가 증발해 REGISTER 재발령이 반복되는 플랩을
                //   차단한다(시뮬 검증). 나머지 정리·SAFE 방송·사운드 정지는 기존 그대로.
                val inDepartHold = trackingStateMap[deviceId] == TrackingState.DEPARTING &&
                    departingStartMap[deviceId]?.let { now - it < 3000L } == true
                if (!inDepartHold && pEma <= effWarning - 5) {
                    trackingStateMap.remove(deviceId)
                    crossingStartMap.remove(deviceId)
                    departingStartMap.remove(deviceId)
                }
                approachStreakStartMap.remove(deviceId)   // [v1.0.46 #4] stale 시작시각 → 재접근 시 Time-Gate 즉시통과 방지
                fastApproachStreakMap.remove(deviceId)    // [v1.1.21] stale 카운터 → 재접근 시 1프레임에 즉시통과 방지
                forwardBiasLatchMap.remove(deviceId)      // [v1.1.11 C1] SAFE 강등 → 래치 리셋(재접근 시 fresh)
                fx.clearDwellMute(deviceId)                  // (v1.1.61) SAFE 확정 = 존 이탈 — dwell 뮤트 리셋(재진입=정상 발령)
                peerInZoneMap.remove(deviceId)            // (v1.1.62) SAFE 정리 — 다음 광고 표본이 재선언(스테일 캐시 방지)
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
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    fx.activeSoundLevel = BleConstants.LEVEL_SAFE
                    fx.stopVibration()
                    fx.collapseOverlay()
                } else {
                    fx.resyncSoundToRemaining()  // [v1.1.37 ②] 상위 기기 이탈 → 남은 최대레벨로 사운드 하향 정합
                    fx.updateFloatingOverlay()   // 다른 위험 기기로 플로팅 전환
                }
            }
            return
        }

        // [v1.0.27] 여기 도달 = 비-SAFE(경보 상황). 정지 중이라도 즉시 전투모드(ACTIVE) 보장.
        fx.bleScanner?.setEcoMode(false)

        // 무음 중 — 상태 추적만 유지 (전역 무음 또는 [v1.0.25] 해당 기기 Acknowledge 무음)
        if (fx.isMuted || fx.isDeviceMuted(deviceId)) {
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
                    fx.stopVibration()
                    fx.collapseOverlay()
                    fx.activeSoundLevel = BleConstants.LEVEL_SAFE
                }
                Log.d(TAG, "이탈 감지 즉시 소리 중지: $deviceId (peak=%.1f, ref=%.1f, drop=%.1f dBm)".format(recedePeak, recedeRef, recedePeak - recedeRef))
                fx.sendStatusBroadcast("↗ 이탈 감지 → 경보 일시 해제: ${fx.extractDisplayName(deviceId)}")
            }
            val recedingMs = now - (recedingStartMap[deviceId] ?: now)
            if (recedingMs >= RECEDING_CLEAR_MS && alertState.containsKey(deviceId)) {
                alertState.remove(deviceId)
                fx.rssiPreFilter.clear(deviceId)
                fx.medianFilter.clear(deviceId)      // [v1.0.45]
                fx.pEmaFilter.clear(deviceId)        // [v1.0.45]
                rushFrameMap.remove(deviceId)     // [v1.0.45]
                dangerContactStreakMap.remove(deviceId)   // [v1.1.16 D]
                warningContactStreakMap.remove(deviceId)  // [v1.1.18]
                warningMissRefMap.remove(deviceId)     // [v1.1.71]
                kalmanFilters[deviceId]?.let { lastKfVelMap[deviceId] = LastKfVelState(it.estimatedVel, android.os.SystemClock.elapsedRealtime()) }   // (v1.1.56 U3) 재시드 캡처 (v1.1.57 시각 동봉)
                kalmanFilters[deviceId]?.reset()
                kalmanFilters.remove(deviceId)
                shadowFusionMap.remove(deviceId)   // (v1.1.40) 섀도우 융합 상태 정리
                wasStationaryMap.remove(deviceId)
                recedingStartMap.remove(deviceId)
                recedeRefMap.remove(deviceId)
                recedePeakMap.remove(deviceId)
                // [v1.1.51 이탈쿨다운 보존] trackingStateMap/departingStartMap 을 지우지 않고 DEPARTING·now 로
                //   재설정 — 재획득 시 applyDepartingHysteresis 5s 억제가 '위험→경고 이탈' 경고범위 통과의
                //   재발령을 덮는다. 이탈정리는 곧 '확정 이탈' 신호이므로 쿨다운 앵커를 이 순간(now)에 맞춘다
                //   (상태가 아직 CROSSING 이어도 강제 DEPARTING 화 → 견고). 5s 안에 되돌아오면 속도게이트가 즉시 해제.
                trackingStateMap[deviceId] = TrackingState.DEPARTING
                departingStartMap[deviceId] = now
                crossingStartMap.remove(deviceId)         // CROSSING 앵커는 불필요(DEPARTING 확정)
                approachStreakStartMap.remove(deviceId)   // [v1.0.46 #4]
                fastApproachStreakMap.remove(deviceId)    // [v1.1.21]
                forwardBiasLatchMap.remove(deviceId)      // [v1.1.11 C1] 이탈 정리 → 래치 리셋
                fx.clearDwellMute(deviceId)                  // (v1.1.61) 이탈 확정 = 존 이탈 — dwell 뮤트 리셋
                deviceRssiMap.remove(deviceId)
                firebaseLastSaveMap.remove(deviceId)
                pendingDisplayMap.remove(deviceId)   // [v1.0.49 #3]
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    fx.stopVibration()
                    fx.collapseOverlay()
                    fx.activeSoundLevel = BleConstants.LEVEL_SAFE
                } else {
                    fx.resyncSoundToRemaining()  // [v1.1.37 ②] 이탈 확인된 상위 기기 → 남은 최대레벨로 사운드 하향 정합
                    fx.updateFloatingOverlay()   // 남은 위험 기기로 플로팅 갱신
                }
                fx.sendStatusBroadcast("↗ 이탈 확인 → 경보 해제: ${fx.extractDisplayName(deviceId)}")
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
            && (fx.recentPeakRssi(deviceId, 500L) ?: avg1sec) >= effWarning) {      // [v1.1.14] 피크 게이트=경고거리 설정값(effWarning) 연동
            // (v1.1.40) 섀도우 TTC 예비 후보 — 메인 칼만 후보 불성립(느린 수렴·접근속도 미달) 시,
            //   내가 정지 + 상대 FORWARD 페이로드 + 섀도우 접근 3프레임 연속이면 섀도우 칼만의
            //   (거리,속도)로 한 번 더 시도. 게이트(WARNING·avg1sec·피크)는 이미 위에서 통과한 프레임.
            var ttc = estimateTTC(kfRssi, kfVel)
            if (ttc == null) {
                val sh2 = if (DevSettings.imuShadowFusionEnabled && payloadPresent &&
                    ImuFusion.isStationary) shadowFusionMap[deviceId] else null
                if (sh2 != null && sh2.apprStreak >= 3)
                    ttc = estimateTTC(sh2.kf.estimatedRssi, sh2.kf.estimatedVel)
            }
            if (ttc != null && ttc <= TTC_THRESHOLD_SEC) {
                alertState[deviceId] = Pair(BleConstants.LEVEL_DANGER, now)  // ★ 먼저 업데이트 → 목록에 DANGER 반영
                pendingDisplayMap.remove(deviceId)   // [v1.0.49 #3] 경보 등록 → 보류 표시 해제
                // (v1.1.65) TTC 로 새로 접근이 감지된 기기는 ACK 뮤트(개별·전체)를 뚫고 재알림한다.
                //   계획서 2-2 "단, TTC 로 새로 접근 감지된 항목은 뮤트 중에도 재알림".
                mutedDevices.remove(deviceId)
                Log.w(TAG, "TTC 선발령: $deviceId TTC=%.1fs kfVel=%.2fdBm/s".format(ttc, kfVel))
                fx.forceAlarmVolume()
                // [v1.0.48 #4] TTC 급접근 진동 — 보행자·EPJ(작업자)는 전용 빠른 패턴(vibrateRapidApproach)으로
                //   일반 DANGER 진동과 촉각 구분(장비가 나에게 돌진 중 = 즉시 회피 신호). 지게차 운전자는
                //   기존 강패턴(vibrateDanger) 유지 — 운전 중 과도한 패턴 분화는 혼란만 준다.
                if (DevSettings.vibrationEnabled) {                                     // [v1.0.46 #7] 포그라운드에서도 진동
                    if (fx.myCategory == BleConstants.CAT_FORKLIFT) fx.vibrateDanger()
                    else fx.vibrateRapidApproach()
                }
                if (DevSettings.soundEnabled)     fx.playDanger()
                fx.activeSoundLevel = BleConstants.LEVEL_DANGER   // [v1.0.46 #2] 사이렌 레벨 동기
                fx.updateFloatingOverlay()
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
                fx.sendStatusBroadcast("충돌 예측 %.0f초: ${fx.extractDisplayName(deviceId)}".format(ttc))
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
            //   배경: 직전 이탈 episode 의 즉시 stopSound(L1100)로 fx.activeSoundLevel=SAFE 가 된 뒤, 기기가
            //   여전히/다시 위험권(stableLevel==DANGER)인데 쿨다운 미경과로 shouldAlert=false 면 최대 한
            //   쿨다운(~2초) 무음이 생긴다. 이 경로를 '추적 중 + 평활 권위값 위험권 + 위험음 미만'일 때 즉시
            //   위험 재발령으로 닫는다(쿨다운 무시). 정규 발령(canonical)은 이 return 이후라 상호배타 →
            //   playDanger 중복호출(비멱등 stutter) 없음.
            //   [v1.1.6 DS-1/3] 판정 기준을 raw avg1sec → 평활 stableLevel 로 통일. (a) canonical 과 동일한
            //   거리 권위값(pEma 기반 stableLevel)을 써, '평활은 DANGER 인데 raw 노이즈 dip 으로 무음'이던
            //   불일치(DS-3)를 제거한다. (b) isReceding 가드도 stableLevel<DANGER(L1085)라, 이탈 프레임은
            //   여기 stableLevel>=DANGER 와 정확한 여집합으로 상호배타 → 진짜 이탈 즉시정지는 유지되고,
            //   genuine 이탈로 stableLevel 이 이미 위험권 밖이면 복구가 되살리지 않아 ghost-danger 과알람도 없다.
            if (!fx.isMuted && !fx.isDeviceMuted(deviceId) && alertState.containsKey(deviceId) &&
                !fx.isDwellMuted(deviceId, stableLevel) &&   // (v1.1.61) dwell 뮤트 존중 — 의도된 무음은 '복구'하지 않는다
                !fx.myZoneInside &&                          // (v1.1.62) 존 안=가청 억제 — fail-loud 복구도 되살리지 않는다
                stableLevel >= BleConstants.LEVEL_DANGER && !isDepartingNow &&   // [v1.1.22 B] 이탈측 무음복구 재발령 금지
                fx.activeSoundLevel < BleConstants.LEVEL_DANGER) {
                fx.forceAlarmVolume()
                fx.activeSoundLevel = BleConstants.LEVEL_DANGER
                if (DevSettings.vibrationEnabled) fx.vibrateDanger()
                if (DevSettings.soundEnabled)     fx.playDanger()
                fx.updateFloatingOverlay()
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
            else if (!fx.isMuted && !fx.isDeviceMuted(deviceId) && alertState.containsKey(deviceId) &&
                     fx.activeSoundLevel >= BleConstants.LEVEL_DANGER && stableLevel < fx.activeSoundLevel) {
                val otherMax = alertState.entries
                    .filter { it.key != deviceId && !fx.isDwellMuted(it.key, it.value.first) }   // (v1.1.61) 뮤트 기기는 소리 소유권 없음
                    .maxOfOrNull { it.value.first } ?: BleConstants.LEVEL_SAFE
                if (otherMax < fx.activeSoundLevel) {
                    AlertSoundPlayer.stopSound()
                    fx.activeSoundLevel = stableLevel
                    if (stableLevel == BleConstants.LEVEL_WARNING && !idleIdleQuiet &&
                        !fx.isDwellMuted(deviceId, BleConstants.LEVEL_WARNING) &&   // (v1.1.61) WARNING dwell 뮤트 시 재발령 생략(강등 정지는 유지)
                        !fx.myZoneInside) {   // (v1.1.62) 존 안=가청 억제 — 정정 재발령도 생략(정지는 유지)
                        if (DevSettings.vibrationEnabled) fx.vibrateWarning()
                        if (DevSettings.soundEnabled)     fx.playWarning()
                    }
                    fx.updateFloatingOverlay()
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
        // [v1.1.58 fix4] lost→재발견 복원 기기는 TimeGate 1회 면제 — 면제권은 도달 즉시 무조건 소비(잔존 방지)
        val timeGateWaived = timeGateWaiveSet.remove(deviceId)
        if (isFirstDetection && !fastContact && !timeGateWaived && (sideCourse || !approachSustained)) {   // [v1.1.18] 2프레임 확증 WARNING/DANGER 첫접촉은 접근속도 게이트 면제(정지 근접 즉시 발령)
            pendingDisplayMap[deviceId] = now   // [v1.0.49 #3] 보류 중에도 목록엔 '감지됨' 노출
            Log.d(TAG, "[v1.0.36] 경보 보류 ${fx.extractDisplayName(deviceId)}: side=$sideCourse 접근지속=${approachStreakMs}ms(<${timeGateMs}) fast=${fastApproachFrames}/2 vel=%.2f".format(kfVel))
            return   // 소리/화면 경보 보류 — 다음 프레임 재평가(접근지속 충족 또는 정면충돌 코스 시 발령)
        }

        pendingDisplayMap.remove(deviceId)   // [v1.0.49 #3] 게이트 통과 → 보류 표시 해제(아래에서 경보 등록)

        alertState[deviceId] = Pair(stableLevel, now)
        if (fx.isMuted) return

        // (v1.1.61) 항목4 dwell 뮤트 게이트 — 이 기기·레벨이 5s 체류 뮤트면 소리·진동만 생략(표시·
        //   브로드캐스트·Firebase 등 나머지 발령 레시피는 그대로). 승인 예외: 빠른 접근(kfVel≥2.0,
        //   urgentBypass 의 속도항)은 뮤트 무시. median>=effDanger 항까지 쓰면 위험권에 '정지'한
        //   기기가 매 프레임 바이패스돼 영원히 안 뮤트("위험 거리도 동일하게" 스펙 무력화)라 속도항만.
        // (v1.1.62) || fx.myZoneInside — 존 비콘 접촉 중엔 무조건 가청 억제(urgentBypass 의 속도항도 안 뚫음).
        val dwellSuppressed = (fx.isDwellMuted(deviceId, stableLevel) && kfVel < 2.0) || fx.myZoneInside
        if (!dwellSuppressed) fx.forceAlarmVolume()
        val globalMax = fx.getAudibleMaxLevel()   // (v1.1.61) 뮤트 기기 제외 — 무음 기기가 신규 경보를 못 막게
        if (stableLevel < globalMax) {
            Log.d(TAG, "우선순위 무시: $stableLevel < $globalMax (활성)")
            return
        }
        // [v1.1.28] 격상뿐 아니라 강등(stableLevel<active)에서도 stale 상위 사이렌을 멈춘다.
        //   기존 '>' 는 격상만 멈춰, demoteWhileStationary·히스테리시스로 DANGER→WARNING 격하 시
        //   재생 중인 DANGER 루프(AlertSoundPlayer)가 안 꺼졌다. 아래 WARNING 분기의 playWarning 은
        //   danger 루프가 isPlaying 인 동안 no-op 이라 소리가 WARNING 으로 바뀌지도 못하고, fx.activeSoundLevel
        //   만 WARNING 으로 낮춰져 위험 사이렌이 영구 지속됐다(사용자: '위험 알림이 꺼지지 않아').
        //   '!=' 로 강등도 정지 — !shouldAlert 의 fail-quiet(쿨다운 미경과 프레임) 와 쌍을 이룬다.
        if (!dwellSuppressed) {
            if (stableLevel != fx.activeSoundLevel) AlertSoundPlayer.stopSound()
            fx.activeSoundLevel = stableLevel
        } else {
            // (v1.1.61) 억제 중 잔존 사이렌 정리 — 직전 kfVel 바이패스 발령 등으로 이 기기가 소리를
            //   점유한 채 다시 억제되면 가청 최대레벨로 재정합(비뮤트 기기 소리는 건드리지 않음 = 내부 no-op).
            fx.resyncSoundToRemaining()
        }

        when (stableLevel) {
            // [v1.0.46 #1] 거리 기반 DANGER 커밋 분기 복원 — v1.0.20 재작성에서 사라진 회귀.
            //   서행 접근(TTC 미발동·특수상태 아님)도 위험권 진입이면 위험 경보+Firebase 기록.
            BleConstants.LEVEL_DANGER -> {
                if (DevSettings.vibrationEnabled && !dwellSuppressed)
                    fx.vibrateDanger()
                if (DevSettings.soundEnabled && !dwellSuppressed)
                    fx.playDanger()
                if (DevSettings.autoSaveAlerts) {
                    val lastFbSave = firebaseLastSaveMap[deviceId] ?: 0L
                    if (now - lastFbSave >= FIREBASE_SAVE_THROTTLE_MS) {
                        firebaseLastSaveMap[deviceId] = now
                        FirebaseManager.saveAlert(deviceId, fx.myId, avgRssi, "DANGER")
                    }
                }
                val name = fx.extractDisplayName(deviceId)
                fx.updateFloatingOverlay()
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
                Log.w(TAG, "위험 발생: $deviceId ($name) avgRssi=$avgRssi state=$newState vel=%.2fdBm/s".format(kfVel))
            }
            BleConstants.LEVEL_WARNING -> {
                // [v1.1.10 Phase2] IDLE-IDLE 가청 억제 — 내 IMU 정지 + 상대 IDLE 송신(둘 다 정지)이면
                //   가청(진동·소리)만 억제하고 표시·오버레이·목록·위젯은 그대로 유지한다. DANGER 는
                //   여기로 오지 않는다(정지 시 demoteWhileStationary 가 DANGER→WARNING 격하 → 항상 WARNING).
                //   둘 중 하나라도 움직이면 다음 프레임 idleIdleQuiet=false 로 즉시 가청 복원. 기본 OFF(옵트인).
                if (DevSettings.vibrationEnabled && !idleIdleQuiet && !dwellSuppressed)   // [v1.0.46 #7] 포그라운드(화면 켜짐)에서도 진동
                    fx.vibrateWarning()
                if (DevSettings.soundEnabled && !idleIdleQuiet && !dwellSuppressed)
                    fx.playWarning()
                // [v1.0.30 Req3] Firebase 경보 저장 쓰로틀 — 같은 기기 1분 1회로 제한(모바일데이터 방어)
                if (DevSettings.autoSaveAlerts) {
                    val lastFbSave = firebaseLastSaveMap[deviceId] ?: 0L
                    if (now - lastFbSave >= FIREBASE_SAVE_THROTTLE_MS) {
                        firebaseLastSaveMap[deviceId] = now
                        FirebaseManager.saveAlert(deviceId, fx.myId, avgRssi, "WARNING")
                    }
                }
                val name = fx.extractDisplayName(deviceId)
                fx.updateFloatingOverlay()
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_WARNING)
                Log.d(TAG, "경고 발생: $deviceId ($name) avgRssi=$avgRssi state=$newState vel=%.2fdBm/s".format(kfVel))
            }
        }
    }

    fun judgeUwbOnly(deviceId: String, uwbD: Float, now: Long) {
        // (v1.1.62 버그A) walker 게이트 이중 안전 — 세션 개설 차단(onUwbAddressReceived)이 1차지만,
        //   이미 열린 세션의 잔여 표본이 이 경로로 들어와도 걸러진 기기를 되살리지 않는다.
        if (fx.myMode == "WALKER" && deviceId.startsWith(BleConstants.WALKER_PREFIX)
            && !deviceId.contains("BEA_") && !DevSettings.walkerDetectsWalker) return
        val rCategory = deviceCategoryMap[deviceId]
        val rState    = deviceStateMap[deviceId]
        val forkliftPair = fx.myCategory == BleConstants.CAT_FORKLIFT ||
            rCategory == BleConstants.CAT_FORKLIFT
        val warnM = if (forkliftPair) DevSettings.uwbForkliftWarnMeters   else DevSettings.uwbPairWarnMeters
        val dangM = if (forkliftPair) DevSettings.uwbForkliftDangerMeters else DevSettings.uwbPairDangerMeters

        val prev      = alertState[deviceId]
        val prevLevel = prev?.first ?: BleConstants.LEVEL_SAFE

        // 레벨 산출 — 유지 중이면 히스테리시스(+0.5m)로 경계 진동(깜빡임) 억제
        val rawLevel = when {
            uwbD <= dangM ||
                (prevLevel >= BleConstants.LEVEL_DANGER && uwbD <= dangM + UWB_RELEASE_HYST_M) ->
                BleConstants.LEVEL_DANGER
            uwbD <= warnM ||
                (prevLevel >= BleConstants.LEVEL_WARNING && uwbD <= warnM + UWB_RELEASE_HYST_M) ->
                BleConstants.LEVEL_WARNING
            else -> BleConstants.LEVEL_SAFE
        }

        // 격상·유지 = 표본 1개 즉시 반영. 격하 = 연속 표본 확증(단발 튐 방어) 또는 이탈 운동학 즉시.
        var stableLevel: Int
        if (rawLevel >= prevLevel) {
            uwbSafeStreakMap[deviceId] = 0
            stableLevel = rawLevel
        } else {
            val streak = (uwbSafeStreakMap[deviceId] ?: 0) + 1
            uwbSafeStreakMap[deviceId] = streak
            val kin = fx.uwbRanger?.uwbKinematics?.get(deviceId)
            val separating = kin != null && now - kin.atMs <= 1500L &&
                kin.separatingStreak >= 3 && kin.closingMps < 0f
            if (streak >= UWB_DEMOTE_STREAK || separating) {
                uwbSafeStreakMap[deviceId] = 0
                stableLevel = rawLevel
            } else {
                stableLevel = prevLevel   // 격하 보류 — 확증 대기(FREQUENT 기준 ~0.4s)
            }
        }

        // (v1.1.62) 피어 IN_ZONE 무해 클램프 — canonical 미러(억제 전용 — 격상 방향 오버라이드 없음).
        //   ★ uwbSafeStreakMap 리셋 금지 — 리셋하면 아래 SAFE 격하 확증(3표본)이 영영 차단된다.
        if (peerInZoneMap[deviceId] == true && stableLevel > BleConstants.LEVEL_SAFE)
            stableLevel = BleConstants.LEVEL_SAFE

        // 특수경보 — 후진/상하차 중 기기가 경고 반경 내 실측이면 즉시 DANGER (canonical 특수경보 미러)
        if (rCategory != null && rState != null &&
            (rState == BleConstants.PSTATE_REVERSE || rState == BleConstants.PSTATE_LOADING) &&
            uwbD <= warnM &&
            peerInZoneMap[deviceId] != true) {   // (v1.1.62) 상대 IN_ZONE 선언=무해 — 특수경보 진입 자체 차단
            suddenLabelMap[deviceId] = fx.makeStateLabel(fx.extractDisplayName(deviceId), rCategory, rState)
            alertState[deviceId] = Pair(BleConstants.LEVEL_DANGER, now)
            pendingDisplayMap.remove(deviceId)
            fx.bleScanner?.setEcoMode(false)
            fx.updateDwellMute(deviceId, BleConstants.LEVEL_DANGER, now)   // (v1.1.61) 특수경보도 체류 추적
            if (fx.isMuted || fx.isDeviceMuted(deviceId) ||
                fx.isDwellMuted(deviceId, BleConstants.LEVEL_DANGER) ||
                fx.myZoneInside) {   // (v1.1.62) 존 안=가청 억제(상태·표시는 위에서 이미 반영)
                fx.updateFloatingOverlay(); return
            }
            fx.forceAlarmVolume()
            if (DevSettings.vibrationEnabled) fx.vibrateDanger()
            if (DevSettings.soundEnabled)     fx.playDanger()
            fx.activeSoundLevel = BleConstants.LEVEL_DANGER
            fx.updateFloatingOverlay()
            fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
            fx.sendStatusBroadcast("${suddenLabelMap[deviceId]} UWB ${"%.1f".format(uwbD)}m")
            return
        }
        suddenLabelMap.remove(deviceId)

        // (v1.1.61) 항목4 dwell 추적 — canonical(processAlert)과 동일 규칙의 UWB 미러.
        if (stableLevel >= BleConstants.LEVEL_WARNING && alertState.containsKey(deviceId))
            fx.updateDwellMute(deviceId, stableLevel, now)

        // SAFE — canonical SAFE 정리 미러(1회성: alertState 보유 기기만). 필터류는 RSSI 헤드가
        //   계속 워밍 중이므로 지워도 수 프레임 내 재수렴 — 폴백 무봉합 유지.
        if (stableLevel == BleConstants.LEVEL_SAFE) {
            uwbSafeStreakMap.remove(deviceId)
            if (alertState.containsKey(deviceId)) {
                alertState.remove(deviceId)
                fx.rssiPreFilter.clear(deviceId)
                fx.medianFilter.clear(deviceId)
                fx.pEmaFilter.clear(deviceId)
                rushFrameMap.remove(deviceId)
                dangerContactStreakMap.remove(deviceId)
                warningContactStreakMap.remove(deviceId)
                warningMissRefMap.remove(deviceId)
                kalmanFilters.remove(deviceId)
                lastKfVelMap.remove(deviceId)   // (v1.1.56 U3) UWB 확증 SAFE — 재시드 불필요, 스냅샷 폐기
                shadowFusionMap.remove(deviceId)
                trackingStateMap.remove(deviceId)
                crossingStartMap.remove(deviceId)
                departingStartMap.remove(deviceId)
                approachStreakStartMap.remove(deviceId)
                fastApproachStreakMap.remove(deviceId)
                forwardBiasLatchMap.remove(deviceId)
                fx.clearDwellMute(deviceId)   // (v1.1.61) UWB 확증 SAFE = 존 이탈 — dwell 뮤트 리셋
                peerInZoneMap.remove(deviceId)   // (v1.1.62) SAFE 정리 — 다음 광고 표본이 재선언(스테일 캐시 방지)
                wasStationaryMap.remove(deviceId)
                recedingStartMap.remove(deviceId)
                recedeRefMap.remove(deviceId)
                recedePeakMap.remove(deviceId)
                deviceRssiMap.remove(deviceId)
                mutedDevices.remove(deviceId)
                suddenLabelMap.remove(deviceId)
                deviceCategoryMap.remove(deviceId)
                deviceStateMap.remove(deviceId)
                deviceTurnMap.remove(deviceId); reverseRssiHist.remove(deviceId); reversePrepUntil.remove(deviceId)
                firebaseLastSaveMap.remove(deviceId)
                pendingDisplayMap.remove(deviceId)
                // ★ uwbSampleAtMsMap 은 보존 — Case A 신선도 근거(지우면 다음 표본까지 순간 RSSI 폴백). peerUwbSeenMap 은 진단용 보존
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    fx.activeSoundLevel = BleConstants.LEVEL_SAFE
                    fx.stopVibration()
                    fx.collapseOverlay()
                } else {
                    fx.resyncSoundToRemaining()
                    fx.updateFloatingOverlay()
                }
            }
            return
        }

        // 비-SAFE — 전투 모드 보장(canonical 미러)
        fx.bleScanner?.setEcoMode(false)

        // 정지↔정지 저감(idle-idle quiet) — UWB 피어는 페이로드 캐시가 항상 있으므로 캐시로 재계산
        // [v1.1.59] 역할쌍 확장(RSSI 게이트와 동일 의미론) — EPJ↔EPJ·EPJ↔보행자 쌍 기본 ON, 지게차 포함 쌍 제외.
        val epjQuietPair = !forkliftPair &&
            (fx.myCategory == BleConstants.CAT_EPJ || rCategory == BleConstants.CAT_EPJ)
        val quietArmed = DevSettings.idleIdleSuppressEnabled ||
            (DevSettings.idleIdleSuppressEpjPairsEnabled && epjQuietPair)
        val idleIdleQuiet = quietArmed && ImuFusion.isStationary &&
            rState == BleConstants.PSTATE_IDLE

        // 무음 중 — 레벨 추적만 유지(발령 시각 보존, canonical 뮤트 미러)
        if (fx.isMuted || fx.isDeviceMuted(deviceId)) {
            alertState[deviceId] = Pair(stableLevel, prev?.second ?: now)
            pendingDisplayMap.remove(deviceId)
            return
        }

        val lastAlertTime    = prev?.second ?: 0L
        val baseCooldown     = if (stableLevel == BleConstants.LEVEL_DANGER) DANGER_COOLDOWN_MS else WARNING_COOLDOWN_MS
        val isFirstDetection = prev == null
        val levelEscalated   = stableLevel > prevLevel
        val cooldownPassed   = now - lastAlertTime >= baseCooldown

        if (!(isFirstDetection || levelEscalated || cooldownPassed)) {
            // 비발령 표본 — 레벨만 갱신(발령 시각 보존) + fail-quiet 강등 정정(canonical 미러)
            alertState[deviceId] = Pair(stableLevel, lastAlertTime)
            if (fx.activeSoundLevel >= BleConstants.LEVEL_DANGER && stableLevel < fx.activeSoundLevel) {
                val otherMax = alertState.entries
                    .filter { it.key != deviceId && !fx.isDwellMuted(it.key, it.value.first) }   // (v1.1.61) 뮤트 기기는 소리 소유권 없음
                    .maxOfOrNull { it.value.first } ?: BleConstants.LEVEL_SAFE
                if (otherMax < fx.activeSoundLevel) {
                    AlertSoundPlayer.stopSound()
                    fx.activeSoundLevel = stableLevel
                    if (stableLevel == BleConstants.LEVEL_WARNING && !idleIdleQuiet &&
                        !fx.isDwellMuted(deviceId, BleConstants.LEVEL_WARNING) &&   // (v1.1.61) WARNING dwell 뮤트 시 재발령 생략(강등 정지는 유지)
                        !fx.myZoneInside) {   // (v1.1.62) 존 안=가청 억제 — 정정 재발령도 생략(정지는 유지)
                        if (DevSettings.vibrationEnabled) fx.vibrateWarning()
                        if (DevSettings.soundEnabled)     fx.playWarning()
                    }
                    fx.updateFloatingOverlay()
                }
            }
            return
        }

        // 발령 — canonical 발령 레시피 미러
        pendingDisplayMap.remove(deviceId)
        alertState[deviceId] = Pair(stableLevel, now)
        // (v1.1.61) 항목4 dwell 뮤트 게이트 — canonical 미러. UWB 경로엔 kfVel(RSSI 칼만 속도)이
        //   없으므로 바이패스 유사물을 발명하지 않는다(스펙 재해석 금지) — 뮤트면 소리·진동만 생략.
        // (v1.1.62) || fx.myZoneInside — 존 비콘 접촉 중엔 무조건 가청 억제.
        val dwellSuppressed = fx.isDwellMuted(deviceId, stableLevel) || fx.myZoneInside
        if (!dwellSuppressed) fx.forceAlarmVolume()
        val globalMax = fx.getAudibleMaxLevel()   // (v1.1.61) 뮤트 기기 제외
        if (stableLevel < globalMax) return   // 더 높은 경보 재생 중 — 소리 격하 금지(우선순위)
        if (!dwellSuppressed) {
            if (stableLevel != fx.activeSoundLevel) AlertSoundPlayer.stopSound()
            fx.activeSoundLevel = stableLevel
        } else {
            fx.resyncSoundToRemaining()   // (v1.1.61) 잔존 사이렌 정리 — 비뮤트 소리는 불간섭(내부 no-op)
        }
        when (stableLevel) {
            BleConstants.LEVEL_DANGER -> {
                if (DevSettings.vibrationEnabled && !dwellSuppressed) fx.vibrateDanger()
                if (DevSettings.soundEnabled && !dwellSuppressed)     fx.playDanger()
                if (DevSettings.autoSaveAlerts) {
                    val lastFbSave = firebaseLastSaveMap[deviceId] ?: 0L
                    if (now - lastFbSave >= FIREBASE_SAVE_THROTTLE_MS) {
                        firebaseLastSaveMap[deviceId] = now
                        FirebaseManager.saveAlert(deviceId, fx.myId, deviceRssiMap[deviceId] ?: 0, "DANGER")
                    }
                }
                fx.updateFloatingOverlay()
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
                Log.w(TAG, "UWB 위험 발생: $deviceId ${"%.1f".format(uwbD)}m (forkliftPair=$forkliftPair)")
            }
            BleConstants.LEVEL_WARNING -> {
                if (DevSettings.vibrationEnabled && !idleIdleQuiet && !dwellSuppressed) fx.vibrateWarning()
                if (DevSettings.soundEnabled && !idleIdleQuiet && !dwellSuppressed)     fx.playWarning()
                if (DevSettings.autoSaveAlerts) {
                    val lastFbSave = firebaseLastSaveMap[deviceId] ?: 0L
                    if (now - lastFbSave >= FIREBASE_SAVE_THROTTLE_MS) {
                        firebaseLastSaveMap[deviceId] = now
                        FirebaseManager.saveAlert(deviceId, fx.myId, deviceRssiMap[deviceId] ?: 0, "WARNING")
                    }
                }
                fx.updateFloatingOverlay()
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_WARNING)
                Log.d(TAG, "UWB 경고 발생: $deviceId ${"%.1f".format(uwbD)}m (forkliftPair=$forkliftPair)")
            }
        }
    }
}
