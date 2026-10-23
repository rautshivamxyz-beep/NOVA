package org.nova.ncie.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nova.ncie.knowledge.KnowledgeStore
import org.nova.ncie.model.NovaResponse

/**
 * v9.33.56: the memory system's graduation ceremony gets its first unit
 * test.
 *
 * [Distiller] decides which learned facts become permanent knowledge, so a
 * silent regression means the memory either never promotes anything or
 * promotes junk. The two documented rules are pinned here: the graduation
 * bar (score path and the v0.9.2 probation streak path) and the document
 * name (the v0.9.2 opt#8 fix - two long questions sharing their first 64
 * characters used to promote into the SAME name, and addDoc REPLACES
 * same-named docs, silently destroying the first question's knowledge).
 *
 * Pure JVM, no Android imports - run with `gradle testDebugUnitTest`
 * (CI runs it on every pull request).
 */
class DistillerTest {

    private val longAnswer = "A".repeat(60) // > MIN_DOC_CHARS (40)

    private fun fact(score: Double, interactions: Int, answer: String = longAnswer) =
        LearnedFact(
            question = "what is the capital of france",
            answer = answer,
            topicTokens = setOf("capital", "france"),
            source = LearnedFact.SOURCE_CHAT,
            learnedAtMillis = 0L,
            lastConfirmedMillis = 0L,
            score = score,
            interactions = interactions,
            provenance = "test",
        )

    private fun distiller(learner: Learner = SimpleLearner()) = Distiller(learner, KnowledgeStore())

    @Test
    fun theScoreBarNeedsTwoConfirmations() {
        assertTrue(distiller().graduates(fact(0.8, 2)))
        assertTrue(!distiller().graduates(fact(0.8, 1)))
    }

    @Test
    fun theProbationStreakGraduatesASteadyFact() {
        // v0.9.2 opt#1: chat answers are born at 0.6 and can never blend
        // past 0.7, so the streak path is the only way they graduate
        assertTrue(distiller().graduates(fact(0.6, 3)))
        assertTrue(!distiller().graduates(fact(0.5, 3)))
        assertTrue(!distiller().graduates(fact(0.65, 2)))
    }

    @Test
    fun aTooShortAnswerNeverGraduates() {
        // KnowledgeStore.chunkText drops pieces of <= 40 chars
        assertTrue(!distiller().graduates(fact(0.9, 5, answer = "short")))
    }

    @Test
    fun aShortQuestionKeepsItsOwnName() {
        assertEquals("who wrote hamlet",
            distiller().docNameOf(fact(0.8, 2).copy(question = "who wrote hamlet")))
    }

    @Test
    fun twoLongQuestionsNeverShareADocumentName() {
        val a = "x".repeat(70)
        val b = "x".repeat(69) + "y" // same first 64 characters
        val na = distiller().docNameOf(fact(0.8, 2).copy(question = a))
        val nb = distiller().docNameOf(fact(0.8, 2).copy(question = b))
        assertEquals(Distiller.MAX_NAME_CHARS, na.length)
        assertNotEquals(na, nb)
    }

    @Test
    fun aDistillPassPromotesForgetsAndSkips() {
        val grad = fact(0.8, 2).copy(question = "capital of france")
        val prob = fact(0.4, 1).copy(question = "an unproven guess")
        val learner = FakeLearner(listOf(grad, prob))
        val store = KnowledgeStore()
        val report = Distiller(learner, store).distill()
        assertEquals(1, report.promoted)
        assertEquals(1, report.skipped)
        assertEquals(listOf("capital of france"), learner.forgotten)
        assertEquals(1, store.docs().size)
        assertEquals("capital of france", store.docs().first().first)
    }

    private class FakeLearner(private val facts: List<LearnedFact>) : Learner {
        val forgotten = mutableListOf<String>()
        override fun recall(text: String): NovaResponse? = null
        override fun record(text: String, response: NovaResponse) {}
        override fun clear() {}
        override fun stats(): String = ""
        override fun learnedFacts(): List<LearnedFact> = facts
        override fun forget(questionKey: String): Boolean {
            forgotten.add(questionKey); return true
        }
    }
}
