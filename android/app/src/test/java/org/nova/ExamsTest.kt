package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v9.33.118 "The exam is not over at nine in the morning".
 *
 * [Exams.daysLeft] is the one rule that decides whether an exam is today,
 * tomorrow or done, and three screens ask it: the Exams list, the planner and
 * the line that goes into the model's prompt. The prompt used to filter on the
 * exam's exact timestamp instead, so from 09:00 on the day of the exam the
 * model was told nothing about it.
 *
 * The math is calendar days in the local zone, so the boundaries are pinned
 * here: today is 0 (not -1 and not 1), tomorrow is 1, yesterday is -1.
 */
class ExamsTest {

    private fun at(dayOffset: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test
    fun todayIsZeroWhateverTheTimeOfDay() {
        assertEquals("midnight today", 0, Exams.daysLeft(at(0, 0, 0)))
        assertEquals("nine in the morning today", 0, Exams.daysLeft(at(0, 9, 0)))
        assertEquals("one minute to midnight today", 0, Exams.daysLeft(at(0, 23, 59)))
    }

    @Test
    fun tomorrowIsOneAndYesterdayIsMinusOne() {
        assertEquals(1, Exams.daysLeft(at(1, 0, 0)))
        assertEquals(1, Exams.daysLeft(at(1, 23, 0)))
        assertEquals(-1, Exams.daysLeft(at(-1, 12, 0)))
    }

    @Test
    fun aDateOnlyExamIsUpcomingOnItsOwnDay() {
        // the rule the prompt asks now: today or later is upcoming. An exam
        // stored at midnight or at 09:00 is still upcoming all day.
        for (hour in listOf(0, 9, 18, 23)) {
            assertTrue("hour $hour on the day must be upcoming",
                Exams.daysLeft(at(0, hour, 0)) >= 0)
        }
        assertTrue("yesterday is over", Exams.daysLeft(at(-1, 23, 0)) < 0)
    }

    @Test
    fun theDifferenceIsCalendarDaysNotMilliseconds() {
        // 23:59 today to 00:01 tomorrow is two minutes, and one day
        val late = Exams.daysLeft(at(0, 23, 59))
        val early = Exams.daysLeft(at(1, 0, 1))
        assertEquals(0, late)
        assertEquals(1, early)
    }
}
