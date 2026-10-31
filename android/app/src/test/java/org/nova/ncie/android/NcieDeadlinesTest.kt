package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v9.33.16: the deadline radar's date parser ([NcieDeadlines.dateAt]) is the
 * one piece of A2 that is pure calendar math and needs no Android, so it can
 * be pinned by a real unit test. These lock the two rules the radar depends
 * on: a date already past rolls forward to next year ONLY when it carried no
 * explicit year, and an impossible date (31 February) is refused rather than
 * silently shifted to 3 March.
 *
 * Run with `gradle testDebugUnitTest` (CI runs it on every pull request).
 */
class NcieDeadlinesTest {

    private fun midnight(y: Int, m: Int, d: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(y, m - 1, d, 0, 0, 0)
        return c.timeInMillis
    }

    private fun ymd(ms: Long): Triple<Int, Int, Int> {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun explicitYearIsKept() {
        val today = midnight(2026, 6, 1)
        assertEquals(Triple(2026, 11, 5), ymd(NcieDeadlines.dateAt(today, 5, 11, "2026")!!))
    }

    @Test
    fun aTwoDigitYearIsThisCentury() {
        val today = midnight(2026, 6, 1)
        assertEquals(Triple(2027, 3, 15), ymd(NcieDeadlines.dateAt(today, 15, 3, "27")!!))
    }

    @Test
    fun noYearFutureThisYearStays() {
        val today = midnight(2026, 6, 1)
        assertEquals(Triple(2026, 6, 20), ymd(NcieDeadlines.dateAt(today, 20, 6, null)!!))
    }

    @Test
    fun noYearAlreadyPastRollsForward() {
        val today = midnight(2026, 6, 1)
        assertEquals(Triple(2027, 1, 1), ymd(NcieDeadlines.dateAt(today, 1, 1, null)!!))
    }

    @Test
    fun impossibleDatesAreRefused() {
        val today = midnight(2026, 6, 1)
        assertNull(NcieDeadlines.dateAt(today, 31, 2, "2026"))
        assertNull(NcieDeadlines.dateAt(today, 0, 5, null))
        assertNull(NcieDeadlines.dateAt(today, 10, 13, null))
    }

    @Test
    fun aDeadlineWrittenForTodayIsOnTheRadarAllDay() {
        val today = midnight(2026, 6, 1)
        val horizon = today + 60L * 24 * 60 * 60 * 1000
        // a date-only value resolves to the start of today, so by 14:30 it
        // is already 'in the past' - it must still be due, not dropped
        assertTrue(NcieDeadlines.onRadar(today, horizon, today))
        assertTrue(NcieDeadlines.onRadar(today, horizon, today + 1))
        assertTrue(NcieDeadlines.onRadar(today, horizon, horizon))
        // yesterday is not due, and neither is anything past the horizon
        assertTrue(!NcieDeadlines.onRadar(today, horizon, today - 1))
        assertTrue(!NcieDeadlines.onRadar(today, horizon, horizon + 1))
    }

    // ---- v9.33.89: the radar reads dates, not arithmetic ----

    @Test
    fun aDecimalIsNotADeadline() {
        val today = midnight(2026, 6, 1)
        // "8.5" is a valid day and month to the numeric pattern - and an
        // average in every note that contains one
        assertTrue(NcieDeadlines.numericDates(today, "my average is 8.5 out of 10").isEmpty())
    }

    @Test
    fun aFractionIsNotADeadline() {
        val today = midnight(2026, 6, 1)
        assertTrue(NcieDeadlines.numericDates(today, "use 1/2 cup of water").isEmpty())
    }

    @Test
    fun aRangeIsNotADeadline() {
        val today = midnight(2026, 6, 1)
        assertTrue(NcieDeadlines.numericDates(today, "read pages 3-5 again").isEmpty())
    }

    @Test
    fun aTimeIsNotADeadline() {
        val today = midnight(2026, 6, 1)
        assertTrue(NcieDeadlines.numericDates(today, "class starts at 10.05 sharp").isEmpty())
    }

    @Test
    fun aBareDateBesideASchedulingWordIsKept() {
        val today = midnight(2026, 6, 1)
        val found = NcieDeadlines.numericDates(today, "submit by 12/06 please")
        assertEquals(1, found.size)
        assertEquals(Triple(2026, 6, 12), ymd(found[0].first))
    }

    @Test
    fun aDateThatCarriesItsYearNeedsNoHint() {
        val today = midnight(2026, 6, 1)
        // no scheduling word anywhere near - the year makes it unambiguous
        val found = NcieDeadlines.numericDates(today, "the ratio was 12/06/2026 on the sheet")
        assertEquals(1, found.size)
        assertEquals(Triple(2026, 6, 12), ymd(found[0].first))
    }

    @Test
    fun theHintWindowIsLocal() {
        val today = midnight(2026, 6, 1)
        val far = "due" + "x".repeat(200) + " 12/06"
        assertTrue(NcieDeadlines.numericDates(today, far).isEmpty())
    }

    @Test
    fun anIsoDateIsNotReadTwice() {
        val today = midnight(2026, 6, 1)
        val found = NcieDeadlines.numericDates(today, "exam 2026-11-05")
        assertTrue(found.isEmpty())
    }

    // ---- v9.33.89: the roll-forward is one step, never a loop ----

    @Test
    fun aRepeatingReminderRollsForwardInOneStep() {
        val now = midnight(2026, 6, 1)
        val day = 24L * 60 * 60 * 1000
        // a daily reminder last armed three days ago is due right now, not
        // three days ago
        assertEquals(now, NcieDeadlines.nextFire(now - 3 * day, day, now))
        // one second after its fire time, the next fire is a second away
        assertEquals(now + 1000, NcieDeadlines.nextFire(now - 3 * day + 1000, day, now))
    }

    @Test
    fun aFutureReminderKeepsItsOwnTime() {
        val now = midnight(2026, 6, 1)
        val day = 24L * 60 * 60 * 1000
        assertEquals(now + 2 * day, NcieDeadlines.nextFire(now + 2 * day, day, now))
    }

    @Test
    fun aOneShotReminderIsNeverRolledForward() {
        val now = midnight(2026, 6, 1)
        assertEquals(now - 5, NcieDeadlines.nextFire(now - 5, 0L, now))
    }

    @Test
    fun aTinyIntervalTerminatesInsteadOfHanging() {
        val now = midnight(2026, 6, 1)
        // 1 ms repeat, decades behind: the old while-loop would have run
        // ~10^12 times on the UI thread. One division now.
        assertEquals(now, NcieDeadlines.nextFire(0L, 1L, now))
    }
}
