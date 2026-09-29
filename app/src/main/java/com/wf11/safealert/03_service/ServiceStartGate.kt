package com.wf11.safealert.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * BleService 시작 판정과 포그라운드 서비스 유형 (v1.1.99).
 * 시작 조건은 근처 기기 유형의 전제 권한뿐이다(Android 12+ 근처 기기 권한, 11 이하 없음). 위치 권한은 메인 화면이
 * 따로 요청하며 시작 조건이 아니다. 되살리기·재부팅 복원·정지 차단이 모두 이 판정을 쓴다.
 */
object ServiceStartGate {

    fun required(sdk: Int): Array<String> = if (sdk >= Build.VERSION_CODES.S) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT
    ) else emptyArray()

    fun canStart(ctx: Context): Boolean = required(Build.VERSION.SDK_INT).all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Android 10~11 에서 정밀 위치가 허용돼 있으면 위치 유형을 더한다(재시작 뒤에도 스캔 결과를 받기 위해). */
    fun fgsType(sdk: Int, fineGranted: Boolean): Int {
        val cd = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return if ((sdk == Build.VERSION_CODES.Q || sdk == Build.VERSION_CODES.R) && fineGranted)
            cd or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else cd
    }

    fun fgsType(ctx: Context): Int = fgsType(
        Build.VERSION.SDK_INT,
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    )
}
