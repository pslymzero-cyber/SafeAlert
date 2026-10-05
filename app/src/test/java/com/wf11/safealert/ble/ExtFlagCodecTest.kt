package com.wf11.safealert.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ext byte (ServiceData byte[1]) bit0 = IN_ZONE, bit1 = SOS, and the frozen 1-byte payload layout (byte[0]).
 * That the SOS advert keeps byte[0] untouched is SosAdvertTest's job (it builds the real service data).
 */
class ExtFlagCodecTest {

    /**
     * The ext byte carries IN_ZONE in bit0 and SOS in bit1: each row is encoded and its SOS bit read back.
     * A byte of 0 is also what a missing ext byte parses as.
     */
    @Test
    fun extByte_inZoneInBit0_sosInBit1() {
        class Row(val label: String, val inZone: Boolean, val sos: Boolean, val byte: Int)
        val rows = listOf(
            Row("row 1 neither (a missing byte is parsed as 0)", inZone = false, sos = false, byte = 0x00),
            Row("row 2 inZone", inZone = true, sos = false, byte = 0x01),
            Row("row 3 sos", inZone = false, sos = true, byte = 0x02),
            Row("row 4 inZone+sos", inZone = true, sos = true, byte = 0x03),
        )
        for (r in rows) {
            assertEquals("${r.label}: encodeExt", r.byte, BleConstants.encodeExt(inZone = r.inZone, sos = r.sos))
            assertEquals("${r.label}: decodeSos", r.sos, BleConstants.decodeSos(r.byte))
        }
    }

    /** Deployed devices decode this 1-byte layout (CAT 7:6, STATE 5:4, TURN 3:2, RISK 1:0); it must never change. */
    @Test
    fun encodePayload_bitLayoutIsFrozen() {
        fun enc(cat: Int, state: Int, turn: Int, risk: Int) = BleConstants.encodePayload(cat, state, turn, risk).toInt() and 0xFF
        val c = BleConstants
        assertEquals(0x00, enc(c.CAT_WALKER, c.PSTATE_IDLE, c.TURN_STRAIGHT, c.LEVEL_SAFE))
        assertEquals(0xA6, enc(c.CAT_FORKLIFT, c.PSTATE_REVERSE, c.TURN_LEFT, c.LEVEL_DANGER))
        assertEquals(0x79, enc(c.CAT_EPJ, c.PSTATE_LOADING, c.TURN_RIGHT, c.LEVEL_WARNING))
        assertEquals(0x90, enc(c.CAT_FORKLIFT, c.PSTATE_FORWARD, c.TURN_STRAIGHT, c.LEVEL_SAFE))
        assertEquals(0x65, enc(1, 2, 1, 1))
        assertEquals(0xC0, enc(0b111, 0, 0, 0))   // category masked to 2 bits
        assertEquals(2, BleConstants.decodeCategory(0xA6))
        assertEquals(2, BleConstants.decodeState(0xA6))
        assertEquals(1, BleConstants.decodeTurn(0xA6))
        assertEquals(2, BleConstants.decodeRisk(0xA6))

        // 0xA6 has CAT, STATE and RISK all equal to 2, so it cannot tell a decoder reading the wrong field.
        // A full encode→decode round trip over every combination can; the fixed hex values above catch swapped fields.
        for (cat in 0..2) for (state in 0..3) for (turn in 0..2) for (risk in 0..2) {
            val p = BleConstants.encodePayload(cat, state, turn, risk).toInt() and 0xFF
            val where = "cat=$cat state=$state turn=$turn risk=$risk"
            assertEquals(where, cat, BleConstants.decodeCategory(p))
            assertEquals(where, state, BleConstants.decodeState(p))
            assertEquals(where, turn, BleConstants.decodeTurn(p))
            assertEquals(where, risk, BleConstants.decodeRisk(p))
        }
    }
}
