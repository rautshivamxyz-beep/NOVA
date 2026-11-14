package org.nova

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.148 "The conversation answers for itself".
 *
 * v9.33.147 gave every best-effort save a way to be heard, but the
 * conversation store - the one file the user cannot re-type - still called
 * `NcieFiles.write(...)` and dropped the Boolean it answered. A full disk lost
 * a whole chat in silence while the screen showed it as saved.
 *
 * [ChatStore.save] now answers, and the screens that claim something happened
 * ("Message deleted", an accepted rename) ask before they say it. The write
 * itself needs a Context and a real filesystem, so it cannot run here; the
 * sentence those screens say is pure, and that is what these pin.
 *
 * The rest of the change - that save() returns the answer instead of
 * discarding it, and that no `try { NcieFiles.write` is left in the source -
 * is read straight out of the code by verify_patches.
 */
class ChatStoreSaveTest {

    /** A save that landed says nothing at all: no notice, no toast, no noise. */
    @Test
    fun aSavedChatHasNothingToSay() {
        assertNull(ChatStore.saveNotice(true))
    }

    /** A save that did not land must say so, and say what it costs. */
    @Test
    fun anUnsavedChatSaysItWillBeLost() {
        val notice = ChatStore.saveNotice(false)
        assertNotNull(notice)
        assertTrue(notice!!.contains("Could not save"))
        // the user needs the consequence, not just the failure
        assertTrue(notice.contains("gone"))
    }

    /**
     * The two answers must not be the same string, or the caller could not
     * tell them apart even if it asked.
     */
    @Test
    fun theTwoAnswersAreDistinguishable() {
        assertTrue(ChatStore.saveNotice(true) != ChatStore.saveNotice(false))
    }
}
