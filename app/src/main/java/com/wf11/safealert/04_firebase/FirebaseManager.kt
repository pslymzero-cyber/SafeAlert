package com.wf11.safealert.firebase

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.wf11.safealert.utils.DevSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object FirebaseManager {

    private const val TAG = "FirebaseManager"
    private val db get() = FirebaseDatabase.getInstance().reference.child(DevSettings.firebaseRoot)

    fun saveAlert(deviceId: String, walkerId: String, rssi: Int, level: String) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val alertId = UUID.randomUUID().toString()
        val data = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "deviceId" to deviceId,
            "walkerId" to walkerId,
            "rssi" to rssi,
            "alertLevel" to level
        )
        db.child("alerts").child(today).child(alertId).setValue(data)
            .addOnFailureListener { Log.e(TAG, "경보 저장 실패: ${it.message}") }
        Log.d(TAG, "경보 저장: $level $deviceId rssi=$rssi")
    }

}
