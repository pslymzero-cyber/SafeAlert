package com.wf11.safealert.ble

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.wf11.safealert.service.BleService
import com.wf11.safealert.utils.BeaconRegistry
import java.util.UUID

class BleScanner(private val scanner: BluetoothLeScanner) {

    companion object {
        private const val TAG = "BleScanner"
        // Avoid the 30-minute throttle: turn the scan off and on every 45s (works around the OS policy that blocks
        //   30 minutes of continuous scanning)
        private const val SCAN_RESTART_MS   = 45_000L
        // Android lets an app start START_LIMIT scans per START_WINDOW_MS and refuses the next one (silently up to
        //   Android 12), leaving reception dead until a later start. It counts per app, so the start log is shared by
        //   every BleScanner and by BeaconManagerActivity's discovery scan (noteScanStart). Restarts keep one start of
        //   headroom (START_BUDGET); one that would exceed it keeps the running scan and is postponed until a start frees
        //   up. Urgent restarts (hazard-near promotion, discovery filter switches, retry after a failed start) may use
        //   the headroom.
        private const val START_LIMIT     = 5
        private const val START_BUDGET    = 4
        private const val START_WINDOW_MS = 30_000L
        private val recentStarts = ArrayDeque<Long>()   // elapsedRealtime of the app's scan starts inside START_WINDOW_MS

        /** Records a scan start the app made (BleScanner's own, or BeaconManagerActivity's discovery scan). */
        fun noteScanStart() = synchronized(recentStarts) { recentStarts.addLast(SystemClock.elapsedRealtime()) }

        /** The newest start failed (onScanFailed): Android did not count it, so the log does not either. */
        private fun dropLastStart() = synchronized(recentStarts) { recentStarts.removeLastOrNull() }

        /** 0 when one more start fits [budget], otherwise how long until the oldest start leaves the window. */
        private fun msUntilStartAllowed(budget: Int): Long = synchronized(recentStarts) {
            val now = SystemClock.elapsedRealtime()
            while (recentStarts.isNotEmpty() && now - recentStarts.first() >= START_WINDOW_MS) recentStarts.removeFirst()
            if (recentStarts.size < budget) 0L else START_WINDOW_MS - (now - recentStarts.first())
        }

        /** Tests only: the log is process-wide, and Robolectric keeps it from one test to the next. */
        internal fun resetStartLog() = synchronized(recentStarts) { recentStarts.clear() }
        // Device-lost timeout, tied to the current radio duty (dynamic).
        //  Continuous (LOW_LATENCY): dense scanning → lost after 2s without reception (fast reaction).
        //  Duty-cycled (BALANCED/LOW_POWER): the scan-OFF window is long, so gaps over 2s are normal; extended
        //    to 6s so a device sitting right next to us is not falsely lost or flickering as 'not detected'.
        private const val DEVICE_TIMEOUT_ACTIVE_MS = 2000L
        private const val DEVICE_TIMEOUT_REST_MS   = 6000L
        // Minimum timeout for beacons (BEA_) only: keeps beacons advertising every 300ms~1s from flapping between lost
        //   and rediscovered under the ACTIVE 2s timeout (applied with maxOf, so the REST 6s is never shortened).
        private const val DEVICE_TIMEOUT_BEACON_MS = 3500L

        // ── Dynamic scan mode policy ────────────────────
        // When the IMU confirms '5s stationary', BleService calls setEcoMode(true) to switch to rest mode, and as soon
        // as movement is detected, setEcoMode(false) → back to active (combat) mode. Rest mode currently equals
        // active mode for scanning (see restScanMode), so this only sets the eco flag.
        //
        // The batching delay is orthogonal to this: it only suppresses CPU wake-ups while the screen is off.
        //   Screen on/active: 0ms   - delivered immediately, zero alert delay
        //   Screen off:       500ms - the BLE chip scans on its own without waking the CPU;
        //                             the CPU wakes once per 0.5s → at most 0.5s delay guaranteed
        private const val BATCH_DELAY_ACTIVE_MS     = 0L
        private const val BATCH_DELAY_SCREEN_OFF_MS = 500L

        // Maps the 'scan period' setting (scanPeriodMs) to the actual scan duty.
        //   Android's public scan API accepts no arbitrary ms period, only 3 presets, so the setting is quantized to the
        //   nearest preset (spinner: 1000/2000/3000/5000ms).
        //     ≤1000ms → LOW_LATENCY (near-continuous scanning: high sensitivity, high drain)
        //     ≤3000ms → BALANCED    (about 1.0s scan / 4.1s period)
        //     above   → LOW_POWER   (about 0.5s scan / 5.1s period: power saving)
        private fun mapScanMode(periodMs: Long): Int = when {
            periodMs <= 1000L -> ScanSettings.SCAN_MODE_LOW_LATENCY
            periodMs <= 3000L -> ScanSettings.SCAN_MODE_BALANCED
            else              -> ScanSettings.SCAN_MODE_LOW_POWER
        }

        // For decoding the peer's motion-state ServiceData (same UUID as the sender's addServiceData)
        private val SERVICE_DATA_UUID = ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID))

        // During a discovery scan (beacon management, 15s), lift the HW filter so unregistered UUIDs are caught too.
        //   Filters of scan clients sharing one BluetoothLeScanner are merged at the stack/controller level, so while
        //   the registered-UUID filter is active, unregistered advertisements never reach even an unfiltered discovery
        //   scan (a circular trap: a beacon not in the registry could never be discovered).
        @Volatile private var discoveryMode = false
        @Volatile private var liveRestart: (() -> Unit)? = null

        /** During a discovery scan (beacon management, 15s), lift the HW filter so unregistered UUIDs are caught too. */
        fun setDiscoveryMode(on: Boolean) {
            if (discoveryMode == on) return
            discoveryMode = on
            liveRestart?.invoke()
        }
    }

    private var scanCallback: BleScanCallback? = null
    private var isScanning = false
    private var radioOff = false   // Bluetooth is off: no scan can start until BleService replaces this scanner
    private val handler = Handler(Looper.getMainLooper())
    // A restart postponed by the start budget runs once, as immediate/urgent as the most demanding request it absorbed
    private var postponedImmediate = false
    private var postponedUrgent = false
    private val postponedRestart = Runnable {
        val immediate = postponedImmediate; val urgent = postponedUrgent
        postponedImmediate = false; postponedUrgent = false
        if (isScanning) restartScanInternal(immediate, urgent)
    }
    private val delayedStart = Runnable { if (isScanning) startScanInternal() }
    // After a failed start nothing scans, so the retry is urgent: there is no running scan to keep while it waits
    private val failRetry = Runnable { if (isScanning) restartScanInternal(urgent = true) }
    // Settings of the scan actually running, set at each start: while a restart waits for the budget, the loss sweep and
    //   the batching switches go by what the radio is doing, not by what was asked for.
    @Volatile private var runningScanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
    private var runningBatchDelay = BATCH_DELAY_ACTIVE_MS
    private val detectedDevices = mutableMapOf<String, Long>()
    var onStatusUpdate: ((String) -> Unit)? = null

    // Predicate for whether UWB ranging is still live (wired by BleService). At the BLE signal timeout (active 2s /
    //   rest 6s), if it is true (a fresh UWB measurement exists), onDeviceLost is deferred. This keeps a momentary
    //   BLE advertising loss from tearing down a UWB session that is still ranging (BleService→UwbRanger).
    //   Re-evaluated every sweep (1s), so once UWB ranging also stops, the device is lost normally.
    var uwbMeasuringCheck: ((String) -> Boolean)? = null

    // 'My hash' for matching the mutual RSSI echo; BleService wires in the shortHash of its own fullId.
    //   The entry matching this hash in a peer's scan-response echo table (0xE0C0) = 'the RSSI at which the peer
    //   heard me'. If null (not wired), echo parsing is skipped (same fallback as an old-version peer or bootstrap).
    var myEchoHash: Int? = null

    private var totalBleCount = 0

    // Screen state: when false, 500ms hardware batching (orthogonal to the scan mode; only minimizes CPU wake-ups)
    @Volatile var isScreenOn: Boolean = true

    // Danger proximity/alert while the screen is off → promote batching to 0ms immediate delivery (safety first).
    //   BleService sets it on any reception at rssi>=WAKE (the detection wakelock pre-acquires 10dB earlier).
    //   When nothing is near and no alert remains (evaluation cycle), it returns to power-saving batching (500ms)
    //   automatically.
    @Volatile private var hazardNear: Boolean = false

    // Active mode = preset mapped from the scanPeriodMs setting (live: read from settings every time).
    private val activeScanMode: Int get() = mapScanMode(BleConstants.scanPeriodMs)
    // Rest mode = same as active mode: receive scanning is never downgraded for eco.
    //   Constant-speed driving has linear acceleration ≈ 0, so ImuFusion.isStationary can misjudge it as
    //   'stationary' and enter rest (eco) mid-work. If scanning then dropped to BALANCED (4.1s period, 1s duty),
    //   first discovery of a new device, median filling and 2-frame confirmation would be trapped in the 4s duty,
    //   and head-on at 6+6km/h (closing 3.33m/s) the first warning would come only at 0.3m (right in front)
    //   (simulation sa_scan_eco_sim.py: L0 0.30m→L2 10.04m, TTC 0.09s→3.01s).
    //   Receiving (RX), 'hearing others approach', is the core of safety → always stay in active mode regardless of
    //   eco. Power saving happens only in advertising (TX, evaluateAdvertiserPower) and batching (screen off),
    //   orthogonal to this. If the user explicitly raises scanPeriodMs to 2000/3000/5000, that power-saving period
    //   is respected.
    private val restScanMode: Int get() = activeScanMode

    // Track the eco (rest) state as a separate boolean: comparing mode values cannot recover it, because the modes
    //   follow the settings and ACTIVE == REST would always resolve to the same side.
    @Volatile private var ecoMode = false

    // Current scan mode: active by default (mapped from settings). Rest (eco) only once the IMU confirms 5s
    //   stationary.
    @Volatile private var currentScanMode: Int = mapScanMode(BleConstants.scanPeriodMs)

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // The scan callback thread is not guaranteed to be the main looper (depending on stack/OEM, binder threads
            //   deliver). detectedDevices and BleService's per-device state maps are all owned by the main thread, so when
            //   not on main, post to the main looper immediately and return (guards against
            //   ConcurrentModificationException). On stock stacks (delivered on main) the condition is false: a zero-delay
            //   no-op. The batch path (onBatchScanResults) also delegates here, so this one guard puts every receive path on
            //   the main thread.
            if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { onScanResult(callbackType, result) }; return }
            val record = result.scanRecord ?: return

            // SafeAlert device detection
            val deviceData = record.getManufacturerSpecificData(BleConstants.COMPANY_ID_DEVICE)
            val walkerData = record.getManufacturerSpecificData(BleConstants.COMPANY_ID_WALKER)

            if (deviceData != null || walkerData != null) {
                totalBleCount++
                BleService.bleScanCount = totalBleCount
                // No periodic scan status ('RX scanning · N SafeAlert devices') here: it would keep occupying the bottom
                // tv_ble_status (detected-device list area).

                val (idBytes, prefix) = when {
                    deviceData != null -> deviceData to BleConstants.DEVICE_PREFIX
                    walkerData != null -> walkerData to BleConstants.WALKER_PREFIX
                    else               -> return
                }

                val deviceId   = String(idBytes, Charsets.UTF_8)
                val fullId     = prefix + deviceId
                val rssi       = result.rssi

                // 1-byte payload: ServiceData byte 0 (Category/State/Turn/Risk) is passed on as remoteState; Turn (bits 3:2,
                //   the peer's turn direction) is decoded here.
                //   Unsupported (beacon/old version): byte absent → 0x00 (stopped) and straight (TURN_STRAIGHT).
                val svcData       = record.getServiceData(SERVICE_DATA_UUID)
                val payloadByte   = svcData?.getOrNull(0)?.toInt()?.and(0xFF)
                val payloadPresent = payloadByte != null   // Whether a real 1-byte self-report was received (beacon/old version = false)
                val remoteState   = payloadByte ?: BleConstants.MOTION_STATE_STATIONARY
                val remoteTurn    = if (payloadByte != null) BleConstants.decodeTurn(payloadByte) else BleConstants.TURN_STRAIGHT
                // ServiceData 2nd (extension) byte: bit0=IN_ZONE (zone beacon contact declaration).
                //   Old-version senders and beacons lack the byte → 0 → false (backward compatible).
                val extByte    = svcData?.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
                val peerInZone = (extByte and BleConstants.EXT_FLAG_IN_ZONE) != 0

                // Mutual RSSI echo parsing: the entry with 'my hash' in the peer's 0xE0C0 scan-response table is 'the RSSI at
                //   which the peer heard me' (rssi_me→peer). Both sides judge symmetrically with sym.
                //   My hash not wired, no echo, or no matching entry → NO_ECHO_RSSI (fallback = behavior without echo).
                val echoData     = record.getManufacturerSpecificData(BleConstants.COMPANY_ID_RSSI_ECHO)
                val myHash       = myEchoHash
                val peerEchoRssi = if (myHash != null && echoData != null)
                    (BleConstants.findEchoRssi(echoData, myHash) ?: BleConstants.NO_ECHO_RSSI)
                    else BleConstants.NO_ECHO_RSSI

                BleService.safeAlertFound++
                detectedDevices[fullId] = System.currentTimeMillis()
                scanCallback?.onDeviceDetected(fullId, rssi, remoteState, remoteTurn, payloadPresent, peerEchoRssi, peerInZone)
                scanCallback?.onPeerSos(fullId, BleConstants.decodeSos(extByte), SosAdvert.decodeEpisode(svcData), SosAdvert.decodeHint(svcData))   // Extension byte bit1

                // Parse the UWB address from the scan response (supported devices only).
                // DEVICE (controller) = 4 bytes (address + channel + preamble), WALKER (controlee) = 2 bytes; pass on
                //   whatever is present
                val uwbData = record.getManufacturerSpecificData(BleConstants.COMPANY_ID_UWB_EXT)
                if (uwbData != null && uwbData.size >= 2) {
                    scanCallback?.onUwbAddressReceived(fullId, uwbData.copyOf(minOf(uwbData.size, 4)))
                }
                return
            }

            // Detect registered iBeacon UUIDs
            val iBeaconData = record.getManufacturerSpecificData(0x004C)
            if (iBeaconData != null) {
                val uuid = BeaconRegistry.parseIBeaconUuid(iBeaconData)
                if (uuid != null && BeaconRegistry.containsUuid(uuid)) {
                    // A zone beacon (zoneMute) is a safe-zone marker, not an alert target: it is not added to the device list or
                    //   judgment and only goes down the zone signal path (raw RSSI, no gain applied).
                    val zp = BeaconRegistry.findZoneProfileByUuid(uuid)
                    if (zp != null) {
                        scanCallback?.onZoneBeaconSignal("ZONE_${uuid.take(8)}", result.rssi, zp.zoneEnterRssi)
                        return
                    }
                    // Keep the status line (tv_ble_status) clean: do not send beacon info as status.
                    val fullId = BleConstants.WALKER_PREFIX + "BEA_${uuid.replace("-", "")}"   // Full key (32 hex); only display and logs truncate it to 8 chars
                    val rssi   = result.rssi
                    detectedDevices[fullId] = System.currentTimeMillis()
                    // External beacons have no motion ServiceData → passed as 0x00 (stopped)
                    scanCallback?.onDeviceDetected(fullId, rssi, BleConstants.MOTION_STATE_STATIONARY)
                    return
                }
            }

            // Service UUID beacon detection.
            // Check both serviceUuids (AD 0x02/0x03/0x06/0x07) and serviceData (AD 0x16): they are independent fields in
            //   the advertising packet. Beacons with 16-bit SIG UUIDs (0000FDA5-… etc.) often advertise only service data
            //   and leave serviceUuids empty; checking serviceUuids alone would never reach this branch, so a beacon
            //   registered as a zone beacon would never trigger onZoneBeaconSignal.
            ((record.serviceUuids ?: emptyList()) + (record.serviceData?.keys ?: emptySet())).forEach { parcelUuid ->
                val uuidStr = parcelUuid.uuid.toString().uppercase()
                if (BeaconRegistry.containsUuid(uuidStr)) {
                    // Zone beacon branch, same as the iBeacon path
                    val zp = BeaconRegistry.findZoneProfileByUuid(uuidStr)
                    if (zp != null) {
                        scanCallback?.onZoneBeaconSignal("ZONE_${uuidStr.take(8)}", result.rssi, zp.zoneEnterRssi)
                        return
                    }
                    val fullId = BleConstants.WALKER_PREFIX + "BEA_${uuidStr.replace("-", "")}"   // Full key (32 hex); only display and logs truncate it to 8 chars
                    detectedDevices[fullId] = System.currentTimeMillis()
                    scanCallback?.onDeviceDetected(fullId, result.rssi, BleConstants.MOTION_STATE_STATIONARY)
                    return
                }
            }

            // MAC-based beacons
            val mac = result.device.address ?: return
            if (BeaconRegistry.containsMac(mac)) {
                // Zone beacon branch, same as the iBeacon path
                val zp = BeaconRegistry.findZoneProfileByMac(mac)
                if (zp != null) {
                    scanCallback?.onZoneBeaconSignal("ZONE_${mac.replace(":", "")}", result.rssi, zp.zoneEnterRssi)
                    return
                }
                val fullId = BleConstants.WALKER_PREFIX + "BEA_${mac.replace(":", "")}"
                detectedDevices[fullId] = System.currentTimeMillis()
                scanCallback?.onDeviceDetected(fullId, result.rssi, BleConstants.MOTION_STATE_STATIONARY)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED             -> "이미 스캔 중"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "앱 등록 실패"
                SCAN_FAILED_FEATURE_UNSUPPORTED         -> "BLE 미지원"
                SCAN_FAILED_INTERNAL_ERROR              -> "내부 오류"
                6                                       -> "쓰로틀링"
                else                                    -> "오류($errorCode)"
            }
            Log.e(TAG, "스캔 실패: $reason")
            onStatusUpdate?.invoke("스캔 오류: $reason")
            scanCallback?.onScanError(errorCode)
            dropLastStart()   // nothing started, so the retry is not held back by a start that never counted
            val delayMs = if (errorCode == 6) 31_000L else 2_000L
            handler.removeCallbacks(failRetry)
            handler.postDelayed(failRetry, delayMs)
        }
    }

    private val timeoutChecker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            // Duty-cycled scans (BALANCED/LOW_POWER) have long scan-OFF windows, so the timeout must be longer to avoid
            // falsely losing healthy devices ("감지 없음" flicker).
            // The timeout follows the radio duty of the running scan, not the eco state or a mode still waiting for its
            //   restart. Only continuous scanning (LOW_LATENCY) uses 2 s — 2 s on a duty scan would drop healthy devices
            //   during the scan-OFF window (up to ~4.6 s). The default scanPeriodMs (1000ms → LOW_LATENCY) uses 2 s.
            val timeoutMs = if (runningScanMode == ScanSettings.SCAN_MODE_LOW_LATENCY)
                                DEVICE_TIMEOUT_ACTIVE_MS else DEVICE_TIMEOUT_REST_MS
            detectedDevices.entries
                .filter {
                    // Beacons advertise slowly (300ms~1s) and briefly flap at the ACTIVE 2 s timeout — guarantee at least 3.5 s
                    val effTimeoutMs = if (it.key.contains("BEA_")) maxOf(timeoutMs, DEVICE_TIMEOUT_BEACON_MS) else timeoutMs
                    now - it.value > effTimeoutMs
                }
                .map { it.key }
                .forEach { id ->
                    // Even after a BLE timeout, defer loss for a device whose UWB ranging is still flowing —
                    //   a brief advertising gap alone must not fire onDeviceLost (which tears down the UWB session).
                    //   The timestamp is not refreshed, so the device stays BLE-stale; each sweep (1 s) re-evaluates it, and once
                    //   UWB ranging also stops it is lost normally.
                    if (uwbMeasuringCheck?.invoke(id) == true) {
                        Log.d(TAG, "신호 소실 유예(UWB 실측 지속): $id")
                        return@forEach
                    }
                    detectedDevices.remove(id)
                    scanCallback?.onDeviceLost(id)
                    Log.d(TAG, "신호 소실: $id")
                }
            if (isScanning) handler.postDelayed(this, 1000)
        }
    }

    /**
     * Immediately marks every detected device as lost — on entering the safe zone (full suppression), when beacon
     * registrations change and before BleService drops the scanner on a Bluetooth restart. Runs the normal
     * BleService.onDeviceLost path, so per-device state (DeviceStateRegistry) is cleaned up at once and UWB candidates
     * are dropped (filters follow onDeviceLost's warm-preserve rule). A live UWB controller session is reconfigured
     * only a moment later, so a caller that must not see late samples stops UWB first. Also clears detectedDevices,
     * so each device then resumes from its first advertisement, like a new device.
     */
    /** Whether the device is still detected; BleService judges UWB samples only for these. */
    fun isTracked(id: String) = id in detectedDevices

    fun forceLoseAll() {
        val ids = detectedDevices.keys.toList()
        detectedDevices.clear()
        ids.forEach { scanCallback?.onDeviceLost(it) }
        if (ids.isNotEmpty()) Log.i(TAG, "강제 소실 처리: ${ids.size}대")
    }

    // Hardware scan filters are mandatory — never emptyList().
    // The SERVICE_UUID (our beacon spec) filter is offloaded to the Bluetooth chipset, so unrelated BLE noise is
    // dropped in the chipset without waking the main CPU (key battery saving with screen off / power saving).
    // ※ BleAdvertiser advertises the same SERVICE_UUID, so our devices pass this filter.
    private fun buildFilters(): List<ScanFilter> {
        // Limited exception to the 'never emptyList()' rule above — only during a user-initiated, foreground,
        // 15-second discovery scan. Scanning keeps running, so the alert pipeline stays alive; when the discovery
        // scan ends, setDiscoveryMode(false) → restartScan restores the filters immediately.
        if (discoveryMode) return emptyList()
        val filters = mutableListOf<ScanFilter>()
        filters.add(ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID)))
            .build())
        runCatching {
            BeaconRegistry.getAll().filter { it.type == "SERVICE_UUID" }.forEach { profile ->
                runCatching {
                    val pu = ParcelUuid(java.util.UUID.fromString(profile.uuid))
                    filters.add(ScanFilter.Builder().setServiceUuid(pu).build())
                    // Supports beacons that advertise only service data (AD 0x16).
                    //   The setServiceUuid filter above matches only AD 0x02/0x03/0x06/0x07 (Service UUID List). Beacons with
                    //   16-bit SIG UUIDs such as 0000FDA5-… often advertise only service data, so without this filter they are
                    //   dropped in the chipset and no callback ever arrives (even a registered zone beacon never reaches
                    //   onZoneBeaconSignal).
                    //   The empty array is required — AOSP matchesPartialData throws an NPE when data == null.
                    filters.add(ScanFilter.Builder().setServiceData(pu, byteArrayOf()).build())
                }.onFailure { Log.w(TAG, "스캔 필터 생성 실패(SERVICE_UUID) ${profile.uuid}: ${it.message} — 이 기기는 칩셋 단에서 폐기되어 미감지") }
            }
            BeaconRegistry.getAll().filter { it.type == "MAC" }.forEach { profile ->
                runCatching { filters.add(ScanFilter.Builder().setDeviceAddress(profile.uuid).build()) }
                    .onFailure { Log.w(TAG, "스캔 필터 생성 실패(MAC) ${profile.uuid}: ${it.message} — 이 기기는 칩셋 단에서 폐기되어 미감지") }
            }
            // Registered iBeacons — manufacturer data (0x004C) pattern filter.
            //   iBeacons advertise neither SERVICE_UUID nor MAC, so the two filters above would drop them in the chipset
            //   (the signal would never be caught — especially critical with the screen off / Doze).
            //   The [0x02,0x15, 16-byte UUID] pattern with a full mask passes only iBeacons with a registered UUID
            //   (other vendors' 0x004C ads nearby are dropped by the chipset → zero noise).
            BeaconRegistry.getAll().filter { it.type == "IBEACON" }.forEach { profile ->
                runCatching {
                    val u  = java.util.UUID.fromString(profile.uuid)
                    val bb = java.nio.ByteBuffer.allocate(16)
                    bb.putLong(u.mostSignificantBits); bb.putLong(u.leastSignificantBits)
                    val pattern = byteArrayOf(0x02, 0x15) + bb.array()      // iBeacon prefix + UUID
                    val mask    = ByteArray(pattern.size) { 0xFF.toByte() } // Exact match on every byte
                    filters.add(ScanFilter.Builder()
                        .setManufacturerData(0x004C, pattern, mask)
                        .build())
                }.onFailure { Log.w(TAG, "스캔 필터 생성 실패(IBEACON) ${profile.uuid}: ${it.message} — 이 기기는 칩셋 단에서 폐기되어 미감지") }
            }
        }.onFailure { Log.w(TAG, "등록 비콘 스캔 필터 일괄 생성 실패: ${it.message} — 기본 SERVICE_UUID 필터만 적용됨") }
        return filters
    }

    // ── Restart to avoid the 30-minute throttle (scan off/on every 45 s) ─────────────────
    // Android force-blocks an app that scans continuously for 30+ minutes (error code 6).
    // A short restart every 45 s sidesteps this policy. ★ Must be kept as is.
    private val antiThrottleRunnable = object : Runnable {
        override fun run() {
            if (!isScanning) return
            restartScanInternal()
            handler.postDelayed(this, SCAN_RESTART_MS)
        }
    }

    fun startScanning(callback: BleScanCallback) {
        scanCallback   = callback
        isScanning     = true
        totalBleCount  = 0
        // A scanner replaced moments ago may have used the app's starts: wait for a free one rather than be refused
        val waitMs = msUntilStartAllowed(START_LIMIT)
        if (waitMs > 0) handler.postDelayed(delayedStart, waitMs) else startScanInternal()
        handler.post(timeoutChecker)
        handler.postDelayed(antiThrottleRunnable, SCAN_RESTART_MS)
        // tv_ble_status is for the detected device list only, so no scan-start status is posted.
        // Apply beacon registration/deletion immediately. HW filters are a snapshot taken at startScan, so they update
        // only on restart, and a deleted device stops producing samples, so state-transition cleanup never runs
        // (the TTL sweep is also deferred while UWB ranging continues). With the radio off there is nothing to rescan,
        // and force-losing would cut the UWB sessions suspendRadio keeps; the STATE_ON rebuild picks up the new filters.
        BeaconRegistry.onChanged = { handler.post { if (!radioOff) { forceLoseAll(); restartScan() } } }
        // Discovery switches are urgent: the lift must land within the 15 s discovery (in a quiet office the watchdog's
        //   restarts fill the budget), and with the screen off Android delivers nothing to an unfiltered scan.
        liveRestart = { handler.post { if (isScanning) restartScanInternal(urgent = true) } }
    }

    // Batch delay is decided by screen state (orthogonal to scan mode). Even with the screen off, a near hazard
    //   (hazardNear) gets 0ms immediate delivery — safety first.
    private fun wantedBatchDelay() = if (!isScreenOn && !hazardNear) BATCH_DELAY_SCREEN_OFF_MS else BATCH_DELAY_ACTIVE_MS

    private fun startScanInternal() {
        if (radioOff) return
        // Scan mode is currentScanMode: activeScanMode (mapped from scanPeriodMs). After 5 s of confirmed IMU
        // stillness it switches to rest (restScanMode), which is the same mode, so receive scanning is never lowered.
        val batchDelay = wantedBatchDelay()
        val settings = ScanSettings.Builder()
            .setScanMode(currentScanMode)
            .setReportDelay(batchDelay)
            // Report on a single advertisement packet — early detection of weak-signal devices
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            // Max devices per filter — track many SafeAlert devices at once
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(buildFilters(), settings, bleScanCallback)
            noteScanStart()
            runningScanMode = currentScanMode
            runningBatchDelay = batchDelay
            Log.d(TAG, "스캔 시작 (${scanModeName(currentScanMode)} · batch=${batchDelay}ms)")
        } catch (e: SecurityException) {
            Log.e(TAG, "스캔 권한 없음"); onStatusUpdate?.invoke("스캔 권한 없음")
        } catch (e: Exception) {
            Log.e(TAG, "스캔 실패: ${e.message}"); onStatusUpdate?.invoke("스캔 시작 실패")
        }
    }

    private fun restartScanInternal(immediate: Boolean = false, urgent: Boolean = false) {
        val waitMs = msUntilStartAllowed(if (urgent) START_LIMIT else START_BUDGET)
        if (waitMs > 0) {   // keep the running scan rather than stop it into a refused start
            postponedImmediate = postponedImmediate || immediate
            postponedUrgent = postponedUrgent || urgent
            handler.removeCallbacks(postponedRestart)
            handler.postDelayed(postponedRestart, waitMs)
            Log.d(TAG, "스캔 재시작 보류: 30초 시작 한도 — ${waitMs}ms 뒤")
            return
        }
        // This restart applies the latest settings, so it covers one still waiting for the budget
        handler.removeCallbacks(postponedRestart); postponedImmediate = false; postponedUrgent = false
        // Flush the batch queue right before restarting — deliver the pending results stopScan would drop (up to
        //   0.5 s of screen-off 500ms batching) first, for a lossless switch.
        //   With 0ms batching the queue is empty, so this is a no-op.
        runCatching { scanner.flushPendingScanResults(bleScanCallback) }
        try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
        if (immediate) {
            // Only for the hazard-near batching switch (setHazardNear) — restart immediately without the 300ms wait,
            //   removing the scan gap when the screen is off and a hazard is near. Ghost scans are prevented by the
            //   isScanning guard, not by the delay, so the immediate path is equally safe. It replaces a start still
            //   pending from an earlier restart, so one restart never costs two starts.
            handler.removeCallbacks(delayedStart)
            if (isScanning) startScanInternal()
            return
        }
        // One pending start at most (stopScanning removes it, so no ghost scan restarts after a stop)
        handler.removeCallbacks(delayedStart)
        handler.postDelayed(delayedStart, 300)
    }

    // RX-only restart for the watchdog (healthCheck) — TX advertising is untouched, so there is no
    // visibility gap in which this device disappears from other devices.
    fun restartScan() {
        if (isScanning) restartScanInternal()
    }

    // Notifies whether a hazard/alert is near — true: 0ms batching (immediate delivery) even with the screen off,
    //   false: power-saving batching (500ms) again while the screen is off. Restarts the scan only when the running
    //   scan's batching differs (no restart storm per packet, none with the screen on). true comes immediately from
    //   onDeviceDetected, false from the evaluateAdvertiserPower (2.5s) aggregation — the same asymmetry as
    //   advertising sleep/wake.
    fun setHazardNear(v: Boolean) {
        if (hazardNear == v) return
        hazardNear = v
        if (isScanning && wantedBatchDelay() != runningBatchDelay) {
            Log.d(TAG, "화면 꺼짐 위험근접=$v → 배칭 ${wantedBatchDelay()}ms 전환")
            // Immediate, without the 300ms gap; a promotion is urgent and may use the start headroom
            restartScanInternal(immediate = true, urgent = v)
        }
    }

    /** Screen off → 500ms hardware batching unless a hazard is near (scan mode kept; only minimizes CPU wake-ups) */
    fun notifyScreenOff() {
        isScreenOn = false
        if (isScanning && wantedBatchDelay() != runningBatchDelay) {
            Log.d(TAG, "화면 꺼짐 → ${scanModeName(currentScanMode)} + ${BATCH_DELAY_SCREEN_OFF_MS}ms 배칭 전환")
            restartScanInternal()
        }
    }

    /** Screen on → back to 0ms immediate delivery (current scan mode kept) */
    fun notifyScreenOn() {
        isScreenOn = true
        if (isScanning && wantedBatchDelay() != runningBatchDelay) {
            Log.d(TAG, "화면 켜짐 → 0ms 즉시 전달 복귀")
            restartScanInternal()
        }
    }

    // Dynamic power-saving scan mode switch (called by BleService according to IMU state).
    //  eco=true  → rest mode: restScanMode. On 5 s of confirmed stillness.
    //  eco=false → active mode: activeScanMode (mapped from scanPeriodMs). Immediately on movement or an alert.
    // restScanMode equals activeScanMode, so scan-only eco is effectively a no-op (safety first).
    //   The ecoMode flag and call sites are kept, leaving room to couple with advertising/batching power saving.
    // Restarts only when the mode actually changes (idempotent) → no needless scan resets.
    fun setEcoMode(eco: Boolean) {
        ecoMode = eco                                // Track eco state separately from the mode value
        applyScanMode()
    }

    /**
     * Applies the scanPeriodMs setting live — called by the prefs listener in BleService.
     * Recomputes only the target mode, keeping the current eco state. It restarts only when the mode actually
     * changes, so unrelated setting keys and resetToDefault (null key) are safe no-ops.
     */
    fun refreshScanMode() = applyScanMode()

    private fun applyScanMode() {
        val target = if (ecoMode) restScanMode else activeScanMode
        if (currentScanMode == target) return        // Same mode → no-op (safe, cheap)
        currentScanMode = target
        Log.d(TAG, "스캔 모드 → ${scanModeName(target)} (${if (ecoMode) "휴식" else "전투"} · 주기설정 ${BleConstants.scanPeriodMs}ms)")
        if (isScanning) restartScanInternal()
    }

    private fun scanModeName(mode: Int): String = when (mode) {
        ScanSettings.SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY"
        ScanSettings.SCAN_MODE_BALANCED    -> "BALANCED"
        ScanSettings.SCAN_MODE_LOW_POWER   -> "LOW_POWER"
        else                               -> "MODE_$mode"
    }

    /**
     * Bluetooth turned off. Scanning stops for good (BleService replaces the scanner on STATE_ON), but the loss sweep
     * keeps running, so devices still leave the normal way — BLE timeout, deferred while UWB ranging continues. Their
     * alert state is cleaned up, and a UWB session that still protects both phones is not cut short.
     */
    fun suspendRadio() {
        radioOff = true
        handler.removeCallbacks(antiThrottleRunnable)
        cancelPendingStarts()
        try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
    }

    private fun cancelPendingStarts() {
        handler.removeCallbacks(postponedRestart); handler.removeCallbacks(delayedStart); handler.removeCallbacks(failRetry)
        postponedImmediate = false; postponedUrgent = false
    }

    fun stopScanning() {
        isScanning = false
        handler.removeCallbacks(timeoutChecker)
        handler.removeCallbacks(antiThrottleRunnable)
        cancelPendingStarts()
        try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
        detectedDevices.clear()
        scanCallback = null
        BeaconRegistry.onChanged = null
        liveRestart = null
    }
}
