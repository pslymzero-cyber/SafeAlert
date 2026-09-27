package com.wf11.safealert.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** (v1.1.97) 공유 세트 파싱 — 읽을 수 없는 항목이 하나라도 있으면 세트 전체를 거부(null). org.json 이 필요해 Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BeaconSetParseTest {

    private val ok = """{"uuid":"E2C56DB5-DFFB-48D2-B060-D0F5A71096E0","label":"a","rssiOffset":10,"zoneMute":false,"visitorBeacon":false}"""
    private val oldFormat = """{"uuid":"AA:BB:CC:DD:EE:FF","label":"옛 형식(추가 필드 없음)"}"""

    @Test
    fun `정상 세트와 옛 형식 항목은 읽는다`() {
        val r = BeaconRegistry.parseProfiles("[$ok,$oldFormat]")!!
        assertEquals(2, r.size)
        assertEquals(10, r[0].rssiOffset)
        assertEquals(0, BeaconRegistry.parseProfiles("[]")!!.size)
    }

    @Test
    fun `읽을 수 없는 항목이 하나라도 있으면 세트 전체를 거부한다`() {
        val bad = listOf(
            "5",                                                        // 객체 아님
            """{"label":"UUID 없음"}""",
            """{"uuid":123}""",                                         // UUID 가 숫자
            """{"uuid":"AA:BB:CC:DD:EE:01","rssiOffset":"10"}""",       // 숫자 자리에 문자열
            """{"uuid":"AA:BB:CC:DD:EE:02","visitorBeacon":"no"}"""     // 참/거짓 자리에 문자열
        )
        for (b in bad) assertNull(b, BeaconRegistry.parseProfiles("[$ok,$b]"))
        assertNull("JSON 손상", BeaconRegistry.parseProfiles("[$ok"))
    }
}
