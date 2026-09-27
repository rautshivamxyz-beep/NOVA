package org.nova

import android.graphics.Color

/**
 * App-wide colors, switchable between dark (default) and light.
 * Every screen reads from here so the light theme works everywhere.
 *
 * v7.7.0 "graphite + iris" refresh: the old navy-blue scheme is replaced
 * with a neutral graphite dark surface and a single vivid iris accent.
 * Property names are unchanged so every existing call site keeps working;
 * only the values moved.
 */
object NovaTheme {

    var dark = true
    var bg = Color.parseColor("#0A0B0E")
    var pill = Color.parseColor("#16181D")
    var surface = Color.parseColor("#1D2026")
    var border = Color.parseColor("#292C34")
    var divider = Color.parseColor("#1E2025")
    var text = Color.parseColor("#F3F4F8")
    var dim = Color.parseColor("#9BA1AD")
    var accent = Color.parseColor("#7C87FF")
    var accentDeep = Color.parseColor("#5A5FE0")
    var bubble = Color.parseColor("#5A5FE0")
    var sendDim = Color.parseColor("#26272E")
    var sendDimText = Color.parseColor("#5B6170")
    var scrim = Color.parseColor("#99000000")

    fun apply(darkTheme: Boolean) {
        dark = darkTheme
        if (darkTheme) {
            bg = Color.parseColor("#0A0B0E")
            pill = Color.parseColor("#16181D")
            surface = Color.parseColor("#1D2026")
            border = Color.parseColor("#292C34")
            divider = Color.parseColor("#1E2025")
            text = Color.parseColor("#F3F4F8")
            dim = Color.parseColor("#9BA1AD")
            accent = Color.parseColor("#7C87FF")
            accentDeep = Color.parseColor("#5A5FE0")
            bubble = Color.parseColor("#5A5FE0")
            sendDim = Color.parseColor("#26272E")
            sendDimText = Color.parseColor("#5B6170")
            scrim = Color.parseColor("#99000000")
        } else {
            bg = Color.parseColor("#FAFAFC")
            pill = Color.parseColor("#F0F1F5")
            surface = Color.parseColor("#E8EAF0")
            border = Color.parseColor("#DEE1E9")
            divider = Color.parseColor("#E9EBF1")
            text = Color.parseColor("#16181D")
            dim = Color.parseColor("#687082")
            accent = Color.parseColor("#5A5FE0")
            accentDeep = Color.parseColor("#4C51CE")
            bubble = Color.parseColor("#5A5FE0")
            sendDim = Color.parseColor("#E3E5EC")
            sendDimText = Color.parseColor("#A8AEBB")
            scrim = Color.parseColor("#66000000")
        }
    }
}
