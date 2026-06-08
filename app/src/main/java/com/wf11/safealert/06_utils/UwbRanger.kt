package com.wf11.safealert.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope

/**
 * UWB 정밀 거리 측정 래퍼
 *
 * 현재 상태: 구조 스텁 — androidx.core.uwb 라이브러리 없이 빌드 가능.
 * UWB 실제 기능 활성화: build.gradle에
 *   implementation 'androidx.core.uwb:core-uwb:1.0.0-alpha08'
 * 추가 후 이 파일을 실제 구현으로 교체.
 *
 * 미지원/라이브러리 없음 → isSupported = false → BLE RSSI 자동 폴백.
 *
 * [uwbDistances]: deviceId → 거리(m). UWB 활성 시 채워짐.
 */
class UwbRanger(
    private val context: Context,
    private val scope: CoroutineScope,         // 향후 coroutine ranging 세션용
    private val isDeviceMode: Boolean          // true=Controller(DEVICE), false=Controlee(WALKER)
) {
    companion object {
        private const val TAG = "UwbRanger"
        const val UWB_CHANNEL  = 9   // FiRa 기본 채널
        const val UWB_PREAMBLE = 10  // FiRa 기본 프리앰블 인덱스

        /** 하드웨어 UWB 지원 여부 (API 31+ & FEATURE_UWB) */
        fun isHardwareSupported(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31) return false
            return context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)
        }
    }

    /**
     * UWB 활성 여부.
     * TODO: androidx.core.uwb 라이브러리 추가 후 → isHardwareSupported(context) 로 변경
     */
    val isSupported: Boolean = false

    /** deviceId → ToF 측정 거리(m). UWB 비활성 시 항상 빈 맵 → BLE 폴백 */
    val uwbDistances = mutableMapOf<String, Float>()

    /** 이 기기의 UWB 로컬 주소 (BLE 스캔 응답에 포함) */
    @Volatile var localAddress: ByteArray? = null
        private set

    /**
     * UWB 세션 초기화 및 로컬 주소 획득.
     * 현재: 스텁 — null 반환 → BLE 폴백.
     */
    suspend fun initSession(): ByteArray? {
        Log.d(TAG, "UWB 스텁: initSession() → null (라이브러리 미설치)")
        return null
    }

    /** BLE 스캔 응답에서 피어 UWB 주소 수신 시 호출 */
    fun onPeerUwbAddressReceived(deviceId: String, peerUwbAddr: ByteArray) {
        // 스텁: 무시
    }

    /** 피어 기기 이탈 시 거리 맵에서 제거 */
    fun onDeviceLost(deviceId: String) {
        uwbDistances.remove(deviceId)
    }

    /** UWB 세션 정리 */
    fun stop() {
        uwbDistances.clear()
        localAddress = null
        Log.d(TAG, "UwbRanger 중지 (스텁)")
    }
}
