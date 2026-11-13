package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.146: the "timer" phone command's duration parser.
 *
 * The two shipped bugs are the reason this test exists, so both are pinned
 * directly: a digit run too long for an Int (which threw NumberFormatException
 * straight out of a chat turn) and an hour count that overflowed the Int
 * multiplication into a negative length. Neither may throw, ever - a phone
 * command must not be able to take the app down - so every absurd input below
 * is asserted to come back as an ordinary answer.
 *
 * Pure JVM, no Android imports: `gradle testDebugUnitTest`.
 */
class TimerSpecTest {

    private fun secs(spec: String): Int? = NcieTimer.parse(spec)?.seconds

    private fun tooLong(spec: String): Boolean = NcieTimer.parse(spec)?.tooLong == true

    @Test
    fun readsMinutesHoursAndSeconds() {
        assertEquals(600, secs("10 minutes"))
        assertEquals(600, secs("10 min"))
        assertEquals(600, secs("10m"))
        assertEquals(7200, secs("2 hours"))
        assertEquals(7200, secs("2 hrs"))
        assertEquals(7200, secs("2h"))
        assertEquals(90, secs("90 seconds"))
        assertEquals(90, secs("90s"))
    }

    @Test
    fun acceptsTheSpacingAndCaseUsersActuallyType() {
        assertEquals(600, secs("10   minutes"))
        assertEquals(600, secs("10MINUTES"))
        assertEquals(600, secs("Ten 10 Minutes"))
        assertEquals(300, secs("5 m"))
    }

    @Test
    fun aDurationThatIsNotThereIsNotATimer() {
        assertNull(secs("ten minutes"))
        assertNull(secs("a while"))
        assertNull(secs("10"))
        assertNull(secs("10 bananas"))
        assertNull(secs(""))
    }

    /**
     * The v9.33.146 crash: 14 digits does not fit an Int. The old inline
     * toInt() threw NumberFormatException with no catch anywhere on the path.
     */
    @Test
    fun anIntOverflowingDigitRunIsAnAnswerNotACrash() {
        val spec = NcieTimer.parse("99999999999999 minutes")
        assertNotNull(spec)
        assertTrue(spec!!.tooLong)
        assertEquals(0, spec.seconds)
    }

    /** 30 digits does not fit a Long either - still just "too long". */
    @Test
    fun aDigitRunTooLongForALongIsStillJustTooLong() {
        assertTrue(tooLong("999999999999999999999999999999 hours"))
    }

    /**
     * The v9.33.146 overflow: 1,000,000 hours is 3.6e9 seconds, which wrapped
     * to a negative Int and was handed to the clock app as a nonsense length.
     */
    @Test
    fun anHourCountThatOverflowedIntIsTooLong() {
        assertTrue(tooLong("1000000 hours"))
        assertTrue(tooLong("100000 hours"))
    }

    @Test
    fun theLongestAllowedTimerIsTwentyFourHours() {
        assertEquals(NcieTimer.MAX_SECONDS, secs("24 hours"))
        assertEquals(NcieTimer.MAX_SECONDS, secs("1440 minutes"))
        assertEquals(NcieTimer.MAX_SECONDS, secs("86400 seconds"))
        assertTrue(tooLong("25 hours"))
        assertTrue(tooLong("1441 minutes"))
        assertTrue(tooLong("86401 seconds"))
    }

    /** "timer 0 minutes" asks for nothing - answered as a usage hint. */
    @Test
    fun aZeroLengthTimerIsNotATimer() {
        assertNull(secs("0 minutes"))
        assertNull(secs("0s"))
    }

    @Test
    fun nothingInThisParserCanThrow() {
        val hostile = listOf(
            "99999999999999999999999999999999999999999999999999 h",
            "0" + "9".repeat(400) + " minutes",
            "-5 minutes", "+5 minutes", "5.5 hours", "1e9 seconds",
            "\u0000", "minutes", "m")
        for (s in hostile) {
            // the call itself must not throw; either answer is acceptable
            val spec = NcieTimer.parse(s)
            if (spec != null) assertFalse("0-second spec leaked for: " + s,
                spec.seconds == 0 && !spec.tooLong)
        }
    }

    /**
     * "timer 1.5 hours" used to set FIVE hours: the integer-only pattern
     * matched the "5 hours" inside "1.5 hours", and the toast echoed it back.
     */
    @Test
    fun aFractionalDurationSetsWhatTheUserAskedFor() {
        assertEquals(5400, secs("1.5 hours"))
        assertEquals(1800, secs("0.5 hours"))
        assertEquals(5400, secs("1,5 hours"))
        assertEquals(135, secs("2.25 minutes"))
        assertEquals(30, secs("0.5 minutes"))
        assertEquals(2, secs("1.9 seconds"))
    }

    @Test
    fun theConfirmationSaysWhatWasActuallySet() {
        assertEquals("10 minutes", NcieTimer.label(600))
        assertEquals("1 minute", NcieTimer.label(60))
        assertEquals("2 hours", NcieTimer.label(7200))
        assertEquals("1 hour 30 minutes", NcieTimer.label(5400))
        assertEquals("1 second", NcieTimer.label(1))
        assertEquals("45 minutes", NcieTimer.label(45 * 60))
        assertEquals("0 seconds", NcieTimer.label(0))
    }

    /** The study-routine timer NOVA arms itself still parses cleanly. */
    @Test
    fun theStudyRoutineLengthIsStillAccepted() {
        val spec = NcieTimer.parse("45 minutes")
        assertNotNull(spec)
        assertEquals(45 * 60, spec!!.seconds)
        assertFalse(spec.tooLong)
    }
}
