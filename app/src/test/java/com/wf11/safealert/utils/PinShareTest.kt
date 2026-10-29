package com.wf11.safealert.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PinShareTest {

    // The PIN lookup answer: only the requester's key opens it, and only for the request it was sealed for.
    @Test fun seal_open_round_trip_and_every_wrong_input_fails() {
        val kp = PinShare.newKeyPair()
        val pub = PinShare.publicB64(kp.public)
        val s = PinShare.seal("4829175306", pub, "req-1")

        assertEquals("4829175306", PinShare.open(s, kp.private, "req-1"))
        assertNull(PinShare.open(s, PinShare.newKeyPair().private, "req-1"))
        assertNull(PinShare.open(s, kp.private, "req-2"))

        val ct = Base64.getDecoder().decode(s.ct)
        ct[0] = (ct[0].toInt() xor 1).toByte()
        assertNull(PinShare.open(s.copy(ct = Base64.getEncoder().encodeToString(ct)), kp.private, "req-1"))

        // Field lengths stay inside the database rule limits.
        assertTrue(pub.length in 40..200)
        assertTrue(s.epk.length <= 200 && s.iv.length <= 40 && s.ct.length <= 120)
    }
}
