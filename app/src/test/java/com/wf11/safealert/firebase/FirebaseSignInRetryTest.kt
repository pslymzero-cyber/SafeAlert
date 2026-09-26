package com.wf11.safealert.firebase

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (결정 A) 익명 로그인 헬스체크 재시도 회귀 — 진행 중 중복 차단, 완료 후 재시도,
 * 로그인 상태 무호출, 예외 시 플래그 해제 4건을 internal 판정 오버로드로 검증한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirebaseSignInRetryTest {

    @Test
    fun `진행 중이면 두 번째 호출은 signIn 을 부르지 않는다`() {
        var signInCalls = 0
        var pendingDone: (() -> Unit)? = null
        val signIn: (onDone: () -> Unit) -> Unit = { onDone ->
            signInCalls++
            pendingDone = onDone
        }
        val isSignedIn: () -> Boolean = { false }

        FirebaseConfig.ensureSignedIn(isSignedIn, signIn)
        FirebaseConfig.ensureSignedIn(isSignedIn, signIn)

        try {
            assertEquals(1, signInCalls)
        } finally {
            pendingDone?.invoke()
        }
    }

    @Test
    fun `완료 후 다시 호출하면 signIn 이 재호출된다`() {
        var signInCalls = 0
        var pendingDone: (() -> Unit)? = null
        val signIn: (onDone: () -> Unit) -> Unit = { onDone ->
            signInCalls++
            pendingDone = onDone
        }
        val isSignedIn: () -> Boolean = { false }

        FirebaseConfig.ensureSignedIn(isSignedIn, signIn)
        pendingDone?.invoke()
        FirebaseConfig.ensureSignedIn(isSignedIn, signIn)

        try {
            assertEquals(2, signInCalls)
        } finally {
            pendingDone?.invoke()
        }
    }

    @Test
    fun `이미 로그인돼 있으면 signIn 을 부르지 않는다`() {
        var signInCalls = 0
        val signIn: (onDone: () -> Unit) -> Unit = { signInCalls++ }
        val isSignedIn: () -> Boolean = { true }

        FirebaseConfig.ensureSignedIn(isSignedIn, signIn)

        assertEquals(0, signInCalls)
    }

    @Test
    fun `signIn 이 예외를 던지면 플래그가 풀려 다음 호출에서 재시도한다`() {
        var signInCalls = 0
        var pendingDone: (() -> Unit)? = null
        val throwingSignIn: (onDone: () -> Unit) -> Unit = {
            signInCalls++
            throw RuntimeException("네트워크 오류")
        }
        val retrySignIn: (onDone: () -> Unit) -> Unit = { onDone ->
            signInCalls++
            pendingDone = onDone
        }
        val isSignedIn: () -> Boolean = { false }

        FirebaseConfig.ensureSignedIn(isSignedIn, throwingSignIn)
        FirebaseConfig.ensureSignedIn(isSignedIn, retrySignIn)

        try {
            assertEquals(2, signInCalls)
        } finally {
            pendingDone?.invoke()
        }
    }
}
