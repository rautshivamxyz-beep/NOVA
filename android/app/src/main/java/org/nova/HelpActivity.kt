package org.nova

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * v9.21.0 "Easy": "What can you do" - every command in one browsable list,
 * grouped by what you are trying to do. Tap a row to copy the command, then
 * paste it into the chat. Discovery was half the reason features went unused.
 */
class HelpActivity : Activity() {

    private val sections: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Talk and study" to listOf(
            "explain photosynthesis" to "ask anything - it runs on this phone",
            "explain it in more detail" to "follow up on the last answer",
            "quiz me on federalism" to "quizzes you one question at a time",
            "quiz me" to "quizzes on the document you have open",
            "study" to "revise your weak areas",
            "my weak areas" to "what you keep getting wrong",
            "my flashcards" to "your saved cards",
            "start study" to "a 45-minute timer",
            "my exam is on 5 March" to "sets the exam countdown",
            "plan my revision" to "builds a revision plan"
        ),
        "Your documents" to listOf(
            "summarize this" to "with a PDF attached",
            "explain this pdf" to "reads the document you attached",
            "from my notes: what is federalism?" to "answers from your own documents",
            "gimme the notes of federalism" to "pastes your stored notes",
            "what documents do i have" to "lists your indexed documents"
        ),
        "Phone actions" to listOf(
            "call mom" to "places the call",
            "text mom I'll be late" to "sends the text",
            "text mom at 6pm: on my way" to "sends it later, automatically",
            "cancel scheduled texts" to "clears the pending sends",
            "open youtube" to "launches an app",
            "set an alarm for 6am" to "sets an alarm",
            "timer 45 minutes" to "sets a timer",
            "set volume 50" to "changes the volume"
        ),
        "Notifications" to listOf(
            "what did I miss" to "a summary of your notifications",
            "any messages from mom" to "just that person's messages",
            "reply to mom I'm coming" to "replies without opening the app",
            "auto reply on" to "NOVA answers for you while you are busy",
            "set auto reply I'm in class" to "the line it sends"
        ),
        "Automation" to listOf(
            "good morning" to "the daily briefing, read aloud",
            "find the tracking number" to "searches everything NOVA has seen",
            "away" to "auto-reply plus vibrate; \"i'm back\" undoes it",
            "set guardian contact mom" to "then \"guardian on 30\" and \"i'm ok\"",
            "quiet hours 22:00 07:00" to "NOVA stays silent in that window",
            "remind me every day at 7am to take my medicine" to "a daily reminder",
            "i promised mom I'd call" to "then \"what did i promise\"",
            "how much did i spend this month" to "totalled from your bank messages",
            "check for scams" to "flags suspicious messages",
            "my passport expires 2030-05-01" to "then \"expiry\"",
            "my packages" to "surfaces delivery messages"
        ),
        "Memory and housekeeping" to listOf(
            "remember my exam is on 12 May" to "NOVA always keeps this",
            "what do you know about me" to "what it remembers about you",
            "summarize our conversation" to "folds the chat so far",
            "forget our conversation" to "clears that"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Settings(this)
        NovaTheme.apply(s.theme == "light")
        window.statusBarColor = NovaTheme.bg
        window.navigationBarColor = NovaTheme.bg
        setContentView(build())
    }

    private fun dp(v: Int): Int = NovaUi.dp(this, v)

    private fun build(): View {
        val scroll = ScrollView(this)
        val col = NovaUi.column(this).apply {
            setBackgroundColor(NovaTheme.bg)
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        col.addView(NovaUi.header(this, "What can you do") { finish() })
        col.addView(NovaUi.small(this,
            "Tap a command to copy it, then paste it into the chat.")
            .apply { setPadding(dp(4), 0, dp(4), dp(4)) })

        for (section in sections) {
            col.addView(NovaUi.sectionLabel(this, section.first))
            val card = NovaUi.card(this)
            for (item in section.second) {
                val row = NovaUi.column(this)
                row.setPadding(0, dp(8), 0, dp(8))
                row.isClickable = true
                row.addView(NovaUi.body(this, item.first))
                row.addView(NovaUi.small(this, item.second))
                row.setOnClickListener { copy(item.first) }
                card.addView(row)
            }
            col.addView(card)
        }

        scroll.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun copy(s: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NOVA", s))
            Toast.makeText(this, "Copied: " + s, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { }
    }
}
