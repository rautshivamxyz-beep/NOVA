package org.nova

import android.app.AlertDialog
import android.app.ListActivity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.nova.ncie.android.NcieLearn
import org.nova.ncie.learn.LearnedFact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.9.1 phase 2 — NOVA's memory screen: everything the LEARN phase has
 * memorized, on one plain list.
 *
 * Deliberately old-school and dependency-free: android.app.ListActivity,
 * no AndroidX, no layout XML, no strings.xml (English is hardcoded), no
 * coroutines — NcieLearn's memory API is already asynchronous and
 * delivers its callbacks on the main thread, so this screen only builds
 * views and reacts. Every action is fail-soft; the list re-snapshots
 * after every mutation (forget, wipe, consolidate), and a tap expands
 * the full answer of an ellipsized row.
 *
 * Opened through its own launcher icon ("NOVA Memory") until phase 3
 * wires it into MainActivity's UI.
 */
class MemoryActivity : ListActivity() {

    private var facts: List<LearnedFact> = emptyList()
    private var adapter: FactAdapter? = null
    private var statsLine: TextView? = null
    private val learnedFmt = SimpleDateFormat("d MMM", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
            // The memory screen can be the app's entry point, so it needs
            // the same lazy learner boot the chat path gets.
            NcieLearn.memoryBoot(this)
        } catch (e: Exception) {
            try { Toast.makeText(this, "Memory screen failed to load", Toast.LENGTH_LONG).show() } catch (x: Exception) { }
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------
    // UI — built programmatically, dark-friendly, stable framework
    // widgets only.
    // ------------------------------------------------------------------

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun pad(n: Int) = (n * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
        }

        val title = TextView(this).apply {
            text = "NOVA Memory"
            // v8.0.0: 20f + NovaTheme, like every other screen title
            textSize = 20f
            letterSpacing = 0.06f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NovaTheme.text)
            setPadding(pad(16), pad(16), pad(16), pad(4))
        }
        root.addView(title)

        statsLine = TextView(this).apply {
            text = "loading…"
            textSize = 12f
            setTextColor(NovaTheme.dim)
            setPadding(pad(16), 0, pad(16), pad(4))
        }
        root.addView(statsLine)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad(8), pad(4), pad(8), pad(4))
        }
        // v8.0.0: styled buttons - default Material buttons stuck out
        val consolidate = Button(this).apply {
            text = "Consolidate now"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(NovaTheme.accentDeep)
                cornerRadius = pad(12).toFloat()
            }
            setPadding(pad(16), pad(10), pad(16), pad(10))
            setOnClickListener { consolidateNow() }
        }
        buttons.addView(consolidate,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val wipe = Button(this).apply {
            text = "Wipe all memory"
            isAllCaps = false
            textSize = 14f
            setTextColor(NovaTheme.dim)
            background = GradientDrawable().apply {
                setColor(NovaTheme.pill)
                cornerRadius = pad(12).toFloat()
                setStroke(pad(1), NovaTheme.border)
            }
            setPadding(pad(16), pad(10), pad(16), pad(10))
            setOnClickListener { confirmWipe() }
        }
        buttons.addView(wipe,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        val empty = TextView(this).apply {
            text = "Nothing learned yet — chat with NOVA and verified answers will appear here."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(NovaTheme.dim)
            setPadding(pad(24), pad(32), pad(24), pad(32))
        }
        root.addView(empty, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val list = ListView(this).apply {
            id = android.R.id.list
            // v8.0.0: transparent divider doubles as card spacing
            divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            dividerHeight = pad(8)
        }
        root.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        adapter = FactAdapter()
        listAdapter = adapter
        list.emptyView = empty
        list.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
            confirmForget(position)
            true
        }
    }

    /** One list row: bold question, one-line answer preview, summary. */
    private fun newRow(): LinearLayout {
        val dp = resources.displayMetrics.density
        fun pad(n: Int) = (n * dp).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // v8.0.0: learned facts live in cards now
            setPadding(pad(16), pad(12), pad(16), pad(12))
            background = GradientDrawable().apply {
                setColor(NovaTheme.pill)
                cornerRadius = pad(14).toFloat()
                setStroke(pad(1), NovaTheme.border)
            }
        }
        val q = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NovaTheme.text)
        }
        row.addView(q)
        val a = TextView(this).apply {
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(NovaTheme.dim)
        }
        row.addView(a)
        val s = TextView(this).apply {
            textSize = 12f
            setTextColor(NovaTheme.dim)
        }
        row.addView(s)
        return row
    }

    private fun summaryOf(f: LearnedFact): String {
        val learned = try { learnedFmt.format(Date(f.learnedAtMillis)) } catch (e: Exception) { "?" }
        return "score " + "%.2f".format(Locale.US, f.score) +
            " · asked " + f.interactions + "×" +
            " · learned " + learned +
            " · " + f.provenance
    }

    // ------------------------------------------------------------------
    // Actions — every mutation re-snapshots the list.
    // ------------------------------------------------------------------

    /** Re-read memory; the snapshot arrives on the main thread. */
    private fun refresh() {
        try {
            NcieLearn.memorySnapshot { snapshot, stats ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    try {
                        facts = snapshot
                        statsLine?.text = stats
                        adapter?.notifyDataSetChanged()
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { }
    }

    /** Tap: the full answer of an ellipsized row, read-only. */
    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        val f = facts.getOrNull(position) ?: return
        try {
            AlertDialog.Builder(this)
                .setTitle(f.question)
                .setMessage(f.answer)
                .setPositiveButton("Close", null)
                .show()
        } catch (e: Exception) { }
    }

    /** Long-press: forget exactly this memory, after a confirmation. */
    private fun confirmForget(position: Int) {
        val f = facts.getOrNull(position) ?: return
        try {
            AlertDialog.Builder(this)
                .setTitle("Forget this memory?")
                .setMessage(f.question)
                .setPositiveButton("Forget") { _, _ ->
                    NcieLearn.memoryForget(f.question)
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) { }
    }

    private fun confirmWipe() {
        try {
            AlertDialog.Builder(this)
                .setTitle("Wipe all memory?")
                .setMessage("Every learned fact and cached answer will be deleted. This cannot be undone.")
                .setPositiveButton("Wipe") { _, _ ->
                    NcieLearn.memoryClear()
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) { }
    }

    private fun consolidateNow() {
        try {
            Toast.makeText(this, "Consolidating…", Toast.LENGTH_SHORT).show()
            NcieLearn.memoryDistill(this) { report ->
                try {
                    Toast.makeText(this, report, Toast.LENGTH_LONG).show()
                } catch (e: Exception) { }
                refresh()
            }
        } catch (e: Exception) {
            try { Toast.makeText(this, "Consolidation failed", Toast.LENGTH_SHORT).show() } catch (x: Exception) { }
        }
    }

    /** The list of facts — [BaseAdapter] over the latest snapshot. */
    private inner class FactAdapter : BaseAdapter() {
        override fun getCount(): Int = facts.size
        override fun getItem(position: Int): Any = facts[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val f = facts[position]
            val row = convertView as? LinearLayout ?: newRow()
            (row.getChildAt(0) as TextView).text = f.question
            (row.getChildAt(1) as TextView).text = f.answer
            (row.getChildAt(2) as TextView).text = summaryOf(f)
            return row
        }
    }
}
