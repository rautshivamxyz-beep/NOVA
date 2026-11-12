package org.nova.ncie.evaluate

import org.nova.ncie.learn.LearningEvidencePolicy
import org.nova.ncie.knowledge.KnowledgeStore
import org.nova.ncie.learn.GapStore
import org.nova.ncie.model.VerifyResult
import org.nova.ncie.skill.SkillStore

/**
 * Small deterministic regression suite for NOVA's on-device learning stack.
 *
 * These checks exercise real parsers, retrieval and evidence policy without
 * loading the language model, network, embeddings, or Android services.
 */
object RegressionLab {
    fun run(): List<Pair<String, Boolean>> = listOf(
        check("skill parse, trigger and render") {
            val skills = SkillStore.parse(
                "[skill]\nname=Quiz me\ntrigger=(?i)\\bquiz me\\b\n" +
                    "instruction=Ask questions about {q}\n"
            )
            skills.size == 1 &&
                skills[0].trigger.containsMatchIn("please QUIZ ME") &&
                skills[0].render("algebra").contains("algebra")
        },
        check("malformed skill is isolated") {
            val skills = SkillStore.parse(
                "[skill]\nname=Broken\ntrigger=[\ninstruction=bad rule\n" +
                    "[skill]\nname=Valid\ntrigger=(?i)hello\ninstruction=Use {q} carefully\n"
            )
            skills.size == 1 && skills[0].name == "Valid"
        },
        check("gap store escapes and restores control characters") {
            val question = "first line\tsecond line\nthird line"
            val store = GapStore.parse(GapStore.parse("").also { it.note(question, 7L) }.serialize())
            store.top(1).singleOrNull()?.let { it.question == question && it.count == 1 } == true
        },
        check("gap ranking is deterministic") {
            val store = GapStore.parse("")
            store.note("alpha gap", 1L)
            store.note("beta gap", 2L)
            store.note("beta gap", 3L)
            store.top(2).map { it.question } == listOf("beta gap", "alpha gap")
        },
        check("source-free answers are not durable facts") {
            !LearningEvidencePolicy.mayPersistFact(
                emptyList(), VerifyResult(true, 0.6, "no ground truth")
            )
        },
        check("source-grounded passing answers may be retained") {
            LearningEvidencePolicy.mayPersistFact(
                listOf("Photosynthesis converts light into glucose"),
                VerifyResult(true, 0.95, "grounded")
            )
        },
        check("failed verification blocks durable facts") {
            !LearningEvidencePolicy.mayPersistFact(
                listOf("The Taj Mahal is in Agra"),
                VerifyResult(false, 0.95, "failed")
            )
        },
        check("knowledge retrieval finds matching source material") {
            val store = KnowledgeStore()
            store.addDoc("biology", "Photosynthesis converts light energy into chemical energy in plants.")
            store.search("photosynthesis light energy", 2).any { it.doc == "biology" }
        },
    )

    private inline fun check(name: String, test: () -> Boolean): Pair<String, Boolean> =
        name to try { test() } catch (_: Exception) { false }
}
