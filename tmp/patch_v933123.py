#!/usr/bin/env python3
"""v9.33.123 "The scheduled text is the whole text".

The scheduled-texts store (filesDir/pending_sends.txt) is one tab-separated
line per send, with the message written raw. Two doors, both silent:

1. THE STORE THE SWEEP MISSED. v9.33.119 gave every store that rewrites what
   it read the difference between "nothing there" and "could not be read",
   and v9.33.121 finished the sweep over the notes and the deck. The
   scheduled texts were not converted: load() returns an empty list for an
   unreadable file, and add() writes that list straight back. One bad read
   and "text mom at 6pm get milk" deletes every scheduled text the user had -
   and NOVA answers "Scheduled: get milk to mom at 6:00 PM", which is true
   about the new one and a lie about the rest.

2. THE MESSAGE IS NOT ONE LINE. A two-line draft (or anything pasted with a
   newline in it) is written as two lines, and the reader keeps only the
   first: the text that actually sends is silently cut at the first newline,
   and NOVA still reports it "sent" with the shortened text. A TAB in the
   message is worse - it is the field separator, so it eats the rest of the
   message and its remainder becomes the "notified" flag.

So: the store is read through NcieFiles.readOrNull (null = there is something
to lose), add() refuses to write when the read failed, and the fields are
escaped on the way out and unescaped on the way in. Old lines carry no
backslash and decode exactly as before - the one change is that a message
which already contained a literal backslash-n now shows a newline, which is
the shape the writer produces anyway.

Self-verifying: every anchor must match exactly once, and the finished tree is
checked for the new markers before the script exits 0. Run from the repo root
(or pass it): python3 tmp/patch_v933123.py .
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


SEND = BASE + "SendReceiver.kt"
MAIN = BASE + "MainActivity.kt"
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")
NEWTEST = os.path.join(TESTDIR, "ScheduledSendsTest.kt")

# ------------------------------------------------------------------ store
OLD_STORE = '''    fun load(ctx: Context): MutableList<Send> {
        val out = mutableListOf<Send>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                for (line in f.readText().split('\\n')) {
                    // v9.13.0: limit 5 - an optional 5th field ("n") marks
                    // a background-notified send. Old 4-field lines parse
                    // unchanged (notified = false).
                    val p = line.trim('\\r').split('\\t', limit = 5)
                    val ms = p.getOrNull(1)?.toLongOrNull()
                    if (p.size >= 4 && ms != null && p[2].isNotEmpty() && p[3].isNotEmpty())
                        out.add(Send(p[0], ms, p[2], p[3], p.getOrNull(4) == "n"))
                }
            }
        } catch (e: Exception) { NovaLog.quiet("SendReceiver", e) }
        return out
    }

    private fun save(ctx: Context, list: List<Send>) {
        try {
            val f = file(ctx)
            // v9.33.98: a scheduled text that is lost here is lost silently
            NcieFiles.write(f, list.joinToString("\\n") { s ->
                "${s.id}\\t${s.millis}\\t${s.name}\\t${s.message}" +
                    (if (s.notified) "\\tn" else "")
            })
        } catch (e: Exception) { NovaLog.quiet("SendReceiver", e) }
    }

    fun add(ctx: Context, atMillis: Long, name: String, message: String) {
        val all = load(ctx)
        all.add(Send(System.currentTimeMillis().toString(), atMillis, name, message))
        save(ctx, all)
        armNext(ctx)
    }
'''

NEW_STORE = '''    /**
     * v9.33.123 "The scheduled text is the whole text": the store, or null
     * when the file is there and could not be read.
     *
     * v9.33.119 gave every store that rewrites what it read the difference
     * between "nothing there" and "could not be read"; v9.33.121 finished the
     * sweep over the notes and the deck. This one was missed. [load] answers
     * an empty list either way, and [add] writes that list straight back - so
     * one unreadable read deleted every scheduled text the user had, and NOVA
     * still answered "Scheduled: ... to ...", true about the new one and a
     * lie about the rest.
     */
    fun loadOrNull(ctx: Context): MutableList<Send>? {
        val text = NcieFiles.readOrNull(file(ctx)) ?: return null
        return decode(text)
    }

    fun load(ctx: Context): MutableList<Send> = loadOrNull(ctx) ?: mutableListOf()

    /**
     * v9.33.123: the file is one tab-separated line per send, and the message
     * used to be written into it raw. A two-line draft - or anything pasted
     * with a newline in it - became two lines, and the reader kept only the
     * first: the text that actually sent was silently cut at the first
     * newline, and NOVA still reported it "sent". A TAB was worse: it is the
     * field separator, so it ate the rest of the message and the remainder
     * became the "notified" flag.
     *
     * [esc] on the way out, [unesc] on the way in. A line written before this
     * release carries no backslash and decodes exactly as it did.
     */
    internal fun esc(s: String): String = s
        .replace("\\\\", "\\\\\\\\")
        .replace("\\t", "\\\\t")
        .replace("\\n", "\\\\n")
        .replace("\\r", "\\\\r")

    /** The inverse of [esc]. An unknown escape is kept as written. */
    internal fun unesc(s: String): String {
        if (s.indexOf('\\\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\\\' -> { sb.append('\\\\'); i += 2 }
                    't' -> { sb.append('\\t'); i += 2 }
                    'n' -> { sb.append('\\n'); i += 2 }
                    'r' -> { sb.append('\\r'); i += 2 }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

    /**
     * The lines of the store, parsed. One broken line costs that line only -
     * the same rule v9.33.101 gave every JSON loader in the app.
     */
    internal fun decode(text: String): MutableList<Send> {
        val out = mutableListOf<Send>()
        for (line in text.split('\\n')) {
            // v9.13.0: limit 5 - an optional 5th field ("n") marks a
            // background-notified send. Old 4-field lines parse unchanged.
            val p = line.trim('\\r').split('\\t', limit = 5)
            val ms = p.getOrNull(1)?.toLongOrNull()
            if (p.size >= 4 && ms != null && p[2].isNotEmpty() && p[3].isNotEmpty())
                out.add(Send(unesc(p[0]), ms, unesc(p[2]), unesc(p[3]), p.getOrNull(4) == "n"))
        }
        return out
    }

    /** The file's exact text for [list] - the inverse of [decode]. */
    internal fun encode(list: List<Send>): String = list.joinToString("\\n") { s ->
        esc(s.id) + "\\t" + s.millis + "\\t" + esc(s.name) + "\\t" + esc(s.message) +
            (if (s.notified) "\\tn" else "")
    }

    private fun save(ctx: Context, list: List<Send>): Boolean = try {
        // v9.33.98: a scheduled text that is lost here is lost silently
        NcieFiles.write(file(ctx), encode(list))
    } catch (e: Exception) { NovaLog.quiet("SendReceiver", e); false }

    /**
     * True when the send is on disk. False when the store could not be read -
     * saving then would rewrite the file from nothing and delete every other
     * scheduled text the user had, so nothing is written at all.
     */
    fun add(ctx: Context, atMillis: Long, name: String, message: String): Boolean {
        val all = loadOrNull(ctx) ?: return false
        all.add(Send(System.currentTimeMillis().toString(), atMillis, name, message))
        if (!save(ctx, all)) return false
        armNext(ctx)
        return true
    }
'''

send = patch(SEND, [(OLD_STORE, NEW_STORE)])

# ---------------------------------------------------------------- fireDue
OLD_FIRE = '''    fun fireDue(ctx: Context, foreground: Boolean = false) {
        val now = System.currentTimeMillis()
        val all = load(ctx)
        if (all.isEmpty()) return
'''
NEW_FIRE = '''    fun fireDue(ctx: Context, foreground: Boolean = false) {
        val now = System.currentTimeMillis()
        // v9.33.123: an unreadable store is not an empty one - this used to
        // return here too, which was safe, but the save below would have
        // rewritten the file from nothing if the read had only partly failed.
        val all = loadOrNull(ctx) ?: return
        if (all.isEmpty()) return
'''
send = patch(SEND, [(OLD_FIRE, NEW_FIRE)])

# -------------------------------------------------------------- the report
OLD_ADD = '''                        ScheduledSends.add(this, ms, who, msg)
                        val human = java.text.SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault())
                            .format(java.util.Date(ms))
                        chatCommandReply(t,
                            "Scheduled: $msg to $who at $human. Say 'cancel scheduled texts' to undo.")
'''
NEW_ADD = '''                        // v9.33.123: the store refuses to write when it
                        // could not be read - say so instead of reporting a
                        // schedule that did not happen.
                        if (!ScheduledSends.add(this, ms, who, msg)) {
                            chatCommandReply(t,
                                "I couldn't read the scheduled-texts list, so I saved nothing - " +
                                    "your other scheduled texts are untouched. Try again.")
                            return true
                        }
                        val human = java.text.SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault())
                            .format(java.util.Date(ms))
                        chatCommandReply(t,
                            "Scheduled: $msg to $who at $human. Say 'cancel scheduled texts' to undo.")
'''
main = patch(MAIN, [(OLD_ADD, NEW_ADD)])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 290") != 1 or gradle.count("versionName '9.33.122'") != 1:
    print("FATAL: build.gradle is not at 9.33.122 (290)")
    sys.exit(1)
gradle = gradle.replace("versionCode 290", "versionCode 291")
gradle = gradle.replace("versionName '9.33.122'", "versionName '9.33.123'")
write(GRADLE, gradle)

# ------------------------------------------------------------- unit test
NEW_TEST = '''package org.nova

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
        val back = roundTrip(send(message = "get milk\\nand eggs"))
        assertEquals("get milk\\nand eggs", back.message)
    }

    @Test
    fun aMessageWithATabIsNotCutAtTheTab() {
        val back = roundTrip(send(message = "col1\\tcol2"))
        assertEquals("col1\\tcol2", back.message)
    }

    @Test
    fun aTabInTheMessageCannotFlipTheNotifiedFlag() {
        // "n" as the last field is the notified marker - a raw tab let the
        // tail of a message become that field
        val back = roundTrip(send(message = "ship\\tn"))
        assertEquals("ship\\tn", back.message)
        assertFalse(back.notified)
    }

    @Test
    fun aCarriageReturnDoesNotSplitTheLine() {
        val back = roundTrip(send(message = "one\\rtwo"))
        assertEquals("one\\rtwo", back.message)
    }

    @Test
    fun aBackslashSurvives() {
        val back = roundTrip(send(message = "C:\\\\notes\\\\milk"))
        assertEquals("C:\\\\notes\\\\milk", back.message)
    }

    @Test
    fun theNotifiedFlagRoundTrips() {
        assertTrue(roundTrip(send(notified = true)).notified)
        assertFalse(roundTrip(send(notified = false)).notified)
    }

    @Test
    fun theWholeListRoundTrips() {
        val list = listOf(send(id = "a", message = "one\\ntwo"), send(id = "b", notified = true))
        val back = ScheduledSends.decode(ScheduledSends.encode(list))
        assertEquals(2, back.size)
        assertEquals("one\\ntwo", back[0].message)
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
        val back = ScheduledSends.decode("77\\t1700000000000\\tMom\\tget milk")
        assertEquals(1, back.size)
        assertEquals("77", back[0].id)
        assertEquals("Mom", back[0].name)
        assertEquals("get milk", back[0].message)
        assertFalse(back[0].notified)
    }

    @Test
    fun oneBrokenLineCostsOnlyThatLine() {
        val text = "1\\t1700000000000\\tMom\\tget milk\\n" +
            "not a record\\n" +
            "2\\tnonsense\\tDad\\tcall back\\n" +
            "3\\t1700000000001\\tDad\\tcall back"
        val back = ScheduledSends.decode(text)
        assertEquals(2, back.size)
        assertEquals("Mom", back[0].name)
        assertEquals("Dad", back[1].name)
        assertEquals("call back", back[1].message)
    }

    @Test
    fun anUnknownEscapeIsKeptAsWritten() {
        assertEquals("a\\\\qb", ScheduledSends.unesc("a\\\\qb"))
    }

    @Test
    fun anEmptyMessageIsNotAStoreOfNothing() {
        // a record with no message is not a scheduled text
        assertEquals(0, ScheduledSends.decode("1\\t1700000000000\\tMom\\t").size)
    }
}
'''

if os.path.exists(NEWTEST):
    print("FATAL: ScheduledSendsTest.kt already exists")
    sys.exit(1)
write(NEWTEST, NEW_TEST)

# ------------------------------------------------- teach the verifier
OLD_V122 = '''check("v9.33.122: version is at least 9.33.122 (290)",
      version_at_least("9.33.122", 290))
'''

NEW_V122 = '''check("v9.33.122: version is at least 9.33.122 (290)",
      version_at_least("9.33.122", 290))

# v9.33.123 "The scheduled text is the whole text": the scheduled-texts store
# was the one the v9.33.119/121 sweep missed - it answered "empty" for a file
# it could not read and wrote that list straight back, and it wrote the
# message raw into a tab-separated line, so a newline cut the text that sent.
check("v9.33.123: the scheduled-texts store tells unreadable from empty",
      _sr.count("fun loadOrNull(ctx: Context): MutableList<Send>?") == 1 and
      _sr.count("val text = NcieFiles.readOrNull(file(ctx)) ?: return null") == 1)
check("v9.33.123: adding a send refuses to rewrite a store it could not read",
      _sr.count("val all = loadOrNull(ctx) ?: return false") == 1)
check("v9.33.123: firing due sends never rewrites an unreadable store",
            _sr.count("val all = loadOrNull(ctx) ?: return\n") == 1)
check("v9.33.123: the message is escaped into the line and unescaped back",
      _sr.count("internal fun esc(s: String): String") == 1 and
      _sr.count("internal fun unesc(s: String): String") == 1 and
      _sr.count("esc(s.message)") == 1 and
      _sr.count("unesc(p[3])") == 1)
check("v9.33.123: the line format is one pure pair of functions",
      _sr.count("internal fun encode(list: List<Send>): String") == 1 and
      _sr.count("internal fun decode(text: String): MutableList<Send>") == 1)
check("v9.33.123: a schedule that did not save is not reported as scheduled",
      ma.count("if (!ScheduledSends.add(this, ms, who, msg)) {") == 1 and
      ma.count("I couldn't read the scheduled-texts list") == 1)
check("v9.33.123: the round trip is pinned by unit tests",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ScheduledSendsTest.kt"),
           encoding="utf-8").read().count("@Test") >= 12)
check("v9.33.123: version is at least 9.33.123 (291)",
      version_at_least("9.33.123", 291))
'''

ver = read(VERIFY)
if ver.count(OLD_V122) != 1:
    print("FATAL: the v9.33.122 tail anchor did not match exactly once")
    sys.exit(1)
write(VERIFY, ver.replace(OLD_V122, NEW_V122))

# ---------------------------------------------------------------- verify
print("")
check("SendReceiver: the store tells unreadable from empty",
      "fun loadOrNull(ctx: Context): MutableList<Send>?" in send)
check("SendReceiver: read through the one atomic read",
      "NcieFiles.readOrNull(file(ctx)) ?: return null" in send)
check("SendReceiver: add refuses an unreadable store",
      "val all = loadOrNull(ctx) ?: return false" in send)
check("SendReceiver: fireDue refuses an unreadable store",
      "val all = loadOrNull(ctx) ?: return\n" in send)
check("SendReceiver: the message is escaped", "esc(s.message)" in send)
check("SendReceiver: the message is unescaped", "unesc(p[3])" in send)
check("SendReceiver: one pure encode", "internal fun encode(list: List<Send>): String" in send)
check("SendReceiver: one pure decode", "internal fun decode(text: String): MutableList<Send>" in send)
check("SendReceiver: a failed save is not reported as success",
      "if (!save(ctx, all)) return false" in send)
check("MainActivity: an unsaved schedule is not reported as scheduled",
      "if (!ScheduledSends.add(this, ms, who, msg)) {" in main)
check("version is 9.33.123 (291)",
      "versionName '9.33.123'" in gradle and "versionCode 291" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.123", 291)' in read(VERIFY))
check("the unit test exists and pins the round trip", os.path.exists(NEWTEST))


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


for name, text in [("SendReceiver.kt", send), ("MainActivity.kt", main),
                   ("ScheduledSendsTest.kt", read(NEWTEST))]:
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
print("PATCH OK: v9.33.123 applied and self-verified")
