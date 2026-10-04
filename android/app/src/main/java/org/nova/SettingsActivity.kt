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
        val b = NovaUi.ghostButton(this, "Response length: ${settings.predictLength} tokens",
            NovaTheme.text) {}
        b.setOnClickListener {
            val opts = Settings.LENGTH_OPTIONS.map { "$it tokens" }.toTypedArray()
            val cur = Settings.LENGTH_OPTIONS.indexOf(settings.predictLength).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("Response length")
                .setSingleChoiceItems(opts, cur) { d, which ->
                    settings.predictLength = Settings.LENGTH_OPTIONS[which]
                    b.text = "Response length: ${settings.predictLength} tokens"
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
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
        col.addView(NovaUi.header(this, "Settings") { finish() })

        group(col, "Voice",
            NovaUi.switchRow(this, "Read replies aloud", settings.readAloud) { settings.readAloud = it },
            NovaUi.switchRow(this, "Auto-listen (conversation mode)", settings.autoListen) { settings.autoListen = it })

        // v9.22.0 "Busy replies": what NOVA says for you while you are busy.
        col.addView(NovaUi.sectionLabel(this, "Auto-reply"))
        val auto = NovaUi.card(this)
        auto.addView(NovaUi.switchRow(this, "Auto-reply when I'm busy", settings.autoReply) {
            settings.autoReply = it
        })
        auto.addView(NovaUi.small(this,
            "Up to " + settings.autoReplyMax + " different replies per person, " +
                "then it stops. Stay silent in quiet hours is set above.")
            .apply { setPadding(0, dp(8), 0, dp(4)) })
        auto.addView(NovaUi.ghostButton(this, "Replies per person: " + settings.autoReplyMax) {
            AlertDialog.Builder(this)
                .setTitle("Replies per person")
                .setItems(arrayOf("1", "2", "3", "4", "5", "6")) { _, which ->
                    settings.autoReplyMax = which + 1
                    recreate()
                }
                .show()
        })
        auto.addView(NovaUi.small(this, "Your busy replies (one per line):")
            .apply { setPadding(0, dp(8), 0, dp(4)) })
        auto.addView(field(settings.busyLines().joinToString("\n"),
            "tell me fast, I'm doing something") { settings.autoReplyLines = it })
        auto.addView(NovaUi.ghostButton(this, "Generate in my style") { generateLines() })
        col.addView(auto)

        col.addView(NovaUi.sectionLabel(this, "Memory"))
        val memory = NovaUi.card(this)
        memory.addView(NovaUi.small(this, "Facts NOVA always remembers").apply { setPadding(0, 0, 0, dp(8)) })
        memory.addView(field(settings.memory, "e.g. My exam is on 12 May") { settings.memory = it })
        col.addView(memory)

        col.addView(NovaUi.sectionLabel(this, "Personality"))
        val person = NovaUi.card(this)
        person.addView(NovaUi.small(this, "How NOVA should behave").apply { setPadding(0, 0, 0, dp(8)) })
        person.addView(field(settings.systemPrompt, "") { settings.systemPrompt = it })
        col.addView(person)

        col.addView(NovaUi.sectionLabel(this, "Answers"))
        val answers = NovaUi.card(this)
        answers.addView(NovaUi.switchRow(this, "Use offline Wikipedia", settings.wikiEnabled) { settings.wikiEnabled = it })
        answers.addView(NovaUi.switchRow(this, "Learn online (asks before fetching)", settings.onlineLearning) { settings.onlineLearning = it })
        answers.addView(NovaUi.switchRow(this, "Strict answers (notes & Wikipedia only)", settings.strictMode) {
            settings.strictMode = it
            // v5.5.0: answers cached under the other mode must not be served
            Knowledge.clearQaCache(this)
        })
        answers.addView(NovaUi.switchRow(this, "Adaptive context (skip sources that never help)", settings.adaptiveContext) { settings.adaptiveContext = it })
        answers.addView(NovaUi.switchRow(this, "Speculative decoding (Qwen3 only)", settings.specDecoding) {
            settings.specDecoding = it
            toast(if (it) "Needs a Qwen3 model + Qwen3 0.6B downloaded - active on next model load"
                  else "Off after the next model load")
        })
        answers.addView(lengthRow())
        col.addView(answers)

        col.addView(NovaUi.sectionLabel(this, "Privacy"))
        val privacy = NovaUi.card(this)
        privacy.addView(NovaUi.small(this, "Route online lookups through a SOCKS proxy (Orbot/Tor) so the sites NOVA reads never see this phone's IP. Turn Orbot on first.").apply { setPadding(0, 0, 0, dp(8)) })
        privacy.addView(NovaUi.switchRow(this, "Route fetches through a SOCKS proxy", settings.proxyEnabled) { settings.proxyEnabled = it })
        privacy.addView(field(settings.proxyHost + ":" + settings.proxyPort,
            "Proxy host:port (Orbot = 127.0.0.1:9050)") { v ->
            if (v.contains(':')) {
                val host = v.substringBeforeLast(':').trim()
                val port = v.substringAfterLast(':').trim().toIntOrNull()
                if (host.isNotEmpty()) settings.proxyHost = host
                if (port != null && port in 1..65535) settings.proxyPort = port
            }
        })
        col.addView(privacy)

        col.addView(NovaUi.sectionLabel(this, "Appearance"))
        val looks = NovaUi.card(this)
        looks.addView(NovaUi.switchRow(this, "Light theme", settings.theme == "light") {
            settings.theme = if (it) "light" else "dark"
            NovaTheme.apply(it)
            toast("Theme changes when you go back")
        })
        col.addView(looks)

        col.addView(NovaUi.sectionLabel(this, "Data"))
        val data = NovaUi.card(this)
        data.addView(NovaUi.dangerButton(this, "Delete all chats") {
            AlertDialog.Builder(this)
                .setTitle("Delete all chats?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    ChatStore.clearAll(this)
                    toast("All chats deleted")
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        col.addView(data)

        col.addView(NovaUi.sectionLabel(this, "Backup"))
        val backup = NovaUi.card(this)
        backup.addView(NovaUi.navRow(this, "Backup & restore",
            "Full encrypted zip of your local state") {
            startActivity(Intent(this, BackupActivity::class.java))
        })
        col.addView(backup)

        col.addView(NovaUi.sectionLabel(this, "About"))
        val about = NovaUi.card(this)
        about.addView(NovaUi.small(this, "NOVA - your private AI.\nRuns 100% on this phone. Nothing leaves it."))
        col.addView(about)

        scroll.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }


    /** v9.22.0 "Busy replies": rewrite the busy lines in the user's own style
     *  using the on-device model (only possible while the app is open). */
    private fun generateLines() {
        if (!NcieStyle.ready()) { toast("Load a model first"); return }
        if (NcieStyle.samples(this).isEmpty()) {
            toast("Chat with NOVA a bit more first, so it can learn how you write")
            return
        }
        toast("Writing your busy replies...")
        scope.launch {
            val lines = try { NcieStyle.generateBusyLines(this@SettingsActivity, 4) }
                        catch (e: Exception) { emptyList() }
            if (lines.isEmpty()) { toast("Couldn't write them - try again"); return@launch }
            settings.autoReplyLines = lines.joinToString("\n")
            toast("Done - " + lines.size + " replies saved")
            recreate()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
