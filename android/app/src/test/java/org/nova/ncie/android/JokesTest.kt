package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** v9.33.18: the joke set is non-empty, in range, and wraps safely. */
class JokesTest {

    @Test
    fun everySeedLandsInRange() {
        for (s in -50..50) {
            val j = Jokes.at(s)
            assertEquals("seed $s", Jokes.at(s), j)
            assertEquals(true, j.isNotBlank())
        }
    }

    @Test
    fun wrapsRatherThanThrowing() {
        assertEquals(Jokes.at(0), Jokes.at(Jokes.count()))
        assertEquals(Jokes.at(0), Jokes.at(Int.MIN_VALUE))
    }

    @Test
    fun differentSeedsCanDiffer() {
        assertNotEquals(Jokes.at(0), Jokes.at(1))
    }
}
