package org.nova.ncie.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.nova.Chat
import org.nova.ChatStore
import org.nova.MainActivity
import org.nova.Msg
import org.nova.NovaEngine
import org.nova.Role
import org.nova.Settings
import java.io.File
import java.util.Locale

/**
 * v9.11.0 "Inference Quality": per-model sampling profiles + the
 * token-budget-aware context trim. Fully local, app-side only - the
 * NCIE kernel (libs/llama-release.aar, com.arm.aichat) is NOT touched.
 *
 * ENGINE BOUNDARY, v9.12.0 UPDATE (supersedes the v9.11.0 finding
 * that there were NO sampling setters): engine v8.0 (llama.cpp
 * 6e60f35 + the v5.3..v8.0 patch chain, the v8.0 swap commit)
 * exposes suspend setSampling(temperature, topP, topK, minP,
 * repeatPenalty) on com.arm.aichat.InferenceEngine - the kernel
 * follow-up happened. applyProfile() now pushes the effective values
 * to the live sampler through NovaEngineAdapter at every generation
 * start (the one call site the v9.11.0 staging left for it), and the
 * "model settings" reply honestly reports whether the push is live
 * or still staged. The failure policy is one-way: the first
 * setSampling Throwable disables the bridge for the session, and it
 * is never retried - a tuning knob must never crash a generation.
 *
 * Storage: filesDir/model_settings/<modelFileName>.txt, lines of
 * "key=value" for temperature, top_p, top_k, min_p, repeat_penalty.
 * An absent file means "use the family defaults".
 */
object NcieTune {

    /** The five sampling knobs this object manages, in display order. */
    val KEYS = listOf("temperature", "top_p", "top_k", "min_p", "repeat_penalty")

    /** Friendly names for the chat replies ("repeat_penalty" reads badly). */
    val DISPLAY = mapOf(
        "temperature" to "temperature",
        "top_p" to "top p",
        "top_k" to "top k",
        "min_p" to "min p",
        "repeat_penalty" to "repeat penalty")

