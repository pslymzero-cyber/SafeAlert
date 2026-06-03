package com.wf11.safealert.model

data class Device(
    val deviceId: String = "",
    val deviceName: String = "",
    val lastSeen: Long = 0L,
    val rssi: Int = 0,
    val isActive: Boolean = false,
    val alertLevel: Int = 0  // 0=정상, 1=경고, 2=위험
)
