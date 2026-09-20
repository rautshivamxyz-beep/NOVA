package org.nova

import android.content.Context

/**
 * Small SharedPreferences wrapper: system prompt, generation length and the
 * path of the last used model.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("nova", Context.MODE_PRIVATE)

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    var predictLength: Int
        get() = prefs.getInt(KEY_PREDICT_LENGTH, 1024)
        set(value) = prefs.edit().putInt(KEY_PREDICT_LENGTH, value).apply()

    var lastModelPath: String?
        get() = prefs.getString(KEY_LAST_MODEL, null)
        set(value) = prefs.edit().putString(KEY_LAST_MODEL, value).apply()

    var lastModelLabel: String
        get() = prefs.getString(KEY_LAST_MODEL_LABEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_MODEL_LABEL, value).apply()

    fun parseLengthIndex(): Int = when (predictLength) {
        256 -> 0; 512 -> 1; 1024 -> 2; 2048 -> 3; else -> 2
    }

    fun lengthFromIndex(i: Int): Int = LENGTH_OPTIONS[i]

    companion object {
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_PREDICT_LENGTH = "predict_length"
        private const val KEY_LAST_MODEL = "last_model_path"
        private const val KEY_LAST_MODEL_LABEL = "last_model_label"

        const val DEFAULT_SYSTEM_PROMPT =
            "You are NOVA, a helpful, concise AI assistant that runs fully offline on this phone. " +
                "Answer clearly and to the point."

        val LENGTH_OPTIONS = intArrayOf(256, 512, 1024, 2048)
    }
}
