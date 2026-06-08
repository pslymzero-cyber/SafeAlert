package com.wf11.safealert.ble

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.wf11.safealert.service.BleService
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import java.util.UUID

class BleScanner(private val scanner: BluetoothLeScanner) {

    companion object {
        private const val TAG = "BleScanner"
        // 30분 쓰로틀 방지: 45초마다 스캔 껐다 켜기 (OS의 30분 연속 스캔 차단 정책 우회)
        private const val SCAN_RESTART_MS   = 45_000L
        private const val DEVICE_TIMEOUT_MS = 2000L

        // ── 하드웨어 배칭 딜레이 (Always-On 정책 v1.0.24) ──────────────────
        // 안테나는 항상 SCAN_MODE_LOW_LATENCY로 100% 가동 (안전 우선, 0초 지연).
        // 배터리 방전 방지는 오직 Report Delay(하드웨어 FIFO 배칭)로만 처리한다.
        //   화면 켜짐/활성: 0ms   — 즉시 전달, 경보 지연 0
        //   화면 꺼짐:     500ms  — BLE 칩이 CPU를 깨우지 않고 독립 100% 스캔,
        //                          CPU는 0.5초마다 1회만 기상 → 최대 0.5초 지연 보장
        private const val BATCH_DELAY_ACTIVE_MS     = 0L
        private const val BATCH_DELAY_SCREEN_OFF_MS = 500L
    }

    private var scanCallback: BleScanCallback? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val detectedDevices = mutableMapOf<String, Long>()
    var onStatusUpdate: ((String) -> Unit)? = null

    private var totalBleCount = 0

    // 화면 상태 — false 시 500ms 하드웨어 배칭 (LOW_LATENCY는 그대로 유지, CPU 웨이크업만 최소화)
    @Volatile var isScreenOn: Boolean = true

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return

            // SafeAlert 기기 감지
            val deviceData = record.getManufacturerSpecificData(BleConstants.COMPANY_ID_DEVICE)
            val walkerData = record.getManufacturerSpecificData(BleConstants.COMPANY_ID_WALKER)

            if (deviceData != null || walkerData != null) {
                totalBleCount++
                BleService.bleScanCount = totalBleCount
                if (totalBleCount % 5 == 0) {
                    onStatusUpdate?.invoke("RX 스캔 중 · SafeAlert ${totalBleCount}개")
                }

                val (idBytes, prefix) = when {
                    deviceData != null -> deviceData to BleConstants.DEVICE_PREFIX
                    walkerData != null -> walkerData to BleConstants.WALKER_PREFIX
                    else               -> return
                }

                val deviceId   = String(idBytes, Charsets.UTF_8)
                val fullId     = prefix + deviceId
                val rssi       = result.rssi
                val alertLevel = calcAlertLevel(rssi)

                BleService.safeAlertFound++
                detectedDevices[fullId] = System.currentTimeMillis()
                scanCallback?.onDeviceDetected(fullId, rssi, alertLevel)

                // UWB 주소 스캔 응답 파싱 (지원 기기 한정)
                val uwbData = record.getManufacturerSpecificData(BleConstants.COMPANY_ID_UWB_EXT)
                if (uwbData != null && uwbData.size >= 2) {
                    scanCallback?.onUwbAddressReceived(fullId, uwbData.copyOf(2))
                }
                return
            }

            // 등록된 iBeacon UUID 감지
            val iBeaconData = record.getManufacturerSpecificData(0x004C)
            if (iBeaconData != null) {
                val uuid = BeaconRegistry.parseIBeaconUuid(iBeaconData)
                if (uuid != null && BeaconRegistry.containsUuid(uuid)) {
                    // [v1.0.25 Req3] 상태줄(tv_ble_status) 오염 방지 — 비콘 정보를 status로 보내지 않는다.
                    val fullId = BleConstants.WALKER_PREFIX + "BEA_${uuid.take(8)}"
                    val rssi   = result.rssi
                    detectedDevices[fullId] = System.currentTimeMillis()
                    scanCallback?.onDeviceDetected(fullId, rssi, calcAlertLevel(rssi))
                    return
                }
            }

            // Service UUID 비콘 감지
            record.serviceUuids?.forEach { parcelUuid ->
                val uuidStr = parcelUuid.uuid.toString().uppercase()
                if (BeaconRegistry.containsUuid(uuidStr)) {
                    val fullId = BleConstants.WALKER_PREFIX + "BEA_${uuidStr.take(8)}"
                    detectedDevices[fullId] = System.currentTimeMillis()
                    scanCallback?.onDeviceDetected(fullId, result.rssi, calcAlertLevel(result.rssi))
                    return
                }
            }

