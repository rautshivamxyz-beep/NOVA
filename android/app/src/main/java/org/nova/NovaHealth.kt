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
        try { NcieDream.schedule(ctx) } catch (e: Exception) { }
        try { NcieLeftovers.rearm(ctx) } catch (e: Exception) { }
        try { ScheduledSends.armNext(ctx) } catch (e: Exception) { }
    }

    fun modelOk(ctx: Context): Boolean = try {
        val p = Settings(ctx).lastModelPath
        p != null && p.isNotBlank() && File(p).exists()
    } catch (e: Exception) { false }

    fun notificationsOk(ctx: Context): Boolean =
        try { NovaListener.isEnabled(ctx) } catch (e: Exception) { false }

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
        Check("Alarms armed", true,
            "Dream mode, reminders and recurring reminders. Re-armed whenever " +
                "you open NOVA and after a reboot.",
            { armAll(ctx) })
    )

    /** The number of checks that need attention. */
    fun problems(ctx: Context): Int = try {
        checks(ctx).count { !it.ok }
    } catch (e: Exception) { 0 }

    fun requestBattery(ctx: Context) {
        try {
            val i = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:" + ctx.packageName)
            launch(ctx, i)
        } catch (e: Exception) { }
    }

    private fun launch(ctx: Context, i: Intent) {
        try {
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) { }
    }
}
