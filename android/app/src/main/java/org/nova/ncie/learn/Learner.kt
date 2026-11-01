package org.nova.ncie.learn

import org.nova.ncie.model.NovaResponse
import org.nova.ncie.model.Route

/**
 * ⑤ LEARN — make the next answer faster or better.
 *
 * v0.1: an exact-match predictive cache plus route statistics
 * (how often each route wins, per intent).
 *
 * v0.7.0: [PersistentLearner] adds the disk half — the same cache, but
 * serialized through a [LearningStore] so a restart serves repeats
 * without paying for them again (the Smart Skip the app's summary_cache
 * does for study questions, generalized kernel-side).
 *
 * v0.8.0: [recallFuzzy] — the same question, asked differently. A hit
 * requires the cached question's significant tokens to cover every
 * significant token of the asking one (question words never count), so
 * the served answer is always for a same-or-more-specific question.
 *
 * v0.8.1: [clear] — the world changed, drop what was learned; and
 * [recallFuzzy] now matches curated synonym classes (study/learn,
 * exam/test, ...) as the same topic token.
 *
 * v0.9.0: the graded memory system. [record] (the fact overload),
 * [forget], [learnedFacts] and [demoteStale] turn the flat answer
 * cache into quality-gated knowledge: facts are born from verified
 * answers, confirm toward graduation, and are promoted into the
 * KnowledgeStore by [Distiller]. All four default to no-ops so custom
 * learners keep compiling.
 *
 * v0.9.3: [knowsTopic] — a side-effect-free probe for adaptive planning.
 *
 * v0.9.4: [noteFailure] and [weakTopics] — the weak-topic signal the
 * adaptive planner's Struggle Rule and the Memory screen consume.
 */
/**
 * v0.9.2 (opt #10): the Learner's counters, structured - the raw
 * material for adaptive planning and dashboards. What [Learner.stats]
 * renders as a string, in a form code can read.
 */
class LearningStats(
    val cacheEntries: Int,
    val factCount: Int,
    val hits: Int,
    val misses: Int,
    val fuzzyHits: Int,
    val routeCounts: Map<Route, Int>,
)

/** v0.9.4 (stage 2): a topic the memory keeps FAILING on. [failures]
 * counts answers the quality gate rejected; the topic string is the
 * learner's canonical topic key (its first significant tokens, sorted). */
class WeakTopic(val topic: String, val failures: Int)

interface Learner {
    /** Return a cached answer for this exact request, if any. */
    fun recall(text: String): NovaResponse?
    /** Return (matched question, cached answer) when a covering entry
     *  exists for a differently-worded ask, or null. Hosts that do not
     *  implement it simply never fuzzy-hit. */
    fun recallFuzzy(query: String): Pair<String, NovaResponse>? = null
    /** v0.9.3 (#10): a side-effect-free probe for the adaptive planner —
     *  true when the memory HALF-knows the question: an exact recall
     *  would miss, but a fuzzy recall would hit. The planner spends one
     *  tier more budget on such turns (see AdaptiveKernel). Unlike
     *  [recallFuzzy] this never bumps counters, so it is safe to call on
     *  every plan. Default: false — learners without fuzzy recall simply
     *  never report it. */
    fun knowsTopic(text: String): Boolean = false

    /** v0.9.4 (stage 2): record a REJECTED answer — the failure signal
     *  [weakTopics] aggregates, and the input to the adaptive planner's
     *  Struggle Rule. Default: no-op, so custom learners keep compiling. */
    fun noteFailure(question: String) {}

    /** v0.9.4 (stage 2): the topics with rejected answers, most-failed
     *  first. Default: empty — learners that track nothing report nothing. */
    fun weakTopics(): List<WeakTopic> = emptyList()

    /** Record a completed exchange. */
    fun record(text: String, response: NovaResponse)
    /** v0.8.1: the world the answers were learned from changed — drop
     *  everything learned. Hosts call this when their knowledge base is
     *  invalidated (the NOVA app: notes were edited or deleted). Default
     *  is a no-op so custom learners keep compiling. */
    fun clear() {}
    /** Human-readable stats for the Learn dashboard. */
    fun stats(): String
    /** v0.9.2 (opt #10): the same numbers [stats] renders, structured
     *  for code. Default: null - learners that keep no counters simply
     *  report nothing. */
    fun statsData(): LearningStats? = null
    /** v0.9.0: record a graded fact into the memory system — the
     *  quality-gated half of learning. Implementations merge by question
     *  (see [PersistentLearner.record]). Default no-op. */
    fun record(fact: LearnedFact) {}
    /** v0.9.0: targeted removal — ONE question leaves memory and disk,
     *  everything else stays (unlike [clear], which wipes everything).
     *  Returns true when something was actually removed. Default no-op. */
    fun forget(questionKey: String): Boolean = false
    /** v0.9.0: the graded memory, for the [Distiller] and the memory
     *  dashboard. Default: an empty memory. */
    fun learnedFacts(): List<LearnedFact> = emptyList()
    /** v0.9.0: aging — entries whose lastConfirmed is older than
     *  [maxAgeDays] days get their score halved, decaying toward death
     *  instead of pretending to be as good as the day they were learned.
     *  Returns how many entries were demoted. Default no-op. */
    fun demoteStale(maxAgeDays: Int = 180): Int = 0
}

class SimpleLearner : Learner {
    private val cache = HashMap<String, NovaResponse>()
    private val routeCounts = HashMap<Route, Int>()
    private var hits = 0
    private var misses = 0

    override fun recall(text: String): NovaResponse? {
        val r = cache[text.trim()]
        if (r != null) hits++ else misses++
        return r
    }

    override fun record(text: String, response: NovaResponse) {
        cache[text.trim()] = response
        routeCounts[response.plan.route] = (routeCounts[response.plan.route] ?: 0) + 1
    }

    /** v0.8.1: factory-fresh again — entries and counters. */
    override fun clear() {
        cache.clear()
        routeCounts.clear()
        hits = 0
        misses = 0
    }

    override fun stats(): String {
        val total = hits + misses
        val hitRate = if (total == 0) 0.0 else hits.toDouble() * 100 / total
        val routes = routeCounts.entries.joinToString(", ") { "${it.key}=${it.value}" }
        return "cache ${cache.size} entries, hit-rate ${"%.0f".format(hitRate)}% | routes: $routes"
    }

    override fun statsData(): LearningStats? = LearningStats(
        cacheEntries = cache.size,
        factCount = 0,
        hits = hits,
        misses = misses,
        fuzzyHits = 0,
        routeCounts = routeCounts.toMap(),
    )
}
