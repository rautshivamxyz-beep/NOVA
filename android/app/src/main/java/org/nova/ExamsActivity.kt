package org.nova

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exam countdown screen: add exams (name + date), see days remaining,
 * long-press to delete. v9.18.0 "Redesign": rebuilt on the NovaUi kit.
 */
class ExamsActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        NovaTheme.apply(settings.theme == "light")
        window.statusBarColor = NovaTheme.bg
        window.navigationBarColor = NovaTheme.bg
        setContentView(build())
    }

    private fun dp(v: Int): Int = NovaUi.dp(this, v)

    private fun build(): View {
        val scroll = ScrollView(this)
        val col = NovaUi.column(this).apply {
            setBackgroundColor(NovaTheme.bg)
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        col.addView(NovaUi.header(this, "Exams") { finish() })
        col.addView(NovaUi.small(this,
            "NOVA counts down for you and keeps them in mind when you chat.")
            .apply { setPadding(dp(4), 0, dp(4), dp(12)) })
        col.addView(NovaUi.primaryButton(this, "Add exam") { showAdd() })
        list = NovaUi.column(this)
        col.addView(list)
        rebuild()
        scroll.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun rebuild() {
        list.removeAllViews()
        val exams = Exams.load(this).sortedBy { it.dateMs }
        if (exams.isEmpty()) {
            list.addView(NovaUi.empty(this, "No exams yet.\nAdd one and NOVA keeps the countdown."))
            return
        }
        for (e in exams) {
            val days = Exams.daysLeft(e.dateMs)
            val dateStr = SimpleDateFormat("EEE, d MMM yyyy", Locale.US).format(Date(e.dateMs))
            val row = NovaUi.card(this)
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            row.addView(NovaUi.heading(this, e.name))
            row.addView(TextView(this).apply {
                text = when {
                    days < 0 -> "done - $dateStr"
                    days == 0 -> "TODAY"
                    days == 1 -> "tomorrow - $dateStr"
                    else -> "in $days days - $dateStr"
                }
                textSize = NovaTheme.T_SMALL
                setTextColor(if (days in 0..3) NovaTheme.accent else NovaTheme.dim)
                setPadding(0, dp(2), 0, 0)
            })
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete '${e.name}'?")
                    .setPositiveButton("Delete") { _, _ ->
                        Exams.save(this, Exams.load(this).filter { it != e })
                        rebuild()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            list.addView(row)
        }
    }

    private fun showAdd() {
        val name = EditText(this).apply {
            hint = "Subject (e.g. Physics)"; setHintTextColor(NovaTheme.faint)
            setTextColor(NovaTheme.text); textSize = NovaTheme.T_BODY
        }
        val date = EditText(this).apply {
            hint = "Date (e.g. 14/5/2026)"; setHintTextColor(NovaTheme.faint)
            setTextColor(NovaTheme.text); textSize = NovaTheme.T_BODY
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val box = NovaUi.column(this).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(name); addView(date)
        }
        AlertDialog.Builder(this)
            .setTitle("Add exam")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val n = name.text.toString().trim()
                // v7.6: reject impossible dates - lenient parsing silently
                // accepted "14/13/2026" and rolled it into the next month
                val ms = SimpleDateFormat("d/M/yyyy", Locale.US).apply { isLenient = false }
                    .parse(date.text.toString().trim())?.time ?: 0L
                if (n.isEmpty() || ms <= 0L) {
                    toast("Fill both fields (date like 14/5/2026)")
                    return@setPositiveButton
                }
                Exams.add(this, n, ms)
                rebuild()
                toast("'$n' added")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
