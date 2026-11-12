package org.nova.ncie.android

import android.content.Context
import android.os.Build
import org.nova.DeviceCapabilities
import org.nova.NovaHealth
import org.nova.ncie.learn.GapStore
import org.nova.ncie.evaluate.RegressionLab
import java.io.File
import java.util.Locale

/**
 * A bounded, on-device improvement cycle for NOVA.
 *
 * It coordinates existing subsystems instead of creating a second learner:
 * health repair, memory consolidation (NcieEvolve), measured capability
 * gaps, generated data-only skills, parser self-tests and device-aware
 * background policy reporting.
 *
 * Safety boundary: this class never executes generated code, downloads
 * code, edits the APK, changes model weights, or changes inference settings.
 * Model quality changes through verified memory and validated skill data.
 */
object SelfEvolutionEngine {
    private const val REPORT = "self_evolution_report.txt"
    private const val MAX_GAP_PREVIEW = 3

    /** Run from the existing nightly Dream alarm. All work is local. */
    fun runNightly(ctx: Context): String {
        // Re-arm the alarms NOVA owns. This is idempotent and does not ask
        // Android for new permissions.
        try { NovaHealth.armAll(ctx) } catch (_: Exception) { }

        val checks = selfTests()
        val health = try { NovaHealth.checks(ctx) } catch (_: Exception) { emptyList() }
        val lowRam = try { DeviceCapabilities.isLowRam(ctx) } catch (_: Exception) { true }
        val totalRam = try { DeviceCapabilities.totalRamGb(ctx) } catch (_: Exception) { 0.0 }
        val freeRam = try { DeviceCapabilities.availableRamGb(ctx) } catch (_: Exception) { 0.0 }
        val version = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }

        val skills = try { NcieSkillForge.forged(ctx) } catch (_: Exception) { emptyList() }
        val ledger = readLines(File(ctx.filesDir, "nova_growth.txt"))
        val answered = ledger.count { it.contains("\tanswer\t") || it.contains("\tskill\t") }
        val gaps = try {
            val f = File(ctx.filesDir, "capability_gaps.txt")
            if (f.exists()) GapStore.parse(f.readText()).top(MAX_GAP_PREVIEW) else emptyList()
        } catch (_: Exception) { emptyList() }

        val failedHealth = health.filter { !it.ok }
        val sb = StringBuilder("SELF-EVOLUTION AUDIT\n")
        sb.append("app: ").append(version).append(" | Android ").append(Build.VERSION.RELEASE).append('\n')
        sb.append("device policy: ").append(if (lowRam) "lite mode (low-RAM safeguards)" else "standard mode")
            .append(" | RAM ").append(String.format(Locale.US, "%.1f/%.1f GB free/total", freeRam, totalRam)).append('\n')
        sb.append("self-tests: ").append(checks.count { it.second }).append('/')
            .append(checks.size).append(" passed\n")
        for ((name, ok) in checks) sb.append(if (ok) "  PASS " else "  FAIL ").append(name).append('\n')
        sb.append("health: ").append(if (failedHealth.isEmpty()) "all checks healthy" else failedHealth.size.toString() + " issue(s)")
            .append(" | alarms re-armed where possible\n")
        if (failedHealth.isNotEmpty()) {
            for (item in failedHealth.take(4)) sb.append("  needs attention: ").append(item.label).append(" - ").append(item.detail).append('\n')
        }
        sb.append("learning ledger: ").append(answered).append(" answered turns recorded\n")
        sb.append("validated self-authored skills: ").append(skills.size).append('/')
            .append(20).append('\n')
        sb.append("measured capability gaps: ").append(gaps.size).append(" top tracked\n")
        for (gap in gaps) {
            sb.append("  - ").append(gap.question.take(100)).append(" (asked ").append(gap.count).append(" times)\n")
        }
        sb.append("adaptation: ").append(if (lowRam)
            "background work stays lightweight; no extra model is loaded"
            else "background consolidation is permitted when Android allows it").append('\n')
        sb.append("model safety: weights and inference/sampling settings unchanged; generated skills remain data, not executable code")
        val report = sb.toString()
        try { NcieFiles.write(File(ctx.filesDir, REPORT), report) } catch (_: Exception) { }
        return report
    }

    /** Last completed audit, without starting another cycle. */
    fun lastSummary(ctx: Context): String = try {
        val f = File(ctx.filesDir, REPORT)
        if (f.exists()) f.readText().take(8_000) else ""
    } catch (_: Exception) { "" }

    /** Deterministic, model-free regression checks for core learning systems. */
    internal fun selfTests(): List<Pair<String, Boolean>> = RegressionLab.run()

    private fun readLines(file: File): List<String> = try {
        if (file.exists()) file.readLines().takeLast(400) else emptyList()
    } catch (_: Exception) { emptyList() }
}
