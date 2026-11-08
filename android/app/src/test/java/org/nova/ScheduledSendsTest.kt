package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.123 "The scheduled text is the whole text".
 *
 * The scheduled-texts store is one tab-separated line per send. The message
 * used to be written raw, so a newline in it split the record in two and the
 * reader kept only the first half - the text that actually sent was cut, and
 * NOVA still said "sent". A tab was worse: it is the field separator.
 *
 * These pin the round trip (what the writer produces is what the reader
 * reads back), that an old line written before this release still parses,
 * and that one broken line costs only itself.
 */
class ScheduledSendsTest {

    private fun send(
        id: String = "1",
        millis: Long = 1_700_000_000_000L,
        name: String = "Mom",
        message: String = "get milk",
        notified: Boolean = false
    ) = ScheduledSends.Send(id, millis, name, message, notified)

    private fun roundTrip(s: ScheduledSends.Send) =
        ScheduledSends.decode(ScheduledSends.encode(listOf(s))).single()

    @Test
    fun aPlainMessageRoundTrips() {
        val back = roundTrip(send())
        assertEquals("Mom", back.name)
        assertEquals("get milk", back.message)
        assertEquals(1_700_000_000_000L, back.millis)
        assertFalse(back.notified)
    }

    @Test
    fun aTwoLineMessageKeepsBothLines() {
        val back = roundTrip(send(message = "get milk\nand eggs"))
        assertEquals("get milk\nand eggs", back.message)
    }

    @Test
    fun aMessageWithATabIsNotCutAtTheTab() {
        val back = roundTrip(send(message = "col1\tcol2"))
        assertEquals("col1\tcol2", back.message)
    }

    @Test
    fun aTabInTheMessageCannotFlipTheNotifiedFlag() {
        // "n" as the last field is the notified marker - a raw tab let the
        // tail of a message become that field
        val back = roundTrip(send(message = "ship\tn"))
        assertEquals("ship\tn", back.message)
        assertFalse(back.notified)
    }

    @Test
    fun aCarriageReturnDoesNotSplitTheLine() {
        val back = roundTrip(send(message = "one\rtwo"))
        assertEquals("one\rtwo", back.message)
    }

    @Test
    fun aBackslashSurvives() {
        val back = roundTrip(send(message = "C:\\notes\\milk"))
        assertEquals("C:\\notes\\milk", back.message)
    }

    @Test
    fun theNotifiedFlagRoundTrips() {
        assertTrue(roundTrip(send(notified = true)).notified)
        assertFalse(roundTrip(send(notified = false)).notified)
    }

    @Test
    fun theWholeListRoundTrips() {
        val list = listOf(send(id = "a", message = "one\ntwo"), send(id = "b", notified = true))
        val back = ScheduledSends.decode(ScheduledSends.encode(list))
        assertEquals(2, back.size)
        assertEquals("one\ntwo", back[0].message)
        assertEquals("b", back[1].id)
        assertTrue(back[1].notified)
    }

    @Test
    fun anEmptyStoreIsEmpty() {
        assertEquals(0, ScheduledSends.decode("").size)
    }

    @Test
    fun aLineWrittenBeforeThisReleaseStillParses() {
        // the exact shape v9.8.0 wrote: id, millis, name, message
        val back = ScheduledSends.decode("77\t1700000000000\tMom\tget milk")
        assertEquals(1, back.size)
        assertEquals("77", back[0].id)
        assertEquals("Mom", back[0].name)
        assertEquals("get milk", back[0].message)
        assertFalse(back[0].notified)
    }

    @Test
    fun oneBrokenLineCostsOnlyThatLine() {
        val text = "1\t1700000000000\tMom\tget milk\n" +
            "not a record\n" +
            "2\tnonsense\tDad\tcall back\n" +
            "3\t1700000000001\tDad\tcall back"
        val back = ScheduledSends.decode(text)
        assertEquals(2, back.size)
        assertEquals("Mom", back[0].name)
        assertEquals("Dad", back[1].name)
        assertEquals("call back", back[1].message)
    }

    @Test
    fun anUnknownEscapeIsKeptAsWritten() {
        assertEquals("a\\qb", ScheduledSends.unesc("a\\qb"))
    }

    @Test
    fun anEmptyMessageIsNotAStoreOfNothing() {
        // a record with no message is not a scheduled text
        assertEquals(0, ScheduledSends.decode("1\t1700000000000\tMom\t").size)
    }
}
