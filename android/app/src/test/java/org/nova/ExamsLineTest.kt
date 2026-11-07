package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v9.33.122 "The exam line carries the three nearest".
 *
 * [Exams.lineFor] is the exam line that goes into the prompt of every chat
 * turn. It is the one part of the prompt nothing can trim - it is context, not
 * a retrieval hit - so its size is paid on every turn, and the model only ever
 * needs the next exam.
 *
 * These pin the cap, the order, and that nothing upcoming still means no line
 * at all (an empty line would be a prompt token spent on nothing).
 */
class ExamsLineTest {

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
    fun noUpcomingExamMeansNoLine() {
        assertNull(Exams.lineFor(emptyList()))
        assertNull(Exams.lineFor(listOf(exam("Physics", -3))))
    }

    @Test
    fun theNearestIsFirst() {
        val line = Exams.lineFor(listOf(exam("Physics", 12), exam("Chemistry", 3)))
        assertTrue(line!!.startsWith("Chemistry"))
        assertTrue(line.indexOf("Chemistry") < line.indexOf("Physics"))
    }

    @Test
    fun onlyTheThreeNearestAreCarried() {
        // full names, not letters: a single "D" would match the month "Dec"
        val line = Exams.lineFor(listOf(
            exam("Physics", 1), exam("Chemistry", 2), exam("Maths", 3),
            exam("Biology", 4), exam("History", 5)
        ))!!
        assertTrue(line.contains("Physics"))
        assertTrue(line.contains("Maths"))
        assertEquals("the fourth exam is not in the line", false, line.contains("Biology"))
        assertEquals("the fifth exam is not in the line", false, line.contains("History"))
    }

    @Test
    fun anExamThatHasPassedIsNotInTheLine() {
        val line = Exams.lineFor(listOf(exam("Old", -2), exam("Next", 4)))!!
        assertEquals(false, line.contains("Old"))
        assertTrue(line.contains("Next"))
    }

    @Test
    fun theExamItselfSaysToday() {
        // the day is what counts, not the hour - the v9.33.118 rule
        val line = Exams.lineFor(listOf(exam("Physics", 0, 23)))!!
        assertTrue("got: " + line, line.contains("Physics TODAY"))
    }

    @Test
    fun theCapIsThree() {
        assertEquals(3, Exams.PROMPT_LIMIT)
    }
}
