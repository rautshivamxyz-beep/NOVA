package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.81: the outgoing-answer grounding check. It may only fire when the
 * install holds NO material - a correct citation must never be rewritten, and
 * an ordinary reply must pass through byte for byte.
 */
class NcieClaimTest {

    @Test
    fun theClaimsAreRecognised() {
        assertTrue(NcieClaim.claimsSource("According to your notes, Paris is the capital."))
        assertTrue(NcieClaim.claimsSource("From your documents: photosynthesis needs light."))
        assertTrue(NcieClaim.claimsSource("In your PDF it says the opposite."))
        assertTrue(NcieClaim.claimsSource("Your notes mention three causes."))
        assertTrue(NcieClaim.claimsSource("You wrote in your notes that the exam is on Monday."))
    }

    @Test
    fun ordinaryTextIsNotAClaim() {
        assertFalse(NcieClaim.claimsSource("Paris is the capital of France."))
        // the conversation itself IS material - never touched
        assertFalse(NcieClaim.claimsSource("As you said earlier, the exam is on Monday."))
        assertFalse(NcieClaim.claimsSource("The text says photosynthesis needs light."))
        assertFalse(NcieClaim.claimsSource("Your question has two parts."))
    }

    @Test
    fun withMaterialPresentNothingIsRewritten() {
        val reply = "According to your notes, Paris is the capital."
        assertEquals(reply, NcieClaim.check(reply, true))
    }

    @Test
    fun withNoMaterialTheClaimIsNeutralisedAndTheNoteAdded() {
        val out = NcieClaim.check("According to your notes, Paris is the capital.", false)
        assertTrue(out.startsWith("in general, Paris is the capital."))
        assertTrue(out.endsWith(NcieClaim.NO_MATERIAL_NOTE))
        assertFalse(NcieClaim.claimsSource(out))
    }

    @Test
    fun anHonestReplyIsUntouched() {
        val reply = "Paris is the capital of France."
        assertEquals(reply, NcieClaim.check(reply, false))
    }

    @Test
    fun theNoteIsNeverAddedTwice() {
        val once = NcieClaim.check("Your notes say X.", false)
        val twice = NcieClaim.check(once, false)
        assertEquals(once, twice)
    }
}
