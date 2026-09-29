package com.wf11.safealert.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.99 ext byte (ServiceData byte[1]) bit1 = SOS. Proves that old readers (bit0-only reader,
 * byte0-only reader) are unaffected by the new bit.
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
    fun bit0OnlyReader_ignoresSos() {
        for (inZone in listOf(false, true)) for (sos in listOf(false, true)) {
            val ext = BleConstants.encodeExt(inZone, sos)
            assertEquals(inZone, (ext and BleConstants.EXT_FLAG_IN_ZONE) != 0)
        }
    }

    @Test
    fun decodeSos_bits() {
        assertFalse(BleConstants.decodeSos(0x01))
        assertTrue(BleConstants.decodeSos(0x02))
        assertTrue(BleConstants.decodeSos(0x03))
        assertFalse(BleConstants.decodeSos(0))   // missing byte is parsed as 0
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
