package com.wf11.safealert.firebase

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wf11.safealert.model.Device
import com.wf11.safealert.utils.DevSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object FirebaseManager {

    private const val TAG = "FirebaseManager"
    private val db get() = FirebaseDatabase.getInstance().reference.child(DevSettings.firebaseRoot)

    fun updateDeviceStatus(deviceId: String, alertLevel: Int) {
        val data = mapOf(
            "lastSeen" to System.currentTimeMillis(),
            "isActive" to true,
            "alertLevel" to alertLevel
        )
        db.child("devices").child(deviceId).updateChildren(data)
            .addOnFailureListener { Log.e(TAG, "장비 상태 업데이트 실패: ${it.message}") }
    }

    fun startDeviceHeartbeat(deviceId: String, alertLevel: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                updateDeviceStatus(deviceId, alertLevel)
                delay(3000L)
            }
        }
    }

    fun updateWalkerStatus(walkerId: String, nearbyDevices: List<String>) {
        val data = mapOf(
            "lastSeen" to System.currentTimeMillis(),
            "nearbyDevices" to nearbyDevices
        )
        db.child("walkers").child(walkerId).updateChildren(data)
            .addOnFailureListener { Log.e(TAG, "보행자 상태 업데이트 실패: ${it.message}") }
    }

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

    fun getTodayAlertCount(callback: (Int) -> Unit) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        db.child("alerts").child(today)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    callback(snapshot.childrenCount.toInt())
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "경보 수 조회 실패: ${error.message}")
                    callback(0)
                }
            })
    }

    fun listenDevices(callback: (List<Device>) -> Unit) {
        db.child("devices").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { child ->
                    val id = child.key ?: return@mapNotNull null
                    Device(
                        deviceId = id,
                        lastSeen = child.child("lastSeen").getValue(Long::class.java) ?: 0L,
                        isActive = child.child("isActive").getValue(Boolean::class.java) ?: false,
                        alertLevel = child.child("alertLevel").getValue(Int::class.java) ?: 0
                    )
                }
                callback(list)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "장비 리스너 오류: ${error.message}")
            }
        })
    }
}
