package com.wf11.safealert.ble

import com.wf11.safealert.utils.DevSettings

object BleConstants {
    const val SERVICE_UUID         = "00001234-0000-1000-8000-00805F9B34FB"
    const val DEVICE_PREFIX        = "SAFEALERT_DEVICE_"
    const val WALKER_PREFIX        = "SAFEALERT_WALKER_"

    // Device/Walker 구분을 CompanyID로 처리 → 광고 패킷 크기 절약
    const val COMPANY_ID_DEVICE   = 0x1234  // 장비 작업자
    const val COMPANY_ID_WALKER   = 0x5678  // 보행자
    // UWB 주소 교환용 (스캔 응답 전용 — 메인 광고 패킷과 분리)
    // 형식: [addr_lo][addr_hi]  (컨트롤러/컨트롤리 모두 동일 2바이트)
    const val COMPANY_ID_UWB_EXT  = 0x9ABC

    // RSSI >= 임계값 → 경보 (가까울수록 RSSI가 0에 가까워짐)
    // 경고: 더 멀리서(더 음수), 위험: 더 가까이서(덜 음수)
    // [v1.0.48 #6] (구) txPower=-38/n=2.53 거리 환산 주석 폐기 — v1.0.39 에서 거리계산 파생을
    //   걷어내고 v1.0.40 부터 dBm 슬라이더 직접 저장이라 환산식은 더 이상 사실이 아니었다.
    //   임계 기본값의 단일 출처는 DevSettings.DEFAULT_RSSI_*_ABS(-75/-55)이며, 아래 상수는
    //   DevSettings 초기화 전 runCatching 폴백 전용이라 같은 값으로 정렬한다(불일치 해소).
    const val DEFAULT_RSSI_WARNING       = -75
    const val DEFAULT_RSSI_DANGER        = -55
    const val DEFAULT_SCAN_PERIOD_MS     = 3000L
    const val DEFAULT_ADVERTISE_INTERVAL = 200

    val rssiWarning: Int       get() = runCatching { DevSettings.rssiWarning }.getOrDefault(DEFAULT_RSSI_WARNING)
    val rssiDanger: Int        get() = runCatching { DevSettings.rssiDanger }.getOrDefault(DEFAULT_RSSI_DANGER)
    val scanPeriodMs: Long     get() = runCatching { DevSettings.scanPeriodMs }.getOrDefault(DEFAULT_SCAN_PERIOD_MS)
    val advertiseInterval: Int get() = runCatching { DevSettings.advertiseInterval }.getOrDefault(DEFAULT_ADVERTISE_INTERVAL)

    const val LEVEL_SAFE    = 0
    const val LEVEL_WARNING = 1
    const val LEVEL_DANGER  = 2

    // [v1.0.29 다이나믹 페이로드] IMU 모션 상태 코드 (송신단 내부 표현 전용)
    //   ImuFusion.motionState 가 반환하는 값과 1:1 일치한다.
    //   0x00 정지 / 0x01 일반 이동 / 0x02 급정거·급회전
    //   ※ v1.0.34 부터 '전파(wire)' 에는 이 값을 그대로 싣지 않고
    //     아래 encodePayload() 로 2bit STATE 필드(PSTATE_*)에 매핑해 1바이트로 패킹한다.
    const val MOTION_STATE_STATIONARY = 0x00
    const val MOTION_STATE_NORMAL     = 0x01
    const val MOTION_STATE_SUDDEN     = 0x02

    // [v1.0.42] 특수상태(후진 PSTATE_REVERSE / 하역 PSTATE_LOADING) 즉시 DANGER 격상 임계를
    //   위험(rssiDanger=-55)으로 통일. 상대 STATE 가 후진·하역이고 정제 RSSI가 rssiDanger 이상
    //   (가까움)이면 TTC·속도·방향 조건을 모두 무시하고 즉시 최고 DANGER로 격상한다.
    //   사용처(BleService 특수경보 분기)에서 BleConstants.rssiDanger 를 직접 참조한다.

