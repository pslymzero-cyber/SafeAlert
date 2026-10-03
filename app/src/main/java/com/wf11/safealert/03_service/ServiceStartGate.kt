package com.wf11.safealert.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * BleService start check and foreground service type.
 * The only start condition is the prerequisite permission of the connectedDevice FGS type (Android 12+: Nearby
 * devices permissions; none on 11 and below). Location permission is requested separately by the main screen and
 * is not a start condition. Revival, reboot restore and the stop block all use this check.
 */
object ServiceStartGate {

    /** Permissions required to start the service (Android 12+: Nearby devices permissions; none on 11 and below). */
    fun required(sdk: Int): Array<String> = if (sdk >= Build.VERSION_CODES.S) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT
    ) else emptyArray()

    /**
     * Permissions the main screen requests at once: start permissions + precise location.
     * On 11 and below, Bluetooth permissions are granted at install time.
     */
    fun screenPermissions(sdk: Int): Array<String> = required(sdk) + Manifest.permission.ACCESS_FINE_LOCATION

    /** All start permissions are granted. */
    fun canStart(ctx: Context): Boolean = required(Build.VERSION.SDK_INT).all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * On Android 10~11, adds the location type if precise location is granted (so scan results keep arriving after a restart).
     */
    fun fgsType(sdk: Int, fineGranted: Boolean): Int {
        val cd = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return if ((sdk == Build.VERSION_CODES.Q || sdk == Build.VERSION_CODES.R) && fineGranted)
            cd or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else cd
    }

    /** fgsType for the current device and permissions. */
    fun fgsType(ctx: Context): Int = fgsType(
        Build.VERSION.SDK_INT,
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    )

    /**
     * On Android 11, an instance started by a background restore (reboot, update, restart)
     * may receive no scan results without location 'Allow all the time'.
     * Instances started from the screen are not affected. The 'Allow all the time'
     * query (background) runs only when the preceding conditions hold.
     */
    fun bgLocationLimited(sdk: Int, fine: Boolean, bgStarted: Boolean, background: () -> Boolean): Boolean =
        sdk == Build.VERSION_CODES.R && bgStarted && fine && !background()

    /**
     * On Android 10~11, re-set the type when precise location was granted after start and the applied type differs from the current one.
     */
    fun needsRetype(sdk: Int, appliedType: Int, fine: Boolean): Boolean =
        (sdk == Build.VERSION_CODES.Q || sdk == Build.VERSION_CODES.R) && fgsType(sdk, fine) != appliedType
}
