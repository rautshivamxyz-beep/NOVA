package org.nova.ncie.verify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidencePipelineTest {
    @Test fun relevantFetchedSourcePasses() {
        assertTrue(EvidencePipeline.acceptsFetchedSource(
            "photosynthesis light energy plants",
            "Photosynthesis captures light energy and supports plant growth."
        ))
    }

    @Test fun unrelatedFetchedSourceFails() {
        assertFalse(EvidencePipeline.acceptsFetchedSource(
            "photosynthesis light energy plants",
            "The Taj Mahal is a white marble monument in Agra."
        ))
    }

    @Test fun wholeWordMatchingDoesNotAcceptSubstrings() {
        assertFalse(EvidencePipeline.acceptsFetchedSource(
            "art astronomy geology biology",
            "The startup began in a remote area with a large garden."
        ))
    }

    @Test fun wholeWordHitCountIsHonoured() {
        // v9.33.155: the 2-whole-word rule is a parameter, not a magic number
        // buried in the caller - raising it must actually refuse a page that
        // covers only two of the query's words.
        val query = "quantum entanglement experiment"
        val source = "Quantum entanglement is mentioned once in this long page " +
            "about mountains, rivers, cities, food, music, films and travel."
        assertFalse(EvidencePipeline.acceptsFetchedSource(
            query, source, minCoverage = 1.0, minWholeWordHits = 99
        ))
        assertTrue(EvidencePipeline.acceptsFetchedSource(query, source))
    }

    @Test fun blankResearchInputsFailClosed() {
        assertFalse(EvidencePipeline.acceptsFetchedSource(" ", "some source text"))
        assertFalse(EvidencePipeline.acceptsFetchedSource("photosynthesis", " "))
    }
}
