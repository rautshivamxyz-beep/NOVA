package org.nova.ncie.learn

import org.nova.ncie.model.NovaResponse
import org.nova.ncie.model.Plan
import org.nova.ncie.model.Route
import org.nova.ncie.model.VerifyResult

/**
 * v0.7.0: the seam the host's disk plugs into. The kernel stays
 * I/O-free — it hands [write] a snapshot string and takes back whatever
 * [read] returns. The NOVA app implements this over a file in filesDir
 * (ChatStore-style, debounced); the demo uses an in-memory string.
 */
interface LearningStore {
    /** Persist the full snapshot; called after every record(). */
    fun write(snapshot: String)
    /** Return the snapshot a previous [write] stored, or null. */
    fun read(): String?
}

/**
 * A learner that survives restarts. The cache is an access-order LRU —
 * the eldest entry drops when it passes [maxEntries] — serialized one
 * record per line:
 *
 *     <escaped request>\t<escaped answer>
 *
 * where backslash, tab, CR and LF are escaped as \\, \t, \r and \n.
 * Malformed lines are skipped, never crashed on: a half-written or
 * corrupted cache is a performance loss, not a behavior one. Recalled
 * answers come back as Route.CACHE responses — exactly what a live
 * cache hit looks like to the kernel. Only the answer text persists;
 * plans, traces and verdicts are per-session telemetry and are rebuilt
 * as the canonical "restored from cache" shape.
 *
 * v0.9.0: the graded memory rides in the SAME snapshot, one
 * [LearnedFact] per line after a leading tab:
 *
 *     \t<escaped fact line>            (see [LearnedFact.serialize])
 *
 * A cache line can never start with a tab (escaped keys contain no
 * raw tabs), so the two kinds never collide and old snapshots — pure
 * cache lines — load unchanged. The fact map is capped by the same
 * [maxEntries] LRU budget as the answer cache.
 *
 * (Moved from Learner.kt when the memory system outgrew one file;
 * everything below v0.9.0 is byte-for-byte the code that lived there.)
 */
