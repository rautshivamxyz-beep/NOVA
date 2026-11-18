package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * v9.33.162: the clock reply.
 *
 * The point of this release is that a date question is answered from the
 * phone's clock instead of by a model that cannot know the date, so these
 * pin the two things that can go wrong: the wording (a wrong date is worse
 * than no answer) and the recognition (a question with extra words must
 * still reach the model).
 */
class NcieClockTest {

    private val ist: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** 9:07 AM on Wednesday 18 November 2026, in [zone]. */
    private fun morning(zone: TimeZone): Long {
        val c = Calendar.getInstance(zone)
        c.clear()
        c.set(2026, Calendar.NOVEMBER, 18, 9, 7, 0)
        return c.timeInMillis
    }

    @Test
    fun timeReadsAsATwelveHourClock() {
        assertEquals("9:07 AM", NcieClock.timeText(morning(ist), ist))
    }

    @Test
    fun dateNamesTheDayAndTheMonth() {
        assertEquals("Wednesday, 18 November 2026", NcieClock.dateText(morning(ist), ist))
    }

    @Test
    fun dayIsJustTheWeekday() {
        assertEquals("Wednesday", NcieClock.dayText(morning(ist), ist))
    }

    @Test
    fun aTimeQuestionAlsoCarriesTheDate() {
        val reply = NcieClock.answer(NcieClock.Ask.TIME, morning(ist), ist)
        assertTrue(reply.contains("9:07 AM"))
        assertTrue(reply.contains("18 November 2026"))
    }

    @Test
    fun aDateQuestionAnswersOnlyTheDate() {
        assertEquals("Wednesday, 18 November 2026",
            NcieClock.answer(NcieClock.Ask.DATE, morning(ist), ist))
    }

    @Test
    fun aDayQuestionAnswersOnlyTheDay() {
        assertEquals("Wednesday",
            NcieClock.answer(NcieClock.Ask.DAY, morning(ist), ist))
    }

    @Test
    fun theReplyFollowsTheTimeZoneItIsGiven() {
        // The same instant is 9:07 AM in India and 3:37 AM in UTC. A clock
        // that ignores the zone would be wrong for every travelling user.
        val ms = morning(ist)
        assertEquals("3:37 AM", NcieClock.timeText(ms, utc))
        assertTrue(NcieClock.dateText(ms, utc).contains("18 November 2026"))
    }

    @Test
    fun theOrdinaryPhrasingsAreRecognised() {
        val times = listOf("what time is it", "what's the time", "What time is it?",
            "current time", "time please", "tell me the time", "what time is it now")
        for (t in times)
            assertEquals("time: " + t, NcieClock.Ask.TIME, NcieClock.ask(t))

        val dates = listOf("what's the date", "what is today's date", "today's date",
            "what date is it", "what's the date today")
        for (t in dates)
            assertEquals("date: " + t, NcieClock.Ask.DATE, NcieClock.ask(t))

        val days = listOf("what day is it", "what day is today", "which day is it",
            "what day of the week is it")
        for (t in days)
            assertEquals("day: " + t, NcieClock.Ask.DAY, NcieClock.ask(t))
    }

    @Test
    fun aQuestionWithExtraWordsIsNotAClockQuestion() {
        // These are real questions and must reach the model untouched.
        val notClock = listOf(
            "what time is it in Tokyo",
            "what day is my exam",
            "what's the date of the French Revolution",
            "what time does the train leave",
            "how much time is left")
        for (t in notClock)
            assertFalse("should not be a clock question: " + t, NcieClock.matches(t))
    }

    @Test
    fun aLongMessageIsNotAClockQuestion() {
        val long = "what time is it" + " ".repeat(60)
        assertFalse(NcieClock.matches(long))
    }

    @Test
    fun noAskMeansNoReply() {
        assertEquals("", NcieClock.answer(NcieClock.Ask.NONE, morning(ist), ist))
    }
}
