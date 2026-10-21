package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val S = "0123456789abcdef"

class SosRelayTest {

    /** A phone that hears peers over Bluetooth (the real entry store) and relays through a transport that answers only when told to. */
    private class Rig(private val me: String = "ME") {
        val store = LoneWorkerPeers { true }
        val t = FakeSosTransport()
        val mails = ArrayList<String>()
        private var clockMs = 0L
        val relay = SosRelay(t, { clockMs }) { event, _, key -> mails.add("$event:$key") }

        fun tick(now: Long, online: Boolean = true, myId: String = me) {
            clockMs = now
            store.tick(now)
            relay.tick(store.entries, now, online, myId)
        }
    }

    // A Bluetooth-only SOS is recorded once, 15 s after it was first heard, and only while connected; the SOS mail is queued
    //   when the server confirms. The phone's own advert, a phone without an ID and an SOS without an SOS ID (an older app)
    //   relay nothing. The key is PREFIX + SOS ID, so every phone writes the same key; floor and process are the sender's.
    @Test fun relays_a_bluetooth_only_sos_once_after_the_wait_and_queues_its_mail() {
        val r = Rig()
        r.store.onBle("P", true, 0, 4, "GATE-A", S, "1F", "OB", "Z1", "G6628767")
        r.tick(SosRelay.RELAY_WAIT_MS - 1)
        assertEquals(0, r.t.creates.size)
        r.tick(SosRelay.RELAY_WAIT_MS, online = false)
        assertEquals("no write while the connection is down", 0, r.t.creates.size)
        r.tick(16_000)
        assertEquals(1, r.t.creates.size)
        val key = SosRelay.PREFIX + S
        assertEquals(key, r.t.creates[0].key)
        assertEquals(SosLedger.Record("P", "P", "", "relay", "GATE-A", null, 0, 4, "1F", "OB", relayBy = "ME", sosId = S, zone = "Z1", worker = "G6628767"), r.t.recs[0])
        r.tick(17_000)
        assertEquals("no second write while the first is in flight", 1, r.t.creates.size)
        r.t.creates[0].cb(true)
        assertEquals(listOf("sos:$key"), r.mails)
        r.tick(18_000)
        assertEquals(1, r.t.creates.size)
        assertEquals(1, r.mails.size)

        // Still heard: nothing is released. The advert turns false for 10 s: the record is released and the release mail follows.
        r.store.onBle("P", true, 20_000, 4, "GATE-A", S)
        r.tick(20_000)
        assertEquals(0, r.t.resolves.size)
        r.store.onBle("P", false, 30_000, 0, "")
        r.store.onBle("P", false, 40_000, 0, "")
        r.tick(40_000)
        assertEquals(1, r.t.resolves.size)
        assertEquals(listOf(false), r.t.autos)
        r.t.resolves[0].cb(true)
        assertEquals(listOf("sos:$key", "resolved:$key"), r.mails)
        // The same episode heard again later is not uploaded a second time while its entry is still listed
        r.store.onBle("P", true, 71_000, 4, "GATE-A", S)
        r.tick(100_000)
        assertEquals(1, r.t.creates.size)

        val own = Rig()
        own.store.onBle("ME", true, 0, 4, "", S)
        own.tick(20_000)
        val anon = Rig()
        anon.store.onBle("P", true, 0, 4, "", S)
        anon.tick(20_000, myId = "")
        val old = Rig()
        old.store.onBle("P", true, 0, 4, "")
        old.tick(20_000)
        assertTrue(own.t.creates.isEmpty() && anon.t.creates.isEmpty() && old.t.creates.isEmpty())
        // Another phone hearing the same SOS builds the same key, so the rules let only one of the two records exist
        val other = Rig("ME2")
        other.store.onBle("P", true, 0, 4, "", S)
        other.tick(20_000)
        assertEquals(key, other.t.creates.single().key)
    }

    // Another phone's record already holds the key: that is the end of it (no retry, no mail). A failed read backs off and retries;
    //   an own record found active is taken over. The sender's own record joining the entry does not release it; its release does
    //   (and the release mail is queued once more: the mail script sends one mail per SOS ID); the one-hour limit releases with
    //   reason auto and no mail; an entry that left the list is not written for.
    @Test fun a_refused_or_merged_relay_settles_without_a_second_write() {
        val key = SosRelay.PREFIX + S
        fun heard() = Rig().also { it.store.onBle("P", true, 0, 4, "", S) }

        val a = heard()
        a.tick(15_000)
        a.t.creates[0].cb(false)
        a.t.reads[0].cb(SosLedger.Remote.OTHER)
        for (at in listOf(16_000L, 17_000L, 50_000L)) a.tick(at)
        assertEquals(1, a.t.creates.size)
        assertTrue(a.mails.isEmpty())

        val b = heard()
        b.tick(15_000)
        b.t.creates[0].cb(false)
        b.t.reads[0].cb(SosLedger.Remote.ERROR)
        b.tick(15_000 + SosLedger.backoffMs(1) - 1)
        assertEquals(1, b.t.creates.size)
        b.tick(15_000 + SosLedger.backoffMs(1))
        assertEquals(2, b.t.creates.size)
        b.t.creates[1].cb(false)
        b.t.reads[1].cb(SosLedger.Remote.MINE_ACTIVE)
        assertEquals(listOf("sos:$key"), b.mails)
        b.store.onServer(LoneWorkerPeers.ServerRec("k1", "P", "P", "WALKER", "still", "", 5L, true, 4, null, sosId = S), 26_000)
        b.tick(30_000)
        assertTrue("a record that joined the entry does not release the relayed one", b.t.resolves.isEmpty())
        b.store.onServer(LoneWorkerPeers.ServerRec("k1", "P", "P", "WALKER", "still", "", 5L, false, 4, 31_000L, sosId = S), 31_000)
        b.tick(32_000)
        assertEquals(1, b.t.resolves.size)
        assertEquals(listOf(false), b.t.autos)
        b.t.resolves[0].cb(true)
        assertEquals(listOf("sos:$key", "resolved:$key"), b.mails)

        val c = heard()
        c.tick(15_000)
        c.t.creates[0].cb(true)
        c.tick(SosLedger.AUTO_RELEASE_MS)
        assertEquals(listOf(true), c.t.autos)
        c.t.resolves[0].cb(true)
        assertEquals(listOf("sos:$key"), c.mails)

        val d = heard()
        d.tick(15_000)
        d.t.creates[0].cb(true)
        d.relay.tick(emptyList(), 20_000, true, "ME")
        assertTrue(d.t.resolves.isEmpty())
    }
}
