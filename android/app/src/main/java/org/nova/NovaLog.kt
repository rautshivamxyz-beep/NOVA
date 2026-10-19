package org.nova

import java.util.concurrent.atomic.AtomicInteger

/**
 * v9.33.15 "Nothing fails silently".
 *
 * NOVA swallows a lot of exceptions on purpose - best-effort file I/O, an
 * optional subsystem that is not there, a notification that cannot be read.
 * That is the right call; a missing side-feature must never crash the app.
 *
 * The problem was that a swallowed exception looked EXACTLY like "there was
 * nothing to find": 275 empty catch blocks meant a real failure (a corrupt
 * file, a permission revoked mid-session, an engine that refused a load) was
 * indistinguishable from an empty result, and nothing anywhere recorded that
 * anything had gone wrong.
 *
 * [quiet] is the one place those deliberate swallows now report to. It is
 * cheap and can never throw - it must be safe to call from inside a catch in
 * a hot loop - and it keeps a session count and the last message, which the
 * health screen surfaces. Detail goes to logcat under the "NOVA" tag.
 */
object NovaLog {

    private val count = AtomicInteger(0)

    /** The last swallowed failure, "tag: Type: message", or null. */
    @Volatile
    var last: String? = null
        private set

    /** Record a deliberately-swallowed exception. Never throws, never blocks. */
    fun quiet(tag: String, e: Throwable) {
        count.incrementAndGet()
        val line = tag + ": " + e.javaClass.simpleName +
            (e.message?.let { ": " + it } ?: "")
        last = line
        try {
            android.util.Log.d("NOVA", "swallowed - " + line)
        } catch (_: Throwable) {
            // logging must never be the thing that fails
        }
    }

    /** How many exceptions have been swallowed this session. */
    fun swallowedCount(): Int = count.get()

    /** One line for the health screen. */
    fun summary(): String {
        val n = count.get()
        return if (n == 0) "no swallowed failures this session"
        else n.toString() + " swallowed failure(s) this session; last: " + (last ?: "")
    }
}
