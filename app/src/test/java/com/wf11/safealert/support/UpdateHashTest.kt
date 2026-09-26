package com.wf11.safealert.support

import com.wf11.safealert.utils.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.1.87 업데이트 APK 무결성 — 해시 계산과 fail-closed 비교. */
class UpdateHashTest {

    private val ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" // SHA-256("abc")

    @Test
    fun sha256Hex() {
        assertEquals(ABC, UpdateManager.sha256Hex("abc".byteInputStream()))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            UpdateManager.sha256Hex(ByteArray(0).inputStream())
        )
        // 버퍼(64KB) 경계를 넘는 입력도 한 번에 계산한 값과 같다
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val expect = java.security.MessageDigest.getInstance("SHA-256").digest(big).joinToString("") { "%02x".format(it) }
        assertEquals(expect, UpdateManager.sha256Hex(big.inputStream()))
    }

    @Test
    fun hashMatches() {
        assertTrue(UpdateManager.hashMatches(ABC, ABC))
        assertTrue(UpdateManager.hashMatches("  ${ABC.uppercase()}\n", ABC))
        assertFalse(UpdateManager.hashMatches("", ABC))
        assertFalse(UpdateManager.hashMatches("   ", ""))
        assertFalse(UpdateManager.hashMatches(ABC, ""))
        assertFalse(UpdateManager.hashMatches(ABC, ABC.replaceFirst('b', 'c')))
    }
}
