package org.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.124 "A conversation that could not be read is not a new chat".
 *
 * [ChatStore.parse] answers null for two different situations - there is no
 * conversation, and there is one that did not parse - and the open path used to
 * read that null as "there is no conversation here", so a damaged file opened
 * as a brand new chat with nothing said.
 *
 * These pin the half of the answer a local unit test can actually reach: text
 * that is not a JSON object is null, never a throw. That is not a stylistic
 * point - [ChatStore.isObject] exists precisely so this half needs no JSON
 * runtime, because a local test has none: org.json ships inside android.jar and
 * Gradle replaces it with a mockable jar whose every method throws.
 *
 * The tolerant half - one bad message row costs that row only - is unchanged
 * from v9.33.101 and is pinned by verify_patches, which reads the source.
 */
class ChatStoreParseTest {

    @Test
    fun anEmptyFileIsNotAConversation() {
        assertNull(ChatStore.parse("", "abc"))
        assertNull(ChatStore.parse("   ", "abc"))
        assertNull(ChatStore.parse("\n\n", "abc"))
    }

    @Test
    fun anArrayIsNotAConversation() {
        assertNull(ChatStore.parse("[]", "abc"))
        assertNull(ChatStore.parse("[{\"r\":\"u\",\"t\":\"hi\"}]", "abc"))
    }

    @Test
    fun theLiteralNullIsNotAConversation() {
        assertNull(ChatStore.parse("null", "abc"))
        assertNull(ChatStore.parse("true", "abc"))
        assertNull(ChatStore.parse("0", "abc"))
    }

    @Test
    fun plainTextIsNotAConversation() {
        assertNull(ChatStore.parse("not json at all", "abc"))
        // an object with no messages is a CONVERSATION (an empty one) - the
        // gate must not reject it, so this is the truncated form instead
        assertNull(ChatStore.parse("{\"r\":\"u\",\"t\":\"hi\"", "abc"))
    }

    @Test
    fun aTruncatedFileIsNotAConversation() {
        // the shape a kill mid-write left behind before v9.33.90
        assertNull(ChatStore.parse("{\"id\":\"abc\",\"messages\":[{\"r\":\"u\",\"t\":\"hel", "abc"))
    }

    @Test
    fun halfATagIsNotAConversation() {
        assertNull(ChatStore.parse("{", "abc"))
        assertNull(ChatStore.parse("}", "abc"))
        assertNull(ChatStore.parse("}{", "abc"))
    }

    @Test
    fun nothingAboveEverThrows() {
        // the whole point: a damaged file must not be able to throw out of the
        // open path. If any of these threw, this test would fail, not pass.
        val junk = listOf("", " ", "[]", "null", "true", "0", "{\"a\":", "}{",
                          "{\"messages\":[", "not a conversation", "\u0000")
        for (j in junk) assertNull(j, ChatStore.parse(j, "abc"))
    }

    @Test
    fun aShapedObjectIsAcceptedByTheGate() {
        // the gate is not the parse - it must say yes to anything object-shaped
        // and leave the rest to the parse
        assertTrue(ChatStore.isObject("{}"))
        assertTrue(ChatStore.isObject("  {\"id\":\"abc\"}  "))
        assertTrue(ChatStore.isObject("{\n \"messages\": []\n}"))
    }

    @Test
    fun aShapelessStringIsRejectedByTheGate() {
        assertFalse(ChatStore.isObject(""))
        assertFalse(ChatStore.isObject(" "))
        assertFalse(ChatStore.isObject("{"))
        assertFalse(ChatStore.isObject("[]"))
        assertFalse(ChatStore.isObject("null"))
        assertFalse(ChatStore.isObject("not json"))
    }
}
