package org.nova.ncie.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v9.33.14: the graded-memory line format survives a round trip. */
class LearnedFactTest {

    @Test
    fun roundTripsThroughSerializeAndParse() {
        val f = LearnedFact.fromChat("who wrote hamlet", "Shakespeare", 0.9)
        val back = LearnedFact.parse(f.serialize())
        assertEquals(f.question, back?.question)
        assertEquals(f.answer, back?.answer)
        assertEquals(f.score, back!!.score, 1e-9)
    }

    @Test
    fun malformedLinesAreSkippedNotThrown() {
        assertNull(LearnedFact.parse("garbage\tnot\ta\tfact"))
        assertNull(LearnedFact.parse(""))
    }
}
