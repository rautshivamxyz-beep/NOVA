package org.nova.ncie.execute

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * An [LlmEngine] over a token stream — the shape every real engine exposes
 * (llama.cpp's `sendUserPrompt(...): Flow<String>`, MNN's Llm callback, a
 * desktop llama-server SSE stream). This is the bridge between the NCIE
 * kernel and NOVA's native engines.
 *
 * The three constructor ports are exactly the surface NOVA's NovaEngine
 * object already publishes, so wiring the Android app is a three-line
 * factory (see `android/NovaEngineAdapter.kt`):
 *
 *   - [engineName] : what model is active (for the phase trace)
 *   - [loaded]     : is a model resident in RAM
 *   - [send]       : (prompt, maxTokens) → cold Flow of token chunks
 *
 * generate() blocks the calling thread until the stream completes — call
 * it from a worker thread (Dispatchers.IO on Android). stop() may be
 * called from any other thread (the UI) to cancel the in-flight
 * collection: generation halts, the partial answer is returned, and the
 * model stays loaded — the same semantics the NOVA chat's stop button has.
 */
class StreamLlmEngine(
    private val engineName: () -> String,
    private val loaded: () -> Boolean,
    private val send: (prompt: String, maxTokens: Int) -> Flow<String>,
) : LlmEngine {

    @Volatile
    private var job: Job? = null

    @Volatile
    var lastGenerationStopped: Boolean = false
        private set

    override fun name(): String = engineName()

    override fun isLoaded(): Boolean = loaded()

    /** True while a generation is in flight. */
    val isGenerating: Boolean get() = job?.isActive == true

    override fun generate(prompt: String, maxTokens: Int): String {
        if (!loaded()) return "[engine] no model loaded"
        val sb = StringBuilder()
        var error: String? = null
        lastGenerationStopped = false
        try {
            runBlocking {
                job = coroutineContext.job
                try {
                    send(prompt, maxTokens).collect { chunk -> sb.append(chunk) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: "generation failed"
                }
            }
        } catch (_: CancellationException) {
            lastGenerationStopped = true
        } finally {
            job = null
        }
        error?.let { return "[engine error] $it" }
        return sb.toString()
    }

    /**
     * Cancel the in-flight generation (call from the UI thread while
     * generate() is blocking a worker thread). Returns immediately.
     */
 fun stop() {
        job?.cancel(CancellationException("stopped by user"))
    }
}
