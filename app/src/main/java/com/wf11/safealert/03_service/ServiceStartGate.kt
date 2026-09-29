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

    /** 서비스 시작에 꼭 필요한 권한(Android 12+ 근처 기기 권한, 11 이하 없음). */
    fun required(sdk: Int): Array<String> = if (sdk >= Build.VERSION_CODES.S) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT
    ) else emptyArray()

    /** 메인 화면이 한 번에 요청하는 권한: 시작 권한 + 정밀 위치. 11 이하 블루투스 권한은 설치 시 권한이다. */
    fun screenPermissions(sdk: Int): Array<String> = required(sdk) + Manifest.permission.ACCESS_FINE_LOCATION

    /** 시작 권한이 모두 허용돼 있다. */
    fun canStart(ctx: Context): Boolean = required(Build.VERSION.SDK_INT).all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Android 10~11 에서 정밀 위치가 허용돼 있으면 위치 유형을 더한다(재시작 뒤에도 스캔 결과를 받기 위해). */
    fun fgsType(sdk: Int, fineGranted: Boolean): Int {
        val cd = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return if ((sdk == Build.VERSION_CODES.Q || sdk == Build.VERSION_CODES.R) && fineGranted)
            cd or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else cd
    }

    /** 현재 기기·권한 기준 fgsType. */
    fun fgsType(ctx: Context): Int = fgsType(
        Build.VERSION.SDK_INT,
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    )

    /**
     * Android 11 에서 백그라운드 복원(재부팅·업데이트·재시작)으로 시작된 인스턴스는 위치 '항상 허용'이 없으면
     * 스캔 결과를 받지 못할 수 있다. 화면에서 시작한 인스턴스는 해당 없음.
     */
    fun bgLocationLimited(sdk: Int, fine: Boolean, background: Boolean, bgStarted: Boolean): Boolean =
        sdk == Build.VERSION_CODES.R && fine && !background && bgStarted

    /** Android 10~11 에서 시작 뒤 정밀 위치가 생겨 적용한 유형과 지금 유형이 다르면 다시 지정한다. */
    fun needsRetype(sdk: Int, appliedType: Int, fine: Boolean): Boolean =
        (sdk == Build.VERSION_CODES.Q || sdk == Build.VERSION_CODES.R) && fgsType(sdk, fine) != appliedType
}
