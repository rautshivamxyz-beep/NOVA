package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.124 "A conversation that could not be read is not a new chat".
 *
 * [ChatStore.parse] is the pure half of opening a conversation. It answers null
 * for text that is not a conversation at all - and that null used to be read as
 * "there is no conversation here", so a damaged file opened as a brand new chat
 * with nothing said. These pin the tolerant half (one bad row costs one row)
 * and the strict half (garbage is null, never a throw).
 */
class ChatStoreParseTest {

    private fun json(vararg rows: String, name: String = "Physics") =
        "{\"id\":\"abc\",\"name\":\"" + name + "\",\"created\":1,\"updated\":2," +
            "\"messages\":[" + rows.joinToString(",") + "]}"

    @Test
    fun aNormalConversationParses() {
        val c = ChatStore.parse(json(
            "{\"r\":\"u\",\"t\":\"what is a mole\",\"d\":true}",
            "{\"r\":\"a\",\"t\":\"a unit of amount\",\"d\":true}"
        ), "abc")!!
        assertEquals("abc", c.id)
        assertEquals("Physics", c.name)
        assertEquals(2, c.messages.size)
        assertEquals(Role.USER, c.messages[0].role)
        assertEquals(Role.ASSISTANT, c.messages[1].role)
        assertEquals("what is a mole", c.messages[0].text)
    }

    @Test
    fun oneBadRowCostsOnlyThatRow() {
        // v9.33.101's rule, one level up
        val c = ChatStore.parse(json(
            "{\"r\":\"u\",\"t\":\"first\"}",
            "42",
            "\"a bare string\"",
            "{\"r\":\"u\",\"t\":\"last\"}"
        ), "abc")!!
        assertEquals(2, c.messages.size)
        assertEquals("first", c.messages[0].text)
        assertEquals("last", c.messages[1].text)
    }

    @Test
    fun everyMessageReadFromDiskIsDone() {
        // v9.29.4: a mid-stream save must not reload as "generating"
        val c = ChatStore.parse(json("{\"r\":\"a\",\"t\":\"half a sen\",\"d\":false}"), "abc")!!
        assertTrue(c.messages[0].done)
    }

    @Test
    fun aConversationWithNoMessagesStillParses() {
        val c = ChatStore.parse(json(), "abc")!!
        assertEquals(0, c.messages.size)
        assertEquals("Physics", c.name)
    }

    @Test
    fun aMissingNameFallsBackToTheDefault() {
        val c = ChatStore.parse("{\"id\":\"abc\",\"messages\":[]}", "abc")!!
        assertEquals("New chat", c.name)
    }

    @Test
    fun aMissingIdFallsBackToTheFileName() {
        val c = ChatStore.parse("{\"messages\":[]}", "from-the-file")!!
        assertEquals("from-the-file", c.id)
    }

    @Test
    fun textThatIsNotAConversationIsNullNotAThrow() {
        // this is the answer that used to be indistinguishable from "no chat"
        assertNull(ChatStore.parse("", "abc"))
        assertNull(ChatStore.parse("not json at all", "abc"))
        assertNull(ChatStore.parse("[]", "abc"))
        assertNull(ChatStore.parse("null", "abc"))
        assertNull(ChatStore.parse("{\"messages\":[", "abc"))
    }

    @Test
    fun aTruncatedFileIsNull() {
        // the shape a kill mid-write used to leave behind before v9.33.90
        assertNull(ChatStore.parse("{\"id\":\"abc\",\"messages\":[{\"r\":\"u\",\"t\":\"hel", "abc"))
    }

    @Test
    fun aReadableConversationIsNotNull() {
        assertNotNull(ChatStore.parse(json(), "abc"))
    }

    @Test
    fun anEmptyNameIsKeptAsWritten() {
        // optString returns the default only when the key is absent, and the
        // auto-name in save() replaces "New chat" - do not resurrect it here
        val c = ChatStore.parse(json(name = ""), "abc")!!
        assertFalse(c.name == "New chat")
    }
}
