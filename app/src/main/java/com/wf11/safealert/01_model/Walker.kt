package com.wf11.safealert.model

data class Walker(
    val walkerId: String = "",
    val lastSeen: Long = 0L,
    val rssi: Int = 0,
    val nearbyDevices: List<String> = emptyList()
)
