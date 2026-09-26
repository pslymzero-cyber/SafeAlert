package com.wf11.safealert.firebase

import android.content.Context
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * (결정 A) firebaseRoot 금지 문자 가드 회귀 — setter 경로 + prefs 직접 기록(getter 경로) 양쪽 검증.
 */
@RunWith(RobolectricTestRunner::class)
class FirebaseRootGuardTest {

    private val badValues = listOf("a.b", "x#", "$", "[", "]", " ", "")

    @Before
    fun setUp() {
        DevSettings.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `setter path normalizes bad values to wf11`() {
        val app = RuntimeEnvironment.getApplication()
        badValues.forEach { bad ->
            DevSettings.firebaseRoot = bad
            assertEquals("입력=$bad", "wf11", DevSettings.firebaseRoot)
            val raw = app.getSharedPreferences("dev_settings", Context.MODE_PRIVATE)
                .getString("firebase_root", null)
            assertEquals("저장된 원시값 입력=$bad", "wf11", raw)
        }
    }

    @Test
    fun `getter path normalizes bad values already stored to wf11`() {
        val app = RuntimeEnvironment.getApplication()
        badValues.forEach { bad ->
            app.getSharedPreferences("dev_settings", Context.MODE_PRIVATE)
                .edit().putString("firebase_root", bad).commit()
            assertEquals("직접기록=$bad", "wf11", DevSettings.firebaseRoot)
        }
    }

    @Test
    fun `normal values are preserved via setter and getter`() {
        val app = RuntimeEnvironment.getApplication()
        listOf("wf11", "site_a").forEach { good ->
            DevSettings.firebaseRoot = good
            assertEquals("setter=$good", good, DevSettings.firebaseRoot)

            app.getSharedPreferences("dev_settings", Context.MODE_PRIVATE)
                .edit().putString("firebase_root", good).commit()
            assertEquals("getter=$good", good, DevSettings.firebaseRoot)
        }
    }
}
