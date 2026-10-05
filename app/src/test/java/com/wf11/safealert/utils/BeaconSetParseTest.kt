package com.wf11.safealert.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shared set parsing — if even one entry is unreadable, the whole set is rejected. Robolectric because org.json is needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BeaconSetParseTest {

    private val ok = """{"uuid":"E2C56DB5-DFFB-48D2-B060-D0F5A71096E0","label":"a","rssiOffset":10,"zoneMute":false,"visitorBeacon":false}"""
    private val oldFormat = """{"uuid":"AA:BB:CC:DD:EE:FF","label":"옛 형식(추가 필드 없음)"}"""

    private fun reason(json: String) = BeaconRegistry.parseProfiles(json).exceptionOrNull()?.message

    @Test
    fun `정상 세트와 옛 형식 항목은 읽는다`() {
        val r = BeaconRegistry.parseProfiles("[$ok,$oldFormat]").getOrThrow()
        assertEquals(2, r.size)
        assertEquals(10, r[0].rssiOffset)
        val old = r[1]
        assertTrue("an old entry is a visitor beacon unless it says otherwise", old.visitorBeacon)
        assertFalse(old.zoneMute)
        assertEquals(-80, old.zoneEnterRssi)
        assertEquals(0, old.rssiOffset)
        assertEquals("IBEACON", old.type)
        assertEquals(0, BeaconRegistry.parseProfiles("[]").getOrThrow().size)
    }

    /** One unreadable entry rejects the whole set, and the reason says which entry and which value. */
    @Test
    fun `읽을 수 없는 항목이 하나라도 있으면 세트 전체를 거부하고 항목 번호와 필드를 알려 준다`() {
        assertEquals("2번째 항목의 rssiOffset 값 형식이 틀렸습니다",
            reason("""[$ok,{"uuid":"AA:BB:CC:DD:EE:01","rssiOffset":"10"}]"""))
        assertEquals("2번째 항목의 uuid 값 형식이 틀렸습니다", reason("""[$ok,{"uuid":123}]"""))
        assertEquals("2번째 항목의 visitorBeacon 값 형식이 틀렸습니다",
            reason("""[$ok,{"uuid":"AA:BB:CC:DD:EE:02","visitorBeacon":"no"}]"""))
        assertEquals("2번째 항목에 UUID 가 없습니다", reason("""[$ok,{"label":"x"}]"""))
        assertEquals("1번째 항목이 비콘 정보 형식이 아닙니다", reason("[5]"))
        assertEquals("JSON 형식이 깨졌습니다", reason("[$ok"))
    }
}
