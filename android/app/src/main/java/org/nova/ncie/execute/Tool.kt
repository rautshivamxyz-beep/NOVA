package org.nova.ncie.execute

import org.nova.ncie.model.Analysis

/**
 * ③ EXECUTE — a deterministic capability of the engine.
 *
 * Tools are pure: no hidden state, same input → same output. That property
 * is what makes the Verify phase meaningful for tool-routed answers.
 */
interface Tool {
    fun name(): String
    /** Return true if this tool can fully answer the request. */
    fun canHandle(analysis: Analysis): Boolean
    /** Execute; return the final user-facing answer. */
    fun execute(analysis: Analysis): String
    /** v0.8.1 TOOL_THEN_LLM: like [canHandle], but the tool only
     *  contributes a computed FACT the answer needs, not the whole
     *  answer (an arithmetic expression buried inside a worded
     *  question). Default: never assists. */
    fun assists(analysis: Analysis): Boolean = false
    /** The fact to inject for a request this tool [assists]. Blank =
     *  nothing usable was found; the kernel degrades to the plain LLM
     *  path. Default: no facts. */
    fun contribute(analysis: Analysis): String = ""
}

class ToolRegistry(private val tools: List<Tool>) {
    fun bestToolFor(analysis: Analysis): Tool? = tools.firstOrNull { it.canHandle(analysis) }
    /** v0.8.1: the first tool that can contribute a fact (not a full
     *  answer) — the TOOL_THEN_LLM planner check. */
    fun bestAssistantFor(analysis: Analysis): Tool? = tools.firstOrNull { it.assists(analysis) }
    fun byName(name: String): Tool? = tools.firstOrNull { it.name() == name }
}

/**
 * The LLM is just another executor — one component, not the system.
 * Implementations later: llama.cpp binding (NOVA Android), MNN (NOVA-MNN),
 * a desktop llama-server client. The kernel never knows which one it talks to.
 */
interface LlmEngine {
    fun name(): String
    fun generate(prompt: String, maxTokens: Int): String
    fun isLoaded(): Boolean
}

/** Offline stand-in so the scaffold runs end-to-end with zero dependencies. */
class StubLlmEngine : LlmEngine {
    override fun name() = "stub"
    override fun isLoaded() = true
    override fun generate(prompt: String, maxTokens: Int): String =
        "[stub-llm] I would now think about: \"$prompt\" (up to $maxTokens tokens). " +
            "Wire NovaEngine / MnnEngine here to make NOVA real."
}
