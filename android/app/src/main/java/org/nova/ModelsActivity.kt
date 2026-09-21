package org.nova

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * Model manager: download curated GGUF models (or any custom Hugging Face
 * URL), import local .gguf files, and load / delete downloaded models.
 *
 * Deliberately dependency-light (plain android.app.Activity + coroutines),
 * matching the rest of NOVA, so it builds even in a minimal Termux setup.
 */
class ModelsActivity : Activity() {

    private lateinit var deviceText: TextView
    private lateinit var localInner: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var cancelBtn: Button
    private lateinit var customUrl: EditText

    private lateinit var settings: Settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val bg = NovaTheme.bg
    private val surface = NovaTheme.pill
    private val surfaceAlt = NovaTheme.surface
    private val accent = NovaTheme.accent
    private val textMain = NovaTheme.text
    private val textDim = NovaTheme.dim
    private val ok = Color.parseColor("#4ADE80")
    private val warn = Color.parseColor("#FBBF24")
    private val bad = Color.parseColor("#F87171")

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(0, dp(36), 0, dp(16))
        }

        buildHeader(root)
        buildBody(root)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })

        observeDownloader()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT cancel ModelDownloader's own scope here: downloads keep
        // running in the background and can be resumed from this screen.
        scope.cancel()
    }

    private fun buildHeader(root: LinearLayout) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        header.addView(Button(this).apply {
            text = "←"
            textSize = 18f
            background = null
            setTextColor(accent)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Models"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textMain)
            setPadding(dp(8), 0, 0, 0)
        })
        root.addView(header, params())
    }

    private fun buildBody(root: LinearLayout) {
        // ---- Device card
        deviceText = TextView(this).apply {
            setTextColor(textMain)
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        root.addView(cardWith(deviceText), params())

        // ---- Progress area
        progressText = TextView(this).apply {
            setTextColor(textDim)
            textSize = 13f
            setPadding(dp(22), 0, dp(22), 0)
            visibility = View.GONE
        }
        progressBar = ProgressBar(this).apply {
            max = 1000
            progress = 0
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        cancelBtn = smallButton("Cancel", bad).apply {
            visibility = View.GONE
            setOnClickListener { ModelDownloader.cancel() }
        }
        root.addView(progressText, params())
        root.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(22); rightMargin = dp(22) })
        root.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END; rightMargin = dp(22) })

        // ---- Catalog
        root.addView(sectionTitle("Download a model"), params())
        val catalogCard = card()
        val catInner = catalogCard.getChildAt(0) as LinearLayout
        for (entry in ModelCatalog.entries) {
            catInner.addView(catalogRow(entry))
        }
        // custom URL + import
        catInner.addView(TextView(this).apply {
            text = "Any other GGUF (Hugging Face URL)"
            setTextColor(textDim)
            textSize = 12f
            setPadding(0, dp(14), 0, dp(6))
        })
        customUrl = EditText(this).apply {
            hint = "https://huggingface.co/.../model-Q4_K_M.gguf"
            setHintTextColor(textDim)
            setTextColor(textMain)
            textSize = 13f
            setSingleLine()
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            background = GradientDrawable().apply {
                setColor(surfaceAlt)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        catInner.addView(customUrl, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val customBtn = smallButton("Download URL", accent)
        customBtn.setOnClickListener {
            val url = customUrl.text.toString().trim()
            if (!url.startsWith("https://")) {
                toast("Enter a valid https:// Hugging Face URL")
            } else {
                customUrl.setText("")
                hideKeyboard()
                ModelDownloader.download(url, ModelCatalog.modelsDir(this))
            }
        }
        catInner.addView(customBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        val importBtn = smallButton("Import local .gguf file", textDim)
        importBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
            }
            startActivityForResult(intent, REQ_PICK_GGUF)
        }
        catInner.addView(importBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        root.addView(catalogCard, params())

        // ---- Local models
        root.addView(sectionTitle("On this device"), params())
        val localCard = card()
        localInner = localCard.getChildAt(0) as LinearLayout
        root.addView(localCard, params())

        refreshDeviceCard()
        refreshLocalModels()
    }

    // ------------------------------------------------------------- helpers

    private fun params() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(14) }

    private fun sectionTitle(s: String) = TextView(this).apply {
        text = s
        setTextColor(textDim)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(22), 0, dp(22), dp(8))
        letterSpacing = 0.08f
    }

    /** Rounded surface card; returns the wrapper containing [inner]. */
    private fun card(): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(14).toFloat()
            }
            addView(inner)
        }
    }

    private fun cardWith(textView: TextView): LinearLayout {
        val wrap = card()
        (wrap.getChildAt(0) as LinearLayout).addView(textView)
        return wrap
    }

    private fun smallButton(label: String, color: Int): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(color)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), color)
            cornerRadius = dp(20).toFloat()
        }
        setPadding(dp(18), dp(8), dp(18), dp(8))
    }

    private fun catalogRow(entry: ModelCatalog.Entry): View {
        val fit = DeviceCapabilities.fitFor(entry.sizeBytes, this)
        val fitColor = when (fit) {
            DeviceCapabilities.Fit.COMFORTABLE -> ok
            DeviceCapabilities.Fit.TIGHT -> warn
            DeviceCapabilities.Fit.TOO_BIG -> bad
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this).apply {
            text = entry.name
            setTextColor(textMain)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = "${fit.emoji} ${entry.params}"
            setTextColor(fitColor)
            textSize = 13f
        })
        val meta = TextView(this).apply {
            text = "${entry.org} · ${entry.quant} · ${entry.sizeBytes / (1000L * 1000 * 1000)} GB · " +
                "needs ~${entry.minRamGb} GB RAM\n${entry.notes}"
            setTextColor(textDim)
            textSize = 12f
        }
        val dl = smallButton("Download", accent)
        dl.setOnClickListener {
            if (ModelDownloader.isBusy) toast("A download is already running")
            else ModelDownloader.download(entry.url, ModelCatalog.modelsDir(this))
        }
        row.addView(top, params())
        row.addView(meta, params().apply { topMargin = dp(2) })
        row.addView(dl, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        return row
    }

    private fun refreshDeviceCard() {
        val totalRam = DeviceCapabilities.totalRamGb(this)
        val freeRam = DeviceCapabilities.availableRamGb(this)
        val recommended = when {
            totalRam >= 11 -> "up to 14B Q4 models run well here"
            totalRam >= 7 -> "7B–8B Q4 models are the sweet spot"
            totalRam >= 5 -> "3B–4B Q4 models are the sweet spot"
            else -> "1B–3B Q4 models are recommended"
        }
        deviceText.text =
            "This device\n" +
            "RAM: %.1f GB total · %.1f GB free\n".format(totalRam, freeRam) +
            "CPU: ${DeviceCapabilities.cpuDescription()}\n\n" +
            "Recommended: $recommended"
    }

    // ------------------------------------------------------- local models

    private fun refreshLocalModels() {
        localInner.removeAllViews()

        val dir = ModelCatalog.modelsDir(this)
        val models = dir.listFiles { f: File -> f.name.endsWith(".gguf") }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?: emptyList()
        val activePath = NovaEngine.activeModelPath

        if (models.isEmpty()) {
            localInner.addView(TextView(this).apply {
                text = "No models yet — download one above, or import a .gguf file.\n\n" +
                    "Everything stays on this device. NOVA works fully offline."
                setTextColor(textDim)
                textSize = 13f
            })
            return
        }

        for (m in models) {
            val isActive = m.absolutePath == activePath
            localInner.addView(TextView(this).apply {
                text = (if (isActive) "● " else "") + ModelCatalog.labelFor(m)
                setTextColor(if (isActive) accent else textMain)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
            localInner.addView(TextView(this).apply {
                text = "${m.name} · ${m.length() / (1000L * 1000 * 1000)} GB"
                setTextColor(textDim)
                textSize = 11f
            })
            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btnRow.addView(smallButton("Load", accent).apply {
                setOnClickListener { confirmAndLoad(m) }
            })
            btnRow.addView(smallButton("Delete", bad).apply {
                setOnClickListener { confirmAndDelete(m) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(10) })
            localInner.addView(btnRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(12) })
        }
    }

    // ------------------------------------------------------------- actions

    private fun confirmAndLoad(f: File) {
        val fit = DeviceCapabilities.fitFor(f.length(), this)
        if (fit == DeviceCapabilities.Fit.TOO_BIG) {
            AlertDialog.Builder(this)
                .setTitle("Large model")
                .setMessage(
                    "This model may be too large for this device " +
                        "(${f.length() / (1000L * 1000 * 1000)} GB file, " +
                        "${"%.1f".format(DeviceCapabilities.totalRamGb(this))} GB RAM). " +
                        "It may fail to load or be killed by the system. Try anyway?"
                )
                .setPositiveButton("Try") { _, _ -> loadModel(f) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            loadModel(f)
        }
    }

    private fun loadModel(f: File) {
        if (NovaEngine.isLoading) {
            toast("Still loading the previous model…")
            return
        }
        toast("Loading ${f.name}…")
        NovaEngine.scope.launch {
            try {
                NovaEngine.load(this@ModelsActivity, f.absolutePath, ModelCatalog.labelFor(f), settings.systemPrompt)
                settings.lastModelPath = f.absolutePath
                settings.lastModelLabel = ModelCatalog.labelFor(f)
                toast("Model ready")
                setResult(Activity.RESULT_OK)
                finish()
            } catch (e: Exception) {
                toast("Failed to load: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun confirmAndDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete model?")
            .setMessage("${f.name} (${f.length() / (1000L * 1000 * 1000)} GB) will be permanently removed.")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    if (NovaEngine.activeModelPath == f.absolutePath) {
                        NovaEngine.unload(this@ModelsActivity)
                    }
                    withContext(Dispatchers.IO) {
                        f.delete()
                        File(f.absolutePath + ".part").delete()
                    }
                    if (settings.lastModelPath == f.absolutePath) {
                        settings.lastModelPath = null
                        settings.lastModelLabel = ""
                    }
                    refreshLocalModels()
                    toast("Deleted")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------- import

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_GGUF && resultCode == Activity.RESULT_OK) {
            data?.data?.let { importPicked(it) }
        }
    }

    private fun importPicked(uri: Uri) {
        var name = "imported-model.gguf"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.let { if (it.isNotBlank()) name = it }
                val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (sIdx >= 0 && !c.isNull(sIdx)) size = c.getLong(sIdx)
            }
        }
        if (!name.endsWith(".gguf", true)) name = "$name.gguf"

        // Verify GGUF magic before starting the copy
        scope.launch {
            val magicOk = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use {
                        val head = ByteArray(4)
                        it.read(head) == 4 && String(head) == "GGUF"
                    } ?: false
                } catch (e: Exception) {
                    false
                }
            }
            if (!magicOk) {
                toast("That file is not a valid GGUF model")
            } else {
                val opener: () -> InputStream? = {
                    try {
                        contentResolver.openInputStream(uri)
                    } catch (e: Exception) {
                        null
                    }
                }
                ModelDownloader.import(name, opener, size, ModelCatalog.modelsDir(this@ModelsActivity))
            }
        }
    }

    // ---------------------------------------------------------- downloader

    private fun observeDownloader() {
        scope.launch {
            ModelDownloader.state.collect { renderProgress(it) }
        }
    }

    private fun renderProgress(st: ModelDownloader.State) {
        when (st) {
            is ModelDownloader.State.Idle -> {
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                cancelBtn.visibility = View.GONE
            }
            is ModelDownloader.State.Downloading -> {
                progressText.visibility = View.VISIBLE
                progressText.text = "Downloading ${st.name}\n${gb(st.downloaded)} / " +
                    (if (st.total > 0) gb(st.total) else "?") +
                    (if (st.total > 0) "  (${st.downloaded * 100 / st.total}%)" else "")
                progressBar.visibility = View.VISIBLE
                if (st.total > 0) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = (st.downloaded * 1000 / st.total).toInt()
                } else {
                    progressBar.isIndeterminate = true
                }
                cancelBtn.visibility = View.VISIBLE
            }
            is ModelDownloader.State.Importing -> {
                progressText.visibility = View.VISIBLE
                progressText.text = "Importing ${st.name} — ${gb(st.copied)} copied"
                progressBar.visibility = View.VISIBLE
                if (st.total > 0) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = (st.copied * 1000 / st.total).toInt()
                } else {
                    progressBar.isIndeterminate = true
                }
                cancelBtn.visibility = View.VISIBLE
            }
            is ModelDownloader.State.Done -> {
                progressBar.visibility = View.GONE
                cancelBtn.visibility = View.GONE
                progressText.visibility = View.VISIBLE
                progressText.text = "✓ Saved ${st.file.name}"
                refreshLocalModels()
                toast("${st.file.name} is ready — tap Load")
                ModelDownloader.acknowledge()
            }
            is ModelDownloader.State.Failed -> {
                progressBar.visibility = View.GONE
                cancelBtn.visibility = View.GONE
                progressText.visibility = View.VISIBLE
                progressText.text = "✗ ${st.name}: ${st.error}\nTap Download again to resume."
                refreshLocalModels()
            }
        }
    }

    private fun gb(bytes: Long): String =
        if (bytes >= 1000L * 1000 * 1000) "%.2f GB".format(bytes / 1e9)
        else "%.0f MB".format(bytes / 1e6)

    // -------------------------------------------------------------- utils

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard() {
        currentFocus?.let { view ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    companion object {
        private const val REQ_PICK_GGUF = 4242
    }
}
