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

    private fun tinted(res: Int, color: Int) = getDrawable(res)!!.mutate().apply {
        colorFilter = android.graphics.PorterDuffColorFilter(
            color, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            setColor(NovaTheme.pill)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), NovaTheme.border)
        }
    }

    private fun sectionLabel(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(NovaTheme.dim)
        setPadding(dp(4), dp(18), dp(4), dp(8))
    }

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
            text = "Study plan"; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(NovaTheme.text)
        })
        col.addView(header)

        col.addView(TextView(this).apply {
            text = "Your minutes follow your exams. Add exams and weak topics - the plan rebuilds itself every day."
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

        col.addView(sectionLabel("TODAY'S PLAN"))
        planList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(planList)

        col.addView(sectionLabel("WEAK TOPICS"))
        col.addView(Button(this).apply {
            text = "Add weak topic"
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

        col.addView(sectionLabel("DOUBT JOURNAL"))
        col.addView(TextView(this).apply {
            text = "Questions that hit you at odd hours. Tap one to copy it into the chat."
            textSize = 12f
            setTextColor(NovaTheme.dim)
            setPadding(dp(4), dp(0), dp(4), dp(8))
        })
        col.addView(Button(this).apply {
            text = "Log a doubt"
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
            .setTitle("Daily study time")
            .setSingleChoiceItems(labels, options.indexOf(current)) { d, which ->
                Planner.setMinutes(this, options[which])
                d.dismiss()
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rebuild() {
        val t = Planner.today(this)
        budgetBtn.text = "Daily study time: ${t.budget} min"

        planList.removeAllViews()
        if (t.entries.isEmpty() && t.weakTopics.isEmpty()) {
            planList.addView(TextView(this).apply {
                text = "Nothing to plan yet. Add exams (drawer - More - Exams) or weak topics below."
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
                    text = if (e.daysLeft < 0) "no exam date - steady revision"
                    else if (e.daysLeft == 0) "exam is TODAY"
                    else "in ${e.daysLeft} day${if (e.daysLeft == 1) "" else "s"}" +
                        if (e.urgency.isEmpty()) "" else " - urgency ${e.urgency}"
                    textSize = 12f
                    setTextColor(if (e.daysLeft in 1..7) NovaTheme.accent else NovaTheme.dim)
                    setPadding(dp(0), dp(3), dp(0), dp(0))
                })
            })
        }
        if (t.cardsTotal > 0) {
            planList.addView(card().apply {
                addView(Button(this@PlannerActivity).apply {
                    text = "Study cards: ${t.cardsDue} of ${t.cardsTotal} due - review now"
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
                text = "No weak topics marked."
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
                    text = "weakness " + "\u25CF".repeat(topic.weak) + "\u25CB".repeat(3 - topic.weak) +
                        "  (long-press to remove)"
                    textSize = 12f
                    setTextColor(NovaTheme.dim)
                    setPadding(dp(0), dp(3), dp(0), dp(0))
                })
                setOnLongClickListener {
                    AlertDialog.Builder(this@PlannerActivity)
                        .setTitle("Remove weak topic?")
                        .setMessage(topic.name)
                        .setPositiveButton("Remove") { _, _ ->
                            val all = Planner.topics(this@PlannerActivity)
                            all.removeAll { it.subject == topic.subject && it.name == topic.name }
                            Planner.saveTopics(this@PlannerActivity, all)
                            rebuild()
                        }
                        .setNegativeButton("Cancel", null)
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
                text = "No open doubts."
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
        val options = arrayOf("Copy for NOVA chat", "Mark solved", "Delete")
        AlertDialog.Builder(this)
            .setTitle("Doubt")
            .setMessage(text)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("doubt", text))
                        Toast.makeText(this, "Copied - paste it in the chat", Toast.LENGTH_SHORT).show()
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
            hint = "Subject (e.g. Physics)"
            setSingleLine()
            setPadding(pad, pad, pad, pad)
        }
        val name = EditText(this).apply {
            hint = "Topic (e.g. Ray optics)"
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
            .setTitle("Add weak topic")
            .setView(wrap)
            .setPositiveButton("Add") { _, _ ->
                val s = subject.text.toString().trim()
                val n = name.text.toString().trim()
                if (s.isEmpty() || n.isEmpty()) {
                    Toast.makeText(this, "Fill both fields", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AlertDialog.Builder(this)
                    .setTitle("How weak is it?")
                    .setItems(arrayOf("1 - shaky", "2 - weak", "3 - cannot do it yet")) { _, w ->
                        val all = Planner.topics(this)
                        all.add(Planner.Topic(s, n, w + 1))
                        Planner.saveTopics(this, all)
                        rebuild()
                    }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddDoubt() {
        val input = EditText(this).apply {
            hint = "What did you wonder about?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("Log a doubt")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                val all = Planner.doubts(this)
                all.add(Planner.Doubt(t, System.currentTimeMillis(), false))
                Planner.saveDoubts(this, all)
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
