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

    /** [f] with [text] in it, atomically. True when the new text is on disk. */
    fun write(f: File, text: String): Boolean {
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
     * The same write, appended to the list of best-effort saves that only
     * care that it happened. Kept for the call sites that already swallow.
     */
    fun writeQuietly(f: File, text: String) {
        write(f, text)
    }
}
