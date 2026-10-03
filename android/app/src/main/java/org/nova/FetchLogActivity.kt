package org.nova

import android.app.AlertDialog
import android.app.ListActivity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
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
 * in plain sight and deletable. The privacy contract made visible.
 * v9.18.0 "Redesign": rebuilt on the NovaUi kit.
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

    private fun dp(v: Int): Int = NovaUi.dp(this, v)

    private fun logFile(): File = File(filesDir, "fetch_log.txt")

    private fun buildUi() {
        val root = NovaUi.column(this).apply {
            setBackgroundColor(NovaTheme.bg)
            setPadding(dp(16), dp(18), dp(16), dp(12))
        }
        root.addView(NovaUi.title(this, "Fetches"))
        hint = NovaUi.small(this,
            "Everything NOVA fetched online - offline forever.\n" +
                "Tap to read, delete removes the article too.")
            .apply { setPadding(0, dp(4), 0, dp(10)) }
        root.addView(hint)
        root.addView(NovaUi.ghostButton(this, "Delete all", NovaTheme.dim) { confirmClearAll() })
        // v8.5.1 crash fix: build our OWN ListView with the id ListActivity
        // requires. Grabbing `listView` before setContentView makes
        // ListActivity inflate its default layout, and re-adding that
        // already-parented list to our root throws IllegalStateException -
        // the Fetches screen crashed on open (the device report).
        val list = ListView(this).apply {
            id = android.R.id.list
            divider = null
            dividerHeight = dp(8)
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

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
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
            val row = NovaUi.card(this@FetchLogActivity).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
            row.addView(NovaUi.heading(this@FetchLogActivity, e.title))
            row.addView(TextView(this@FetchLogActivity).apply {
                text = fmt.format(Date(e.millis)) + "  -  " +
                    (if (e.url == "wikipedia") "Wikipedia" else
                        try { android.net.Uri.parse(e.url).host ?: e.url } catch (x: Exception) { e.url })
                textSize = NovaTheme.T_CAPTION + 1f
                setTextColor(NovaTheme.dim)
            })
            return row
        }
    }
}
