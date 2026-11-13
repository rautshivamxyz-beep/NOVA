package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.147: the record a save leaves when it returns false.
 *
 * v9.33.90 made NcieFiles.write answer false instead of throwing, which
 * silently turned every `try { write(...) } catch { }` in the app into dead
 * code - the catch could never run, and a failed save left no trace. [NovaLog.note]
 * is the counterpart to [NovaLog.quiet] for that answer, so what matters here
 * is that it records, that it is counted, and above all that it never throws:
 * it is called from inside best-effort save paths, where a logging failure
 * would be worse than the thing being logged.
 *
 * Pure JVM: android.util.Log is the stub jar here, which is exactly the
 * hostile environment [note] promises to survive.
 */
class NovaLogTest {

    @Test
    fun aNotedFailureIsCounted() {
        val before = NovaLog.swallowedCount()
        NovaLog.note("Test", "could not save thing.txt")
        assertEquals(before + 1, NovaLog.swallowedCount())
    }

    @Test
    fun theLastNoteIsWhatTheHealthScreenShows() {
        NovaLog.note("NcieFiles", "could not save reminders.txt")
        assertEquals("NcieFiles: could not save reminders.txt", NovaLog.last)
        assertTrue(NovaLog.summary().contains("could not save reminders.txt"))
    }

    @Test
    fun notingNeverThrowsEvenWithTheStubLogger() {
        // android.util.Log is unavailable under a JVM unit test; the call must
        // still complete and still be recorded
        val before = NovaLog.swallowedCount()
        NovaLog.note("NcieFiles", "could not save growth_report.txt")
        assertEquals(before + 1, NovaLog.swallowedCount())
    }

    @Test
    fun quietAndNoteShareOneTally() {
        val before = NovaLog.swallowedCount()
        NovaLog.quiet("Test", IllegalStateException("boom"))
        NovaLog.note("Test", "could not save x.txt")
        assertEquals(before + 2, NovaLog.swallowedCount())
    }

    @Test
    fun anEmptyTagOrMessageIsStillRecorded() {
        val before = NovaLog.swallowedCount()
        NovaLog.note("", "")
        assertEquals(before + 1, NovaLog.swallowedCount())
        assertEquals(": ", NovaLog.last)
    }
}
