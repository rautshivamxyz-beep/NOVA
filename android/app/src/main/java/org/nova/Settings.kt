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

    /** v9.29.3: the folder the user granted for tool recipes (SAF tree URI). */
    var grantedFolder: String
        get() = prefs.getString(KEY_GRANTED_FOLDER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GRANTED_FOLDER, value).apply()

    /** v9.29.4: auto-check for an update on launch (and use the delta patch). */
    var autoUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    /** v9.29.4: the version the user last deferred, so auto-check won't nag. */
    var dismissedUpdate: String
        get() = prefs.getString(KEY_DISMISSED_UPDATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISMISSED_UPDATE, value).apply()

    /** UI theme (only "dark" is currently implemented). v9.33.58: the old
     *  comment here described auto-listen by mistake. */
    var theme: String
        get() = prefs.getString(KEY_THEME, "dark") ?: "dark"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /** Conversation mode: auto-listen + auto-send after each reply. */
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

    /** v9.32.0 "Always online": a factual question is looked up automatically
     *  instead of asking first - the source is what stops the model inventing
     *  facts. Turning this off restores the ask-first dialog.
     */
    var autoOnline: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ONLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ONLINE, value).apply()

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
    /** v9.33.40 "Real numbers": the last measured speed. Device + model +
     *  tok/s is the data that improves the model catalogue for EVERY phone -
     *  and it contains nothing personal, which is why it can travel. */
    var lastSpeedReport: String
        get() = prefs.getString(KEY_LAST_SPEED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SPEED, value).apply()

    /** v9.33.42 "Borrowed brain": run the model on a machine that can afford
     *  a bigger one - a laptop on the same Wi-Fi, or another phone. */
    var remoteEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_ON, value).apply()

    var remoteUrl: String
        get() = prefs.getString(KEY_REMOTE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REMOTE_URL, value).apply()

    /** v9.33.45 "Nothing private leaves": send ONLY the question the user
     *  typed - never their notes, documents, learned facts or conversation -
     *  to a machine they do not own. On by default, because the safe default
     *  is the one that cannot leak. Turn it off for your own laptop. */
    var remotePrivateOnly: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_PRIVATE, true)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_PRIVATE, value).apply()

    /** v9.33.44: the password the server was started with (llama-server
     *  --api-key). Only needed once the server is reachable from outside the
     *  home Wi-Fi - through a tunnel or a port forward. On the LAN it is
     *  optional, because only the LAN can reach it. */
    var remoteKey: String
        get() = prefs.getString(KEY_REMOTE_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REMOTE_KEY, value).apply()

    var remoteModel: String
        get() = prefs.getString(KEY_REMOTE_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REMOTE_MODEL, value).apply()

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

    /** v9.16.9 "Auto-responder": opt-in silent auto-reply to incoming chat
     *  messages (WhatsApp / SMS / etc.) while you are busy. Off by default -
     *  NOVA never answers for you until you turn it on. */
    var autoReply: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REPLY, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REPLY, value).apply()

    /** The line the auto-responder sends. */
    var autoReplyMsg: String
        get() = prefs.getString(KEY_AUTO_REPLY_MSG,
            "I'm busy right now - I'll get back to you soon.")
            ?: "I'm busy right now - I'll get back to you soon."
        set(value) = prefs.edit().putString(KEY_AUTO_REPLY_MSG, value).apply()

    /** v9.22.0 "Busy replies": the lines NOVA sends while you are away, one
     *  per line. Empty falls back to the single autoReplyMsg above. */
    var autoReplyLines: String
        get() = prefs.getString(KEY_AUTO_REPLY_LINES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTO_REPLY_LINES, value).apply()

    /** How many different replies NOVA may send to one person before it
     *  stops (v9.22.0: was a flat one-per-five-minutes). */
    var autoReplyMax: Int
        get() = prefs.getInt(KEY_AUTO_REPLY_MAX, 3)
        set(value) = prefs.edit().putInt(KEY_AUTO_REPLY_MAX, value).apply()

    /** The busy replies, defaulted when the user has not set any. */
    fun busyLines(): List<String> {
        val mine = autoReplyLines.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (mine.isNotEmpty()) return mine
        return DEFAULT_BUSY_LINES.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** v9.17.0 "Guardian": the trusted contact NOVA texts if you don't
     *  check in. Empty means no guardian is set. */
    var guardianContact: String
        get() = prefs.getString(KEY_GUARDIAN_CONTACT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GUARDIAN_CONTACT, value).apply()

    /** When the armed check-in is due (millis); 0 = not armed. */
    var guardianUntil: Long
        get() = prefs.getLong(KEY_GUARDIAN_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_GUARDIAN_UNTIL, value).apply()

    /** The SOS text the guardian sends. */
    var guardianMsg: String
        get() = prefs.getString(KEY_GUARDIAN_MSG, DEFAULT_GUARDIAN_MSG) ?: DEFAULT_GUARDIAN_MSG
        set(value) = prefs.edit().putString(KEY_GUARDIAN_MSG, value).apply()

    /** v9.17.0 "Quiet hours": minutes-of-day window when NOVA stays silent
     *  (no auto-reply). -1 = off. */
    var quietStart: Int
        get() = prefs.getInt(KEY_QUIET_START, -1)
        set(value) = prefs.edit().putInt(KEY_QUIET_START, value).apply()

    var quietEnd: Int
        get() = prefs.getInt(KEY_QUIET_END, -1)
        set(value) = prefs.edit().putInt(KEY_QUIET_END, value).apply()

    /** v9.21.0 "Easy": the first-run setup wizard has been completed. */
    var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()

    companion object {
        private const val KEY_SYSTEM_PROMPT = "system_prompt_v2"
        private const val KEY_PREDICT_LENGTH = "predict_length"
        private const val KEY_LAST_MODEL = "last_model_path"
        private const val KEY_LAST_MODEL_LABEL = "last_model_label"
        private const val KEY_CURRENT_CHAT = "current_chat_id"
        private const val KEY_READ_ALOUD = "read_aloud"
        private const val KEY_MEMORY = "memory"
        private const val KEY_GRANTED_FOLDER = "grantedFolder"
        private const val KEY_AUTO_UPDATE = "autoUpdate"
        private const val KEY_DISMISSED_UPDATE = "dismissedUpdate"
        private const val KEY_AUTO_LISTEN = "auto_listen"
        private const val KEY_THEME = "theme"
        private const val KEY_KNOWLEDGE = "knowledge_enabled"
        // v9.33.58: was a bare literal at every use site.
        private const val KEY_AUTO_ONLINE = "autoOnline"
        private const val KEY_WIKI = "wiki_enabled"
        private const val KEY_ONLINE_LEARN = "online_learning"
        private const val KEY_STRICT = "strict_mode"
        private const val KEY_SPEC = "spec_decoding"
        private const val KEY_ADAPTIVE_CTX = "adaptive_context"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_KNOWLEDGE_EXCL = "knowledge_excluded"
        private const val KEY_AUTO_REPLY = "auto_reply"
        private const val KEY_AUTO_REPLY_MSG = "auto_reply_msg"
        // v9.22.0 "Busy replies"
        private const val KEY_AUTO_REPLY_LINES = "auto_reply_lines"
        private const val KEY_AUTO_REPLY_MAX = "auto_reply_max"
        // v9.17.0 "Leftovers"
        private const val KEY_GUARDIAN_CONTACT = "guardian_contact"
        private const val KEY_GUARDIAN_UNTIL = "guardian_until"
        private const val KEY_GUARDIAN_MSG = "guardian_msg"
        private const val KEY_QUIET_START = "quiet_start"
        private const val KEY_QUIET_END = "quiet_end"
        // v9.21.0 "Easy"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_LAST_SPEED = "last_speed_report"
        private const val KEY_REMOTE_ON = "remote_on"
        private const val KEY_REMOTE_URL = "remote_url"
        private const val KEY_REMOTE_MODEL = "remote_model"
        private const val KEY_REMOTE_KEY = "remote_key"
        private const val KEY_REMOTE_PRIVATE = "remote_private_only"

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

        /**
         * v9.33.62 "Rules cached, answers to the point": the model's standing
         * instructions.
         *
         * These used to be prepended to EVERY turn's user message, so ~280
         * tokens were re-prefilled on every single turn - the dominant part of
         * the wait before the first word - and duplicated into the KV cache
         * once per turn. They now ride in the SYSTEM prompt, which the engine
         * encodes once per model load and keeps in its KV cache: the model
         * still reads the exact same rules (quality unchanged), while each turn
         * prefills only the context and the question. First word sooner, less
         * KV memory, same answers.
         *
         * The first rule is the "to the point" default: answer first, stay
         * short unless the user asks for more.
         */
        const val RULEBOOK =
            "(Answer the question directly, beginning with the answer itself. " +
                "Keep it to the point: by default give the shortest answer that fully " +
                "answers the question - usually one to three sentences, or a few short " +
                "lines. Expand into steps, examples or extra detail only when the user " +
                "asks for detail, or when the question genuinely needs them.)\n" +
                "(FACTS: state a date, a place, a name or a number when you are certain of it. " +
                "When you are unsure, say so in one line and give what you do know.\n" +
                "WHO IS/WAS X: answer in one to three concise sentences with the most important facts. " +
                "Give a longer fact list only when the user asks for detail.\n" +
                "ANSWER WHAT WAS ASKED: answer the question you were given, and that one.\n" +
                "USE THE BACKGROUND: the facts about the user are context for your answer. " +
                "Use them silently, and answer in your own voice as NOVA speaking to the user. " +
                "When a question is unclear, ask ONE short question and answer that.\n" +
                "FOLLOW-UPS: \"he\", \"she\", \"it\", \"the topic\" mean the SAME subject as the " +
                "previous turn - keep that subject.\n" +
                "VOICE: speak directly to the user, in the first person, as NOVA.\n" +
                "CODE: when the user asks for code, write it in the SAME reply - complete, runnable, " +
                "in one fenced block, ready to copy. Put the code first and keep any note to one line.\n" +
                "TEACHING: start with the topic, then the ideas in order, then a short example.)\n\n"

        /** v9.17.0: the default SOS text the guardian sends. */
        const val DEFAULT_GUARDIAN_MSG =
            "This is NOVA, your assistant. You asked me to check in on you and " +
                "you haven't replied - please reach out to make sure you're okay."

        /** v9.22.0: the default busy replies, used until the user sets (or
         *  generates) their own. */
        const val DEFAULT_BUSY_LINES =
            "Tell me fast, I'm doing something right now|" +
                "I'm in the middle of something - tell me quick|" +
                "Busy right now, what's up?|" +
                "Can't talk properly right now, tell me fast"

        val LENGTH_OPTIONS = intArrayOf(256, 512, 1024, 2048)
    }
}
