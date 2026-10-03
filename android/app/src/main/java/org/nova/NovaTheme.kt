package org.nova

import android.graphics.Color

/**
 * v9.18.0 "Redesign": the app-wide design system. Every screen reads its
 * colours, radii, spacing and type sizes from here, so one coherent
 * language runs through the whole app.
 *
 * Property names from the v8.0.0 "midnight" refresh are all kept, so every
 * existing call site keeps working; only the values were refined, and the
 * new tokens (radii, spacing, type scale, semantic colours) were added.
 */
object NovaTheme {

    var dark = true

    // ---- surfaces ----
    var bg = Color.parseColor("#0B0D12")
    var pill = Color.parseColor("#151821")
    var surface = Color.parseColor("#171A24")
    var cardAlt = Color.parseColor("#1C2029")
    var border = Color.parseColor("#262B38")
    var divider = Color.parseColor("#1E2230")

    // ---- content ----
    var text = Color.parseColor("#E9ECF3")
    var dim = Color.parseColor("#8B93A7")
    var faint = Color.parseColor("#5C6478")

    // ---- brand + semantic ----
    var accent = Color.parseColor("#5B8DEF")
    var accentDeep = Color.parseColor("#3B6FE0")
    var accentSoft = Color.parseColor("#1E2A45")
    var bubble = Color.parseColor("#3B6FE0")
    var success = Color.parseColor("#34D399")
    var warn = Color.parseColor("#FBBF24")
    var danger = Color.parseColor("#F87171")

    // ---- controls ----
    var sendDim = Color.parseColor("#1C2029")
    var sendDimText = Color.parseColor("#565E70")
    var scrim = Color.parseColor("#99000000")

    // ---- geometry (density-independent) ----
    const val RADIUS_CARD = 20
    const val RADIUS_FIELD = 14
    const val RADIUS_PILL = 999
    const val PAD_SCREEN = 16
    const val PAD_CARD = 16
    const val GAP = 12

    // ---- type scale (sp) ----
    const val T_DISPLAY = 26f
    const val T_TITLE = 20f
    const val T_HEADING = 16f
    const val T_BODY = 15f
    const val T_SMALL = 13f
    const val T_CAPTION = 11f

    fun apply(darkTheme: Boolean) {
        dark = darkTheme
        if (darkTheme) {
            bg = Color.parseColor("#0B0D12")
            pill = Color.parseColor("#151821")
            surface = Color.parseColor("#171A24")
            cardAlt = Color.parseColor("#1C2029")
            border = Color.parseColor("#262B38")
            divider = Color.parseColor("#1E2230")
            text = Color.parseColor("#E9ECF3")
            dim = Color.parseColor("#8B93A7")
            faint = Color.parseColor("#5C6478")
            accent = Color.parseColor("#5B8DEF")
            accentDeep = Color.parseColor("#3B6FE0")
            accentSoft = Color.parseColor("#1E2A45")
            bubble = Color.parseColor("#3B6FE0")
            success = Color.parseColor("#34D399")
            warn = Color.parseColor("#FBBF24")
            danger = Color.parseColor("#F87171")
            sendDim = Color.parseColor("#1C2029")
            sendDimText = Color.parseColor("#565E70")
            scrim = Color.parseColor("#99000000")
        } else {
            bg = Color.parseColor("#F5F7FB")
            pill = Color.parseColor("#FFFFFF")
            surface = Color.parseColor("#FFFFFF")
            cardAlt = Color.parseColor("#F0F3F9")
            border = Color.parseColor("#E2E6EF")
            divider = Color.parseColor("#EDF0F6")
            text = Color.parseColor("#10131B")
            dim = Color.parseColor("#667085")
            faint = Color.parseColor("#98A2B3")
            accent = Color.parseColor("#2F6BFF")
            accentDeep = Color.parseColor("#1F53D6")
            accentSoft = Color.parseColor("#E4ECFF")
            bubble = Color.parseColor("#1F53D6")
            success = Color.parseColor("#059669")
            warn = Color.parseColor("#B45309")
            danger = Color.parseColor("#DC2626")
            sendDim = Color.parseColor("#E7EBF3")
            sendDimText = Color.parseColor("#A9B0C0")
            scrim = Color.parseColor("#66000000")
        }
    }
}
