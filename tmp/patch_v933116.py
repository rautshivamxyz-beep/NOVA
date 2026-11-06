#!/usr/bin/env python3
"""v9.33.116 "A repeating reminder is not due all day".

The spoken nudge (NovaNudge.check, every 30 minutes) announced "You have a
reminder due" from this count:

    ReminderStore.load(ctx).count { t -> t.first in 1..now2 }

`t.first` is the row's `at`, and `at` is the reminder's FIRST fire time - the
anchor. Nothing ever advances it: ReminderReceiver re-arms the next
occurrence from `at` and leaves the row alone. So a daily 9am reminder
created three weeks ago has an anchor three weeks in the past, which is
"in 1..now" for ever.

The result: one repeating reminder made NOVA announce, out loud, every 30
minutes, all day, every day, that something was due - while the alarm that
actually carries the reminder had already fired and would not fire again
until tomorrow. The count never went down, so nothing ever stopped it.

What "due" means now lives in one pure function (ReminderStore.isDue): a
repeating reminder is due when its NEXT fire is within the hour, and a
one-shot is due when its time has passed and it is still on disk - which is
the only case the nudge is really for, because an alarm that fired on time
already told the user.

Self-verifying: every anchor must match exactly once, and the finished tree
is checked for the new markers before the script exits 0. Run from the repo
root (or pass it): python3 tmp/patch_v933116.py .
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = os.path.join(ROOT, "android/app/src/main/java/org/nova/")

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


STORE = BASE + "ReminderStore.kt"
JARVIS = BASE + "ncie/android/NovaJarvis.kt"
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")
TEST = os.path.join(ROOT, "android/app/src/test/java/org/nova/ReminderStoreTest.kt")

# ------------------------------------------------------------ the one rule
OLD_NEXTARM_TAIL = '''        val first = org.nova.ncie.android.NcieDeadlines.nextFire(at, rep, now)
        if (first > now) return first
        return first + rep                          // strictly after now, one step
    }
'''

NEW_NEXTARM_TAIL = '''        val first = org.nova.ncie.android.NcieDeadlines.nextFire(at, rep, now)
        if (first > now) return first
        return first + rep                          // strictly after now, one step
    }

    /**
     * v9.33.116 "A repeating reminder is not due all day": is this reminder
     * worth announcing NOW?
     *
     * The spoken nudge counted rows whose `at` was in the past, and `at` is
     * the reminder's FIRST fire - nothing advances it. A daily reminder
     * therefore stayed "due" from the day it was created, for ever, and the
     * nudge said so every 30 minutes all day while the alarm that carries it
     * had already fired and was waiting for tomorrow.
     *
     * A repeat is due when its next fire is within [window] (the nudge's own
     * interval, so it says it once and not on every pass). A one-shot is due
     * when its time has passed and it is still on disk - the alarm never
     * delivered it, which is the only thing the nudge can add to an alarm
     * that fires on time. Pure arithmetic over [now], so a unit test can pin
     * it.
     */
    internal fun isDue(at: Long, rep: Long, now: Long, window: Long): Boolean {
        if (rep <= 0L) return at <= now
        val next = nextArm(at, rep, now) ?: return false
        return next <= now + window
    }
'''

store = patch(STORE, [(OLD_NEXTARM_TAIL, NEW_NEXTARM_TAIL)])

# --------------------------------------------------------------- the nudge
OLD_JARVIS = '''        // reminders that are due
        try {
            val now2 = System.currentTimeMillis()
            val due = org.nova.ReminderStore.load(ctx).count { t -> t.first in 1..now2 }
            if (due > 0) parts.add(if (due == 1) "You have a reminder due." else "You have $due reminders due.")
        } catch (e: Exception) { NovaLog.quiet("NovaJarvis", e) }
'''

NEW_JARVIS = '''        // reminders that are due
        try {
            // v9.33.116 "A repeating reminder is not due all day": this was
            // `t.first in 1..now`, i.e. "the reminder's START time is in the
            // past" - and the start time of a repeating reminder is in the
            // past from the day it is created. One daily reminder therefore
            // had NOVA announce "You have a reminder due" out loud every 30
            // minutes, all day, for ever. The rule lives in ReminderStore
            // now: a repeat is due when its next fire is within the hour, a
            // one-shot when its time passed and no alarm delivered it.
            val now2 = System.currentTimeMillis()
            val due = org.nova.ReminderStore.loadRows(ctx).count {
                org.nova.ReminderStore.isDue(it.at, it.rep, now2, 60L * 60L * 1000L)
            }
            if (due > 0) parts.add(if (due == 1) "You have a reminder due." else "You have $due reminders due.")
        } catch (e: Exception) { NovaLog.quiet("NovaJarvis", e) }
'''

jarvis = patch(JARVIS, [(OLD_JARVIS, NEW_JARVIS)])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 283") != 1 or gradle.count("versionName '9.33.115'") != 1:
    print("FATAL: build.gradle is not at 9.33.115 (283)")
    sys.exit(1)
gradle = gradle.replace("versionCode 283", "versionCode 284")
gradle = gradle.replace("versionName '9.33.115'", "versionName '9.33.116'")
write(GRADLE, gradle)

# ------------------------------------------------------------- unit tests
test = read(TEST)
OLD_TAIL = '''    @Test
    fun aCorruptRepeatIntervalIsNeverArmed() {
        assertNull("a decade is not a repeat interval",
            ReminderStore.nextArm(now - day, Long.MAX_VALUE, now))
        assertNull("a negative interval is not a schedule",
            ReminderStore.nextArm(now - day, -day, now))
    }
}
'''

NEW_TAIL = '''    @Test
    fun aCorruptRepeatIntervalIsNeverArmed() {
        assertNull("a decade is not a repeat interval",
            ReminderStore.nextArm(now - day, Long.MAX_VALUE, now))
        assertNull("a negative interval is not a schedule",
            ReminderStore.nextArm(now - day, -day, now))
    }

    // v9.33.116 "A repeating reminder is not due all day": what the spoken
    // nudge announces. The old rule asked whether the reminder's START time
    // was in the past, which is true for a repeat from the day it is made.

    private val hour = 60L * 60L * 1000L

    @Test
    fun aRepeatIsDueOnlyNearItsNextFire() {
        // yesterday, half an hour later than now -> the next fire is in 30 min
        assertTrue(ReminderStore.isDue(now - day + 30L * 60L * 1000L, day, now, hour))
        // yesterday, five hours later than now -> the next fire is in 5 hours
        assertFalse(ReminderStore.isDue(now - day + 5 * hour, day, now, hour))
    }

    @Test
    fun aRepeatIsNotDueForEverBecauseItStartedInThePast() {
        // three weeks in, one fire a day: the anchor is ancient, the next
        // fire is not, and only the next fire decides
        assertFalse(ReminderStore.isDue(now - 21 * day, day, now, hour))
    }

    @Test
    fun aOneShotIsDueOnlyOnceItsTimeHasPassed() {
        assertTrue("a missed one-shot is overdue",
            ReminderStore.isDue(now - 2 * hour, 0L, now, hour))
        assertFalse("a future one-shot is not due yet",
            ReminderStore.isDue(now + 2 * hour, 0L, now, hour))
    }

    @Test
    fun aCorruptIntervalIsNeverAnnounced() {
        assertFalse(ReminderStore.isDue(now - day, Long.MAX_VALUE, now, hour))
    }
}
'''

if test.count(OLD_TAIL) != 1:
    print("FATAL: the ReminderStoreTest tail anchor did not match exactly once")
    sys.exit(1)
test = test.replace(OLD_TAIL, NEW_TAIL)
if "import org.junit.Assert.assertFalse\n" not in test:
    test = test.replace("import org.junit.Assert.assertEquals\n",
                        "import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\n")
write(TEST, test)

# ------------------------------------------------- teach the verifier
OLD_V115 = '''check("v9.33.115: version is at least 9.33.115 (283)",
      version_at_least("9.33.115", 283))
'''

NEW_V115 = '''check("v9.33.115: version is at least 9.33.115 (283)",
      version_at_least("9.33.115", 283))

# v9.33.116 "A repeating reminder is not due all day": the spoken nudge
# counted rows whose START time was in the past, and a repeat's start time is
# in the past from the day it is created - so one daily reminder had NOVA
# announce "You have a reminder due" every 30 minutes, all day, for ever.
_jar = load("ncie/android/NovaJarvis.kt")
check("v9.33.116: what 'due' means has one definition",
      _rs115.count("internal fun isDue(at: Long, rep: Long, now: Long, window: Long): Boolean") == 1 and
      _jar.count("org.nova.ReminderStore.isDue(") == 1)
check("v9.33.116: the nudge asks about the next fire, not the anchor",
      _jar.count("t.first in 1..now2") == 0 and
      _jar.count("org.nova.ReminderStore.loadRows(ctx).count {") == 1)
check("v9.33.116: the due rule is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ReminderStoreTest.kt"),
           encoding="utf-8").read().count("@Test") >= 17)
check("v9.33.116: version is at least 9.33.116 (284)",
      version_at_least("9.33.116", 284))
'''

ver = read(VERIFY)
if ver.count(OLD_V115) != 1:
    print("FATAL: the verifier tail anchor did not match exactly once")
    sys.exit(1)
write(VERIFY, ver.replace(OLD_V115, NEW_V115))

# ---------------------------------------------------------------- verify
print("")
check("store: one due rule", store.count("internal fun isDue(") == 1)
check("store: the due rule reuses the one arming rule",
      store.count("val next = nextArm(at, rep, now) ?: return false") == 1)
check("nudge: the anchor is no longer the test", "t.first in 1..now2" not in jarvis)
check("nudge: it reads the rows and asks the one rule",
      "org.nova.ReminderStore.loadRows(ctx)" in jarvis and
      "org.nova.ReminderStore.isDue(" in jarvis)
check("version is 9.33.116 (284)",
      "versionName '9.33.116'" in gradle and "versionCode 284" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.116", 284)' in read(VERIFY))
check("ReminderStoreTest pins the due rule", "ReminderStore.isDue(" in read(TEST))


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


for name, text in [("ReminderStore.kt", store), ("NovaJarvis.kt", jarvis),
                   ("ReminderStoreTest.kt", read(TEST))]:
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
print("PATCH OK: v9.33.116 applied and self-verified")
