package com.wf11.safealert.ble

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import kotlin.math.abs

class BleAdvertiser(
    private val advertiser: BluetoothLeAdvertiser,
    private val prefix: String = BleConstants.DEVICE_PREFIX,
    // [v1.0.34] 송신자 역할(Category) — 1바이트 페이로드 bits[1:0] 에 패킹된다.
    //   보행자=CAT_WALKER / EPJ=CAT_EPJ / 지게차=CAT_FORKLIFT
    private val category: Int = BleConstants.CAT_WALKER,
    private val onStatusUpdate: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "BleAdvertiser"
        // [v1.0.29] 상태 갱신 최소 주기 — 안드로이드 OS Advertising Rate-Limit 회피
        //   [v1.1.7 #1] STATE 와 Turn(bits 3:2) 재광고가 이 throttle 을 '공유'한다.
        private const val MIN_STATE_UPDATE_INTERVAL_MS = 1000L   // [v1.1.18] 2000→1000 디바이스 간 STATE/TURN 전파 가속(광고 stop/start 1s 간격=LOW_POWER 주기와 동급, OS Rate-Limit 안전선)
        // [v1.1.14] 위험상태(RISK) 전용 최소 재광고 간격 — STATE/TURN(1초)과 독립.
        //   위험은 안전 critical 이라 빠르게 전파한다: 상승(위험↑)은 이 간격도 무시하고 즉시 재광고,
        //   하강(위험↓)만 0.5초 최소간격으로 stop/start 폭주(레벨 토글)를 막는다.
        private const val MIN_RISK_UPDATE_INTERVAL_MS = 500L
        // 상태 변경 시 stopAdvertising 후 재시작까지 대기 (OS 정리 시간 확보)
        private const val STATE_RESTART_DELAY_MS = 50L
        // [v1.0.43 Req3] (구) 하트비트 버스트 상수 폐지 — 슬립을 'stop + 700ms/4s 버스트'에서
        //   'LOW_POWER(~1s) 연속 광고'로 전환(깨어남 지연 제거). 완전 정지하지 않고 저빈도로 상시
        //   송출하므로 상대 스캐너가 항상 나를 잡아 즉시 재발견한다. 재광고 정리 대기는 STATE_RESTART_DELAY_MS 공용.

        // [v1.0.48 #5] 죽은 설정이던 '광고 간격(advertiseInterval)'을 활성 광고 모드에 매핑.
        //   안드로이드 공개 광고 API 는 임의 ms 간격을 받지 않고 3단 프리셋만 허용하므로
        //   설정값을 가장 가까운 프리셋으로 양자화한다(스피너: 100/200/500/1000ms).
        //     ≤100ms → LOW_LATENCY(~100ms) / ≤250ms → BALANCED(~250ms — 기본 200,
        //     v1.0.37 거동 보존) / 초과 → LOW_POWER(~1000ms)
        private fun mapAdvertiseMode(intervalMs: Int): Int = when {
            intervalMs <= 100 -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            intervalMs <= 250 -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            else              -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        }
    }

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "광고 시작 성공 ✓")
            onStatusUpdate?.invoke("TX 송출 확인됨 ✓")
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
            if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) {
                onStatusUpdate?.invoke("❌ TX 실패: $reason")
            }
        }
    }

    // 현재 광고 중인 deviceId (UWB 재시작용)
    private var currentDeviceId = ""

    // [v1.0.41] 중지 가드 — stopAdvertising() 호출 후, 메인 루퍼에 예약돼(postDelayed) 있던
    //   재광고 콜백이 광고를 되살리는 누수를 차단. 한 번 true 가 되면 이 인스턴스는 다시
    //   광고하지 않는다(재시작은 BleService 가 BleAdvertiser 를 새로 생성하므로 안전).
    @Volatile private var stopped = false

    // [v1.0.42 Req3 · v1.0.43] RSSI 동적 슬립 가드 — stopped(영구 종료)와 '독립'. paused=true 면
    //   BALANCED 연속 광고를 LOW_POWER 연속 광고로 낮춰 상시 송출한다(완전 정지 아님). startAdvertising 이
    //   이 플래그를 보고 광고 모드를 자동 선택한다. resumeAdvertising() 으로 즉시(0ms) BALANCED 로 승격.
    //   ※ stopped 와 분리해야 함: stopped 는 단방향 종료(재광고 영구 차단)이므로 슬립/웨이크에 쓰면
    //     한 번 자면 다시 못 깨는 데드락이 된다 → 별도 플래그(paused)로 일시정지/재개를 구현한다.
    @Volatile private var paused = false

    /** [v1.0.42 Req3] 현재 RSSI 슬립(일시정지) 상태인지 — BleService 의 전력관리 평가가 참조. */
    val isPaused: Boolean get() = paused

    // [v1.0.48 #5] 마지막으로 OS 에 실제 적용한 광고 모드 — refreshAdvertiseMode() 의 no-op 가드용.
    //   prefs 리스너는 설정 저장 시 키마다 불리므로(한 번에 ~12회) 모드가 실제로 바뀐 경우에만
    //   재광고해 stop/start 폭주를 막는다. -1 = 아직 광고 시작 전.
    @Volatile private var lastAppliedAdvertiseMode: Int = -1

    // [v1.1.26] 경고권 진입 버스트 — 상대 근접(rssi≥WAKE) 수신 시 내 광고를 LOW_LATENCY(~100ms)로
    //   가속해 상대가 나를 더 빨리 발견(상호 보호). 이 시각(elapsedRealtime ms)까지 LOW_LATENCY 유지.
    //   0 = 버스트 없음. requestBurst() 가 설정, burstExpiryRunnable 이 만료 시 정상 모드로 복귀.
    @Volatile private var burstUntilMs = 0L

    // [v1.0.34 다이나믹 페이로드] 현재 송신자 STATE(2bit, PSTATE_*) — Category·Turn 와 함께
    //   encodePayload() 로 1바이트로 패킹되어 ServiceData 로 탑재된다.
    @Volatile private var currentState: Int = BleConstants.PSTATE_IDLE
    // [v1.1.7 #1] 현재 송신 회전 방향(TURN_*, bits 3:2) — startAdvertising 이 Turn 2비트로 패킹해 싣는다.
    //   BleService 가 ImuFusion.turnDirection 을 주기적으로 updateTurn() 으로 밀어 넣는다.
    @Volatile private var currentTurnDir: Int = BleConstants.TURN_STRAIGHT
    // [v1.1.14] 현재 송신 위험상태(RISK 2bit, LEVEL_*) — encodePayload bits[1:0] 에 패킹.
    //   BleService 가 자신의 alertState 최대 경보레벨을 updateRisk() 로 주기적으로 민다.
    //   상대 수신단이 decodeRisk 로 풀어 '자신 RSSI 게이트와 결합'(절충)해 경보를 격상 → 양방향 협력 알림.
    @Volatile private var currentRisk: Int = BleConstants.LEVEL_SAFE
    // [v1.0.36] STATE·Speed 재광고 공용 throttle 타임스탬프 (구 lastStateUpdateMs)
    private var lastPayloadUpdateMs = 0L
    // [v1.1.14] 위험상태(RISK) 전용 throttle 타임스탬프 — STATE/TURN throttle 과 독립.
    private var lastRiskUpdateMs = 0L
    // 재광고 시 UWB 주소 유지 (updateState / restartWithUwbAddress 공용)
    private var lastUwbAddress: ByteArray? = null
    private val stateHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // [v1.0.51 #1] Rate-Limit 드롭 보완 — throttle 에 걸려 적용 못 한 최신 STATE 보류 저장(-1=없음).
    //   IMU 모션 통지는 '전이 순간'에만 오므로 한 번 드롭되면 다음 전이까지 낡은 상태로 고착된다
    //   (예: 잠깐 정지 후 2초 내 출발 → FORWARD 드롭 → 이동 내내 IDLE 송출 = 상대에겐 '대기 중').
    //   보류해 두고 잔여 시간 뒤 자동 재시도해 고착을 끊는다. 모든 갱신 경로가 메인 루퍼 → 락 불필요.
    @Volatile private var pendingState = -1
    private val pendingStateRunnable = object : Runnable {
        override fun run() {
            val s = pendingState
            if (s < 0) return
            if (s == currentState) { pendingState = -1; return }   // 그 사이 동일 상태로 수렴 → 재광고 불필요
            val now = SystemClock.elapsedRealtime()
            val remain = MIN_STATE_UPDATE_INTERVAL_MS - (now - lastPayloadUpdateMs)
            if (remain > 0) {
                // 그 사이 속도 재광고가 throttle 타임스탬프를 갱신 → 잔여 시간만큼 재대기
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

    // ── [v1.0.42 Req2] 현재 '송출 중'인 로컬 상태 읽기 전용 노출 — 내 장비(Local) UI 표시 전용 ──
    //   BleService 가 이 값들로 LocalState 를 구성해 MainActivity 에 전파한다.
    //   수신(Target) 경로와 완전히 분리 — 외부에서 쓰기 불가(읽기 전용 getter).
    val txCategory: Int  get() = category
    val txState: Int     get() = currentState
    val txTurnDir: Int   get() = currentTurnDir
    val txRisk: Int      get() = currentRisk      // [v1.1.14] 현재 송출 중인 위험상태(LEVEL_*)

    /**
     * @param uwbLocalAddress 이 기기의 UWB 주소 2바이트 (null = UWB 미지원/미초기화)
     */
    fun startAdvertising(deviceId: String, uwbLocalAddress: ByteArray? = null) {
        // [v1.0.41] 예약 콜백이 stop 후 뒤늦게 실행돼도 여기서 차단.
        if (stopped) { Log.d(TAG, "중지 상태 — 광고 시작 생략"); return }
        currentDeviceId = deviceId
        if (uwbLocalAddress != null) lastUwbAddress = uwbLocalAddress
        // [v1.0.43 Req3] 슬립(paused)이면 LOW_POWER(~1s) '연속' 광고로 전환 — 완전 정지 대신
        //   저빈도 상시 송출. 상대 스캐너가 항상(~1s 내) 나를 발견 → 깨어남 지연 제거.
        //   깨어나면(paused=false) 활성 모드로 승격. (구 하트비트 버스트 폐지)
        // [v1.0.48 #5] 활성 모드를 BALANCED 고정에서 설정(advertiseInterval) 매핑으로 전환 —
        //   매 (재)광고 시점에 설정을 다시 읽으므로 슬립/웨이크·STATE/Speed 재광고가 항상 최신값 적용.
        //   슬립은 항상 LOW_POWER 유지(공개 API 최저 주기 — 설정과 무관).
        // [v1.1.26 B] 버스트 우선 — 버스트 활성(burstUntilMs 미래)이면 paused/설정과 무관히
        //   LOW_LATENCY(~100ms)로 송출해 상대가 나를 즉시 발견. 그 외에는 기존 규칙
        //   (슬립=LOW_POWER, 활성=advertiseInterval 매핑) 유지.
        val advertiseMode = when {
            SystemClock.elapsedRealtime() < burstUntilMs -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            paused -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            else -> mapAdvertiseMode(BleConstants.advertiseInterval)
        }
        lastAppliedAdvertiseMode = advertiseMode   // [v1.0.48 #5] refreshAdvertiseMode no-op 판정용

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advertiseMode)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val companyId = if (prefix == BleConstants.DEVICE_PREFIX)
            BleConstants.COMPANY_ID_DEVICE else BleConstants.COMPANY_ID_WALKER

        // ID를 짧게 (최대 14바이트) — 전체 패킷 ≤ 31바이트 유지
        val idBytes = deviceId.toByteArray(Charsets.UTF_8).take(14).toByteArray()

        // ── Primary 광고 패킷 (ServiceUUID 포함 → 화면 꺼짐에도 스캔 필터 작동) ──
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID)))
            // [v1.1.7 #1] 1바이트 ServiceData: Category+State+Turn+Risk(2-2-2-2 패킹) 단일 바이트.
            //   기존 Speed 4비트 폐기 → Turn 2비트(bits 3:2) 탑재.
            //   [v1.1.14] 하위 2비트(예약)→ Risk(위험 감지 상태, bits 1:0) 탑재 — 양방향 협력 알림.
            //   SERVICE_UUID 가 16비트 short UUID(0x1234) 패턴 → ServiceData 약 5바이트.
            .addServiceData(
                ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID)),
                byteArrayOf(
                    BleConstants.encodePayload(category, currentState, currentTurnDir, currentRisk)
                )
            )
            .addManufacturerData(companyId, idBytes)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        // ── UWB 스캔 응답 (지원 시 별도 패킷으로 UWB 주소 공유) ──
        // (v1.1.30) 컨트롤러(DEVICE)=4바이트(주소2+채널+프리앰블), 컨트롤리(WALKER)=2바이트(주소)
        val scanResponse = if (uwbLocalAddress != null && uwbLocalAddress.size >= 2) {
            AdvertiseData.Builder()
                .addManufacturerData(BleConstants.COMPANY_ID_UWB_EXT, uwbLocalAddress.take(4).toByteArray())
                .setIncludeDeviceName(false)
                .build()
        } else null

        if (scanResponse != null) {
            advertiser.startAdvertising(settings, advertiseData, scanResponse, callback)
            Log.d(TAG, "광고+UWB 시작: id=$deviceId uwb=${uwbLocalAddress!!.take(4).joinToString("") { "%02X".format(it) }}")
        } else {
            advertiser.startAdvertising(settings, advertiseData, callback)
            Log.d(TAG, "광고 시작: companyId=0x${companyId.toString(16).uppercase()} id=$deviceId")
        }
    }

    /**
     * [v1.0.34 다이나믹 페이로드] 송신자 STATE(2bit, PSTATE_*) 갱신.
     * 변경이 있을 때만, 그리고 최소 2초 간격(OS Rate-Limit 회피)으로
     * 기존 광고를 멈추고 약 50ms 뒤 새 페이로드(Category+State+Turn)로 다시 켠다.
     * Category 는 생성자 고정, Turn 은 updateTurn() 이 갱신 — 여기선 STATE 만 바뀐다.
     * [v1.0.51 #1] Rate-Limit 에 걸린 갱신을 버리지 않고 보류(pendingState) 후 잔여 시간 뒤
     *   자동 재시도한다. (구버전: 드롭 → IMU 가 전이 순간에만 통지하는 특성과 겹쳐 실제와
     *   반대 상태로 고착 — 이동 중인데 '대기 중'으로 보이는 현상의 원인)
     */
    fun updateState(newState: Int) {
        val s = newState and 0b11
        if (s == currentState) {
            // [v1.0.51 #1] 최신 의도 == 현재 송출 상태 → 반대 상태로 남은 보류 재시도는 낡은 값이므로 취소
            //   (예: 잠깐 정지로 IDLE 보류 → 2초 내 이동 재개 → 보류 IDLE 이 이동 중에 적용되는 역전 방지)
            if (pendingState >= 0) {
                pendingState = -1
                stateHandler.removeCallbacks(pendingStateRunnable)
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val wait = MIN_STATE_UPDATE_INTERVAL_MS - (now - lastPayloadUpdateMs)
        if (wait > 0) {
            // [v1.0.51 #1] 2초 이내 재갱신 금지 — 드롭 대신 보류 저장 후 잔여 시간 뒤 재시도
            pendingState = s
            stateHandler.removeCallbacks(pendingStateRunnable)
            stateHandler.postDelayed(pendingStateRunnable, wait)
            Log.d(TAG, "상태 갱신 보류(Rate-Limit): STATE=$s — ${wait}ms 후 재시도")
            return
        }
        pendingState = -1                                  // 직접 적용이 보류를 대체
        stateHandler.removeCallbacks(pendingStateRunnable)
        lastPayloadUpdateMs = now
        currentState = s
        Log.d(TAG, "STATE 갱신 → $s (CAT=$category) 재광고")
        restartAdvertise()
    }

    /**
     * [v1.1.7 #1] 송신 회전 방향(Turn 2비트) 갱신. ImuFusion.turnDirection 을 BleService 가 주기적으로 민다.
     *  - 동일 방향이면 무시 — 불필요한 stop/start 폭주 방지(회전은 이산값이라 정확 비교).
     *  - STATE 와 동일한 2초 Rate-Limit throttle 을 공유한다(동시 재광고 충돌 방지).
     *    throttle 에 걸리면 이번 갱신은 생략하고 다음 폴링(BleService ~1.5초)에서 재시도.
     *  - 재광고 시 startAdvertising 이 최신 currentState + currentTurnDir 를 함께 패킹해 싣는다.
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
     * (v1.1.14) 송신 위험상태(RISK 2비트, LEVEL_*) 갱신 — BleService 가 자신의 alertState 최대
     *  경보레벨을 주기적으로 민다. STATE/TURN(2초 throttle)과 '독립'된 throttle 을 쓴다(안전 critical).
     *  - 상승(위험↑, 예: SAFE→DANGER)은 throttle 을 무시하고 '즉시' 재광고 — 전파 지연 0.
     *  - 하강(위험↓)은 0.5초 최소간격(레벨 토글 시 stop/start 폭주 차단). 걸리면 이번 호출은 생략하고
     *    currentRisk 도 그대로 둔다 → BleService 송신 폴링(~1.5초)이 매번 다시 호출하므로
     *    throttle 풀린 다음 호출에서 자연 반영된다(보류 runnable 불필요). 하강이 최대 ~1.5초 늦어도
     *    '더 위험하게'가 아니라 '안전측으로 더 오래 울림'이라 무해.
     *  - restartAdvertise 가 최신 currentState+currentTurnDir+currentRisk 를 함께 패킹해 싣는다.
     */
    fun updateRisk(level: Int) {
        val r = level.coerceIn(BleConstants.LEVEL_SAFE, BleConstants.LEVEL_DANGER)
        if (r == currentRisk) return
        val now = SystemClock.elapsedRealtime()
        val rising = r > currentRisk
        if (!rising && now - lastRiskUpdateMs < MIN_RISK_UPDATE_INTERVAL_MS) return  // 하강 비긴급 — 다음 폴링서 재시도
        currentRisk = r
        lastRiskUpdateMs = now
        Log.d(TAG, "위험상태 송출 갱신 → $r (상승=$rising) 재광고")
        restartAdvertise()
    }

    /** v1.0.36 광고 정지 후 STATE_RESTART_DELAY_MS 뒤 최신 페이로드로 재광고 (updateState/updateTurn 공용). */
    private fun restartAdvertise() {
        if (stopped || paused) return   // [v1.0.42 Req3] 슬립 중엔 STATE/Turn 갱신이 광고를 깨우지 않음
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId, lastUwbAddress)
        }, STATE_RESTART_DELAY_MS)
    }

    // ── [v1.1.26 B] 경고권 진입 버스트 ──────────────────────────────────────────
    //   BleService.noteRssiForWake 가 상대 근접(rssi≥WAKE) 수신 시 requestBurst 를 호출 →
    //   내 광고를 LOW_LATENCY(~100ms)로 가속해 상대가 나를 더 빨리 발견(상호 보호).
    //   근접이 지속되는 동안 매 수신마다 hold 만큼 연장되고, 멀어지면 만료 후 정상 모드로 복귀.

    //   버스트 만료 처리 — burstUntilMs 가 지났으면 정상 모드로 재광고(restartAdvertise 가
    //   깨어있으면 BALANCED, paused 면 no-op). 더 긴 버스트로 연장됐으면(아직 미래) 아무것도 안 함.
    private val burstExpiryRunnable = Runnable {
        if (stopped) return@Runnable
        if (SystemClock.elapsedRealtime() >= burstUntilMs) restartAdvertise()
    }

    //   [v1.1.26 B] 경고권 버스트 요청 — durationMs 동안 LOW_LATENCY 광고로 가속.
    //   이미 같거나 더 긴 버스트가 진행 중이면 무시(연장·재광고 불필요). 슬립(paused) 중이면
    //   곧 웨이크 시 startAdvertising 이 burstUntilMs 를 보고 LOW_LATENCY 로 시작하므로
    //   여기선 만료 타이머만 건다. 깨어있고 아직 LOW_LATENCY 가 아니면 즉시 재광고로 가속.
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

    /** [v1.0.48 #5] 설정(advertiseInterval) 라이브 반영 — BleService 의 prefs 리스너가 호출.
     *  슬립(paused) 중엔 어차피 LOW_POWER 고정이라 건너뛰고(웨이크 시 startAdvertising 이 새
     *  매핑을 자동 적용), 활성 광고 중이며 매핑 모드가 실제로 바뀐 경우에만 재광고한다(no-op 가드
     *  — 무관한 설정 키 변경·resetToDefault 에도 안전). */
    fun refreshAdvertiseMode() {
        if (stopped || paused) return
        val target = mapAdvertiseMode(BleConstants.advertiseInterval)
        if (target == lastAppliedAdvertiseMode) return
        Log.d(TAG, "광고 모드 라이브 갱신 → 간격설정 ${BleConstants.advertiseInterval}ms")
        restartAdvertise()
    }

    /** UWB 주소 준비 완료 후 광고 재시작 */
    fun restartWithUwbAddress(uwbLocalAddress: ByteArray) {
        // (v1.1.30) sticky 주소를 가드보다 먼저 기록 — 슬립(paused) 중 갱신이 유실되지 않고
        //   wake 시 startAdvertising(currentDeviceId, lastUwbAddress) 가 최신 주소로 송출.
        lastUwbAddress = uwbLocalAddress
        if (stopped || paused) return   // [v1.0.42 Req3] 슬립 중엔 UWB 재시작도 광고를 깨우지 않음
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        // [v1.0.41] 전용 Handler 대신 stateHandler 로 통일 — stopAdvertising() 의
        //   removeCallbacksAndMessages(null) 한 번으로 모든 예약 재광고를 취소하기 위함.
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId, uwbLocalAddress)
        }, 300)
    }

    /** (v1.1.30) UWB 세션 종료 후 UWB 스캔 응답 없이 광고 재시작 — sticky 주소도 함께 제거 */
    fun restartWithoutUwbAddress() {
        lastUwbAddress = null                // 슬립 중이어도 주소는 지워 wake 시 UWB 없이 재광고
        if (stopped || paused) return
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId)
        }, 300)
    }

    // ── [v1.0.42 Req3 · v1.0.43 개선] RSSI 동적 슬립/웨이크 ──────────────────
    //   [v1.0.43] 슬립을 'stop + 하트비트 버스트(700ms/4s)' → 'LOW_POWER 연속 광고'로 전환.
    //   완전 정지하지 않고 저빈도(~1s)로 항상 송출하므로 상대가 즉시 재발견 → 깨어남 지연 제거.
    //   (구 heartbeatRunnable / HEARTBEAT_* 상수 폐지)

    /**
     * [v1.0.42 Req3 · v1.0.43 개선] RSSI 슬립 진입 — 완전 정지가 아니라 LOW_POWER 연속 광고로 낮춘다.
     * paused=true 로 둔 뒤 재광고하면 startAdvertising 이 LOW_POWER(~1s) 모드로 상시 송출한다.
     * 상대 스캐너가 항상 나를 잡을 수 있어 재발견(→ wake) 지연이 사라진다.
     * stopped(영구 종료)와 독립 — resumeAdvertising() 으로 언제든 즉시 BALANCED 로 승격.
     */
    fun pauseAdvertising() {
        if (stopped || paused) return
        paused = true
        burstUntilMs = 0L                                  // [v1.1.26 B] 슬립 진입 시 버스트 해제(근접 신호 사라짐)
        stateHandler.removeCallbacksAndMessages(null)      // 예약된 재광고 콜백 전부 취소
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        // stop→start OS 정리 대기 후 재광고 — paused=true 이므로 startAdvertising 이 LOW_POWER 연속으로 송출.
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId, lastUwbAddress)
        }, STATE_RESTART_DELAY_MS)
        Log.d(TAG, "RSSI 슬립 진입 — LOW_POWER 연속 광고로 전환(상시 저빈도 송출)")
    }

    /**
     * [v1.0.42 Req3 · v1.0.43 개선] RSSI 웨이크 — 0ms 즉시 BALANCED 연속 광고로 승격.
     * paused=false 로 둔 뒤 재광고하면 startAdvertising 이 BALANCED 모드로 올린다. 재개 시점의 최신
     * currentState/currentTurnDir 가 그대로 패킹돼 나가 '강한 LocalState 송출' 효과를 낸다(postDelayed 없음).
     */
    fun resumeAdvertising() {
        if (stopped) return
        val wasPaused = paused
        paused = false
        stateHandler.removeCallbacksAndMessages(null)      // 슬립 측 예약 콜백 제거
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        startAdvertising(currentDeviceId, lastUwbAddress)  // 0ms 즉시 연속 광고 ON(버스트 중이면 LOW_LATENCY)
        // [v1.1.26 B] 위에서 콜백을 전부 지웠으므로, 진행 중이던 버스트가 있으면 만료 타이머를 다시 건다.
        val burstRemain = burstUntilMs - SystemClock.elapsedRealtime()
        if (burstRemain > 0) stateHandler.postDelayed(burstExpiryRunnable, burstRemain)
        if (wasPaused) Log.d(TAG, "RSSI 웨이크 — 즉시 연속 광고로 승격(LocalState 강송출)")
    }

    fun stopAdvertising() {
        // [v1.0.41] 핵심 수정 — 예약된 재광고(restartAdvertise/updateState/updateTurn/
        //   restartWithUwbAddress 의 postDelayed)가 stop 직후 광고를 되살리는 누수를 차단.
        stopped = true
        stateHandler.removeCallbacksAndMessages(null)
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        Log.d(TAG, "광고 중지(예약 재광고 취소 포함)")
    }
}
