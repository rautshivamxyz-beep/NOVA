package org.nova.ncie.learn

import org.nova.ncie.knowledge.KnowledgeStore

/** One consolidation pass's tally, for the host's logs. */
class DistillReport(
    /** Facts promoted into the KnowledgeStore this pass. */
    val promoted: Int,
    /** Facts kept in the graded memory — still on probation. */
    val skipped: Int,
    /** Facts whose score was halved for being stale (maxAgeDays). */
    val demotedStale: Int,
) {
    override fun toString(): String =
        "distill: $promoted promoted, $skipped kept on probation, $demotedStale demoted (stale)"
}

/**
 * v0.9.0 — ⑤ LEARN's consolidation pass: the graded memory's
 * graduation ceremony.
 *
 *     birth → probation → graduation → death
 *
 * A fact is born when the kernel records a verified answer, and sits on
 * probation in the learner. [distill] promotes the ones that earned it —
 * score ≥ [GRADUATION_SCORE] AND at least [GRADUATION_INTERACTIONS]
 * confirmations — into the [KnowledgeStore] through its public
 * [KnowledgeStore.addDoc] API (the same door the app's KnowledgeAdapter
 * uses for notes): the question becomes the document name, the answer
 * the content, and the store's rarity-weighted search serves it to
 * every later answer as offline RAG. Promoted facts are then
 * [Learner.forget]-ten from the graded memory — they have graduated
 * from short-term cache to long-term knowledge, and leaving them in
 * both would double-serve.
 *
 * Stale facts (last confirmed more than [Learner.demoteStale]'s
 * maxAgeDays ago) get their score halved first, so time alone never
 * graduates anything — decay pushes the other way.
 *
 * Pure Kotlin/JVM, no Android, no I/O: the host decides when to run it
 * (the NOVA app: nightly, or on chat close).
 */
class Distiller(
    private val learner: Learner,
    private val knowledge: KnowledgeStore,
) {

    fun distill(): DistillReport {
        val demotedStale = learner.demoteStale()
        var promoted = 0
        var skipped = 0
        for (fact in learner.learnedFacts()) {
            if (!graduates(fact)) {
                skipped++
                continue
            }
            knowledge.addDoc(docNameOf(fact), docTextOf(fact))
            learner.forget(fact.question)
            promoted++
        }
        return DistillReport(promoted, skipped, demotedStale)
    }

    /** The graduation bar: good enough, confirmed more than once, and
     *  long enough to survive the store's chunker.
     *
     *  v0.9.2 (opt #1) adds the probation path: ordinary chat answers are
     *  born at the flat 0.6 quality score and the 0.6*old + 0.4*new blend
     *  can never lift them past 0.7, so the score-only bar froze
     *  graduation forever - the Distiller could never promote anything.
     *  A fact confirmed [GRADUATION_STREAK] times while still holding at
     *  least [MIN_STREAK_SCORE] has served its probation: it graduates
     *  on the streak. Stale-demoted facts (score halved below 0.6) and
     *  born-bad facts stay out. */
    fun graduates(fact: LearnedFact): Boolean {
        if (fact.answer.length <= MIN_DOC_CHARS) return false
        if (fact.score >= GRADUATION_SCORE &&
            fact.interactions >= GRADUATION_INTERACTIONS
        ) return true
        return fact.interactions >= GRADUATION_STREAK && fact.score >= MIN_STREAK_SCORE
    }

    /** The promoted document's name: the question itself (capped), so
     *  the store's x2 name bonus rewards a query that re-asks it.
     *
     *  v0.9.2 (opt #8): the cap ends in a short hash of the FULL
     *  question instead of an ellipsis - two questions sharing their
     *  first 64 characters used to promote into the SAME document name,
     *  and KnowledgeStore.addDoc REPLACES same-named docs, silently
     *  destroying the first question's knowledge. Public so hosts that
     *  persist promotions themselves (the NOVA app) build the exact
     *  same name instead of re-implementing the cap. */
    fun docNameOf(fact: LearnedFact): String {
        val q = fact.question.trim()
        if (q.length <= MAX_NAME_CHARS) return q
        val hash = Integer.toHexString(q.hashCode()).padStart(8, '0').takeLast(8)
        return q.substring(0, MAX_NAME_CHARS - 9) + "…" + hash
    }

    /** The promoted document's content: the answer, headed by its own
     *  question — the chunker then keeps them together, so a retrieval
     *  hit returns the answer in context. */
    private fun docTextOf(fact: LearnedFact): String =
        fact.question.trim() + "\n\n" + fact.answer.trim()

    companion object {
        /** Score a fact needs to graduate (blend-decayed over time). */
        const val GRADUATION_SCORE = 0.7
        /** Confirmations a fact needs — born with 1, graduates at 2+. */
        const val GRADUATION_INTERACTIONS = 2
        /** KnowledgeStore.chunkText silently drops pieces of ≤ 40 chars —
         *  a shorter answer would promote into an empty document. */
        const val MIN_DOC_CHARS = 40
        /** Document-name cap, so docs() stays readable on a screen. */
        const val MAX_NAME_CHARS = 64
        /** v0.9.2 (opt #1): confirmations that graduate a steady-scoring
         *  fact on probation alone (see [graduates]). */
        const val GRADUATION_STREAK = 3
        /** v0.9.2 (opt #1): the floor a streak-graduating fact must hold. */
        const val MIN_STREAK_SCORE = 0.6
    }
}

