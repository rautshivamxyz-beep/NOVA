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

    @Test
    fun aFunctionWordDoesNotOutrankTheContentWord() {
        // "what is the boiling point of water" - the first line holds what
        // and the and point, the second holds boiling and water. Ranking on
        // every query word quoted the first line; ranking on content words
        // quotes the one the user asked about.
        val chunk = "What is the point of this? The whole thing is what it is.\n" +
            "Boiling water at sea level reaches 100 degrees."
        assertEquals("Boiling water at sea level reaches 100 degrees.",
            NcieLookup.excerpt(chunk, "what is the boiling point of water"))
    }

    @Test
    fun theWindowIsCentredOnTheWholeWordNotASubstring() {
        // "the" first occurs inside "There", so the old slice started at
        // character 0 and stopped before the word the user searched for.
        val line = "There is a lot of background here about many unrelated things, " +
            "and the paragraph rambles on about cooking, storage and other matters " +
            "before it finally mentions the boiling point of water at altitude."
        val ex = NcieLookup.excerpt(line, "what is the boiling point of water", 120)
        assertTrue(ex.contains("boiling"))
    }

    @Test
    fun aShortWordIsNotFoundInsideALongerOne() {
        // "art" only ever appears as the start of "startup" and "part"
        // early in the line - the quote must be the standalone word.
        val line = "The startup of the machine takes a long time, and this part " +
            "of the process is slow, but the art of tuning it is knowing where " +
            "to begin and the rest is practice and patience over many weeks."
        val ex = NcieLookup.excerpt(line, "art", 90)
        assertTrue(ex.contains(" art "))
    }

    @Test
    fun aQueryOfOnlyFunctionWordsStillQuotesALine() {
        assertEquals("First line here.",
            NcieLookup.excerpt("First line here.\nSecond line there.", "what is it"))
    }

    @Test
    fun aHitThatSharesNoWordWithTheQuestionIsNotQuoted() {
        // retrieval can return a semantic-only hit - no literal overlap at
        // all. Quoting its first line as "your notes on tensor" would be a
        // paraphrase dressed up as a quote.
        val hits = listOf("Physics" to "Kinetic energy depends on mass and speed.")
        val body = NcieLookup.body("tensor", hits, 1)
        assertTrue(body.contains("Nothing in your notes mentions \"tensor\""))
        assertTrue(!body.contains("Kinetic energy"))
    }

    @Test
    fun anUnrelatedHitDoesNotConsumeAQuoteSlot() {
        val hits = listOf(
            "Physics" to "Kinetic energy depends on mass.",
            "ML Notes" to "A tensor is a multi-dimensional array.")
        val body = NcieLookup.body("tensor", hits, 2)
        assertTrue(body.contains("1. ML Notes - A tensor is a multi-dimensional array."))
        assertTrue(!body.contains("Physics"))
    }
}
