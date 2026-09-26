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
 */
val NovaEngineAdapter: StreamLlmEngine = StreamLlmEngine(
    engineName = { NovaEngine.activeModelLabel.ifBlank { "llama.cpp" } },
    loaded = { NovaEngine.isModelLoaded },
    send = { prompt, maxTokens -> NovaEngine.send(prompt, maxTokens) },
)
