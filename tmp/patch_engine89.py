#!/usr/bin/env python3
"""NOVA one-shot patch for v9.33.86 "device senses" (Tier 1 of the backlog).

Deterministic, idempotent, self-verifying (ends by running verify_patches.py).
Usage: patch_engine89.py <NOVA repo root>

Tier 1 device senses: what the phone itself is doing - battery, charging,
network, screen, free storage - answered in the chat, deterministically, with
NO model call and NO new permission. Battery and screen state need no
permission at all; ACCESS_NETWORK_STATE is already declared (the online offer
uses it), and StatFs on the app's own directory needs nothing.

Why it matters for speed, not just features: a question about the phone
currently goes to the model, which generates a paragraph of guesses about a
device it cannot see. This answers in the time it takes to read a file.

The Android reads are one thin function; the reply text is built by
NcieSenses.format, which is pure Kotlin and therefore unit-tested on the JVM -
including the honesty rule that an unreadable battery is "not readable", never
"0%".

What it does
  1. build.gradle        : versionCode 253 -> 254, versionName 9.33.85 -> 9.33.86
  2. NcieSenses.kt       : NEW - the reads + the pure reply formatter
  3. NcieChatExt.kt      : NEW - SENSES_Q + MainActivity.answerSenses
  4. NcieChat.kt         : the router calls it before any generation
  5. NcieSensesTest.kt   : NEW - six tests on the formatter
  6. verify_patches.py   : six checks, the version floor at 9.33.86/254
"""
import os
import sys
import subprocess

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = "android/app/src/main/java/org/nova/"
TEST = "android/app/src/test/java/org/nova/"


def read(rel):
    return open(os.path.join(ROOT, rel), encoding="utf-8").read()


def write(rel, s):
    p = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(s)
    print("wrote", rel)


def edit(rel, pairs):
    s = read(rel)
    for old, new, n in pairs:
        got = s.count(old)
        if got != n:
            raise SystemExit("FATAL %s: expected %d of %r, found %d" % (rel, n, old[:70], got))
        s = s.replace(old, new)
    write(rel, s)


# 1. the version bump
edit("android/app/build.gradle", [
    ("versionCode 253", "versionCode 254", 1),
    ("versionName '9.33.85'", "versionName '9.33.86'", 1),
])

# 2. the new object
SENSES = r'''package org.nova.ncie.android

import org.nova.NovaLog

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs

/**
 * Tier 1 "device senses" (v9.33.86): "battery" / "am i online" /
 * "device status" - what the phone itself is doing, answered
 * deterministically. No model call, no network request, and NO new
 * permission:
 *
 *  - battery level and charging state need no permission (the sticky
 *    ACTION_BATTERY_CHANGED broadcast, or BatteryManager's capacity property)
 *  - the network type uses ACCESS_NETWORK_STATE, already declared for the
 *    online-offer logic
 *  - the screen state comes from PowerManager, which needs none
 *  - free storage is StatFs over the app's OWN directory
 *
 * Nothing read here is stored, logged as content, or sent anywhere: it is
 * read, formatted and shown once. Asking the model "how is my battery" used
 * to produce a paragraph of guesses about a device it cannot see; this answers
 * before any model is loaded.
 *
 * [read] is the only Android-dependent part. [format] is pure, so the wording
 * and the honesty rules are pinned by JVM unit tests.
 */
object NcieSenses {

    /** What the phone is doing right now. -1 means "could not read". */
    data class State(
        val batteryPercent: Int,
        val charging: Boolean,
        val online: Boolean,
        val network: String,
        val screenOn: Boolean,
        val freeMb: Long)

    fun read(ctx: Context): State {
        var pct = -1
        var charging = false
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (pct < 0) {
                val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (lvl >= 0 && scale > 0) pct = lvl * 100 / scale
            }
        } catch (e: Exception) { NovaLog.quiet("NcieSenses", e) }

        var online = false
        var net = "no connection"
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val n = cm?.activeNetwork
            val c = if (n != null) cm?.getNetworkCapabilities(n) else null
            if (c != null) {
                online = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                net = when {
                    c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wi-fi"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "connected"
                }
                if (!online) net = net + " (no internet)"
            }
        } catch (e: Exception) { NovaLog.quiet("NcieSenses", e) }

        var screenOn = true
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            screenOn = pm?.isInteractive ?: true
        } catch (e: Exception) { NovaLog.quiet("NcieSenses", e) }

        var freeMb = -1L
        try {
            freeMb = StatFs(ctx.filesDir.absolutePath).availableBytes / (1024L * 1024L)
        } catch (e: Exception) { NovaLog.quiet("NcieSenses", e) }

        return State(pct, charging, online, net, screenOn, freeMb)
    }

    /**
     * The reply. Pure - no Android, no IO - so a JVM unit test can pin it.
     *
     * The honesty rules matter more than the layout: a battery that could not
     * be read is "not readable", NEVER "0%" (which would read as a dead
     * phone), and an offline phone says so without alarm, because everything
     * except online lookups still works.
     */
    fun format(s: State): String {
        val sb = StringBuilder("```\nDevice:\n")
        val batt = when {
            s.batteryPercent < 0 -> "not readable on this phone"
            s.charging -> s.batteryPercent.toString() + "% (charging)"
            s.batteryPercent <= 15 -> s.batteryPercent.toString() + "% (low)"
            else -> s.batteryPercent.toString() + "%"
        }
        sb.append("  battery: ").append(batt).append('\n')
        sb.append("  network: ").append(s.network).append('\n')
        sb.append("  screen: ").append(if (s.screenOn) "on" else "off").append('\n')
        sb.append("  storage free: ")
            .append(if (s.freeMb < 0) "unknown" else s.freeMb.toString() + " MB")
            .append('\n')
        sb.append("```")
        val notes = ArrayList<String>()
        if (s.batteryPercent in 0..15 && !s.charging)
            notes.add("Battery is low - charge before a long model run.")
        if (s.freeMb in 0..499)
            notes.add("Storage is nearly full - downloads and backups may fail.")
        if (!s.online)
            notes.add("No internet right now, so online lookups are unavailable. " +
                "Everything else still works offline.")
        if (notes.isNotEmpty()) sb.append('\n').append(notes.joinToString("\n"))
        return sb.toString()
    }
}
'''
write(BASE + "ncie/android/NcieSenses.kt", SENSES)

