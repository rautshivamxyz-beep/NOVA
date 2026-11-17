package org.nova.ncie.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceFingerprintTest {
    @Test fun fingerprintsAreStableAcrossWhitespace() {
        val a = EvidenceFingerprint.fromSources(listOf("photosynthesis  converts light"))
        val b = EvidenceFingerprint.fromSources(listOf("photosynthesis\nconverts   light"))
        assertEquals(a, b)
        assertTrue(EvidenceFingerprint.isValid(a.single()))
    }

    @Test fun duplicateSourcesCollapseAndEvidenceIsBounded() {
        val sources = listOf("same", "same", "two", "three", "four", "five", "six")
        val ids = EvidenceFingerprint.fromSources(sources)
        assertEquals(4, ids.size)
        assertEquals(4, ids.distinct().size)
    }

    @Test fun blankSourcesAreIgnored() {
        assertTrue(EvidenceFingerprint.fromSources(listOf("", "  ")).isEmpty())
    }
}
