package org.nova

import android.content.Context
import android.os.SystemClock
import org.nova.ncie.android.NovaEngineAdapter

/**
 * v9.33.34 "Measured speed": run one short, fixed generation and report what
 * THIS phone actually does, instead of a guess.
 *
 * Decode speed on a CPU is memory-bandwidth bound, so it depends on the model,
 * the quantization, the phone and how hot it currently is - the only honest
 * number is a measurement on the device. This runs one throwaway turn, counts
 * the tokens the engine emits, and divides by the time they took.
 *
 * The measurement is split in two, because they are different costs:
 *  - time to first token  = prefill (reading the prompt) + one decode step
 *  - decode tokens/second = the steady-state rate once it is going
 */
object SpeedTest {

    /** Short and unambiguous, so every model actually generates the tokens. */
    private const val PROMPT =
        "(Write two short sentences about why the sky is blue. Plain text only.)"

    /** Enough to reach a steady rate, few enough to finish quickly. */
    private const val TOKENS = 64

    class Result(val firstTokenMs: Long, val tokens: Int, val decodeMs: Long) {
        /** Tokens per second AFTER the first one - the steady-state rate. */
        val tokensPerSecond: Double
            get() = if (decodeMs <= 0L || tokens <= 1) 0.0 else (tokens - 1) * 1000.0 / decodeMs

        val usable: Boolean get() = tokens > 1 && decodeMs > 0L
    }

    /**
     * Runs the measurement. Returns null when no model is loaded.
     *
     * The test prompt lands in the engine's context, so this always clears the
     * engine afterwards and flags that it did - a benchmark must never cost
     * the user their conversation memory.
     */
    suspend fun run(ctx: Context): Result? {
        if (!NovaEngine.isModelLoaded) return null
        val t0 = SystemClock.elapsedRealtime()
        var first = 0L
        var last = 0L
        var tokens = 0
        try {
            NovaEngineAdapter.stream(PROMPT, TOKENS).collect { chunk ->
                val now = SystemClock.elapsedRealtime()
                if (first == 0L) first = now
                last = now
                if (chunk.isNotEmpty()) tokens++
            }
        } catch (e: Exception) {
            // a failed benchmark is not a failed app - report what we got
            NovaLog.quiet("SpeedTest", e)
        }
        // Clear the throwaway turn, and tell the chat to re-send its own
        // history next time so the conversation survives the measurement.
        try {
            val app = ctx.applicationContext
            if (NovaEngine.resetConversation(app, Settings(app).systemPrompt)) {
                NovaEngine.contextResetExternally = true
            }
        } catch (e: Exception) { NovaLog.quiet("SpeedTest", e) }
        val r = Result(firstTokenMs = first, tokens = tokens,
            decodeMs = if (first == 0L) 0L else last - first)
        return if (r.tokens == 0) null else r
    }
}
