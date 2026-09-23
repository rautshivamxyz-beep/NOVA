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
        val prompt = systemPrompt + thinkingHint(path, label)
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
    }

    /**
     * Reasoning models (Qwen3 / LFM Thinking) burn most of their response
     * time generating hidden thinking chains - often hundreds of tokens on
     * trivial questions. Tell them to keep it short so answers arrive fast.
     */
    private fun thinkingHint(path: String, label: String): String {
        val n = (label + " " + path.substringAfterLast('/')).lowercase()
        if ("think" !in n) return ""
        return "\n\n(You are a reasoning model. Keep your hidden thinking SHORT: " +
            "one or two brief lines for easy questions, detailed step-by-step " +
            "reasoning only for genuinely hard problems. Then answer directly.)"
    }

    /** Reloads the active model, starting a fresh conversation. */
    fun reloadAsync(context: Context, systemPrompt: String) {
        val path = activeModelPath ?: return
        val label = activeModelLabel
        loadAsync(context, path, label, systemPrompt)
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
}
