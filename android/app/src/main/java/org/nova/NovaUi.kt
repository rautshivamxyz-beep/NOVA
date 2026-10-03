package org.nova

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * v9.18.0 "Redesign": the shared component kit. Every screen builds its
 * chrome from these, so spacing, radii, type and colour stay identical
 * across the app. Pure view construction - no business logic.
 */
object NovaUi {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** A rounded, optionally stroked shape. */
    fun shape(ctx: Context, color: Int, radiusDp: Int,
              strokeDp: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    // ---- text ----

    fun display(ctx: Context, s: String): TextView = TextView(ctx).apply {
        text = s; textSize = NovaTheme.T_DISPLAY
        setTextColor(NovaTheme.text)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun title(ctx: Context, s: String): TextView = TextView(ctx).apply {
        text = s; textSize = NovaTheme.T_TITLE
        setTextColor(NovaTheme.text)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun heading(ctx: Context, s: String): TextView = TextView(ctx).apply {
        text = s; textSize = NovaTheme.T_HEADING
        setTextColor(NovaTheme.text)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun body(ctx: Context, s: String): TextView = TextView(ctx).apply {
        text = s; textSize = NovaTheme.T_BODY; setTextColor(NovaTheme.text)
    }

    fun small(ctx: Context, s: String): TextView = TextView(ctx).apply {
        text = s; textSize = NovaTheme.T_SMALL; setTextColor(NovaTheme.dim)
    }

    /** The small uppercase section caption used above every group. */
    fun sectionLabel(ctx: Context, s: String): TextView = TextView(ctx).apply {
        text = s.uppercase()
        textSize = NovaTheme.T_CAPTION
        letterSpacing = 0.14f
        setTextColor(NovaTheme.dim)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(ctx, 4), dp(ctx, 18), 0, dp(ctx, 8))
    }

    // ---- containers ----

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, NovaTheme.PAD_CARD), dp(ctx, 14),
                   dp(ctx, NovaTheme.PAD_CARD), dp(ctx, 14))
        background = shape(ctx, NovaTheme.surface, NovaTheme.RADIUS_CARD, 1, NovaTheme.border)
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(NovaTheme.divider)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1))
    }

    // ---- controls ----

    /** A full-width primary action. */
    fun primaryButton(ctx: Context, label: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = NovaTheme.T_BODY
            setTextColor(android.graphics.Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = shape(ctx, NovaTheme.accentDeep, NovaTheme.RADIUS_FIELD)
            setPadding(dp(ctx, 18), dp(ctx, 12), dp(ctx, 18), dp(ctx, 12))
            setOnClickListener { onClick() }
        }

    /** A quiet, text-only action. */
    fun ghostButton(ctx: Context, label: String, color: Int = NovaTheme.accent,
                    onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = NovaTheme.T_BODY
            setTextColor(color)
            background = null
            setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10))
            setOnClickListener { onClick() }
        }

    fun dangerButton(ctx: Context, label: String, onClick: () -> Unit): Button =
        ghostButton(ctx, label, NovaTheme.danger, onClick)

    /** Label on the left, switch on the right - the settings row. */
    fun switchRow(ctx: Context, label: String, checked: Boolean,
                  onChange: (Boolean) -> Unit): View {
        val r = row(ctx)
        r.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        r.addView(body(ctx, label).apply {
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        r.addView(Switch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        return r
    }

    /** A tappable row: title (and optional subtitle) on the left. */
    fun navRow(ctx: Context, title: String, subtitle: String?,
               onClick: () -> Unit): View {
        val r = column(ctx)
        r.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
        r.isClickable = true
        r.addView(body(ctx, title))
        if (subtitle != null && subtitle.isNotBlank())
            r.addView(small(ctx, subtitle).apply { setPadding(0, dp(ctx, 2), 0, 0) })
        r.setOnClickListener { onClick() }
        return r
    }

    /** The back-arrow + title header every screen opens with. */
    fun header(ctx: Context, title: String, onBack: () -> Unit): LinearLayout {
        val h = row(ctx)
        h.setPadding(0, dp(ctx, 4), 0, dp(ctx, 10))
        val back = Button(ctx).apply {
            isAllCaps = false
            val d = ctx.getDrawable(R.drawable.ic_back)!!.mutate()
            d.colorFilter = android.graphics.PorterDuffColorFilter(
                NovaTheme.text, android.graphics.PorterDuff.Mode.SRC_IN)
            setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
            background = null
            setOnClickListener { onBack() }
        }
        h.addView(back, LinearLayout.LayoutParams(dp(ctx, 44),
            LinearLayout.LayoutParams.WRAP_CONTENT))
        h.addView(NovaUi.title(ctx, title).apply { setPadding(dp(ctx, 6), 0, 0, 0) })
        return h
    }

    /** Centred muted text for an empty list. */
    fun empty(ctx: Context, s: String): TextView = TextView(ctx).apply {
        text = s
        textSize = NovaTheme.T_SMALL
        setTextColor(NovaTheme.dim)
        gravity = Gravity.CENTER
        setPadding(0, dp(ctx, 40), 0, dp(ctx, 40))
    }
}
