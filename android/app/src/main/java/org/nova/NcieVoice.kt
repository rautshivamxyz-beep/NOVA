package org.nova

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * v9.0.0 "Voice": NOVA's spoken-replies engine - one shared
 * TextToSpeech wrapper for the whole app. Fully local by design law:
 * the engine is whatever the OS provides (on-device or system TTS),
 * no new app dependencies, no accounts, nothing ever leaves the phone.
 *
 * `enabled` persists in the app's single SharedPreferences store (the
 * same "nova" prefs the Settings class reads and writes), under the
 * key "voiceReplies" - so the drawer toggle survives restarts exactly
 * like every other flag. MainActivity's `tts` is an alias onto
 * [NcieVoice.tts], so the document read-aloud and the streaming
 * read-aloud share this ONE engine instead of a second instance.
 */
object NcieVoice {

    /** The shared engine; null before init(ctx) or after shutdown(ctx). */
    var tts: TextToSpeech? = null

    /** True once the engine initialized successfully. */
    val isReady: Boolean
        get() = ready

    private var ready = false
    private var prefs: android.content.SharedPreferences? = null

    /** True when NOVA should read its replies aloud (drawer: "Voice replies"). */
    var enabled: Boolean
        get() = prefs?.getBoolean(KEY_ENABLED, false) ?: false
        set(value) { prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply() }

    /** Create the engine once, from MainActivity.onCreate.
     *  v9.4.0 "Audit Fixes II" (audit: TTS leak): init is idempotent -
     *  a second call returns early instead of leaking another engine
     *  over the shared one. */
    fun init(ctx: Context) {
        if (ready || tts != null) return
        prefs = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
        try {
            tts = TextToSpeech(ctx.applicationContext) { code ->
                ready = code == TextToSpeech.SUCCESS
                if (ready) {
                    try { tts?.language = Locale.getDefault() } catch (e: Exception) { }
                } else {
                    // no engine on this phone - disable and stay silent,
                    // never crash
                    try { tts?.shutdown() } catch (e: Exception) { }
                    tts = null
                }
            }
        } catch (e: Exception) {
            tts = null
            ready = false
        }
    }

    /**
     * Speak one reply aloud. QUEUE_FLUSH: a newer reply cuts off
     * whatever was still being said, so speech can never pile up.
     * The text is cleaned of the app's formatting artifacts first
     * (markdown-ish asterisks/backticks, links, code fences, stray
     * whitespace) and capped at 800 characters.
     */
    fun speak(text: String) {
        if (!enabled || !ready || tts == null) return
        val clean = cleanText(text)
        if (clean.isBlank()) return
        try {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "nova_reply")
        } catch (e: Exception) { }
    }

    /** Stop any in-flight speech (QUEUE_FLUSH already handles overlap;
     *  exposed for safety). */
    fun stop() {
        try { tts?.stop() } catch (e: Exception) { }
    }

    /** Release the engine - called from MainActivity.onDestroy. */
    fun shutdown(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        try { tts?.stop() } catch (e: Exception) { }
        try { tts?.shutdown() } catch (e: Exception) { }
        tts = null
        ready = false
    }

    /** Strips the app's formatting artifacts; caps at 800 chars. */
    internal fun cleanText(text: String): String {
        var clean = text
            .replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1")  // links -> text
            .replace(Regex("```[a-zA-Z0-9]*"), " code: ")        // code fences
            .replace(Regex("""[*_`>#~|]+"""), "")                // emphasis etc.
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (clean.length > 800) {
            // v9.4.0 "Audit Fixes II" (audit: speech cut mid-word): trim
            // to the last whitespace before the cap, never mid-word
            var end = 800
            while (end > 0 && !clean[end - 1].isWhitespace()) end--
            clean = clean.substring(0, if (end > 0) end else 800)
        }
        return clean
    }

    private const val KEY_ENABLED = "voiceReplies"
}
