package com.wf11.safealert.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
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
        signInAnonymously()
    }

    /**
     * (v1.1.84) 익명 로그인 — DB 규칙 `auth != null` 잠금의 사전 작업.
     * 기존 세션이 있으면 그대로 재사용한다(UID 유지).
     *
     * 실패는 전부 로그만 남긴다. 경보 판정은 BLE 전용이라 서버와 무관하므로,
     * 네트워크가 없거나 로그인이 막혀도 스캔·광고·경보·진동은 그대로 돈다.
     * 서버 기록만 실패하는 것이 정상 거동이다.
     */
    private fun signInAnonymously() {
        try {
            val auth = FirebaseAuth.getInstance()
            val existing = auth.currentUser
            if (existing != null) {
                Log.d(TAG, "익명 세션 재사용: ${existing.uid}")
                return
            }
            auth.signInAnonymously()
                .addOnSuccessListener { Log.d(TAG, "익명 로그인 성공: ${it.user?.uid}") }
                .addOnFailureListener { Log.w(TAG, "익명 로그인 실패(서버 기록만 영향): ${it.message}") }
        } catch (e: Exception) {
            Log.w(TAG, "익명 로그인 시도 불가(서버 기록만 영향): ${e.message}")
        }
    }
}
