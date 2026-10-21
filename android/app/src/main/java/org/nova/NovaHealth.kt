package org.nova

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import org.nova.ncie.android.NcieDream
import org.nova.ncie.android.NcieLeftovers
import java.io.File

/**
 * v9.21.0 "Reliable + Easy": one place that knows whether NOVA's moving
 * parts are actually working, and one place that re-arms them.
 *
 * The lesson behind it: almost every "NOVA doesn't work" report was a
 * SILENT failure - a permission never granted, or an alarm that was never
 * armed, so the feature simply did nothing and said nothing. [checks]
 * makes that state visible and [armAll] makes the alarms self-healing.
 */
object NovaHealth {

    /** One line of the health screen. [fix] opens the screen that repairs it. */
    class Check(val label: String, val ok: Boolean, val detail: String,
                val fix: (() -> Unit)?)

    /**
     * Arm every alarm NOVA owns. Idempotent - safe at app start, on boot,
     * and from the health screen. Before v9.21.0 the nightly Dream alarm
     * was armed ONLY when the morning briefing ran, so after a reboot it
     * silently stopped until the user said "good morning" again.
     */
    fun armAll(ctx: Context) {
        try { NcieDream.schedule(ctx) } catch (e: Exception) { NovaLog.quiet("NovaHealth", e) }
        try { NcieLeftovers.rearm(ctx) } catch (e: Exception) { NovaLog.quiet("NovaHealth", e) }
        try { ScheduledSends.armNext(ctx) } catch (e: Exception) { NovaLog.quiet("NovaHealth", e) }
    }

    fun modelOk(ctx: Context): Boolean = try {
        val p = Settings(ctx).lastModelPath
        p != null && p.isNotBlank() && File(p).exists()
    } catch (e: Exception) { false }

    fun notificationsOk(ctx: Context): Boolean =
        try { NovaListener.isEnabled(ctx) } catch (e: Exception) { false }

    /** v9.33.7: is the always-armed nightly Dream alarm really scheduled?
     *  This check was hard-coded to true, so the health screen could never
     *  report a missing alarm - the exact silent failure it exists to catch. */
    fun alarmsArmed(ctx: Context): Boolean =
        try { NcieDream.isArmed(ctx) } catch (e: Exception) { false }

    private fun granted(ctx: Context, p: String): Boolean = try {
        ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    fun smsOk(ctx: Context): Boolean = granted(ctx, android.Manifest.permission.SEND_SMS)
    fun phoneOk(ctx: Context): Boolean = granted(ctx, android.Manifest.permission.CALL_PHONE)
    fun cameraOk(ctx: Context): Boolean = granted(ctx, android.Manifest.permission.CAMERA)

    /** True when Android is NOT allowed to kill NOVA's background work. */
    fun batteryOk(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) { true }

    /** True on a fresh install that hasn't finished the setup wizard. */
    fun needsSetup(ctx: Context): Boolean = try {
        !Settings(ctx).setupDone
    } catch (e: Exception) { false }

    fun openModels(ctx: Context) = launch(ctx, Intent(ctx, ModelsActivity::class.java))
    fun openSetup(ctx: Context) = launch(ctx, Intent(ctx, SetupActivity::class.java))

    /** The red/green list the health screen renders. */
    fun checks(ctx: Context): List<Check> = listOf(
        Check("A model is loaded", modelOk(ctx),
            "NOVA cannot answer until a model is downloaded and loaded.",
            { openModels(ctx) }),
        Check("Notification access", notificationsOk(ctx),
            "Powers \"what did I miss\", the money radar, the scam shield, the " +
                "package tracker and the auto-responder.",
            { launch(ctx, Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }),
        Check("SMS permission", smsOk(ctx),
            "Lets NOVA send a text for you - scheduled texts and the guardian/SOS.",
            { openSetup(ctx) }),
        Check("Phone permission", phoneOk(ctx),
            "Lets NOVA place a call for you.",
            { openSetup(ctx) }),
        Check("Camera permission", cameraOk(ctx),
            "Lets you photograph a page and have it read into the chat.",
            { openSetup(ctx) }),
        Check("Battery optimisation off", batteryOk(ctx),
            "Android - especially Realme/Oppo - kills NOVA's background parts " +
                "while this is on, so reminders and notifications stop.",
            { requestBattery(ctx) }),
        Check("Alarms armed", alarmsArmed(ctx),
            "Dream mode, reminders and recurring reminders. Re-armed whenever " +
                "you open NOVA and after a reboot.",
            { armAll(ctx) })
    )

    /** The number of checks that need attention. */
    fun problems(ctx: Context): Int = try {
        checks(ctx).count { !it.ok }
    } catch (e: Exception) { 0 }

    /**
     * v9.32.8 "Polish": the whole checklist as plain text, for the chat.
     * Almost every "NOVA doesn't work" report is a missing access - a feature
     * that is implemented, wired and simply switched off. This says which,
     * and what to do about each.
     */
    fun report(ctx: Context): String {
        val cs = checks(ctx)
        val bad = cs.count { !it.ok }
        val head = if (bad == 0)
            "Everything NOVA needs is set up. All features are available."
        else
            bad.toString() + " thing(s) still need setting up - these features " +
                "cannot work until they are:"
        val body = head + "\n\n" + cs.joinToString("\n") { c ->
            (if (c.ok) "\u2713 " else "\u2717 ") + c.label +
                (if (c.ok) "" else "\n   " + c.detail)
        }
        // v9.33.15 "Nothing fails silently": the count of exceptions NOVA
        // deliberately swallowed this session. Zero is the healthy answer;
        // anything else is the trail to why a feature looked like it did
        // nothing. Detail is in logcat under the NOVA tag.
        // v9.33.18: features that quietly do LESS when a dependency is
        // missing. Shown so a silent downgrade is visible, not surprising.
        return body + "\n\n" + NovaLog.summary() + "\n" + capabilities(ctx)
    }

    /** v9.33.18: the two features that degrade silently - semantic search
     *  (needs the bundled embedder) and offline Wikipedia (needs its
     *  download). "On"/"ready" is the healthy answer. */
    fun capabilities(ctx: Context): String {
        val embedder = try {
            ctx.assets.open("embedder.onnx").use { true }
        } catch (e: Exception) { false }
        val semantic = if (embedder) "on" else "off - keyword search only (embedder not bundled)"
        val wiki = if (WikiCore.isReady(ctx)) "ready" else "not downloaded - lookups go online only"
        val lite = if (DeviceCapabilities.isLowRam(ctx))
            "on - semantic search is skipped to keep the model in RAM on this phone"
        else "off"
        return "semantic search: " + semantic + "\noffline Wikipedia: " + wiki +
            "\nlite mode: " + lite
    }

    fun requestBattery(ctx: Context) {
        try {
            val i = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:" + ctx.packageName)
            launch(ctx, i)
        } catch (e: Exception) { NovaLog.quiet("NovaHealth", e) }
    }

    private fun launch(ctx: Context, i: Intent) {
        try {
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) { NovaLog.quiet("NovaHealth", e) }
    }
}
