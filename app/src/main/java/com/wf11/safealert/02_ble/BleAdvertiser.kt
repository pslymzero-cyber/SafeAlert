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
        //   [v1.0.35] STATE 와 방위각(Byte 2) 재광고가 이 throttle 을 '공유'한다.
        private const val MIN_STATE_UPDATE_INTERVAL_MS = 2000L
        // 상태 변경 시 stopAdvertising 후 재시작까지 대기 (OS 정리 시간 확보)
        private const val STATE_RESTART_DELAY_MS = 50L
        // [v1.0.35] 방위각 미세 변화 무시 임계(코드 단위, 1코드 ≈ 1.41°).
        //   ±4코드(≈5.6°) 미만 변화는 재광고를 생략해 불필요한 stop/start 폭주를 막는다.
        private const val MIN_AZIMUTH_DELTA_CODE = 4
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

    // [v1.0.34 다이나믹 페이로드] 현재 송신자 STATE(2bit, PSTATE_*) — Category·Speed 와 함께
    //   encodePayload() 로 1바이트로 패킹되어 ServiceData 로 탑재된다. (Speed 는 0 고정)
    @Volatile private var currentState: Int = BleConstants.PSTATE_NORMAL
    // [v1.0.35] 현재 송신 방위각 코드(0~255) — startAdvertising 이 Byte 2 로 싣는다.
    //   BleService 가 ImuFusion.azimuthDeg 를 주기적으로 updateAzimuth() 로 밀어 넣는다.
    @Volatile private var currentAzimuthCode: Int = 0
    // [v1.0.35] STATE·방위각 재광고 공용 throttle 타임스탬프 (구 lastStateUpdateMs)
    private var lastPayloadUpdateMs = 0L
    // 재광고 시 UWB 주소 유지 (updateState / restartWithUwbAddress 공용)
    private var lastUwbAddress: ByteArray? = null
    private val stateHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * @param uwbLocalAddress 이 기기의 UWB 주소 2바이트 (null = UWB 미지원/미초기화)
     */
    fun startAdvertising(deviceId: String, uwbLocalAddress: ByteArray? = null) {
        currentDeviceId = deviceId
        if (uwbLocalAddress != null) lastUwbAddress = uwbLocalAddress
        // 장비 작업자: LOW_LATENCY(100ms) — 포크리프트 충돌 방지 우선
        // 보행자: BALANCED(250ms) — 배터리 절약
        val advertiseMode = if (prefix == BleConstants.DEVICE_PREFIX)
            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        else
            AdvertiseSettings.ADVERTISE_MODE_BALANCED

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
            // [v1.0.35] 2바이트 ServiceData: Byte 1 = Category+State+Speed(2-2-4 패킹),
            //   Byte 2 = 방위각(0~255). SERVICE_UUID 가 16비트 short UUID(0x1234) 패턴 →
            //   ServiceData 약 6바이트. 전체 ≈ flags3 + svcUuid4 + svcData6 + mfg(4+N) ≤ 31(N≤14).
            .addServiceData(
                ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID)),
                byteArrayOf(
                    BleConstants.encodePayload(category, currentState),
                    currentAzimuthCode.toByte()
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
     * Category 는 생성자에서 고정, Speed 는 0 고정 — 여기선 STATE 만 바뀐다.
     */
    fun updateState(newState: Int) {
        val s = newState and 0b11
        if (s == currentState) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPayloadUpdateMs < MIN_STATE_UPDATE_INTERVAL_MS) {
            // 2초 이내 재갱신 금지 — 다음 상태 변화 시점에 반영
            Log.d(TAG, "상태 갱신 보류(Rate-Limit): STATE=$s")
            return
        }
        lastPayloadUpdateMs = now
        currentState = s
        Log.d(TAG, "STATE 갱신 → $s (CAT=$category) 재광고")
        restartAdvertise()
    }

    /**
     * v1.0.35 방위각(Byte 2) 갱신. ImuFusion 의 현재 방위각을 BleService 가 주기적으로 밀어 넣는다.
     *  - 미세 변화(±MIN_AZIMUTH_DELTA_CODE 미만)는 무시 — 불필요한 stop/start 폭주 방지.
     *  - STATE 와 동일한 2초 Rate-Limit throttle 을 공유한다(동시 재광고 충돌 방지).
     *    throttle 에 걸리면 이번 갱신은 생략하고 다음 폴링(BleService ~1.5초)에서 재시도.
     *  - 재광고 시 startAdvertising 이 최신 currentState + currentAzimuthCode 를 함께 싣는다.
     */
    fun updateAzimuth(azimuthDeg: Float) {
        val code = BleConstants.encodeAzimuth(azimuthDeg).toInt() and 0xFF
        if (abs(code - currentAzimuthCode) < MIN_AZIMUTH_DELTA_CODE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPayloadUpdateMs < MIN_STATE_UPDATE_INTERVAL_MS) return
        lastPayloadUpdateMs = now
        currentAzimuthCode = code
        Log.d(TAG, "방위각 갱신 → code=$code (~${code * 360 / 255}°) 재광고")
        restartAdvertise()
    }

    /** v1.0.35 광고 정지 후 STATE_RESTART_DELAY_MS 뒤 최신 페이로드로 재광고 (updateState/updateAzimuth 공용). */
    private fun restartAdvertise() {
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        stateHandler.postDelayed({
            startAdvertising(currentDeviceId, lastUwbAddress)
        }, STATE_RESTART_DELAY_MS)
    }

    /** UWB 주소 준비 완료 후 광고 재시작 */
    fun restartWithUwbAddress(uwbLocalAddress: ByteArray) {
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startAdvertising(currentDeviceId, uwbLocalAddress)
        }, 300)
    }

    fun stopAdvertising() {
        advertiser.stopAdvertising(callback)
        Log.d(TAG, "광고 중지")
    }
}
