package org.nova

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Build.VERSION

/**
 * Device capability probing used by the model manager to recommend models.
 */
object DeviceCapabilities {

    fun memoryInfo(context: Context): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also { am(context).getMemoryInfo(it) }

    private fun am(context: Context) =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun totalRamGb(context: Context): Double = memoryInfo(context).totalMem / 1e9

    /** Below this the phone cannot afford a second model in memory. */
    const val LOW_RAM_GB = 5.0

    /** v9.33.33 "Lite mode": a 4 GB phone holds the chat model and very little
     *  else. Cached - totalMem cannot change while the process lives. */
    @Volatile private var lowRamCache: Boolean? = null
    fun isLowRam(context: Context): Boolean {
        lowRamCache?.let { return it }
        // Fail closed: if probing fails, assume low RAM and avoid memory-heavy features.
        val low = try { totalRamGb(context) < LOW_RAM_GB } catch (e: Exception) { true }
        lowRamCache = low
        return low
    }

    fun availableRamGb(context: Context): Double = memoryInfo(context).availMem / 1e9

    fun cpuDescription(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val soc = if (VERSION.SDK_INT >= 31 && !Build.SOC_MODEL.isNullOrBlank()) {
            Build.SOC_MODEL
        } else null
        return if (soc != null) "$soc ($abi)" else abi
    }

    /**
     * How well a model file of [sizeBytes] is expected to run on this device.
     *
     * Rule of thumb: llama.cpp needs roughly the file size plus ~30-40% again
     * for KV cache and runtime overhead, and Android itself keeps 2-3 GB of
     * RAM. These thresholds compare the file against TOTAL RAM — generous,
     * because "tight" still lets the user try (with a warning).
     */
    fun fitFor(sizeBytes: Long, context: Context): Fit {
        val total = memoryInfo(context).totalMem
        return when {
            sizeBytes <= total * 0.30 -> Fit.COMFORTABLE
            sizeBytes <= total * 0.60 -> Fit.TIGHT
            else -> Fit.TOO_BIG
        }
    }

    enum class Fit(val label: String, val emoji: String) {
        COMFORTABLE("Runs well", "✓"),
        TIGHT("Tight — close other apps first", "△"),
        TOO_BIG("Too large for this device", "✗")
    }
}