    // ───────────────────────────────────────────────────────────────
    // [v1.0.34 다이나믹 페이로드 — 1Byte 비트패킹 프로토콜 (2-2-4 Split)]
    //   ServiceData 1바이트를 3개 필드로 분할 패킹/언패킹한다.
    //
    //   Bit:  7  6 | 5  4 | 3  2  1  0
    //         [ CAT ]|[STATE]|[   SPEED   ]
    //          2bit    2bit    4bit(0~15)
    //
    //   CAT  (Category, 송신자 역할 — bits 7:6):
    //     00 보행자(WALKER) / 01 EPJ / 10 지게차·리치·오더피커(FORKLIFT) / 11 예약
    //   STATE (송신자 동적 상태 — bits 5:4) [v1.0.42 의미 재정의]:
    //     00 정지·일반(IDLE)      - 정지 또는 평상(특수경보 아님)
    //     01 전진·주행(FORWARD)   - 평상 주행(특수경보 아님)
    //     10 후진(REVERSE)        - 특수경보 트리거
    //     11 하역·작업(LOADING)   - 특수경보 트리거 / 지게차는 '상부 고소 작업'
    //   SPEED (bits 3:0): 0~15 km/h 를 1km/h 단위로 양자화 → 코드 0~15 (4비트 full).
    //          공식: encode = (kmh / 1.0).toInt(),  decode = code * 1.0 (km/h).
    //          [v1.0.36] 송신단(ImuFusion.estimatedSpeedKmh, 가속도 RMS 기반 예상속도)이
    //          실시간 송출 → 수신단 '충돌 기하학 필터'가 합산 접근속도 산출에 사용한다.
    //
    //   ※ 호환성: 보행자 평상(CAT=00,STATE=00,SPEED=0) = 0x00 →
    //     페이로드를 싣지 않는 iBeacon/MAC 비콘의 기본 0x00 과 자연 일치(안전한 기본값).
    // ───────────────────────────────────────────────────────────────

    // Category (bits 7:6)
    const val CAT_WALKER   = 0b00   // 보행자
    const val CAT_EPJ      = 0b01   // EPJ (전동 파레트 잭)
    const val CAT_FORKLIFT = 0b10   // 지게차·리치·오더피커
    const val CAT_RESERVED = 0b11   // 예약

    // State (bits 5:4) — [v1.0.42] 의미 재정의: 차량 주행 모드 중심
    const val PSTATE_IDLE    = 0b00   // 정지·일반 (정지 또는 평상)
    const val PSTATE_FORWARD = 0b01   // 전진·주행 (평상 주행 — 특수경보 아님)
    const val PSTATE_REVERSE = 0b10   // 후진 (특수경보 트리거)
    const val PSTATE_LOADING = 0b11   // 하역·작업 (특수경보 / 지게차 상부 고소 작업)

    // Speed (bits 3:0) — 1km/h 단위 양자화 [v1.0.36: 0~6 0.5단위 → 0~15 1단위 확장]
    const val SPEED_UNIT_KMH = 1.0      // 1코드 = 1km/h
    const val SPEED_MAX_KMH  = 15.0     // 송출 상한 (코드 15, 4비트 full)
    // [v1.0.39] EPJ(전동 파레트 잭) 물리 최고속도 가정 — 송수신 양단에서 속도를 이 값으로 cap.
    //   송신: 내 카테고리가 EPJ면 송출 속도를 3km/h 로 제한.
    //   수신: 내가/상대가 EPJ면 충돌 기하학 합산 접근속도 계산 시 해당 속도를 3km/h 로 제한.
    const val EPJ_MAX_SPEED_KMH = 3.0

    // 비트 필드 마스크/시프트  (2-2-4: CAT 상위 → SPEED 하위)
    private const val CAT_SHIFT   = 6
    private const val CAT_MASK    = 0b11
    private const val STATE_SHIFT = 4
    private const val STATE_MASK  = 0b11
    private const val SPEED_SHIFT = 0
    private const val SPEED_MASK  = 0b1111

