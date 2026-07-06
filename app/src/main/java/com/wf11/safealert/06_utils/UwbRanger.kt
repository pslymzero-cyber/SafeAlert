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
 * (v1.1.37) UWB 정밀 거리 측정 — androidx.core.uwb 1.0.0-alpha09 실구현. 다중기기 재작성.
 *
 * 역할 선출(고정 아님): 모든 페어가 UWB 를 시도한다. 누가 컨트롤러/컨트롤리가 될지는 링크마다
 * BLE 로 이미 보이는 정보(이름 프리픽스 → 차량/보행자, fullId → 동급 타이브레이크)만으로 양측이
 * 동일하게 계산한다. 이 선출은 UWB 주소를 필요로 하지 않으므로(구 버전이 역할을 고정한 근거였던
 * '상호 주소를 알기 전엔 스코프를 못 만드는 순환 의존'이 사라진다) 같은 역할 페어도 UWB 를 쓴다.
 *   · 차량(지게차/EPJ, myIsVehicle=true) > 보행자(false) — 차량이 컨트롤러.
 *   · 동급이면 fullId 가 작은 쪽이 컨트롤러.
 *
 * 다중기기: 컨트롤러는 CONFIG_MULTICAST_DS_TWR 로 자신이 상위인 컨트롤리들을 동시에 측정한다.
 * 단일 세션 하드웨어(대다수 폰) 현실상 한 기기는 한 시점에 스코프 1개(=역할 1개)만 돌린다. 그래서
 * 링크별 선출은 '의도'이고, 실제 집계 역할은 위험도 우선순위로 정한다: 가장 위험한 링크에 그 한
 * 세션을 쓴다(차량→컨트롤러로 보행자들 멀티캐스트 / 보행자→가장 급한 차량에 컨트롤리로 합류 /
 * 차량 없는 보행자쌍→fullId 로 선출). 하드웨어가 못 감당하는 링크는 조용히 RSSI 로 폴백 —
 * 오늘보다 나빠지지 않는다.
 *
 * OOB(대역외) 합의: BLE 0x9ABC 스캔 응답으로 컨트롤러는 4바이트(주소2 + 채널 + 프리앰블),
 * 컨트롤리는 2바이트(주소)를 광고한다(와이어 포맷은 종전과 동일 — 광고 주체만 선출로 바뀜).
 * sessionId 와 STATIC STS 8바이트 키는 컨트롤러 주소 2바이트에서 양측이 동일하게 유도한다.
 *
 * 안전 불변식: 모든 UWB 연산은 try/catch → 실패 시 조용히 RSSI 폴백. [uwbDistances] 는 유한한
 * 실측이 있을 때만 채워지고(경보·거리 표시 파이프라인은 부재 시 RSSI 사용), 이 클래스는 측정값
 * 저장 + 상태줄 통지 외에 경보 로직에 개입하지 않는다.
 */
