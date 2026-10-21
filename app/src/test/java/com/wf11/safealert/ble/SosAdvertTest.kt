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
        // Scan response during an SOS: SOS info only = 1 length + 1 type + 2 company + 23 payload = 27 bytes
        val sid = "0123456789abcdef"
        val info = SosAdvert.info(sid, SosAdvert.scopeByte("10F", "HR"), "ZONE", "G123456789")!!
        assertEquals(SosAdvert.INFO_FULL_LEN, info.size)
        assertEquals(23, info.size)
        assertTrue(1 + 1 + 2 + info.size <= SosAdvert.PACKET_MAX)
        assertEquals(27, 1 + 1 + 2 + info.size)
        for (bad in listOf("0123456789ABCDEF", "0123456789abcde", "0123456789abcdeg", "")) {
            assertEquals("not an SOS ID: $bad", null, SosAdvert.info(bad, 0))
        }
        // While my rescue request runs the advert never drops below BALANCED (the 0.2 s setting), even asleep; a faster
        //   setting or a burst stays faster, and without it sleep is LOW_POWER again
        val lowPower = android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        val balanced = android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_BALANCED
        val lowLatency = android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        assertEquals(balanced, BleAdvertiser.modeFor(burst = false, floorBalanced = true, paused = true, intervalMs = 200))
        assertEquals(balanced, BleAdvertiser.modeFor(burst = false, floorBalanced = true, paused = false, intervalMs = 1000))
        assertEquals(lowLatency, BleAdvertiser.modeFor(burst = false, floorBalanced = true, paused = false, intervalMs = 100))
        assertEquals(lowLatency, BleAdvertiser.modeFor(burst = true, floorBalanced = true, paused = true, intervalMs = 200))
        assertEquals(lowPower, BleAdvertiser.modeFor(burst = false, floorBalanced = false, paused = true, intervalMs = 200))
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
        // Scan response info: scope byte round trip over every floor and process, not-in-list values read as "" and 0
        for (f in com.wf11.safealert.utils.SiteScope.FLOORS) for (p in com.wf11.safealert.utils.SiteScope.PROCS) {
            val sc = SosAdvert.scopeByte(f, p)
            assertEquals(f, SosAdvert.floorOf(sc)); assertEquals(p, SosAdvert.procOf(sc))
        }
        assertEquals(0, SosAdvert.scopeByte("", ""))
        assertEquals(0, SosAdvert.scopeByte("11F", "XX"))
        assertEquals("", SosAdvert.floorOf(SosAdvert.scopeByte("11F", "OB"))); assertEquals("OB", SosAdvert.procOf(SosAdvert.scopeByte("11F", "OB")))
        val sid = "0123456789abcdef"
        val sc = SosAdvert.scopeByte("3F", "IB")
        val info = SosAdvert.info(sid, sc)
        assertEquals(sid, SosAdvert.decodeSosId(info)); assertEquals(sc, SosAdvert.decodeScope(info))
        assertEquals("", SosAdvert.decodeSosId(null)); assertEquals(0, SosAdvert.decodeScope(null))
        assertEquals("", SosAdvert.decodeSosId(ByteArray(8))); assertEquals(0, SosAdvert.decodeScope(ByteArray(8)))
        // Zone and worker code round trip; values outside their format come back "" and leave the SOS ID and scope alone
        val full = SosAdvert.info(sid, sc, "Z1", "G6628767")
        assertEquals("Z1", SosAdvert.decodeZone(full)); assertEquals("G6628767", SosAdvert.decodeWorker(full))
        val max = SosAdvert.info(sid, sc, "ZONE", "G123456789")
        assertEquals("ZONE", SosAdvert.decodeZone(max)); assertEquals("G123456789", SosAdvert.decodeWorker(max))
        val none = SosAdvert.info(sid, sc)
        assertEquals("", SosAdvert.decodeZone(none)); assertEquals("", SosAdvert.decodeWorker(none))
        val bad = SosAdvert.info(sid, sc, "TOOLONG", "GG1")
        assertEquals("", SosAdvert.decodeZone(bad)); assertEquals("", SosAdvert.decodeWorker(bad))
        assertEquals(sid, SosAdvert.decodeSosId(bad)); assertEquals(sc, SosAdvert.decodeScope(bad))
        // A 1.2.13 sender sends only the first 9 bytes: SOS ID and scope still read, zone and worker code are ""
        val old = full!!.copyOf(SosAdvert.INFO_LEN)
        assertEquals(sid, SosAdvert.decodeSosId(old)); assertEquals(sc, SosAdvert.decodeScope(old))
        assertEquals("", SosAdvert.decodeZone(old)); assertEquals("", SosAdvert.decodeWorker(old))
        assertEquals("", SosAdvert.decodeZone(null)); assertEquals("", SosAdvert.decodeWorker(null))
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