/**
 * A plain-JVM demo/test of the whole memory lifecycle — no Android, no
 * dependencies. Three facts: one low-quality, one confirmed twice, one
 * stale. One distill pass, and the report printed.
 */
fun main() {
    val store = object : LearningStore {
        private var snapshot: String? = null
        override fun write(snapshot: String) { this.snapshot = snapshot }
        override fun read(): String? = snapshot
    }
    val learner = PersistentLearner(store)
    val knowledge = KnowledgeStore()

    // 1. a low-quality fact — never reaches the bar, stays on probation
    learner.record(LearnedFact.fromChat(
        "who wrote hamlet",
        "Shakespeare, around 1600 — but this thin answer scored poorly.",
        0.35,
    ))

    // 2. a good fact, confirmed twice — born at 0.75, re-answered at 0.9:
    //    score blends to 0.6*0.75 + 0.4*0.9 = 0.81, interactions reach 2
    learner.record(LearnedFact.fromChat(
        "what is the capital of france",
        "The capital of France is Paris, its largest city and the seat of government.",
        0.75,
    ))
    learner.record(LearnedFact.fromChat(
        "what is the capital of france",
        "The capital of France is Paris, its largest city and the seat of government.",
        0.9,
    ))

    // 3. a stale fact — good once (0.9, three confirmations) but not
    //    confirmed for 200 days: distill halves it to 0.45
    val day = 24L * 60 * 60 * 1000
    learner.record(LearnedFact(
        question = "when was the battle of panipat fought",
        answer = "The First Battle of Panipat was fought in 1526, when Babur defeated Ibrahim Lodi.",
        topicTokens = setOf("battle", "panipat", "fought"),
        source = LearnedFact.SOURCE_CHAT,
        learnedAtMillis = System.currentTimeMillis() - 365 * day,
        lastConfirmedMillis = System.currentTimeMillis() - 200 * day,
        score = 0.9,
        interactions = 3,
        provenance = "learned from chat, quality 0.90",
    ))

    println("== memory before distill ==")
    println(learner.stats())
    for (f in learner.learnedFacts()) println("  $f")

    val report = Distiller(learner, knowledge).distill()

    println("== distill ==")
    println(report)

    println("== knowledge base now holds ==")
    for ((name, chunks) in knowledge.docs()) println("  \"$name\" — $chunks chunk(s)")
    val hit = knowledge.search("capital of france", maxResults = 1)
    println("  search(\"capital of france\") → " + (hit.firstOrNull()?.text ?: "nothing"))

    println("== memory after distill ==")
    println(learner.stats())
    for (f in learner.learnedFacts()) println("  $f")
}