class UwbRanger(
    private val context: Context,
    private val scope: CoroutineScope,
    private val myFullId: String,               // 내 전체 광고 ID(prefix+id) — 동급 선출 비교 기준
    private val myIsVehicle: Boolean,           // 내가 차량(지게차/EPJ, DEVICE 모드)인가
    private val onStatus: ((String) -> Unit)? = null,
    private val onLocalAddressChanged: ((ByteArray) -> Unit)? = null,
    private val rssiOf: ((String) -> Int?)? = null,   // deviceId → 최근 평활 RSSI(dBm) — 세션 우선순위·시작 게이트용
    private val forkliftPairOf: ((String) -> Boolean)? = null   // deviceId → 지게차 낀 쌍 여부 — 게이트 완화·우선순위 가산용
) {
    companion object {
        private const val TAG = "UwbRanger"
        private const val SESSION_ID_BASE = 0x00570000        // 'W'(0x57) 프리픽스 — 앱 고유 네임스페이스
        private const val RESTART_BACKOFF_MS = 10_000L        // 세션 오류·피어 해제 후 재시도 대기
        private const val REJOIN_DELAY_MS = 1_000L            // 피어 재광고·이탈 등 즉시성 재시작(디바운스) 대기
        private const val STATUS_THROTTLE_MS = 3_000L         // 거리 상태줄 전파 최소 간격
        private const val SWITCH_HYSTERESIS_DB = 6            // 컨트롤러 재선정 핑퐁 방지 히스테리시스
        private const val FORKLIFT_RANK_BIAS_DB = 12          // 지게차 낀 쌍 우선순위 가산 — 단일 세션 경쟁에서 15m 경고가 밀리지 않게
        private const val GATE_RELEASE_MARGIN_DB = 8         // 이미 측정 중인 상대는 게이트를 이만큼 완화해 유지(경계 진동 억제)
        private const val MULTICAST_MAX = 6                  // 한 컨트롤러가 동시에 측정할 컨트롤리 상한(하드웨어 여유·튜닝 지점)

        // 접근속도 운동학 — dt 연속성 창·평활 계수·이탈 데드밴드
        private const val KIN_DT_MIN_MS = 60L         // 이보다 촘촘한 표본은 미분 노이즈 증폭 — 직전 기준점 유지
        private const val KIN_DT_MAX_MS = 2000L       // 이보다 벌어지면 연속성 단절 — 운동학 리셋
        private const val KIN_EMA_ALPHA = 0.45f       // 평활 접근속도 EMA 계수(표본 약 240ms 간격 기준)
        private const val SEP_MIN_MPS = 0.15f         // 이탈 streak 최소 속도(노이즈 데드밴드)

        /** 하드웨어 UWB 지원 여부 (API 31+ & FEATURE_UWB) */
        fun isHardwareSupported(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31) return false
            return context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)
        }

        // (v1.1.38 C) UWB 실가동 진단 스냅샷 — 같은 프로세스(BleService + Activity 단일 프로세스)의
        //   개발자설정 화면이 IPC 없이 직접 읽는다. UwbRanger 는 한 번에 하나만 살아 있고(BleService 가
        //   stop→null→새 인스턴스 순서로 교체, stop 은 동기) 옛 인스턴스 stop() 이 이 값을 리셋하므로
        //   경합 없이 최신 인스턴스 상태만 노출된다. publishDiag() 가 상태 변동점마다 갱신.
        @Volatile var liveActive: Boolean = false      // 세션 스코프 열림(=UWB 초기화 성공, BLE 폴백 아님)
            private set
        @Volatile var liveRole: String = "-"           // 대기 / 컨트롤러 / 컨트롤리 / -
            private set
        @Volatile var liveSessionCount: Int = 0        // 현재 UWB 실측 거리를 수신 중인 피어 수
            private set
    }

    private enum class Role { NONE, CONTROLLER, CONTROLEE }

    /** 재구성 목표(순수 계산 결과) — NONE=대기(컨트롤리 광고만) / CONTROLLER=멀티캐스트 측정 / CONTROLEE=합류 */
    private class Desired(
        val role: Role,
        val controllerId: String?,          // CONTROLEE: 합류할 컨트롤러
        val controllerPayload: ByteArray?,  // CONTROLEE: 그 컨트롤러의 4바이트 OOB
        val controlees: List<String>        // CONTROLLER: 측정할 컨트롤리 deviceId(우선순위순)
    )

    /** UWB 실가동 여부 — initSession() 성공 시 true (미지원·권한 없음·초기화 실패 = false → BLE 폴백) */
    @Volatile var isSupported: Boolean = false
        private set

    /** deviceId(fullId) → 최근 UWB 실측 거리(m). 세션 없는 기기는 항상 부재 → BLE 폴백 */
    val uwbDistances: MutableMap<String, Float> = ConcurrentHashMap()

    // UWB 실측 운동학 — 연속 거리 표본을 미분한 접근속도(+ = 접근)와 지속 표본 수.
    //   uwbDistances 와 같은 수명(세션 종료·피어 이탈·중지 시 즉시 제거) — 값이 있으면 라이브 실측.
    data class UwbKin(val closingMps: Float, val approachStreak: Int, val separatingStreak: Int, val atMs: Long)

    /** deviceId(fullId) → 접근속도 운동학. BleService 속도 승격/이탈 해제 판정용(부재 = 개입 없음) */
    val uwbKinematics: MutableMap<String, UwbKin> = ConcurrentHashMap()
    private val lastSampleMap = ConcurrentHashMap<String, Pair<Long, Float>>()   // deviceId → (시각, 거리) 직전 표본

    /** 이 기기의 UWB 로컬 주소 2바이트 (스코프 갱신 때마다 새 값) */
    @Volatile var localAddress: ByteArray? = null
        private set

    private var uwbManager: UwbManager? = null
    private var sessionScope: UwbClientSessionScope? = null   // 1스코프=1세션 — prepareSession 후 소진
    private var rangingJob: Job? = null
    private var sessionGen = 0                                // 세션 세대 토큰 — stale 콜백 무효화

    @Volatile private var role: Role = Role.NONE

    // CONTROLEE 상태 — 내가 합류한 컨트롤러
    @Volatile private var activeControllerId: String? = null
    @Volatile private var activeControllerPayload: ByteArray? = null
    @Volatile private var activeControllerAddrHex: String? = null   // 결과 귀속 매칭용(컨트롤러 주소 2B)
    private var lastActiveControllerId: String? = null              // 컨트롤러 재선정 히스테리시스 기준

    // CONTROLLER 상태 — 내가 측정 중인 컨트롤리들
    private val servedControlees = LinkedHashMap<String, ByteArray>()      // deviceId → 컨트롤리 주소 2B
    private val servedAddrToId = ConcurrentHashMap<String, String>()       // 주소hex → deviceId (멀티캐스트 결과 귀속)

    private val candidates = LinkedHashMap<String, ByteArray>()   // deviceId → 최신 OOB 페이로드(2B/4B)
    private var restartScheduled = false
    private var stopped = false
    @Volatile private var lastStatusAt = 0L

    /**
     * UWB 세션 스코프 초기화 및 BLE 광고용 OOB 페이로드 획득.
     * 대기 역할은 컨트롤리(2바이트) — 도착하는 상위 기기가 즉시 발견·합류할 수 있게 한다. 실제 역할은
     * 피어가 보이는 대로 reconcile 이 승격/합류로 바꾼다. 실패(미지원·권한 없음·UWB OFF·오류) 시 null
     * 반환 → 호출부(BleService)는 UWB 없는 광고를 그대로 유지한다.
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
            val s = mgr.controleeSessionScope()          // 대기 역할 = 컨트롤리(합류 가능 상태로 발견되게)
            val payload = buildAdvertisePayload(s)        // 2바이트
            synchronized(this) {
                if (stopped) return null
                uwbManager = mgr
                sessionScope = s
                localAddress = s.localAddress.address.copyOf()
                role = Role.NONE
                isSupported = true
            }
            publishDiag()
            Log.i(TAG, "UWB 초기화 완료(대기=컨트롤리, vehicle=$myIsVehicle) payload=${payload.toHex()}")
            payload
        } catch (e: Exception) {
            Log.w(TAG, "UWB 초기화 실패 — BLE 전용으로 동작: ${e.message}")
            null
        }
    }

    /**
     * BLE 스캔 응답(0x9ABC)에서 피어의 UWB OOB 페이로드 수신 — 역할·소스 제한 없이 모든 피어 수용.
     * 스캔 콜백(바인더 스레드)에서 호출됨 — 상태 뮤테이션은 동기화로 보호. 매 수신마다 reconcile 을
     * 돌려 RSSI/게이트 변화까지 반영하되, 실제 세션 재구성은 목표가 바뀔 때만(디바운스+가드).
     */
    @Synchronized
    fun onPeerUwbAddressReceived(deviceId: String, peerUwbAddr: ByteArray) {
        if (stopped || !isSupported) return
        if (peerUwbAddr.size < 2) return
        candidates[deviceId] = peerUwbAddr.copyOf(minOf(peerUwbAddr.size, 4))   // 컨트롤러 4B / 컨트롤리 2B
        reconcileLocked()
    }

    /** BLE 스캔에서 피어 이탈 시 호출 — 후보·거리 제거, 활성 상대였다면 재구성 */
    @Synchronized
    fun onDeviceLost(deviceId: String) {
        candidates.remove(deviceId)
        dropServedLocked(deviceId)   // 서빙 중이었으면 서빙맵·거리 제거(아니어도 거리/운동학 정리 — 무해)
        if (deviceId == activeControllerId) {
            Log.d(TAG, "합류 중이던 컨트롤러 이탈: $deviceId")
            stopActiveLocked()
            scheduleRestartLocked(REJOIN_DELAY_MS)
        } else {
            reconcileLocked()   // 남은 상대로 역할 재평가(가드가 불필요한 재구성 차단)
        }
    }

    /** UWB 세션 전체 정리 — stop 후 재사용 불가(BleService 가 필요 시 새 인스턴스를 만든다) */
    @Synchronized
    fun stop() {
        stopped = true
        rangingJob?.cancel()
        rangingJob = null
        sessionGen++
        sessionScope = null
        uwbManager = null
        role = Role.NONE
        activeControllerId = null
        activeControllerPayload = null
        activeControllerAddrHex = null
        lastActiveControllerId = null
        servedControlees.clear()
        servedAddrToId.clear()
        candidates.clear()
        uwbDistances.clear()
        uwbKinematics.clear()
        lastSampleMap.clear()
        localAddress = null
        isSupported = false
        publishDiag()
        Log.d(TAG, "UwbRanger 중지")
    }

    /** (v1.1.38 C) 진단 스냅샷 발행 — companion @Volatile 필드로 같은 프로세스의 개발자설정 UI 가 IPC 없이 읽는다.
     *   role 은 세션 상태 필드, uwbDistances 는 ConcurrentHashMap 이라 호출 지점(락 안/밖) 무관하게 안전. */
    private fun publishDiag() {
        liveActive = isSupported
        liveRole = when (role) {
            Role.CONTROLLER -> "컨트롤러"
            Role.CONTROLEE  -> "컨트롤리"
            Role.NONE       -> if (isSupported) "대기" else "-"
        }
        liveSessionCount = uwbDistances.size
    }

    // ── 선출·위험도(BLE 가시 정보만) ────────────────────────────────────────

    /** 차량 여부는 이름 프리픽스로 판정 — UWB 주소 도착 전에도 확정(경합 없음) */
    private fun peerIsVehicle(id: String): Boolean = id.startsWith(BleConstants.DEVICE_PREFIX)

    /** 이 링크에서 피어가 나보다 상위인가(피어가 컨트롤러여야 하는가) */
    private fun peerOutranksMe(id: String): Boolean {
        val pv = peerIsVehicle(id)
        if (pv != myIsVehicle) return pv   // 차량 > 보행자
        return id < myFullId               // 동급 — 작은 fullId 가 컨트롤러
    }

    /** 링크 위험도: 차량↔보행자=2(최우선), 차량↔차량=1, 보행자↔보행자=0 */
    private fun pairDanger(peerVeh: Boolean): Int =
        if (peerVeh != myIsVehicle) 2 else if (myIsVehicle) 1 else 0

    private fun isForkliftPair(id: String): Boolean = forkliftPairOf?.invoke(id) == true

    /** 세션 우선순위 랭크 — RSSI(강할수록 가까움) + 지게차 가산 */
    private fun rankOf(id: String): Int =
        (rssiOf?.invoke(id) ?: 0) + if (isForkliftPair(id)) FORKLIFT_RANK_BIAS_DB else 0

    /** 시작 게이트 통과 여부 — 이미 측정 중인 상대는 완화(경계 진동 억제). rssiOf 미제공 시 항상 통과 */
    private fun gatePassLocked(id: String): Boolean {
        if (DevSettings.uwbForce) return true   // (v1.1.38 B) 강제 활성화 — RSSI 거리 게이트 우회(무조건 통과)
        val f = rssiOf ?: return true
        val base = if (isForkliftPair(id)) DevSettings.uwbStartRssiGateForklift else DevSettings.uwbStartRssiGate
        val active = servedControlees.containsKey(id) || id == activeControllerId
        val gate = if (active) base - GATE_RELEASE_MARGIN_DB else base
        return (f.invoke(id) ?: Int.MIN_VALUE) >= gate
    }

    // ── 재구성(reconcile) ──────────────────────────────────────────────────

    /**
     * 현재 후보/역할/RSSI 로 목표 세션을 계산(순수). 단일 세션 하드웨어 전제로 집계 역할을 위험도
     * 우선순위로 정한다. 실제 측정은 상대가 '호환되는 포맷'으로 광고 중인 링크만(컨트롤러는 2B
     * 컨트롤리를, 컨트롤리는 4B 컨트롤러를) 대상으로 하며, 어긋난 링크는 조용히 RSSI 로 남는다.
     */
    private fun computeDesiredLocked(): Desired {
        if (candidates.isEmpty()) return Desired(Role.NONE, null, null, emptyList())
        val joinable = ArrayList<String>()      // 피어가 상위 + 컨트롤러(4B) 광고 + 게이트 → 합류 후보
        val controllable = ArrayList<String>()  // 내가 상위 + 컨트롤리(2B) 광고 + 게이트 → 측정 후보
        for ((id, p) in candidates) {
            if (!gatePassLocked(id)) continue
            val out = peerOutranksMe(id)
            if (out && p.size >= 4) joinable.add(id)
            else if (!out && p.size < 4) controllable.add(id)
        }
        val dangerControl = controllable.maxOfOrNull { pairDanger(peerIsVehicle(it)) } ?: -1
        val dangerJoin = joinable.maxOfOrNull { pairDanger(peerIsVehicle(it)) } ?: -1

        // 더 위험한 방향에 단일 세션을 쓴다. 동률이면 컨트롤러 우선(멀티캐스트로 다수를 커버).
        if (controllable.isNotEmpty() && dangerControl >= dangerJoin) {
            val sorted = controllable.sortedByDescending { rankOf(it) }
            val served = sorted.take(MULTICAST_MAX)
            if (served.size < controllable.size) {
                Log.i(TAG, "멀티캐스트 상한 초과 — ${controllable.size}→${served.size} (나머지 RSSI 폴백)")
            }
            return Desired(Role.CONTROLLER, null, null, served)
        }
        if (joinable.isNotEmpty()) {
            val best = chooseControllerLocked(joinable)
                ?: return Desired(Role.NONE, null, null, emptyList())
            return Desired(Role.CONTROLEE, best, candidates[best], emptyList())
        }
        return Desired(Role.NONE, null, null, emptyList())
    }

    /** 합류할 컨트롤러 선정: 위험도 우선 → 랭크(가까움) 우선, 직전 선택은 히스테리시스로 유지 */
    private fun chooseControllerLocked(joinable: List<String>): String? {
        if (joinable.isEmpty()) return null
        val best = joinable.sortedWith(
            compareByDescending<String> { pairDanger(peerIsVehicle(it)) }.thenByDescending { rankOf(it) }
        ).first()
        val keep = lastActiveControllerId?.takeIf {
            it in joinable &&
                pairDanger(peerIsVehicle(it)) >= pairDanger(peerIsVehicle(best)) &&
                rankOf(it) >= rankOf(best) - SWITCH_HYSTERESIS_DB
        }
        return keep ?: best
    }

    /** 현재 가동 세션이 목표와 동일한가(동일하면 재구성 불필요) */
    private fun sameAsActiveLocked(d: Desired): Boolean {
        if (d.role != role) return false
        return when (d.role) {
            Role.NONE -> true
            Role.CONTROLLER -> {
                if (d.controlees.size != servedControlees.size) return false
                d.controlees.all { id ->
                    val cur = servedControlees[id] ?: return@all false
                    val want = candidates[id]?.copyOf(2) ?: return@all false
                    cur.contentEquals(want)
                }
            }
            Role.CONTROLEE -> d.controllerId == activeControllerId &&
                (d.controllerPayload?.contentEquals(activeControllerPayload ?: ByteArray(0)) == true)
        }
    }

    /** 재구성이 필요한가 — 대기 목표는 가동 세션이 있으면 정지 필요, 그 외는 미가동/불일치면 필요 */
    private fun needsRebuildLocked(): Boolean {
        val d = computeDesiredLocked()
        return if (d.role == Role.NONE) {
            rangingJob != null || role != Role.NONE
        } else {
            rangingJob == null || !sameAsActiveLocked(d)
        }
    }

    private fun reconcileLocked() {
        if (stopped || uwbManager == null) return
        if (needsRebuildLocked()) scheduleRestartLocked(REJOIN_DELAY_MS)
    }

    /** 재구성 예약(동기화 블록 안에서만) — 디바운스(중복 예약 방지). 정지는 fire 시점에 renew 가 수행 */
    private fun scheduleRestartLocked(delayMs: Long) {
        if (stopped || restartScheduled) return
        restartScheduled = true
        scope.launch {
            try {
                delay(delayMs)
            } finally {
                synchronized(this@UwbRanger) { restartScheduled = false }
            }
            renewAndStart()
        }
    }

    /** 활성 세션 정리(동기화 블록 안에서만) — 세대 증가로 in-flight 콜백을 무효화하고 상태를 대기로 */
    private fun stopActiveLocked() {
        rangingJob?.cancel()
        rangingJob = null
        sessionGen++
        sessionScope = null
        servedControlees.keys.forEach { uwbDistances.remove(it); uwbKinematics.remove(it); lastSampleMap.remove(it) }
        activeControllerId?.let { uwbDistances.remove(it); uwbKinematics.remove(it); lastSampleMap.remove(it) }
        servedControlees.clear()
        servedAddrToId.clear()
        activeControllerId = null
        activeControllerPayload = null
        activeControllerAddrHex = null
        role = Role.NONE
        publishDiag()
    }

    /** 서빙 컨트롤리 1개 제거(동기화 블록 안에서만) — 서빙맵·거리·운동학 동시 정리 */
    private fun dropServedLocked(id: String) {
        val addr = servedControlees.remove(id)
        if (addr != null) servedAddrToId.remove(addr.toHex())
        uwbDistances.remove(id)
        uwbKinematics.remove(id)
        lastSampleMap.remove(id)
        publishDiag()
    }

    /**
     * 목표 역할에 맞는 새 스코프 생성 → 새 로컬 주소로 BLE 재광고 → 세션 시작. 스코프는 1회용이라
     * 세션이 끝나거나 목표가 바뀔 때마다 여기로 온다. 정지→생성 사이의 짧은 공백은 RSSI 가 덮는다.
     * 스코프 생성/파라미터 실패는 조용히 폴백(다음 스캔 수신 또는 백오프가 자연 재시도).
     */
    private suspend fun renewAndStart() {
        val plan = synchronized(this) {
            if (stopped) return
            val mgr = uwbManager ?: return
            val desired = computeDesiredLocked()
            if (rangingJob != null && sameAsActiveLocked(desired)) return   // 이미 목표대로 가동 중 — 유지
            stopActiveLocked()                                             // 현 세션 정리(세대 증가)
            Triple(mgr, desired, sessionGen)
        }
        val (mgr, desired, gen) = plan

        when (desired.role) {
            Role.NONE -> {
                // 대기: 발견 가능한 컨트롤리로 광고만(세션 없음)
                val sc = try {
                    mgr.controleeSessionScope()
                } catch (e: Exception) {
                    Log.w(TAG, "UWB 대기 스코프 생성 실패(백오프 재시도): ${e.message}")
                    synchronized(this) { scheduleRestartLocked(RESTART_BACKOFF_MS) }
                    return
                }
                val payload = try { buildAdvertisePayload(sc) } catch (e: Exception) { return }
                synchronized(this) {
                    if (stopped || gen != sessionGen) return
                    sessionScope = sc
                    localAddress = sc.localAddress.address.copyOf()
                    role = Role.NONE
                    publishDiag()
                }
                onLocalAddressChanged?.invoke(payload)
            }

            Role.CONTROLLER -> {
                val sc: UwbControllerSessionScope = try {
                    mgr.controllerSessionScope()
                } catch (e: Exception) {
                    Log.w(TAG, "UWB 컨트롤러 스코프 생성 실패(백오프 재시도): ${e.message}")
                    synchronized(this) { scheduleRestartLocked(RESTART_BACKOFF_MS) }
                    return
                }
                val payload = try { buildAdvertisePayload(sc) } catch (e: Exception) { return }
                val served = LinkedHashMap<String, ByteArray>()
                synchronized(this) {
                    if (stopped || gen != sessionGen) return
                    sessionScope = sc
                    localAddress = sc.localAddress.address.copyOf()
                    role = Role.CONTROLLER
                    servedControlees.clear(); servedAddrToId.clear()
                    for (id in desired.controlees) {
                        val p = candidates[id] ?: continue      // 계산~구성 사이 이탈한 후보는 스킵
                        val addr = p.copyOf(2)
                        served[id] = addr
                        servedControlees[id] = addr
                        servedAddrToId[addr.toHex()] = id
                    }
                    publishDiag()
                }
                onLocalAddressChanged?.invoke(payload)
                if (served.isEmpty()) {
                    // 구성 시점에 대상이 모두 사라짐 — 다음 수신/재구성이 정정
                    synchronized(this) { if (gen == sessionGen && !stopped) scheduleRestartLocked(REJOIN_DELAY_MS) }
                    return
                }
                val job = scope.launch { runControllerSession(sc, served, gen) }
                synchronized(this) { if (gen == sessionGen && !stopped) rangingJob = job else job.cancel() }
                onStatus?.invoke("UWB 컨트롤러: ${served.size}대 측정")
                Log.d(TAG, "UWB 컨트롤러 시작 — ${served.keys.joinToString { shortId(it) }}")
            }

            Role.CONTROLEE -> {
                val cid = desired.controllerId ?: return
                val cpayload = desired.controllerPayload ?: return
                if (cpayload.size < 4) return
                val sc = try {
                    mgr.controleeSessionScope()
                } catch (e: Exception) {
                    Log.w(TAG, "UWB 컨트롤리 스코프 생성 실패(백오프 재시도): ${e.message}")
                    synchronized(this) { scheduleRestartLocked(RESTART_BACKOFF_MS) }
                    return
                }
                val payload = try { buildAdvertisePayload(sc) } catch (e: Exception) { return }
                synchronized(this) {
                    if (stopped || gen != sessionGen) return
                    sessionScope = sc
                    localAddress = sc.localAddress.address.copyOf()
                    role = Role.CONTROLEE
                    activeControllerId = cid
                    activeControllerPayload = cpayload
                    activeControllerAddrHex = cpayload.copyOf(2).toHex()
                    lastActiveControllerId = cid
                    publishDiag()
                }
                onLocalAddressChanged?.invoke(payload)
                val job = scope.launch { runControleeSession(sc, cpayload, gen) }
                synchronized(this) { if (gen == sessionGen && !stopped) rangingJob = job else job.cancel() }
                onStatus?.invoke("UWB 합류: ${shortId(cid)}")
                Log.d(TAG, "UWB 컨트롤리 시작 — 컨트롤러 ${shortId(cid)}")
            }
        }
    }

    /**
     * 컨트롤러 멀티캐스트 세션 — CONFIG_MULTICAST_DS_TWR 로 여러 컨트롤리를 동시에 측정.
     * sessionId/키는 내 컨트롤러 주소 2바이트에서 유도(컨트롤리들이 광고로 동일 유도).
     */
    private suspend fun runControllerSession(
        sc: UwbControllerSessionScope, served: Map<String, ByteArray>, gen: Int
    ) {
        val params = try {
            buildMulticastParameters(sc, served.values.toList())
        } catch (e: Exception) {
            Log.w(TAG, "컨트롤러 파라미터 구성 실패: ${e.message}")
            onSessionEnded(gen, "파라미터 오류"); return
        }
        try {
            sc.prepareSession(params).collect { result -> handleResult(result) }
            onSessionEnded(gen, "스트림 종료")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "컨트롤러 세션 오류: ${e.message}")
            onSessionEnded(gen, "오류")
        }
    }

    /** 컨트롤리 세션 — 지정 컨트롤러에 합류(단일 피어). 결과는 activeControllerAddrHex→activeControllerId 로 귀속 */
    private suspend fun runControleeSession(
        sc: UwbClientSessionScope, controllerPayload: ByteArray, gen: Int
    ) {
        val params = try {
            buildJoinParameters(controllerPayload)
        } catch (e: Exception) {
            Log.w(TAG, "컨트롤리 파라미터 구성 실패: ${e.message}")
            onSessionEnded(gen, "파라미터 오류"); return
        }
        try {
            sc.prepareSession(params).collect { result -> handleResult(result) }
            onSessionEnded(gen, "스트림 종료")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "컨트롤리 세션 오류: ${e.message}")
            onSessionEnded(gen, "오류")
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

    /** 컨트롤러(멀티캐스트) 파라미터 — 내 주소에서 sessionId/키 유도, 컨트롤리 다수를 peerDevices 로 */
    private fun buildMulticastParameters(
        sc: UwbControllerSessionScope, controleeAddrs: List<ByteArray>
    ): RangingParameters {
        val myAddr = sc.localAddress.address
        val a0 = myAddr[0]; val a1 = myAddr[1]
        val peers = controleeAddrs.map { UwbDevice.createForAddress(it.copyOf(2)) }
        return RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
            sessionId = deriveSessionId(a0, a1),
            subSessionId = 0,
            sessionKeyInfo = deriveSessionKey(a0, a1),
            subSessionKeyInfo = null,
            complexChannel = sc.uwbComplexChannel,   // 시스템이 실할당한 채널(광고로 이미 공유됨)
            peerDevices = peers,
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
        )
    }

    /** 컨트롤리 파라미터 — 컨트롤러의 4바이트 OOB(주소2+채널+프리앰블)에서 세션을 동일 유도 */
    private fun buildJoinParameters(controllerPayload: ByteArray): RangingParameters {
        val a0 = controllerPayload[0]; val a1 = controllerPayload[1]
        val ch = UwbComplexChannel(controllerPayload[2].toInt() and 0xFF, controllerPayload[3].toInt() and 0xFF)
        val controller = UwbDevice.createForAddress(controllerPayload.copyOf(2))
        return RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
            sessionId = deriveSessionId(a0, a1),
            subSessionId = 0,
            sessionKeyInfo = deriveSessionKey(a0, a1),
            subSessionKeyInfo = null,
            complexChannel = ch,
            peerDevices = listOf(controller),
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
        )
    }

    // 컨트롤러 주소 2바이트 = 세션 파라미터 유도의 단일 기준(양측 동일값 보장)
    private fun deriveSessionId(a0: Byte, a1: Byte): Int =
        SESSION_ID_BASE or ((a0.toInt() and 0xFF) shl 8) or (a1.toInt() and 0xFF)

    // STATIC STS 는 정확히 8바이트 키 필수 — "WF" + 컨트롤러 주소 2B + "SAFE"
    private fun deriveSessionKey(a0: Byte, a1: Byte): ByteArray =
        byteArrayOf(0x57, 0x46, a0, a1, 0x53, 0x41, 0x46, 0x45)

    /** 레인징 결과 처리 — 멀티캐스트는 result.device.address 로 어느 피어인지 역매핑 */
    private fun handleResult(result: RangingResult) {
        when (result) {
            is RangingResult.RangingResultPosition -> {
                val d = result.position.distance?.value ?: return
                if (!d.isFinite()) return   // [v1.1.37 ①] NaN/±Inf 표본 차단 — 운동학·보정 오염 및 roundToInt 예외 방지
                val id = peerIdForResult(result.device) ?: return
                uwbDistances[id] = d
                publishDiag()
                val now = System.currentTimeMillis()
                updateKinematics(id, d, now)
                if (now - lastStatusAt >= STATUS_THROTTLE_MS) {
                    lastStatusAt = now
                    onStatus?.invoke("UWB 거리: ${shortId(id)} ${"%.1f".format(d)}m")
                }
            }
            is RangingResult.RangingResultPeerDisconnected -> {
                val id = peerIdForResult(result.device)
                Log.d(TAG, "UWB 피어 연결 해제: ${id ?: "?"}")
                onPeerDisconnected(id)
            }
            else -> { /* 알 수 없는 결과 타입 — 무시 */ }
        }
    }

    /** 결과의 주소를 deviceId 로 역매핑 — 컨트롤러는 서빙맵, 컨트롤리는 활성 컨트롤러 */
    private fun peerIdForResult(device: UwbDevice): String? {
        val hex = device.address.address.toHex()
        return when (role) {
            Role.CONTROLLER -> servedAddrToId[hex]
            Role.CONTROLEE -> if (hex == activeControllerAddrHex) activeControllerId else null
            else -> null
        }
    }

    /** UWB 레벨 피어 해제 처리 — 컨트롤러는 해당 컨트롤리만 정리(남으면 유지), 컨트롤리는 재합류 */
    @Synchronized
    private fun onPeerDisconnected(id: String?) {
        if (stopped) return
        when (role) {
            Role.CONTROLLER -> {
                if (id != null) dropServedLocked(id)
                if (servedControlees.isEmpty()) {
                    stopActiveLocked()
                    scheduleRestartLocked(REJOIN_DELAY_MS)
                } else {
                    reconcileLocked()   // 대체 컨트롤리 승격 여지 확인(가드가 불필요 재구성 차단)
                }
            }
            Role.CONTROLEE -> {
                stopActiveLocked()
                scheduleRestartLocked(REJOIN_DELAY_MS)
            }
            else -> { /* 대기 상태 — 무시 */ }
        }
    }

    // 접근속도 운동학 갱신 — 연속 표본 미분(+ = 접근). 세션 스레드 단일 호출 전제.
    //   dt 창 밖(너무 촘촘/단절)은 각각 기준점 유지/리셋으로 미분 노이즈·유령 속도를 차단한다.
    //   approach/separating streak 은 상호배타(서로 리셋) — 같은 표본이 양쪽에 설 수 없다.
    private fun updateKinematics(deviceId: String, distM: Float, now: Long) {
        val prev = lastSampleMap.put(deviceId, now to distM) ?: return
        val dtMs = now - prev.first
        if (dtMs < KIN_DT_MIN_MS) { lastSampleMap[deviceId] = prev; return }   // 과밀 표본 — 직전 기준점 유지
        if (dtMs > KIN_DT_MAX_MS) { uwbKinematics.remove(deviceId); return }   // 연속성 단절 — 리셋 후 재축적
        val instMps = (prev.second - distM) / (dtMs / 1000f)
        val approachMps = DevSettings.uwbApproachSpeedKmh / 3.6f
        val k = uwbKinematics[deviceId]
        val ema = if (k == null) instMps else k.closingMps + KIN_EMA_ALPHA * (instMps - k.closingMps)
        uwbKinematics[deviceId] = UwbKin(
            closingMps = ema,
            approachStreak = if (instMps >= approachMps) (k?.approachStreak ?: 0) + 1 else 0,
            separatingStreak = if (instMps <= -SEP_MIN_MPS) (k?.separatingStreak ?: 0) + 1 else 0,
            atMs = now
        )
    }

    /** 세션 종료 공통 처리 — 현 세대에 대해서만 1회 동작(stale 세대 콜백 무시) */
    @Synchronized
    private fun onSessionEnded(gen: Int, reason: String) {
        if (stopped || gen != sessionGen) return
        Log.d(TAG, "UWB 세션 종료($reason) role=$role")
        stopActiveLocked()
        scheduleRestartLocked(RESTART_BACKOFF_MS)
    }

    private fun shortId(deviceId: String): String =
        deviceId.removePrefix(BleConstants.DEVICE_PREFIX).removePrefix(BleConstants.WALKER_PREFIX).take(6)

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
