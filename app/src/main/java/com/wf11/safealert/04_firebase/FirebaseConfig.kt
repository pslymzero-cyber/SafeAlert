package com.wf11.safealert.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

object FirebaseConfig {

    private const val TAG = "FirebaseConfig"

    @Volatile
    private var signInInProgress = false

    fun init() {
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            Log.d(TAG, "Firebase 초기화 완료 (오프라인 캐시 활성화)")
        } catch (e: Exception) {
            // setPersistenceEnabled can be called only once per app lifetime — repeated calls are ignored
            Log.w(TAG, "Firebase 이미 초기화됨: ${e.message}")
        }
        ensureSignedIn()
    }

    /**
     * Anonymous sign-in — required by the DB rule lock `auth != null`.
     * Reuses an existing session if there is one (keeps the UID).
     *
     * All failures are only logged. Alert judging is BLE-only and independent of the server, so with no network
     * or a blocked sign-in, scanning, advertising, alerts and vibration keep running.
     * Only server records fail; that is the expected behavior.
     *
     * If not signed in, BleService's 15 s health check calls this again. Skipped while one is in progress;
     * when it finishes (success, failure or exception) the flag is cleared so the next cycle retries.
     * Threading note: init, the health check and Task listeners all run on the main thread, so @Volatile is enough.
     */
    fun ensureSignedIn() {
        ensureSignedIn(
            isSignedIn = { FirebaseAuth.getInstance().currentUser != null },
            signIn = { onDone ->
                FirebaseAuth.getInstance().signInAnonymously()
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d(TAG, "익명 로그인 성공: ${task.result?.user?.uid}")
                        } else {
                            Log.w(TAG, "익명 로그인 실패(서버 기록만 영향): ${task.exception?.message}")
                        }
                        onDone()
                    }
            }
        )
    }

    internal fun ensureSignedIn(isSignedIn: () -> Boolean, signIn: (onDone: () -> Unit) -> Unit) {
        if (signInInProgress) return
        try {
            if (isSignedIn()) return
            signInInProgress = true
            signIn { signInInProgress = false }
        } catch (e: Exception) {
            signInInProgress = false
            Log.w(TAG, "익명 로그인 시도 불가(서버 기록만 영향): ${e.message}")
        }
    }
}
