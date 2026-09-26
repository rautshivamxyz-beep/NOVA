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

    /** v5.5.0: strict mode - refuse instead of inventing when the
     *  answer is not in the user's notes or offline Wikipedia. */
    var strictMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT, false)
        set(value) = prefs.edit().putBoolean(KEY_STRICT, value).apply()

    /** v5.7.0: speculative decoding - Qwen3 0.6B drafts tokens that the
     *  main model verifies in batches. Off by default: measure with the
     *  built-in speed timers before trusting it. */
    var specDecoding: Boolean
        get() = prefs.getBoolean(KEY_SPEC, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEC, value).apply()

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
        private const val KEY_STRICT = "strict_mode"
        private const val KEY_SPEC = "spec_decoding"
        private const val KEY_KNOWLEDGE_EXCL = "knowledge_excluded"

        const val DEFAULT_SYSTEM_PROMPT =
            "You are NOVA, a helpful AI assistant running fully offline on this phone.\n" +
                "Rules:\n" +
                "1. Be direct and confident about things you know — science, maths, history, public figures and general knowledge. Do not refuse just because a person or topic is mentioned.\n" +
                "2. If you truly do not know something or might confuse details, say so plainly — never invent facts, numbers, quotes or sources.\n" +
                "3. For maths, science or reasoning problems, work through it step by step before the final answer.\n" +
                "4. Keep answers clear and concise; use markdown when it helps.\n" +
                "5. Reply in the language the user writes in.\n" +
                "6. When notes from the user's documents are provided, prefer them over your own memory.\n" +
                "7. Answer directly. Never describe what you are about to write, never repeat instructions back, and never end with commentary about the reply itself.\n"

        val LENGTH_OPTIONS = intArrayOf(256, 512, 1024, 2048)
    }
}
