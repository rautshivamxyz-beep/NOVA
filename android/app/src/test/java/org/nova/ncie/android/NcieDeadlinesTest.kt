package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
