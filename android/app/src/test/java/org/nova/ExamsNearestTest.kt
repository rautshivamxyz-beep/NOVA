package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * v9.33.120 "One exam date, one store".
 *
 * The chat countdown, the revision plan, the deadline radar and the weekly
 * review all need ONE exam date, and they used to read a file of their own
 * that only the chat command wrote - so a date set on the Exams screen was
 * invisible to the chat, and an exam cleared on the screen kept the chat
 * counting down. [Exams.nearest] is the one selection rule now: the exam the
 * screen lists first and the one the model prompt carries.
 *
 * These pin the two boundaries it has to get right - which exam is "my exam"
 * when there are several, and what it answers when every exam is in the past.
 */
class ExamsNearestTest {

    private fun at(dayOffset: Int, hour: Int): Long {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun exam(name: String, dayOffset: Int, hour: Int = 9) =
        Exams.Exam(name, at(dayOffset, hour))

    @Test
    fun noExamsMeansNoExam() {
        assertNull(Exams.nearest(emptyList()))
    }

    @Test
    fun theSoonestUpcomingExamIsTheAnswer() {
        val nearest = Exams.nearest(listOf(
            exam("Physics", 12),
            exam("Chemistry", 3),
            exam("Maths", 40)
        ))
        assertEquals("Chemistry", nearest?.name)
    }

    @Test
    fun anExamThatHasPassedIsNotTheAnswerWhileOneIsUpcoming() {
        val nearest = Exams.nearest(listOf(
            exam("Physics", -2),
            exam("Chemistry", 5)
        ))
        assertEquals("Chemistry", nearest?.name)
    }

    @Test
    fun withNothingUpcomingTheMostRecentPastExamIsAnswered() {
        // the screen is still showing it, so the chat must not say "set your
        // exam date first" - it says the exam is today or already past
        val nearest = Exams.nearest(listOf(
            exam("Physics", -30),
            exam("Chemistry", -2)
        ))
        assertEquals("Chemistry", nearest?.name)
    }

    @Test
    fun anExamLaterTodayIsStillUpcoming() {
        // the day is what counts, not the hour: a 23:00 exam is upcoming at
        // 00:01 on its own day, which is the v9.33.118 rule this asks
        val nearest = Exams.nearest(listOf(exam("Physics", 0, 23)))
        assertEquals("Physics", nearest?.name)
        assertEquals(0, Exams.daysLeft(nearest!!.dateMs))
    }
}
