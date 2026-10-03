package com.wf11.safealert.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
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
 * UWB precise ranging — implementation on androidx.core.uwb 1.0.0-alpha09, multi-device.
 *
 * Role election (not fixed): every pair that passes the RSSI start gate (stronger than -80dBm) is eligible
 * for UWB. Who becomes controller/controlee is computed
 * per link, identically on both sides, from information BLE already shows (name prefix → vehicle/walker,
 * fullId → tie-break within the same class). The election needs no UWB address, which avoids the circular
 * dependency of needing each other's address before a scope can be created, so same-role pairs use UWB too.
 *   · Vehicle (forklift/EPJ, myIsVehicle=true) > walker (false) — the vehicle is the controller.
 *   · Same class: the smaller fullId is the controller.
 *
 * Multi-device: a controller ranges the controlees it outranks at once via CONFIG_MULTICAST_DS_TWR (up to
 * MULTICAST_MAX, by rank).
 * Most phones support only a single session, so a device runs one scope (= one role) at a time. Per-link
 * election is therefore the intent; the actual role is chosen by risk priority, spending that one session on
 * the riskiest link (vehicle → controller multicasting to walkers / walker → joins the most urgent vehicle as
 * controlee / walker pair without vehicles → elected by fullId). Links the hardware can't serve silently fall
 * back to RSSI — never worse than RSSI alone.
 *
 * OOB agreement: in the BLE 0x9ABC scan response the controller advertises 4 bytes (2-byte address + channel +
 * preamble) and the controlee 2 bytes (address). The wire format is fixed; election only decides who
 * advertises what. Both sides derive sessionId and the 8-byte STATIC STS key identically from the
 * controller's 2-byte address.
 *
 * Safety invariant: every UWB call is wrapped in try/catch and silently falls back to RSSI on failure.
 * [uwbDistances] holds only finite measurements (alert and distance-display pipelines use RSSI when absent),
 * and this class makes no alert decisions: it stores measurements and kinematics, pushes each sample to
 * onUwbSample, and notifies the status line.
 *
 * Address convergence: a scope is single-use and each scope has a new local address. Re-advertising a new
 * scope (= new address) on every target change makes the peer poll a stale address, so both sides reconfigure
 * endlessly and ranging drops to zero (address chase). Countermeasures: ① reuse an unconsumed standby scope
 * (no re-advertising — advertised address == session address), ② a live controller applies dynamic multicast
 * addControlee/removeControlee deltas (controller address unchanged — existing controlees unaffected, new or
 * rejoining peers converge in one round), ③ init failure reason snapshot (liveInitError) — shown in the
 * diagnostics panel so a failure doesn't silently settle into BLE fallback, while the caller (BleService)
 * retries with backoff until it succeeds.
 */
