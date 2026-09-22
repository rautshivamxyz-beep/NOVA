package org.nova

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Manage NOVA's knowledge base: user documents indexed for answers. */
class KnowledgeActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var list: LinearLayout
    private lateinit var wikiStatus: TextView
    private lateinit var wikiBtn: Button

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

    private fun build(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NovaTheme.bg)
            setPadding(dp(14), dp(28), dp(14), dp(30))
        }

        // header
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
            text = "Knowledge"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(NovaTheme.text)
        })
        col.addView(header)

        // switch
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(2))
        }
        row.addView(TextView(this).apply {
            text = "Answer using my documents"
            textSize = 15f; setTextColor(NovaTheme.text)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Switch(this).apply {
            isChecked = settings.knowledgeEnabled
            setOnCheckedChangeListener { _, v -> settings.knowledgeEnabled = v }
        })
        col.addView(row)

        col.addView(TextView(this).apply {
            text = "Add textbooks, notes or PDFs. When you ask a question, NOVA searches them and answers from what it finds."
            textSize = 12f; setTextColor(NovaTheme.dim)
            setPadding(dp(4), dp(6), dp(4), dp(10))
        })

        // add button
        col.addView(Button(this).apply {
            text = "Add documents"
            isAllCaps = false
            textSize = 14f
            setTextColor(NovaTheme.text)
            background = GradientDrawable().apply {
                setColor(NovaTheme.pill)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
            setOnClickListener {
                try {
                    val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/plain"))
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    startActivityForResult(pick, 7800)
                } catch (e: Exception) {
                    toast("No file picker available")
                }
            }
        })

        // ---- offline Wikipedia card
        col.addView(TextView(this).apply {
            text = "Offline Wikipedia"
            textSize = 16f; setTextColor(NovaTheme.text)
            setPadding(dp(4), dp(26), 0, dp(4))
        })
        val wikiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = GradientDrawable().apply {
                setColor(NovaTheme.pill)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        wikiStatus = TextView(this).apply {
            textSize = 13f; setTextColor(NovaTheme.dim)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        wikiRow.addView(wikiStatus)
        wikiBtn = Button(this).apply {
            isAllCaps = false
            setCompoundDrawablesWithIntrinsicBounds(
                tinted(R.drawable.ic_globe, NovaTheme.dim), null, null, null)
            background = null
            setOnClickListener { startWiki() }
            setOnLongClickListener {
                if (!WikiCore.isReady(this@KnowledgeActivity)) return@setOnLongClickListener true
                AlertDialog.Builder(this@KnowledgeActivity)
                    .setTitle("Remove Wikipedia?")
                    .setMessage("Deletes the downloaded articles. NOVA will answer without them.")
                    .setPositiveButton("Remove") { _, _ ->
                        WikiCore.remove(this@KnowledgeActivity)
                        refreshWiki()
                        toast("Wikipedia removed")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
        wikiRow.addView(wikiBtn)
        col.addView(wikiRow)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        rebuildList()
        refreshWiki()

        scroll.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun rebuildList() {
        list.removeAllViews()
        val docs = Knowledge.docs(this)
        if (docs.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No documents yet."
                textSize = 13f; setTextColor(NovaTheme.dim)
                setPadding(dp(4), dp(14), dp(4), dp(4))
            })
            return
        }
        for ((name, chunks) in docs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(6), dp(12))
                background = GradientDrawable().apply {
                    setColor(NovaTheme.pill)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), NovaTheme.border)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            row.addView(TextView(this).apply {
                text = name
                textSize = 14f; setTextColor(NovaTheme.text)
                setSingleLine(true)
                setCompoundDrawablesWithIntrinsicBounds(
                    tinted(R.drawable.ic_doc, NovaTheme.dim), null, null, null)
                compoundDrawablePadding = dp(10)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                isAllCaps = false
                setCompoundDrawablesWithIntrinsicBounds(
                    tinted(R.drawable.ic_close, NovaTheme.dim), null, null, null)
                background = null
                setOnClickListener {
                    Knowledge.removeDoc(this@KnowledgeActivity, name)
                    rebuildList()
                }
            })
            list.addView(row)
            list.addView(TextView(this).apply {
                text = "$chunks sections indexed"
                textSize = 11f; setTextColor(NovaTheme.dim)
                setPadding(dp(30), 0, 0, 0)
            })
        }
    }

    private fun refreshWiki() {
        when {
            WikiCore.downloading -> {
                wikiStatus.text = WikiCore.state.value.second.ifBlank { "downloading…" }
                wikiBtn.visibility = View.GONE
            }
            WikiCore.isReady(this) -> {
                wikiStatus.text = "${WikiCore.articleCount(this)} articles ready - NOVA answers with real facts"
                wikiBtn.visibility = View.VISIBLE
            }
            else -> {
                wikiStatus.text = WikiCore.lastError
                    ?.let { "\u2717 $it" }
                    ?: "Not downloaded (one time, ~25 MB)"
                wikiBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun startWiki() {
        if (WikiCore.downloading) return
        toast("Downloading Wikipedia - keep NOVA open, Wi-Fi recommended")
        Thread {
            kotlinx.coroutines.runBlocking { WikiCore.download(this@KnowledgeActivity) }
        }.start()
        Thread {
            while (WikiCore.downloading) {
                runOnUiThread { refreshWiki() }
                Thread.sleep(800)
            }
            runOnUiThread { refreshWiki() }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7800 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
        if (uris.isEmpty()) return
        toast("Indexing ${uris.size} document(s)…")
        Thread {
            var added = 0
            for (uri in uris) {
                val name = displayName(uri) ?: "document"
                val text = readText(uri)
                if (text.isNotBlank()) {
                    Knowledge.addDoc(this, name, text)
                    added++
                }
            }
            runOnUiThread {
                rebuildList()
                toast(if (added > 0) "Added $added document(s)" else "Could not read those files")
            }
        }.start()
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            } else null
        }
    } catch (e: Exception) { null }

    private fun readText(uri: Uri): String = try {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        val head = String(bytes.copyOfRange(0, minOf(200, bytes.size)))
        var text = if (head.contains("%PDF"))
            kotlinx.coroutines.runBlocking {
                PdfDoc.extractText(this@KnowledgeActivity, uri, 200_000)
            }
        else String(bytes)
        if (text.length > 200_000) text = text.substring(0, 200_000)
        text
    } catch (e: Exception) { "" }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
