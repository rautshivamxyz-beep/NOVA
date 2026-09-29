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
    private val cache = LinkedHashMap<String, NovaResponse>(16, 0.75f, true)
    /** v0.9.2 (opt #4): the canonical fuzzy-match tokens of every cache
     *  key, computed ONCE at insert - recallFuzzy no longer re-parses
     *  every key (lowercase, split, stopword filter, synonym map) on
     *  every miss. Evicted in lockstep with the cache below. */
    private val canon = HashMap<String, Set<String>>()
    /** v0.9.0: the graded memory — keyed by trimmed question, same
     *  access-order LRU budget as the answer cache. */
    private val facts = LinkedHashMap<String, LearnedFact>(16, 0.75f, true)
    private val routeCounts = HashMap<Route, Int>()
    private var hits = 0
    private var misses = 0
    private var fuzzyHits = 0
    /** v0.9.4 (stage 2): rejected-answer counts per topic — the weak-
     *  topic signal. Persisted as double-tab lines in the snapshot. */
    private val weaknesses = HashMap<String, Int>()

    // v0.9.2 fix: these token sets moved ABOVE the init block. restore()
    // (which init runs) now warms the canonical-token cache for every
    // loaded key, and canonTokens reads them - in declaration order they
    // were still null during construction (the CI demo crashed on it).
    /** Question words never count for matching — only the topic does. */
    private val queryStop = setOf(
        "a", "an", "and", "any", "about", "are", "can", "define", "describe",
        "did", "do", "does", "explain", "for", "gimme", "give", "how", "in",
        "is", "it", "its", "me", "mean", "means", "meaning", "my", "of", "on",
        "or", "please", "teach", "tell", "the", "that", "this", "to", "us",
        "was", "were", "what", "whats", "when", "where", "which", "who",
        "whos", "why", "you", "your",
    )

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

    init {
        restore()
    }

    @Synchronized override fun recall(text: String): NovaResponse? {
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
    @Synchronized override fun recallFuzzy(query: String): Pair<String, NovaResponse>? {
        val key = fuzzyMatch(query) ?: return null
        val r = cache[key] ?: return null   // the get() refreshes the LRU
        fuzzyHits++
        return key to r
    }

    /** v0.9.3 (#10): the matching half of [recallFuzzy] without the
     *  counter bump — the shared body of the fuzzy path and [knowsTopic]. */
    private fun fuzzyMatch(query: String): String? {
        val q = canonTokens(query)
        if (q.isEmpty()) return null
        var bestKey: String? = null
        var bestExtras = Int.MAX_VALUE
        for (k in cache.keys) {
            // v0.9.2 (opt #4): the tokens come from the insert-time cache
            val e = canon[k] ?: continue
            if (e.size < q.size || !e.containsAll(q)) continue
            val extras = e.size - q.size
            if (extras < bestExtras) { bestExtras = extras; bestKey = k }
        }
        return bestKey
    }

    /** v0.9.3 (#10): half-known topic probe — side-effect-free, so the
     *  adaptive planner can call it every turn without polluting the
     *  counters. An EXACTLY known question does not count as half-known:
     *  recall serves it before PLAN ever runs, so there is nothing to
     *  adapt on. */
    @Synchronized override fun knowsTopic(text: String): Boolean {
        if (cache.containsKey(text.trim())) return false
        return fuzzyMatch(text) != null
    }

    /** v0.9.4 (stage 2): a question's topic — its first canonical
     *  tokens, sorted, so word order never splits one topic in two. */
    private fun topicKey(q: String) = canonTokens(q).sorted().take(3).joinToString(" ")

    /** v0.9.4 (stage 2): a rejected answer counts against its topic. The
     *  map is capped at 50 topics — the least-failed one drops when a NEW
     *  topic arrives; existing counts only ever grow or clear. */
    @Synchronized override fun noteFailure(question: String) {
        val key = topicKey(question)
        if (key.isEmpty()) return
        if (!weaknesses.containsKey(key) && weaknesses.size >= 50) {
            val drop = weaknesses.entries.minByOrNull { it.value }?.key ?: return
            weaknesses.remove(drop)
        }
        weaknesses[key] = (weaknesses[key] ?: 0) + 1
        persist()
    }

    /** v0.9.4 (stage 2): the most-failed topics first. */
    @Synchronized override fun weakTopics(): List<WeakTopic> =
        weaknesses.entries.map { WeakTopic(it.key, it.value) }
            .sortedByDescending { it.failures }

    @Synchronized override fun record(text: String, response: NovaResponse) {
        val key = text.trim()
        if (key.isEmpty() || response.plan.route == Route.CACHE) return
        cache[key] = response
        canon[key] = canonTokens(key)
        evictLocked()
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
    @Synchronized override fun record(fact: LearnedFact) {
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
        // v0.9.4 (stage 2): a fact that cleared the quality bar is the
        // topic demonstrably working - its failure count clears.
        weaknesses.remove(topicKey(key))
        while (facts.size > maxEntries) facts.remove(facts.keys.first())
        cache[key] = factResponse(merged)
        canon[key] = canonTokens(key)
        evictLocked()
        persist()
    }

    /**
     * v0.9.0: targeted removal — ONE question leaves memory AND disk,
     * everything else stays (the surgical opposite of [clear], which
     * wipes the world). Returns true when something was removed, so
     * hosts can report a forgotten-vs-unknown distinction.
     */
    @Synchronized override fun forget(questionKey: String): Boolean {
        val key = questionKey.trim()
        val fromFacts = facts.remove(key) != null
        val fromCache = cache.remove(key) != null
        canon.remove(key)
        if (!fromFacts && !fromCache) return false
        persist()
        return true
    }

    /** v0.9.0: the graded memory, in learn order (eldest first). */
    @Synchronized override fun learnedFacts(): List<LearnedFact> = facts.values.toList()

    /**
     * v0.9.0: aging — entries whose lastConfirmed is older than
     * [maxAgeDays] days get their score multiplied by 0.5, so knowledge
     * nobody has re-asked for decays toward death instead of staying as
     * credible as the day it was learned. A demotion can be undone the
     * usual way: re-answering the question re-confirms the fact and
     * blends its score back up. Returns how many entries were demoted.
     */
    @Synchronized override fun demoteStale(maxAgeDays: Int): Int {
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
    @Synchronized override fun clear() {
        cache.clear()
        canon.clear()
        facts.clear()
        routeCounts.clear()
        hits = 0
        misses = 0
        fuzzyHits = 0
        store.write("")
        // v0.9.4 (stage 2): a wiped world has no weak topics either
        weaknesses.clear()
    }

    @Synchronized override fun stats(): String {
        val total = hits + misses
        val hitRate = if (total == 0) 0.0 else hits.toDouble() * 100 / total
        val routes = routeCounts.entries.joinToString(", ") { "${it.key}=${it.value}" }
        val fz = if (fuzzyHits > 0) " (+$fuzzyHits fuzzy)" else ""
        return "cache ${cache.size} entries, hit-rate ${"%.0f".format(hitRate)}%$fz | " +
            "memory ${facts.size} facts | routes: $routes"
    }

    /** v0.9.2 (opt #10): the structured twin of [stats]. */
    @Synchronized override fun statsData(): LearningStats? = LearningStats(
        cacheEntries = cache.size,
        factCount = facts.size,
        hits = hits,
        misses = misses,
        fuzzyHits = fuzzyHits,
        routeCounts = routeCounts.toMap(),
    )

    /** Significant tokens: lowercase, alphanumeric, not a question
     *  word, and longer than one character (single digits survive —
     *  "chapter 3" keeps its 3). */
    private fun sigTokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 || it.all(Char::isDigit) }
            .filter { it !in queryStop }.toSet()

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
        // v0.9.4 (stage 2): weak topics ride in the same snapshot, two
        // leading tabs - a cache line starts with neither, a fact line
        // with exactly one, so all three kinds never collide.
        for ((t, c) in weaknesses) {
            sb.append("\t\t").append(escapeLine(t)).append('\t').append(c).append('\n')
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
            if (line.length > 1 && line[0] == '\t' && line[1] == '\t') {
                // v0.9.4 (stage 2): a weak-topic line
                val body = line.substring(2)
                val i2 = body.indexOf('\t')
                if (i2 > 0) {
                    val t = unescapeLine(body.substring(0, i2))
                    val c = body.substring(i2 + 1).trim().toIntOrNull()
                    if (t != null && t.isNotEmpty() && c != null && c > 0) weaknesses[t] = c
                }
                continue
            }
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
            canon[key] = canonTokens(key)
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
        evictLocked()
    }

    /** v0.9.2 (opt #4): explicit LRU eviction - the LinkedHashMap's
     *  removeEldestEntry hook fired inside restore() and record() but
     *  could not keep the canon token cache in lockstep, so it is
     *  manual now. keys.first() of an access-ordered map IS the least
     *  recently used entry. */
    private fun evictLocked() {
        while (cache.size > maxEntries) {
            val eldest = cache.keys.first()
            cache.remove(eldest)
            canon.remove(eldest)
        }
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
