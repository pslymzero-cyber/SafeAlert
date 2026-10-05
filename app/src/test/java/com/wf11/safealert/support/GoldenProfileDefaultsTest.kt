package com.wf11.safealert.support

import android.content.Context
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The golden profile (BleServiceTestHarness.applyGoldenDevSettings) pins DevSettings to literals equal to today's shipped
 * defaults, so on its own a changed DEFAULT_* would leave every golden green. This test reads every DevSettings value on
 * cleared prefs, applies the profile and reads them again: only the deliberate deviations below may differ. A changed
 * shipped default fails here by name and needs an explicit decision: move the pin with it, or list it below with a reason.
 */
@RunWith(RobolectricTestRunner::class)
class GoldenProfileDefaultsTest {

    /**
     * Side-effect flags the profile switches off on purpose: no vibration, sound or Firebase alert writes in tests.
     * The profile pins a fourth flag off, uwbProbeUploadEnabled, but its shipped default is already off, so it is no deviation.
     */
    private val deliberateDeviations = setOf("autoSaveAlerts", "soundEnabled", "vibrationEnabled")

    private val valueTypes: Set<Class<*>> = listOf(Int::class, Long::class, Float::class, Double::class, Boolean::class)
        .flatMap { listOfNotNull(it.javaPrimitiveType, it.javaObjectType) }.toSet() + String::class.java

    /** Every public no-arg getX/isX of the DevSettings object that returns a value type, read on the object instance. */
    private fun snapshot(): Map<String, Any?> = DevSettings::class.java.methods
        .filter { it.parameterCount == 0 && it.returnType in valueTypes && (it.name.startsWith("get") || it.name.startsWith("is")) }
        .associate { m ->
            m.name.removePrefix("get").replaceFirstChar { it.lowercase() } to
                runCatching { m.invoke(DevSettings) }.getOrElse { "threw ${(it.cause ?: it).javaClass.simpleName}" }
        }

    @Test
    fun `골든 프로파일은 정한 항목 말고는 출하 기본값과 같다`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("dev_settings", Context.MODE_PRIVATE).edit().clear().commit()   // DevSettings' prefs file
        DevSettings.init(app)
        val shipped = snapshot()
        BleServiceTestHarness.applyGoldenDevSettings()
        val changed = snapshot().filter { (name, value) -> shipped[name] != value }
        assertEquals(
            "출하 기본값과 다른 골든 설정(기본값 → 골든): " + changed.map { (name, value) -> "$name ${shipped[name]} → $value" },
            deliberateDeviations, changed.keys,
        )
    }
}
