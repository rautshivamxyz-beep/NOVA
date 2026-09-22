package org.nova

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
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
 * long-press to delete.
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun build(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NovaTheme.bg)
            setPadding(dp(14), dp(28), dp(14), dp(30))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }
        header.addView(Button(this).apply {
            isAllCaps = false
            setCompoundDrawablesWithIntrinsicBounds(
                tinted(R.drawable.ic_back, NovaTheme.text), null, null, null)
            background = null
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        header.addView(TextView(this).apply {
            text = "Exams"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(NovaTheme.text)
        })
        col.addView(header)

        col.addView(TextView(this).apply {
            text = "NOVA counts down for you and keeps them in mind when you chat."
            textSize = 12f; setTextColor(NovaTheme.dim)
            setPadding(dp(4), dp(6), dp(4), dp(10))
        })

        col.addView(Button(this).apply {
            text = "Add exam"
            isAllCaps = false
            textSize = 14f
            setTextColor(NovaTheme.text)
            background = GradientDrawable().apply {
                setColor(NovaTheme.pill)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
            setOnClickListener { showAdd() }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        rebuild()

        scroll.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun tinted(res: Int, color: Int) = getDrawable(res)!!.mutate().apply {
        colorFilter = android.graphics.PorterDuffColorFilter(
            color, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    private fun rebuild() {
        list.removeAllViews()
        val exams = Exams.load(this).sortedBy { it.dateMs }
        if (exams.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No exams yet."
                textSize = 13f; setTextColor(NovaTheme.dim)
                setPadding(dp(4), dp(14), dp(4), dp(4))
            })
            return
        }
        for (e in exams) {
            val days = Exams.daysLeft(e.dateMs)
            val dateStr = SimpleDateFormat("EEE, d MMM yyyy", Locale.US).format(Date(e.dateMs))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply {
                    setColor(NovaTheme.pill)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), NovaTheme.border)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@ExamsActivity)
                        .setTitle("Delete '${e.name}'?")
                        .setPositiveButton("Delete") { _, _ ->
                            Exams.save(this@ExamsActivity,
                                Exams.load(this@ExamsActivity).filter { it != e })
                            rebuild()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }
            row.addView(TextView(this).apply {
                text = e.name
                textSize = 16f; setTextColor(NovaTheme.text)
                typeface = Typeface.DEFAULT_BOLD
            })
            row.addView(TextView(this).apply {
                text = when {
                    days < 0 -> "done — $dateStr"
                    days == 0 -> "TODAY"
                    days == 1 -> "tomorrow — $dateStr"
                    else -> "in $days days — $dateStr"
                }
                textSize = 13f
                setTextColor(if (days in 0..3) NovaTheme.accent else NovaTheme.dim)
                setPadding(0, dp(2), 0, 0)
            })
            list.addView(row)
        }
    }

    private fun showAdd() {
        val name = EditText(this).apply {
            hint = "Subject (e.g. Physics)"; setHintTextColor(NovaTheme.dim)
            setTextColor(NovaTheme.text); textSize = 14f
        }
        val date = EditText(this).apply {
            hint = "Date (e.g. 14/5/2026)"; setHintTextColor(NovaTheme.dim)
            setTextColor(NovaTheme.text); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(name); addView(date)
        }
        AlertDialog.Builder(this)
            .setTitle("Add exam")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val n = name.text.toString().trim()
                val ms = SimpleDateFormat("d/M/yyyy", Locale.US)
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
