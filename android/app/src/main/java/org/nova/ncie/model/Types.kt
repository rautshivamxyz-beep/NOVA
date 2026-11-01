package org.nova.ncie.model

/** What kind of task the kernel thinks this is. */
enum class Intent { CHAT, CALCULATION, UNKNOWN }

/** Which execution path the Decision Kernel chose. */
enum class Route { TOOL, LLM, TOOL_THEN_LLM, CACHE }

/** ① ANALYZE — everything the planner needs to know about the request. */
data class Analysis(
    val text: String,
    val intent: Intent,
    /** 0.0 (trivial) .. 1.0 (very hard) — drives thinking/context budget. */
    val complexity: Double,
    /** True when a deterministic tool alone can fully answer. */
    val toolSufficient: Boolean,
    val keywords: List<String>,
)

/** ② PLAN — the Decision Kernel's decision. */
data class Plan(
    val route: Route,
    val toolName: String?,
    /** Max tokens the LLM may generate (Thinking Budget Engine hook). */
    val thinkingBudgetTokens: Int,
    /** Max characters of context to inject (Context Budget hook). */
    val contextBudgetChars: Int,
    val rationale: String,
)

/** ④ VERIFY — the Verifier's verdict on an executed result. */
data class VerifyResult(
    val passed: Boolean,
    /** 0.0 .. 1.0 quality score; 1.0 = verified-correct. */
    val qualityScore: Double,
    val notes: String,
)

/** The pipeline trace — one entry per phase, with wall-clock ms. */
data class PhaseTrace(val phase: String, val ms: Long, val detail: String)

/** FINAL RESPONSE — everything the caller sees. */
data class NovaResponse(
    val answer: String,
    val plan: Plan,
    val verify: VerifyResult,
    val repaired: Boolean,
    val cacheHit: Boolean,
    val llmUsed: Boolean,
    val trace: List<PhaseTrace>,
)
