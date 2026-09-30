package org.nova

import android.content.Context
import android.os.SystemClock
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-scoped holder for the llama.cpp inference engine.
 *
 * IMPORTANT: model loading runs in [scope], which lives as long as the
 * process — NOT in any Activity's scope. That way leaving the Models
 * screen or rotating the phone never cancels a load halfway through
 * (that was the "job cancelled" bug). UIs observe [loadState].
 */
object NovaEngine {

    /** Lifecycle of a model load, for the UI to observe. */
    sealed class LoadState {
        object Idle : LoadState()
        data class Loading(val label: String) : LoadState()
        object Ready : LoadState()
        data class Failed(val label: String, val error: String?) : LoadState()
    }

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    /** Dismiss a terminal (Ready/Failed) state back to Idle. */
    fun acknowledgeLoad() {
        val s = _loadState.value
        if (s is LoadState.Ready || s is LoadState.Failed) _loadState.value = LoadState.Idle
    }

    /** App-lifetime scope: model loads run here so they survive navigation. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var engineRef: InferenceEngine? = null

    @Volatile
    var activeModelPath: String? = null
        private set

    @Volatile
    var activeModelLabel: String = ""
        private set

    /** True after any send() - the conversation context is no longer clean.
     *  Cleared by load() (fresh model = fresh context). Callers use it to
     *  skip a wasteful multi-second reload when the engine is already clean. */
    @Volatile
    var contextDirty: Boolean = false
        private set

    suspend fun get(context: Context): InferenceEngine =
        engineRef ?: AiChat.getInferenceEngine(context.applicationContext).also { engineRef = it }

