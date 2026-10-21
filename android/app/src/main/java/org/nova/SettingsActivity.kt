package org.nova

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.nova.ncie.android.NcieStyle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Full settings screen: voice, memory, personality, appearance and data.
 * v9.18.0 "Redesign": rebuilt on the NovaUi kit.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: Settings
    /** v9.22.0: a scope for the on-device style generation. */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        NovaTheme.apply(settings.theme == "light")
        window.statusBarColor = NovaTheme.bg
        window.navigationBarColor = NovaTheme.bg
        setContentView(build())
    }

    private fun dp(v: Int): Int = NovaUi.dp(this, v)

    private fun field(text: String, hint: String, onChange: (String) -> Unit): EditText {
        val f = EditText(this).apply {
            this.hint = hint
            setHintTextColor(NovaTheme.faint)
            setTextColor(NovaTheme.text)
            textSize = NovaTheme.T_BODY
            setSingleLine(false)
            minLines = 2
            maxLines = 6
            background = NovaUi.shape(this@SettingsActivity, NovaTheme.bg,
                NovaTheme.RADIUS_FIELD, 1, NovaTheme.border)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        // listener added AFTER setText so opening the screen never saves the
        // initial text over the user's (or the default) value
        f.setText(text)
        f.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
        return f
    }

    private fun group(col: LinearLayout, label: String, vararg rows: View) {
        col.addView(NovaUi.sectionLabel(this, label))
        val c = NovaUi.card(this)
        for (r in rows) c.addView(r)
        col.addView(c)
    }

    private fun lengthRow(): View {
        val b = NovaUi.ghostButton(this, getString(R.string.response_length_n, settings.predictLength),
            NovaTheme.text) {}
        b.setOnClickListener {
            val opts = Settings.LENGTH_OPTIONS.map { getString(R.string.tokens_n, it) }.toTypedArray()
            val cur = Settings.LENGTH_OPTIONS.indexOf(settings.predictLength).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(R.string.response_length_title)
                .setSingleChoiceItems(opts, cur) { d, which ->
                    settings.predictLength = Settings.LENGTH_OPTIONS[which]
                    b.text = getString(R.string.response_length_n, settings.predictLength)
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        return b
    }

    private fun build(): View {
        val scroll = ScrollView(this)
        val col = NovaUi.column(this).apply {
            setBackgroundColor(NovaTheme.bg)
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        col.addView(NovaUi.header(this, getString(R.string.settings_title)) { finish() })

        group(col, getString(R.string.section_voice),
            NovaUi.switchRow(this, getString(R.string.read_aloud), settings.readAloud) { settings.readAloud = it },
            NovaUi.switchRow(this, getString(R.string.auto_listen), settings.autoListen) { settings.autoListen = it })

        // v9.22.0 "Busy replies": what NOVA says for you while you are busy.
        col.addView(NovaUi.sectionLabel(this, getString(R.string.section_auto_reply)))
        val auto = NovaUi.card(this)
        auto.addView(NovaUi.switchRow(this, getString(R.string.auto_reply_busy), settings.autoReply) {
            settings.autoReply = it
        })
        auto.addView(NovaUi.small(this,
            getString(R.string.auto_reply_hint, settings.autoReplyMax))
            .apply { setPadding(0, dp(8), 0, dp(4)) })
        auto.addView(NovaUi.ghostButton(this, getString(R.string.replies_per_person_n, settings.autoReplyMax)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.replies_per_person)
                .setItems(arrayOf("1", "2", "3", "4", "5", "6")) { _, which ->
                    settings.autoReplyMax = which + 1
                    recreate()
                }
                .show()
        })
        auto.addView(NovaUi.small(this, getString(R.string.busy_replies_label))
            .apply { setPadding(0, dp(8), 0, dp(4)) })
        auto.addView(field(settings.busyLines().joinToString("\n"),
            getString(R.string.busy_replies_hint)) { settings.autoReplyLines = it })
        auto.addView(NovaUi.ghostButton(this, getString(R.string.generate_style)) { generateLines() })
        col.addView(auto)

        col.addView(NovaUi.sectionLabel(this, getString(R.string.section_memory)))
        val memory = NovaUi.card(this)
        memory.addView(NovaUi.small(this, getString(R.string.memory_label)).apply { setPadding(0, 0, 0, dp(8)) })
        memory.addView(field(settings.memory, getString(R.string.memory_hint)) { settings.memory = it })
        col.addView(memory)

        col.addView(NovaUi.sectionLabel(this, getString(R.string.section_personality)))
        val person = NovaUi.card(this)
        person.addView(NovaUi.small(this, getString(R.string.personality_label)).apply { setPadding(0, 0, 0, dp(8)) })
        person.addView(field(settings.systemPrompt, "") { settings.systemPrompt = it })
        col.addView(person)

        col.addView(NovaUi.sectionLabel(this, getString(R.string.section_answers)))
        val answers = NovaUi.card(this)
        answers.addView(NovaUi.switchRow(this, getString(R.string.use_wiki), settings.wikiEnabled) { settings.wikiEnabled = it })
        answers.addView(NovaUi.switchRow(this, getString(R.string.learn_online), settings.onlineLearning) { settings.onlineLearning = it })
        answers.addView(NovaUi.switchRow(this, getString(R.string.strict_answers), settings.strictMode) {
            settings.strictMode = it
            // v5.5.0: answers cached under the other mode must not be served
            Knowledge.clearQaCache(this)
        })
        answers.addView(NovaUi.switchRow(this, getString(R.string.adaptive_context), settings.adaptiveContext) { settings.adaptiveContext = it })
        answers.addView(NovaUi.switchRow(this, getString(R.string.spec_decoding), settings.specDecoding) {
            settings.specDecoding = it
            toast(if (it) getString(R.string.spec_on) else getString(R.string.spec_off))
        })
        answers.addView(lengthRow())
        col.addView(answers)

        col.addView(NovaUi.sectionLabel(this, getString(R.string.section_privacy)))
        val privacy = NovaUi.card(this)
        privacy.addView(NovaUi.small(this, getString(R.string.proxy_explainer)).apply { setPadding(0, 0, 0, dp(8)) })
        privacy.addView(NovaUi.switchRow(this, getString(R.string.proxy_switch), settings.proxyEnabled) { settings.proxyEnabled = it })
        privacy.addView(field(settings.proxyHost + ":" + settings.proxyPort,
            getString(R.string.proxy_hint)) { v ->
            if (v.contains(':')) {
                val host = v.substringBeforeLast(':').trim()
                val port = v.substringAfterLast(':').trim().toIntOrNull()
                if (host.isNotEmpty()) settings.proxyHost = host
                if (port != null && port in 1..65535) settings.proxyPort = port
            }
        })
        col.addView(privacy)

        col.addView(NovaUi.sectionLabel(this, getString(R.string.section_appearance)))
        val looks = NovaUi.card(this)
        looks.addView(NovaUi.switchRow(this, getString(R.string.light_theme), settings.theme == "light") {
            settings.theme = if (it) "light" else "dark"
            NovaTheme.apply(it)
            toast(getString(R.string.theme_toast))
        })
        col.addView(looks)

        col.addView(NovaUi.sectionLabel(this, getString(R.string.section_data)))
        val data = NovaUi.card(this)
        data.addView(NovaUi.dangerButton(this, getString(R.string.delete_all_chats)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_all_chats_q)
                .setMessage(R.string.cannot_undo)
                .setPositiveButton(R.string.delete) { _, _ ->
                    ChatStore.clearAll(this)
                    toast(getString(R.string.all_deleted))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
        col.addView(data)

        col.addView(NovaUi.sectionLabel(this, getString(R.string.section_backup)))
        val backup = NovaUi.card(this)
        backup.addView(NovaUi.navRow(this, getString(R.string.backup_restore),
            getString(R.string.backup_sub)) {
            startActivity(Intent(this, BackupActivity::class.java))
        })
        col.addView(backup)

        col.addView(NovaUi.sectionLabel(this, getString(R.string.section_about)))
        val about = NovaUi.card(this)
        about.addView(NovaUi.small(this, getString(R.string.about_text)))
        col.addView(about)

        scroll.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }


    /** v9.22.0 "Busy replies": rewrite the busy lines in the user's own style
     *  using the on-device model (only possible while the app is open). */
    private fun generateLines() {
        if (!NcieStyle.ready()) { toast(getString(R.string.load_model_first)); return }
        if (NcieStyle.samples(this).isEmpty()) {
            toast(getString(R.string.style_need_samples))
            return
        }
        toast(getString(R.string.writing_replies))
        scope.launch {
            val lines = try { NcieStyle.generateBusyLines(this@SettingsActivity, 4) }
                        catch (e: Exception) { emptyList() }
            if (lines.isEmpty()) { toast(getString(R.string.write_failed)); return@launch }
            settings.autoReplyLines = lines.joinToString("\n")
            toast(getString(R.string.replies_saved, lines.size))
            recreate()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
