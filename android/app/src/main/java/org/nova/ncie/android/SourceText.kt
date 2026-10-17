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
    if (x.length > 1200) {
        x = x.substring(0, 1200)
        val cut = x.lastIndexOf(". ")
        if (cut > 300) x = x.substring(0, cut + 1)
    }
    return x
}
