package org.nova

import android.graphics.Color

/**
 * App-wide colors, switchable between dark (default) and light.
 * Every screen reads from here so the light theme works everywhere.
 */
object NovaTheme {

    var dark = true
    var bg = Color.parseColor("#0A0D12")
    var pill = Color.parseColor("#141926")
    var surface = Color.parseColor("#171C26")
    var border = Color.parseColor("#242C3C")
    var divider = Color.parseColor("#1A2030")
    var text = Color.parseColor("#EAF0FA")
    var dim = Color.parseColor("#8B94A7")
    var accent = Color.parseColor("#5B9BFF")
    var accentDeep = Color.parseColor("#2E6BE6")
    var bubble = Color.parseColor("#2E6BE6")
    var sendDim = Color.parseColor("#232B3A")
    var sendDimText = Color.parseColor("#5A6579")
    var scrim = Color.parseColor("#99000000")

    fun apply(darkTheme: Boolean) {
        dark = darkTheme
        if (darkTheme) {
            bg = Color.parseColor("#0A0D12")
            pill = Color.parseColor("#141926")
            surface = Color.parseColor("#171C26")
            border = Color.parseColor("#242C3C")
            divider = Color.parseColor("#1A2030")
            text = Color.parseColor("#EAF0FA")
            dim = Color.parseColor("#8B94A7")
            accent = Color.parseColor("#5B9BFF")
            accentDeep = Color.parseColor("#2E6BE6")
            bubble = Color.parseColor("#2E6BE6")
            sendDim = Color.parseColor("#232B3A")
            sendDimText = Color.parseColor("#5A6579")
            scrim = Color.parseColor("#99000000")
        } else {
            bg = Color.parseColor("#FFFFFF")
            pill = Color.parseColor("#F6F7FB")
            surface = Color.parseColor("#EFF1F7")
            border = Color.parseColor("#E1E5EE")
            divider = Color.parseColor("#E7EAF2")
            text = Color.parseColor("#141B2E")
            dim = Color.parseColor("#6B7386")
            accent = Color.parseColor("#2E6BE6")
            accentDeep = Color.parseColor("#2E6BE6")
            bubble = Color.parseColor("#2E6BE6")
            sendDim = Color.parseColor("#E4E7EE")
            sendDimText = Color.parseColor("#A0A7B6")
            scrim = Color.parseColor("#66000000")
        }
    }
}
