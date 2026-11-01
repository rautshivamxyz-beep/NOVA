package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.94: the Time Machine's reply order is relevance, not collection
 * order. The collection order is fixed (notifications, chats, notes,
 * promises, expiry) and the reply used to be cut straight from it, so a
 * phone that had logged a handful of notifications mentioning one query word
 * could never show the chat message that held both.
 *
 * [NcieTimeMachine.rank] and [NcieTimeMachine.score] are pure list work, so
 * they are pinned here with no Android and no IO.
 */
class NcieTimeMachineTest {

    @Test
    fun aHitWithBothWordsOutranksOneWithOne() {
        val hits = listOf(
            "notif" to "package number 4471",
            "chat" to "the tracking number is 4471"
        )
        val out = NcieTimeMachine.rank(hits, listOf("tracking", "number"), 8)
        assertEquals("chat", out[0].first)
        assertEquals("notif", out[1].first)
    }

    @Test
    fun aChatHitIsNotBuriedByANotificationFlood() {
        // the exact failure: eight notifications that each hold only "number"
        val hits = ArrayList<Pair<String, String>>()
        for (i in 1..8) hits.add("notif" to "delivery number $i")
        hits.add("chat" to "the tracking number for the parcel is 4471")
        val out = NcieTimeMachine.rank(hits, listOf("tracking", "number"), 8)
        assertEquals("chat", out[0].first)
        assertTrue(out[0].second.contains("tracking"))
    }

    @Test
    fun tiesKeepTheCollectionOrder() {
        val hits = listOf(
            "notif" to "alpha here",
            "chat" to "alpha there",
            "notes: a.txt" to "alpha everywhere"
        )
        val out = NcieTimeMachine.rank(hits, listOf("alpha"), 8)
        assertEquals(listOf("notif", "chat", "notes: a.txt"), out.map { it.first })
    }

    @Test
    fun theLimitIsRespected() {
        val hits = (1..10).map { "notif" to "item $it" }
        assertEquals(3, NcieTimeMachine.rank(hits, listOf("item"), 3).size)
        assertTrue(NcieTimeMachine.rank(hits, listOf("item"), 0).isEmpty())
    }

    @Test
    fun aHitThatHoldsNoTermSinksButIsNotLost() {
        val hits = listOf("notif" to "unrelated line", "chat" to "the word number")
        val out = NcieTimeMachine.rank(hits, listOf("number"), 8)
        assertEquals("chat", out[0].first)
        assertEquals(2, out.size)
    }

    @Test
    fun aRepeatedTermIsCountedOnce() {
        // "number number number" must not outrank a hit with two DIFFERENT words
        assertEquals(1, NcieTimeMachine.score("number number number", listOf("number")))
        assertEquals(2, NcieTimeMachine.score("tracking number", listOf("tracking", "number")))
    }

    @Test
    fun theMatchIsCaseInsensitive() {
        assertEquals(1, NcieTimeMachine.score("Tracking 4471", listOf("tracking")))
    }
}
