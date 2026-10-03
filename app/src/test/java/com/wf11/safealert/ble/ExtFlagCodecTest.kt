package com.wf11.safealert.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ext byte (ServiceData byte[1]) bit1 = SOS. Proves that older readers (a bit0-only reader, a
 * byte0-only reader) are unaffected by this bit.
 */
class ExtFlagCodecTest {

    @Test
    fun encodeExt_fourCombinations() {
        assertEquals(0x00, BleConstants.encodeExt(inZone = false, sos = false))
        assertEquals(0x01, BleConstants.encodeExt(inZone = true, sos = false))
        assertEquals(0x02, BleConstants.encodeExt(inZone = false, sos = true))
        assertEquals(0x03, BleConstants.encodeExt(inZone = true, sos = true))
    }

    @Test
    fun decodeSos_bits() {
        assertFalse(BleConstants.decodeSos(0x01))
        assertTrue(BleConstants.decodeSos(0x02))
        assertTrue(BleConstants.decodeSos(0x03))
        assertFalse(BleConstants.decodeSos(0))   // missing byte is parsed as 0
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
    }

    @Test
    fun byte0OnlyReader_unaffectedBySos() {
        for (cat in 0..2) for (state in 0..3) for (turn in 0..2) for (risk in 0..2) {
            for (inZone in listOf(false, true)) {
                val expected = BleConstants.encodePayload(cat, state, turn, risk)
                val off = byteArrayOf(expected, BleConstants.encodeExt(inZone, false).toByte())
                val on = byteArrayOf(expected, BleConstants.encodeExt(inZone, true).toByte())
                assertEquals(2, off.size)
                assertEquals(2, on.size)
                assertEquals(off[0], on[0])
                assertEquals(expected, on[0])
                val p = on[0].toInt() and 0xFF
                assertEquals(cat, BleConstants.decodeCategory(p))
                assertEquals(state, BleConstants.decodeState(p))
                assertEquals(turn, BleConstants.decodeTurn(p))
                assertEquals(risk, BleConstants.decodeRisk(p))
            }
        }
    }
}