            // MAC 기반 비콘
            val mac = result.device.address ?: return
            if (BeaconRegistry.containsMac(mac)) {
                val fullId = BleConstants.WALKER_PREFIX + "BEA_${mac.replace(":", "")}"
                detectedDevices[fullId] = System.currentTimeMillis()
                scanCallback?.onDeviceDetected(fullId, result.rssi, calcAlertLevel(result.rssi))
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
            onStatusUpdate?.invoke("⚠ 스캔 오류: $reason")
            scanCallback?.onScanError(errorCode)
            val delayMs = if (errorCode == 6) 31_000L else 2_000L
            handler.postDelayed({ if (isScanning) restartScanInternal() }, delayMs)
        }
    }

    private val timeoutChecker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            detectedDevices.entries
                .filter { now - it.value > DEVICE_TIMEOUT_MS }
                .map { it.key }
                .forEach { id ->
                    detectedDevices.remove(id)
                    scanCallback?.onDeviceLost(id)
                    Log.d(TAG, "신호 소실: $id")
                }
            if (isScanning) handler.postDelayed(this, 1000)
        }
    }

    private fun buildFilters(): List<ScanFilter> {
        val filters = mutableListOf<ScanFilter>()
        filters.add(ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID)))
            .build())
        runCatching {
            BeaconRegistry.getAll().filter { it.type == "SERVICE_UUID" }.forEach { profile ->
                runCatching {
                    filters.add(ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(java.util.UUID.fromString(profile.uuid)))
                        .build())
                }
            }
            BeaconRegistry.getAll().filter { it.type == "MAC" }.forEach { profile ->
                runCatching { filters.add(ScanFilter.Builder().setDeviceAddress(profile.uuid).build()) }
            }
        }
        return filters
    }

    // ── 30분 쓰로틀 방지 재시작 (45초마다 스캔 껐다 켜기) ─────────────────
    // 안드로이드는 한 앱이 30분 이상 연속 스캔하면 강제 차단(error code 6)한다.
    // 45초마다 짧게 재시작하여 이 정책을 우회한다. ★ 반드시 그대로 유지할 것.
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
        startScanInternal()
        handler.post(timeoutChecker)
        handler.postDelayed(antiThrottleRunnable, SCAN_RESTART_MS)
        val beaconCnt = runCatching { BeaconRegistry.count() }.getOrDefault(0)
        onStatusUpdate?.invoke("RX 스캔 시작 (비콘 ${beaconCnt}개)")
    }

    private fun startScanInternal() {
        // Always-On: 안테나는 화면 상태·역할·감지 여부와 무관하게 무조건 LOW_LATENCY로
        // 100% 가동한다 (안전 우선, 0초 지연). 배터리 방전은 하드웨어 배칭으로만 억제.
        val batchDelay = if (!isScreenOn) BATCH_DELAY_SCREEN_OFF_MS else BATCH_DELAY_ACTIVE_MS
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(batchDelay)
            // 단일 광고 패킷만으로도 즉시 보고 — 약한 신호 기기 조기 감지
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            // 필터 당 최대 기기 수 — 다수 SafeAlert 기기 동시 추적
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(buildFilters(), settings, bleScanCallback)
            Log.d(TAG, "스캔 시작 (LOW_LATENCY 고정 · batch=${batchDelay}ms)")
        } catch (e: SecurityException) {
            Log.e(TAG, "스캔 권한 없음"); onStatusUpdate?.invoke("❌ 스캔 권한 없음")
        } catch (e: Exception) {
            Log.e(TAG, "스캔 실패: ${e.message}"); onStatusUpdate?.invoke("❌ 스캔 시작 실패")
        }
    }

    private fun restartScanInternal() {
        try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
        handler.postDelayed({ startScanInternal() }, 300)
    }

    /** 화면 꺼짐 → 500ms 하드웨어 배칭 전환 (LOW_LATENCY 유지, CPU 웨이크업 최소화) */
    fun notifyScreenOff() {
        isScreenOn = false
        if (isScanning) {
            Log.d(TAG, "화면 꺼짐 → LOW_LATENCY + ${BATCH_DELAY_SCREEN_OFF_MS}ms 배칭 전환")
            restartScanInternal()
        }
    }

    /** 화면 켜짐 → 0ms 즉시 전달 복귀 (LOW_LATENCY 유지) */
    fun notifyScreenOn() {
        isScreenOn = true
        if (isScanning) {
            Log.d(TAG, "화면 켜짐 → LOW_LATENCY + 0ms 즉시 전달 복귀")
            restartScanInternal()
        }
    }

    fun stopScanning() {
        isScanning = false
        handler.removeCallbacks(timeoutChecker)
        handler.removeCallbacks(antiThrottleRunnable)
        try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
        detectedDevices.clear()
        scanCallback = null
        onStatusUpdate?.invoke("RX 스캔 중지")
    }

    private fun calcAlertLevel(rssi: Int): Int = when {
        rssi >= BleConstants.rssiWarning -> BleConstants.LEVEL_WARNING
        else                             -> BleConstants.LEVEL_SAFE
    }
}
