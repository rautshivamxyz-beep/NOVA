package org.nova

import android.content.Context

/**
 * SharedPreferences wrapper: system prompt, generation length, last model,
 * current chat, voice settings.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("nova", Context.MODE_PRIVATE)

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    var predictLength: Int
        get() = prefs.getInt(KEY_PREDICT_LENGTH, 512)
        set(value) = prefs.edit().putInt(KEY_PREDICT_LENGTH, value).apply()

    var lastModelPath: String?
        get() = prefs.getString(KEY_LAST_MODEL, null)
        set(value) = prefs.edit().putString(KEY_LAST_MODEL, value).apply()

    var lastModelLabel: String
        get() = prefs.getString(KEY_LAST_MODEL_LABEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_MODEL_LABEL, value).apply()

    var currentChatId: String
        get() = prefs.getString(KEY_CURRENT_CHAT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CURRENT_CHAT, value).apply()

    var readAloud: Boolean
        get() = prefs.getBoolean(KEY_READ_ALOUD, false)
        set(value) = prefs.edit().putBoolean(KEY_READ_ALOUD, value).apply()

    /** Facts about the user, injected into every prompt. */
    var memory: String
        get() = prefs.getString(KEY_MEMORY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MEMORY, value).apply()

    /** Conversation mode: auto-listen + auto-send after each reply. */
    var theme: String
        get() = prefs.getString(KEY_THEME, "dark") ?: "dark"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var autoListen: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LISTEN, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_LISTEN, value).apply()

    /** Knowledge base: answer using the user's indexed documents.
     *  v5.4.4: defaults ON - importing documents means wanting them used.
     *  The old silent OFF default made NOVA index every PDF and then
     *  quietly never inject any of them, so all answers came from the
     *  model's memory alone. */
    var knowledgeEnabled: Boolean
        get() = prefs.getBoolean(KEY_KNOWLEDGE, true)
        set(value) = prefs.edit().putBoolean(KEY_KNOWLEDGE, value).apply()

    /** Offline Wikipedia: attach matching articles as background facts. */
    var wikiEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIKI, true)
        set(value) = prefs.edit().putBoolean(KEY_WIKI, value).apply()

    /** v8.4.0 (stage 1): learn online - when a study question has nothing
     *  local behind it, ASK, then fetch the Wikipedia article (keywords
     *  only - nothing personal ever leaves the phone) and keep it offline
     *  forever. Off by default: the phone answers 100% offline until the
     *  user opts in. */
    var onlineLearning: Boolean
        get() = prefs.getBoolean(KEY_ONLINE_LEARN, false)
        set(value) = prefs.edit().putBoolean(KEY_ONLINE_LEARN, value).apply()

    /** v5.5.0: strict mode - refuse instead of inventing when the
     *  answer is not in the user's notes or offline Wikipedia. */
    var strictMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT, false)
        set(value) = prefs.edit().putBoolean(KEY_STRICT, value).apply()

    /** v5.7.0: speculative decoding - the Qwen3 0.6B draft proposes tokens
     *  that the main model verifies in batches.
     *
     *  v9.13.6 "Faster Qwen3" made this ON by default; v9.13.9 turns it back
     *  OFF. The draft model costs RAM, and on a 4 GB phone that extra
     *  pressure can make generation fail and wedge the engine - the exact
     *  "User prompt discarded" failure seen on the Narzo 50A. The feature is
     *  unchanged and lossless; turn it ON in Settings on a phone with RAM to
     *  spare (a Qwen model + the Qwen3 0.6B draft). */
    var specDecoding: Boolean
        get() = prefs.getBoolean(KEY_SPEC, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEC, value).apply()

    /** v9.14.0 "Sharp Memory": a context source that has been injected
     *  many times and never contributed is skipped, so the prompt shrinks
     *  (faster prefill) with no loss to answers. Default ON; off means
     *  every source is always injected, exactly as before. */
    var adaptiveContext: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_CTX, true)
        set(value) = prefs.edit().putBoolean(KEY_ADAPTIVE_CTX, value).apply()

    /** v9.15.0 "Private Fetch": route every online lookup through a SOCKS
     *  proxy - Orbot/Tor, or a proxy the user controls - so the sites
     *  NOVA reads never see this phone's IP. Off by default: with it off,
     *  requests go direct, exactly as before. */
    var proxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PROXY_ENABLED, value).apply()

    /** SOCKS proxy host (Orbot's default is 127.0.0.1). */
    var proxyHost: String
        get() = prefs.getString(KEY_PROXY_HOST, "127.0.0.1") ?: "127.0.0.1"
        set(value) = prefs.edit().putString(KEY_PROXY_HOST, value).apply()

    /** SOCKS proxy port (Orbot's default is 9050). */
    var proxyPort: Int
        get() = prefs.getInt(KEY_PROXY_PORT, 9050)
        set(value) = prefs.edit().putInt(KEY_PROXY_PORT, value).apply()

    /** v5.4.6: documents excluded from Knowledge search via the Notes
     *  filter drawer row - "English only" during an English exam. */
    var knowledgeExcluded: MutableSet<String>
        get() = prefs.getStringSet(KEY_KNOWLEDGE_EXCL, emptySet())?.toMutableSet() ?: mutableSetOf()
        set(value) = prefs.edit().putStringSet(KEY_KNOWLEDGE_EXCL, value.toSet()).apply()

    companion object {
        private const val KEY_SYSTEM_PROMPT = "system_prompt_v2"
        private const val KEY_PREDICT_LENGTH = "predict_length"
        private const val KEY_LAST_MODEL = "last_model_path"
        private const val KEY_LAST_MODEL_LABEL = "last_model_label"
        private const val KEY_CURRENT_CHAT = "current_chat_id"
        private const val KEY_READ_ALOUD = "read_aloud"
        private const val KEY_MEMORY = "memory"
        private const val KEY_AUTO_LISTEN = "auto_listen"
        private const val KEY_THEME = "theme"
        private const val KEY_KNOWLEDGE = "knowledge_enabled"
        private const val KEY_WIKI = "wiki_enabled"
        private const val KEY_ONLINE_LEARN = "online_learning"
        private const val KEY_STRICT = "strict_mode"
        private const val KEY_SPEC = "spec_decoding"
        private const val KEY_ADAPTIVE_CTX = "adaptive_context"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_KNOWLEDGE_EXCL = "knowledge_excluded"

        // v9.11.0 "Inference Quality": a strong, concise base prompt
        // (under 70 words) replaces the old 7-rule stack - the small
        // on-device models follow it noticeably better, and every
        // preamble part (docPart, memory carry, rolling summary) is
        // injected AFTER it, unchanged. NovaEngine falls back to this
        // same text when the stored prompt is blank. v9.12.1 "Context
        // Diet": one directness sentence added - the 1.5B model kept
        // narrating what it was about to write.
        // v9.16.1 "Thin Prompt": the small on-device models drown when the
        // system prompt stacks many instructions, so it is three plain
        // rules now - the app decides what context to supply and how to
        // rank it, and the model only has to answer. Keep it thin.
        const val DEFAULT_SYSTEM_PROMPT =
            "You are NOVA, a private offline assistant running on this phone - " +
                "nothing you say leaves the device.\n" +
                "Be concise and direct. If you are not sure, say so instead of guessing. " +
                "Do not invent facts.\n" +
                "Reply in the language the user writes in."

        val LENGTH_OPTIONS = intArrayOf(256, 512, 1024, 2048)
    }
}
