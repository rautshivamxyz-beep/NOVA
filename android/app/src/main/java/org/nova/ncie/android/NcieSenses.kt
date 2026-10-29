package org.nova.ncie.android

import org.nova.NovaLog

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs

/**
 * Tier 1 "device senses" (v9.33.86): "battery" / "am i online" /
 * "device status" - what the phone itself is doing, answered
 * deterministically. No model call, no network request, and NO new
 * permission:
 *
 *  - battery level and charging state need no permission (the sticky
 *    ACTION_BATTERY_CHANGED broadcast, or BatteryManager's capacity property)
 *  - the network type uses ACCESS_NETWORK_STATE, already declared for the
 *    online-offer logic
 *  - the screen state comes from PowerManager, which needs none
 *  - free storage is StatFs over the app's OWN directory
 *
 * Nothing read here is stored, logged as content, or sent anywhere: it is
 * read, formatted and shown once. Asking the model "how is my battery" used
 * to produce a paragraph of guesses about a device it cannot see; this answers
 * before any model is loaded.
 *
 * [read] is the only Android-dependent part. [format] is pure, so the wording
 * and the honesty rules are pinned by JVM unit tests.
 */
object NcieSenses {

    /** What the phone is doing right now. -1 means "could not read". */
    data class State(
        val batteryPercent: Int,
        val charging: Boolean,
        val online: Boolean,
        val network: String,
        val screenOn: Boolean,
        val freeMb: Long)

    fun read(ctx: Context): State {
        var pct = -1
        var charging = false
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (pct < 0) {
                val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (lvl >= 0 && scale > 0) pct = lvl * 100 / scale
            }
        } catch (e: Exception) { NovaLog.quiet("NcieSenses", e) }

        var online = false
        var net = "no connection"
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val n = cm?.activeNetwork
            val c = if (n != null) cm?.getNetworkCapabilities(n) else null
            if (c != null) {
                online = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                net = when {
                    c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wi-fi"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "connected"
                }
                if (!online) net = net + " (no internet)"
            }
        } catch (e: Exception) { NovaLog.quiet("NcieSenses", e) }

        var screenOn = true
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            screenOn = pm?.isInteractive ?: true
        } catch (e: Exception) { NovaLog.quiet("NcieSenses", e) }

        var freeMb = -1L
        try {
            freeMb = StatFs(ctx.filesDir.absolutePath).availableBytes / (1024L * 1024L)
        } catch (e: Exception) { NovaLog.quiet("NcieSenses", e) }

        return State(pct, charging, online, net, screenOn, freeMb)
    }

    /**
     * The reply. Pure - no Android, no IO - so a JVM unit test can pin it.
     *
     * The honesty rules matter more than the layout: a battery that could not
     * be read is "not readable", NEVER "0%" (which would read as a dead
     * phone), and an offline phone says so without alarm, because everything
     * except online lookups still works.
     */
    fun format(s: State): String {
        val sb = StringBuilder("```\nDevice:\n")
        val batt = when {
            s.batteryPercent < 0 -> "not readable on this phone"
            s.charging -> s.batteryPercent.toString() + "% (charging)"
            s.batteryPercent <= 15 -> s.batteryPercent.toString() + "% (low)"
            else -> s.batteryPercent.toString() + "%"
        }
        sb.append("  battery: ").append(batt).append('\n')
        sb.append("  network: ").append(s.network).append('\n')
        sb.append("  screen: ").append(if (s.screenOn) "on" else "off").append('\n')
        sb.append("  storage free: ")
            .append(if (s.freeMb < 0) "unknown" else s.freeMb.toString() + " MB")
            .append('\n')
        sb.append("```")
        val notes = ArrayList<String>()
        if (s.batteryPercent in 0..15 && !s.charging)
            notes.add("Battery is low - charge before a long model run.")
        if (s.freeMb in 0..499)
            notes.add("Storage is nearly full - downloads and backups may fail.")
        if (!s.online)
            notes.add("No internet right now, so online lookups are unavailable. " +
                "Everything else still works offline.")
        if (notes.isNotEmpty()) sb.append('\n').append(notes.joinToString("\n"))
        return sb.toString()
    }
}
