package org.nova

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

/**
 * v8.3.0: the study plan screen, reached from the drawer's "Study plan" row.
 *
 * Three cards: today's plan (computed by Planner from exam dates, weak
 * topics and the flashcard deck), the weak-topic list itself, and the
 * doubt journal. Everything is offline, instant and pure logic - the
 * model is never involved, so the plan is always trustworthy.
 */
class PlannerActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var budgetBtn: Button
    private lateinit var planList: LinearLayout
    private lateinit var topicsList: LinearLayout
    private lateinit var doubtsList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        NovaTheme.apply(settings.theme == "light")
        window.statusBarColor = NovaTheme.bg
        window.navigationBarColor = NovaTheme.bg
        setContentView(build())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tinted(res: Int, color: Int) =
        (androidx.core.content.ContextCompat.getDrawable(this, res)
            ?: android.graphics.drawable.ColorDrawable(color)).mutate().apply {
        colorFilter = android.graphics.PorterDuffColorFilter(
            color, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    private fun card(): LinearLayout = NovaUi.card(this)

    private fun sectionLabel(text: String): View = NovaUi.sectionLabel(this, text)

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
            contentDescription = getString(R.string.action_back)
            background = null
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        header.addView(TextView(this).apply {
            text = getString(R.string.planner_title); textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(NovaTheme.text)
        })
        col.addView(header)

        col.addView(TextView(this).apply {
            text = getString(R.string.planner_hint)
            textSize = 12f
            setTextColor(NovaTheme.dim)
            setPadding(dp(4), dp(6), dp(4), dp(10))
        })

        budgetBtn = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            setTextColor(NovaTheme.text)
            background = GradientDrawable().apply {
                setColor(NovaTheme.pill)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
            setOnClickListener { pickBudget() }
        }
        col.addView(budgetBtn)

        col.addView(sectionLabel(getString(R.string.planner_today_header)))
        planList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(planList)

        col.addView(sectionLabel(getString(R.string.planner_weak_header)))
        col.addView(Button(this).apply {
            text = getString(R.string.planner_add_weak)
            isAllCaps = false
            textSize = 14f
            setTextColor(NovaTheme.text)
            background = GradientDrawable().apply {
                setColor(NovaTheme.pill)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
            setOnClickListener { showAddTopic() }
        })
        topicsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(topicsList)

        col.addView(sectionLabel(getString(R.string.planner_doubt_header)))
        col.addView(TextView(this).apply {
            text = getString(R.string.planner_doubt_hint)
            textSize = 12f
            setTextColor(NovaTheme.dim)
            setPadding(dp(4), dp(0), dp(4), dp(8))
        })
        col.addView(Button(this).apply {
            text = getString(R.string.planner_log_doubt)
            isAllCaps = false
            textSize = 14f
            setTextColor(NovaTheme.text)
            background = GradientDrawable().apply {
                setColor(NovaTheme.pill)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
            setOnClickListener { showAddDoubt() }
        })
        doubtsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(doubtsList)

        rebuild()

        scroll.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun pickBudget() {
        val options = intArrayOf(30, 45, 60, 90, 120)
        val labels = options.map { "$it min" }.toTypedArray()
        val current = Planner.minutes(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.planner_daily_time)
            .setSingleChoiceItems(labels, options.indexOf(current)) { d, which ->
                Planner.setMinutes(this, options[which])
                d.dismiss()
                rebuild()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun rebuild() {
        val t = Planner.today(this)
        budgetBtn.text = getString(R.string.planner_daily_time_n, t.budget)

        planList.removeAllViews()
        if (t.entries.isEmpty() && t.weakTopics.isEmpty()) {
            planList.addView(TextView(this).apply {
                text = getString(R.string.planner_nothing)
                textSize = 13f
                setTextColor(NovaTheme.dim)
                setPadding(dp(4), dp(10), dp(4), dp(4))
            })
        }
        for (e in t.entries) {
            planList.addView(card().apply {
                addView(TextView(this@PlannerActivity).apply {
                    text = "${e.subject} - ${e.minutes} min"
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(NovaTheme.text)
                })
                addView(TextView(this@PlannerActivity).apply {
                    text = if (e.daysLeft < 0) getString(R.string.planner_no_exam_date)
                    else if (e.daysLeft == 0) getString(R.string.planner_exam_today)
                    else getString(if (e.daysLeft == 1) R.string.planner_in_days_one
                                   else R.string.planner_in_days_other, e.daysLeft) +
                        if (e.urgency.isEmpty()) "" else getString(R.string.planner_urgency, e.urgency)
                    textSize = 12f
                    setTextColor(if (e.daysLeft in 1..7) NovaTheme.accent else NovaTheme.dim)
                    setPadding(dp(0), dp(3), dp(0), dp(0))
                })
            })
        }
        if (t.cardsTotal > 0) {
            planList.addView(card().apply {
                addView(Button(this@PlannerActivity).apply {
                    text = getString(R.string.planner_cards, t.cardsDue, t.cardsTotal)
                    isAllCaps = false
                    textSize = 14f
                    setTextColor(NovaTheme.accent)
                    background = null
                    setOnClickListener { Study.review(this@PlannerActivity) }
                })
            })
        }

        // ---- weak topics ----
        topicsList.removeAllViews()
        if (t.weakTopics.isEmpty()) {
            topicsList.addView(TextView(this).apply {
                text = getString(R.string.planner_no_weak)
                textSize = 13f
                setTextColor(NovaTheme.dim)
                setPadding(dp(4), dp(10), dp(4), dp(4))
            })
        }
        for (topic in t.weakTopics) {
            topicsList.addView(card().apply {
                addView(TextView(this@PlannerActivity).apply {
                    text = "${topic.subject} - ${topic.name}"
                    textSize = 14f
                    setTextColor(NovaTheme.text)
                })
                addView(TextView(this@PlannerActivity).apply {
                    text = getString(R.string.planner_weakness,
                        "\u25CF".repeat(topic.weak) + "\u25CB".repeat(3 - topic.weak))
                    textSize = 12f
                    setTextColor(NovaTheme.dim)
                    setPadding(dp(0), dp(3), dp(0), dp(0))
                })
                setOnLongClickListener {
                    AlertDialog.Builder(this@PlannerActivity)
                        .setTitle(R.string.planner_remove_weak_q)
                        .setMessage(topic.name)
                        .setPositiveButton(R.string.action_remove) { _, _ ->
                            val all = Planner.topics(this@PlannerActivity)
                            all.removeAll { it.subject == topic.subject && it.name == topic.name }
                            Planner.saveTopics(this@PlannerActivity, all)
                            rebuild()
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                    true
                }
            })
        }

        // ---- doubts ----
        doubtsList.removeAllViews()
        val all = Planner.doubts(this)
        val open = all.filter { !it.done && it.text.isNotBlank() }
        if (open.isEmpty()) {
            doubtsList.addView(TextView(this).apply {
                text = getString(R.string.planner_no_doubts)
                textSize = 13f
                setTextColor(NovaTheme.dim)
                setPadding(dp(4), dp(10), dp(4), dp(4))
            })
        }
        for (d in open.asReversed()) {
            doubtsList.addView(card().apply {
                addView(TextView(this@PlannerActivity).apply {
                    text = d.text
                    textSize = 14f
                    setTextColor(NovaTheme.text)
                })
                setOnClickListener { doubtActions(d.text, d.ts) }
            })
        }
    }

    /** v9.4.0 "Audit Fixes II" (audit: duplicate doubts were not
     *  distinct - acting on one acted on all with the same text): the
     *  entry's timestamp is part of the key, so each logged doubt
     *  stays its own item. */
    private fun doubtActions(text: String, ts: Long) {
        val options = arrayOf(getString(R.string.planner_copy_for_chat), getString(R.string.planner_mark_solved), getString(R.string.action_delete))
        AlertDialog.Builder(this)
            .setTitle(R.string.planner_doubt)
            .setMessage(text)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("doubt", text))
                        Toast.makeText(this, getString(R.string.planner_copied), Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val all = Planner.doubts(this)
                        for (d in all) if (d.text == text && d.ts == ts && !d.done) d.done = true
                        Planner.saveDoubts(this, all)
                        rebuild()
                    }
                    2 -> {
                        val all = Planner.doubts(this).filter { !(it.text == text && it.ts == ts) }
                        Planner.saveDoubts(this, all)
                        rebuild()
                    }
                }
            }
            .show()
    }

    private fun showAddTopic() {
        val pad = dp(10)
        val subject = EditText(this).apply {
            hint = getString(R.string.planner_subject_hint)
            setSingleLine()
            setPadding(pad, pad, pad, pad)
        }
        val name = EditText(this).apply {
            hint = getString(R.string.planner_topic_hint)
            setSingleLine()
            setPadding(pad, pad, pad, pad)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(0))
            addView(subject)
            addView(name)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.planner_add_weak)
            .setView(wrap)
            .setPositiveButton(R.string.action_add) { _, _ ->
                val s = subject.text.toString().trim()
                val n = name.text.toString().trim()
                if (s.isEmpty() || n.isEmpty()) {
                    Toast.makeText(this, getString(R.string.planner_fill_both), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.planner_how_weak)
                    .setItems(arrayOf(getString(R.string.planner_weak1), getString(R.string.planner_weak2), getString(R.string.planner_weak3))) { _, w ->
                        val all = Planner.topics(this)
                        all.add(Planner.Topic(s, n, w + 1))
                        Planner.saveTopics(this, all)
                        rebuild()
                    }
                    .show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showAddDoubt() {
        val input = EditText(this).apply {
            hint = getString(R.string.planner_doubt_field_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.planner_log_doubt)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                val all = Planner.doubts(this)
                all.add(Planner.Doubt(t, System.currentTimeMillis(), false))
                Planner.saveDoubts(this, all)
                rebuild()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
