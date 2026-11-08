#!/usr/bin/env python3
"""v9.33.124 "A conversation that could not be read is not a new chat".

ChatStore.load answers null for two completely different situations: the file
is not there, and the file is there and could not be read. onCreate then does

    ChatStore.load(this, settings.currentChatId) ?: ChatStore.newChat()
    settings.currentChatId = currentChat.id

so a damaged conversation opened as a brand new chat, the id was repointed at
it, and the user was told nothing at all. The conversation is the most precious
state NOVA owns and it is the one store the v9.33.119/121/123 sweep did not
reach: the file is not deleted (the new chat has its own id), but it is
invisible - loadMeta skips it too, so it is not in the chat list either - and
from the inside the app looks exactly as if every message had been thrown away.

The parse moves into a pure function ([ChatStore.parse]) so the rule is pinned
by a JVM test instead of a screen, [load] is that function over the file, and
[ChatStore.unreadable] answers the question [load] cannot: is there something
there to lose? The open path asks it, and says so when the answer is yes.

Self-verifying: every anchor must match exactly once, the generated verifier is
compiled, and the finished tree is checked for the new markers before the
script exits 0. Run from the repo root (or pass it): python3 tmp/patch_v933124.py .
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = os.path.join(ROOT, "android/app/src/main/java/org/nova/")
TESTDIR = os.path.join(ROOT, "android/app/src/test/java/org/nova/")
RES = os.path.join(ROOT, "android/app/src/main/res/")

fails = []


def check(name, cond):
    print(("PASS  " if cond else "FAIL  ") + name)
    if not cond:
        fails.append(name)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def patch(path, pairs):
    text = read(path)
    for old, new in pairs:
        n = text.count(old)
        if n != 1:
            print("FATAL: anchor matched %d times in %s:\n%s" % (n, path, old))
            sys.exit(1)
        text = text.replace(old, new)
    write(path, text)
    return text


CHAT = BASE + "ChatStore.kt"
MAIN = BASE + "MainActivity.kt"
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")
NEWTEST = os.path.join(TESTDIR, "ChatStoreParseTest.kt")
STR = os.path.join(RES, "values/strings.xml")
STR_HI = os.path.join(RES, "values-hi/strings.xml")

# ------------------------------------------------------------------ the store
OLD_LOAD = '''    fun load(context: Context, id: String): Chat? = try {
        val f = File(dir(context), id + ".json")
        if (!f.exists()) return null
        val json = JSONObject(f.readText())
        val messages = mutableListOf<Msg>()
        val arr = json.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            // v9.33.101: one malformed row used to throw straight out of this
            // loop and take the WHOLE conversation with it - load() catches,
            // returns null, and the chat is simply gone. A row that is not an
            // object is skipped; every other message still opens.
            val o = arr.optJSONObject(i) ?: continue
            messages.add(Msg(
                if (o.optString("r") == "u") Role.USER else Role.ASSISTANT,
                o.optString("t"),
                // v9.29.4: a message read from disk is NEVER "generating".
                // A mid-stream save (done=false) used to reload as a
                // permanent animated bubble - the "stuck on generating"
                // report. History is always done.
                true
            ))
        }
        Chat(
            id = json.optString("id", id),
            name = json.optString("name", NEW_CHAT_NAME),
            createdAt = json.optLong("created", 0L),
            updatedAt = json.optLong("updated", 0L),
            messages = messages
        )
    } catch (e: Exception) {
        NovaLog.quiet("ChatStore", e)
        null
    }
'''

NEW_LOAD = '''    /**
     * v9.33.124 "A conversation that could not be read is not a new chat": the
     * conversation in [text], or null when [text] is not one.
     *
     * v9.33.101 made one malformed message ROW cost that row only. A malformed
     * top-level object still cost the whole conversation, because the parse
     * lived inside [load] and [load] answers null - so the caller could not
     * tell "there is no conversation here" from "there is one and it did not
     * parse". Pure, so a JVM test can pin both halves of that answer.
     */
    fun parse(text: String, id: String): Chat? = try {
        val json = JSONObject(text)
        val messages = mutableListOf<Msg>()
        val arr = json.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            // v9.33.101: one malformed row used to throw straight out of this
            // loop and take the WHOLE conversation with it. A row that is not
            // an object is skipped; every other message still opens.
            val o = arr.optJSONObject(i) ?: continue
            messages.add(Msg(
                if (o.optString("r") == "u") Role.USER else Role.ASSISTANT,
                o.optString("t"),
                // v9.29.4: a message read from disk is NEVER "generating".
                // A mid-stream save (done=false) used to reload as a
                // permanent animated bubble - the "stuck on generating"
                // report. History is always done.
                true
            ))
        }
        Chat(
            id = json.optString("id", id),
            name = json.optString("name", NEW_CHAT_NAME),
            createdAt = json.optLong("created", 0L),
            updatedAt = json.optLong("updated", 0L),
            messages = messages
        )
    } catch (e: Exception) {
        NovaLog.quiet("ChatStore", e)
        null
    }

    fun load(context: Context, id: String): Chat? = try {
        val f = File(dir(context), id + ".json")
        if (!f.exists()) return null
        parse(f.readText(), id)
    } catch (e: Exception) {
        NovaLog.quiet("ChatStore", e)
        null
    }

    /**
     * v9.33.124: true when the conversation file is THERE and could not be
     * read - the question [load] cannot answer, because it says null for a
     * missing file too.
     *
     * The open path used to take that null and start a brand new chat, with
     * nothing said. The file was never deleted (the new chat has its own id),
     * but the chat list skips it as well, so from the inside the app looked
     * exactly as if every message had been thrown away.
     */
    fun unreadable(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        val f = File(dir(context), id + ".json")
        if (!f.exists()) return false
        return try { parse(f.readText(), id) == null }
        catch (e: Exception) { NovaLog.quiet("ChatStore", e); true }
    }
'''

chat = patch(CHAT, [(OLD_LOAD, NEW_LOAD)])

# ------------------------------------------------------------------- the open
OLD_OPEN = '''        currentChat = if (settings.currentChatId.isNotBlank()) {
            ChatStore.load(this, settings.currentChatId) ?: ChatStore.newChat()
        } else ChatStore.newChat()
        settings.currentChatId = currentChat.id
'''

NEW_OPEN = '''        // v9.33.124 "A conversation that could not be read is not a new chat":
        // load answers null for a damaged file exactly as it does for a missing
        // one, so the last conversation opened as a brand new chat and the user
        // was told nothing. The file is not touched - the new chat gets its own
        // id - so say that instead of letting it look like a deletion.
        val reopenId = settings.currentChatId
        currentChat = if (reopenId.isNotBlank()) {
            val reopened = ChatStore.load(this, reopenId)
            if (reopened == null && ChatStore.unreadable(this, reopenId))
                toast(getString(R.string.chat_unreadable))
            reopened ?: ChatStore.newChat()
        } else ChatStore.newChat()
        settings.currentChatId = currentChat.id
'''

main = patch(MAIN, [(OLD_OPEN, NEW_OPEN)])

# ------------------------------------------------------------------ the words
EN_OLD = ('    <string name="remote_private_off">Notes and documents will be sent to that machine</string>\n'
          '</resources>\n')
EN_NEW = ('    <string name="remote_private_off">Notes and documents will be sent to that machine</string>\n'
          '    <string name="chat_unreadable">I could not read that conversation — its file is still on your '
          'phone, untouched. Opening a new chat.</string>\n'
          '</resources>\n')
hi_old = ('    <string name="remote_private_off">नोट्स और दस्तावेज़ उस मशीन पर भेजे जाएँगे</string>\n'
          '</resources>\n')
hi_new = ('    <string name="remote_private_off">नोट्स और दस्तावेज़ उस मशीन पर भेजे जाएँगे</string>\n'
          '    <string name="chat_unreadable">यह बातचीत पढ़ी नहीं जा सकी — इसकी फ़ाइल आपके फ़ोन पर सुरक्षित है, '
          'छेड़ी नहीं गई। नई बातचीत खोली जा रही है।</string>\n'
          '</resources>\n')
patch(STR, [(EN_OLD, EN_NEW)])
patch(STR_HI, [(hi_old, hi_new)])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 291") != 1 or gradle.count("versionName '9.33.123'") != 1:
    print("FATAL: build.gradle is not at 9.33.123 (291)")
    sys.exit(1)
gradle = gradle.replace("versionCode 291", "versionCode 292")
gradle = gradle.replace("versionName '9.33.123'", "versionName '9.33.124'")
write(GRADLE, gradle)

# ------------------------------------------------------------- unit test
NEW_TEST = '''package org.nova

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
        "{\\"id\\":\\"abc\\",\\"name\\":\\"" + name + "\\",\\"created\\":1,\\"updated\\":2," +
            "\\"messages\\":[" + rows.joinToString(",") + "]}"

    @Test
    fun aNormalConversationParses() {
        val c = ChatStore.parse(json(
            "{\\"r\\":\\"u\\",\\"t\\":\\"what is a mole\\",\\"d\\":true}",
            "{\\"r\\":\\"a\\",\\"t\\":\\"a unit of amount\\",\\"d\\":true}"
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
            "{\\"r\\":\\"u\\",\\"t\\":\\"first\\"}",
            "42",
            "\\"a bare string\\"",
            "{\\"r\\":\\"u\\",\\"t\\":\\"last\\"}"
        ), "abc")!!
        assertEquals(2, c.messages.size)
        assertEquals("first", c.messages[0].text)
        assertEquals("last", c.messages[1].text)
    }

    @Test
    fun everyMessageReadFromDiskIsDone() {
        // v9.29.4: a mid-stream save must not reload as "generating"
        val c = ChatStore.parse(json("{\\"r\\":\\"a\\",\\"t\\":\\"half a sen\\",\\"d\\":false}"), "abc")!!
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
        val c = ChatStore.parse("{\\"id\\":\\"abc\\",\\"messages\\":[]}", "abc")!!
        assertEquals("New chat", c.name)
    }

    @Test
    fun aMissingIdFallsBackToTheFileName() {
        val c = ChatStore.parse("{\\"messages\\":[]}", "from-the-file")!!
        assertEquals("from-the-file", c.id)
    }

    @Test
    fun textThatIsNotAConversationIsNullNotAThrow() {
        // this is the answer that used to be indistinguishable from "no chat"
        assertNull(ChatStore.parse("", "abc"))
        assertNull(ChatStore.parse("not json at all", "abc"))
        assertNull(ChatStore.parse("[]", "abc"))
        assertNull(ChatStore.parse("null", "abc"))
        assertNull(ChatStore.parse("{\\"messages\\":[", "abc"))
    }

    @Test
    fun aTruncatedFileIsNull() {
        // the shape a kill mid-write used to leave behind before v9.33.90
        assertNull(ChatStore.parse("{\\"id\\":\\"abc\\",\\"messages\\":[{\\"r\\":\\"u\\",\\"t\\":\\"hel", "abc"))
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
'''

if os.path.exists(NEWTEST):
    print("FATAL: ChatStoreParseTest.kt already exists")
    sys.exit(1)
write(NEWTEST, NEW_TEST)

# ------------------------------------------------- teach the verifier
OLD_V123 = '''check("v9.33.123: version is at least 9.33.123 (291)",
      version_at_least("9.33.123", 291))
'''

NEW_V123 = '''check("v9.33.123: version is at least 9.33.123 (291)",
      version_at_least("9.33.123", 291))

# v9.33.124 "A conversation that could not be read is not a new chat": the
# conversation store was the one the sweep never reached. load answers null for
# a damaged file exactly as for a missing one, so the last conversation opened
# as a brand new chat with nothing said - and the chat list skips it too.
_cs124 = load("ChatStore.kt")
check("v9.33.124: the conversation parse is a pure function",
      _cs124.count("fun parse(text: String, id: String): Chat?") == 1 and
      _cs124.count("parse(f.readText(), id)") == 2)
check("v9.33.124: load is that function over the file",
      _cs124.count("fun load(context: Context, id: String): Chat?") == 1)
check("v9.33.124: the store can tell a damaged file from a missing one",
      _cs124.count("fun unreadable(context: Context, id: String): Boolean") == 1 and
      _cs124.count("if (!f.exists()) return false") >= 1)
check("v9.33.124: the open path asks before it opens a fresh chat",
      ma.count("ChatStore.unreadable(this, reopenId)") == 1 and
      ma.count("toast(getString(R.string.chat_unreadable))") == 1)
check("v9.33.124: the words exist in both languages",
      open(os.path.join(ROOT, "android/app/src/main/res/values/strings.xml"),
           encoding="utf-8").read().count('name="chat_unreadable"') == 1 and
      open(os.path.join(ROOT, "android/app/src/main/res/values-hi/strings.xml"),
           encoding="utf-8").read().count('name="chat_unreadable"') == 1)
check("v9.33.124: the parse is pinned by unit tests",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ChatStoreParseTest.kt"),
           encoding="utf-8").read().count("@Test") >= 10)
check("v9.33.124: version is at least 9.33.124 (292)",
      version_at_least("9.33.124", 292))
'''

ver = read(VERIFY)
if ver.count(OLD_V123) != 1:
    print("FATAL: the v9.33.123 tail anchor did not match exactly once")
    sys.exit(1)
write(VERIFY, ver.replace(OLD_V123, NEW_V123))
# v9.33.123: a patch script that writes the verifier can write a SyntaxError
# into it - compile what was written before trusting it.
compile(read(VERIFY), VERIFY, "exec")

# ---------------------------------------------------------------- verify
print("")
check("ChatStore: the parse is pure", "fun parse(text: String, id: String): Chat? = try {" in chat)
check("ChatStore: load goes through it", "parse(f.readText(), id)" in chat)
check("ChatStore: unreadable exists", "fun unreadable(context: Context, id: String): Boolean {" in chat)
check("ChatStore: an absent file is not unreadable", "if (!f.exists()) return false" in chat)
check("MainActivity: the old id is captured before it is repointed",
      "val reopenId = settings.currentChatId" in main)
check("MainActivity: a damaged conversation is reported", "R.string.chat_unreadable" in main)
check("version is 9.33.124 (292)",
      "versionName '9.33.124'" in gradle and "versionCode 292" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.124", 292)' in read(VERIFY))
check("the unit test exists and pins the parse", os.path.exists(NEWTEST))
check("the words are in strings.xml", 'name="chat_unreadable"' in read(STR))
check("the words are in strings-hi.xml", 'name="chat_unreadable"' in read(STR_HI))


def balanced(text):
    """Braces and parens outside comments and string literals - the same scan
    verify_patches.py runs, because a naive count is fooled by both."""
    i, n = 0, len(text)
    braces = parens = 0
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        elif c == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
        elif c == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
        else:
            if c == "{":
                braces += 1
            elif c == "}":
                braces -= 1
            elif c == "(":
                parens += 1
            elif c == ")":
                parens -= 1
            i += 1
    return braces == 0 and parens == 0


for name, text in [("ChatStore.kt", chat), ("MainActivity.kt", main),
                   ("ChatStoreParseTest.kt", read(NEWTEST))]:
    check(name + ": structure is balanced", balanced(text))
    bad = []
    for m in re.finditer(r'"(?:[^"\\]|\\.)*"', text):
        for e in re.finditer(r"\\(.)", m.group(0)):
            if e.group(1) not in "tbnr\"'\\$su":
                bad.append(e.group(1))
    check(name + ": no invalid string escapes", not bad)

print("")
if fails:
    print("PATCH FAILED: %d problem(s)" % len(fails))
    for x in fails:
        print(" - " + x)
    sys.exit(1)
print("PATCH OK: v9.33.124 applied and self-verified")
