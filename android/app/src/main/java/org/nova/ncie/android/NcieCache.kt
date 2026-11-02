package org.nova.ncie.android

import org.nova.NovaLog
import java.io.File

/**
 * v9.33.91 "The answer cache has a ceiling".
 *
 * Two things are cached in `filesDir/summary_cache` so a repeat costs no
 * generation: an answer to a study question already asked (`qa_<question>_
 * <model>`) and a document's summary (`doc5_<name>_<length>`). Both are
 * cleared when the user's own material changes, which is the correctness
 * rule. Neither had a size rule.
 *
 * Every distinct question ever asked and every document ever summarized left
 * one file behind, and nothing ever removed it, so the directory grew for as
 * long as the install lived - hundreds of entries after a term of study, each
 * one a stat() on every later listing, on a phone whose storage the user is
 * also filling with models. A cache is only a cache if it is bounded.
 *
 * [put] writes one entry and then keeps the [KEEP] newest entries of the same
 * family (the part of the name before the first underscore), so the cost of
 * the ceiling is one directory listing per cached answer - a rare event, not a
 * per-turn one. Deleting an entry another thread is reading is safe: the read
 * fails and the turn simply regenerates.
 */
object NcieCache {

    /** How many entries of one family to keep. */
    const val KEEP = 200

    /** The cache directory, created if it is not there. */
    fun dir(filesDir: File): File =
        File(filesDir, "summary_cache").apply { mkdirs() }

    /** The family a cache key belongs to: "qa_", "doc5_", ... */
    fun familyOf(name: String): String =
        if (name.contains('_')) name.substringBefore('_') + "_" else name

    /**
     * v9.33.97 "A cached answer belongs to the settings that produced it":
     * the one place the cached-answer key is built.
     *
     * Three places used to build this key by hand - the read, the write's
     * owner and the delete - and they did not agree. The delete lowercased
     * with the DEVICE locale while the read used Locale.ROOT, so on a phone
     * whose locale folds "I" differently (Turkish, Azeri) the two produced
     * different keys for the same question: the cache still hit, and the
     * delete that v9.13.0 added so a rejected answer could never replay
     * quietly removed nothing. The key also ignored the answer-length
     * setting, so turning the reply length up and asking the same question
     * again served the old short answer out of the cache instead of
     * generating the longer one - the setting looked broken.
     *
     * One function, one rule: the question in Locale.ROOT, the model label,
     * and the generation length that shaped the answer. Pure string work, so
     * a unit test can pin it.
     */
    fun qaKey(question: String, model: String, length: Int): String =
        "qa_" + Integer.toHexString(question.lowercase(java.util.Locale.ROOT).hashCode()) +
            "_" + Integer.toHexString(model.hashCode()) + "_" + length

    /** Cache one entry atomically, then keep the newest [keep] of its family. */
    fun put(filesDir: File, name: String, text: String, keep: Int = KEEP): Boolean {
        val d = dir(filesDir)
        val ok = NcieFiles.write(File(d, name), text)
        if (ok) prune(d, familyOf(name), keep)
        return ok
    }

    /**
     * Keep the [keep] newest files in [dir] whose name starts with [prefix].
     * Returns how many were removed.
     *
     * Pure file work over a directory, so a JVM unit test can pin it: the
     * newest survive, the oldest go, a directory that is already under the
     * ceiling is left completely alone, and a file outside the family is never
     * touched.
     */
    fun prune(dir: File, prefix: String, keep: Int): Int {
        if (keep < 0) return 0
        val all = dir.listFiles { f: File -> f.isFile && f.name.startsWith(prefix) } ?: return 0
        if (all.size <= keep) return 0
        val ordered = all.sortedBy { it.lastModified() }
        var removed = 0
        for (i in 0 until ordered.size - keep) {
            try { if (ordered[i].delete()) removed++ } catch (e: Exception) {
                NovaLog.quiet("NcieCache", e)
            }
        }
        return removed
    }
}
