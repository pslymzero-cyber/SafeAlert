package com.wf11.safealert.utils

import com.wf11.safealert.utils.SitePin.State
import com.wf11.safealert.utils.SitePin.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** Site PIN check value, validity and the decision (iterations kept small for speed). */
class SitePinTest {

    @Test fun record_validity_and_decision() {
        assertEquals(60_000, SitePin.ITER)
        val rec = SitePin.newRecord("4829175306", 1, "t", SecureRandom(), 1_000)
        val pub = rec.pub
        assertTrue(SitePin.matches("4829175306", pub))
        assertFalse(SitePin.matches("4829175307", pub))
        assertNotEquals(pub.hash, rec.gate)
        assertEquals(rec.gate, SitePin.gateOf("4829175306", pub.salt, pub.iter))
        assertEquals(pub.hash, SitePin.hashOf("4829175306", pub.salt, pub.iter))
        assertTrue(Regex("[0-9a-f]{32}").matches(pub.salt))
        assertTrue(Regex("[0-9a-f]{64}").matches(pub.hash) && Regex("[0-9a-f]{64}").matches(rec.gate))

        assertFalse(SitePin.isValidNew(SitePin.INITIAL))
        assertFalse(SitePin.isValidNew("482917530"))
        assertFalse(SitePin.isValidNew("48291753a6"))
        assertTrue(SitePin.isValidNew("4829175306"))

        assertEquals(Verdict.Ok, SitePin.check("4829175306", State.Has(pub)))
        assertEquals(Verdict.Wrong, SitePin.check("4829175307", State.Has(pub)))
        assertEquals(Verdict.FirstSet, SitePin.check(SitePin.INITIAL, State.None))
        assertEquals(Verdict.Wrong, SitePin.check("4829175306", State.None))
        assertEquals(Verdict.NeedOnline, SitePin.check(SitePin.INITIAL, State.Unknown))
    }
}
