package com.wf11.safealert.model

data class AlertLog(
    val alertId: String = "",
    val timestamp: Long = 0L,
    val deviceId: String = "",
    val walkerId: String = "",
    val rssi: Int = 0,
    val alertLevel: String = "",  // "WARNING" 또는 "DANGER"
    val location: String = ""
)
