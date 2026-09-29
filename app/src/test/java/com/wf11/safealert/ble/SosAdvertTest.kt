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

    @Test
    fun service_data_keeps_byte0_and_byte1_and_appends_episode_and_hint() {
        val b0 = BleConstants.encodePayload(1, 2, 1, 1)
        val svc = SosAdvert.serviceData(b0, BleConstants.encodeExt(false, true), 7, 0xBEEF)
        assertEquals(5, svc.size)
        assertEquals(b0, svc[0])
        assertTrue(BleConstants.decodeSos(svc[1].toInt() and 0xFF))
        assertEquals(7, SosAdvert.decodeEpisode(svc))
        assertEquals(0xBEEF, SosAdvert.decodeHint(svc))
    }

    @Test
    fun no_episode_keeps_two_bytes_and_old_senders_decode_as_absent() {
        val svc = SosAdvert.serviceData(5, BleConstants.encodeExt(true, false), 0, 0x1234)
        assertEquals(2, svc.size)
        assertEquals(0, SosAdvert.decodeEpisode(null))
        assertEquals(0, SosAdvert.decodeHint(null))
        assertEquals(0, SosAdvert.decodeEpisode(svc))
        assertEquals(0, SosAdvert.decodeHint(svc))
        val three = byteArrayOf(1, 2, 9)
        assertEquals(9, SosAdvert.decodeEpisode(three))
        assertEquals(0, SosAdvert.decodeHint(three))
        assertEquals(0, SosAdvert.decodeHint(byteArrayOf(1, 2, 9, 7)))
    }

    @Test
    fun episode_and_hint_bytes_are_unsigned() {
        val svc = SosAdvert.serviceData(0, 2, 255, 0xFFFF)
        assertEquals(255, SosAdvert.decodeEpisode(svc))
        assertEquals(0xFFFF, SosAdvert.decodeHint(svc))
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
