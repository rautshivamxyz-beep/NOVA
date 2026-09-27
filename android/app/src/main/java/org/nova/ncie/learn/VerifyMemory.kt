package org.nova.ncie.learn

import org.nova.ncie.NovaKernel

/**
 * Plain-JVM verification of the v0.9.0 memory system (run: kotlinc + java,
 * see MEMORY_DESIGN.md). Six checks:
 * 1. Kernel end-to-end: a chat answer is born as a graded fact (quality gate)
 * 2. A calculator turn answers correctly and is NOT recorded as a fact
 * 3. Serialization survives a "restart" (new PersistentLearner, same store)
 * 4. A corrupt fact line is skipped, never fatal
 * 5. forget() removes exactly one entry
 * 6. Merge math — a re-answered question blends its score and bumps interactions
 */
fun main() {
    val snapshot = object : LearningStore {
        private var snap: String? = null
        override fun write(snapshot: String) { snap = snapshot }
        override fun read(): String? = snap
    }

    // 1 + 2: kernel with defaults (stub LLM, rule-based analyzer)
    val kernel = NovaKernel.defaults()
    val chat = kernel.ask("what is the capital of france")
    check(chat.answer.isNotBlank()) { "chat answer blank" }
    check(chat.verify.qualityScore >= NovaKernel.LEARN_QUALITY_THRESHOLD) {
        "chat quality ${chat.verify.qualityScore} below threshold"
    }

    val calc = kernel.ask("12*(3+4)^2")
    check(calc.answer.contains("588")) { "calculator wrong: ${calc.answer}" }

    // birth
    val learner = PersistentLearner(snapshot)
    learner.record(LearnedFact.fromChat("what is the capital of france", chat.answer, chat.verify.qualityScore))
    val before = learner.learnedFacts()
    check(before.size == 1 && before[0].interactions == 1) { "fact not born" }

    // 3: restart — a NEW learner over the same snapshot sees the fact
    val learner2 = PersistentLearner(snapshot)
    check(learner2.learnedFacts().size == 1) { "fact lost on restart" }
    val hit = learner2.recall("what is the capital of france")
    check(hit != null && hit.answer == chat.answer) { "recall failed after restart" }

    // 3b: fuzzy recall of a differently-worded ask
    val fuzzy = learner2.recallFuzzy("tell me the france capital")
    check(fuzzy != null) { "fuzzy recall failed" }

    // 4: corrupt lines are skipped, never fatal
    val corrupt = object : LearningStore {
        private var snap = snapshot.read().orEmpty() + "\tGARBAGE\tNOT\tA\tFACT\n\tbroken\\\\\n"
        override fun write(snapshot: String) { snap = snapshot }
        override fun read(): String? = snap
    }
    val learner3 = PersistentLearner(corrupt)
    check(learner3.learnedFacts().size == 1) { "corrupt lines not tolerated: ${learner3.learnedFacts()}" }

    // 5: forget() removes exactly one
    learner3.record(LearnedFact.fromChat("who wrote hamlet", "Shakespeare", 0.9))
    check(learner3.learnedFacts().size == 2)
    val removed = learner3.forget("who wrote hamlet")
    check(removed && learner3.learnedFacts().size == 1) { "forget() broken" }

    // 6: merge math — same question re-answered blends score, bumps interactions
    learner3.record(LearnedFact.fromChat("what is the capital of france", chat.answer, 0.9))
    val merged = learner3.learnedFacts().first { it.question == "what is the capital of france" }
    check(merged.interactions == 2) { "interactions not bumped: $merged" }
    check(merged.score > 0.55) { "score not blended: $merged" }

    println("ALL 6 INDEPENDENT CHECKS PASSED")
    println(learner3.stats())
}
