package org.nova

import org.nova.ncie.Tokens
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.net.Uri
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.animation.AlphaAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.nova.ncie.android.NcieArithmetic
import org.nova.ncie.android.NcieChat
import org.nova.ncie.android.NcieEngines
import org.nova.ncie.android.NcieGround
import org.nova.ncie.android.NcieEmbed
import org.nova.ncie.android.NcieKnowledge
import org.nova.ncie.android.NcieLearn
import org.nova.ncie.android.NcieSkills
import org.nova.ncie.android.NcieSummary
import org.nova.ncie.android.NcieTune
import org.nova.ncie.android.NcieValue
import org.nova.ncie.android.NovaEngineAdapter
import org.nova.ncie.verify.Coverage
import org.nova.ncie.android.ncieSend
import io.noties.markwon.Markwon
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.prism4j.Prism4j
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale


// v9.33.58 (improvement pass): extracted from MainActivity.kt.
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = mutableListOf<Msg>()
    private var markwon: Markwon? = null
    var onContinue: (() -> Unit)? = null
    var onTool: ((String) -> Unit)? = null
    var onRegenerate: (() -> Unit)? = null
    var onRunJs: ((String) -> Unit)? = null
    var onEditResend: ((Msg) -> Unit)? = null
    /** v9.33.20: remove one message from this chat. */
    var onDelete: ((Msg) -> Unit)? = null

    fun add(m: Msg) {
        items.add(m)
        notifyItemInserted(items.size - 1)
        // v9.0.0 "Voice": the single display point - every finished
        // assistant message shown in the chat (the deterministic eyes,
        // tutor and profile answers included) passes through here, so
        // this is where NOVA reads it aloud. Model replies stream into
        // their bubble (done=false at this point) and speak once at
        // their completion instead; history replays set suppressSpeak.
        if (m.role == Role.ASSISTANT && m.done && m.text.isNotBlank() && !suppressSpeak)
            NcieVoice.speak(m.text)
    }

    /** True while displayChatMessages replays stored history. */
    var suppressSpeak = false

    fun appendToLast(token: String) {
        if (items.isEmpty()) return
        items[items.size - 1].text += token
        notifyItemChanged(items.size - 1)
    }

    /** Replaces the text of the last bubble (live progress updates). */
    fun setLastText(t: String) {
        if (items.isEmpty()) return
        items[items.size - 1].text = t
        notifyItemChanged(items.size - 1)
    }

    fun finalizeLast() {
        if (items.isEmpty()) return
        val last = items[items.size - 1]
        last.done = true
        // permanently remove hidden thinking text - this is what gets
        // shown, copied, spoken and saved to the chat transcript
        last.text = Regex("(?s)^\\s*(?:NOVA|You)\\s*:\\s*")
            .replace(stripThinking(last.text).trim(), "")
        notifyItemChanged(items.size - 1)
    }

    fun clear() {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }

    fun lastMessage(): Msg? = items.lastOrNull()

    fun removeLast() {
        if (items.isEmpty()) return
        items.removeAt(items.size - 1)
        notifyItemRemoved(items.size)
    }

    fun notifyChanged(m: Msg) {
        val i = items.indexOf(m)
        if (i >= 0) notifyItemChanged(i)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        if (markwon == null) {
            ru.noties.jlatexmath.JLatexMathAndroid.init(ctx)
            val prism4j = Prism4j(NovaGrammarLocator)
            markwon = Markwon.builder(ctx)
                .usePlugin(SyntaxHighlightPlugin.create(prism4j, Prism4jThemeDefault.create()))
                // v9.29.1: inline math was never enabled, so the model's
                // single-dollar formulas ($x+1$) rendered as raw LaTeX. Turn
                // on the inline parser + inline LaTeX, and normalise single-$
                // math to the $$...$$ form the plugin actually parses.
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(JLatexMathPlugin.create(15.5f) { builder -> builder.inlinesEnabled(true) })
                .usePlugin(object : AbstractMarkwonPlugin() {
                    private val dollar = "${'$'}"
                    private val singleDollar =
                        Regex("(?<!" + dollar + ")" + dollar +
                            "([^" + dollar + "\\n]+?)" + dollar + "(?!" + dollar + ")")
                    override fun processMarkdown(markdown: String): String =
                        singleDollar.replace(markdown) { m ->
                            val inner = m.groupValues[1]
                            // only clear math (a command, superscript or
                            // subscript) - never a currency "$5"
                            if (inner.contains('\\') || inner.contains('^') || inner.contains('_'))
                                "\$\$" + inner + "\$\$"
                            else m.value
                        }
                })
                .build()
        }
        val avatar = TextView(ctx).apply {
            text = "✦"
            textSize = 14f
            setTextColor(NovaTheme.accent)
            setPadding(0, dp(ctx, 9), 0, 0)
        }
        val bubble = TextView(ctx).apply {
            textSize = 15.5f
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            setPadding(dp(ctx, 15), dp(ctx, 11), dp(ctx, 15), dp(ctx, 11))
        }
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 16), 0, 0, dp(ctx, 4))
        }
        fun actIcon(res: Int) = (androidx.core.content.ContextCompat.getDrawable(ctx, res)
            ?: android.graphics.drawable.ColorDrawable(NovaTheme.dim)).mutate().apply {
            colorFilter = android.graphics.PorterDuffColorFilter(
                NovaTheme.dim, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        val copyBtn = TextView(ctx).apply {
            background = rippleOverlay(null, dp(ctx, 14).toFloat())
            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 18), dp(ctx, 6))
            setCompoundDrawablesWithIntrinsicBounds(actIcon(R.drawable.ic_copy), null, null, null)
            contentDescription = "Copy this message"
        }
        val regenBtn = TextView(ctx).apply {
            background = rippleOverlay(null, dp(ctx, 14).toFloat())
            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
            setCompoundDrawablesWithIntrinsicBounds(actIcon(R.drawable.ic_refresh), null, null, null)
            contentDescription = "Regenerate the answer"
        }
        actions.addView(copyBtn)
        actions.addView(regenBtn)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(bubble, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(actions)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 14) }
        }
        row.addView(avatar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(ctx, 10) })
        row.addView(col, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return VH(row, avatar, bubble, actions, copyBtn, regenBtn)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.bubble.context
        val user = m.role == Role.USER
        holder.bubble.clearAnimation()

        if (user) {
            holder.avatar.visibility = View.GONE
            holder.actions.visibility = View.GONE
            (holder.bubble.layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
                weight = 0f
                gravity = Gravity.END
                leftMargin = dp(ctx, 48)
                rightMargin = 0
            }
            holder.bubble.background = GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(NovaTheme.accent, NovaTheme.accentDeep)).apply {
                val r = dp(ctx, 22).toFloat()
                val s = dp(ctx, 5).toFloat()
                setCornerRadii(floatArrayOf(r, r, r, r, s, s, r, r))
            }
            holder.bubble.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            holder.bubble.setTextColor(Color.WHITE)
        } else {
            holder.avatar.visibility = View.VISIBLE
            val showActions = m.done && stripThinking(m.text).isNotBlank()
            holder.actions.visibility = if (showActions) View.VISIBLE else View.GONE
            if (showActions) {
                holder.copyBtn.setOnClickListener {
                    copyToClipboard(ctx, plainText(stripThinking(m.text).trim()))
                }
                holder.regenBtn.setOnClickListener { onRegenerate?.invoke() }
            }
            (holder.bubble.layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                weight = 0f
                gravity = Gravity.START
                leftMargin = 0
                rightMargin = 0
            }
            // v8.0.0: replies live in cards now - surface + hairline
            // border instead of floating bare text
            holder.bubble.background = GradientDrawable().apply {
                setCornerRadius(dp(ctx, 18).toFloat())
                setColor(NovaTheme.surface)
                setStroke(dp(ctx, 1), NovaTheme.border)
            }
            holder.bubble.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            holder.bubble.setTextColor(NovaTheme.text)
        }

        if (!user && !m.done && stripThinking(m.text).isEmpty()) {
            // model is reasoning in a hidden thinking block, or not started
            holder.bubble.text = "•\u00A0\u00A0•\u00A0\u00A0•"
            val dots = AlphaAnimation(0.25f, 1f).apply {
                duration = 420
                repeatMode = AlphaAnimation.REVERSE
                repeatCount = AlphaAnimation.INFINITE
            }
            holder.bubble.startAnimation(dots)
            holder.bubble.setTextColor(NovaTheme.accent)
        } else if (!user && m.done && m.text.isNotBlank() && markwon != null) {
            markwon?.setMarkdown(holder.bubble, m.text)
        } else {
            holder.bubble.text = stripThinking(m.text)
        }

        holder.bubble.layoutParams = holder.bubble.layoutParams

        holder.bubble.setOnLongClickListener {
            val msgText = stripThinking(m.text).trim()
            if (msgText.isBlank()) return@setOnLongClickListener true
            // code inside fences, without the fence markers
            val code = CODE_BLOCK.findAll(msgText)
                .joinToString("\n\n") { it.groupValues[1].trim() }
            val options = mutableListOf<String>()
            if (user) options += "Edit & resend"
            if (code.isNotBlank()) options += "Copy code"
            options += "Copy"
            options += "Share"
            options += "Delete"
            val tools = if (user) linkedMapOf(
                "Fix grammar" to "Fix the grammar and spelling of the text between the lines. Reply with ONLY the corrected text, nothing else:\n-----\n$msgText\n-----",
                "Rewrite better" to "Rewrite the text between the lines to be clearer and better written. Keep the same meaning and the same language. Reply with ONLY the rewritten text:\n-----\n$msgText\n-----",
                "Translate to Hindi" to "Translate the text between the lines into Hindi. Reply with ONLY the translation:\n-----\n$msgText\n-----",
                "Make shorter" to "Rewrite the text between the lines much shorter while keeping the key facts. Reply with ONLY the shortened text:\n-----\n$msgText\n-----",
                "Make longer" to "Expand the text between the lines with more detail and examples. Reply with ONLY the expanded text:\n-----\n$msgText\n-----"
            ) else linkedMapOf(
                "Make study cards" to "Create 8 study flashcards from this material. Format each card EXACTLY as:\nQ: <question>\nA: <answer>\nNo numbering, no text before or after.",
                "Save to Knowledge" to "",
                "Make it sound like me" to "",
                "Regenerate" to ""
            ).apply {
                // v5.6.0: coding tools when the message contains a code block
                if (code.isNotBlank()) {
                    put("Explain code", "Explain this code step by step in simple language for a beginner. Say what each part does and why:\n-----\n$code\n-----")
                    put("Find bugs", "Check this code for bugs, mistakes or bad practices. Explain each issue and how to fix it. If it is correct, say it is correct:\n-----\n$code\n-----")
                    put("Run (JavaScript)", "")
                }
            }
            options += tools.keys
            AlertDialog.Builder(ctx)
                .setItems(options.toTypedArray()) { _, which ->
                    when (val chosen = options[which]) {
                        "Edit & resend" -> onEditResend?.invoke(m)
                        "Copy code" -> copyToClipboard(ctx, code)
                        "Run (JavaScript)" -> onRunJs?.invoke(code)
                        "Copy" -> copyToClipboard(ctx, plainText(msgText))
                        "Share" -> {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, msgText)
                            }
                            ctx.startActivity(Intent.createChooser(send, "Share message"))
                        }
                        "Save to Knowledge" -> {
                            val nm = "Saved: " + msgText.replace("\n", " ").take(28)
                            Thread { NcieKnowledge.addDoc(ctx, nm, msgText) }.start()
                            Toast.makeText(ctx, "Saved to Knowledge: $nm", Toast.LENGTH_SHORT).show()
                        }
                        "Regenerate" -> onRegenerate?.invoke()
                        "Delete" -> onDelete?.invoke(m)
                        "Make it sound like me" -> onTool?.invoke("__STYLE__" + msgText)
                        else -> tools[chosen]?.let { onTool?.invoke(it) }
                    }
                }
                .show()
            true
        }

        // tap the last finished reply to continue it
        if (m.role == Role.ASSISTANT && m.done && position == items.size - 1) {
            holder.bubble.setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setMessage("Continue this answer?")
                    .setPositiveButton("Continue") { _, _ -> onContinue?.invoke() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else {
            holder.bubble.setOnClickListener(null)
        }
    }

    class VH(row: LinearLayout, val avatar: TextView, val bubble: TextView,
             val actions: LinearLayout, val copyBtn: TextView, val regenBtn: TextView) :
        RecyclerView.ViewHolder(row)

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
