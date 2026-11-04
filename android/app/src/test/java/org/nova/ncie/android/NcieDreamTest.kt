package org.nova.ncie.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.110: the morning briefing shows the report the 03:00 alarm wrote.
 * The only decision in that path is whether a stored report belongs to today,
 * so that is what is pinned here - a report from last night is rebuilt, a
 * report from tonight (or this morning) is shown as it stands.
 */
class NcieDreamTest {

    private val midnight = 1_700_000_000_000L
    private val threeAm = midnight + 3 * 60 * 60 * 1000L

    @Test
    fun theOvernightReportIsFresh() {
        assertTrue(NcieDream.fresh(threeAm, midnight))
    }

    @Test
    fun yesterdaysReportIsNotFresh() {
        assertFalse(NcieDream.fresh(midnight - 1L, midnight))
    }

    @Test
    fun aReportWrittenExactlyAtMidnightIsFresh() {
        assertTrue(NcieDream.fresh(midnight, midnight))
    }

    @Test
    fun aMissingReportIsNotFresh() {
        assertFalse(NcieDream.fresh(0L, midnight))
    }

    @Test
    fun anUnknownDayStartIsNeverFresh() {
        assertFalse(NcieDream.fresh(threeAm, 0L))
    }
}
