package org.nova.ncie.android

import org.nova.NovaEngine
import org.nova.ncie.execute.StreamLlmEngine

/**
 * NOVA Android app → NCIE kernel bridge. The whole llama.cpp engine
 * becomes one LlmEngine component for the kernel.
 *
 * Wire it once, app-wide (Application class or MainActivity member):
 *
 *     val calc = CalculatorTool()
 *     val tools = ToolRegistry(listOf(calc))
 *     val nova = NovaKernel(
 *         analyzer = RuleBasedAnalyzer(),
 *         planner = DecisionKernel(tools),
 *         tools = tools,
 *         llm = NovaEngineAdapter,          // ← the real engine, not the stub
 *         verifier = MathVerifier(calc),
 *         learner = SimpleLearner(),
 *     )
 *
 * Notes on semantics:
 *  - nova.ask() blocks on generation — always call it off the main thread.
 *  - Conversation memory lives in the native engine (llama.cpp keeps the
 *    full context), same as the current chat: each ask() is one
 *    sendUserPrompt. NCIE's Learner cache is separate and additive.
 *  - "New chat" still goes through NovaEngine.resetConversation(...);
 *    the kernel does not own conversation state.
 *  - The stop button: NovaEngineAdapter.stop() cancels token collection
 *    and keeps the model loaded — identical to the current stop behavior.
 *  - The predictLength setting maps 1:1 to the kernel's thinking budget
 *    (maxTokens); NCIE just decides it per-request instead of globally.
 *
 * v9.12.0 "Live Tuning": this is an object now (the base class is open) so
 * it can carry engine-specific ports on top of the three kernel ports -
 * setSampling(...) below pushes the NcieTune profile into the live
 * sampler of engine v8.0 (com.arm.aichat.InferenceEngine.setSampling).
 */
object NovaEngineAdapter : StreamLlmEngine(
    engineName = { NovaEngine.activeModelLabel.ifBlank { "llama.cpp" } },
    loaded = { NovaEngine.isModelLoaded },
    send = { prompt, maxTokens -> NovaEngine.send(prompt, maxTokens) },
) {

    /**
     * v9.12.0 "Live Tuning": engine v8.0 (llama.cpp 6e60f35 + the
     * v5.3..v8.0 patch chain) exposes suspend sampling setters on the
     * live sampler. One-way failure policy: the first Throwable marks
     * the bridge broken for the rest of the session and it is never
     * retried - a tuning knob must never crash a generation or wedge
     * the app. The engine impl is suspend, ModelReady-guarded and does
     * its own off-main-thread hop, so this is safe to call from the UI
     * scope.
     * v9.13.0 "Audit Fixes" (HIGH 5c): the latch is no longer one-shot.
     * A single mid-generation failure just retries on the next turn;
     * only 3 CONSECUTIVE failures mark the bridge broken for the
     * session. Returns true when the engine accepted the set, so
     * NcieTune knows whether to record it as pushed.
     */
    @Volatile
    var samplingBroken: Boolean = false
        private set

    /** True once a setSampling call has succeeded this session. */
    @Volatile
    var samplingLive: Boolean = false
        private set

    /** Consecutive setSampling failures (reset by any success). */
    @Volatile
    private var samplingFails = 0

    suspend fun setSampling(temperature: Float, topP: Float, topK: Int,
                            minP: Float, repeatPenalty: Float): Boolean {
        if (samplingBroken) return false
        return try {
            NovaEngine.setSampling(temperature, topP, topK, minP, repeatPenalty)
            samplingLive = true
            samplingFails = 0
            true
        } catch (t: Throwable) {
            if (++samplingFails >= 3) samplingBroken = true
            false
        }
    }
}
