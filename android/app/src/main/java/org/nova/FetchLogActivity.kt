package org.nova

import android.app.AlertDialog
import android.app.ListActivity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.nova.NovaTheme
import org.nova.WikiCore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v8.5.0: the Fetches screen - every article NOVA has fetched online,
 * in plain sight and deletable. The privacy contract made visible: an
 * empty list means NOVA never fetched anything; a full list is exactly
 * what left the phone (once) and now lives offline. Deleting an entry
 * deletes the article from the offline store with it.
 *
 * Same style as MemoryActivity: ListActivity, programmatic UI, no XML,
 * fail-soft everywhere, list reloads after every mutation.
 */
class FetchLogActivity : ListActivity() {

    private class Entry(val millis: Long, val title: String, val url: String)

    private var entries: List<Entry> = emptyList()
    private var adapter: EntryAdapter? = null
    private var hint: TextView? = null
    private val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        reload()
    }

    private fun logFile(): File = File(filesDir, "fetch_log.txt")

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun pad(n: Int) = (n * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(pad(20), pad(18), pad(20), pad(12))
        }
        root.addView(TextView(this).apply {
            text = "Fetches"
            textSize = 22f
            setTextColor(NovaTheme.text)
            typeface = Typeface.DEFAULT_BOLD
        })
        hint = TextView(this).apply {
            text = "Everything NOVA fetched online - offline forever.\n" +
                "Tap to read, delete removes the article too."
            textSize = 13f
            setTextColor(NovaTheme.dim)
            setPadding(0, pad(4), 0, pad(10))
        }
        root.addView(hint)
        root.addView(Button(this).apply {
            text = "Delete all"
            isAllCaps = false
            setOnClickListener { confirmClearAll() }
            setTextColor(NovaTheme.dim)
        })
        // v8.5.1 crash fix: build our OWN ListView with the id ListActivity
        // requires. Grabbing `listView` before setContentView makes
        // ListActivity inflate its default layout, and re-adding that
        // already-parented list to our root throws IllegalStateException -
        // the Fetches screen crashed on open (the device report).
        val list = ListView(this).apply {
            id = android.R.id.list
            divider = null
            dividerHeight = pad(8)
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        listAdapter = EntryAdapter().also { adapter = it }
    }

    private fun reload() {
        Thread {
            val loaded = try {
                logFile().readLines().mapNotNull { l ->
                    val p = l.split('\t')
                    if (p.size != 3) return@mapNotNull null
                    val m = p[0].toLongOrNull() ?: return@mapNotNull null
                    if (p[1].isBlank()) return@mapNotNull null
                    Entry(m, p[1], p[2])
                }
            } catch (e: Exception) { emptyList() }
            runOnUiThread {
                entries = loaded.asReversed()   // newest first
                adapter?.notifyDataSetChanged()
                hint?.text = if (entries.isEmpty())
                    "Nothing fetched yet. When a study question has nothing " +
                    "local behind it, NOVA asks before going online."
                else "Everything NOVA fetched online - offline forever.\n" +
                    "Tap to read, delete removes the article too."
            }
        }.start()
    }

    override fun onListItemClick(l: android.widget.ListView, v: View, position: Int, id: Long) {
        val e = entries.getOrNull(position) ?: return
        Thread {
            val body = try { WikiCore.articleText(this, e.title) } catch (x: Exception) { null }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(e.title)
                    // v9.4.0 "Audit Fixes II": 4000 chars cut long
                    // articles off mid-sentence - the read dialog now
                    // shows up to 8000
                    .setMessage((body ?: "(article text unavailable)")
                        .take(8000) + "\n\nfrom: " +
                        (if (e.url == "wikipedia") "Wikipedia" else e.url))
                    .setPositiveButton("Delete") { _, _ -> confirmDelete(e.title) }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }.start()
    }

    private fun confirmDelete(title: String) {
        Thread {
            val removed = try { WikiCore.removeArticle(this, title) } catch (x: Exception) { false }
            if (removed) rewriteLogWithout(title)
            runOnUiThread { reload() }
        }.start()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Delete all fetched articles?")
            .setMessage("Every article NOVA fetched online will be removed " +
                "from the offline store. This cannot be undone.")
            .setPositiveButton("Delete all") { _, _ ->
                Thread {
                    for (e in entries) {
                        try { WikiCore.removeArticle(this, e.title) } catch (x: Exception) { }
                    }
                    try { logFile().delete() } catch (x: Exception) { }
                    runOnUiThread { reload() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rewriteLogWithout(title: String) {
        try {
            val kept = logFile().readLines().filter {
                it.split('\t').getOrNull(1)?.equals(title, ignoreCase = true) != true
            }
            logFile().writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n")
        } catch (e: Exception) { }
    }

    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val e = entries[position]
            val row = LinearLayout(this@FetchLogActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
                background = GradientDrawable().apply {
                    setColor(NovaTheme.pill)
                    cornerRadius = 12f * resources.displayMetrics.density
                    setStroke(1, NovaTheme.border)
                }
                val dp = resources.displayMetrics.density
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            }
            row.addView(TextView(this@FetchLogActivity).apply {
                text = e.title
                textSize = 15f
                setTextColor(NovaTheme.text)
                typeface = Typeface.DEFAULT_BOLD
            })
            row.addView(TextView(this@FetchLogActivity).apply {
                text = fmt.format(Date(e.millis)) + "  -  " +
                    (if (e.url == "wikipedia") "Wikipedia" else
                        try { android.net.Uri.parse(e.url).host ?: e.url } catch (x: Exception) { e.url })
                textSize = 12f
                setTextColor(NovaTheme.dim)
            })
            return row
        }
    }
}
