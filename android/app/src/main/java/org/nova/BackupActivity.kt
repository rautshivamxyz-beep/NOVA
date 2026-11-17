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
            setBackgroundColor(NovaTheme.bg)
            setPadding(pad(20), pad(18), pad(20), pad(12))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.backup_title)
            textSize = 22f
            setTextColor(NovaTheme.text)
            typeface = Typeface.DEFAULT_BOLD
        })
        hint = TextView(this).apply {
            text = getString(R.string.backup_hint)
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
            text = getString(R.string.backup_save)
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = NovaUi.shape(this@BackupActivity, NovaTheme.accentDeep,
                NovaTheme.RADIUS_FIELD)
            setPadding(pad(16), pad(10), pad(16), pad(10))
            setOnClickListener { pickSaveTarget() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = getString(R.string.backup_restore_btn)
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
            toast(getString(R.string.no_file_picker))
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
    private fun collectFiles(): List<Pair<File, String>> {
        val out = mutableListOf<Pair<File, String>>()
        fun walk(root: File, prefix: String) {
            fun rec(d: File) {
                val kids = d.listFiles() ?: return
                for (f in kids) {
                    if (f.isDirectory) {
                        if (f.name == "cache" || f.name == "code_cache") continue
                        rec(f)
                    } else if (f.isFile && !f.name.endsWith(".tmp") &&
                        // v9.3.0 "Audit Fixes I": the notification log is
                        // private - it must not leave the phone in a backup
                        f.name != "notif_log.txt" && f.name != "notif_pause.txt") {
                        out.add(f to (prefix + f.relativeTo(root).path
                            .replace(File.separatorChar, '/')))
                    }
                }
            }
            rec(root)
        }
        walk(filesDir, "")
        // v9.16.3: the app's SharedPreferences (system prompt, memory, theme,
        // proxy...) live in shared_prefs, NOT filesDir - a backup used to
        // silently drop every setting. Include them now.
        val sp = File(filesDir.parentFile, "shared_prefs")
        if (sp.isDirectory) walk(sp, "shared_prefs/")
        out.sortBy { it.second }
        return out
    }

    private fun saveBackup(uri: Uri, password: String) {
        Thread {
            var count = 0
            var bytes = 0L
            var ok = false
            try {
                val files = collectFiles()
                // v9.33.140: a null stream skipped the whole zip and still
                // reported the backup saved. Same lie, other direction.
                if (files.isEmpty()) throw java.io.IOException("There is nothing to back up yet.")
                val out = contentResolver.openOutputStream(uri, "w")
                    ?: throw java.io.IOException("The backup file could not be opened.")
                out.use { os ->
                    // v9.16.10: a password wraps the zip in AES-GCM, so a
                    // backup kept in the cloud or on a laptop is unreadable
                    // without it. Blank password = plain zip, as before.
                    val sink: java.io.OutputStream =
                        if (password.isNotBlank()) wrapOut(os, password) else os
                    ZipOutputStream(BufferedOutputStream(sink)).use { zos ->
                        for ((f, rel) in files) {
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
            } catch (e: Exception) { NovaLog.quiet("BackupActivity", e) }
            runOnUiThread {
                if (ok) {
                    hint?.text = getString(R.string.backup_saved_hint, count, kb(bytes))
                    toast(getString(R.string.toast_backup_saved))
                } else {
                    toast(getString(R.string.backup_failed))
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
            toast(getString(R.string.no_file_picker))
        }
    }

    private fun inspectBackup(uri: Uri, password: String?) {
        Thread {
            val loaded = mutableListOf<Entry>()
            try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    val bin = BufferedInputStream(ins)
                    val zin = if (password != null) wrapIn(bin, password) else bin
                    ZipInputStream(zin).use { zis ->
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
            } catch (e: Exception) { NovaLog.quiet("BackupActivity", e) }
            runOnUiThread {
                entries = loaded
                adapter?.notifyDataSetChanged()
                if (loaded.isEmpty()) {
                    hint?.text = getString(R.string.backup_not_a_backup)
                } else {
                    hint?.text = getString(R.string.backup_files_in, loaded.size)
                    confirmRestore(uri, password)
                }
            }
        }.start()
    }

    private fun confirmRestore(uri: Uri, password: String?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_restore_q)
            // v9.4.0 "Audit Fixes II": the restore is a true replace now -
            // the warning must say exactly what that means
            .setMessage(R.string.backup_restore_msg)
            .setPositiveButton(R.string.action_restore) { _, _ -> restoreBackup(uri, password) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * v9.33.140 "Nothing is deleted before it is replaced": where one entry of
     * the backup is staged while the zip is still being read.
     *
     * The staging tree mirrors the app data folder - `files/...` for filesDir,
     * `shared_prefs/...` beside it - so the swap later is a plain copy into
     * place. A crafted entry cannot escape the staging folder: the resolved
     * path must still be under it, which is what the canonical-path test
     * below enforces. Pure file arithmetic, so a unit test pins the rule.
     */
    private fun stageEntry(staging: File, name: String): File? = stageTarget(staging, name)

    /** Copy every staged file into place, remembering what was restored. */
    private fun place(from: File, to: File, prefix: String, keep: MutableSet<String>): Int {
        if (!from.isDirectory) return 0
        var n = 0
        from.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(from).path
            val target = File(to, rel)
            val parent = target.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs())
                throw java.io.IOException("Could not create restore folder: " + parent.name)
            f.inputStream().use { input ->
                java.io.FileOutputStream(target).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            keep.add(prefix + "/" + rel.replace(File.separatorChar, '/'))
            n++
        }
        return n
    }

    /** Snapshot/rollback copies run only during an explicit user restore. */
    private fun copyTree(from: File, to: File, skipRuntime: Boolean) {
        if (!from.exists()) return
        if (from.isDirectory) {
            if (skipRuntime && (from.name == "cache" || from.name == "code_cache")) return
            if (!to.exists() && !to.mkdirs())
                throw java.io.IOException("Could not create rollback folder: " + to.name)
            val children = from.listFiles()
                ?: throw java.io.IOException("Could not read app data folder: " + from.name)
            for (child in children) {
                if (skipRuntime && (child.name == "notif_log.txt" || child.name == "notif_pause.txt")) continue
                copyTree(child, File(to, child.name), skipRuntime)
            }
        } else {
            val parent = to.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs())
                throw java.io.IOException("Could not create rollback folder: " + parent.name)
            from.inputStream().use { input ->
                java.io.FileOutputStream(to).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
        }
    }

    /** Delete mutable state while preserving caches and phone-local runtime logs. */
    private fun clearMutableTree(top: File, preserveRuntime: Boolean) {
        if (!top.exists()) return
        if (!top.isDirectory) throw java.io.IOException("Expected a folder: " + top.name)
        val children = top.listFiles()
            ?: throw java.io.IOException("Could not list app data folder: " + top.name)
        for (child in children) {
            if (child.isDirectory) {
                if (child.name == "cache" || child.name == "code_cache") continue
                clearMutableTree(child, preserveRuntime)
                val remaining = child.listFiles()
                    ?: throw java.io.IOException("Could not list restore folder: " + child.name)
                if (remaining.isEmpty() && !child.delete())
                    throw java.io.IOException("Could not remove old folder: " + child.name)
            } else if (preserveRuntime &&
                (child.name == "notif_log.txt" || child.name == "notif_pause.txt")) {
                continue
            } else if (!child.delete()) {
                throw java.io.IOException("Could not remove old file: " + child.name)
            }
        }
    }

    /**
     * The v9.4.0 "true replace", applied LAST instead of first: delete what
     * the backup did not contain, so the state after a restore is exactly the
     * state in the zip. Every restored file is already on disk when this runs,
     * which is the whole point of v9.33.140 - the old order deleted first.
     */
    private fun prune(top: File, prefix: String, keep: Set<String>, keepRuntimeFiles: Boolean) {
        fun walk(dir: File) {
            val kids = dir.listFiles()
                ?: throw java.io.IOException("Could not list restore folder: " + dir.name)
            for (f in kids) {
                if (f.isDirectory) {
                    if (f.name == "cache" || f.name == "code_cache") continue
                    walk(f)
                    if ((f.listFiles() ?: emptyArray()).isEmpty() && !f.delete())
                        throw java.io.IOException("Could not remove empty folder: " + f.name)
                    continue
                }
                // These phone-local runtime files are not part of the backup.
                if (keepRuntimeFiles && (f.name == "notif_log.txt" || f.name == "notif_pause.txt")) continue
                val rel = f.relativeTo(top).path.replace(File.separatorChar, '/')
                if ((prefix + "/" + rel) !in keep && !f.delete())
                    throw java.io.IOException("Could not remove old file: " + f.name)
            }
        }
        if (top.exists()) walk(top)
    }

    private fun restoreBackup(uri: Uri, password: String?) {
        Thread {
            var restored = 0
            var ok = false
            val root = filesDir.parentFile?.canonicalFile
            val staging = if (root != null) File(root, STAGING_DIR) else null
            var snapshotReady = false
            try {
                if (root == null || staging == null)
                    throw java.io.IOException("NOVA has no app data folder.")
                if (staging.exists() && !staging.deleteRecursively())
                    throw java.io.IOException("The restore staging folder could not be cleared.")
                if (!staging.mkdirs())
                    throw java.io.IOException("The restore staging folder could not be created.")

                val input = contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("The backup file could not be opened.")
                val seen = HashSet<String>()
                var entryCount = 0
                var totalBytes = 0L
                input.use { raw ->
                    val bin = BufferedInputStream(raw)
                    val zin = if (password != null) wrapIn(bin, password) else bin
                    ZipInputStream(zin).use { zis ->
                        var ze: ZipEntry? = zis.nextEntry
                        while (ze != null) {
                            if (++entryCount > MAX_RESTORE_ENTRIES)
                                throw java.io.IOException("The backup has too many entries.")
                            val target = stageEntry(staging, ze.name)
                                ?: throw java.io.IOException("The backup contains an unsafe path.")
                            val canonical = target.canonicalPath
                            if (!seen.add(canonical))
                                throw java.io.IOException("The backup contains a duplicate path.")
                            if (ze.isDirectory) {
                                if (!target.exists() && !target.mkdirs())
                                    throw java.io.IOException("Could not stage backup folder: " + target.name)
                                if (!target.isDirectory)
                                    throw java.io.IOException("A backup folder conflicts with a file.")
                            } else {
                                if (target.exists() && target.isDirectory)
                                    throw java.io.IOException("A backup file conflicts with a folder.")
                                val parent = target.parentFile
                                if (parent != null && !parent.exists() && !parent.mkdirs())
                                    throw java.io.IOException("Could not stage backup folder: " + parent.name)
                                var fileBytes = 0L
                                target.outputStream().buffered().use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val n = zis.read(buffer)
                                        if (n < 0) break
                                        fileBytes += n
                                        totalBytes += n
                                        if (fileBytes > MAX_RESTORE_FILE_BYTES ||
                                            totalBytes > MAX_RESTORE_TOTAL_BYTES)
                                            throw java.io.IOException("The backup exceeds safe extraction limits.")
                                        out.write(buffer, 0, n)
                                    }
                                }
                                restored++
                            }
                            zis.closeEntry()
                            ze = zis.nextEntry
                        }
                    }
                }
                if (restored == 0) throw java.io.IOException("The backup held no files.")

                // Back up the current state before touching it. The rollback
                // snapshot skips caches and local notification runtime logs.
                val rollback = File(staging, "rollback")
                val rollbackFiles = File(rollback, "files")
                val rollbackPrefs = File(rollback, "shared_prefs")
                copyTree(filesDir, rollbackFiles, true)
                copyTree(File(root, "shared_prefs"), rollbackPrefs, false)
                snapshotReady = true

                try {
                    val keep = HashSet<String>()
                    restored = place(File(staging, "files"), filesDir, "files", keep) +
                        place(File(staging, "shared_prefs"), File(root, "shared_prefs"), "shared_prefs", keep)
                    if (restored == 0) throw java.io.IOException("The backup held no restorable files.")
                    prune(filesDir, "files", keep, true)
                    prune(File(root, "shared_prefs"), "shared_prefs", keep, false)
                    ok = true
                } catch (commitError: Exception) {
                    // A copy/prune failure must not leave half of the old
                    // profile replaced. Restore the snapshot before reporting.
                    if (snapshotReady) {
                        try {
                            clearMutableTree(filesDir, true)
                            copyTree(rollbackFiles, filesDir, false)
                            val prefsDir = File(root, "shared_prefs")
                            clearMutableTree(prefsDir, false)
                            copyTree(rollbackPrefs, prefsDir, false)
                        } catch (rollbackError: Exception) {
                            NovaLog.quiet("BackupActivity.rollback", rollbackError)
                        }
                    }
                    throw commitError
                }
            } catch (e: Exception) {
                NovaLog.quiet("BackupActivity", e)
            } finally {
                try {
                    if (staging != null && staging.exists()) staging.deleteRecursively()
                } catch (e: Exception) { NovaLog.quiet("BackupActivity", e) }
            }
            runOnUiThread {
                if (ok) {
                    hint?.text = getString(R.string.backup_restored, restored)
                    AlertDialog.Builder(this)
                        .setMessage(R.string.backup_restore_complete)
                        .setCancelable(false)
                        .setPositiveButton(R.string.action_ok) { _, _ ->
                            finishAffinity()
                            System.exit(0)
                        }
                        .show()
                } else {
                    toast(getString(R.string.backup_restore_failed))
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == REQ_SAVE) askPasswordForSave(uri)
        else if (requestCode == REQ_RESTORE) {
            if (isEncrypted(uri)) askPasswordForRestore(uri) else inspectBackup(uri, null)
        }
    }

    // ---- v9.16.10 "Encrypted backup": optional password ----------------

    private fun askPasswordForSave(uri: Uri) {
        val box = android.widget.EditText(this)
        box.hint = getString(R.string.backup_password_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_password_optional)
            .setMessage(R.string.backup_password_msg)
            .setView(box)
            .setPositiveButton(R.string.action_save) { _, _ -> saveBackup(uri, box.text.toString().trim()) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun askPasswordForRestore(uri: Uri) {
        val box = android.widget.EditText(this)
        box.hint = getString(R.string.backup_password_set_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_encrypted)
            .setMessage(R.string.backup_encrypted_msg)
            .setView(box)
            .setPositiveButton(R.string.action_open) { _, _ -> inspectBackup(uri, box.text.toString().trim()) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    // ---- v9.16.10 "Encrypted backup": AES-GCM + PBKDF2 ----------------

    private fun deriveKey(password: String, salt: ByteArray): javax.crypto.SecretKey {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        val f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return javax.crypto.spec.SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }

    /** Writes the MAGIC + salt + iv header, then an AES-GCM stream. */
    private fun wrapOut(os: java.io.OutputStream, password: String): java.io.OutputStream {
        val salt = ByteArray(16); java.security.SecureRandom().nextBytes(salt)
        val iv = ByteArray(12); java.security.SecureRandom().nextBytes(iv)
        os.write(MAGIC.toByteArray(Charsets.US_ASCII))
        os.write(salt); os.write(iv)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, deriveKey(password, salt),
            javax.crypto.spec.GCMParameterSpec(128, iv))
        return javax.crypto.CipherOutputStream(os, c)
    }

    /** Reads the header and returns an AES-GCM decrypting stream. A wrong
     *  password fails the GCM tag check at the end of the stream. */
    private fun wrapIn(ins: java.io.InputStream, password: String): java.io.InputStream {
        val h = ByteArray(7); readFully(ins, h)
        val salt = ByteArray(16); readFully(ins, salt)
        val iv = ByteArray(12); readFully(ins, iv)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, deriveKey(password, salt),
            javax.crypto.spec.GCMParameterSpec(128, iv))
        return javax.crypto.CipherInputStream(ins, c)
    }

    private fun readFully(ins: java.io.InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val r = ins.read(b, off, b.size - off)
            if (r < 0) throw java.io.EOFException()
            off += r
        }
    }

    private fun isEncrypted(uri: Uri): Boolean = try {
        contentResolver.openInputStream(uri)?.use { ins ->
            val h = ByteArray(7)
            var off = 0
            while (off < 7) { val r = ins.read(h, off, 7 - off); if (r < 0) break; off += r }
            off == 7 && String(h, Charsets.US_ASCII) == MAGIC
        } ?: false
    } catch (e: Exception) { false }

    private fun toast(s: String) {
        try { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } catch (e: Exception) { NovaLog.quiet("BackupActivity", e) }
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
        private const val MAGIC = "NOVABK1"
        /** v9.33.140: where a restore is read into before anything is replaced. */
        private const val STAGING_DIR = "nova_restore_staging"
        private const val MAX_RESTORE_ENTRIES = 10_000
        private const val MAX_RESTORE_FILE_BYTES = 128L * 1024 * 1024
        private const val MAX_RESTORE_TOTAL_BYTES = 512L * 1024 * 1024
        private const val REQ_SAVE = 4101
        private const val REQ_RESTORE = 4102
    }
}

/**
 * v9.33.140 "Nothing is deleted before it is replaced": where one backup entry
 * lands inside the restore staging folder, or null when it would escape it.
 *
 * `files/...` mirrors filesDir and `shared_prefs/...` mirrors the folder beside
 * it. The resolved path must still sit under the staging folder, so an entry
 * named "../../databases/x" or "shared_prefs/../../evil" is refused instead of
 * being written outside the app's own data. Pure file arithmetic - a unit test
 * pins it without an Activity, a zip or a device.
 */
internal fun stageTarget(staging: File, name: String): File? {
    val normalized = name.replace('\\', '/')
    if (normalized.isBlank() || normalized.startsWith("/") ||
        normalized.contains(':') || normalized.contains('\u0000')) return null
    val isPrefsRoot = normalized == "shared_prefs/"
    val isPrefs = isPrefsRoot || normalized.startsWith("shared_prefs/")
    val clean = if (normalized.endsWith("/")) normalized.dropLast(1) else normalized
    if (clean.isBlank()) return null
    val parts = clean.split('/')
    if (parts.any { it.isBlank() || it == "." || it == ".." }) return null

    val relative = if (isPrefsRoot) "" else if (isPrefs) clean.removePrefix("shared_prefs/") else clean
    val target = when {
        isPrefsRoot -> File(staging, "shared_prefs")
        isPrefs -> File(File(staging, "shared_prefs"), relative)
        else -> File(File(staging, "files"), clean)
    }
    val base = staging.canonicalFile.path
    val canonical = target.canonicalFile.path
    return if (canonical == base || canonical.startsWith(base + File.separator)) target else null
}
