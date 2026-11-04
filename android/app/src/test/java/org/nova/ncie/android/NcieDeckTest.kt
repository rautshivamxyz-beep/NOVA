package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.109 "One flashcard count": the briefing and the dream report read the
 * same file and printed two different numbers, because one counted cards and
 * the other counted lines.
 */
class NcieDeckTest {

    private fun card(front: String, back: String) = front + NcieDeck.SEP + back

    @Test
    fun aRealCardIsACard() {
        assertTrue(NcieDeck.isCard(card("What is Ohm's law", "V = IR")))
    }

    @Test
    fun aLineWithoutASeparatorIsNotACard() {
        assertFalse(NcieDeck.isCard("just a note"))
        assertFalse(NcieDeck.isCard(""))
    }

    @Test
    fun aBlankSideIsNotACard() {
        assertFalse(NcieDeck.isCard(card("", "V = IR")))
        assertFalse(NcieDeck.isCard(card("What is Ohm's law", "")))
        assertFalse(NcieDeck.isCard(card("   ", "V = IR")))
        assertFalse(NcieDeck.isCard(NcieDeck.SEP + "V = IR"))
    }

    @Test
    fun theCountIgnoresBlankAndBrokenLines() {
        val lines = listOf(
            card("a", "1"),
            "",
            "   ",
            "no separator here",
            card("b", "2"),
            card("", "3")
        )
        assertEquals(2, NcieDeck.count(lines))
    }

    @Test
    fun anEmptyDeckCountsZero() {
        assertEquals(0, NcieDeck.count(emptyList()))
    }
}
