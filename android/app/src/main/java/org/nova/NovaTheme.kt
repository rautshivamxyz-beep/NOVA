package org.nova

import android.graphics.Color

/**
 * App-wide colors, switchable between dark (default) and light.
 * Every screen reads from here so the light theme works everywhere.
 *
 * v8.0.0 "midnight" refresh: deeper backgrounds, cooler lifted surfaces
 * and a brighter cornflower accent. Replies and list rows moved into
 * cards - see the per-screen commits of this release.
 * Property names are unchanged so every existing call site keeps working;
 * only the values moved.
 */
object NovaTheme {

    var dark = true
    var bg = Color.parseColor("#06070B")
    var pill = Color.parseColor("#12141D")
    var surface = Color.parseColor("#1A1D28")
    var border = Color.parseColor("#2C3040")
    var divider = Color.parseColor("#15171F")
    var text = Color.parseColor("#F4F5FA")
    var dim = Color.parseColor("#959DB2")
    var accent = Color.parseColor("#6C9CFF")
    var accentDeep = Color.parseColor("#4E6FDB")
    var bubble = Color.parseColor("#4E6FDB")
    var sendDim = Color.parseColor("#22242F")
    var sendDimText = Color.parseColor("#5B6170")
    var scrim = Color.parseColor("#99000000")

    fun apply(darkTheme: Boolean) {
        dark = darkTheme
        if (darkTheme) {
            bg = Color.parseColor("#06070B")
            pill = Color.parseColor("#12141D")
            surface = Color.parseColor("#1A1D28")
            border = Color.parseColor("#2C3040")
            divider = Color.parseColor("#15171F")
            text = Color.parseColor("#F4F5FA")
            dim = Color.parseColor("#959DB2")
            accent = Color.parseColor("#6C9CFF")
            accentDeep = Color.parseColor("#4E6FDB")
            bubble = Color.parseColor("#4E6FDB")
            sendDim = Color.parseColor("#22242F")
            sendDimText = Color.parseColor("#5B6170")
            scrim = Color.parseColor("#99000000")
        } else {
            bg = Color.parseColor("#F7F8FC")
            pill = Color.parseColor("#EDEFF5")
            surface = Color.parseColor("#E5E8F0")
            border = Color.parseColor("#D9DDE8")
            divider = Color.parseColor("#E6E9F1")
            text = Color.parseColor("#141824")
            dim = Color.parseColor("#67707F")
            accent = Color.parseColor("#3E6BE8")
            accentDeep = Color.parseColor("#3050C8")
            bubble = Color.parseColor("#3050C8")
            sendDim = Color.parseColor("#E1E4EC")
            sendDimText = Color.parseColor("#A8AEBB")
            scrim = Color.parseColor("#66000000")
        }
    }
}
