package com.wf11.safealert.ble

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

class BleAdvertiser(
    private val advertiser: BluetoothLeAdvertiser,
    private val prefix: String = BleConstants.DEVICE_PREFIX,
    private val onStatusUpdate: ((String) -> Unit)? = null
) {
    companion object { private const val TAG = "BleAdvertiser" }

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

    /**
     * @param uwbLocalAddress 이 기기의 UWB 주소 2바이트 (null = UWB 미지원/미초기화)
     */
    fun startAdvertising(deviceId: String, uwbLocalAddress: ByteArray? = null) {
        currentDeviceId = deviceId
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
