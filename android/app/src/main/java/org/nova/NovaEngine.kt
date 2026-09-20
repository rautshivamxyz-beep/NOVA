package org.nova

import android.content.Context
import android.os.SystemClock
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * App-scoped holder for the llama.cpp inference engine.
 *
 * The engine is a process-wide singleton provided by the com.arm.aichat
 * binding (llama-release.aar, built from ggml-org/llama.cpp's
 * examples/llama.android). This wrapper adds:
 *
 *  - safe initialization (waits for the native lib to finish loading)
 *  - load / reload with a system prompt
 *  - "new conversation" (the native layer keeps chat history, so a fresh
 *    conversation means unloading and loading the model again)
 */
object NovaEngine {

    @Volatile
    private var engineRef: InferenceEngine? = null

    @Volatile
    var activeModelPath: String? = null
        private set

    @Volatile
    var activeModelLabel: String = ""
        private set

    suspend fun get(context: Context): InferenceEngine =
        engineRef ?: AiChat.getInferenceEngine(context.applicationContext).also { engineRef = it }

    /**
     * Current engine state, or null if the engine has not been created yet.
     */
    suspend fun state(context: Context): InferenceEngine.State? = try {
        get(context).state.value
    } catch (e: Exception) {
        null
    }

    /**
     * Waits until the native library is fully initialized and no other
     * engine operation is in flight, then makes sure no model is loaded
     * (unloading / resetting error state as needed).
     */
    private suspend fun ensureReady(engine: InferenceEngine) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            if (SystemClock.elapsedRealtime() - start > 90_000L) {
                throw IllegalStateException("Engine busy or init timed out")
            }
            when (engine.state.value) {
                is InferenceEngine.State.Uninitialized,
                is InferenceEngine.State.Initializing,
                is InferenceEngine.State.LoadingModel,
                is InferenceEngine.State.UnloadingModel,
                is InferenceEngine.State.ProcessingSystemPrompt,
                is InferenceEngine.State.ProcessingUserPrompt,
                is InferenceEngine.State.Generating,
                is InferenceEngine.State.Benchmarking -> delay(150)

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
     * Loads a GGUF model. Sends the system prompt right after load as the
     * binding requires. A blank system prompt is skipped.
     *
     * @param label human readable model name for the UI
     */
    suspend fun load(context: Context, path: String, label: String, systemPrompt: String) {
        if (loading) return // a load is already running
        loading = true
        try {
            val engine = get(context)
            ensureReady(engine)
            engine.loadModel(path)
            if (systemPrompt.isNotBlank()) {
                try {
                    engine.setSystemPrompt(systemPrompt)
                } catch (e: Exception) {
                    // Not fatal: model still works without a system prompt
                }
            }
            activeModelPath = path
            activeModelLabel = label
        } finally {
            loading = false
        }
    }

    /**
     * Reloads the currently active model, starting a fresh conversation.
     */
    suspend fun reload(context: Context, systemPrompt: String): Boolean {
        val path = activeModelPath ?: return false
        val label = activeModelLabel
        load(context, path, label, systemPrompt)
        return true
    }

    /**
     * Unloads the current model (if any) and clears the active model pointer.
     */
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

    /**
     * Sends a user message and returns the token stream.
     * Conversation history is kept by the native layer.
     */
    fun send(message: String, predictLength: Int): Flow<String> {
        val engine = requireNotNull(engineRef) { "No model loaded" }
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
