package org.nova.ncie.android

/**
 * v9.33.7: the one implementation of "turn a fetched source into a short,
 * readable answer". This rule used to live TWICE - OnlineFetch.cleanSource
 * and an inline copy in NcieChat's entity-answer path - and the two copies
 * drifted into the same off-by-constant crash (a `> 900` guard with a
 * `substring(0, 1200)` slice). It lives in one place now, so a fix lands
 * once and cannot diverge again.
 */
internal fun cleanSourceText(t: String): String {
    var x = t.replace(Regex("\\[\\d+\\]"), "").replace(Regex("\\s+"), " ").trim()
    // v9.33.8: 1200 characters is a paragraph dump. Even when the answer is
    // quoted from a source, two or three sentences answer a lookup - the rest
    // is what made NOVA read like a search result instead of a reply.
    if (x.length > 420) {
        x = x.substring(0, 420)
        val cut = x.lastIndexOf(". ")
        if (cut > 150) x = x.substring(0, cut + 1)
    }
    return x
}
