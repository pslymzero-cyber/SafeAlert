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
        // [v1.0.28] 기기 소실 타임아웃 — 스캔 모드 연동(동적).
        //  ACTIVE: 촘촘한 스캔 → 2초 미수신이면 소실 판정(빠른 반응).
        //  REST(BALANCED):      OFF 듀티 구간이 길어 2초를 넘기는 정상 케이스가 있으므로
        //                       6초로 연장 → 옆에 멀쩡히 있는 기기가 '감지 없음'으로
        //                       오소실/깜빡임 되는 v1.0.27 휴식모드 회귀를 방지한다.
        private const val DEVICE_TIMEOUT_ACTIVE_MS = 2000L
        private const val DEVICE_TIMEOUT_REST_MS   = 6000L

        // ── 동적 스캔 모드 정책 (v1.0.27 배터리 최적화) ────────────────────
        // IMU 가 '정지 5초'를 확정하면 BleService 가 setEcoMode(true)를 호출해
        // 휴식 모드로 전환하고, 이동 감지 즉시 setEcoMode(false) → 전투 모드 원복.
        //
        // 배칭 딜레이는 이와 직교 — 화면 꺼짐 시 CPU 웨이크업만 별도로 억제한다.
        //   화면 켜짐/활성: 0ms   — 즉시 전달, 경보 지연 0
        //   화면 꺼짐:     500ms  — BLE 칩이 CPU를 깨우지 않고 독립 스캔,
        //                          CPU는 0.5초마다 1회만 기상 → 최대 0.5초 지연 보장
        private const val BATCH_DELAY_ACTIVE_MS     = 0L
        private const val BATCH_DELAY_SCREEN_OFF_MS = 500L

        // [v1.0.48 #5] 죽은 설정이던 '스캔 주기(scanPeriodMs)'를 실제 스캔 듀티에 매핑.
        //   안드로이드 공개 스캔 API 는 임의 ms 주기를 받지 않고 3단 프리셋만 허용하므로,
        //   설정값을 가장 가까운 프리셋으로 양자화한다(스피너: 1000/2000/3000/5000ms).
        //     ≤1000ms → LOW_LATENCY (거의 연속 스캔 — 고감도·고소모)
        //     ≤3000ms → BALANCED    (약 1.0s 스캔/4.1s 주기 — 기본 3000ms, v1.0.37 거동 보존)
        //     초과    → LOW_POWER   (약 0.5s 스캔/5.1s 주기 — 절전)
        //   (구) ACTIVE_SCAN_MODE/REST_SCAN_MODE 고정 상수(v1.0.37 둘 다 BALANCED) 폐지.
        private fun mapScanMode(periodMs: Long): Int = when {
            periodMs <= 1000L -> ScanSettings.SCAN_MODE_LOW_LATENCY
            periodMs <= 3000L -> ScanSettings.SCAN_MODE_BALANCED
            else              -> ScanSettings.SCAN_MODE_LOW_POWER
        }

        // [v1.0.29] 상대 모션 상태 ServiceData 디코드용 (송신측 addServiceData 와 동일 UUID)
        private val SERVICE_DATA_UUID = ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID))
    }

    private var scanCallback: BleScanCallback? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val detectedDevices = mutableMapOf<String, Long>()
    var onStatusUpdate: ((String) -> Unit)? = null

    private var totalBleCount = 0

    // 화면 상태 — false 시 500ms 하드웨어 배칭 (스캔 모드와 직교, CPU 웨이크업만 최소화)
    @Volatile var isScreenOn: Boolean = true

    // [v1.0.48 #5] 전투 모드 = 설정 scanPeriodMs 의 프리셋 매핑(라이브 — 매번 설정에서 읽음).
    //   휴식 모드 = 전투가 LOW_LATENCY 면 BALANCED 로 한 단계 강하, 그 외엔 전투와 동일
    //   (BALANCED 미만으론 더 내리지 않음 — 기본 3000ms 에선 둘 다 BALANCED, 현행 거동 보존).
    private val activeScanMode: Int get() = mapScanMode(BleConstants.scanPeriodMs)
    private val restScanMode: Int
        get() = if (activeScanMode == ScanSettings.SCAN_MODE_LOW_LATENCY)
                    ScanSettings.SCAN_MODE_BALANCED else activeScanMode

    // [v1.0.48 #5] eco(휴식) 상태를 boolean 으로 별도 추적 — (구) '모드 값 비교' 방식은
    //   ACTIVE==REST(BALANCED)라 항상 같은 쪽으로 고정되는 함정이 있었고, 모드가 설정에
    //   따라 변하는 지금은 값 비교로 eco 여부를 복원할 수 없다.
    @Volatile private var ecoMode = false

    // [v1.0.27] 현재 스캔 모드 — 기본 전투(설정 매핑값). IMU 정지 5초 확정 시에만 휴식(eco).
    @Volatile private var currentScanMode: Int = mapScanMode(BleConstants.scanPeriodMs)

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return

            // SafeAlert 기기 감지
            val deviceData = record.getManufacturerSpecificData(BleConstants.COMPANY_ID_DEVICE)
            val walkerData = record.getManufacturerSpecificData(BleConstants.COMPANY_ID_WALKER)

            if (deviceData != null || walkerData != null) {
                totalBleCount++
                BleService.bleScanCount = totalBleCount
                // [v1.0.26 Req1] 주기적 'RX 스캔 중 · SafeAlert N개' 상태 송출 영구 삭제 —
                // 이 로그가 하단 tv_ble_status(감지 기기 목록 영역)를 계속 점유하던 문제 제거.

                val (idBytes, prefix) = when {
                    deviceData != null -> deviceData to BleConstants.DEVICE_PREFIX
                    walkerData != null -> walkerData to BleConstants.WALKER_PREFIX
                    else               -> return
                }

                val deviceId   = String(idBytes, Charsets.UTF_8)
                val fullId     = prefix + deviceId
                val rssi       = result.rssi
                val alertLevel = calcAlertLevel(rssi)

                // [v1.1.7 #1 1바이트 페이로드] 상대 ServiceData 1바이트 → Category/State/Turn 해독.
                //   기존 Speed 4비트(bits 3:0) 폐기 → Turn 2비트(bits 3:2)로 상대 회전 방향 수신.
                //   미지원(비콘/구버전): 바이트 부재 → 0x00(정지)·직진(TURN_STRAIGHT).
                val svcData       = record.getServiceData(SERVICE_DATA_UUID)
                val payloadByte   = svcData?.getOrNull(0)?.toInt()?.and(0xFF)
                val payloadPresent = payloadByte != null   // [v1.1.11 C2] 실제 1바이트 자기-신고 수신 여부(비콘/구버전=false)
                val remoteState   = payloadByte ?: BleConstants.MOTION_STATE_STATIONARY
                val remoteTurn    = if (payloadByte != null) BleConstants.decodeTurn(payloadByte) else BleConstants.TURN_STRAIGHT

                BleService.safeAlertFound++
                detectedDevices[fullId] = System.currentTimeMillis()
                scanCallback?.onDeviceDetected(fullId, rssi, alertLevel, remoteState, remoteTurn, payloadPresent)

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
                    // [v1.0.29] 외부 비콘은 모션 ServiceData 없음 → 0x00(정지)으로 전달
                    scanCallback?.onDeviceDetected(fullId, rssi, calcAlertLevel(rssi), BleConstants.MOTION_STATE_STATIONARY)
                    return
                }
            }

            // Service UUID 비콘 감지
            record.serviceUuids?.forEach { parcelUuid ->
                val uuidStr = parcelUuid.uuid.toString().uppercase()
                if (BeaconRegistry.containsUuid(uuidStr)) {
                    val fullId = BleConstants.WALKER_PREFIX + "BEA_${uuidStr.take(8)}"
                    detectedDevices[fullId] = System.currentTimeMillis()
                    scanCallback?.onDeviceDetected(fullId, result.rssi, calcAlertLevel(result.rssi), BleConstants.MOTION_STATE_STATIONARY)
                    return
                }
            }

            // MAC 기반 비콘
            val mac = result.device.address ?: return
            if (BeaconRegistry.containsMac(mac)) {
                val fullId = BleConstants.WALKER_PREFIX + "BEA_${mac.replace(":", "")}"
                detectedDevices[fullId] = System.currentTimeMillis()
                scanCallback?.onDeviceDetected(fullId, result.rssi, calcAlertLevel(result.rssi), BleConstants.MOTION_STATE_STATIONARY)
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
            // [v1.0.28] 듀티 스캔(BALANCED/LOW_POWER)은 스캔 OFF 구간이 길어 타임아웃을 늘려야
            //           정상 기기의 오소실('감지 없음' 깜빡임)을 막는다.
            // [v1.0.48 #5] 타임아웃 선택 기준을 'eco 여부'가 아닌 '현재 라디오 듀티'로 교정.
            //   (구) currentScanMode == REST_SCAN_MODE 비교는 ACTIVE==REST 라 항상 6초로
            //   고정되던 함정이었다. 연속 스캔(LOW_LATENCY)일 때만 2초 — 듀티 스캔에서 2초를
            //   쓰면 스캔 OFF 구간(최대 ~4.6초) 동안 멀쩡한 기기가 오소실된다.
            //   기본 설정(BALANCED)에선 6초 그대로 — 현행 거동 보존.
            val timeoutMs = if (currentScanMode == ScanSettings.SCAN_MODE_LOW_LATENCY)
                                DEVICE_TIMEOUT_ACTIVE_MS else DEVICE_TIMEOUT_REST_MS
            detectedDevices.entries
                .filter { now - it.value > timeoutMs }
                .map { it.key }
                .forEach { id ->
                    detectedDevices.remove(id)
                    scanCallback?.onDeviceLost(id)
                    Log.d(TAG, "신호 소실: $id")
                }
            if (isScanning) handler.postDelayed(this, 1000)
        }
    }

    // [v1.0.27 Req1] 하드웨어 스캔 필터 의무 적용 — emptyList() 금지.
    // SERVICE_UUID(우리 비콘 규격) 필터를 블루투스 칩셋에 오프로딩 → 무관한 BLE 잡음은
    // 메인 CPU 를 깨우지 않고 칩셋 단에서 즉시 폐기된다(화면 꺼짐·절전 모드 배터리 절감 핵심).
    // ※ BleAdvertiser 가 동일 SERVICE_UUID 를 광고하므로 우리 기기는 이 필터를 정상 통과한다.
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
            // [v1.1.14] iBeacon 등록 비콘 — 제조사데이터(0x004C) 패턴 필터.
            //   iBeacon 은 SERVICE_UUID·MAC 을 광고하지 않으므로 위 두 필터로는 칩셋 단에서
            //   폐기된다(= 등록해도 신호를 못 잡던 원인, 화면 꺼짐·Doze 에서 특히 치명적).
            //   [0x02,0x15, UUID 16바이트] 패턴 + 전체 마스크로 '등록 UUID 의 iBeacon 만'
            //   통과시킨다(주변 타사 0x004C 광고는 칩셋이 폐기 → 잡음 유입 0).
            BeaconRegistry.getAll().filter { it.type == "IBEACON" }.forEach { profile ->
                runCatching {
                    val u  = java.util.UUID.fromString(profile.uuid)
                    val bb = java.nio.ByteBuffer.allocate(16)
                    bb.putLong(u.mostSignificantBits); bb.putLong(u.leastSignificantBits)
                    val pattern = byteArrayOf(0x02, 0x15) + bb.array()      // iBeacon 프리픽스 + UUID
                    val mask    = ByteArray(pattern.size) { 0xFF.toByte() } // 전 바이트 정확 매칭
                    filters.add(ScanFilter.Builder()
                        .setManufacturerData(0x004C, pattern, mask)
                        .build())
                }
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
        // [v1.0.26 Req1] 'RX 스캔 시작' 상태 송출 제거 — tv_ble_status 는 감지 기기 목록 전용.
    }

    private fun startScanInternal() {
        // [v1.0.27] 스캔 모드는 currentScanMode(동적). 기본 전투(activeScanMode),
        // IMU 정지 5초 확정 시에만 휴식(restScanMode)으로 낮춘다. 이동 즉시 원복.
        // 배칭 딜레이는 화면 상태로 별도 결정(스캔 모드와 직교).
        val batchDelay = if (!isScreenOn) BATCH_DELAY_SCREEN_OFF_MS else BATCH_DELAY_ACTIVE_MS
        val settings = ScanSettings.Builder()
            .setScanMode(currentScanMode)
            .setReportDelay(batchDelay)
            // 단일 광고 패킷만으로도 즉시 보고 — 약한 신호 기기 조기 감지
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            // 필터 당 최대 기기 수 — 다수 SafeAlert 기기 동시 추적
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(buildFilters(), settings, bleScanCallback)
            Log.d(TAG, "스캔 시작 (${scanModeName(currentScanMode)} · batch=${batchDelay}ms)")
        } catch (e: SecurityException) {
            Log.e(TAG, "스캔 권한 없음"); onStatusUpdate?.invoke("❌ 스캔 권한 없음")
        } catch (e: Exception) {
            Log.e(TAG, "스캔 실패: ${e.message}"); onStatusUpdate?.invoke("❌ 스캔 시작 실패")
        }
    }

    private fun restartScanInternal() {
        try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
        // [v1.0.46 #5] stopScanning() 직후 잔류 람다가 유령 스캔을 다시 켜는 것 방지
        handler.postDelayed({ if (isScanning) startScanInternal() }, 300)
    }

    // [v1.0.46 #9] 워치독(healthCheck) 전용 RX 재시작 — TX 광고는 건드리지 않아
    // 상대 기기에서 내가 사라지는 가시성 갭이 생기지 않는다.
    fun restartScan() {
        if (isScanning) restartScanInternal()
    }

    /** 화면 꺼짐 → 500ms 하드웨어 배칭 전환 (스캔 모드는 유지, CPU 웨이크업만 최소화) */
    fun notifyScreenOff() {
        isScreenOn = false
        if (isScanning) {
            Log.d(TAG, "화면 꺼짐 → ${scanModeName(currentScanMode)} + ${BATCH_DELAY_SCREEN_OFF_MS}ms 배칭 전환")
            restartScanInternal()
        }
    }

    /** 화면 켜짐 → 0ms 즉시 전달 복귀 (현재 스캔 모드 유지) */
    fun notifyScreenOn() {
        isScreenOn = true
        if (isScanning) {
            Log.d(TAG, "화면 켜짐 → 0ms 즉시 전달 복귀")
            restartScanInternal()
        }
    }

    // [v1.0.27] 동적 절전 스캔 모드 전환 (BleService 가 IMU 상태에 따라 호출).
    //  eco=true  → 휴식 모드: restScanMode. 정지 5초 확정 시.
    //  eco=false → 전투 모드: activeScanMode(설정 scanPeriodMs 매핑). 이동 즉시·경보 발생 시.
    // 모드가 실제로 바뀔 때만 재시작(idempotent) → 불필요한 스캔 리셋 없음.
    fun setEcoMode(eco: Boolean) {
        ecoMode = eco                                // [v1.0.48 #5] 모드 값과 분리해 eco 상태 기억
        applyScanMode()
    }

    /** [v1.0.48 #5] 설정(scanPeriodMs) 라이브 반영 — BleService 의 prefs 리스너가 호출.
     *  현재 eco 상태는 유지한 채 목표 모드만 재계산. 모드가 실제로 바뀔 때만 재시작하므로
     *  무관한 설정 키 변경·resetToDefault(null key)에도 안전하다(no-op). */
    fun refreshScanMode() = applyScanMode()

    private fun applyScanMode() {
        val target = if (ecoMode) restScanMode else activeScanMode
        if (currentScanMode == target) return        // 동일 모드 → no-op (안전·저비용)
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

    fun stopScanning() {
        isScanning = false
        handler.removeCallbacks(timeoutChecker)
        handler.removeCallbacks(antiThrottleRunnable)
        try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
        detectedDevices.clear()
        scanCallback = null
        // [v1.0.26 Req1] 'RX 스캔 중지' 상태 송출 제거.
    }

    private fun calcAlertLevel(rssi: Int): Int = when {
        rssi >= BleConstants.rssiWarning -> BleConstants.LEVEL_WARNING
        else                             -> BleConstants.LEVEL_SAFE
    }
}
