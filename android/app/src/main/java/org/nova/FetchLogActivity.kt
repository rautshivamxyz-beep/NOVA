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
        root.addView(NovaUi.title(this, getString(R.string.fetch_title)))
        hint = NovaUi.small(this,
            getString(R.string.fetch_empty))
            .apply { setPadding(0, dp(4), 0, dp(10)) }
        root.addView(hint)
        root.addView(NovaUi.ghostButton(this, getString(R.string.fetch_delete_all), NovaTheme.dim) { confirmClearAll() })
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
                    getString(R.string.fetch_none_yet)
                else getString(R.string.fetch_empty)
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
                    .setMessage((body ?: getString(R.string.fetch_article_unavailable))
                        .take(8000) + getString(R.string.fetch_from,
                        if (e.url == "wikipedia") "Wikipedia" else e.url))
                    .setPositiveButton(R.string.action_delete) { _, _ -> confirmDelete(e.title) }
                    .setNegativeButton(R.string.action_close, null)
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
            .setTitle(R.string.fetch_delete_all_q)
            .setMessage(R.string.fetch_delete_all_msg)
            .setPositiveButton(R.string.fetch_delete_all) { _, _ ->
                Thread {
                    for (e in entries) {
                        try { WikiCore.removeArticle(this, e.title) } catch (x: Exception) { NovaLog.quiet("FetchLogActivity", x) }
                    }
                    try { logFile().delete() } catch (x: Exception) { NovaLog.quiet("FetchLogActivity", x) }
                    runOnUiThread { reload() }
                }.start()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun rewriteLogWithout(title: String) {
        try {
            val f = logFile()
            val kept = f.readLines().filter {
                it.split('\t').getOrNull(1)?.equals(title, ignoreCase = true) != true
            }
            val body = kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n"
            // v9.33.78: written atomically. A direct writeText truncates the
            // file first, so a crash (or the process being killed while the
            // user is deleting an entry) left an empty or half-written fetch
            // log - the whole lookup history, gone. tmp + rename means the
            // log is either the old one or the new one, never a fragment.
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { NovaLog.quiet("FetchLogActivity", e) }
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
