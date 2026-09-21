package org.nova

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * NOVA — local AI chat (Aria-style).
 * Voice input, streaming read-aloud, markdown, copy/share, saved chats.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var busyDot: ProgressBar
    private lateinit var messagesRv: RecyclerView
    private lateinit var emptyView: View
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private val adapter = MessageAdapter()

    private lateinit var settings: Settings
    private lateinit var currentChat: Chat

    /** True when the displayed history is NOT in the engine's context (chat was resumed). */
    private var needsContextCarry = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var generationJob: Job? = null
    private var generating = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // streaming TTS: how much of the reply has been spoken already
    private var spokenLength = 0
    private var speechCancelled = false

    private val bg = Color.parseColor("#0A0D12")
    private val surface = Color.parseColor("#141926")
    private val accent = Color.parseColor("#5B9BFF")
    private val accentDeep = Color.parseColor("#2E6BE6")
    private val textMain = Color.parseColor("#EAF0FA")
    private val textDim = Color.parseColor("#8B94A7")
    private val stopColor = Color.parseColor("#FF6B6B")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        currentChat = if (settings.currentChatId.isNotBlank()) {
            ChatStore.load(this, settings.currentChatId) ?: ChatStore.newChat()
        } else ChatStore.newChat()
        settings.currentChatId = currentChat.id
        needsContextCarry = currentChat.messages.isNotEmpty()

        setContentView(buildUi())
        displayChatMessages()
        observeEngine()

        tts = TextToSpeech(this) { code ->
            ttsReady = code == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
    }

    override fun onResume() {
        super.onResume()
        restoreLastModel()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(0, dp(38), 0, dp(10))
        }

        // ---- Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(14), dp(10))
        }
        val brandCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(TextView(this).apply {
            text = "✦"
            textSize = 17f
            setTextColor(accent)
            setPadding(0, 0, dp(6), 0)
        })
        brandRow.addView(TextView(this).apply {
            text = "NOVA"
            textSize = 19f
            letterSpacing = 0.14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textMain)
        })
        status = TextView(this).apply {
            text = "starting…"
            textSize = 11.5f
            setTextColor(textDim)
            setPadding(dp(17), dp(2), 0, 0)
        }
        busyDot = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
            visibility = View.GONE
        }
        brandCol.addView(brandRow)
        brandCol.addView(status)
        header.addView(brandCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(busyDot, FrameLayout.LayoutParams(dp(18), dp(18)).apply {
            rightMargin = dp(10)
        })
        header.addView(roundButton("+", textDim).apply {
            setOnClickListener { newConversation() }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(7) })
        header.addView(roundButton("▤", textDim).apply {
            setOnClickListener {
                startActivityForResult(
                    Intent(this@MainActivity, ChatsActivity::class.java), REQ_CHATS)
            }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(7) })
        header.addView(roundButton("≡", textDim).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, ModelsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(7) })
        header.addView(roundButton("⚙", textDim).apply {
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#1A2030")) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))

        // ---- Messages
        messagesRv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = this@MainActivity.adapter
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        emptyView = TextView(this).apply {
            text = "✦\n\nYour private AI.\nRuns 100% on this phone.\n\nTap ≡ to download a model, then say hi.\nTap 🎤 to speak instead of typing."
            setTextColor(textDim)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(60), dp(48), dp(60))
        }
        root.addView(FrameLayout(this).apply {
            addView(emptyView)
            addView(messagesRv)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            private fun refresh() {
                emptyView.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            }
            override fun onChanged() = refresh()
            override fun onItemRangeInserted(p0: Int, p1: Int) = refresh()
            override fun onItemRangeRemoved(p0: Int, p1: Int) = refresh()
        })

        // ---- Input
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        micBtn = roundButton("🎤", textDim).apply {
            setOnClickListener { startSpeech() }
        }
        inputRow.addView(micBtn, FrameLayout.LayoutParams(dp(40), dp(40)).apply {
            rightMargin = dp(8)
        })
        input = EditText(this).apply {
            hint = "Message NOVA…"
            setHintTextColor(textDim)
            setTextColor(textMain)
            textSize = 15f
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#242C3C"))
            }
            setPadding(dp(18), dp(13), dp(18), dp(13))
            maxLines = 5
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sendBtn = Button(this).apply {
            text = "➤"
            textSize = 15f
            background = GradientDrawable().apply {
                setColor(accentDeep)
                cornerRadius = dp(23).toFloat()
            }
            setTextColor(Color.WHITE)
            setOnClickListener { send() }
        }
        inputRow.addView(sendBtn, FrameLayout.LayoutParams(dp(46), dp(46)).apply {
            leftMargin = dp(10)
        })
        root.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return root
    }

    // ------------------------------------------------------------- chats

    private fun displayChatMessages() {
        adapter.clear()
        for (m in currentChat.messages) adapter.add(m)
        if (currentChat.messages.isNotEmpty()) scrollToEnd()
    }

    private fun newConversation() {
        if (generationJob?.isActive == true) generationJob?.cancel()
        tts?.stop()
        if (NovaEngine.isModelLoaded) {
            NovaEngine.reloadAsync(this, settings.systemPrompt)
            needsContextCarry = false
        }
        currentChat = ChatStore.newChat()
        settings.currentChatId = currentChat.id
        adapter.clear()
        toast("New conversation")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CHATS && resultCode == Activity.RESULT_OK && data != null) {
            if (data.getBooleanExtra(ChatsActivity.EXTRA_NEW_CHAT, false)) {
                newConversation()
            } else {
                val id = data.getStringExtra(ChatsActivity.EXTRA_CHAT_ID)
                if (id != null && id != currentChat.id) openChat(id)
            }
        }
        if (requestCode == REQ_SPEECH) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val heard = results?.firstOrNull()
                if (!heard.isNullOrBlank()) {
                    if (heard.trim().endsWith(" send", ignoreCase = true)) {
                        input.setText(heard.trim().dropLast(4).trim())
                        send()
                    } else {
                        input.setText(heard)
                        input.setSelection(heard.length)
                    }
                }
            }
        }
    }

    private fun openChat(id: String) {
        if (generationJob?.isActive == true) generationJob?.cancel()
        tts?.stop()
        val chat = ChatStore.load(this, id) ?: return
        currentChat = chat
        settings.currentChatId = chat.id
        if (NovaEngine.isModelLoaded) NovaEngine.reloadAsync(this, settings.systemPrompt)
        needsContextCarry = chat.messages.isNotEmpty()
        displayChatMessages()
    }

    // ------------------------------------------------------------- models

    private fun restoreLastModel() {
        if (NovaEngine.isLoading) { setStatus(); return }
        if (NovaEngine.isModelLoaded) { setStatus(); return }
        val path = settings.lastModelPath
        if (path != null && File(path).exists()) {
            NovaEngine.loadAsync(
                this, path,
                settings.lastModelLabel.ifBlank { "model" },
                settings.systemPrompt
            )
        } else {
            setStatus()
        }
    }

    private fun observeEngine() {
        scope.launch {
            NovaEngine.loadState.collect { renderLoadState(it) }
        }
    }

    private fun renderLoadState(st: NovaEngine.LoadState) {
        when (st) {
            is NovaEngine.LoadState.Loading -> {
                status.text = "loading ${st.label}…"
                busyDot.visibility = View.VISIBLE
                input.isEnabled = false
                input.hint = "Loading model… (takes a while)"
            }
            NovaEngine.LoadState.Ready -> {
                NovaEngine.acknowledgeLoad()
                setStatus()
            }
            is NovaEngine.LoadState.Failed -> {
                NovaEngine.acknowledgeLoad()
                busyDot.visibility = View.GONE
                status.text = "✗ ${st.error}"
                input.isEnabled = false
                input.hint = "Model failed to load — try a smaller one (≡)"
                toast("Model failed: ${st.error}")
            }
            NovaEngine.LoadState.Idle -> setStatus()
        }
    }

    private fun setStatus() {
        val busy = generating || NovaEngine.isLoading
        busyDot.visibility = if (busy) View.VISIBLE else View.GONE
        val label = NovaEngine.activeModelLabel.ifBlank { settings.lastModelLabel }
        status.text = when {
            generating -> "generating…"
            label.isBlank() -> "no model — tap ≡"
            else -> label
        }
        val ready = NovaEngine.isModelLoaded && !NovaEngine.isLoading
        input.isEnabled = ready
        input.hint = if (ready) "Message NOVA…" else "Tap ≡ to load a model"
    }

    // -------------------------------------------------------------- chat

    private fun send() {
        if (generationJob?.isActive == true) {
            generationJob?.cancel()
            return
        }
        if (!NovaEngine.isModelLoaded) {
            Toast.makeText(this, "Load a model first — tap ≡", Toast.LENGTH_SHORT).show()
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        tts?.stop()
        spokenLength = 0
        speechCancelled = false

        val prompt: String = if (needsContextCarry && currentChat.messages.isNotEmpty()) {
            val recent = currentChat.messages.takeLast(8).joinToString("\n") { m ->
                (if (m.role == Role.USER) "You: " else "NOVA: ") + m.text.take(400)
            }
            "(Earlier conversation for context:\n$recent\n— end of earlier conversation)\n\nNew message: $text"
        } else text

        val userMsg = Msg(Role.USER, text)
        currentChat.messages.add(userMsg)
        adapter.add(userMsg)

        val replyMsg = Msg(Role.ASSISTANT, "", done = false)
        currentChat.messages.add(replyMsg)
        adapter.add(replyMsg)
        scrollToEnd()

        sendBtn.text = "■"
        sendBtn.setTextColor(stopColor)
        generating = true
        setStatus()

        generationJob = scope.launch {
            try {
                NovaEngine.send(prompt, settings.predictLength)
                    .collect { token ->
                        adapter.appendToLast(token)
                        scrollToEnd()
                        speakNewSentences(replyMsg.text, flush = false)
                    }
            } catch (e: CancellationException) {
                adapter.appendToLast(" ⏹")
                speechCancelled = true
            } catch (e: Exception) {
                adapter.appendToLast("\n[error: ${e.message}]")
            } finally {
                withContext(Dispatchers.Main) {
                    sendBtn.text = "➤"
                    sendBtn.setTextColor(Color.WHITE)
                    generating = false
                    setStatus()
                    adapter.finalizeLast()
                    needsContextCarry = false
                    // persist the conversation
                    withContext(Dispatchers.IO) { ChatStore.save(this@MainActivity, currentChat) }
                    // speak whatever is left of the reply
                    if (!speechCancelled) speakNewSentences(replyMsg.text, flush = true)
                }
            }
        }
    }

    /**
     * Speaks finished sentences as they stream in (queued), so the voice
     * keeps pace with the text instead of waiting for the whole reply.
     * Strips markdown so it reads naturally.
     */
    private fun speakNewSentences(full: String, flush: Boolean) {
        if (!settings.readAloud || !ttsReady || tts == null) return
        if (spokenLength >= full.length) return
        val pending = full.substring(spokenLength)

        var idx = -1
        for (d in charArrayOf('.', '!', '?', '\n', ';', ':')) {
            val i = pending.lastIndexOf(d)
            if (i > idx) idx = i
        }
        val chunk: String? = when {
            flush && pending.isNotBlank() -> pending
            idx >= 24 -> pending.substring(0, idx + 1)
            else -> null
        }
        if (chunk != null) {
            val clean = chunk
                .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")   // links -> text
                .replace(Regex("```[a-zA-Z0-9]*"), " code: ")            // code fences
                .replace(Regex("[*_`>#~|]+"), "")                       // emphasis etc.
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.isNotBlank()) {
                tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, "nova$spokenLength")
            }
            spokenLength += chunk.length
        }
    }

    private fun startSpeech() {
        if (generating || !NovaEngine.isModelLoaded) {
            toast("Load a model first — tap ≡")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to NOVA… (say \"send\" at the end to send)")
        }
        try {
            startActivityForResult(intent, REQ_SPEECH)
        } catch (e: android.content.ActivityNotFoundException) {
            toast("Speech input is not available on this phone")
        }
    }

    private fun scrollToEnd() {
        if (adapter.itemCount > 0) {
            messagesRv.smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    // ----------------------------------------------------------- settings

    private fun showSettings() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        outer.addView(TextView(this).apply {
            text = "System prompt (applies when the model is reloaded)"
            setTextColor(textDim)
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        })
        val promptEdit = EditText(this).apply {
            setText(settings.systemPrompt)
            setTextColor(textMain)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            setSingleLine(false)
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#242C3C"))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        outer.addView(promptEdit)
        outer.addView(TextView(this).apply {
            text = "Max response length"
            setTextColor(textDim)
            textSize = 12f
            setPadding(0, dp(16), 0, dp(6))
        })
        val lengthRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lengthBtns = Settings.LENGTH_OPTIONS.map { tokens -> roundButton("$tokens", textDim) }
        lengthBtns.forEachIndexed { i, b ->
            val tokens = Settings.LENGTH_OPTIONS[i]
            b.setOnClickListener {
                settings.predictLength = tokens
                lengthBtns.forEach { it.setTextColor(textDim) }
                b.setTextColor(accent)
            }
            if (tokens == settings.predictLength) b.setTextColor(accent)
            lengthRow.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })
        }
        outer.addView(lengthRow)

        val ttsBtn = roundButton(
            if (settings.readAloud) "🔊 Read replies aloud: ON" else "🔇 Read replies aloud: OFF",
            if (settings.readAloud) accent else textDim
        )
        ttsBtn.setOnClickListener {
            settings.readAloud = !settings.readAloud
            ttsBtn.text = if (settings.readAloud) "🔊 Read replies aloud: ON" else "🔇 Read replies aloud: OFF"
            ttsBtn.setTextColor(if (settings.readAloud) accent else textDim)
            if (!settings.readAloud) tts?.stop()
        }
        outer.addView(ttsBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        AlertDialog.Builder(this)
            .setTitle("NOVA settings")
            .setView(outer)
            .setPositiveButton("Save") { _, _ ->
                val newPrompt = promptEdit.text.toString()
                val changed = newPrompt != settings.systemPrompt
                settings.systemPrompt = newPrompt
                if (changed && NovaEngine.isModelLoaded) {
                    AlertDialog.Builder(this)
                        .setMessage("Apply the new system prompt now? This starts a new conversation.")
                        .setPositiveButton("Apply now") { _, _ -> newConversation() }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------- utils

    private fun roundButton(label: String, color: Int): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(color)
        background = GradientDrawable().apply {
            setColor(surface)
            setStroke(dp(1), Color.parseColor("#28314A"))
            cornerRadius = dp(17).toFloat()
        }
        setPadding(0, 0, 0, 0)
        minWidth = 0
        minimumWidth = 0
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_SPEECH = 4251
        private const val REQ_CHATS = 4252
    }
}

// ---------------------------------------------------------------- adapter

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = mutableListOf<Msg>()
    private var markwon: Markwon? = null

    fun add(m: Msg) {
        items.add(m)
        notifyItemInserted(items.size - 1)
    }

    fun appendToLast(token: String) {
        if (items.isEmpty()) return
        items[items.size - 1].text += token
        notifyItemChanged(items.size - 1)
    }

    fun finalizeLast() {
        if (items.isEmpty()) return
        items[items.size - 1].done = true
        notifyItemChanged(items.size - 1)
    }

    fun clear() {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        if (markwon == null) markwon = Markwon.create(ctx)
        val bubble = TextView(ctx).apply {
            textSize = 15.5f
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            setPadding(dp(ctx, 15), dp(ctx, 11), dp(ctx, 15), dp(ctx, 11))
        }
        val row = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }
        row.addView(bubble)
        return VH(row, bubble)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.bubble.context
        val user = m.role == Role.USER

        holder.bubble.background = GradientDrawable().apply {
            val r = dp(ctx, 20).toFloat()
            val s = dp(ctx, 5).toFloat()
            if (user) setCornerRadii(floatArrayOf(r, r, r, r, s, s, r, r))
            else setCornerRadii(floatArrayOf(r, r, r, r, r, r, s, s))
            setColor(if (user) Color.parseColor("#2E6BE6") else Color.parseColor("#171C26"))
            if (!user) setStroke(dp(ctx, 1), Color.parseColor("#232B3A"))
        }
        holder.bubble.setTextColor(if (user) Color.WHITE else Color.parseColor("#EAF0FA"))

        if (!user && !m.done && m.text.isEmpty()) {
            holder.bubble.text = "● ● ●"
            holder.bubble.setTextColor(Color.parseColor("#5B9BFF"))
        } else if (!user && m.done && m.text.isNotBlank() && markwon != null) {
            markwon?.setMarkdown(holder.bubble, m.text)
        } else {
            holder.bubble.text = m.text
        }

        val lp = holder.bubble.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.leftMargin = if (user) dp(ctx, 64) else 0
        lp.rightMargin = if (user) 0 else dp(ctx, 64)
        holder.bubble.layoutParams = lp

        holder.bubble.setOnLongClickListener {
            if (m.text.isBlank()) return@setOnLongClickListener true
            val options = arrayOf("📋  Copy", "↗  Share")
            AlertDialog.Builder(ctx)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("NOVA", m.text))
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, m.text)
                            }
                            ctx.startActivity(Intent.createChooser(send, "Share message"))
                        }
                    }
                }
                .show()
            true
        }
    }

    class VH(row: FrameLayout, val bubble: TextView) : RecyclerView.ViewHolder(row)

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
