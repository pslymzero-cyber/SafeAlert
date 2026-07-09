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
    // (v1.1.30) 형식: DEVICE(컨트롤러) 4바이트=[addr0][addr1][channel][preambleIndex]
    //           / WALKER(컨트롤리) 2바이트=[addr0][addr1]
    const val COMPANY_ID_UWB_EXT  = 0x9ABC
    // (v1.1.53) 상호 RSSI 교환용 — 스캔 응답에 UWB_EXT 와 나란히 탑재(메인 광고 패킷은 만석).
    //   각 기기가 '내가 상대를 들은 RSSI' 를 상대별로 되돌려보내(에코) 양쪽이 동일한
    //   sym=(rssi_A→B + rssi_B→A)/2 로 판정 → 폰별 TX/RX 비대칭(내 폰은 무음·상대는 경보) 구조 해소.
    //   엔트리 = [hash_hi][hash_lo][rssi(signed)] 3바이트. hash = shortHash(상대 fullId) 2바이트.
    const val COMPANY_ID_RSSI_ECHO = 0xE0C0
    const val ECHO_ENTRY_SIZE      = 3        // 엔트리당 바이트(2바이트 해시 + 1바이트 signed RSSI)
    const val NO_ECHO_RSSI         = Int.MIN_VALUE   // 에코 부재 센티널(RSSI 는 음수 dBm 이라 MIN_VALUE 안전)

    // RSSI >= 임계값 → 경보 (가까울수록 RSSI가 0에 가까워짐)
    // 경고: 더 멀리서(더 음수), 위험: 더 가까이서(덜 음수)
    // [v1.0.48 #6] (구) txPower=-38/n=2.53 거리 환산 주석 폐기 — v1.0.39 에서 거리계산 파생을
    //   걷어내고 v1.0.40 부터 dBm 슬라이더 직접 저장이라 환산식은 더 이상 사실이 아니었다.
    //   임계 기본값의 단일 출처는 DevSettings.DEFAULT_RSSI_*_ABS(-75/-55)이며, 아래 상수는
    //   DevSettings 초기화 전 runCatching 폴백 전용이라 같은 값으로 정렬한다(불일치 해소).
    const val DEFAULT_RSSI_WARNING       = -75
    const val DEFAULT_RSSI_DANGER        = -55
    // [v1.1.7 #3] 3000ms→1000ms: BleScanner.mapScanMode 가 ≤1000ms 를 LOW_LATENCY(연속 스캔)로
    //   매핑 → 감지 blind window 제거(알람 지연/누락 방지). DevSettings.scanPeriodMs 기본값과 일치.
    const val DEFAULT_SCAN_PERIOD_MS     = 1000L
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
    // [v1.1.7 #1 다이나믹 페이로드 — 1Byte 비트패킹 프로토콜 (2-2-2-2 Split)]
    //   ServiceData 1바이트를 4개 필드로 분할 패킹/언패킹한다.
    //   [v1.1.7 #1] SPEED(4bit) 폐기 → TURN(회전, 2bit) 탑재. 하위 2bit는 예약.
    //
    //   Bit:  7  6 | 5  4 | 3  2 | 1  0
    //         [ CAT ]|[STATE]|[TURN]|[RSV]
    //          2bit    2bit    2bit   2bit
    //
    //   CAT  (Category, 송신자 역할 — bits 7:6):
    //     00 보행자(WALKER) / 01 EPJ / 10 지게차·리치·오더피커(FORKLIFT) / 11 예약
    //   STATE (송신자 동적 상태 — bits 5:4) [v1.0.42 의미 재정의]:
    //     00 정지·일반(IDLE)      - 정지 또는 평상(특수경보 아님)
    //     01 전진·주행(FORWARD)   - 평상 주행(특수경보 아님)
    //     10 후진(REVERSE)        - 특수경보 트리거
    //     11 하역·작업(LOADING)   - 특수경보 트리거 / 지게차는 '상부 고소 작업'
    //   TURN (회전 방향 — bits 3:2) [v1.1.7 #1 신설, 기존 SPEED 4bit 대체]:
    //     00 직진(STRAIGHT) / 01 좌회전(LEFT) / 10 우회전(RIGHT) / 11 예약
    //          송신단(ImuFusion.turnDirection, GAME_ROTATION_VECTOR 방위각 미분 기반)이
    //          실시간 송출 → 수신단이 상대의 회전 진입을 표시·경보에 활용한다.
    //   RISK (bits 1:0) [v1.1.14 — 기존 RSV 예약 2비트 활용]:
    //     00 안전(미감지) / 01 경고 감지 / 10 위험 감지 / 11 예약
    //          송신단(BleService 가 자신의 alertState 최대 경보레벨)을 송출 →
    //          수신단이 decodeRisk 로 풀어 '자신 RSSI 게이트와 결합'(절충)해 경보를 격상한다.
    //          → 한쪽이 먼저 감지하면 양쪽이 함께 울리는 양방향 협력 알림(fail-safe).
    //
    //   ※ 호환성: 보행자 평상(CAT=00,STATE=00,TURN=00) = 0x00 →
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

    // Turn (bits 3:2) — [v1.1.7 #1] 회전 방향. 기존 SPEED 4bit 폐기 후 재배치.
    const val TURN_STRAIGHT = 0b00   // 직진
    const val TURN_LEFT     = 0b01   // 좌회전
    const val TURN_RIGHT    = 0b10   // 우회전
    const val TURN_RESERVED = 0b11   // 예약

    // 비트 필드 마스크/시프트  (2-2-2-2: CAT 상위 → STATE → TURN → RISK 하위)
    private const val CAT_SHIFT   = 6
    private const val CAT_MASK    = 0b11
    private const val STATE_SHIFT = 4
    private const val STATE_MASK  = 0b11
    private const val TURN_SHIFT  = 2
    private const val TURN_MASK   = 0b11
    // [v1.1.14] RISK(위험 감지 상태) — bits[1:0]. LEVEL_*(0~2) 를 그대로 2비트에 싣는다.
    private const val RISK_SHIFT  = 0
    private const val RISK_MASK   = 0b11

    /**
     * 4개 필드(Category 2bit + State 2bit + Turn 2bit + Risk 2bit)를 1바이트로 패킹한다. (2-2-2-2 Split)
     * 레이아웃: bits[7:6]=CAT, bits[5:4]=STATE, bits[3:2]=TURN, bits[1:0]=RISK.
     * @param category CAT_* (0~3) — 범위 밖 상위 비트는 마스킹돼 버려진다.
     * @param state    PSTATE_* (0~3)
     * @param turn     TURN_* (0~3). [v1.1.7 #1] 송신단은 ImuFusion.turnDirection(방위각 미분)를 송출.
     * @param risk     LEVEL_* (0~2). (v1.1.14) 송신자 위험 감지 상태(기본 SAFE). 수신단이 격상에 활용.
     */
    fun encodePayload(category: Int, state: Int, turn: Int = TURN_STRAIGHT, risk: Int = LEVEL_SAFE): Byte {
        val c = (category and CAT_MASK) shl CAT_SHIFT
        val s = (state and STATE_MASK) shl STATE_SHIFT
        val t = (turn and TURN_MASK) shl TURN_SHIFT
        val r = (risk and RISK_MASK) shl RISK_SHIFT      // [v1.1.14] 위험 감지 상태(RSV→RISK)
        return (c or s or t or r).toByte()
    }

    /** 패킹된 1바이트에서 Category(bits 7:6) 추출. */
    fun decodeCategory(payload: Int): Int = ((payload and 0xFF) shr CAT_SHIFT) and CAT_MASK

    /** 패킹된 1바이트에서 State(bits 5:4) 추출. */
    fun decodeState(payload: Int): Int = ((payload and 0xFF) shr STATE_SHIFT) and STATE_MASK

    /** 패킹된 1바이트에서 Turn 코드(bits 3:2, TURN_*) 추출. [v1.1.7 #1] */
    fun decodeTurn(payload: Int): Int = ((payload and 0xFF) shr TURN_SHIFT) and TURN_MASK

    /** 패킹된 1바이트에서 Risk(bits 1:0, LEVEL_*) 추출. (v1.1.14) 송신자 위험 감지 상태(0 SAFE/1 경고/2 위험). */
    fun decodeRisk(payload: Int): Int = ((payload and 0xFF) shr RISK_SHIFT) and RISK_MASK

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

    /** Turn(TURN_*) -> 표시용 한글 라벨. [v1.1.7 #1] */
    fun turnLabel(turn: Int): String = when (turn) {
        TURN_LEFT     -> "좌회전"
        TURN_RIGHT    -> "우회전"
        TURN_STRAIGHT -> "직진"
        else          -> "-"
    }

    // ── (v1.1.53) 상호 RSSI 교환 — 에코 테이블 인코딩/디코딩 + 짧은 해시 ──────────────
    /**
     * 상대 fullId(prefix+wire id, deviceRssiMap 키와 동일)를 2바이트(0..65535) 해시로 축약.
     *   FNV-1a 32비트 후 상·하위 XOR-fold → 16비트. 송신측은 상대 fullId 로, 수신측은
     *   '자기 fullId' 로 같은 함수를 호출해 자기 에코 엔트리를 식별한다(양측 동일 문자열 → 동일 해시).
     */
    fun shortHash(id: String): Int {
        var h = -0x7ee3623b               // 0x811C9DC5 FNV-1a offset basis(Int 비트패턴)
        for (b in id.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193               // FNV prime
        }
        return (h xor (h ushr 16)) and 0xFFFF
    }

    /**
     * 에코 엔트리 리스트((hash, rssiDbm))를 바이트 배열로 인코딩. 최대 maxEntries 개.
     *   각 엔트리 3바이트: [hash>>8][hash&0xFF][rssi.coerceIn(-128,127)].
     */
    fun encodeEchoTable(entries: List<Pair<Int, Int>>, maxEntries: Int): ByteArray {
        val n = minOf(entries.size, maxEntries)
        val out = ByteArray(n * ECHO_ENTRY_SIZE)
        for (i in 0 until n) {
            val (hash, rssi) = entries[i]
            out[i * 3]     = ((hash ushr 8) and 0xFF).toByte()
            out[i * 3 + 1] = (hash and 0xFF).toByte()
            out[i * 3 + 2] = rssi.coerceIn(-128, 127).toByte()
        }
        return out
    }

    /**
     * 에코 바이트에서 myHash 와 일치하는 엔트리의 RSSI(dBm) 반환. 없으면 null.
     *   수신측이 '자기 해시'로 자기 에코를 찾아 상대가 나를 들은 세기를 복원한다.
     */
    fun findEchoRssi(echoData: ByteArray, myHash: Int): Int? {
        var i = 0
        while (i + ECHO_ENTRY_SIZE <= echoData.size) {
            val hash = ((echoData[i].toInt() and 0xFF) shl 8) or (echoData[i + 1].toInt() and 0xFF)
            if (hash == myHash) return echoData[i + 2].toInt()
            i += ECHO_ENTRY_SIZE
        }
        return null
    }
}