class UwbRanger(
    private val context: Context,
    private val scope: CoroutineScope,
    private val myFullId: String,               // my full advertised ID (prefix+id) — tie-break key for same-class election
    private val myIsVehicle: Boolean,           // whether I'm a vehicle (forklift/EPJ, DEVICE mode)
    private val onStatus: ((String) -> Unit)? = null,
    private val onLocalAddressChanged: ((ByteArray) -> Unit)? = null,
    private val rssiOf: ((String) -> Int?)? = null,   // deviceId → recent smoothed RSSI (dBm) — session priority (rankOf) and start gate
    private val forkliftPairOf: ((String) -> Boolean)? = null,   // deviceId → whether the pair includes a forklift — for priority bias
    private val onUwbSample: ((String, Float) -> Unit)? = null   // per-sample callback (deviceId, distance m) — drives UWB-led decisions
) {
    companion object {
        private const val TAG = "UwbRanger"
        private const val SESSION_ID_BASE = 0x00570000        // 'W' (0x57) prefix — app-specific namespace
        private const val RESTART_BACKOFF_MS = 10_000L        // retry wait after a session error or peer release
        private const val REJOIN_DELAY_MS = 250L              // short restart (debounce) wait after peer re-advertise/leave/zombie teardown
        private const val STATUS_THROTTLE_MS = 3_000L         // minimum interval between distance status-line updates
        private const val SWITCH_HYSTERESIS_DB = 6            // hysteresis against controller reselection ping-pong
        private const val FORKLIFT_RANK_BIAS_DB = 12          // forklift-pair priority bias — keeps the 15m warning winning the single session
        private const val MULTICAST_MAX = 6                  // max controlees one controller ranges at once (hardware headroom; tuning point)
        private const val UWB_START_RSSI_GATE_DBM = -80      // UWB only for peers stronger (closer) than this; others stay on RSSI (Case B)

        // Approach-speed kinematics — dt continuity window, smoothing factor, separation deadband
        private const val KIN_DT_MIN_MS = 60L         // denser samples amplify derivative noise — keep the previous reference point
        private const val KIN_DT_MAX_MS = 2000L       // a wider gap breaks continuity — reset kinematics
        private const val KIN_EMA_ALPHA = 0.45f       // EMA factor for smoothed approach speed (assumes ~240ms sample spacing)
        private const val SEP_MIN_MPS = 0.15f         // minimum speed for a separation streak (noise deadband)

        /** Whether the hardware supports UWB (API 31+ & FEATURE_UWB) */
        fun isHardwareSupported(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31) return false
            return context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)
        }

        // UWB live diagnostics snapshot — read directly (no IPC) by the developer settings screen in the same
        //   process (BleService + Activity in one process). Only one UwbRanger is alive at a time (BleService swaps
        //   stop → null → new instance; stop is synchronous) and the old instance's stop() resets these values, so
        //   only the latest instance's state is exposed, race-free. publishDiag() updates them at every state change.
        @Volatile var liveActive: Boolean = false      // session scope open (= UWB init succeeded, not BLE fallback)
            private set
        @Volatile var liveRole: String = "-"           // "대기" / "컨트롤러" / "컨트롤리" / "-"
            private set
        @Volatile var liveSessionCount: Int = 0        // number of peers currently delivering UWB distances
            private set
        // Last init/reconfiguration failure reason — null on success. stop() doesn't clear it (while the retry
        //   loop runs, the diagnostics panel must keep showing why UWB won't open).
        @Volatile var liveInitError: String? = null
            private set

        /** Records the renewAndStart reconfiguration result as the diagnostic reason. stage=null → success (clears it). */
        internal fun noteRebuild(stage: String?, e: Exception? = null) {
            liveInitError = if (stage == null) null else "재구성 실패($stage): ${e?.message}"
        }
    }

    private enum class Role { NONE, CONTROLLER, CONTROLEE }

    /**
     * Reconfiguration target (pure computation) — NONE = standby (advertise as
     * controlee only) / CONTROLLER = multicast ranging / CONTROLEE = join
     */
    private class Desired(
        val role: Role,
        val controllerId: String?,          // CONTROLEE: controller to join
        val controllerPayload: ByteArray?,  // CONTROLEE: that controller's 4-byte OOB
        val controlees: List<String>        // CONTROLLER: controlee deviceId list to range (priority order)
    )

    /**
     * Whether UWB is actually running — true when initSession() succeeds (unsupported, no permission or init failure = false → BLE fallback)
     */
    @Volatile var isSupported: Boolean = false
        private set

    /** deviceId (fullId) → latest UWB-measured distance (m). Devices without a session are absent → BLE fallback */
    val uwbDistances: MutableMap<String, Float> = ConcurrentHashMap()

    // UWB kinematics — approach speed (+ = approaching) differentiated from consecutive distance samples,
    //   and the sustained sample count. Same lifetime as uwbDistances (removed on session end, peer leave or stop),
    //   so an entry can outlive its last sample — consumers check atMs for freshness.
    data class UwbKin(val closingMps: Float, val approachStreak: Int, val separatingStreak: Int, val atMs: Long)

    /** deviceId (fullId) → approach kinematics for BleService speed promotion / separation release (absent = none) */
    val uwbKinematics: MutableMap<String, UwbKin> = ConcurrentHashMap()
    private val lastSampleMap = ConcurrentHashMap<String, Pair<Long, Float>>()   // deviceId → (time, distance) of the previous sample

    /** This device's 2-byte UWB local address (new value on every scope renewal) */
    @Volatile var localAddress: ByteArray? = null
        private set

    private var uwbManager: UwbManager? = null
    private var sessionScope: UwbClientSessionScope? = null   // one scope = one session — consumed by prepareSession
    private var rangingJob: Job? = null
    private var sessionGen = 0                                // session generation token — invalidates stale callbacks

    @Volatile private var role: Role = Role.NONE

    // CONTROLEE state — the controller I joined
    @Volatile private var activeControllerId: String? = null
    @Volatile private var activeControllerPayload: ByteArray? = null
    @Volatile private var activeControllerAddrHex: String? = null   // for attributing results (controller address, 2B)
    private var lastActiveControllerId: String? = null              // basis for controller reselection hysteresis

    // CONTROLLER state — the controlees I am ranging
    private val servedControlees = LinkedHashMap<String, ByteArray>()      // deviceId → controlee address (2B)
    private val servedAddrToId = ConcurrentHashMap<String, String>()       // address hex → deviceId (attributes multicast results)

    private val candidates = LinkedHashMap<String, ByteArray>()   // deviceId → latest OOB payload (2B/4B)
    private var restartScheduled = false
    private var stopped = false
    // scopePrepared: whether the current sessionScope has been (or is about to be) consumed by prepareSession.
    //   Marked at install time — otherwise a stop between install and launch could misjudge a scope about to be
    //   consumed as unconsumed, keep it, and throw on reuse. An unconsumed (false) scope is kept by
    //   stopActiveLocked and reused by the next reconfiguration
    //   (same address → no re-advertising → no address chase).
    private var scopePrepared = false
    // Single-flight guard for dynamic multicast updates; reconcile in the completion callback absorbs later deltas.
    private var dynUpdateRunning = false
    @Volatile private var lastStatusAt = 0L

    /**
     * Initializes the UWB session scope and gets the OOB payload for BLE advertising.
     * The standby role is controlee (2 bytes) so an arriving higher-ranked device can discover and pair with it
     * at once. reconcile then switches the actual role (promote / join) as peers appear. Returns null on failure
     * (unsupported, no permission, UWB OFF, error) → the caller (BleService) keeps advertising without UWB.
     */
    suspend fun initSession(): ByteArray? {
        if (!isHardwareSupported(context)) {
            liveInitError = "하드웨어 미지원"
            Log.d(TAG, "UWB 하드웨어 미지원 — BLE 전용으로 동작")
            return null
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.UWB_RANGING)
            != PackageManager.PERMISSION_GRANTED) {
            liveInitError = "UWB_RANGING 권한 없음"
            Log.i(TAG, "UWB_RANGING 권한 없음 — BLE 전용으로 동작")
            return null
        }
        return try {
            val mgr = UwbManager.createInstance(context)
            if (!mgr.isAvailable()) {
                liveInitError = "시스템 UWB 꺼짐(설정에서 초광대역 확인)"
                Log.i(TAG, "UWB 서비스 비활성(기기 설정 OFF 등) — BLE 전용으로 동작")
                return null
            }
            val s = mgr.controleeSessionScope()          // standby role = controlee (so it's discovered as joinable)
            val payload = buildAdvertisePayload(s)        // 2 bytes
            synchronized(this) {
                if (stopped) return null
                uwbManager = mgr
                sessionScope = s
                scopePrepared = false   // unconsumed standby scope — the first reconfiguration reuses it (same address)
                localAddress = s.localAddress.address.copyOf()
                role = Role.NONE
                isSupported = true
            }
            liveInitError = null
            publishDiag()
            Log.i(TAG, "UWB 초기화 완료(대기=컨트롤리, vehicle=$myIsVehicle) payload=${payload.toHex()}")
            payload
        } catch (e: Exception) {
            liveInitError = "초기화 오류: ${e.message}"
            Log.w(TAG, "UWB 초기화 실패 — BLE 전용으로 동작: ${e.message}")
            null
        }
    }

    /**
     * Receives a peer's UWB OOB payload from the BLE scan response (0x9ABC) — accepts every peer regardless of
     * role or source. Called from the scan callback (binder thread); state mutations are synchronized. Runs
     * reconcile on every receipt to track RSSI rank changes too, but actually reconfigures the session only when
     * the target changes (debounce + guard).
     */
    @Synchronized
    fun onPeerUwbAddressReceived(deviceId: String, peerUwbAddr: ByteArray) {
        if (stopped || !isSupported) return
        if (peerUwbAddr.size < 2) return
        candidates[deviceId] = peerUwbAddr.copyOf(minOf(peerUwbAddr.size, 4))   // controller 4B / controlee 2B
        reconcileLocked()
    }

    /** Called when a peer leaves BLE scan — drops candidate and distance; reconfigures if it was an active peer */
    @Synchronized
    fun onDeviceLost(deviceId: String) {
        candidates.remove(deviceId)
        if (deviceId == activeControllerId) {
            Log.d(TAG, "합류 중이던 컨트롤러 이탈: $deviceId")
            stopActiveLocked()   // also clears the active controller's distance and kinematics
            scheduleRestartLocked(REJOIN_DELAY_MS)
        } else if (role == Role.CONTROLLER && rangingJob != null && servedControlees.containsKey(deviceId)) {
            // Live controller: don't tear the serving map apart here — reconcile's delta path does removeControlee plus
            //   bookkeeping cleanup (controller address unchanged → remaining controlees unaffected).
            reconcileLocked()
        } else {
            dropServedLocked(deviceId)   // also clears distance/kinematics for non-served devices — harmless
            reconcileLocked()   // re-evaluate roles with the remaining peers (guard blocks needless rebuilds)
        }
    }

    /** Tears down all UWB sessions — not reusable after stop (BleService creates a new instance when needed) */
    @Synchronized
    fun stop() {
        stopped = true
        rangingJob?.cancel()
        rangingJob = null
        sessionGen++
        sessionScope = null
        scopePrepared = false
        dynUpdateRunning = false
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

    /**
     * Publishes the diagnostics snapshot via companion @Volatile fields, read without IPC by the developer
     * settings UI in the same process.
     *   role is a session state field and uwbDistances is a ConcurrentHashMap, so it's safe to call
     *   inside or outside the lock.
     */
    private fun publishDiag() {
        liveActive = isSupported
        liveRole = when (role) {
            Role.CONTROLLER -> "컨트롤러"
            Role.CONTROLEE  -> "컨트롤리"
            Role.NONE       -> if (isSupported) "대기" else "-"
        }
        liveSessionCount = uwbDistances.size
    }

    // ── Election and risk (BLE-visible info only) ────────────────────────────────────────

    /** Vehicle status comes from the name prefix — known before any UWB address arrives (no race) */
    private fun peerIsVehicle(id: String): Boolean = id.startsWith(BleConstants.DEVICE_PREFIX)

    /** Whether the peer outranks me on this link (i.e. the peer should be the controller) */
    private fun peerOutranksMe(id: String): Boolean {
        val pv = peerIsVehicle(id)
        if (pv != myIsVehicle) return pv   // vehicle > walker
        return id < myFullId               // same class — smaller fullId is the controller
    }

    /** Link risk: vehicle↔walker=2 (top priority), vehicle↔vehicle=1, walker↔walker=0 */
    private fun pairDanger(peerVeh: Boolean): Int =
        if (peerVeh != myIsVehicle) 2 else if (myIsVehicle) 1 else 0

    private fun isForkliftPair(id: String): Boolean = forkliftPairOf?.invoke(id) == true

    /** Session priority rank — RSSI (stronger = closer) + forklift bias */
    private fun rankOf(id: String): Int =
        (rssiOf?.invoke(id) ?: 0) + if (isForkliftPair(id)) FORKLIFT_RANK_BIAS_DB else 0

    // RSSI start gate (computeDesiredLocked) — only peers stronger (closer) than -80dBm get a UWB pairing.
    //   Far peers stay on RSSI: their distant NLOS samples would pollute the learned Δ. RSSI is therefore both
    //   the session admission gate and the priority (rankOf: slot allocation, controller selection).
    //   DevSettings.uwbForce remains valid as the run gate (forced start ignoring uwbEnabled, in BleService).

    // ── Reconfiguration (reconcile) ──────────────────────────────────────────────────

    /**
     * Computes the target session from current candidates/roles/RSSI (pure). Assuming single-session hardware,
     * the overall role follows risk priority. Only links whose peer advertises a compatible format are ranged
     * (a controller ranges 2B controlees, a controlee joins a 4B controller);
     * mismatched links silently stay on RSSI.
     */
    private fun computeDesiredLocked(): Desired {
        if (candidates.isEmpty()) return Desired(Role.NONE, null, null, emptyList())
        val joinable = ArrayList<String>()      // peer outranks me + advertises controller (4B) → join candidate
        val controllable = ArrayList<String>()  // I outrank + peer advertises controlee (2B) → ranging candidate
        for ((id, p) in candidates) {
            // RSSI start gate — only peers stronger (closer) than -80dBm are UWB session targets.
            //   Weak (distant) peers don't try UWB and stay on RSSI (Case B) decisions. Unknown RSSI (null) can't be
            //   confirmed strong, so it is excluded (included automatically once a later scan fills it in).
            val r = rssiOf?.invoke(id)
            if (r == null || r <= UWB_START_RSSI_GATE_DBM) continue
            val out = peerOutranksMe(id)
            if (out && p.size >= 4) joinable.add(id)
            else if (!out && p.size < 4) controllable.add(id)
        }
        val dangerControl = controllable.maxOfOrNull { pairDanger(peerIsVehicle(it)) } ?: -1
        val dangerJoin = joinable.maxOfOrNull { pairDanger(peerIsVehicle(it)) } ?: -1

        // Spend the single session on the riskier side. On a tie, prefer controller (multicast covers more peers).
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

    /** Picks the controller to join: risk first, then rank (closeness); the previous choice is kept with hysteresis */
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

    /** Whether the running session already matches the target (no reconfiguration needed if so) */
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

    /**
     * Whether reconfiguration is needed — for a standby target only if a session is
     * running (to stop it); otherwise if nothing runs or it doesn't match
     */
    private fun needsRebuildLocked(d: Desired): Boolean {
        return if (d.role == Role.NONE) {
            rangingJob != null || role != Role.NONE
        } else {
            rangingJob == null || !sameAsActiveLocked(d)
        }
    }

    private fun reconcileLocked() {
        if (stopped || uwbManager == null) return
        val d = computeDesiredLocked()
        if (!needsRebuildLocked(d)) return
        // Live controller keeps the controller target and only the controlee set changed — converge with a dynamic
        //   multicast delta instead of a full reconfiguration (new scope = new address = re-advertise = address chase).
        if (role == Role.CONTROLLER && d.role == Role.CONTROLLER && rangingJob != null) {
            applyControleeDeltaLocked(d)
            return
        }
        scheduleRestartLocked(REJOIN_DELAY_MS)
    }

    /** Schedules a reconfiguration (in synchronized blocks only), debounced; renew does the stop when it fires */
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

    /**
     * Clears the active session (inside synchronized blocks only) — bumps the
     * generation to invalidate in-flight callbacks and returns to standby
     */
    private fun stopActiveLocked() {
        rangingJob?.cancel()
        rangingJob = null
        sessionGen++
        // Discard only consumed scopes — an unconsumed standby scope (prepareSession not called) is kept and reused
        //   by the next reconfiguration (same address → no re-advertising → no address chase).
        if (scopePrepared) sessionScope = null
        scopePrepared = false
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

    /** Removes one served controlee (synchronized blocks only) — clears serving map, distance and kinematics together */
    private fun dropServedLocked(id: String) {
        val addr = servedControlees.remove(id)
        if (addr != null) servedAddrToId.remove(addr.toHex())
        uwbDistances.remove(id)
        uwbKinematics.remove(id)
        lastSampleMap.remove(id)
        publishDiag()
    }

    /**
     * Applies a live controller's controlee-set change as a dynamic multicast delta (synchronized blocks only).
     * Only addControlee/removeControlee run while the session stays up, so the controller address is unchanged —
     * existing controlees are unaffected and new or rejoining (new address) peers converge in one round. On failure
     * falls back to stop + scheduled reconfiguration (the full path — when it fires, rangingJob==null, so the delta
     * branch is skipped naturally).
     */
    private fun applyControleeDeltaLocked(d: Desired) {
        val sc = sessionScope as? UwbControllerSessionScope ?: return
        if (dynUpdateRunning) return   // single flight — the completion callback's reconcileLocked() absorbs later deltas
        val want = LinkedHashMap<String, ByteArray>()
        for (id in d.controlees) { val p = candidates[id] ?: continue; want[id] = p.copyOf(2) }
        val toRemove = ArrayList<Pair<String, ByteArray>>()
        for ((id, addr) in servedControlees) {
            val w = want[id]
            if (w == null || !w.contentEquals(addr)) toRemove.add(id to addr)   // left, or address changed (rejoin)
        }
        val toAdd = ArrayList<Pair<String, ByteArray>>()
        for ((id, addr) in want) {
            val cur = servedControlees[id]
            if (cur == null || !cur.contentEquals(addr)) toAdd.add(id to addr)
        }
        if (toRemove.isEmpty() && toAdd.isEmpty()) return
        if (want.isEmpty()) {   // nothing left — an empty multicast is pointless, use the normal stop path
            stopActiveLocked()
            scheduleRestartLocked(REJOIN_DELAY_MS)
            return
        }
        dynUpdateRunning = true
        val gen = sessionGen
        scope.launch {
            var ok = true
            try {
                for ((id, addr) in toRemove) {
                    // A failed individual remove is harmless (address already gone, etc.) — only adds count toward success
                    try { sc.removeControlee(UwbAddress(addr.copyOf(2))) }
                    catch (e: Exception) { Log.d(TAG, "removeControlee(${shortId(id)}) 실패(무해): ${e.message}") }
                }
                for ((_, addr) in toAdd) { sc.addControlee(UwbAddress(addr.copyOf(2))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "동적 멀티캐스트 갱신 실패 — 전체 재구성: ${e.message}")
                ok = false
            }
            synchronized(this@UwbRanger) {
                dynUpdateRunning = false
                if (stopped || gen != sessionGen) return@launch
                if (!ok) { stopActiveLocked(); scheduleRestartLocked(REJOIN_DELAY_MS); return@launch }
                // Bookkeeping only after success and with a matching generation —
                // on failure the sameAsActive mismatch remains, so it retries naturally
                for ((id, addr) in toRemove) {
                    servedAddrToId.remove(addr.toHex())
                    if (!want.containsKey(id)) {
                        servedControlees.remove(id); uwbDistances.remove(id)
                        uwbKinematics.remove(id); lastSampleMap.remove(id)
                    }
                }
                for ((id, addr) in toAdd) { servedControlees[id] = addr; servedAddrToId[addr.toHex()] = id }
                publishDiag()
                Log.d(TAG, "동적 멀티캐스트 갱신: +${toAdd.size} -${toRemove.size} (총 ${servedControlees.size})")
                reconcileLocked()   // apply changes that arrived while in flight right away
            }
        }
    }

    /**
     * Creates a new scope for the target role → re-advertises the new local address over BLE → starts the session.
     * Scopes are single-use, so this runs whenever a session ends or the target changes. RSSI covers the short gap
     * between stop and create. Scope creation/parameter failures fall back silently (the next scan receipt or the
     * backoff retries naturally).
     */
    private suspend fun renewAndStart() {
        val plan = synchronized(this) {
            if (stopped) return
            val mgr = uwbManager ?: return
            val desired = computeDesiredLocked()
            if (rangingJob != null && sameAsActiveLocked(desired)) return   // already running as targeted — keep it
            // If the target narrowed to a controlee-set change before the delayed schedule fired, delegate to the delta
            //   instead of tearing down the live session — a full reconfiguration causes address chase.
            if (role == Role.CONTROLLER && desired.role == Role.CONTROLLER && rangingJob != null) {
                applyControleeDeltaLocked(desired)
                return
            }
            stopActiveLocked()                                             // clear the current session (bumps generation)
            Triple(mgr, desired, sessionGen)
        }
        val (mgr, desired, gen) = plan

        when (desired.role) {
            Role.NONE -> {
                // Standby: advertise only, as a discoverable controlee (no session).
                // If an unconsumed controlee scope remains, switch to standby with it as-is — same address, so no
                //   re-advertising (the address peers know stays valid → no address chase).
                val standby = synchronized(this) {
                    if (stopped || gen != sessionGen) return
                    val s = sessionScope
                    if (s != null && s !is UwbControllerSessionScope) {
                        role = Role.NONE
                        noteRebuild(null)
                        publishDiag()
                        true
                    } else false
                }
                if (standby) return
                val sc = try {
                    mgr.controleeSessionScope()
                } catch (e: Exception) {
                    Log.w(TAG, "UWB 대기 스코프 생성 실패(백오프 재시도): ${e.message}")
                    noteRebuild("대기 스코프", e)
                    synchronized(this) { scheduleRestartLocked(RESTART_BACKOFF_MS) }
                    return
                }
                val payload = try { buildAdvertisePayload(sc) } catch (e: Exception) {
                    Log.w(TAG, "광고 페이로드 생성 실패 → RSSI 폴백: ${e.message}"); return
                }
                synchronized(this) {
                    if (stopped || gen != sessionGen) return
                    sessionScope = sc
                    scopePrepared = false   // session not started — the next reconfiguration can reuse it
                    localAddress = sc.localAddress.address.copyOf()
                    role = Role.NONE
                    noteRebuild(null)
                    publishDiag()
                }
                onLocalAddressChanged?.invoke(payload)
            }

            Role.CONTROLLER -> {
                val sc: UwbControllerSessionScope = try {
                    mgr.controllerSessionScope()
                } catch (e: Exception) {
                    Log.w(TAG, "UWB 컨트롤러 스코프 생성 실패(백오프 재시도): ${e.message}")
                    noteRebuild("컨트롤러 스코프", e)
                    synchronized(this) { scheduleRestartLocked(RESTART_BACKOFF_MS) }
                    return
                }
                val payload = try { buildAdvertisePayload(sc) } catch (e: Exception) {
                    Log.w(TAG, "광고 페이로드 생성 실패 → RSSI 폴백: ${e.message}"); return
                }
                val served = LinkedHashMap<String, ByteArray>()
                synchronized(this) {
                    if (stopped || gen != sessionGen) return
                    sessionScope = sc   // Drop any leftover unconsumed controlee scope; a controller needs a fresh one
                    scopePrepared = true   // Mark scope consumed at install so a racing stop will not keep it for reuse
                    localAddress = sc.localAddress.address.copyOf()
                    role = Role.CONTROLLER
                    servedControlees.clear(); servedAddrToId.clear()
                    for (id in desired.controlees) {
                        val p = candidates[id] ?: continue      // Skip candidates that left between computation and configuration
                        val addr = p.copyOf(2)
                        served[id] = addr
                        servedControlees[id] = addr
                        servedAddrToId[addr.toHex()] = id
                    }
                    noteRebuild(null)
                    publishDiag()
                }
                onLocalAddressChanged?.invoke(payload)
                if (served.isEmpty()) {
                    // All targets left before configuration; the next receive/rebuild corrects it
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
                // Reuse an unconsumed controlee scope: the address is unchanged, so skip re-advertising. We join
                //   with the address the controller already knows from our advertisement, so ranging succeeds on the
                //   first try (otherwise: new address re-advertised → controller polls the old one → no ranging →
                //   endless mutual rebuilds).
                val reused: UwbClientSessionScope? = synchronized(this) {
                    if (stopped || gen != sessionGen) return
                    sessionScope?.takeIf { it !is UwbControllerSessionScope }
                }
                val sc = reused ?: try {
                    mgr.controleeSessionScope()
                } catch (e: Exception) {
                    Log.w(TAG, "UWB 컨트롤리 스코프 생성 실패(백오프 재시도): ${e.message}")
                    noteRebuild("컨트롤리 스코프", e)
                    synchronized(this) { scheduleRestartLocked(RESTART_BACKOFF_MS) }
                    return
                }
                val payload = try { buildAdvertisePayload(sc) } catch (e: Exception) {
                    Log.w(TAG, "광고 페이로드 생성 실패 → RSSI 폴백: ${e.message}"); return
                }
                synchronized(this) {
                    if (stopped || gen != sessionGen) return
                    sessionScope = sc
                    scopePrepared = true   // Mark scope consumed at install so a racing stop will not keep it for reuse
                    localAddress = sc.localAddress.address.copyOf()
                    role = Role.CONTROLEE
                    activeControllerId = cid
                    activeControllerPayload = cpayload
                    activeControllerAddrHex = cpayload.copyOf(2).toHex()
                    lastActiveControllerId = cid
                    noteRebuild(null)
                    publishDiag()
                }
                if (reused == null) onLocalAddressChanged?.invoke(payload)   // Re-advertise only for a new address
                val job = scope.launch { runControleeSession(sc, cpayload, gen) }
                synchronized(this) { if (gen == sessionGen && !stopped) rangingJob = job else job.cancel() }
                onStatus?.invoke("UWB 합류: ${shortId(cid)}")
                Log.d(TAG, "UWB 컨트롤리 시작 — 컨트롤러 ${shortId(cid)}(내 주소 ${if (reused != null) "유지" else "신규"})")
            }
        }
    }

    /**
     * Controller multicast session: ranges several controlees at once with CONFIG_MULTICAST_DS_TWR.
     * sessionId/key derive from my 2-byte controller address; controlees derive the same from the advertisement.
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

    /** Controlee session: joins one given controller. Results map via activeControllerAddrHex→activeControllerId */
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

    /** OOB payload in the BLE scan response: controller 4 bytes (address + assigned channel), controlee 2 bytes */
    private fun buildAdvertisePayload(s: UwbClientSessionScope): ByteArray {
        val addr = s.localAddress.address
        return if (s is UwbControllerSessionScope) {
            val ch = s.uwbComplexChannel
            byteArrayOf(addr[0], addr[1], (ch.channel and 0xFF).toByte(), (ch.preambleIndex and 0xFF).toByte())
        } else {
            byteArrayOf(addr[0], addr[1])
        }
    }

    /** Controller (multicast) parameters: sessionId/key derived from my address, all controlees as peerDevices */
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
            complexChannel = sc.uwbComplexChannel,   // System-assigned channel (already shared in the advertisement)
            peerDevices = peers,
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT   // ~120ms report interval
        )
    }

    /** Controlee parameters: same session derived from the controller's 4-byte OOB (2B address + channel + preamble) */
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
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT   // ~120ms report interval
        )
    }

    // Session parameters derive only from the 2-byte controller address, so both sides get identical values
    private fun deriveSessionId(a0: Byte, a1: Byte): Int =
        SESSION_ID_BASE or ((a0.toInt() and 0xFF) shl 8) or (a1.toInt() and 0xFF)

    // STATIC STS needs a key of exactly 8 bytes: "WF" + 2B controller address + "SAFE"
    private fun deriveSessionKey(a0: Byte, a1: Byte): ByteArray =
        byteArrayOf(0x57, 0x46, a0, a1, 0x53, 0x41, 0x46, 0x45)

    /** Handle ranging results; multicast results are mapped back to their peer via result.device.address */
    private fun handleResult(result: RangingResult) {
        when (result) {
            is RangingResult.RangingResultPosition -> {
                val d = result.position.distance?.value ?: return
                if (!d.isFinite()) return   // Drop NaN/±Inf: would corrupt kinematics/calibration and make roundToInt throw
                val id = peerIdForResult(result.device) ?: return
                uwbDistances[id] = d
                publishDiag()
                val now = System.currentTimeMillis()
                updateKinematics(id, d, now)
                onUwbSample?.invoke(id, d)   // Push at once; decisions don't wait for the BLE scan cycle (lower latency)
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
            else -> { /* Unknown result type: ignore */ }
        }
    }

    /** Map a result address back to a deviceId: controller uses the served map, controlee the active controller */
    private fun peerIdForResult(device: UwbDevice): String? {
        val hex = device.address.address.toHex()
        return when (role) {
            Role.CONTROLLER -> servedAddrToId[hex]
            Role.CONTROLEE -> if (hex == activeControllerAddrHex) activeControllerId else null
            else -> null
        }
    }

    /** UWB peer disconnect: controller drops only that controlee (session kept if others remain); controlee rejoins */
    @Synchronized
    private fun onPeerDisconnected(id: String?) {
        if (stopped) return
        when (role) {
            Role.CONTROLLER -> {
                if (id != null) {
                    // Peer left while the session continues: also remove it from the multicast list (best-effort),
                    //   so re-adding the same address on rejoin cannot throw a duplicate-entry exception.
                    val sc = sessionScope as? UwbControllerSessionScope
                    val addr = servedControlees[id]?.copyOf(2)
                    if (sc != null && addr != null && rangingJob != null) {
                        scope.launch { try { sc.removeControlee(UwbAddress(addr)) } catch (_: Exception) {} }
                    }
                    dropServedLocked(id)
                }
                if (servedControlees.isEmpty()) {
                    stopActiveLocked()
                    scheduleRestartLocked(REJOIN_DELAY_MS)
                } else {
                    reconcileLocked()   // See if a replacement controlee can be promoted; guards block needless rebuilds
                }
            }
            Role.CONTROLEE -> {
                stopActiveLocked()
                scheduleRestartLocked(REJOIN_DELAY_MS)
            }
            else -> { /* Idle state: ignore */ }
        }
    }

    // Update approach-speed kinematics by differentiating consecutive samples (+ = approaching).
    //   Assumes a single caller on the session thread. Outside the dt window (too dense / gap) the
    //   reference point is kept / reset respectively, blocking derivative noise and phantom speeds.
    //   approach/separating streaks are mutually exclusive (each resets the other); one sample cannot
    //   count toward both.
    private fun updateKinematics(deviceId: String, distM: Float, now: Long) {
        val prev = lastSampleMap.put(deviceId, now to distM) ?: return
        val dtMs = now - prev.first
        if (dtMs < KIN_DT_MIN_MS) { lastSampleMap[deviceId] = prev; return }   // Too-dense sample: keep the previous reference point
        if (dtMs > KIN_DT_MAX_MS) { uwbKinematics.remove(deviceId); return }   // Continuity gap: reset and re-accumulate
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

    /** Shared session-end handling: runs once, for the current generation only (stale-generation callbacks ignored) */
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
