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
        //   [v1.0.36] STATE 와 Speed(bits 3:0) 재광고가 이 throttle 을 '공유'한다.
        private const val MIN_STATE_UPDATE_INTERVAL_MS = 2000L
        // 상태 변경 시 stopAdvertising 후 재시작까지 대기 (OS 정리 시간 확보)
        private const val STATE_RESTART_DELAY_MS = 50L
        // [v1.0.36] 속도 미세 변화 무시 임계(km/h). 1km/h 미만 변화는 재광고 생략(stop/start 폭주 방지).
        private const val MIN_SPEED_DELTA_KMH = 1.0
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

    // [v1.0.34 다이나믹 페이로드] 현재 송신자 STATE(2bit, PSTATE_*) — Category·Speed 와 함께
    //   encodePayload() 로 1바이트로 패킹되어 ServiceData 로 탑재된다.
    @Volatile private var currentState: Int = BleConstants.PSTATE_IDLE
    // [v1.0.36] 현재 송신 예상속도(km/h, 0~15) — startAdvertising 이 Speed 4비트로 패킹해 싣는다.
    //   BleService 가 ImuFusion.estimatedSpeedKmh 를 주기적으로 updateSpeed() 로 밀어 넣는다.
    @Volatile private var currentSpeedKmh: Double = 0.0
    // [v1.0.36] STATE·Speed 재광고 공용 throttle 타임스탬프 (구 lastStateUpdateMs)
    private var lastPayloadUpdateMs = 0L
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
    val txSpeedKmh: Double get() = currentSpeedKmh

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
        val advertiseMode = if (paused) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                            else mapAdvertiseMode(BleConstants.advertiseInterval)
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
            // [v1.0.36] 1바이트 ServiceData 복구: Category+State+Speed(2-2-4 패킹) 단일 바이트.
            //   v1.0.35 의 2번째 방위각 바이트는 지자기 자기장 교란 문제로 전면 롤백됐다.
            //   SERVICE_UUID 가 16비트 short UUID(0x1234) 패턴 → ServiceData 약 5바이트.
            .addServiceData(
                ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID)),
                byteArrayOf(
                    BleConstants.encodePayload(category, currentState, currentSpeedKmh)
                )
            )
            .addManufacturerData(companyId, idBytes)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        // ── UWB 스캔 응답 (지원 시 별도 패킷으로 UWB 주소 공유) ──
        val scanResponse = if (uwbLocalAddress != null && uwbLocalAddress.size >= 2) {
            AdvertiseData.Builder()
                .addManufacturerData(BleConstants.COMPANY_ID_UWB_EXT, uwbLocalAddress.take(2).toByteArray())
                .setIncludeDeviceName(false)
                .build()
        } else null

        if (scanResponse != null) {
            advertiser.startAdvertising(settings, advertiseData, scanResponse, callback)
            Log.d(TAG, "광고+UWB 시작: id=$deviceId uwb=${uwbLocalAddress!!.take(2).joinToString("") { "%02X".format(it) }}")
        } else {
            advertiser.startAdvertising(settings, advertiseData, callback)
            Log.d(TAG, "광고 시작: companyId=0x${companyId.toString(16).uppercase()} id=$deviceId")
        }
    }

    /**
     * [v1.0.34 다이나믹 페이로드] 송신자 STATE(2bit, PSTATE_*) 갱신.
     * 변경이 있을 때만, 그리고 최소 2초 간격(OS Rate-Limit 회피)으로
     * 기존 광고를 멈추고 약 50ms 뒤 새 페이로드(Category+State+Speed)로 다시 켠다.
     * Category 는 생성자 고정, Speed 는 updateSpeed() 가 갱신 — 여기선 STATE 만 바뀐다.
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
     * v1.0.36 송신 예상속도(Speed 4비트) 갱신. ImuFusion.estimatedSpeedKmh 를 BleService 가 주기적으로 민다.
     *  - 미세 변화(±MIN_SPEED_DELTA_KMH 미만)는 무시 — 불필요한 stop/start 폭주 방지.
     *  - STATE 와 동일한 2초 Rate-Limit throttle 을 공유한다(동시 재광고 충돌 방지).
     *    throttle 에 걸리면 이번 갱신은 생략하고 다음 폴링(BleService ~1.5초)에서 재시도.
     *  - 재광고 시 startAdvertising 이 최신 currentState + currentSpeedKmh 를 함께 패킹해 싣는다.
     */
    fun updateSpeed(speedKmh: Float) {
        val v = speedKmh.toDouble().coerceIn(0.0, BleConstants.SPEED_MAX_KMH)
        if (abs(v - currentSpeedKmh) < MIN_SPEED_DELTA_KMH) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPayloadUpdateMs < MIN_STATE_UPDATE_INTERVAL_MS) return
        lastPayloadUpdateMs = now
        currentSpeedKmh = v
        Log.d(TAG, "속도 갱신 → %.1fkm/h 재광고".format(v))
        restartAdvertise()
    }

    /** v1.0.36 광고 정지 후 STATE_RESTART_DELAY_MS 뒤 최신 페이로드로 재광고 (updateState/updateSpeed 공용). */
    private fun restartAdvertise() {
        if (stopped || paused) return   // [v1.0.42 Req3] 슬립 중엔 STATE/Speed 갱신이 광고를 깨우지 않음
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId, lastUwbAddress)
        }, STATE_RESTART_DELAY_MS)
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
        if (stopped || paused) return   // [v1.0.42 Req3] 슬립 중엔 UWB 재시작도 광고를 깨우지 않음
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        // [v1.0.41] 전용 Handler 대신 stateHandler 로 통일 — stopAdvertising() 의
        //   removeCallbacksAndMessages(null) 한 번으로 모든 예약 재광고를 취소하기 위함.
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId, uwbLocalAddress)
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
     * currentState/currentSpeedKmh 가 그대로 패킹돼 나가 '강한 LocalState 송출' 효과를 낸다(postDelayed 없음).
     */
    fun resumeAdvertising() {
        if (stopped) return
        val wasPaused = paused
        paused = false
        stateHandler.removeCallbacksAndMessages(null)      // 슬립 측 예약 콜백 제거
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        startAdvertising(currentDeviceId, lastUwbAddress)  // 0ms 즉시 BALANCED 연속 광고 ON
        if (wasPaused) Log.d(TAG, "RSSI 웨이크 — 즉시 BALANCED 연속 광고로 승격(LocalState 강송출)")
    }

    fun stopAdvertising() {
        // [v1.0.41] 핵심 수정 — 예약된 재광고(restartAdvertise/updateState/updateSpeed/
        //   restartWithUwbAddress 의 postDelayed)가 stop 직후 광고를 되살리는 누수를 차단.
        stopped = true
        stateHandler.removeCallbacksAndMessages(null)
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        Log.d(TAG, "광고 중지(예약 재광고 취소 포함)")
    }
}
