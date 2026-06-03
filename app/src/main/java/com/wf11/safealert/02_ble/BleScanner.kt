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
        // Android 8.1+: 필터 있는 스캔은 화면 꺼짐에도 유지됨
        private const val SCAN_RESTART_MS = 25_000L
    }

    private var scanCallback: BleScanCallback? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val detectedDevices = mutableMapOf<String, Long>()
    private val DEVICE_TIMEOUT_MS = 2000L
    var onStatusUpdate: ((String) -> Unit)? = null

    private var totalBleCount = 0

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return

            // 1) SafeAlert 앱 기기 감지 (ServiceUUID + CompanyID)
            val deviceData = record.getManufacturerSpecificData(BleConstants.COMPANY_ID_DEVICE)
            val walkerData = record.getManufacturerSpecificData(BleConstants.COMPANY_ID_WALKER)

            if (deviceData != null || walkerData != null) {
                totalBleCount++
                BleService.bleScanCount = totalBleCount
                if (totalBleCount % 5 == 0) {
                    onStatusUpdate?.invoke("RX 스캔 중 · SafeAlert 필터 수신 ${totalBleCount}개")
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
                onStatusUpdate?.invoke("✅ SafeAlert 감지: $fullId rssi=$rssi")
                detectedDevices[fullId] = System.currentTimeMillis()
                scanCallback?.onDeviceDetected(fullId, rssi, alertLevel)
                Log.d(TAG, "SafeAlert 감지: $fullId rssi=$rssi level=$alertLevel")
                return
            }

            // 2) iBeacon UUID 기반 감지 (Apple CompanyID 0x004C)
            val iBeaconData = record.getManufacturerSpecificData(0x004C)  // Apple Inc.
            if (iBeaconData != null) {
                val uuid = BeaconRegistry.parseIBeaconUuid(iBeaconData)
                if (uuid != null && BeaconRegistry.containsUuid(uuid)) {
                    val label  = BeaconRegistry.getLabelByUuid(uuid)
                    val fullId = BleConstants.WALKER_PREFIX + "BEA_${uuid.take(8)}"
                    val rssi   = result.rssi
                    detectedDevices[fullId] = System.currentTimeMillis()
                    scanCallback?.onDeviceDetected(fullId, rssi, calcAlertLevel(rssi))
                    onStatusUpdate?.invoke("📡 비콘 감지: $label  rssi=$rssi")
                    Log.d(TAG, "iBeacon 감지: $label ($uuid) rssi=$rssi")
                    return
                }
            }

            // 3) MAC 주소 기반 비콘 감지
            val mac = result.device.address
            if (mac != null && BeaconRegistry.containsMac(mac)) {
                val label  = BeaconRegistry.getLabelByMac(mac)
                val fullId = BleConstants.WALKER_PREFIX + "BEA_${mac.replace(":", "")}"
                val rssi   = result.rssi
                detectedDevices[fullId] = System.currentTimeMillis()
                scanCallback?.onDeviceDetected(fullId, rssi, calcAlertLevel(rssi))
                onStatusUpdate?.invoke("📡 MAC 비콘 감지: $label  rssi=$rssi")
                Log.d(TAG, "MAC 비콘 감지: $label ($mac) rssi=$rssi")
                return
            }

            // 4) Service UUID 기반 커스텀 비콘 감지
            val serviceUuids = record.serviceUuids ?: return
            serviceUuids.forEach { parcelUuid ->
                val uuidStr = parcelUuid.uuid.toString().uppercase()
                if (BeaconRegistry.containsUuid(uuidStr)) {
                    val label  = BeaconRegistry.getLabelByUuid(uuidStr)
                    val fullId = BleConstants.WALKER_PREFIX + "BEA_${uuidStr.take(8)}"
                    val rssi   = result.rssi
                    detectedDevices[fullId] = System.currentTimeMillis()
                    scanCallback?.onDeviceDetected(fullId, rssi, calcAlertLevel(rssi))
                    onStatusUpdate?.invoke("📡 비콘 감지: $label  rssi=$rssi")
                    Log.d(TAG, "ServiceUUID 비콘 감지: $label rssi=$rssi")
                }
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
                6                                       -> "스캔 너무 자주 (쓰로틀링)"
                else                                    -> "오류($errorCode)"
            }
            Log.e(TAG, "스캔 실패: $reason (code=$errorCode)")
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

    // SafeAlert ServiceUUID + 등록된 비콘 MAC 필터 목록
    private fun buildFilters(): List<ScanFilter> {
        val filters = mutableListOf<ScanFilter>()
        // SafeAlert 앱 기기 필터 (ServiceUUID)
        filters.add(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID)))
                .build()
        )
        // Service UUID 타입 비콘 필터
        runCatching {
            BeaconRegistry.getAll()
                .filter { it.type == "SERVICE_UUID" }
                .forEach { profile ->
                    runCatching {
                        filters.add(
                            ScanFilter.Builder()
                                .setServiceUuid(ParcelUuid(java.util.UUID.fromString(profile.uuid)))
                                .build()
                        )
                    }
                }
        }
        // MAC 주소 기반 비콘 필터 (화면 꺼짐에도 감지 유지)
        runCatching {
            BeaconRegistry.getAll()
                .filter { it.type == "MAC" }
                .forEach { profile ->
                    runCatching {
                        filters.add(ScanFilter.Builder().setDeviceAddress(profile.uuid).build())
                    }
                }
        }
        return filters
    }

    private val antiThrottleRunnable = object : Runnable {
        override fun run() {
            if (!isScanning) return
            Log.d(TAG, "쓰로틀 방지 스캔 재시작")
            restartScanInternal()
            handler.postDelayed(this, SCAN_RESTART_MS)
        }
    }

    fun startScanning(callback: BleScanCallback) {
        scanCallback  = callback
        isScanning    = true
        totalBleCount = 0
        startScanInternal()
        handler.post(timeoutChecker)
        handler.postDelayed(antiThrottleRunnable, SCAN_RESTART_MS)
        val beaconCount = runCatching { BeaconRegistry.count() }.getOrDefault(0)
        onStatusUpdate?.invoke("RX 스캔 시작 (SafeAlert + 비콘 ${beaconCount}개)")
    }

    private fun startScanInternal() {
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(buildFilters(), settings, bleScanCallback)
            Log.d(TAG, "스캔 시작 (필터: SafeAlert + 비콘 ${BeaconRegistry.count()}개)")
        } catch (e: SecurityException) {
            Log.e(TAG, "스캔 권한 없음: ${e.message}")
            onStatusUpdate?.invoke("❌ 스캔 권한 없음 — 권한 설정 필요")
        } catch (e: Exception) {
            Log.e(TAG, "스캔 시작 실패: ${e.message}")
            onStatusUpdate?.invoke("❌ 스캔 시작 실패: ${e.message}")
        }
    }

    private fun restartScanInternal() {
        try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
        handler.postDelayed({ startScanInternal() }, 500)
    }

    fun stopScanning() {
        isScanning = false
        handler.removeCallbacks(timeoutChecker)
        handler.removeCallbacks(antiThrottleRunnable)
        try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
        detectedDevices.clear()
        scanCallback = null
        onStatusUpdate?.invoke("RX 스캔 중지")
        Log.d(TAG, "스캔 중지")
    }

    private fun calcAlertLevel(rssi: Int): Int = when {
        rssi >= BleConstants.rssiDanger  -> BleConstants.LEVEL_DANGER
        rssi >= BleConstants.rssiWarning -> BleConstants.LEVEL_WARNING
        else                             -> BleConstants.LEVEL_SAFE
    }
}
