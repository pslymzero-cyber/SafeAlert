package com.wf11.safealert.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import com.wf11.safealert.ble.BleConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * (v1.1.30) UWB 정밀 거리 측정 — androidx.core.uwb 1.0.0-alpha09 실구현.
 *
 * 역할 매핑: DEVICE=Controller / WALKER=Controlee 고정. (설계 초안의 '주소 비교 선출'은 상호
 * 주소를 알기 전엔 스코프를 만들 수 없는 순환 의존이라 역할 고정으로 확정. 같은 역할 페어는
 * BLE 전용으로 남는다.)
 *
 * OOB(대역외) 합의: BLE 0x9ABC 스캔 응답으로 컨트롤러는 4바이트(주소2 + 채널 + 프리앰블 —
 * 채널은 스코프가 실제 할당받은 값), 컨트롤리는 2바이트(주소)를 광고한다. sessionId 와
 * STATIC STS 8바이트 키는 컨트롤러 주소 2바이트에서 양측이 동일하게 유도하므로 별도 협상
 * 패킷이 없다.
 *
 * 동시 세션은 1개(unicast, 1스코프=1세션 규칙). 세션이 끝나면 새 스코프(새 주소·새 채널)를
 * 만들고 [onLocalAddressChanged] 로 BLE 재광고를 트리거한다. 피어의 광고 페이로드가 바뀌면
 * (상대가 세션을 재시작한 것) 이쪽도 스코프를 갱신해 재합류한다.
 *
 * 안전 불변식: 측정값은 [uwbDistances] 채움 + 상태줄 통지뿐 — 경보·거리 표시 파이프라인
 * (RSSI 기반)에는 일절 개입하지 않는다. 초기화·세션이 어떤 이유로든 실패하면 조용히 BLE
 * 전용으로 남는다(무봉합 폴백).
 */
