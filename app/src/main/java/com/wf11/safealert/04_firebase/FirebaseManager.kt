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

    // ── 기기 간 비콘 공유 (이름붙은 세트) ───────────────────────
    //   같은 root(firebaseRoot) 아래 beacon_share/<key> 에 선택분을 업로드,
    //   다른 기기가 목록에서 골라 내려받아 병합한다. (v1.1.17)

    data class BeaconSetMeta(
        val key: String,        // Firebase 키(정규화됨)
        val name: String,       // 표시 이름(사용자 입력 원본)
        val count: Int,
        val sender: String,
        val timestamp: Long
    )

    // Firebase 키 금지문자 제거 ( . # $ [ ] / )
    private fun sanitizeKey(s: String): String =
        s.trim().replace(Regex("[.#$\\[\\]/]"), "_").ifEmpty { "set" }

    /** 선택한 비콘 프로파일(JSON)을 이름붙은 세트로 업로드 */
    fun uploadBeaconSet(setName: String, profilesJson: String, count: Int, sender: String, onResult: (Boolean) -> Unit) {
        val key = sanitizeKey(setName)
        val data = mapOf(
            "name"         to setName.trim().ifEmpty { key },
            "profilesJson" to profilesJson,
            "count"        to count,
            "sender"       to sender,
            "timestamp"    to System.currentTimeMillis()
        )
        db.child("beacon_share").child(key).setValue(data)
            .addOnSuccessListener { Log.d(TAG, "비콘 세트 업로드: $key ($count개)"); onResult(true) }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 업로드 실패: ${it.message}"); onResult(false) }
    }

    /** 업로드된 이름붙은 세트 목록 조회 (최신순) */
    fun listBeaconSets(onResult: (List<BeaconSetMeta>) -> Unit) {
        db.child("beacon_share").get()
            .addOnSuccessListener { snap ->
                val sets = snap.children.mapNotNull { c ->
                    val key = c.key ?: return@mapNotNull null
                    BeaconSetMeta(
                        key       = key,
                        name      = c.child("name").getValue(String::class.java) ?: key,
                        count     = (c.child("count").getValue(Long::class.java) ?: 0L).toInt(),
                        sender    = c.child("sender").getValue(String::class.java) ?: "",
                        timestamp = c.child("timestamp").getValue(Long::class.java) ?: 0L
                    )
                }.sortedByDescending { it.timestamp }
                onResult(sets)
            }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 목록 조회 실패: ${it.message}"); onResult(emptyList()) }
    }

    /** 특정 세트의 프로파일 JSON 다운로드 (key = BeaconSetMeta.key) */
    fun downloadBeaconSet(key: String, onResult: (String?) -> Unit) {
        db.child("beacon_share").child(key).child("profilesJson").get()
            .addOnSuccessListener { onResult(it.getValue(String::class.java)) }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 다운로드 실패: ${it.message}"); onResult(null) }
    }

    /** 업로드된 세트를 클라우드에서 삭제 (관리용) */
    fun deleteBeaconSet(key: String, onResult: (Boolean) -> Unit) {
        db.child("beacon_share").child(key).removeValue()
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 삭제 실패: ${it.message}"); onResult(false) }
    }

}
