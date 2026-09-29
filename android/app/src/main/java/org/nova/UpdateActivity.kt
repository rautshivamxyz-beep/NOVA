package org.nova

import android.app.AlertDialog
import android.app.ListActivity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.nova.ncie.android.NcieDelta
import java.io.File

/**
 * v9.7.0 "Delta Updates": the Update screen, from the drawer's Update row.
 *
 * Three rows: open the (private) releases page - the same browser intent
 * the drawer row used before; APPLY UPDATE PATCH, highlighted - pick a
 * NOVA-delta-*.patch downloaded from the latest release with SAF, and
 * NcieDelta applies it against the INSTALLED APK entirely on this phone,
 * then hands the built APK to the system installer; and a non-clickable
 * info line with the version and the download hint.
 *
 * Design law, kept: everything local - the patch application is pure file
 * work, install always happens behind an explicit user tap, no accounts.
 * Same style as BackupActivity/FetchLogActivity: ListActivity, own
 * ListView under android.R.id.list, programmatic UI, no XML layouts,
 * fail-soft everywhere.
 */
class UpdateActivity : ListActivity() {

    /** the built update APK in the cache (FileProvider territory) */
    private var builtApk: File? = null
    /** the same APK handed to MediaStore.Downloads, when that worked */
    private var builtUri: Uri? = null

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
            text = "Update"
            textSize = 22f
            setTextColor(NovaTheme.text)
            typeface = Typeface.DEFAULT_BOLD
        })
        hint = TextView(this).apply {
            text = "You are on v" + currentVersion + ".\n" +
                "Download the NOVA-delta-*.patch file from the latest\n" +
                "release first, then apply it here - or grab the full APK."
            textSize = 13f
            setTextColor(NovaTheme.dim)
            setPadding(0, pad(4), 0, pad(10))
        }
        root.addView(hint)
        // v8.5.1 lesson: build our OWN ListView with the id ListActivity
        // requires - never re-parent the default one.
        val list = ListView(this).apply {
            id = android.R.id.list
            divider = null
            dividerHeight = pad(8)
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        listAdapter = RowAdapter()
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = 3
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = position != ROW_INFO

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = LinearLayout(this@UpdateActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    when (position) {
                        ROW_APPLY -> setColor(NovaTheme.accentDeep)
                        else -> setColor(NovaTheme.pill)
                    }
                    cornerRadius = 12f * resources.displayMetrics.density
                    setStroke(1, NovaTheme.border)
                }
                setPadding((16 * dp2()).toInt(), (14 * dp2()).toInt(),
                    (16 * dp2()).toInt(), (14 * dp2()).toInt())
            }
            row.addView(TextView(this@UpdateActivity).apply {
                when (position) {
                    ROW_RELEASES -> { text = "OPEN RELEASES PAGE"; setTextColor(NovaTheme.text) }
                    ROW_APPLY -> { text = "APPLY UPDATE PATCH"; setTextColor(Color.WHITE) }
                    else -> { text = "v" + currentVersion + " - Download the " +
                        "NOVA-delta-*.patch file from the latest release first"
                        setTextColor(NovaTheme.dim) }
                }
                textSize = if (position == ROW_INFO) 12f else 15f
                typeface = if (position == ROW_INFO) Typeface.DEFAULT
                           else Typeface.DEFAULT_BOLD
            })
            return row
        }
    }

    override fun onListItemClick(l: ListView?, v: View?, position: Int, id: Long) {
        when (position) {
            ROW_RELEASES -> openReleases()
            ROW_APPLY -> pickPatch()
        }
    }

    private fun dp2(): Float = resources.displayMetrics.density

    // ------------------------------------------------------------------
    // Row 1: the releases page - the same ACTION_VIEW the drawer's
    // Update row did before v9.7.0 opened this screen instead.
    // ------------------------------------------------------------------

    private fun openReleases() {
        toast("Opening private releases - sign in as the owner")
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/rautshivamxyz-beep/NOVA/releases")))
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------
    // Row 2: pick a NOVA-delta-*.patch (SAF, any mime - the bsdiff40
    // header is validated when it is applied), then apply it on device.
    // ------------------------------------------------------------------

    private fun pickPatch() {
        // the picker cannot filter by extension - accept any file and
        // let the header check reject what is not a delta patch
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(i, REQ_PICK_PATCH)
        } catch (e: Exception) {
            toast("No file picker available")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PICK_PATCH -> confirmAndApply(uri)
            REQ_INSTALL_PERM -> {
                // back from the install-permissions screen: if the user
                // granted it and the built APK is still there, offer the
                // install again without re-picking anything
                if (builtApk != null && packageManager.canRequestPackageInstalls()) installNow()
            }
        }
    }

    /** best-effort filename check: the patch name carries its from-to
     *  versions; when the "from" half does not match what is installed,
     *  say so before spending the work */
    private fun confirmAndApply(uri: Uri) {
        val name = displayName(uri)
        if (name != null && !name.lowercase().contains("delta-v" + currentVersion)) {
            AlertDialog.Builder(this)
                .setTitle("Different version?")
                .setMessage("This patch is for a different version - apply anyway?")
                .setPositiveButton("Apply anyway") { _, _ -> runApply(uri) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        runApply(uri)
    }

    private fun runApply(uri: Uri) {
        toast("Applying the patch - this takes a moment")
        Thread {
            val dir = File(cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "NOVA-update.apk")
            val ok = NcieDelta.applyPatch(this, uri, out)
            runOnUiThread {
                if (!ok) {
                    AlertDialog.Builder(this)
                        .setTitle("Patch failed")
                        .setMessage("Patch failed - download the full APK instead.")
                        .setPositiveButton("Open releases") { _, _ -> openReleases() }
                        .setNegativeButton("Close", null)
                        .show()
                    return@runOnUiThread
                }
                builtApk = out
                builtUri = null
                handOverAndInstall(out)
            }
        }.start()
    }

    /** the built APK goes to Downloads (MediaStore) when possible - that
     *  is where the user looked for it anyway - and the install runs off
     *  the same copy. When MediaStore declines, the APK stays in the app
     *  cache and installs through the FileProvider, and the toast says
     *  exactly where it is. */
    private fun handOverAndInstall(out: File) {
        var savedToDownloads = false
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "NOVA-update.apk")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
            }
            val storeUri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (storeUri != null) {
                contentResolver.openOutputStream(storeUri)?.use { os ->
                    out.inputStream().use { it.copyTo(os) }
                }
                builtUri = storeUri
                savedToDownloads = true
            }
        } catch (e: Exception) { }
        if (savedToDownloads) {
            // the permanent copy exists - the cache original is redundant
            try { out.delete() } catch (e: Exception) { }
            toast("NOVA-update.apk saved in Downloads")
        } else {
            toast("NOVA-update.apk saved at " + out.path)
        }
        checkInstallPermission()
    }

    private fun checkInstallPermission() {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Allow install from NOVA in settings")
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + packageName)), REQ_INSTALL_PERM)
            } catch (e: Exception) { }
            return
        }
        installNow()
    }

    /** install always behind this explicit user tap: the picked patch
     *  was a tap, and the system installer prompt is the final ask */
    private fun installNow() {
        val uri = builtUri ?: builtApk?.let {
            try { FileProvider.getUriForFile(this, "org.nova.fileprovider", it) } catch (e: Exception) { null }
        } ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            toast("Could not start the installer - open NOVA-update.apk from Downloads")
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private val currentVersion: String
        get() = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst())
                c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    } catch (e: Exception) { null }

    private fun toast(s: String) {
        try { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } catch (e: Exception) { }
    }

    companion object {
        private const val ROW_RELEASES = 0
        private const val ROW_APPLY = 1
        private const val ROW_INFO = 2
        private const val REQ_PICK_PATCH = 4201
        private const val REQ_INSTALL_PERM = 4202
    }
}