class PersistentLearner(
    private val store: LearningStore,
    private val maxEntries: Int = 200,
) : Learner {
    private val cache = object : LinkedHashMap<String, NovaResponse>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NovaResponse>?): Boolean =
            size > maxEntries
    }
    /** v0.9.0: the graded memory — keyed by trimmed question, same
     *  access-order LRU budget as the answer cache. */
    private val facts = LinkedHashMap<String, LearnedFact>(16, 0.75f, true)
    private val routeCounts = HashMap<Route, Int>()
    private var hits = 0
    private var misses = 0
    private var fuzzyHits = 0

    init {
        restore()
    }

    override fun recall(text: String): NovaResponse? {
        val r = cache[text.trim()]
        if (r != null) hits++ else misses++
        return r
    }

    /**
     * v0.8.0: the same question, asked differently. A hit requires the
     * cached question's significant tokens to cover EVERY significant
     * token of the query — question words ("what", "explain", "the")
     * never count — and the tightest covering entry (fewest extra
     * tokens) wins. The direction is deliberate: a cached question
     * that covers at least as much as the asking one has a
     * same-or-more-specific answer, which is always safe to serve.
     * "explain federalism in india" therefore stays a miss until a
     * question that actually covers india was answered. A fuzzy hit
     * refreshes the LRU like an exact one and is counted separately
     * in [stats].
     *
     * v0.8.1: curated synonym classes count as the same token, so
     * "the best way to study for the exam" covers "best way to learn
     * for the test" — the covering guarantee holds per class, and a
     * class never spans two topics (small, deliberate groups only).
     */
    override fun recallFuzzy(query: String): Pair<String, NovaResponse>? {
        val q = canonTokens(query)
        if (q.isEmpty()) return null
        var bestKey: String? = null
        var bestExtras = Int.MAX_VALUE
        for (k in cache.keys) {
            val e = canonTokens(k)
            if (e.size < q.size || !e.containsAll(q)) continue
            val extras = e.size - q.size
            if (extras < bestExtras) { bestExtras = extras; bestKey = k }
        }
        val key = bestKey ?: return null
        val r = cache[key] ?: return null   // the get() refreshes the LRU
        fuzzyHits++
        return key to r
    }

    override fun record(text: String, response: NovaResponse) {
        val key = text.trim()
        if (key.isEmpty() || response.plan.route == Route.CACHE) return
        cache[key] = response
        routeCounts[response.plan.route] = (routeCounts[response.plan.route] ?: 0) + 1
        persist()
    }

    /**
     * v0.9.0: record a graded fact. A repeat of a known question MERGES
     * instead of duplicating: the answer updates, interactions bump, the
     * score blends 0.6·old + 0.4·newQuality (so one bad re-answer cannot
     * kill a good fact, and one good one cannot instantly graduate a
     * bad one), and lastConfirmed moves to now. A new question is born
     * with the fact's own score and interaction count. The fact's answer
     * also refreshes the plain cache, so [recall]/[recallFuzzy] keep
     * serving the newest answer.
     */
    override fun record(fact: LearnedFact) {
        val key = fact.question.trim()
        if (key.isEmpty() || fact.answer.isEmpty()) return
        val merged = facts[key]?.let { old ->
            old.copy(
                answer = fact.answer,
                topicTokens = fact.topicTokens,
                score = 0.6 * old.score + 0.4 * fact.score,
                interactions = old.interactions + 1,
                lastConfirmedMillis = fact.lastConfirmedMillis,
                provenance = fact.provenance,
            )
        } ?: fact.copy(question = key)
        facts[key] = merged
        while (facts.size > maxEntries) facts.remove(facts.keys.first())
        cache[key] = factResponse(merged)
        persist()
    }

    /**
     * v0.9.0: targeted removal — ONE question leaves memory AND disk,
     * everything else stays (the surgical opposite of [clear], which
     * wipes the world). Returns true when something was removed, so
     * hosts can report a forgotten-vs-unknown distinction.
     */
    override fun forget(questionKey: String): Boolean {
        val key = questionKey.trim()
        val fromFacts = facts.remove(key) != null
        val fromCache = cache.remove(key) != null
        if (!fromFacts && !fromCache) return false
        persist()
        return true
    }

    /** v0.9.0: the graded memory, in learn order (eldest first). */
    override fun learnedFacts(): List<LearnedFact> = facts.values.toList()

    /**
     * v0.9.0: aging — entries whose lastConfirmed is older than
     * [maxAgeDays] days get their score multiplied by 0.5, so knowledge
     * nobody has re-asked for decays toward death instead of staying as
     * credible as the day it was learned. A demotion can be undone the
     * usual way: re-answering the question re-confirms the fact and
     * blends its score back up. Returns how many entries were demoted.
     */
    override fun demoteStale(maxAgeDays: Int): Int {
        val cutoff = System.currentTimeMillis() - maxAgeDays * DAY_MILLIS
        var demoted = 0
        for ((k, f) in facts) {
            if (f.lastConfirmedMillis < cutoff) {
                facts[k] = f.copy(score = f.score * 0.5)
                demoted++
            }
        }
        if (demoted > 0) persist()
        return demoted
    }

    /** v0.8.1: wipe the learned cache — memory AND disk. Counters reset
     *  too: a cleared learner reports a clean slate, exactly like a
     *  fresh one. The empty snapshot is written at once, so a restart
     *  finds nothing to load. v0.9.0: the graded memory goes with it —
     *  clear() is the whole-world reset, forget() is the scalpel. */
    override fun clear() {
        cache.clear()
        facts.clear()
        routeCounts.clear()
        hits = 0
        misses = 0
        fuzzyHits = 0
        store.write("")
    }

    override fun stats(): String {
        val total = hits + misses
        val hitRate = if (total == 0) 0.0 else hits.toDouble() * 100 / total
        val routes = routeCounts.entries.joinToString(", ") { "${it.key}=${it.value}" }
        val fz = if (fuzzyHits > 0) " (+$fuzzyHits fuzzy)" else ""
        return "cache ${cache.size} entries, hit-rate ${"%.0f".format(hitRate)}%$fz | " +
            "memory ${facts.size} facts | routes: $routes"
    }

    /** Question words never count for matching — only the topic does. */
    private val queryStop = setOf(
        "a", "an", "and", "any", "about", "are", "can", "define", "describe",
        "did", "do", "does", "explain", "for", "gimme", "give", "how", "in",
        "is", "it", "its", "me", "mean", "means", "meaning", "my", "of", "on",
        "or", "please", "teach", "tell", "the", "that", "this", "to", "us",
        "was", "were", "what", "whats", "when", "where", "which", "who",
        "whos", "why", "you", "your",
    )

    /** Significant tokens: lowercase, alphanumeric, not a question
     *  word, and longer than one character (single digits survive —
     *  "chapter 3" keeps its 3). */
    private fun sigTokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 || it.all(Char::isDigit) }
            .filter { it !in queryStop }.toSet()

    /** v0.8.1: equivalence classes for recallFuzzy — every word in a
     *  group is the same topic token. Deliberately tiny and curated:
     *  an unbounded thesaurus trades precision for noise, and one
     *  wrong group would serve the wrong answer. Question words stay
     *  in queryStop where they belong. */
    private val synonymGroups = listOf(
        setOf("math", "maths", "mathematics"),
        setOf("calculation", "arithmetic"),
        setOf("exam", "test"),
        setOf("study", "learn"),
        setOf("photo", "picture", "image"),
        setOf("big", "large", "huge"),
        setOf("small", "little", "tiny"),
        setOf("fast", "quick", "rapid"),
        setOf("start", "begin"),
        setOf("make", "create", "build"),
        setOf("buy", "purchase"),
        setOf("city", "town"),
        setOf("car", "vehicle"),
        setOf("word", "term"),
    )
    private val synonymIndex: Map<String, String> =
        synonymGroups.flatMapIndexed { i, g -> g.map { it to "syn$i" } }.toMap()

    /** Canonical significant tokens: synonym classes collapse to one
     *  id; everything else maps to itself. */
    private fun canonTokens(s: String): Set<String> =
        sigTokens(s).map { synonymIndex[it] ?: it }.toSet()

    /** One snapshot write per record; a host that records often can
     *  debounce inside its LearningStore. v0.9.0: graded-fact lines
     *  follow the cache lines, each after a leading tab. */
    private fun persist() {
        val sb = StringBuilder()
        for ((k, v) in cache) {
            sb.append(escapeLine(k)).append('\t').append(escapeLine(v.answer)).append('\n')
        }
        for (f in facts.values) {
            sb.append('\t').append(f.serialize()).append('\n')
        }
        store.write(sb.toString())
    }

    private fun restore() {
        val snap = store.read() ?: return
        for (line in snap.split('\n')) {
            if (line.isEmpty()) continue
            // v0.9.0: a leading tab marks a graded-fact line — a cache
            // line can never start with one. Malformed fact lines are
            // skipped, never fatal.
            if (line[0] == '\t') {
                val fact = LearnedFact.parse(line.substring(1))
                if (fact != null) facts[fact.question.trim()] = fact
                continue
            }
            val i = line.indexOf('\t')
            if (i <= 0) continue
            val key = unescapeLine(line.substring(0, i)) ?: continue
            val answer = unescapeLine(line.substring(i + 1)) ?: continue
            if (key.isEmpty() || answer.isEmpty()) continue
            cache[key] = NovaResponse(
                answer = answer,
                plan = Plan(Route.CACHE, null, 0, 0, "restored from the learner store"),
                verify = VerifyResult(true, 0.95, "served from cache (verified when first computed)"),
                repaired = false,
                cacheHit = true,
                llmUsed = false,
                trace = emptyList(),
            )
        }
        while (facts.size > maxEntries) facts.remove(facts.keys.first())
    }

    /** v0.9.0: the cache shape of a graded fact — the same canonical
     *  "restored from cache" response restore() rebuilds, but carrying
     *  the fact's own score in the verdict. */
    private fun factResponse(f: LearnedFact) = NovaResponse(
        answer = f.answer,
        plan = Plan(Route.CACHE, null, 0, 0, "restored from the learner store"),
        verify = VerifyResult(true, f.score, "served from learned memory (${f.provenance})"),
        repaired = false,
        cacheHit = true,
        llmUsed = false,
        trace = emptyList(),
    )

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
