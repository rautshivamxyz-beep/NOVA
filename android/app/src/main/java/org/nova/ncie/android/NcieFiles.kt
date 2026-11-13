package org.nova.ncie.android

import org.nova.NovaLog
import java.io.File

/**
 * v9.33.90 "One way to write a file".
 *
 * NOVA keeps its small state as text files in the app's own directory: the
 * exam date, the tuning overrides, the learned experience, the focus session,
 * the value stats, the rolling summary, the Jarvis trigger log. Several of
 * them were written with a bare `File.writeText`.
 *
 * That call truncates the file first and then writes. Kill the app (or lose
 * the phone) between those two moments and what is left on disk is a
 * half-written file - not the old value and not the new one. On the read side
 * every one of these files is parsed defensively, so the result is not a
 * crash: it is a silent reset. The user's exam date is simply gone, or every
 * learned fact is quietly dropped, and nothing anywhere says why.
 *
 * [write] writes a sibling `.tmp` and renames it over the target, so a reader
 * sees either the complete old file or the complete new one. `renameTo` is
 * atomic within one filesystem, and every one of these files lives in the
 * app's own `filesDir`. The fallbacks below only exist for a filesystem that
 * refuses the rename: a direct write is still better than no file at all.
 *
 * This is the same ceremony `Knowledge.kt` (v7.6), `ChatStore`, `ReminderStore`
 * and `Exams` already do by hand. It lives here so a new file does not have to
 * reinvent it - and so one unit test can pin it.
 *
 * Never throws: a failed save returns false and is recorded in the swallowed
 * count, exactly like every other best-effort write in the app.
 */
object NcieFiles {

    /**
     * v9.33.99 "One buffer per file": one lock per destination path.
     *
     * The save writes a FIXED sibling name (`<name>.tmp`), so two threads
     * saving the same file at the same time were writing into the same
     * buffer: `writeText` is a buffered stream, so their bytes can interleave
     * and the file that gets renamed into place is neither writer's text - a
     * corrupt state file, which every reader then parses defensively and
     * silently resets. Nothing in the app serialized the callers (NcieCache.put
     * and several state saves run on whatever thread got there first).
     *
     * The lock is keyed by the destination's absolute path, so different files
     * still save in parallel and only the same file waits. The map holds one
     * entry per state file the app owns - a small, fixed set, not per call.
     */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** [f] with [text] in it, atomically. True when the new text is on disk. */
    fun write(f: File, text: String): Boolean {
        val lock = locks.getOrPut(f.absolutePath) { Any() }
        return synchronized(lock) { writeNow(f, text) }
    }

    private fun writeNow(f: File, text: String): Boolean {
        val tmp = File(f.parentFile, f.name + ".tmp")
        return try {
            f.parentFile?.mkdirs()
            tmp.writeText(text)
            if (tmp.renameTo(f)) {
                true
            } else {
                // Some Android filesystems refuse a rename onto an existing
                // file. Drop the old one and try again before giving up.
                f.delete()
                if (tmp.renameTo(f)) true else {
                    f.writeText(text)
                    tmp.delete()
                    true
                }
            }
        } catch (e: Exception) {
            NovaLog.quiet("NcieFiles", e)
            try { tmp.delete() } catch (e2: Exception) { NovaLog.quiet("NcieFiles", e2) }
            false
        }
    }

    /**
     * v9.33.131 "One save, even for an append": the second write path.
     *
     * v9.33.127 pinned `writeText` as the one way NOVA saves a file, but
     * `appendText` is not `writeText`, and the skill forge still used it to
     * grow the user's skills file. That is a bare buffered append: not
     * atomic, and outside the per-path lock every other save takes.
     *
     * An append cannot delete what it never read, so it did not need the
     * readOrNull guard to be safe - but reading first is what makes it
     * crash-safe, and it is exactly what the old call was missing. The read
     * is the guard too: a file that is there and unreadable answers false
     * and is left alone, because appending to a file NOVA cannot read would
     * write a second half onto a damaged whole.
     */
    fun append(f: File, text: String): Boolean {
        val lock = locks.getOrPut(f.absolutePath) { Any() }
        return synchronized(lock) {
            val old = readOrNull(f)
            if (old == null) false else writeNow(f, old + text)
        }
    }

    /**
     * The same write, appended to the list of best-effort saves that only
     * care that it happened. Kept for the call sites that already swallow.
     */
    fun writeQuietly(f: File, text: String) {
        write(f, text)
    }

    /**
     * v9.33.147: [write], with its answer recorded when the answer is false.
     *
     * A best-effort save may still fail, but it may not fail INVISIBLY. The
     * call sites this replaces were written as
     *
     *     try { NcieFiles.write(f, text) } catch (e: Exception) { NovaLog.quiet(...) }
     *
     * which stopped meaning anything at v9.33.90: [write] returns false
     * instead of throwing, so that catch is dead code and the false was
     * dropped on the floor. A growth report, a capability-gap list, a summary
     * count, a Jarvis trigger mark - all state the app reasons from later -
     * could stop saving and nothing anywhere would say so.
     *
     * The answer is still the answer; this only makes sure the health screen's
     * tally hears about it. Never throws, so it is safe in the same places
     * [write] is.
     */
    fun writeOrNote(f: File, text: String, tag: String): Boolean {
        val ok = write(f, text)
        if (!ok) NovaLog.note(tag, "could not save " + f.name)
        return ok
    }

    /**
     * v9.33.119 "A store that could not be read is not an empty store": the
     * one read, the other half of [write].
     *
     * Every store parses its file defensively, so a file it cannot read comes
     * back as "nothing there". Two answers are needed, not one:
     *
     *   ""    the file is not there yet - a fresh install, nothing to lose
     *   null  the file is there and could not be read - something to lose
     *
     * A reader may flatten both to an empty list; a store that is about to
     * rewrite the file from what it read may not. The stores that write back
     * what they read (reminders, exams, planner.json) ask this, and refuse to
     * save when the answer is null - otherwise adding one item rewrites the
     * file from an empty list and deletes everything else the user had.
     *
     * Never throws: an unreadable file is recorded and answered with null.
     */
    fun readOrNull(f: File): String? {
        if (!f.exists()) return ""
        return try {
            f.readText()
        } catch (e: Exception) {
            NovaLog.quiet("NcieFiles", e)
            null
        }
    }
}
