package com.wf11.safealert.utils

/** Set by BleService while the phone monitors as an admin; makes the SOS listener receive the whole site. */
object AdminMode {
    @Volatile var active = false
}
