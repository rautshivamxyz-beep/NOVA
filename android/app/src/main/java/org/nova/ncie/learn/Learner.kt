package org.nova.ncie.learn

import org.nova.ncie.model.NovaResponse
import org.nova.ncie.model.Plan
import org.nova.ncie.model.Route
import org.nova.ncie.model.VerifyResult

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
 */
interface Learner {
    /** Return a cached answer for this exact request, if any. */
    fun recall(text: String): NovaResponse?
    /** Return (matched question, cached answer) when a covering entry
     *  exists for a differently-worded ask, or null. Hosts that do not
     *  implement it simply never fuzzy-hit. */
    fun recallFuzzy(query: String): Pair<String, NovaResponse>? = null
    /** Record a completed exchange. */
    fun record(text: String, response: NovaResponse)
    /** Human-readable stats for the Learn dashboard. */
    fun stats(): String
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

    override fun stats(): String {
        val total = hits + misses
        val hitRate = if (total == 0) 0.0 else hits.toDouble() * 100 / total
        val routes = routeCounts.entries.joinToString(", ") { "${it.key}=${it.value}" }
        return "cache ${cache.size} entries, hit-rate ${"%.0f".format(hitRate)}% | routes: $routes"
    }
}

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
 */
class PersistentLearner(
    private val store: LearningStore,
    private val maxEntries: Int = 200,
) : Learner {
    private val cache = object : LinkedHashMap<String, NovaResponse>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NovaResponse>?): Boolean =
            size > maxEntries
    }
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
     */
    override fun recallFuzzy(query: String): Pair<String, NovaResponse>? {
        val q = sigTokens(query)
        if (q.isEmpty()) return null
        var bestKey: String? = null
        var bestExtras = Int.MAX_VALUE
        for (k in cache.keys) {
            val e = sigTokens(k)
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

    override fun stats(): String {
        val total = hits + misses
        val hitRate = if (total == 0) 0.0 else hits.toDouble() * 100 / total
        val routes = routeCounts.entries.joinToString(", ") { "${it.key}=${it.value}" }
        val fz = if (fuzzyHits > 0) " (+$fuzzyHits fuzzy)" else ""
        return "cache ${cache.size} entries, hit-rate ${"%.0f".format(hitRate)}%$fz | routes: $routes"
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

    /** One snapshot write per record; a host that records often can
     *  debounce inside its LearningStore. */
    private fun persist() {
        val sb = StringBuilder()
        for ((k, v) in cache) {
            sb.append(escape(k)).append('\t').append(escape(v.answer)).append('\n')
        }
        store.write(sb.toString())
    }

    private fun restore() {
        val snap = store.read() ?: return
        for (line in snap.split('\n')) {
            if (line.isEmpty()) continue
            val i = line.indexOf('\t')
            if (i <= 0) continue
            val key = unescape(line.substring(0, i)) ?: continue
            val answer = unescape(line.substring(i + 1)) ?: continue
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
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    /** Strict unescape: any stray backslash invalidates the whole line. */
    private fun unescape(s: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') {
                sb.append(c)
                i++
                continue
            }
            if (i + 1 >= s.length) return null
            when (val n = s[i + 1]) {
                '\\' -> sb.append('\\')
                't' -> sb.append('\t')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                else -> return null
            }
            i += 2
        }
        return sb.toString()
    }
}
