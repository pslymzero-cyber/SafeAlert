package com.wf11.safealert.firebase

import android.util.Log
import com.google.firebase.database.FirebaseDatabase

object FirebaseConfig {

    private const val TAG = "FirebaseConfig"

    fun init() {
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            Log.d(TAG, "Firebase 초기화 완료 (오프라인 캐시 활성화)")
        } catch (e: Exception) {
            // setPersistenceEnabled는 앱 생명주기 내 1회만 호출 가능 — 중복 호출 시 무시
            Log.w(TAG, "Firebase 이미 초기화됨: ${e.message}")
        }
    }
}
