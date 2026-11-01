package org.nova.ncie.learn

/**
 * v0.9.4 (stage 4): the capability-gap log. When a turn fails with
 * NOTHING behind it - no tool claimed it, no skill matched, no sources
 * grounded it - the request is a thing NOVA structurally cannot do yet.
 * The host notes it here and surfaces the counts ("you asked for this
 * 4 times - request the feature"), so the improvement loop starts from
 * MEASURED gaps instead of guesses.
 *
 * One entry per trimmed question, merged by count. The host owns the
 * file; this class owns the logic.
 */
class GapCount(val question: String, val count: Int, val lastMillis: Long)

class GapStore private constructor(private val gaps: HashMap<String, GapCount>) {

    fun note(question: String, now: Long) {
        val key = question.trim()
        if (key.isEmpty()) return
        val old = gaps[key]
        if (old != null) {
            gaps[key] = GapCount(key, old.count + 1, now)
            return
        }
        if (gaps.size >= MAX) {
            // full: the least-asked gap drops - the log keeps the
            // signal, not the history
            val drop = gaps.values.minByOrNull { it.count } ?: return
            gaps.remove(drop.question)
        }
        gaps[key] = GapCount(key, 1, now)
    }

    /** The most-asked gaps first. */
    fun top(n: Int): List<GapCount> =
        gaps.values.sortedWith(
            compareByDescending<GapCount> { it.count }.thenBy { it.question }
        ).take(n)

    fun size(): Int = gaps.size

    fun serialize(): String {
        val sb = StringBuilder()
        for (g in top(Int.MAX_VALUE)) {
            sb.append(escape(g.question)).append('\t')
                .append(g.count).append('\t').append(g.lastMillis).append('\n')
        }
        return sb.toString()
    }

    companion object {
        /** The log keeps at most 100 distinct gaps. */
        const val MAX = 100

        fun parse(text: String): GapStore {
            val gaps = HashMap<String, GapCount>()
            for (line in text.split('\n')) {
                if (line.isEmpty()) continue
                val p = line.split('\t')
                if (p.size != 3) continue
                val q = unescape(p[0]) ?: continue
                val c = p[1].toIntOrNull() ?: continue
                val last = p[2].toLongOrNull() ?: continue
                if (q.isEmpty() || c <= 0) continue
                gaps[q] = GapCount(q, c, last)
            }
            return GapStore(gaps)
        }

        private fun escape(s: String): String =
            s.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\r", "\\r").replace("\n", "\\n")

        private fun unescape(s: String): String? {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\') {
                    if (i + 1 >= s.length) return null
                    when (s[i + 1]) {
                        '\\' -> sb.append('\\')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'n' -> sb.append('\n')
                        else -> return null
                    }
                    i += 2
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
    }
}