    /**
     * 3개 필드(Category 2bit + State 2bit + Speed 4bit)를 1바이트로 패킹한다. (2-2-4 Split)
     * 레이아웃: bits[7:6]=CAT, bits[5:4]=STATE, bits[3:0]=SPEED.
     * @param category CAT_* (0~3) — 범위 밖 상위 비트는 마스킹돼 버려진다.
     * @param state    PSTATE_* (0~3)
     * @param speedKmh 0~15 km/h (범위 밖은 clamp). 1km/h 단위 양자화: (kmh / 1.0).toInt().
     *                 v1.0.36 송신단은 ImuFusion.estimatedSpeedKmh(가속도 RMS 추정)를 송출.
     */
    fun encodePayload(category: Int, state: Int, speedKmh: Double = 0.0): Byte {
        val c = (category and CAT_MASK) shl CAT_SHIFT
        val s = (state and STATE_MASK) shl STATE_SHIFT
        val units = (speedKmh.coerceIn(0.0, SPEED_MAX_KMH) / SPEED_UNIT_KMH).toInt().coerceIn(0, SPEED_MASK)
        val v = (units and SPEED_MASK) shl SPEED_SHIFT
        return (c or s or v).toByte()
    }

    /** 패킹된 1바이트에서 Category(bits 7:6) 추출. */
    fun decodeCategory(payload: Int): Int = ((payload and 0xFF) shr CAT_SHIFT) and CAT_MASK

    /** 패킹된 1바이트에서 State(bits 5:4) 추출. */
    fun decodeState(payload: Int): Int = ((payload and 0xFF) shr STATE_SHIFT) and STATE_MASK

    /** 패킹된 1바이트에서 Speed 코드(bits 3:0, 0~15) 추출. */
    fun decodeSpeed(payload: Int): Int = ((payload and 0xFF) shr SPEED_SHIFT) and SPEED_MASK

    /** 패킹된 1바이트에서 Speed 를 km/h 실측값으로 환산 (코드 * 1.0). */
    fun decodeSpeedKmh(payload: Int): Double = decodeSpeed(payload) * SPEED_UNIT_KMH

    // ── [v1.0.42 Req2] 표시용 한글 라벨 (Local/Target 양쪽 UI 가 공유하는 단일 소스) ──
    /** Category(CAT_*) -> 표시용 한글 라벨. */
    fun categoryLabel(category: Int): String = when (category) {
        CAT_WALKER   -> "보행자"
        CAT_EPJ      -> "EPJ"
        CAT_FORKLIFT -> "지게차"
        else         -> "예비"
    }

    /** State(PSTATE_*) -> 표시용 한글 라벨 (v1.0.42 의미 재정의: 정지·일반/전진·주행/후진/하역·작업). */
    fun stateLabel(state: Int): String = when (state) {
        PSTATE_IDLE    -> "정지·일반"
        PSTATE_FORWARD -> "전진·주행"
        PSTATE_REVERSE -> "후진"
        PSTATE_LOADING -> "하역·작업"
        else           -> "정지·일반"
    }
}

/**
 * [v1.0.42 Req2] 내 장비(Local) 송신 상태 — 내가 BLE 로 '송출'하는 역할/상태/속도.
 *   수신(Target)과 완전히 분리된 별도 모델이다. 상대 페이로드 디코드 결과(TargetState)가
 *   이 값을 절대 덮어쓰지 않도록 데이터 모델 자체를 분리한다(received hex → local UI 오염 차단).
 */
data class LocalState(
    val category: Int    = BleConstants.CAT_WALKER,    // 내 역할 (CAT_*)
    val state: Int       = BleConstants.PSTATE_IDLE,    // 내 동적 상태 (PSTATE_*)
    val speedKmh: Double = 0.0                          // 내 송출 예상속도 (0~15)
) {
    val categoryLabel: String get() = BleConstants.categoryLabel(category)
    val stateLabel: String    get() = BleConstants.stateLabel(state)
}

/**
 * [v1.0.42 Req2] 수신 타겟(Target) 상태 — 상대 기기가 송출한 1바이트 페이로드 디코드 결과.
 *   deviceId 별 1개. 내 장비(Local) 표시에 절대 영향을 주지 않는다.
 */
data class TargetState(
    val deviceId: String,
    val displayName: String,
    val category: Int,        // 상대 역할 (CAT_*)
    val state: Int,           // 상대 동적 상태 (PSTATE_*)
    val speedKmh: Double,     // 상대 송출 속도 (km/h)
    val level: Int,           // 경보 레벨 (LEVEL_*)
    val rssi: Int             // 최근 RSSI (dBm)
) {
    val categoryLabel: String get() = BleConstants.categoryLabel(category)
    val stateLabel: String    get() = BleConstants.stateLabel(state)
}
