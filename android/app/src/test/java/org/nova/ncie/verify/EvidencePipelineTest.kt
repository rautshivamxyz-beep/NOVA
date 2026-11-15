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

    @Test fun blankResearchInputsFailClosed() {
        assertFalse(EvidencePipeline.acceptsFetchedSource(" ", "some source text"))
        assertFalse(EvidencePipeline.acceptsFetchedSource("photosynthesis", " "))
    }
}
