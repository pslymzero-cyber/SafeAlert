package com.wf11.safealert.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SosAdvertTest {

    @Test
    fun maximal_sos_packet_fits_31_bytes_and_id_cap_applies_only_with_extension() {
        val ext = SosAdvert.serviceData(0, BleConstants.encodeExt(true, true), 255, 0xFFFF)
        val plain = SosAdvert.serviceData(0, BleConstants.encodeExt(true, false), 0, 0)
        val worst = SosAdvert.advertBytes(SosAdvert.idBytes("A".repeat(20), true).size, ext.size)
        println("SOS_MAX_PACKET=" + worst)
        assertTrue(worst <= 31)
        assertTrue(SosAdvert.advertBytes(12, 5) <= 31)
        assertTrue(SosAdvert.advertBytes(11, 5) <= 31)
        assertTrue(SosAdvert.advertBytes(15, plain.size) <= 31)
        val auto = "AUTO_ABCDEFG"
        assertEquals(11, SosAdvert.idBytes(auto.take(11), true).size)
        assertEquals(11, SosAdvert.idBytes(auto.take(11), false).size)
        val long = "ABCDEFGHIJKLMNO"
        assertEquals(12, SosAdvert.idBytes(long, true).size)
        assertEquals(15, SosAdvert.idBytes(long, false).size)
    }

    /**
     * Service data layout (byte0, ext byte, episode, hint hi, hint lo): byte0 and the ext byte pass through unchanged,
     * episode and hint decode unsigned, a packet without an episode stays 2 bytes (its hint is dropped), and absent or
     * shorter data (older senders) decodes as 0. A null column is not checked for that row.
     */
    @Test
    fun service_data_layout_round_trips_episode_and_hint() {
        class Row(
            val label: String, val svc: ByteArray?,
            val size: Int? = null, val byte0: Byte? = null, val sos: Boolean? = null,
            val episode: Int? = null, val hint: Int? = null,
        )
        val b0 = BleConstants.encodePayload(1, 2, 1, 1)
        val rows = listOf(
            Row("row 1 byte0 and byte1 kept, episode 7 and hint 0xBEEF appended",
                SosAdvert.serviceData(b0, BleConstants.encodeExt(false, true), 7, 0xBEEF),
                size = 5, byte0 = b0, sos = true, episode = 7, hint = 0xBEEF),
            Row("row 2 episode and hint bytes are unsigned", SosAdvert.serviceData(0, 2, 255, 0xFFFF),
                episode = 255, hint = 0xFFFF),
            Row("row 3 no episode keeps two bytes", SosAdvert.serviceData(5, BleConstants.encodeExt(true, false), 0, 0x1234),
                size = 2, episode = 0, hint = 0),
            Row("row 4 absent service data", null, episode = 0, hint = 0),
            Row("row 5 old sender with episode only", byteArrayOf(1, 2, 9), episode = 9, hint = 0),
            Row("row 6 truncated hint", byteArrayOf(1, 2, 9, 7), hint = 0),
        )
        for (r in rows) {
            r.size?.let { assertEquals("${r.label}: size", it, r.svc!!.size) }
            r.byte0?.let { assertEquals("${r.label}: byte0", it, r.svc!![0]) }
            r.sos?.let { assertEquals("${r.label}: SOS bit", it, BleConstants.decodeSos(r.svc!![1].toInt() and 0xFF)) }
            r.episode?.let { assertEquals("${r.label}: episode", it, SosAdvert.decodeEpisode(r.svc)) }
            r.hint?.let { assertEquals("${r.label}: hint", it, SosAdvert.decodeHint(r.svc)) }
        }
    }

    @Test
    fun beacon_short_id_is_crc16_ccitt_false_case_insensitive_and_nonzero() {
        assertEquals(0x29B1, SosAdvert.beaconShortId("123456789"))
        assertEquals(SosAdvert.beaconShortId("aabbccddeeff"), SosAdvert.beaconShortId("AABBCCDDEEFF"))
        assertEquals(0, SosAdvert.beaconShortId(""))
        assertEquals(1, SosAdvert.nonZero(0))
        assertEquals(0x29B1, SosAdvert.nonZero(0x29B1))
    }
}