    /**
     * Waits until the native library is initialized and no other engine
     * operation is in flight, then makes sure no model is loaded.
     */
    private suspend fun ensureReady(engine: InferenceEngine) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val timeout = when (engine.state.value) {
                is InferenceEngine.State.Uninitialized,
                is InferenceEngine.State.Initializing -> 60_000L
                else -> 600_000L
            }
            if (SystemClock.elapsedRealtime() - start > timeout) {
                throw IllegalStateException("engine busy or init timed out")
            }
            when (engine.state.value) {
                is InferenceEngine.State.Uninitialized,
                is InferenceEngine.State.Initializing,
                is InferenceEngine.State.LoadingModel,
                is InferenceEngine.State.UnloadingModel,
                is InferenceEngine.State.ProcessingSystemPrompt,
                is InferenceEngine.State.ProcessingUserPrompt,
                is InferenceEngine.State.Generating,
                is InferenceEngine.State.Benchmarking -> delay(200)

                is InferenceEngine.State.ModelReady -> engine.cleanUp()
                is InferenceEngine.State.Error -> engine.cleanUp()
                else -> return // Initialized
            }
        }
    }

    @Volatile
    private var loading = false

    /** True while a model load/reload is in progress. */
    val isLoading: Boolean get() = loading

    /**
     * Starts loading a model in the app scope (survives navigation).
     * Observe [loadState] for the result.
     */
    fun loadAsync(context: Context, path: String, label: String, systemPrompt: String) {
        if (loading) return
        loading = true
        _loadState.value = LoadState.Loading(label)
        val appContext = context.applicationContext
        scope.launch {
            try {
                load(appContext, path, label, systemPrompt)
                Settings(appContext).let {
                    it.lastModelPath = path
                    it.lastModelLabel = label
                }
                _loadState.value = LoadState.Ready
            } catch (e: CancellationException) {
                _loadState.value = LoadState.Failed(label, "cancelled")
            } catch (e: Exception) {
                _loadState.value = LoadState.Failed(label, e.message ?: "unknown error")
            } finally {
                loading = false
            }
        }
    }

    suspend fun load(context: Context, path: String, label: String, systemPrompt: String) {
        val engine = get(context)
        ensureReady(engine)
        engine.loadModel(path)
        // v9.11.0 "Inference Quality": a blank prompt (the user cleared
        // it in Settings) falls back to the strong concise base prompt -
        // the model always gets a system turn.
        val prompt = systemPrompt.ifBlank { Settings.DEFAULT_SYSTEM_PROMPT } +
            thinkingHint(path, label)
        if (prompt.isNotBlank()) {
            try {
                engine.setSystemPrompt(prompt)
            } catch (e: Exception) {
                // Not fatal: model still works without a system prompt
            }
        }
        activeModelPath = path
        activeModelLabel = label
        contextDirty = false

        // v5.7.0: speculative decoding - a small draft model proposes
        // tokens that the main model verifies in batches. Only Qwen3
        // targets (the draft must share the vocabulary). The binding
        // swallows failures, so speculation just stays off if anything
        // is missing.
        try {
            if (Settings(context.applicationContext).specDecoding) {
                findDraftModel(context.applicationContext)?.let {
                    engine.loadDraftModel(it.absolutePath)
                }
            } else {
                engine.unloadDraftModel()
            }
        } catch (e: Exception) { }
    }

    /** v5.7.0: the Qwen3 0.6B file, when a Qwen3 target is active. */
    private fun findDraftModel(ctx: Context): java.io.File? {
        val target = (activeModelLabel + " " + (activeModelPath ?: "")).lowercase()
        if (!target.contains("qwen3")) return null
        return ModelCatalog.modelsDir(ctx).listFiles { f: java.io.File ->
            f.extension == "gguf" && f.name.lowercase().contains("qwen3-0.6b")
        }?.firstOrNull()
    }

    /**
     * Reasoning models (Qwen3 / LFM Thinking) burn most of their response
     * time generating hidden thinking chains - often hundreds of tokens on
     * trivial questions. Tell them to keep it short so answers arrive fast.
     */
    private fun thinkingHint(path: String, label: String): String {
        val n = (label + " " + path.substringAfterLast('/')).lowercase()
        if ("think" !in n && "minicpm" !in n) return ""
        return "\n\n(You are a reasoning model. THINK BRIEFLY: at most ONE short " +
            "sentence of planning for routine questions; save step-by-step " +
            "reasoning for genuinely hard math or logic only. Never repeat the " +
            "question or restate your plan inside the thinking. Start the visible " +
            "answer immediately after thinking.)"
    }

    /** Reloads the active model, starting a fresh conversation. */
    fun reloadAsync(context: Context, systemPrompt: String) {
        val path = activeModelPath ?: return
        val label = activeModelLabel
        loadAsync(context, path, label, systemPrompt)
    }

    /**
     * v6.2.0: instant conversation reset ("new chat" without a model reload).
     *
     * The v6.2 engine allows setSystemPrompt() any time the model is ready,
     * not just right after load. Re-processing the system prompt clears the
     * KV cache and chat history inside the engine, so the app no longer has
     * to unload and reload the whole model file from flash (multi-seconds)
     * just to start a fresh conversation. The weights stay resident in RAM
     * and only the short system prompt is re-encoded - the model-in-RAM /
     * KV-cache / prompt-cache optimizations, with no quality change.
     *
     * [resetConversation] suspends until the context is clean and returns
     * success; [resetConversationAsync] is the fire-and-forget wrapper.
     * Both fall back to a full model reload on older engines.
     */
    @Volatile
    var resetting: Boolean = false
        private set

    suspend fun resetConversation(context: Context, systemPrompt: String): Boolean {
        val engine = engineRef ?: return false
        // v7.6: never fight an in-flight model load - "new chat" during a
        // model switch could double-drive the engine
        if (loading) return false
        if (!isModelLoaded) return false
        resetting = true
        try {
            // never fight an in-flight generation - wait, like reloadAsync
            val t0 = SystemClock.elapsedRealtime()
            while (isGenerating && SystemClock.elapsedRealtime() - t0 < 60_000) delay(200)
            // v9.11.0 "Inference Quality": same fallback as load() - a
            // blank prompt never wipes the base prompt off the engine.
            val prompt = systemPrompt.ifBlank { Settings.DEFAULT_SYSTEM_PROMPT } +
                thinkingHint(activeModelPath ?: "", activeModelLabel)
            engine.setSystemPrompt(prompt)
            contextDirty = false
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // engine too old or not in ModelReady state - full reload
            return try {
                load(context, activeModelPath ?: return false, activeModelLabel, systemPrompt)
                true
            } catch (e2: Exception) {
                false
            }
        } finally {
            resetting = false
        }
    }

    fun resetConversationAsync(context: Context, systemPrompt: String) {
        scope.launch { resetConversation(context, systemPrompt) }
    }

    suspend fun unload(context: Context) {
        activeModelPath = null
        activeModelLabel = ""
        val engine = engineRef ?: return
        val s = engine.state.value
        if (s is InferenceEngine.State.ModelReady || s is InferenceEngine.State.Error) {
            try {
                engine.cleanUp()
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    fun send(message: String, predictLength: Int): Flow<String> {
        val engine = requireNotNull(engineRef) { "No model loaded" }
        contextDirty = true   // the conversation context now holds this prompt
        return engine.sendUserPrompt(message, predictLength)
    }

    /**
     * v9.12.0 "Live Tuning": the v8.0 engine AAR exposes live sampling
     * setters (com.arm.aichat.InferenceEngine.setSampling - suspend,
     * ModelReady-guarded, off the main thread inside the impl). Thin
     * pass-through; a missing engine is a silent no-op, and the one-way
     * broken-flag failure policy lives one level up in NovaEngineAdapter.
     */
    suspend fun setSampling(temperature: Float, topP: Float, topK: Int,
                            minP: Float, repeatPenalty: Float) {
        val engine = engineRef ?: return
        engine.setSampling(temperature, topP, topK, minP, repeatPenalty)
    }

    val isModelLoaded: Boolean
        get() {
            val engine = engineRef ?: return false
            val s = engine.state.value
            return s is InferenceEngine.State.ModelReady ||
                s is InferenceEngine.State.Generating ||
                s is InferenceEngine.State.ProcessingSystemPrompt ||
                s is InferenceEngine.State.ProcessingUserPrompt ||
                s is InferenceEngine.State.Benchmarking
        }

    /** True while a generation is running on the engine. */
    val isGenerating: Boolean
        get() = engineRef?.state?.value is InferenceEngine.State.Generating
}
