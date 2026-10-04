package org.nova

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v9.21.0 "Reliable + Easy": the Setup & health screen. A red/green list of
 * everything NOVA needs, each row tapping through to the screen that fixes
 * it. Turns every silent failure into something visible.
 */
class HealthActivity : Activity() {

    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Settings(this)
        NovaTheme.apply(s.theme == "light")
        window.statusBarColor = NovaTheme.bg
        window.navigationBarColor = NovaTheme.bg
        setContentView(build())
    }

    private fun dp(v: Int): Int = NovaUi.dp(this, v)

    private fun build(): View {
        val scroll = ScrollView(this)
        col = NovaUi.column(this).apply {
            setBackgroundColor(NovaTheme.bg)
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        col.addView(NovaUi.header(this, "Setup & health") { finish() })
        col.addView(NovaUi.small(this,
            "Everything NOVA needs, in one place. Tap a red row to fix it.")
            .apply { setPadding(dp(4), 0, dp(4), dp(4)) })
        col.addView(NovaUi.primaryButton(this, "Re-arm everything") {
            NovaHealth.armAll(this)
            render()
        })
        render()
        scroll.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    /** Rebuild the check rows (called after a fix, and on resume). */
    private fun render() {
        // drop everything after the header block (header, hint, button)
        while (col.childCount > 3) col.removeViewAt(col.childCount - 1)

        val checks = NovaHealth.checks(this)
        val bad = checks.count { !it.ok }
        col.addView(NovaUi.sectionLabel(this,
            if (bad == 0) "All good" else bad.toString() + " need attention"))

        for (c in checks) {
            val card = NovaUi.card(this)
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }

            val head = NovaUi.row(this)
            head.addView(TextView(this).apply {
                text = if (c.ok) "\u2713" else "!"
                textSize = NovaTheme.T_HEADING
                setTextColor(if (c.ok) NovaTheme.success else NovaTheme.danger)
                setPadding(0, 0, dp(10), 0)
            })
            head.addView(NovaUi.heading(this, c.label).apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            card.addView(head)
            card.addView(NovaUi.small(this, c.detail).apply { setPadding(0, dp(4), 0, 0) })
            if (!c.ok && c.fix != null) {
                card.addView(NovaUi.ghostButton(this, "Fix this") { c.fix.invoke(); render() })
            }
            col.addView(card)
        }
    }

    override fun onResume() {
        super.onResume()
        // a permission may have been granted while we were away
        try { render() } catch (e: Exception) { }
    }
}
