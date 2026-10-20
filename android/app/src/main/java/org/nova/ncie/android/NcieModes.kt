package org.nova.ncie.android

import org.nova.NovaLog

import android.content.Context
import org.nova.Settings

/**
 * v9.16.14 "Jarvis": one-phrase modes. "away" turns the auto-responder on
 * and puts the phone on vibrate; "i'm back" undoes both. Deterministic, no
 * model call. The ringer change is best-effort (Do-Not-Disturb grants vary
 * by device), so a failure there never breaks the mode.
 */
object NcieModes {

    fun away(ctx: Context): String {
        // v9.33.17: report honestly when the setting could not be
        // saved - v9.33.15 recorded swallowed failures, v9.33.16/17
        // make the deterministic commands stop claiming success.
        val saved = try {
            Settings(ctx).autoReply = true
            true
        } catch (e: Exception) {
            NovaLog.quiet("NcieModes", e)
            false
        }
        setRinger(ctx, android.media.AudioManager.RINGER_MODE_VIBRATE)
        if (!saved) return "Couldn't switch away mode on - NOVA could not save the setting."
        return "Away mode ON - NOVA is auto-replying, phone on vibrate. Say \"i'm back\" to undo."
    }

    fun back(ctx: Context): String {
        val saved = try {
            Settings(ctx).autoReply = false
            true
        } catch (e: Exception) {
            NovaLog.quiet("NcieModes", e)
            false
        }
        setRinger(ctx, android.media.AudioManager.RINGER_MODE_NORMAL)
        if (!saved) return "Couldn't switch away mode off - NOVA could not save the setting."
        return "Welcome back - auto-replies off, ringer normal."
    }

    private fun setRinger(ctx: Context, mode: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.ringerMode = mode
        } catch (e: Exception) { NovaLog.quiet("NcieModes", e) }
    }
}