    /** Valid ranges - "set temperature 5" gets an honest usage reply. */
    val RANGES: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
        "temperature" to 0.0..2.0,
        "top_p" to 0.0..1.0,
        "top_k" to 0.0..100.0,
        "min_p" to 0.0..0.3,
        "repeat_penalty" to 1.0..2.0)

    /**
     * v9.11.0 family defaults, matched case-insensitively as substrings
     * of the model FILE NAME. "qwen" -> the ChatML family tuned for
     * tight, on-point answers; "lfm" -> Liquid's recommended chat
     * sampling; llama / gemma / everything else -> the conservative
     * conversational default.
     */
    val QWEN_DEFAULTS: Map<String, Double> = mapOf(
        "temperature" to 0.7, "top_p" to 0.8, "top_k" to 20.0,
        "min_p" to 0.0, "repeat_penalty" to 1.05)
    val LFM_DEFAULTS: Map<String, Double> = mapOf(
        "temperature" to 0.6, "top_p" to 0.9, "top_k" to 40.0,
        "min_p" to 0.05, "repeat_penalty" to 1.1)
    val OTHER_DEFAULTS: Map<String, Double> = mapOf(
        "temperature" to 0.8, "top_p" to 0.9, "top_k" to 40.0,
        "min_p" to 0.05, "repeat_penalty" to 1.1)

    /**
     * v9.11.0: the app-side context budget. The engine API does not
     * expose the loaded model's context size (llama_n_ctx lives behind
     * the AAR), so the default window is 4096 tokens ~= 16000 chars.
     * v9.12.1 "Context Diet": the trim now fires at 40% of that (was
     * 60%) - the extra KV headroom is what the prompt-budget work on
     * the prefill side needs to keep the writing speed up.
     */
    const val CONTEXT_WINDOW_CHARS = 16000
    const val CONTEXT_BUDGET_CHARS = 6400

    /** v9.13.2 "Small Model Honesty": a 230M-class tiny model trims at
     *  HALF the regular budget - garbage replayed from recent history
     *  (a hallucination a few turns back) hurts tiny models most. */
    const val TINY_CONTEXT_BUDGET_CHARS = 3200

    /** The family a model file belongs to (case-insensitive substring). */
    fun familyOf(modelFile: String): String {
        val n = modelFile.lowercase(Locale.US)
        return when {
            "qwen" in n -> "qwen"
            "lfm" in n -> "lfm"
            else -> "default"
        }
    }

    fun defaultsFor(family: String): Map<String, Double> = when (family) {
        "qwen" -> QWEN_DEFAULTS
        "lfm" -> LFM_DEFAULTS
        else -> OTHER_DEFAULTS
    }

    private fun dir(c: Context): File =
        File(c.filesDir, "model_settings").apply { mkdirs() }

    private fun fileFor(c: Context, modelFile: String): File =
        File(dir(c), modelFile + ".txt")

    /**
     * The current model as (fileName, label) - the loaded one when the
     * engine is warm, else the last one the user had active. Null when
     * NOVA has never had a model at all.
     */
    fun currentModel(c: Context): Pair<String, String>? {
        NovaEngine.activeModelPath?.let { p ->
            return Pair(p.substringAfterLast('/'),
                NovaEngine.activeModelLabel.ifBlank { p.substringAfterLast('/') })
        }
        val s = Settings(c)
        val p = s.lastModelPath ?: return null
        return Pair(p.substringAfterLast('/'),
            s.lastModelLabel.ifBlank { p.substringAfterLast('/') })
    }

    /**
     * Effective profile for [modelFile]: the per-model override file's
     * values over the family defaults. Returns the five effective
     * values plus the set of keys that came from the override file
     * (for the "model settings" origin display).
     */
    fun effective(c: Context, modelFile: String): Pair<Map<String, Double>, Set<String>> {
        val custom = HashMap<String, Double>()
        try {
            val f = fileFor(c, modelFile)
            if (f.exists()) for (l in f.readText().lines()) {
                val i = l.indexOf('=')
                if (i <= 0) continue
                val k = l.substring(0, i).trim()
                val v = l.substring(i + 1).trim().toDoubleOrNull() ?: continue
                if (k in KEYS) custom[k] = v
            }
        } catch (e: Exception) { }
        val fam = defaultsFor(familyOf(modelFile))
        val values = LinkedHashMap<String, Double>()
        for (k in KEYS) values[k] = custom[k] ?: fam.getValue(k)
        return Pair(values, custom.keys)
    }

    /** The profile resolved for the model of the in-flight generation. */
    @Volatile
    var active: Map<String, Double> = emptyMap()
        private set

    /** The model file [active] belongs to ("" when none resolved yet). */
    @Volatile
    var activeModel: String = ""
        private set

    /** v9.13.0 "Audit Fixes" (HIGH 5): the last sampling set the live
     *  engine actually accepted (null until the first success this
     *  engine session). An identical set is never re-pushed - identical
     *  values used to rebuild the sampler EVERY turn. Cleared when the
     *  active model changes: the new engine instance starts at default
     *  sampling, so the profile must be pushed again. */
    @Volatile
    private var lastPushed: Map<String, Double>? = null

    /**
     * v9.11.0: called at generation start (once per turn, from
     * ncieSend, after ensureModelReady). Resolves the active model's
     * effective profile and stages it in [active].
     * v9.12.0 "Live Tuning": also pushes the effective values to the
     * live engine sampler via NovaEngineAdapter.setSampling (the v8.0
     * engine setters). Guarded by the one-way broken flag, launched off
     * the main thread, and a failure only demotes the "model settings"
     * status reply to "staged" - it never blocks or crashes the turn.
     */
    fun applyProfile(act: MainActivity) {
        val m = currentModel(act) ?: return
        // v9.13.0 "Audit Fixes" (HIGH 5a): the model changed - its fresh
        // sampler starts at defaults, so the last-pushed set no longer holds
        if (m.first != activeModel) lastPushed = null
        val (values, _) = effective(act, m.first)
        active = values
        activeModel = m.first
        // v9.13.0 (HIGH 5a): skip the push entirely when the effective
        // values are the set already pushed - identical values rebuilt
        // the sampler every single turn before
        if (lastPushed == values) return
        if (!NovaEngineAdapter.samplingBroken) {
            act.scope.launch(Dispatchers.IO) {
                // v9.13.0 (HIGH 5b): never push while a generation is in
                // flight - an async push landing mid-generation is exactly
                // what could throw against the live sampler. Defer to the
                // next turn (lastPushed is still unset, so the next
                // applyProfile retries). Re-check the values too: another
                // push may have landed first.
                if (NcieChat.generating) return@launch
                if (lastPushed == values) return@launch
                if (NovaEngineAdapter.setSampling(
                        values.getValue("temperature").toFloat(),
                        values.getValue("top_p").toFloat(),
                        values.getValue("top_k").toInt(),
                        values.getValue("min_p").toFloat(),
                        values.getValue("repeat_penalty").toFloat()))
                    lastPushed = values
            }
        }
    }

    // ------------------------------------------------ chat commands

    /** The "set <knob> <value>" reply, or the honest usage reply when
     *  the value is out of range / unparseable. */
    fun setCommand(c: Context, key: String, raw: String): String {
        val m = currentModel(c)
            ?: return "No model yet - download one from the Models screen first."
        val v = raw.toDoubleOrNull()
        val range = RANGES[key]
        if (v == null || range == null || v < range.start || v > range.endInclusive)
            return usage()
        val vals = readOverrides(c, m.first)
        vals[key] = v
        try {
            val sb = StringBuilder()
            for (k in KEYS) if (k in vals) sb.append(k).append('=').append(fmt(vals.getValue(k))).append('\n')
            fileFor(c, m.first).writeText(sb.toString())
        } catch (e: Exception) {
            return "Could not save the setting (storage error)."
        }
        return DISPLAY.getValue(key) + " set to " + fmt(v) + " for " + m.second
    }

    /** The "model settings" listing: monospace, with each value's origin. */
    fun showCommand(c: Context): String {
        val m = currentModel(c)
            ?: return "No model yet - download one from the Models screen first."
        val (values, custom) = effective(c, m.first)
        val fam = familyOf(m.first)
        val sb = StringBuilder("```\nModel: " + m.second + "  (" + m.first + ")\n")
        for (k in KEYS) {
            sb.append("  ").append(k).append(" = ").append(fmt(values.getValue(k)))
            sb.append("   (").append(if (k in custom) "custom" else "family default: " + fam)
            sb.append(")\n")
        }
        sb.append("```")
        // v9.12.0 "Live Tuning": honest, deterministic status - "(live)"
        // only once a setSampling call has actually succeeded this
        // session; otherwise the values are staged, not applied.
        sb.append(if (NovaEngineAdapter.samplingLive) " (live)"
                  else " (staged - engine not updated)")
        return sb.toString()
    }

    /** The "reset model settings" reply - deletes the override file. */
    fun resetCommand(c: Context): String {
        val m = currentModel(c)
            ?: return "No model yet - download one from the Models screen first."
        try { fileFor(c, m.first).delete() } catch (e: Exception) { }
        return "Model settings reset to " + familyOf(m.first) +
            " family defaults for " + m.second
    }

    private fun usage(): String =
        "Usage:\n" +
            "set temperature 0-2\n" +
            "set top p 0-1\n" +
            "set top k 0-100\n" +
            "set min p 0-0.3\n" +
            "set repeat penalty 1-2\n" +
            "Also: model settings, reset model settings"

    private fun readOverrides(c: Context, modelFile: String): HashMap<String, Double> {
        val vals = HashMap<String, Double>()
        try {
            val f = fileFor(c, modelFile)
            if (f.exists()) for (l in f.readText().lines()) {
                val i = l.indexOf('=')
                if (i <= 0) continue
                val k = l.substring(0, i).trim()
                val v = l.substring(i + 1).trim().toDoubleOrNull() ?: continue
                if (k in KEYS) vals[k] = v
            }
        } catch (e: Exception) { }
        return vals
    }

    /** 20.0 -> "20", 0.70 -> "0.7" - values read back the way they read in. */
    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ------------------------------------------------ context trimming

    /**
     * v9.11.0: token-budget-aware context trimming, called at
     * generation start. [chat].messages is the in-memory mirror of the
     * conversation the engine holds in its KV cache; once its total
     * text exceeds [CONTEXT_BUDGET_CHARS] (40% of the assumed window),
     * the OLDEST messages go first, always in whole user+assistant
     * pairs - never the current question, which is not in the list yet
     * (startGeneration adds it after this runs), and never the most
     * recent pair. The rolling summary (NcieSummary) already preserves
     * the gist of what is dropped; the caller sets needsContextCarry
     * and resets the engine so the freed KV is actually freed.
     * Deterministic, no LLM. Returns true when something was dropped.
     */
    fun trimContext(act: MainActivity, chat: Chat): Boolean {
        val msgs = chat.messages
        // v9.13.2 "Small Model Honesty": the tiny-model history diet -
        // a 230M-class model trims at 3200 chars (20%) instead of 6400
        // (40%): its recent history is the first thing that poisons it.
        val budget = if (NcieChat.tinyModelFile(currentModel(act)?.first ?: ""))
            TINY_CONTEXT_BUDGET_CHARS else CONTEXT_BUDGET_CHARS
        var total = 0
        for (m in msgs) total += m.text.length
        if (total <= budget || msgs.size <= 2) return false
        var t = total
        var drop = 0
        while (t > budget && msgs.size - drop > 2) {
            // whole pairs: a leading USER plus its ASSISTANT reply; a
            // leading orphan (an interrupted turn) goes alone
            val step = if (msgs[drop].role == Role.USER && msgs.size - drop > 3) 2 else 1
            for (k in 0 until step) {
                t -= msgs[drop].text.length
                drop++
            }
        }
        if (drop == 0) return false
        val keep = ArrayList<Msg>(msgs.size - drop)
        for (i in drop until msgs.size) keep.add(msgs[i])
        msgs.clear()
        msgs.addAll(keep)
        act.scope.launch(Dispatchers.IO) {
            try { ChatStore.save(act, chat) } catch (e: Exception) { }
        }
        return true
    }
}
