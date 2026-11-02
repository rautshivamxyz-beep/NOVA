package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
