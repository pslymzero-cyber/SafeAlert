package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SERVER_NOW = 20_000L * 86_400_000L + 5

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
            relay.tick(store.entries, now, online, myId, "1F", "OB", SERVER_NOW)
        }
    }

    // A Bluetooth-only SOS is recorded once, 15 s after it was first heard, and only while connected; the SOS mail is queued
    //   when the server confirms. The phone's own advert and a phone without an ID relay nothing.
    @Test fun relays_a_bluetooth_only_sos_once_after_the_wait_and_queues_its_mail() {
        val r = Rig()
        r.store.onBle("P", true, 0, 4, "GATE-A")
        r.tick(SosRelay.RELAY_WAIT_MS - 1)
        assertEquals(0, r.t.creates.size)
        r.tick(SosRelay.RELAY_WAIT_MS, online = false)
        assertEquals("no write while the connection is down", 0, r.t.creates.size)
        r.tick(16_000)
        assertEquals(1, r.t.creates.size)
        val key = SosRelay.key("P", 4, SERVER_NOW)
        assertEquals(key, r.t.creates[0].key)
        assertEquals(SosLedger.Record("P", "P", "", "relay", "GATE-A", null, 0, 4, "1F", "OB", relayBy = "ME"), r.t.recs[0])
        r.tick(17_000)
        assertEquals("no second write while the first is in flight", 1, r.t.creates.size)
        r.t.creates[0].cb(true)
        assertEquals(listOf("sos:$key"), r.mails)
        r.tick(18_000)
        assertEquals(1, r.t.creates.size)
        assertEquals(1, r.mails.size)

        val own = Rig()
        own.store.onBle("ME", true, 0, 4, "")
        own.tick(20_000)
        val anon = Rig()
        anon.store.onBle("P", true, 0, 4, "")
        anon.tick(20_000, myId = "")
        assertTrue(own.t.creates.isEmpty() && anon.t.creates.isEmpty())
    }
}
