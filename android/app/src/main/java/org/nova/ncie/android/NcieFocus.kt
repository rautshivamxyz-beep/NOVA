package org.nova.ncie.android

import android.content.Context
import java.io.File

/**
 * A5 "Focus mode" (v9.33.12): "focus 25" / "focus mode" / "stop focus" - a
 * deterministic, fully offline focus session. No model call, no network and
 * no new permission: system Do-Not-Disturb needs Notification Policy Access,
 * which is a separate ask (see the plan's WAITING-FOR-SHIVAM list).
 *
 * While a session is active NOVA stops interrupting the user itself - the
 * auto-responder stays silent (NovaListener checks [active]) and the spoken
 * nudges are held. A session is just a timestamp on disk, so it survives a
 * restart and expires on its own; nothing else is needed to end it.
 */
object NcieFocus {

    private fun file(ctx: Context) = File(ctx.filesDir, "focus_until")

    /** The end of the current session in millis, or 0 when none is set. */
    fun until(ctx: Context): Long = try {
        val f = file(ctx)
        if (f.exists()) f.readText().trim().toLongOrNull() ?: 0L else 0L
    } catch (e: Exception) { 0L }

    fun active(ctx: Context): Boolean = until(ctx) > System.currentTimeMillis()

    /** Whole minutes left in the current session, 0 when none is active. */
    fun minutesLeft(ctx: Context): Int {
        val ms = until(ctx) - System.currentTimeMillis()
        if (ms <= 0L) return 0
        return ((ms + 59_999L) / 60_000L).toInt()
    }

    fun start(ctx: Context, minutes: Int): String {
        val mins = minutes.coerceIn(1, 480)
        val end = System.currentTimeMillis() + mins * 60_000L
        try { file(ctx).writeText(end.toString()) } catch (e: Exception) { }
        return "Focus mode on for " + mins +
            (if (mins == 1) " minute." else " minutes.") +
            "\nNOVA will stay quiet - no auto-replies, no spoken nudges - until then." +
            "\nSay \"stop focus\" to end it early."
    }

    fun stop(ctx: Context): String {
        val was = active(ctx)
        try { file(ctx).delete() } catch (e: Exception) { }
        return if (was) "Focus mode off." else "Focus mode was not on."
    }

    fun status(ctx: Context): String {
        val left = minutesLeft(ctx)
        return if (left <= 0)
            "Focus mode is off. Say \"focus 25\" to start a 25-minute session."
        else
            "Focus mode is on - " + left +
                (if (left == 1) " minute" else " minutes") + " left."
    }
}
