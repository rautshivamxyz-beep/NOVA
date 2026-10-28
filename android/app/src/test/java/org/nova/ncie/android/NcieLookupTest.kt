package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.80: the notes-lookup command. It answers from the user's own lines
 * with no model in the loop, so the two things that can go wrong are a
 * command that fires when it should not, and a quote that is not the line the
 * user meant. Both are pinned here.
 */
class NcieLookupTest {

    @Test
    fun theCommandFormsAreRecognised() {
        assertEquals("tensor", NcieLookup.query("find in my notes: tensor"))
        assertEquals("tensor", NcieLookup.query("search my notes for tensor"))
        assertEquals("tensor", NcieLookup.query("look through my documents for tensor?"))
        assertEquals("tensor", NcieLookup.query("which note mentions tensor"))
        assertEquals("tensor", NcieLookup.query("what document says tensor"))
    }

    @Test
    fun aNormalQuestionIsNotALookup() {
        assertNull(NcieLookup.query("what is a tensor"))
        assertNull(NcieLookup.query("find the derivative of x squared"))
        // a pasted page that merely contains the words is not a command
        assertNull(NcieLookup.query("find in my notes: " + "word ".repeat(40)))
        // a command with no query after it is not a lookup either
        assertNull(NcieLookup.query("find in my notes:"))
    }

    @Test
    fun noSourcesSaysSoWithoutPretending() {
        val body = NcieLookup.body("tensor", emptyList(), 0)
        assertTrue(body.contains("no notes or documents yet"))
    }

    @Test
    fun nothingFoundNamesWhatItLookedIn() {
        val body = NcieLookup.body("tensor", emptyList(), 3)
        assertTrue(body.contains("Nothing in your notes mentions \"tensor\""))
        assertTrue(body.contains("3 sources"))
        assertEquals(true, NcieLookup.body("tensor", emptyList(), 1).contains("1 source."))
    }

    @Test
    fun aHitIsQuotedWithItsSource() {
        val hits = listOf(
            "ML Notes" to "A tensor is a multi-dimensional array.\nIt has a shape.",
            "Physics" to "The stress tensor describes internal forces."
        )
        val body = NcieLookup.body("tensor", hits, 2)
        assertTrue(body.contains("1. ML Notes - A tensor is a multi-dimensional array."))
        assertTrue(body.contains("2. Physics - The stress tensor describes internal forces."))
        assertTrue(body.contains("no model used"))
        // the second line of the first chunk is not the best match - the line
        // that actually holds the query word is quoted
        assertTrue(!body.contains("It has a shape"))
    }

    @Test
    fun atMostFourQuotes() {
        val hits = (1..9).map { "src$it" to "line about tensor $it" }
        val body = NcieLookup.body("tensor", hits, 9)
        assertTrue(body.contains("4. src4"))
        assertTrue(!body.contains("5. src5"))
    }

    @Test
    fun theExcerptIsTheLineWithTheMostQueryWords() {
        val chunk = "Intro paragraph about nothing.\nThe tensor rank is the number of indices.\n" +
            "Closing words about tensors in general."
        val ex = NcieLookup.excerpt(chunk, "tensor rank indices")
        assertEquals("The tensor rank is the number of indices.", ex)
    }

    @Test
    fun aLongLineIsBoundedAndCentredOnTheMatch() {
        val filler = "lorem ipsum ".repeat(60)
        val chunk = filler + "the attention tensor lives here " + filler
        val ex = NcieLookup.excerpt(chunk, "tensor", 120)
        assertTrue(ex.length <= 130)
        assertTrue(ex.contains("tensor"))
        assertTrue(ex.startsWith("..."))
    }

    @Test
    fun aCodeFenceInTheNotesCannotBreakTheReply() {
        val hits = listOf("Notes" to "here is code ```python\nx = 1\n``` done")
        val body = NcieLookup.body("code", hits, 1)
        assertEquals(2, Regex("```").findAll(body).count())
    }
}
