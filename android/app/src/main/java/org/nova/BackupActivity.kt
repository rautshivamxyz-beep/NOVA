package org.nova

import android.app.AlertDialog
import android.app.ListActivity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * v8.7.0: the Backup screen - NOVA's entire local state in one zip.
 *
 * SAVE writes every regular file under filesDir (memory, wiki store,
 * knowledge docs, chat history, skills overrides, fetch log, settings -
 * whatever is there) into a SAF-chosen zip, preserving relative paths.
 * The cache/ and code_cache/ dirs and *.tmp files are skipped, so the
 * rule stays future-proof: anything NOVA persists later is picked up
 * automatically. RESTORE wipes every regular file under filesDir
 * (minus the cache dirs) first, then reads a zip back - a true replace,
 * not a merge. v9.4.0 "Audit Fixes II" (audit: restore left stale state
 * behind): after a successful extraction NOVA force-restarts itself, so
 * no in-memory copy of the old state ever gets written back.
 *
 * Same style as FetchLogActivity: ListActivity, programmatic UI, no
 * XML, no new permissions (SAF needs none), no network, fail-soft
 * everywhere.
 */
class BackupActivity : ListActivity() {

    private class Entry(val name: String, val size: Long)

    private var entries: List<Entry> = emptyList()
    private var adapter: EntryAdapter? = null
    private var hint: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    // ------------------------------------------------------------------
    // UI - built programmatically, dark theme, ListActivity-style.
    // ------------------------------------------------------------------

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun pad(n: Int) = (n * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(pad(20), pad(18), pad(20), pad(12))
        }
        root.addView(TextView(this).apply {
            text = "Backup"
            textSize = 22f
            setTextColor(NovaTheme.text)
            typeface = Typeface.DEFAULT_BOLD
        })
        hint = TextView(this).apply {
            text = "Save everything NOVA knows to a zip, or bring it\n" +
                "back from one. Nothing leaves the phone."
            textSize = 13f
            setTextColor(NovaTheme.dim)
            setPadding(0, pad(4), 0, pad(10))
        }
        root.addView(hint)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, pad(12))
        }
        actions.addView(Button(this).apply {
            text = "SAVE BACKUP"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(NovaTheme.accentDeep)
                cornerRadius = pad(12).toFloat()
            }
            setPadding(pad(16), pad(10), pad(16), pad(10))
            setOnClickListener { pickSaveTarget() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = "RESTORE BACKUP"
            isAllCaps = false
            textSize = 14f
            setTextColor(NovaTheme.dim)
            background = GradientDrawable().apply {
                setColor(NovaTheme.pill)
                cornerRadius = pad(12).toFloat()
                setStroke(pad(1), NovaTheme.border)
            }
            setPadding(pad(16), pad(10), pad(16), pad(10))
            setOnClickListener { pickRestoreSource() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        // v8.5.1 lesson (Fetches screen): build our OWN ListView with the
        // id ListActivity requires - never re-parent the default one.
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

    // ------------------------------------------------------------------
    // SAVE - SAF ACTION_CREATE_DOCUMENT, then zip filesDir into it.
    // ------------------------------------------------------------------

    private fun pickSaveTarget() {
        val name = "NOVA-backup-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(i, REQ_SAVE)
        } catch (e: Exception) {
            toast("No file picker available")
        }
    }

    /**
     * Every regular file under filesDir, minus the cache dirs and *.tmp
     * files and the v9.3.0 privacy exclusions (the notification log and
     * its pause flag never ride in a backup), relative paths preserved.
     * This is deliberately generic -
     * it backs up memory, wiki, knowledge, chats, skills, the fetch
     * log and anything added later, without a per-store checklist.
     */
    private fun collectFiles(): List<File> {
        val out = mutableListOf<File>()
        fun walk(d: File) {
            val kids = d.listFiles() ?: return
            for (f in kids) {
                if (f.isDirectory) {
                    if (f.name == "cache" || f.name == "code_cache") continue
                    walk(f)
                } else if (f.isFile && !f.name.endsWith(".tmp") &&
                    // v9.3.0 "Audit Fixes I": the notification log is
                    // private - it must not leave the phone in a backup
                    f.name != "notif_log.txt" && f.name != "notif_pause.txt") {
                    out.add(f)
                }
            }
        }
        walk(filesDir)
        out.sortBy { it.path }
        return out
    }

    private fun saveBackup(uri: Uri) {
        Thread {
            var count = 0
            var bytes = 0L
            var ok = false
            try {
                val files = collectFiles()
                contentResolver.openOutputStream(uri, "w")?.use { os ->
                    ZipOutputStream(BufferedOutputStream(os)).use { zos ->
                        for (f in files) {
                            val rel = f.relativeTo(filesDir).path
                                .replace(File.separatorChar, '/')
                            val ze = ZipEntry(rel)
                            ze.time = f.lastModified()
                            zos.putNextEntry(ze)
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                            count++
                            bytes += f.length()
                        }
                    }
                }
                ok = true
            } catch (e: Exception) { }
            runOnUiThread {
                if (ok) {
                    hint?.text = "Backup saved: $count files, " + kb(bytes) + ".\n" +
                        "Keep the zip anywhere - Downloads, the cloud, a laptop."
                    toast("Backup saved")
                } else {
                    toast("Backup failed - could not write the zip")
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // RESTORE - SAF ACTION_OPEN_DOCUMENT, show the contents, confirm,
    // then wipe filesDir (minus the cache dirs) and extract the zip.
    // v9.4.0: a true replace, followed by a forced restart.
    // ------------------------------------------------------------------

    private fun pickRestoreSource() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        try {
            startActivityForResult(i, REQ_RESTORE)
        } catch (e: Exception) {
            toast("No file picker available")
        }
    }

    private fun inspectBackup(uri: Uri) {
        Thread {
            val loaded = mutableListOf<Entry>()
            try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    ZipInputStream(BufferedInputStream(ins)).use { zis ->
                        var ze: ZipEntry? = zis.nextEntry
                        while (ze != null) {
                            if (!ze.isDirectory) {
                                // consume the entry: exact size + CRC check
                                var n = 0L
                                val buf = ByteArray(65536)
                                while (true) {
                                    val r = zis.read(buf)
                                    if (r < 0) break
                                    n += r
                                }
                                loaded.add(Entry(ze.name, n))
                            }
                            zis.closeEntry()
                            ze = zis.nextEntry
                        }
                    }
                }
            } catch (e: Exception) { }
            runOnUiThread {
                entries = loaded
                adapter?.notifyDataSetChanged()
                if (loaded.isEmpty()) {
                    hint?.text = "That file is not a NOVA backup " +
                        "(no readable zip entries)."
                } else {
                    hint?.text = loaded.size.toString() + " files in this backup.\n" +
                        "Restore replaces what is on the phone now."
                    confirmRestore(uri)
                }
            }
        }.start()
    }

    private fun confirmRestore(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Restore this backup?")
            // v9.4.0 "Audit Fixes II": the restore is a true replace now -
            // the warning must say exactly what that means
            .setMessage("This wipes NOVA's current memory, wiki, knowledge, " +
                "chats and settings and restores the backup exactly.")
            .setPositiveButton("Restore") { _, _ -> restoreBackup(uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** v9.4.0 "Audit Fixes II" (audit: restore was a merge, not a
     *  replace): delete every file under filesDir except the cache
     *  dirs, so what the backup lacks is really gone after a restore.
     *  Subdirectories other than cache/code_cache are emptied and
     *  removed - the zip recreates whatever it actually contains. */
    private fun wipeFilesDir() {
        fun wipe(dir: File) {
            val kids = dir.listFiles() ?: return
            for (f in kids) {
                if (f.isDirectory) {
                    if (f.name == "cache" || f.name == "code_cache") continue
                    wipe(f)
                    f.delete()
                } else if (
                    // v9.13.0 "Audit Fixes" (MEDIUM 8): the notification log
                    // and its pause flag are runtime-private and EXCLUDED
                    // from every backup (v9.3.0 privacy) - the restore wipe
                    // must not delete what the backup never contained. The
                    // live log stays exactly as it is.
                    f.name != "notif_log.txt" && f.name != "notif_pause.txt") f.delete()
            }
        }
        wipe(filesDir)
    }

    private fun restoreBackup(uri: Uri) {
        Thread {
            var restored = 0
            var ok = false
            try {
                val root = filesDir.canonicalFile
                // the true replace: current state goes first, then the zip
                wipeFilesDir()
                contentResolver.openInputStream(uri)?.use { ins ->
                    ZipInputStream(BufferedInputStream(ins)).use { zis ->
                        var ze: ZipEntry? = zis.nextEntry
                        while (ze != null) {
                            if (!ze.isDirectory) {
                                val target = File(filesDir, ze.name)
                                // a crafted zip must not escape filesDir
                                if (target.canonicalFile.path
                                        .startsWith(root.path + File.separator)) {
                                    target.parentFile?.mkdirs()
                                    target.outputStream().use { zis.copyTo(it) }
                                    restored++
                                }
                            }
                            zis.closeEntry()
                            ze = zis.nextEntry
                        }
                    }
                }
                ok = true
            } catch (e: Exception) { }
            runOnUiThread {
                if (ok) {
                    // v9.4.0 "Audit Fixes II" (audit: stale in-memory state
                    // could be written back over the restored files): the
                    // old "restart NOVA yourself" toast became a forced
                    // restart - every old store and singleton dies here
                    hint?.text = "Restored $restored files."
                    AlertDialog.Builder(this)
                        .setMessage("Restore complete — NOVA will now close. " +
                            "Reopen it.")
                        .setCancelable(false)
                        .setPositiveButton("OK") { _, _ ->
                            finishAffinity()
                            System.exit(0)
                        }
                        .show()
                } else {
                    toast("Restore failed - the zip could not be read")
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == REQ_SAVE) saveBackup(uri)
        else if (requestCode == REQ_RESTORE) inspectBackup(uri)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun toast(s: String) {
        try { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } catch (e: Exception) { }
    }

    private fun kb(bytes: Long): String = "%.1f KB".format(Locale.US, bytes / 1024.0)

    /** One list row: bold path, dim size - a card like every other screen. */
    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val e = entries[position]
            val row = LinearLayout(this@BackupActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(NovaTheme.pill)
                    cornerRadius = 12f * resources.displayMetrics.density
                    setStroke(1, NovaTheme.border)
                }
                val dp = resources.displayMetrics.density
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            }
            row.addView(TextView(this@BackupActivity).apply {
                text = e.name
                textSize = 15f
                setTextColor(NovaTheme.text)
                typeface = Typeface.DEFAULT_BOLD
            })
            row.addView(TextView(this@BackupActivity).apply {
                text = kb(e.size)
                textSize = 12f
                setTextColor(NovaTheme.dim)
            })
            return row
        }
    }

    companion object {
        private const val REQ_SAVE = 4101
        private const val REQ_RESTORE = 4102
    }
}
