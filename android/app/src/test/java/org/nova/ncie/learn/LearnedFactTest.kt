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
    fun evidenceFingerprintsRoundTripAndLegacyNineFieldLinesStillLoad() {
        val source = "Photosynthesis\nconverts light energy into chemical energy."
        val f = LearnedFact.fromChat("explain photosynthesis", "Photosynthesis converts light energy into chemical energy in plants.", 0.9, listOf(source))
        val back = LearnedFact.parse(f.serialize())
        assertEquals(f.evidenceFingerprints, back?.evidenceFingerprints)
        val legacy = "who wrote hamlet\\tShakespeare\\tshakespeare\\tCHAT\\t1\\t1\\t0.9\\t1\\tlearned from chat, quality 0.90"
        assertEquals(emptyList<String>(), LearnedFact.parse(legacy)?.evidenceFingerprints)
    }

    @Test
    fun malformedLinesAreSkippedNotThrown() {
        assertNull(LearnedFact.parse("garbage\tnot\ta\tfact"))
        assertNull(LearnedFact.parse(""))
    }
}