# 3. the handler
HANDLER_ANCHOR = "internal fun MainActivity.answerFocus(text: String): Boolean {\n"
HANDLER_NEW = r'''// v9.33.86 "Device senses" (Tier 1): "battery" / "am i online" /
// "device status" - what the phone itself is doing. Deterministic, offline,
// no model call and no new permission. It sits with the other deterministic
// commands and runs before any generation, so a question about the phone
// never waits for a token.
internal val SENSES_Q = Regex(
    "(?i)^\\s*(?:" +
        "how(?:'s|\\s+is)?\\s+(?:my\\s+)?battery" +
        "|battery(?:\\s+(?:status|level))?" +
        "|am\\s+i\\s+online" +
        "|(?:network|connection)\\s+status" +
        "|device\\s+status" +
        "|phone\\s+status" +
        ")\\s*[.!?]*\\s*$")

internal fun MainActivity.answerSenses(text: String): Boolean {
    if (text.length > 60) return false
    if (!SENSES_Q.containsMatchIn(text)) return false
    val activity = this
    input.setText("")
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    scope.launch(Dispatchers.IO) {
        val body = try {
            NcieSenses.format(NcieSenses.read(activity))
        } catch (e: Exception) { "Device status unavailable." }
        withContext(Dispatchers.Main) { postGroundReply(activity, body) }
    }
    return true
}

internal fun MainActivity.answerFocus(text: String): Boolean {
'''
edit(BASE + "ncie/android/NcieChatExt.kt", [(HANDLER_ANCHOR, HANDLER_NEW, 1)])

# 4. the router
ROUTER_OLD = "        if (answerFocus(text)) { input.setText(\"\"); return }\n"
ROUTER_NEW = (
    "        if (answerFocus(text)) { input.setText(\"\"); return }\n"
    "        // v9.33.86 \"Device senses\" (Tier 1): \"battery\" / \"am i online\" /\n"
    "        // \"device status\" - what the phone itself is doing. Deterministic,\n"
    "        // offline, no model call and no new permission.\n"
    "        if (answerSenses(text)) { input.setText(\"\"); return }\n"
)
edit(BASE + "ncie/android/NcieChat.kt", [(ROUTER_OLD, ROUTER_NEW, 1)])

