package org.nova.ncie.learn

import org.nova.ncie.model.NovaResponse
import org.nova.ncie.model.Route

/**
 * ⑤ LEARN — make the next answer faster or better.
 *
 * v0.1: an exact-match predictive cache plus route statistics
 * (how often each route wins, per intent). Everything is in-memory now;
 * the interface is the seam where the JSON-on-disk store from NOVA
 * (ChatStore-style) plugs in later.
 */
interface Learner {
    /** Return a cached answer for this exact request, if any. */
    fun recall(text: String): NovaResponse?
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
