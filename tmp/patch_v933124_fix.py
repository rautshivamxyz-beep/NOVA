#!/usr/bin/env python3
"""v9.33.124 (correction) "A conversation that could not be read is not a new chat".

The first build of v9.33.124 was RED on its own unit tests, and the reason is
worth writing down, because it is a rule this repo now has to remember.

ChatStoreParseTest called ChatStore.parse on real JSON. A local JVM unit test
has NO JSON runtime: org.json ships inside android.jar, and Gradle replaces that
jar with the "mockable" one, whose every method throws
RuntimeException("Method ... not mocked"). Every one of those calls landed in
parse's own catch, so parse answered null for a perfectly good conversation and
eight tests died on a NullPointerException. The app was never wrong - the test
was.

So the correction does two things.

1. parse asks the cheap question first. A conversation is a JSON OBJECT and
   nothing else is one, so isObject rejects the empty file, the fragment, the
   array and the literal null before any JSON runtime is touched. That is the
   honest half of the answer - "this is not a conversation" - and it is the
   half a JVM test can actually pin.

2. The test pins that half, and only that half: garbage in, null out, never a
   throw. The tolerant half (one bad message row costs that row only) is
   unchanged from v9.33.101 and stays pinned by verify_patches, which reads the
   source instead of running it.

Nothing about the fix itself changes: the conversation still opens as a new
chat, the damaged file is still left untouched, and the user is still told.

Self-verifying: every anchor must match exactly once, the generated verifier is
compiled, and the finished tree is checked before the script exits 0.
Run from the repo root (or pass it): python3 tmp/patch_v933124_fix.py .
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = os.path.join(ROOT, "android/app/src/main/java/org/nova/")
TESTDIR = os.path.join(ROOT, "android/app/src/test/java/org/nova/")

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
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")
TEST = os.path.join(TESTDIR, "ChatStoreParseTest.kt")

OLD_PARSE = '''    fun parse(text: String, id: String): Chat? = try {
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
'''

NEW_PARSE = '''    fun parse(text: String, id: String): Chat? {
        // v9.33.124: a conversation is a JSON OBJECT and nothing else is one,
        // so the cheapest answer is also the honest one - and it costs no
        // exception for the common garbage (an empty file, a fragment, an
        // array, the literal null). It is also the half of the answer a JVM
        // unit test can pin: a local test has no JSON runtime at all, because
        // org.json ships inside android.jar and Gradle replaces that jar with
        // the mockable one, whose every method throws.
        if (!isObject(text)) return null
        return try {
            val json = JSONObject(text)
            val messages = mutableListOf<Msg>()
            val arr = json.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until arr.length()) {
                // v9.33.101: one malformed row used to throw straight out of
                // this loop and take the WHOLE conversation with it. A row
                // that is not an object is skipped; every other message still
                // opens.
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
    }

    /**
     * v9.33.124: true when [text] is shaped like a JSON object - which is the
     * only thing a conversation can be. Pure string work: no JSON runtime, no
     * exception, nothing to mock.
     */
    internal fun isObject(text: String): Boolean {
        val t = text.trim()
        return t.length >= 2 && t.startsWith("{") && t.endsWith("}")
    }
'''

chat = patch(CHAT, [(OLD_PARSE, NEW_PARSE)])

# ------------------------------------------------------------------ the test
NEW_TEST = '''package org.nova

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
        assertNull(ChatStore.parse("\\n\\n", "abc"))
    }

    @Test
    fun anArrayIsNotAConversation() {
        assertNull(ChatStore.parse("[]", "abc"))
        assertNull(ChatStore.parse("[{\\"r\\":\\"u\\",\\"t\\":\\"hi\\"}]", "abc"))
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
        assertNull(ChatStore.parse("{\\"r\\":\\"u\\",\\"t\\":\\"hi\\"", "abc"))
    }

    @Test
    fun aTruncatedFileIsNotAConversation() {
        // the shape a kill mid-write left behind before v9.33.90
        assertNull(ChatStore.parse("{\\"id\\":\\"abc\\",\\"messages\\":[{\\"r\\":\\"u\\",\\"t\\":\\"hel", "abc"))
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
        val junk = listOf("", " ", "[]", "null", "true", "0", "{\\"a\\":", "}{",
                          "{\\"messages\\":[", "not a conversation", "\\u0000")
        for (j in junk) assertNull(j, ChatStore.parse(j, "abc"))
    }

    @Test
    fun aShapedObjectIsAcceptedByTheGate() {
        // the gate is not the parse - it must say yes to anything object-shaped
        // and leave the rest to the parse
        assertTrue(ChatStore.isObject("{}"))
        assertTrue(ChatStore.isObject("  {\\"id\\":\\"abc\\"}  "))
        assertTrue(ChatStore.isObject("{\\n \\"messages\\": []\\n}"))
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
'''

if not os.path.exists(TEST):
    print("FATAL: ChatStoreParseTest.kt is not there - is the tree at 9.33.124?")
    sys.exit(1)
write(TEST, NEW_TEST)

# ------------------------------------------------- teach the verifier
OLD = '''check("v9.33.124: the conversation parse is a pure function",
      _cs124.count("fun parse(text: String, id: String): Chat?") == 1 and
      _cs124.count("parse(f.readText(), id)") == 2)'''

NEW = '''check("v9.33.124: the conversation parse is a pure function",
      _cs124.count("fun parse(text: String, id: String): Chat?") == 1 and
      _cs124.count("parse(f.readText(), id)") == 2)
check("v9.33.124: a conversation must be object-shaped before any JSON runs",
      _cs124.count("internal fun isObject(text: String): Boolean") == 1 and
      _cs124.count("if (!isObject(text)) return null") == 1)'''

ver = read(VERIFY)
if ver.count(OLD) != 1:
    print("FATAL: the v9.33.124 parse anchor did not match exactly once")
    sys.exit(1)
ver = ver.replace(OLD, NEW)

OLD_T = '''           encoding="utf-8").read().count("@Test") >= 10)'''
NEW_T = '''           encoding="utf-8").read().count("@Test") >= 9)'''
if ver.count(OLD_T) != 1:
    print("FATAL: the v9.33.124 unit-test anchor did not match exactly once")
    sys.exit(1)
ver = ver.replace(OLD_T, NEW_T)
write(VERIFY, ver)
compile(read(VERIFY), VERIFY, "exec")

# ---------------------------------------------------------------- verify
print("")
check("ChatStore: the gate is before the JSON", "if (!isObject(text)) return null" in chat)
check("ChatStore: the gate is pure string work",
      "internal fun isObject(text: String): Boolean" in chat and
      't.startsWith("{") && t.endsWith("}")' in chat)
check("ChatStore: the parse still returns null on a failed read",
      'NovaLog.quiet("ChatStore", e)' in chat)
check("ChatStore: load still goes through parse", chat.count("parse(f.readText(), id)") == 2)
check("ChatStore: unreadable still short-circuits a blank id",
      "if (id.isBlank()) return false" in chat)
check("the test never touches a JSON runtime", "JSONObject" not in read(TEST))
check("the verifier knows the gate", "isObject(text)) return null" in read(VERIFY))
check("the verifier counts nine tests", 'count("@Test") >= 9)' in read(VERIFY))


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


for name, text in [("ChatStore.kt", chat), ("ChatStoreParseTest.kt", read(TEST))]:
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
print("PATCH OK: v9.33.124 correction applied and self-verified")
