package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.96 "Reminders hardened": the reminder id counter.
 *
 * Reminder ids are AlarmManager request codes, so two reminders that share
 * one are ONE alarm - the second silently replaces the first. v7.6 fixed the
 * original cause (hashCode collisions). This pins the other door: a counter
 * file that is missing, blank, corrupt or overflowed must never hand out an
 * id that an older, still-armed alarm may be holding.
 *
 * [ReminderStore.nextIdFrom] is pure arithmetic over the stored text and the
 * clock, so it is pinned here instead of on a phone.
 */
class ReminderStoreTest {

    private val now = 1_700_000_000_000L

    @Test
    fun aGoodCounterKeepsCounting() {
        assertEquals(2, ReminderStore.nextIdFrom("1", now))
        assertEquals(8, ReminderStore.nextIdFrom("7", now))
    }

    @Test
    fun surroundingWhitespaceIsNotCorruption() {
        assertEquals(43, ReminderStore.nextIdFrom(" 42\n", now))
    }

    @Test
    fun aMissingCounterNeverRestartsAtOne() {
        val id = ReminderStore.nextIdFrom(null, now)
        assertNotEquals(1, id)
        assertTrue("a fresh id must be a positive request code", id > 0)
    }

    @Test
    fun aCorruptCounterNeverRestartsAtOne() {
        for (bad in listOf("", "   ", "abc", "1x", "-5", "0", "1.5", "99999999999999999999")) {
            val id = ReminderStore.nextIdFrom(bad, now)
            assertNotEquals("corrupt counter [$bad] must not restart at 1", 1, id)
            assertTrue("corrupt counter [$bad] must give a usable id", id > 0)
        }
    }

    @Test
    fun aCorruptCounterDoesNotReuseTheOldSequence() {
        // the ids an earlier install handed out were small; the fallback has
        // to live outside that range or it recreates the collision
        val id = ReminderStore.nextIdFrom("junk", now)
        assertTrue("fallback id $id must sit clear of the small ids", id > 1000)
    }

    @Test
    fun theTopOfTheRangeStartsOverFromTheClock() {
        val id = ReminderStore.nextIdFrom(Int.MAX_VALUE.toString(), now)
        assertTrue("no wraparound into negative request codes", id > 0)
        assertNotEquals(Int.MIN_VALUE, id)
    }

    @Test
    fun theClockFallbackIsStableWithinOneSecond() {
        val a = ReminderStore.nextIdFrom(null, now)
        val b = ReminderStore.nextIdFrom(null, now + 500L)
        assertEquals("same second, same base", a, b)
        assertNotEquals("the next second is a new id", a, ReminderStore.nextIdFrom(null, now + 1000L))
    }

    // v9.33.115 "A re-arm is not a new alarm": the one arming rule. Every
    // re-arm site (launch, boot, watchdog) asks this instead of writing its
    // own `when`, so the answer has to be right once.

    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun aOneShotInTheFutureKeepsItsOwnTime() {
        assertEquals(now + 5 * day, ReminderStore.nextArm(now + 5 * day, 0L, now))
    }

    @Test
    fun aOneShotThatAlreadyFiredIsNotArmedAgain() {
        assertNull("a fired one-shot must not be re-armed", ReminderStore.nextArm(now - day, 0L, now))
    }

    @Test
    fun aRepeatStillInTheFutureKeepsItsFirstFire() {
        assertEquals(now + 5 * day, ReminderStore.nextArm(now + 5 * day, day, now))
    }

    @Test
    fun aRepeatIsAlwaysArmedStrictlyAfterNow() {
        // an alarm set for the instant it is armed is a race: the OS fires it
        // at once, so the re-arm has to land after now even when the interval
        // divides the time behind us exactly
        assertEquals(now + day, ReminderStore.nextArm(now - 3 * day, day, now))
        assertEquals(now + day, ReminderStore.nextArm(now, day, now))
    }

    @Test
    fun aTinyRepeatIntervalIsOneStepAndNotAHang() {
        // v9.33.89 replaced the add-the-interval loop because a 1ms interval
        // could hang the thread arming it. One step now, and it is a step.
        assertEquals(now + 1L, ReminderStore.nextArm(now - 1L, 1L, now))
    }

    @Test
    fun aCorruptRepeatIntervalIsNeverArmed() {
        assertNull("a decade is not a repeat interval",
            ReminderStore.nextArm(now - day, Long.MAX_VALUE, now))
        assertNull("a negative interval is not a schedule",
            ReminderStore.nextArm(now - day, -day, now))
    }

    // v9.33.116 "A repeating reminder is not due all day": what the spoken
    // nudge announces. The old rule asked whether the reminder's START time
    // was in the past, which is true for a repeat from the day it is made.

    private val hour = 60L * 60L * 1000L

    @Test
    fun aRepeatIsDueOnlyNearItsNextFire() {
        // yesterday, half an hour later than now -> the next fire is in 30 min
        assertTrue(ReminderStore.isDue(now - day + 30L * 60L * 1000L, day, now, hour))
        // yesterday, five hours later than now -> the next fire is in 5 hours
        assertFalse(ReminderStore.isDue(now - day + 5 * hour, day, now, hour))
    }

    @Test
    fun aRepeatIsNotDueForEverBecauseItStartedInThePast() {
        // three weeks in, one fire a day: the anchor is ancient, the next
        // fire is not, and only the next fire decides
        assertFalse(ReminderStore.isDue(now - 21 * day, day, now, hour))
    }

    @Test
    fun aOneShotIsDueOnlyOnceItsTimeHasPassed() {
        assertTrue("a missed one-shot is overdue",
            ReminderStore.isDue(now - 2 * hour, 0L, now, hour))
        assertFalse("a future one-shot is not due yet",
            ReminderStore.isDue(now + 2 * hour, 0L, now, hour))
    }

    @Test
    fun aCorruptIntervalIsNeverAnnounced() {
        assertFalse(ReminderStore.isDue(now - day, Long.MAX_VALUE, now, hour))
    }
}
