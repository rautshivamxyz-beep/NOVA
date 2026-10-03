package org.nova.ncie.android

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
        try { Settings(ctx).autoReply = true } catch (e: Exception) { }
        setRinger(ctx, android.media.AudioManager.RINGER_MODE_VIBRATE)
        return "Away mode ON - NOVA is auto-replying, phone on vibrate. Say \"i'm back\" to undo."
    }

    fun back(ctx: Context): String {
        try { Settings(ctx).autoReply = false } catch (e: Exception) { }
        setRinger(ctx, android.media.AudioManager.RINGER_MODE_NORMAL)
        return "Welcome back - auto-replies off, ringer normal."
    }

    private fun setRinger(ctx: Context, mode: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.ringerMode = mode
        } catch (e: Exception) { }
    }
}
