package org.nova.ncie.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.55: the fetched-knowledge gate gets its first unit test.
 *
 * [Coverage.ratio] is the bar that decides whether a fetched article or a
 * wiki background match is actually ABOUT the question (the host accepts
 * at >= 0.3) or junk that must be dropped. A silent regression here either
 * lets garbage into the knowledge base or throws away a good answer, so
 * the whole-word term logic is pinned directly.
 *
 * Pure JVM, no Android imports - run with `gradle testDebugUnitTest`
 * (CI runs it on every pull request).
 */
class CoverageTest {

    @Test
    fun aFullMatchScoresOne() {
        assertEquals(1.0, Coverage.ratio("modi", "Narendra Modi was born in Vadodara"), 1e-9)
    }

    @Test
    fun stopwordsDoNotCountAgainstCoverage() {
        // "who"/"is" are stopwords, so only physics+wallah are counted
        assertEquals(1.0,
            Coverage.ratio("who is physics wallah", "Physics Wallah is an Indian educator"), 1e-9)
    }

    @Test
    fun anUnrelatedArticleScoresZero() {
        assertEquals(0.0,
            Coverage.ratio("photosynthesis", "The Battle of Panipat was fought in 1526"), 1e-9)
    }

    @Test
    fun aPartialMatchScoresTheFraction() {
        assertEquals(0.5, Coverage.ratio("modi vadodara", "Modi was born in Gujarat"), 1e-9)
    }

    @Test
    fun aQueryWithNoSignificantTermsScoresZero() {
        assertEquals(0.0, Coverage.ratio("who is the", "anything at all"), 1e-9)
    }

    @Test
    fun theAcceptBarIsThirtyPercent() {
        // the host accepts at >= 0.3 - one matched term in four is junk
        assertTrue(Coverage.ratio("alpha beta gamma delta", "alpha only here") < 0.3)
    }
}