/**
 * [v1.0.42 Req2] 내 장비(Local) 송신 상태 — 내가 BLE 로 '송출'하는 역할/상태/속도.
 *   수신(Target)과 완전히 분리된 별도 모델이다. 상대 페이로드 디코드 결과(TargetState)가
 *   이 값을 절대 덮어쓰지 않도록 데이터 모델 자체를 분리한다(received hex → local UI 오염 차단).
 */
data class LocalState(
    val category: Int  = BleConstants.CAT_WALKER,       // 내 역할 (CAT_*)
    val state: Int     = BleConstants.PSTATE_IDLE,       // 내 동적 상태 (PSTATE_*)
    val turnDir: Int   = BleConstants.TURN_STRAIGHT      // 내 송출 회전 방향 (TURN_*)
) {
    val categoryLabel: String get() = BleConstants.categoryLabel(category)
    val stateLabel: String    get() = BleConstants.stateLabel(state)
    val turnLabel: String     get() = BleConstants.turnLabel(turnDir)
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
    val turnDir: Int,         // 상대 송출 회전 방향 (TURN_*)
    val level: Int,           // 경보 레벨 (LEVEL_*)
    val rssi: Int             // 최근 RSSI (dBm)
) {
    val categoryLabel: String get() = BleConstants.categoryLabel(category)
    val stateLabel: String    get() = BleConstants.stateLabel(state)
    val turnLabel: String     get() = BleConstants.turnLabel(turnDir)
}