# 5. the tests
SENSES_TEST = r'''package org.nova.ncie.android

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.86: the device-senses reply. The Android reads cannot run on the JVM,
 * so the part that can be wrong in a way the user sees - the wording, and the
 * honesty rules - is what these pin.
 */
class NcieSensesTest {

    private fun state(
        pct: Int = 80,
        charging: Boolean = false,
        online: Boolean = true,
        net: String = "wi-fi",
        screen: Boolean = true,
        free: Long = 8000L
    ) = NcieSenses.State(pct, charging, online, net, screen, free)

    @Test
    fun aHealthyPhoneReadsPlainly() {
        val s = NcieSenses.format(state())
        assertTrue(s.contains("battery: 80%"))
        assertTrue(s.contains("network: wi-fi"))
        assertTrue(s.contains("screen: on"))
        assertTrue(s.contains("storage free: 8000 MB"))
        assertTrue(!s.contains("charge before"))
    }

    @Test
    fun aLowBatteryIsCalledLowAndComesWithOneLineOfAdvice() {
        val s = NcieSenses.format(state(pct = 12))
        assertTrue(s.contains("12% (low)"))
        assertTrue(s.contains("Battery is low"))
    }

    @Test
    fun chargingIsNamed() {
        val s = NcieSenses.format(state(pct = 12, charging = true))
        assertTrue(s.contains("12% (charging)"))
        assertTrue(!s.contains("Battery is low"))
    }

    @Test
    fun anUnreadableBatteryIsNotZeroPercent() {
        val s = NcieSenses.format(state(pct = -1))
        assertTrue(s.contains("not readable"))
        assertTrue(!s.contains("0%"))
        assertTrue(!s.contains("-1%"))
    }

    @Test
    fun offlineSaysSoWithoutAlarm() {
        val s = NcieSenses.format(state(online = false, net = "no connection"))
        assertTrue(s.contains("network: no connection"))
        assertTrue(s.contains("still works offline"))
    }

    @Test
    fun nearlyFullStorageIsCalledOut() {
        val s = NcieSenses.format(state(free = 120L))
        assertTrue(s.contains("storage free: 120 MB"))
        assertTrue(s.contains("nearly full"))
    }
}
'''
write(TEST + "ncie/android/NcieSensesTest.kt", SENSES_TEST)

# 6. verify_patches
VERIFY_ANCHOR = (
    'check("v9.33.85: version is at least 9.33.85 (253)",\n'
    '      version_at_least("9.33.85", 253))\n'
)
VERIFY_NEW = VERIFY_ANCHOR + (
    '\n'
    '# ---- v9.33.86 "Device senses" (Tier 1) ----\n'
    '# Deterministic, offline, and it must not need a new permission: the whole\n'
    '# point of Tier 1 is that the phone\'s own state is free to read.\n'
    '_senses = load("ncie/android/NcieSenses.kt")\n'
    'check("v9.33.86: the senses command is routed before any generation",\n'
    '      "if (answerSenses(text)) { input.setText(\\"\\"); return }" in nc)\n'
    'check("v9.33.86: the handler never calls the model",\n'
    '      "internal fun MainActivity.answerSenses(text: String): Boolean" in nc\n'
    '      and "NovaEngineAdapter.generate" not in _senses\n'
    '      and "NcieTutor" not in _senses)\n'
    'check("v9.33.86: the senses need no new permission",\n'
    '      "requestPermissions" not in _senses\n'
    '      and "uses-permission" not in _senses)\n'
    'check("v9.33.86: an unreadable battery is never reported as 0%",\n'
    '      "not readable on this phone" in _senses)\n'
    'check("v9.33.86: the reply is pinned by unit tests",\n'
    '      "fun anUnreadableBatteryIsNotZeroPercent()" in\n'
    '      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/NcieSensesTest.kt"),\n'
    '           encoding="utf-8").read())\n'
    'check("v9.33.86: version is at least 9.33.86 (254)",\n'
    '      version_at_least("9.33.86", 254))\n'
)
edit("tmp/verify_patches.py", [(VERIFY_ANCHOR, VERIFY_NEW, 1)])

# the new file joins the structural sweep (brace balance + string escapes)
STRUCT_OLD = '("ncie/Tokens.kt", load("ncie/Tokens.kt"))]:'
STRUCT_NEW = ('("ncie/Tokens.kt", load("ncie/Tokens.kt")), '
              '("ncie/android/NcieSenses.kt", load("ncie/android/NcieSenses.kt"))]:')
edit("tmp/verify_patches.py", [(STRUCT_OLD, STRUCT_NEW, 1)])

# self-verify
print("running verify_patches ...")
rc = subprocess.call([sys.executable, os.path.join(ROOT, "tmp/verify_patches.py"), ROOT])
if rc != 0:
    raise SystemExit("FATAL: verify_patches failed after patching")
print("patch_engine89 OK")