class UwbRanger(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isDeviceMode: Boolean,          // true=Controller(DEVICE), false=Controlee(WALKER)
    private val onStatus: ((String) -> Unit)? = null,
    private val onLocalAddressChanged: ((ByteArray) -> Unit)? = null,
    private val rssiOf: ((String) -> Int?)? = null,   // (v1.1.32) deviceId → 최근 평활 RSSI(dBm) — 세션 우선순위·시작 게이트용
    private val forkliftPairOf: ((String) -> Boolean)? = null   // (v1.1.33) deviceId → 지게차 낀 쌍 여부 — 게이트 완화·우선순위 가산용
) {
    companion object {
        private const val TAG = "UwbRanger"
        private const val SESSION_ID_BASE = 0x00570000        // 'W'(0x57) 프리픽스 — 앱 고유 네임스페이스
        private const val RESTART_BACKOFF_MS = 10_000L        // 세션 오류·피어 해제 후 재시도 대기
        private const val REJOIN_DELAY_MS = 1_000L            // 피어 재광고·이탈 등 즉시성 재시작 대기
        private const val STATUS_THROTTLE_MS = 3_000L         // 거리 상태줄 전파 최소 간격
        private const val SWITCH_HYSTERESIS_DB = 6            // (v1.1.32) 직전 피어 유지 히스테리시스(재선정 핑퐁 방지)
        private const val FORKLIFT_RANK_BIAS_DB = 12          // (v1.1.33) 지게차 낀 쌍 우선순위 가산 — 단일 세션 경쟁에서 더 가까운 비지게차 쌍에 세션을 뺏겨 15m 경고가 무력화되는 것 방지

        /** 하드웨어 UWB 지원 여부 (API 31+ & FEATURE_UWB) */
        fun isHardwareSupported(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31) return false
            return context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)
        }
    }

    /** UWB 실가동 여부 — initSession() 성공 시 true (미지원·권한 없음·초기화 실패 = false → BLE 폴백) */
    @Volatile var isSupported: Boolean = false
        private set

    /** deviceId(fullId) → 최근 UWB 실측 거리(m). 세션 없는 기기는 항상 부재 → BLE 폴백 */
    val uwbDistances: MutableMap<String, Float> = ConcurrentHashMap()

    /** 이 기기의 UWB 로컬 주소 2바이트 (스코프 갱신 때마다 새 값) */
    @Volatile var localAddress: ByteArray? = null
        private set

    private var uwbManager: UwbManager? = null
    private var sessionScope: UwbClientSessionScope? = null   // 1스코프=1세션 — prepareSession 후 소진
    private var rangingJob: Job? = null
    private var activePeerId: String? = null
    private var activePeerPayload: ByteArray? = null
    private val candidates = LinkedHashMap<String, ByteArray>()   // deviceId → 최신 OOB 페이로드(수신 순서)
    private var lastActivePeerId: String? = null                  // (v1.1.32) 직전 세션 피어 — 선택 히스테리시스 기준
    private var restartScheduled = false
    private var stopped = false
    @Volatile private var lastStatusAt = 0L

    /**
     * UWB 세션 스코프 초기화 및 BLE 광고용 OOB 페이로드 획득.
     * 컨트롤러 4바이트 / 컨트롤리 2바이트. 실패(미지원·권한 없음·UWB OFF·하드웨어 오류) 시
     * null 반환 → 호출부(BleService)는 UWB 없는 광고를 그대로 유지한다.
     */
    suspend fun initSession(): ByteArray? {
        if (!isHardwareSupported(context)) {
            Log.d(TAG, "UWB 하드웨어 미지원 — BLE 전용으로 동작")
            return null
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.UWB_RANGING)
            != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "UWB_RANGING 권한 없음 — BLE 전용으로 동작")
            return null
        }
        return try {
            val mgr = UwbManager.createInstance(context)
            if (!mgr.isAvailable()) {
                Log.i(TAG, "UWB 서비스 비활성(기기 설정 OFF 등) — BLE 전용으로 동작")
                return null
            }
            val s = if (isDeviceMode) mgr.controllerSessionScope() else mgr.controleeSessionScope()
            val payload = buildAdvertisePayload(s)
            synchronized(this) {
                if (stopped) return null
                uwbManager = mgr
                sessionScope = s
                localAddress = s.localAddress.address.copyOf()
                isSupported = true
            }
            Log.i(TAG, "UWB 초기화 완료: role=${if (isDeviceMode) "controller" else "controlee"} " +
                    "payload=${payload.toHex()}")
            payload
        } catch (e: Exception) {
            Log.w(TAG, "UWB 초기화 실패 — BLE 전용으로 동작: ${e.message}")
            null
        }
    }

    /**
     * BLE 스캔 응답(0x9ABC)에서 피어의 UWB OOB 페이로드 수신.
     * 스캔 콜백(바인더 스레드)에서 호출됨 — 상태 뮤테이션은 동기화로 보호.
     */
    @Synchronized
    fun onPeerUwbAddressReceived(deviceId: String, peerUwbAddr: ByteArray) {
        if (stopped || !isSupported) return
        // 역할 필터: DEVICE(컨트롤러) ↔ WALKER(컨트롤리) 페어만 UWB — 같은 역할끼리는 BLE 전용
        val expectedPrefix = if (isDeviceMode) BleConstants.WALKER_PREFIX else BleConstants.DEVICE_PREFIX
        if (!deviceId.startsWith(expectedPrefix)) return
        // 컨트롤리는 컨트롤러의 채널 정보까지 4바이트 필요, 컨트롤러는 컨트롤리 주소 2바이트면 충분
        val needed = if (isDeviceMode) 2 else 4
        if (peerUwbAddr.size < needed) return

        val payload = peerUwbAddr.copyOf(needed)
        if (deviceId == activePeerId) {
            if (activePeerPayload?.contentEquals(payload) == true) return   // 변화 없음(dedupe)
            // 피어가 새 스코프로 재광고(상대측 세션 재시작) — 이쪽도 스코프를 갱신해 재합류
            Log.d(TAG, "피어 페이로드 변경 — 세션 재합류: ${deviceId}")
            candidates[deviceId] = payload
            stopActiveLocked()
            scheduleRestartLocked(REJOIN_DELAY_MS)
            return
        }
        candidates[deviceId] = payload
        if (activePeerId == null) {
            if (sessionScope != null) startNextLocked()
            else scheduleRestartLocked(REJOIN_DELAY_MS)   // 스코프 소진 상태 — 갱신 후 시작
        }
    }

    /** BLE 스캔에서 피어 이탈 시 호출 — 후보·거리 제거, 활성 세션 상대였다면 다음 후보로 */
    @Synchronized
    fun onDeviceLost(deviceId: String) {
        candidates.remove(deviceId)
        uwbDistances.remove(deviceId)
        if (deviceId == activePeerId) {
            Log.d(TAG, "활성 UWB 피어 이탈: ${deviceId}")
            stopActiveLocked()
            scheduleRestartLocked(REJOIN_DELAY_MS)
        }
    }

    /** UWB 세션 전체 정리 — stop 후 재사용 불가(BleService 가 필요 시 새 인스턴스를 만든다) */
    @Synchronized
    fun stop() {
        stopped = true
        rangingJob?.cancel()
        rangingJob = null
        activePeerId = null
        activePeerPayload = null
        lastActivePeerId = null
        sessionScope = null
        uwbManager = null
        candidates.clear()
        uwbDistances.clear()
        localAddress = null
        isSupported = false
        Log.d(TAG, "UwbRanger 중지")
    }

    // ── 내부 구현 ──────────────────────────────────────────────────────────

    /** 활성 세션 정리(동기화 블록 안에서만 호출) — prepareSession 이 소비된 스코프는 함께 버린다 */
    private fun stopActiveLocked() {
        rangingJob?.cancel()
        rangingJob = null
        activePeerId?.let { uwbDistances.remove(it) }
        activePeerId = null
        activePeerPayload = null
        sessionScope = null
    }

    /** 스코프 갱신+다음 세션 시작 예약(동기화 블록 안에서만 호출) — 중복 예약 방지 */
    private fun scheduleRestartLocked(delayMs: Long) {
        if (stopped || restartScheduled) return
        restartScheduled = true
        scope.launch {
            try {
                delay(delayMs)
            } finally {
                synchronized(this@UwbRanger) { restartScheduled = false }
            }
            renewScopeAndStartNext()
        }
    }

    /**
     * 새 세션 스코프 생성 → 새 로컬 주소로 BLE 재광고 트리거 → 대기 후보와 세션 시작.
     * 스코프는 1회용이라 세션이 끝날 때마다 여기로 온다. 갱신에 실패하면 다음 피어 광고
     * 수신이 자연 재시도 트리거가 된다(onPeerUwbAddressReceived 의 스코프 부재 분기).
     */
    private suspend fun renewScopeAndStartNext() {
        val mgr = synchronized(this) {
            if (stopped || sessionScope != null || activePeerId != null) return
            uwbManager
        } ?: return
        val newScope = try {
            if (isDeviceMode) mgr.controllerSessionScope() else mgr.controleeSessionScope()
        } catch (e: Exception) {
            Log.w(TAG, "UWB 스코프 갱신 실패(다음 수신 시 재시도): ${e.message}")
            return
        }
        val payload = try {
            buildAdvertisePayload(newScope)
        } catch (e: Exception) {
            Log.w(TAG, "UWB 페이로드 구성 실패: ${e.message}")
            return
        }
        synchronized(this) {
            if (stopped) return
            sessionScope = newScope
            localAddress = newScope.localAddress.address.copyOf()
        }
        onLocalAddressChanged?.invoke(payload)   // BLE 스캔 응답 갱신(재광고)
        synchronized(this) { if (!stopped) startNextLocked() }
    }

    /**
     * (v1.1.32) 대기 후보 중 세션 상대 선정 후 레인징 시작(동기화 블록 안에서만 호출).
     * 선정 규칙: BLE 평활 RSSI 최강 후보 우선 — 가장 가까운 페어가 곧 가장 위험한 페어.
     *   직전 세션 피어가 최강 대비 SWITCH_HYSTERESIS_DB 이내면 유지(재선정 핑퐁 방지).
     * 시작 게이트(배터리 듀티사이클): RSSI 가 게이트 미만이거나 미추적인 후보는 세션을
     *   시작하지 않는다. 후보는 스캔 응답 수신마다 재평가되므로 접근하면 자연 개시.
     *   활성 세션은 게이트와 무관하게 자연 종료까지 유지(시작 시점만 게이트).
     *   rssiOf 미제공(단독 사용) 시엔 게이트·우선순위 없이 종전대로 첫 후보를 쓴다.
     * (v1.1.33) 지게차 낀 쌍 차등 — 게이트는 완화값(uwbStartRssiGateForklift, 기본 -90)을
     *   써서 15m 경고 반경 밖에서도 세션을 열고, 우선순위 rank 에 FORKLIFT_RANK_BIAS_DB 를
     *   가산해 단일 세션 경쟁에서 지게차 쌍이 밀리지 않게 한다(승격 임계가 15/8m 로 넓어
     *   같은 RSSI 면 지게차 쌍의 실측이 더 급하다). forkliftPairOf 미제공 시 종전과 동일.
     */
    private fun startNextLocked() {
        if (stopped || activePeerId != null) return
        val s = sessionScope ?: return
        if (candidates.isEmpty()) return
        fun isForkliftPair(id: String): Boolean = forkliftPairOf?.invoke(id) == true
        fun rankOf(id: String): Int {
            val r = rssiOf?.invoke(id) ?: return Int.MIN_VALUE   // 미추적 — 가산 전 반환(오버플로 방지)
            return r + if (isForkliftPair(id)) FORKLIFT_RANK_BIAS_DB else 0
        }
        val deviceId: String = if (rssiOf == null) {
            candidates.keys.first()
        } else {
            val eligible = candidates.keys.filter {
                val gate = if (isForkliftPair(it)) DevSettings.uwbStartRssiGateForklift
                           else DevSettings.uwbStartRssiGate
                (rssiOf?.invoke(it) ?: Int.MIN_VALUE) >= gate   // 게이트는 raw RSSI 기준(가산 없음)
            }
            if (eligible.isEmpty()) return
            val best = eligible.maxByOrNull { rankOf(it) } ?: return
            lastActivePeerId
                ?.takeIf { it in eligible && rankOf(it) >= rankOf(best) - SWITCH_HYSTERESIS_DB }
                ?: best
        }
        val payload = candidates[deviceId] ?: return
        activePeerId = deviceId
        activePeerPayload = payload
        lastActivePeerId = deviceId
        rangingJob = scope.launch { runSession(s, deviceId, payload) }
        Log.d(TAG, "UWB 세션 시작: ${deviceId} rssi=${rssiOf?.invoke(deviceId)} 후보=${candidates.size}")
    }

    /**
     * 단일 피어와 레인징 세션 실행 — prepareSession 은 스코프당 1회만 소비 가능.
     * CONFIG_UNICAST_DS_TWR(STATIC STS): sessionId/sessionKeyInfo 를 컨트롤러 주소 2바이트에서
     * 양측이 동일하게 유도한다(OOB 광고만으로 파라미터 합의 — 협상 채널 없음).
     */
    private suspend fun runSession(s: UwbClientSessionScope, deviceId: String, payload: ByteArray) {
        val params = try {
            buildParameters(s, payload)
        } catch (e: Exception) {
            Log.w(TAG, "UWB 파라미터 구성 실패: ${e.message}")
            onSessionEnded(deviceId, "파라미터 오류")
            return
        }
        try {
            s.prepareSession(params).collect { result -> handleResult(deviceId, result) }
            onSessionEnded(deviceId, "스트림 종료")
        } catch (e: CancellationException) {
            throw e   // 취소 주체(stopActiveLocked/stop)가 후속을 관리 — 재시작 사이클 중복 방지
        } catch (e: Exception) {
            Log.w(TAG, "UWB 세션 오류: ${e.message}")
            onSessionEnded(deviceId, "오류")
        }
    }

    /** BLE 스캔 응답에 실을 OOB 페이로드 — 컨트롤러 4바이트(주소+실할당 채널), 컨트롤리 2바이트 */
    private fun buildAdvertisePayload(s: UwbClientSessionScope): ByteArray {
        val addr = s.localAddress.address
        return if (s is UwbControllerSessionScope) {
            val ch = s.uwbComplexChannel
            byteArrayOf(addr[0], addr[1], (ch.channel and 0xFF).toByte(), (ch.preambleIndex and 0xFF).toByte())
        } else {
            byteArrayOf(addr[0], addr[1])
        }
    }

    private fun buildParameters(s: UwbClientSessionScope, payload: ByteArray): RangingParameters {
        // 컨트롤러 주소 2바이트 = 세션 파라미터 유도의 단일 기준(양측 동일값 보장)
        val ctrlA0: Byte
        val ctrlA1: Byte
        val complexChannel: UwbComplexChannel
        if (s is UwbControllerSessionScope) {
            val myAddr = s.localAddress.address
            ctrlA0 = myAddr[0]; ctrlA1 = myAddr[1]
            complexChannel = s.uwbComplexChannel          // 시스템이 실할당한 채널(광고로 이미 공유됨)
        } else {
            ctrlA0 = payload[0]; ctrlA1 = payload[1]
            complexChannel = UwbComplexChannel(payload[2].toInt() and 0xFF, payload[3].toInt() and 0xFF)
        }
        val sessionId = SESSION_ID_BASE or ((ctrlA0.toInt() and 0xFF) shl 8) or (ctrlA1.toInt() and 0xFF)
        // STATIC STS 는 정확히 8바이트 키 필수 — "WF" + 컨트롤러 주소 2B + "SAFE"
        val sessionKey = byteArrayOf(0x57, 0x46, ctrlA0, ctrlA1, 0x53, 0x41, 0x46, 0x45)
        val peer = UwbDevice.createForAddress(payload.copyOf(2))
        return RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
            sessionId = sessionId,
            subSessionId = 0,
            sessionKeyInfo = sessionKey,
            subSessionKeyInfo = null,
            complexChannel = complexChannel,
            peerDevices = listOf(peer),
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
        )
    }

    private fun handleResult(deviceId: String, result: RangingResult) {
        when (result) {
            is RangingResult.RangingResultPosition -> {
                val d = result.position.distance?.value ?: return
                uwbDistances[deviceId] = d
                val now = System.currentTimeMillis()
                if (now - lastStatusAt >= STATUS_THROTTLE_MS) {
                    lastStatusAt = now
                    onStatus?.invoke("UWB 거리: ${shortId(deviceId)} ${"%.1f".format(d)}m")
                }
            }
            is RangingResult.RangingResultPeerDisconnected -> {
                Log.d(TAG, "UWB 피어 연결 해제: ${deviceId}")
                onSessionEnded(deviceId, "피어 해제")
            }
            else -> { /* 알 수 없는 결과 타입 — 무시 */ }
        }
    }

    /** 세션 종료 공통 처리 — 현재 활성 세션에 대해서만 1회 동작(중복 종료 무시) */
    @Synchronized
    private fun onSessionEnded(deviceId: String, reason: String) {
        if (stopped || activePeerId != deviceId) return
        Log.d(TAG, "UWB 세션 종료(${reason}): ${deviceId}")
        stopActiveLocked()
        scheduleRestartLocked(RESTART_BACKOFF_MS)
    }

    private fun shortId(deviceId: String): String =
        deviceId.removePrefix(BleConstants.DEVICE_PREFIX).removePrefix(BleConstants.WALKER_PREFIX).take(6)

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
