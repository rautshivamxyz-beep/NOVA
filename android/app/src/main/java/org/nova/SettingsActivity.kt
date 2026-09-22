package org.nova

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Full settings screen: voice, memory, personality, appearance and data.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        NovaTheme.apply(settings.theme == "light")
        window.statusBarColor = NovaTheme.bg
        window.navigationBarColor = NovaTheme.bg
        setContentView(build())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun card(title: String): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(NovaTheme.pill)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        outer.addView(TextView(this).apply {
            text = title
            textSize = 12f
            letterSpacing = 0.1f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NovaTheme.dim)
        })
        return outer
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 15f; setTextColor(NovaTheme.text)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        return row
    }

    private fun editField(text: String, hint: String, onChange: (String) -> Unit): EditText {
        val field = EditText(this).apply {
            this.hint = hint
            setHintTextColor(NovaTheme.dim)
            setTextColor(NovaTheme.text)
            textSize = 14f
            setSingleLine(false)
            minLines = 2
            maxLines = 6
            background = GradientDrawable().apply {
                setColor(NovaTheme.bg)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        // listener added AFTER setText so opening the screen never saves the
        // initial text over the user's (or the default) value
        field.setText(text)
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
        return field
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
            val d = getDrawable(R.drawable.ic_back)!!.mutate()
            d.colorFilter = android.graphics.PorterDuffColorFilter(
                NovaTheme.text, android.graphics.PorterDuff.Mode.SRC_IN)
            setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
            background = null
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        header.addView(TextView(this).apply {
            text = "Settings"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(NovaTheme.text)
        })
        col.addView(header)

        // Voice
        val voice = card("VOICE")
        voice.addView(switchRow("Read replies aloud", settings.readAloud) { settings.readAloud = it })
        voice.addView(switchRow("Auto-listen (conversation mode)", settings.autoListen) { settings.autoListen = it })
        col.addView(voice)

        // Memory
        val memory = card("MEMORY")
        memory.addView(TextView(this).apply {
            text = "Facts NOVA always remembers"
            textSize = 12f; setTextColor(NovaTheme.dim)
            setPadding(0, dp(8), 0, dp(6))
        })
        memory.addView(editField(settings.memory, "e.g. My exam is on 12 May") { settings.memory = it })
        col.addView(memory)

        // Personality
        val person = card("PERSONALITY")
        person.addView(TextView(this).apply {
            text = "System prompt — how NOVA should behave"
            textSize = 12f; setTextColor(NovaTheme.dim)
            setPadding(0, dp(8), 0, dp(6))
        })
        person.addView(editField(settings.systemPrompt, "") { settings.systemPrompt = it })
        col.addView(person)

        // Answers
        val answers = card("ANSWERS")
        answers.addView(switchRow("Use offline Wikipedia", settings.wikiEnabled) {
            settings.wikiEnabled = it
        })
        answers.addView(Button(this).apply {
            isAllCaps = false
            background = null
            setPadding(0, dp(10), 0, dp(4))
            fun refreshLen() {
                text = "Response length: ${settings.predictLength} tokens"
                setTextColor(NovaTheme.text)
                textSize = 15f
            }
            refreshLen()
            setOnClickListener {
                val opts = Settings.LENGTH_OPTIONS.map { "$it tokens" }.toTypedArray()
                val cur = Settings.LENGTH_OPTIONS.indexOf(settings.predictLength)
                    .coerceAtLeast(0)
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Response length")
                    .setSingleChoiceItems(opts, cur) { d, which ->
                        settings.predictLength = Settings.LENGTH_OPTIONS[which]
                        refreshLen()
                        d.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        col.addView(answers)

        // Appearance
        val looks = card("APPEARANCE")
        looks.addView(switchRow("Light theme", settings.theme == "light") {
            settings.theme = if (it) "light" else "dark"
            NovaTheme.apply(it)
            toast("Theme changes when you go back")
        })
        col.addView(looks)

        // Data
        val data = card("DATA")
        data.addView(Button(this).apply {
            text = "Delete all chats"
            isAllCaps = false
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#FF6B6B"))
            background = null
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Delete all chats?")
                    .setMessage("This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        ChatStore.clearAll(this@SettingsActivity)
                        toast("All chats deleted")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        col.addView(data)

        // Backup
        val backup = card("BACKUP")
        backup.addView(Button(this).apply {
            text = "Backup to file"
            isAllCaps = false
            textSize = 15f
            setTextColor(NovaTheme.text)
            background = null
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener {
                val name = "nova-backup-" + java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmm", Locale.US).format(java.util.Date()) + ".json"
                try {
                    val pick = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, name)
                    }
                    startActivityForResult(pick, 9001)
                } catch (e: Exception) { toast("No file picker available") }
            }
        })
        backup.addView(Button(this).apply {
            text = "Restore from file"
            isAllCaps = false
            textSize = 15f
            setTextColor(NovaTheme.text)
            background = null
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener {
                try {
                    val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    startActivityForResult(pick, 9002)
                } catch (e: Exception) { toast("No file picker available") }
            }
        })
        col.addView(backup)

        // About
        val about = card("ABOUT")
        about.addView(TextView(this).apply {
            text = "NOVA — your private AI.\nRuns 100% on this phone. Nothing leaves it."
            textSize = 13f; setTextColor(NovaTheme.dim)
            setPadding(0, dp(8), 0, dp(4))
        })
        col.addView(about)

        scroll.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uri = data.data ?: return
        if (requestCode == 9001) {
            // write the backup where the user chose
            try {
                val json = Backup.export(this)
                contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                }
                toast("Backup saved")
            } catch (e: Exception) { toast("Backup failed") }
        } else if (requestCode == 9002) {
            // restore from a chosen file
            try {
                val text = contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: ""
                if (!Backup.looksLikeBackup(text)) {
                    toast("That file is not a NOVA backup")
                    return
                }
                AlertDialog.Builder(this)
                    .setTitle("Restore backup?")
                    .setMessage("Chats with the same id are replaced, others are kept. " +
                        "Memory, personality and study deck are replaced.")
                    .setPositiveButton("Restore") { _, _ ->
                        val n = Backup.restore(this, text)
                        if (n >= 0) {
                            toast("Restored $n chats")
                            recreate()
                        } else toast("Restore failed")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } catch (e: Exception) { toast("Could not read that file") }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
