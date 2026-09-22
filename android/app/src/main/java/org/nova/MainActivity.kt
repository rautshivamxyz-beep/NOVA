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
import android.net.Uri
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.animation.AlphaAnimation
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
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
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

    init {
        adapter.onContinue = { continueAnswer() }
        adapter.onEditResend = { showEditResend(it) }
        adapter.onTool = { runTool(it) }
        adapter.onRegenerate = { regenerateLast() }
    }

    private lateinit var settings: Settings
    private lateinit var currentChat: Chat

    /** True when the displayed history is NOT in the engine's context (chat was resumed). */
    private var needsContextCarry = false

    /** Last memory text injected into this engine context. */
    private var lastInjectedMemory: String? = null

    /** Guards runaway auto-continues. */
    private var autoContinueCount = 0

    /** Set while a flashcard-generating reply is running. */
    private var pendingCards = false

    /** Compressed summary of older turns (auto-compact). */
    private var compactSummary: String? = null
    private var compacting = false

    /** Message count at the last auto-compact - throttles re-compaction. */
    private var compactedAtCount = 0

    /** Attached document (PDF / text file) the user can ask about. */
    private var docName: String? = null
    private var docContext: String? = null
    private var docInjected = false
    private lateinit var docBanner: LinearLayout
    private lateinit var docLabel: TextView
    private lateinit var docBtn: Button

    /** Side drawer. */
    private lateinit var drawerPane: LinearLayout
    private lateinit var scrim: View
    private lateinit var drawerList: LinearLayout

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var generationJob: Job? = null
    private var generating = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // streaming TTS: how much of the reply has been spoken already
    private var spokenLength = 0
    private var speechCancelled = false

    /** True while the chat is scrolled to the bottom; see scrollToEnd(). */
    private var atBottom = true

    private var bg = Color.BLACK
    private var surface = Color.BLACK
    private var accent = Color.WHITE
    private var accentDeep = Color.BLUE
    private var textMain = Color.BLACK
    private var textDim = Color.GRAY
    private val stopColor = Color.parseColor("#FF6B6B")
    private var appliedTheme = ""

    /** Applies the current theme to this screen and the status bar. */
    private fun applyTheme() {
        NovaTheme.apply(settings.theme == "light")
        bg = NovaTheme.bg
        surface = NovaTheme.surface
        accent = NovaTheme.accent
        accentDeep = NovaTheme.accentDeep
        textMain = NovaTheme.text
        textDim = NovaTheme.dim
        window.statusBarColor = NovaTheme.bg
        window.navigationBarColor = NovaTheme.bg
        appliedTheme = settings.theme
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        applyTheme()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)

        currentChat = if (settings.currentChatId.isNotBlank()) {
            ChatStore.load(this, settings.currentChatId) ?: ChatStore.newChat()
        } else ChatStore.newChat()
        settings.currentChatId = currentChat.id
        needsContextCarry = currentChat.messages.isNotEmpty()

        if (WikiCore.isReady(this)) scope.launch(Dispatchers.IO) {
            WikiCore.warmUp(this@MainActivity)
        }
        installCrashReporter()
        setContentView(buildUi())
        displayChatMessages()
        observeEngine()
        handleSharedText()
        maybeShowCrashReport()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4253)
        }

        tts = TextToSpeech(this) { code ->
            ttsReady = code == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
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
        header.addView(roundButton("", textDim).apply {
            setCompoundDrawablesWithIntrinsicBounds(icon(R.drawable.ic_add, textDim), null, null, null)
            setOnClickListener { newConversation() }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(7) })
        header.addView(roundButton("", textDim).apply {
            setCompoundDrawablesWithIntrinsicBounds(icon(R.drawable.ic_menu, textDim), null, null, null)
            setOnClickListener { openDrawer() }
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(View(this).apply { setBackgroundColor(NovaTheme.divider) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))

        // ---- Messages
        messagesRv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = this@MainActivity.adapter
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        // Track whether the user is at the bottom of the chat. We only
        // auto-scroll during streaming when they're already there — this
        // prevents the up-down fighting between overlapping smooth scrolls.
        messagesRv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                atBottom = !rv.canScrollVertically(1)
            }
        })
        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(30), dp(36), dp(20))
            addView(TextView(this@MainActivity).apply {
                text = "✦"
                textSize = 34f
                setTextColor(accent)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = "How can I help you today?"
                textSize = 20f
                setTextColor(textMain)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Your private AI. Runs 100% on this phone."
                textSize = 12f
                setTextColor(textDim)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(20))
            })
            val suggestions = listOf(
                "Explain something to me" to R.drawable.ic_lightbulb,
                "Translate to Hindi" to R.drawable.ic_globe,
                "Help me write code" to R.drawable.ic_edit,
                "Summarize a topic" to R.drawable.ic_doc
            )
            for ((s, ico) in suggestions) {
                addView(Button(this@MainActivity).apply {
                    text = s
                    isAllCaps = false
                    compoundDrawablePadding = dp(10)
                    setCompoundDrawablesWithIntrinsicBounds(icon(ico, NovaTheme.dim), null, null, null)
                    textSize = 14f
                    setTextColor(textMain)
                    setPadding(dp(18), 0, dp(18), 0)
                    minWidth = 0
                    minimumWidth = 0
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(NovaTheme.pill)
                        cornerRadius = dp(22).toFloat()
                        setStroke(dp(1), NovaTheme.border)
                    }
                    setOnClickListener {
                        input.setText(
                            when {
                                s.contains("Explain") -> "Explain in simple words: "
                                s.contains("Translate") -> "Translate to Hindi: "
                                s.contains("code") -> "Write me code for: "
                                else -> "Summarize this in 3 points: "
                            })
                        input.setSelection(input.text.length)
                    }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                ).apply {
                    topMargin = dp(10)
                })
            }
        }
        root.addView(FrameLayout(this).apply {
            addView(messagesRv)
            // emptyView ON TOP: an empty RecyclerView still eats touches,
            // which made the welcome cards impossible to tap
            addView(emptyView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- Attached document banner (PDF / text loaded for questions)
        docBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(2))
            visibility = View.GONE
        }
        docLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(accent)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        docBanner.addView(docLabel, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val docClear = Button(this).apply {
            isAllCaps = false
            setCompoundDrawablesWithIntrinsicBounds(icon(R.drawable.ic_close, textDim), null, null, null)
            background = null
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                docName = null; docContext = null; docInjected = false
                updateDocBanner()
                toast("Document removed")
            }
        }
        docBanner.addView(docClear, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(docBanner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            private fun refresh() {
                emptyView.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            }
            override fun onChanged() = refresh()
            override fun onItemRangeInserted(p0: Int, p1: Int) = refresh()
            override fun onItemRangeRemoved(p0: Int, p1: Int) = refresh()
        })

        // ---- Input (ChatGPT-style pill)
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(NovaTheme.pill)
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        micBtn = roundButton("", textDim).apply {
            val icon = getDrawable(R.drawable.ic_mic)!!.mutate()
            icon.colorFilter = android.graphics.PorterDuffColorFilter(
                textDim, android.graphics.PorterDuff.Mode.SRC_IN)
            gravity = Gravity.CENTER
            background = null
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            setOnClickListener { startSpeech() }
        }
        docBtn = roundButton("", textDim).apply {
            setCompoundDrawablesWithIntrinsicBounds(icon(R.drawable.ic_attach, textDim), null, null, null)
            setOnClickListener { openDocPicker() }
        }
        pill.addView(docBtn, LinearLayout.LayoutParams(dp(38), dp(38)))
        pill.addView(micBtn, LinearLayout.LayoutParams(dp(38), dp(38)))
        input = EditText(this).apply {
            hint = "Message NOVA…"
            setHintTextColor(textDim)
            setTextColor(textMain)
            textSize = 15f
            background = null
            setPadding(dp(10), dp(12), dp(10), dp(12))
            maxLines = 5
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { updateSendLook() }
            })
        }
        pill.addView(input, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sendBtn = Button(this).apply {
            text = ""
            textSize = 18f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
            background = GradientDrawable().apply {
                setColor(accentDeep)
                cornerRadius = dp(20).toFloat()
            }
            setOnClickListener { send() }
        }
        pill.addView(sendBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        inputRow.addView(pill, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        updateSendLook()

        // ---- Side drawer (ChatGPT style)
        val frame = FrameLayout(this)
        scrim = View(this).apply {
            setBackgroundColor(NovaTheme.scrim)
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        frame.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        drawerPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NovaTheme.pill)
            setPadding(dp(20), dp(44), dp(16), dp(20))
            visibility = View.GONE
        }
        drawerPane.addView(TextView(this).apply {
            text = "✦"
            textSize = 24f
            setTextColor(NovaTheme.accent)
        })
        drawerPane.addView(TextView(this).apply {
            text = "NOVA"
            textSize = 22f
            letterSpacing = 0.14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NovaTheme.text)
            setPadding(0, dp(2), 0, dp(4))
        })
        val modelLabel = settings.lastModelLabel.ifBlank { "Download a model" }
        val modelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(12))
            setOnClickListener {
                closeDrawer()
                startActivity(Intent(this@MainActivity, ModelsActivity::class.java))
            }
        }
        modelRow.addView(TextView(this).apply {
            text = modelLabel
            textSize = 13f
            setTextColor(NovaTheme.dim)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        modelRow.addView(TextView(this).apply {
            text = "›"; textSize = 16f; setTextColor(NovaTheme.dim)
        })
        drawerPane.addView(modelRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        drawerPane.addView(View(this).apply { setBackgroundColor(NovaTheme.border) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        drawerPane.addView(drawerRow("New chat", R.drawable.ic_add) { newConversation() })
        drawerPane.addView(drawerRow("Knowledge", R.drawable.ic_doc) {
            startActivity(Intent(this, KnowledgeActivity::class.java))
        })
        drawerPane.addView(drawerRow("Study", R.drawable.ic_edit) { Study.review(this) })
        drawerPane.addView(drawerRow("All chats", R.drawable.ic_chat) {
            startActivityForResult(Intent(this@MainActivity, ChatsActivity::class.java), REQ_CHATS)
        })
        drawerPane.addView(drawerRow("Settings", R.drawable.ic_settings) { showSettings() })
        drawerPane.addView(View(this).apply { setBackgroundColor(NovaTheme.border) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        drawerPane.addView(TextView(this).apply {
            text = "RECENT"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(NovaTheme.dim)
            setPadding(dp(4), dp(14), 0, dp(6))
        })
        drawerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drawerPane.addView(drawerList, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        frame.addView(drawerPane, FrameLayout.LayoutParams(dp(292), FrameLayout.LayoutParams.MATCH_PARENT))
        return frame
    }

    // ------------------------------------------------------------- chats

    private fun displayChatMessages() {
        adapter.clear()
        for (m in currentChat.messages) adapter.add(m)
        if (currentChat.messages.isNotEmpty()) scrollToEnd()
        updateDocBanner()
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
        docName = null; docContext = null; docInjected = false
        compactSummary = null; compactedAtCount = 0
        updateDocBanner()
        toast("New conversation")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7700 && resultCode == RESULT_OK) {
            data?.data?.let { loadSharedDocument(it) }
            return
        }
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
                    val said = heard.trim()
                    val sendNow = settings.autoListen || said.endsWith(" send", ignoreCase = true)
                    if (sendNow) {
                        input.setText(
                            if (said.endsWith(" send", ignoreCase = true)) said.dropLast(4).trim()
                            else said)
                        send()
                    } else {
                        input.setText(heard)
                        input.setSelection(heard.length)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText()
    }

    private fun openChat(id: String) {
        if (generationJob?.isActive == true) generationJob?.cancel()
        tts?.stop()
        val chat = ChatStore.load(this, id) ?: return
        currentChat = chat
        settings.currentChatId = chat.id
        if (NovaEngine.isModelLoaded) NovaEngine.reloadAsync(this, settings.systemPrompt)
        needsContextCarry = chat.messages.isNotEmpty()
        compactSummary = null; compactedAtCount = 0
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

    /**
     * True when the model is ready. If it died (e.g. after a stopped reply)
     * it silently restarts it instead of nagging the user.
     */
    private fun ensureModelReady(): Boolean {
        if (NovaEngine.isModelLoaded) return true
        return when {
            NovaEngine.isLoading -> {
                toast("Model is still loading — one moment"); false
            }
            NovaEngine.activeModelPath != null -> {
                toast("Restarting the model — try again shortly")
                NovaEngine.reloadAsync(this, settings.systemPrompt); false
            }
            else -> {
                toast("Load a model first — open the menu"); false
            }
        }
    }

    private fun send() {
        if (generationJob?.isActive == true) {
            generationJob?.cancel()
            return
        }
        if (!ensureModelReady()) return
        if (compacting) {
            toast("Compressing older messages — one moment")
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        maybeAutoRemember(text)
        maybeSetReminder(text)

        val docPart = if (docContext != null && !docInjected) {
            docInjected = true
            "(The user shared a document titled \"$docName\". Its content is between the lines.\n-----\n${docContext!!.take(6000)}\n-----\nEnd of document.)\n\n"
        } else ""
        val basePrompt: String = docPart + when {
            needsContextCarry && compactSummary != null && currentChat.messages.isNotEmpty() -> {
                val recent = currentChat.messages.takeLast(6).joinToString("\n") { m ->
                    (if (m.role == Role.USER) "You: " else "NOVA: ") + m.text.take(250)
                }
                "(Summary of earlier conversation: $compactSummary)\n\n(Recent messages:\n$recent\n— end)\n\nNew message: $text"
            }
            needsContextCarry && currentChat.messages.isNotEmpty() -> {
                val recent = currentChat.messages.takeLast(6).joinToString("\n") { m ->
                    (if (m.role == Role.USER) "You: " else "NOVA: ") + m.text.take(250)
                }
                "(Earlier conversation for context:\n$recent\n— end of earlier conversation)\n\nNew message: $text"
            }
            else -> text
        }

        var prompt = basePrompt
        // Memory rides along in the engine's context, so it only needs to be
        // injected once per conversation (or when its text changes).
        val mem = settings.memory.trim().take(500)
        if (mem.isNotEmpty() && (
                    currentChat.messages.isEmpty() || needsContextCarry || mem != lastInjectedMemory
                    )) {
            prompt = "(Facts about the user, always remember: $mem)\n\n$basePrompt"
            lastInjectedMemory = mem
        }
        // knowledge base (offline RAG): relevant notes from the user's documents
        if (settings.knowledgeEnabled && Knowledge.hasDocs(this)) {
            val hits = Knowledge.search(this, text)
            if (hits.isNotEmpty()) {
                var notes = hits.joinToString("\n---\n") { "[${it.doc}] ${it.text}" }
                if (notes.length > 2400) notes = notes.substring(0, 2400) + "\n[...more omitted]"
                prompt = "(Relevant notes from the user's documents — use them if they help:\n$notes)\n\n$prompt"
            }
        }
        // offline Wikipedia: matching articles as background facts
        if (WikiCore.isReady(this)) {
            val wikiHits = WikiCore.search(this, text)
            if (wikiHits.isNotEmpty()) {
                val facts = wikiHits.joinToString("\n---\n") { "${it.title}: ${it.text}" }
                prompt = "(Wikipedia background - use if relevant, ignore otherwise:\n$facts)\n\n$prompt"
            }
        }
        autoContinueCount = 0
        startGeneration(prompt, text)
    }

    /**
     * Runs one generation turn. userText == null for internal prompts
     * (chips, auto-continue, edit-resend) - no user bubble is shown.
     * newBubble == false keeps appending to the existing last reply.
     */
    private fun startGeneration(prompt: String, userText: String?, newBubble: Boolean = true) {
        if (userText != null) {
            val userMsg = Msg(Role.USER, userText)
            currentChat.messages.add(userMsg)
            adapter.add(userMsg)
        }
        val replyMsg: Msg
        if (newBubble) {
            replyMsg = Msg(Role.ASSISTANT, "", done = false)
            currentChat.messages.add(replyMsg)
            adapter.add(replyMsg)
        } else {
            replyMsg = currentChat.messages.last()
            replyMsg.done = false
        }
        val junction = replyMsg.text.length   // where a continuation begins
        tts?.stop()
        speechCancelled = false
        spokenLength = replyMsg.text.length   // speak only the new part
        scrollToEnd()

        sendBtn.setCompoundDrawablesWithIntrinsicBounds(
            icon(R.drawable.ic_stop, stopColor), null, null, null)
        generating = true
        setStatus()

        generationJob = scope.launch {
            // efficiency: batch tokens, redraw + speak ~8x per second
            val pending = StringBuilder()
            var lastFlush = 0L
            fun flush() {
                if (pending.isNotEmpty()) {
                    adapter.appendToLast(pending.toString())
                    pending.setLength(0)
                }
            }
            try {
                NovaEngine.send(prompt, settings.predictLength)
                    .collect { token ->
                        pending.append(token)
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastFlush >= 120 || pending.length > 400) {
                            lastFlush = now
                            flush()
                            scrollToEnd(force = false)
                            // thinking models (Qwen3 / LFM): hidden reasoning is
                            // running while nothing is visible yet
                            status.text = if (stripThinking(replyMsg.text).isEmpty())
                                "thinking…" else "generating…"
                            speakNewSentences(stripThinking(replyMsg.text), flush = false)
                        }
                    }
            } catch (e: CancellationException) {
                flush()
                adapter.appendToLast(" ⏹")
                speechCancelled = true
            } catch (e: Exception) {
                adapter.appendToLast("\n[error: ${e.message}]")
            } finally {
                flush()
                withContext(Dispatchers.Main) {
                    generating = false
                    updateSendLook()
                    setStatus()
                    // drop the duplicated tail the model often repeats when a
                    // cut-off reply is auto-continued
                    if (!newBubble && junction < replyMsg.text.length) {
                        replyMsg.text = stripRepeatJoin(
                            replyMsg.text.substring(0, junction),
                            replyMsg.text.substring(junction))
                    }
                    // after a stopped reply, the next answer often starts by
                    // repeating the stopped line - drop that echo
                    if (newBubble) {
                        val prev = currentChat.messages.getOrNull(currentChat.messages.size - 2)
                        if (prev != null && prev.role == Role.ASSISTANT &&
                            prev.text.trimEnd().endsWith("⏹")) {
                            replyMsg.text = stripRepeatStart(replyMsg.text, prev.text)
                        }
                    }
                    if (pendingCards) {
                        pendingCards = false
                        val n = Study.parseAndAdd(this@MainActivity, replyMsg.text)
                        toast(if (n > 0) "Saved $n cards - open Study in the menu" else "No cards found")
                    }
                    adapter.finalizeLast()
                    needsContextCarry = false
                    // show chips the moment the reply ends - before anything
                    // that could fail (storage, voice) gets a chance to skip it
                    val willContinue = newBubble && !speechCancelled &&
                        autoContinueCount < 2 &&
                        shouldAutoContinue(replyMsg.text)
                    // persist the conversation
                    try {
                        withContext(Dispatchers.IO) { ChatStore.save(this@MainActivity, currentChat) }
                    } catch (e: Exception) { }
                    // speak whatever is left of the reply
                    if (!speechCancelled) {
                        try { speakNewSentences(stripThinking(replyMsg.text), flush = true) }
                        catch (e: Exception) { }
                    }
                    // conversation mode: listen again once the voice finishes
                    if (settings.autoListen) scope.launch {
                        var waited = 0
                        while (tts?.isSpeaking == true && waited < 600) {
                            delay(200)
                            waited++
                        }
                        if (settings.autoListen && !generating) startSpeech()
                    }
                    // auto-continue: if the reply was cut off at the token
                    // limit, continue it in the same bubble
                    if (willContinue) {
                        autoContinueCount++
                        startGeneration(
                            "Continue your previous answer exactly where it stopped. Do not repeat anything.",
                            null, newBubble = false)
                    } else {
                        // auto-compact: compress old turns once the chat grows
                        if (!speechCancelled && !compacting &&
                            currentChat.messages.size > 20 &&
                            currentChat.messages.size - compactedAtCount >= 8
                        ) {
                            compactOldTurns()
                        }
                    }
                }
            }
        }
    }

    /** True when a reply looks cut off mid-sentence at the token limit. */
    private fun shouldAutoContinue(text: String): Boolean {
        val t = stripThinking(text).trim()
        if (t.length < settings.predictLength * 3) return false
        val last = t.lastOrNull() ?: return false
        return last !in ".!?\u2026\"'`)]}*"
    }

    /** Quick-action chips under a finished reply. */
    private fun updateDocBanner() {
        val has = docContext != null
        docBanner.visibility = if (has) View.VISIBLE else View.GONE
        if (has) docLabel.text = "$docName • ${docContext!!.length} chars"
    }

    /** Send button: dim when there is nothing to type, bright blue when ready. */
    private fun updateSendLook() {
        if (generating) return
        if (input.text.isNotBlank()) {
            sendBtn.background = GradientDrawable().apply {
                setColor(accentDeep); cornerRadius = dp(20).toFloat()
            }
            sendBtn.setCompoundDrawablesWithIntrinsicBounds(
                icon(R.drawable.ic_send, Color.WHITE), null, null, null)
        } else {
            sendBtn.background = GradientDrawable().apply {
                setColor(NovaTheme.sendDim); cornerRadius = dp(20).toFloat()
            }
            sendBtn.setCompoundDrawablesWithIntrinsicBounds(
                icon(R.drawable.ic_send, NovaTheme.sendDimText), null, null, null)
        }
    }

    /** Runs a hidden-prompt tool action (no duplicate user bubble). */
    private fun runTool(prompt: String) {
        if (compacting) { toast("Compressing older messages — one moment"); return }
        if (generating) { toast("Wait for the current reply to finish"); return }
        if (!ensureModelReady()) return
        pendingCards = prompt.startsWith("Create 8 study flashcards")
        startGeneration(prompt, null)
    }

    /** Long-press a reply -> answer the last question again. */
    private fun regenerateLast() {
        if (compacting) { toast("Compressing older messages — one moment"); return }
        if (generating) { toast("Wait for the current reply to finish"); return }
        if (!ensureModelReady()) return
        val msgs = currentChat.messages
        if (msgs.lastOrNull()?.role == Role.ASSISTANT) {
            currentChat.messages.removeAt(msgs.size - 1)
            adapter.removeLast()
        }
        val lastUser = currentChat.messages.lastOrNull { it.role == Role.USER }
        if (lastUser == null) { toast("Nothing to regenerate"); return }
        startGeneration(lastUser.text, null)
    }

    /** Loads a shared or picked file (PDF / plain text) as document context. */
    private fun loadSharedDocument(uri: Uri) {
        val name = try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst())
                    c.getString(c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME))
                else null
            }
        } catch (e: Exception) { null } ?: "document"
        toast("Reading $name…")
        scope.launch {
            val isPdf = name.endsWith(".pdf", true) ||
                contentResolver.getType(uri)?.contains("pdf", true) == true
            val text = withContext(Dispatchers.IO) {
                try {
                    if (isPdf) PdfDoc.extractText(this@MainActivity, uri)
                    else readPlainDocument(uri)
                } catch (e: Exception) { "" }
            }
            if (text.isBlank() || text.trim().length < 40) {
                toast("NOVA can't read images — it reads PDF and text files")
                return@launch
            }
            attachDocument(name, text.trim())
        }
    }

    /**
     * Reads a plain-text document. Returns "" for images and other binary
     * files (JPEG/PNG magic bytes, or NUL bytes in the head) so they never
     * reach the model as garbage. Caps length like PDFs.
     */
    private fun readPlainDocument(uri: Uri): String {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        } catch (e: Exception) { return "" }
        if (bytes.size < 4) return ""
        val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val head = bytes.copyOfRange(0, minOf(4096, bytes.size))
        val hasNul = head.contains(0.toByte())
        if (isJpeg || isPng || hasNul) return ""
        val text = String(bytes, Charsets.UTF_8)
        return if (text.length > 60_000)
            text.substring(0, 60_000) + "\n[...document truncated]"
        else text
    }

    private fun attachDocument(name: String, text: String) {
        docName = name
        docContext = text
        docInjected = false
        updateDocBanner()
        val opts = arrayOf("Summarize it", "Key points", "Quiz me", "I'll ask questions")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("${text.length} characters loaded. What should NOVA do with it?")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> runTool("Summarize this document in a few short paragraphs.")
                    1 -> runTool("List the key points of this document as short bullet points.")
                    2 -> runTool("Create a quiz of 10 short questions from this material. Number them 1-10, cover the whole material, and write the correct answer in brackets right after each question.")
                    3 -> toast("Ask anything about $name — then tap ↑")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openDocPicker() {
        try {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/plain"))
            }
            startActivityForResult(pick, 7700)
        } catch (e: Exception) {
            toast("No file picker available")
        }
    }

    // ---------- side drawer ----------

    private fun drawerRow(label: String, iconRes: Int, onClick: () -> Unit): View =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(NovaTheme.text)
            background = null
            setPadding(dp(4), dp(12), dp(4), dp(12))
            compoundDrawablePadding = dp(14)
            if (iconRes != 0)
                setCompoundDrawablesWithIntrinsicBounds(icon(iconRes, NovaTheme.dim), null, null, null)
            setOnClickListener { closeDrawer(); onClick() }
        }

    private fun openDrawer() {
        refreshDrawer()
        drawerPane.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()
        drawerPane.translationX = -dp(292).toFloat()
        drawerPane.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        if (scrim.visibility != View.VISIBLE) return
        scrim.animate().alpha(0f).setDuration(180)
            .withEndAction { scrim.visibility = View.GONE }.start()
        drawerPane.animate().translationX(-drawerPane.width.toFloat()).setDuration(200)
            .withEndAction { drawerPane.visibility = View.GONE }.start()
    }

    private fun refreshDrawer() {
        drawerList.removeAllViews()
        val byTime = ChatStore.list(this).asReversed()
        for (chat in byTime.take(12)) {
            val first = chat.messages.firstOrNull { it.role == Role.USER }?.text ?: "Chat"
            val title = if (first.length > 38) first.take(38) + "…" else first
            drawerList.addView(TextView(this).apply {
                text = title
                textSize = 14f
                maxLines = 1
                setTextColor(NovaTheme.text)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { openChatFromDrawer(chat.id) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        if (byTime.isEmpty()) {
            drawerList.addView(TextView(this).apply {
                text = "No chats yet"
                textSize = 13f
                setTextColor(NovaTheme.dim)
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
        }
    }

    private fun openChatFromDrawer(id: String) {
        closeDrawer()
        val chat = ChatStore.load(this, id) ?: return
        if (generationJob?.isActive == true) generationJob?.cancel()
        tts?.stop()
        currentChat = chat
        settings.currentChatId = chat.id
        needsContextCarry = chat.messages.isNotEmpty()
        compactSummary = null; compactedAtCount = 0
        docName = null; docContext = null; docInjected = false
        if (NovaEngine.isModelLoaded) NovaEngine.reloadAsync(this, settings.systemPrompt)
        displayChatMessages()
    }

    override fun onResume() {
        super.onResume()
        if (appliedTheme.isNotEmpty() && settings.theme != appliedTheme) {
            recreate()
            return
        }
        restoreLastModel()
    }

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
        if (generating) return
        if (!ensureModelReady()) return
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

    // ------------------------------------------------------- share-in

    /** Handles text shared from other apps (Share -> NOVA). */
    private fun handleSharedText() {
        val sendIntent = intent?.takeIf { it.action == Intent.ACTION_SEND } ?: return
        val shared = sendIntent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (shared.isNullOrEmpty()) {
            // no text - maybe a file (PDF / txt) was shared to NOVA
            @Suppress("DEPRECATION")
            val stream = sendIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (stream != null) loadSharedDocument(stream)
            return
        }
        if (generating) return
        val preview = if (shared.length > 280) shared.take(280) + "…" else shared
        val opts = arrayOf(
            "Explain this",
            "Translate to English",
            "Summarize",
            "Use as my message"
        )
        AlertDialog.Builder(this)
            .setTitle("Shared with NOVA")
            .setMessage(preview)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> sendShared("Explain the following text in simple words:\n\n$shared")
                    1 -> sendShared("Translate the following text to English. Reply with only the translation:\n\n$shared")
                    2 -> sendShared("Summarize the following text in 3 short bullet points:\n\n$shared")
                    3 -> { input.setText(shared); input.setSelection(shared.length) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendShared(prompt: String) {
        input.setText(prompt)
        send()
    }

    /** Tap-continue on the last reply. */
    private fun continueAnswer() {
        if (compacting) { toast("Compressing older messages — one moment"); return }
        if (generating || !ensureModelReady()) return
        startGeneration(
            "Continue your previous answer exactly where it stopped. Do not repeat anything.",
            null, newBubble = false)
    }

    /** Detects "remember that ..." and offers to save it to Memory. */
    private fun maybeAutoRemember(text: String) {
        val m = Regex("(?i)^\\s*(?:please\\s+)?remember\\b[\\s:,]+(.{4,400})").find(text) ?: return
        var fact = m.groupValues[1].trim().trimEnd('.', '!', '?')
        fact = fact.removePrefix("that ").removePrefix("That ")
        if (fact.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Add to NOVA's memory?")
            .setMessage(fact)
            .setPositiveButton("Add") { _, _ ->
                settings.memory = if (settings.memory.isBlank()) fact
                else settings.memory.trimEnd() + "\n- " + fact
                toast("Added to memory")
            }
            .setNegativeButton("No", null)
            .show()
    }

    /** Long-press own message -> edit & resend. */
    private fun showEditResend(m: Msg) {
        if (generating || !NovaEngine.isModelLoaded) {
            toast("Wait for the current reply to finish")
            return
        }
        val edit = EditText(this).apply {
            setText(m.text)
            setTextColor(textMain)
            textSize = 14f
            setSingleLine(false)
            minLines = 2
            maxLines = 6
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("Edit & resend")
            .setView(edit)
            .setPositiveButton("Resend") { _, _ ->
                val newText = edit.text.toString().trim()
                if (newText.isEmpty()) return@setPositiveButton
                m.text = newText
                adapter.notifyChanged(m)
                startGeneration(newText, null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Auto-compact: summarize old turns so the engine context stays small. */
    private fun compactOldTurns() {
        compacting = true
        toast("Compressing older messages to keep replies fast…")
        scope.launch {
            val old = currentChat.messages.dropLast(6)
                .joinToString("\n") { m ->
                    (if (m.role == Role.USER) "User: " else "NOVA: ") + m.text.take(250)
                }
            val sb = StringBuilder()
            try {
                NovaEngine.send(
                    "Summarize this conversation in one short paragraph. " +
                        "Keep all key facts, decisions, names and numbers:\n\n$old",
                    256
                ).collect { sb.append(it) }
                val summary = stripThinking(sb.toString()).trim()
                if (summary.length > 40) {
                    compactSummary = summary
                    compactedAtCount = currentChat.messages.size
                    needsContextCarry = true
                    docInjected = false
                    NovaEngine.reloadAsync(this@MainActivity, settings.systemPrompt)
                }
            } catch (e: Exception) {
                // failed - keep full context, retry next turn
            }
            compacting = false
        }
    }

    /** Detects "remind me to X at/in TIME" and schedules a local notification. */
    private fun maybeSetReminder(text: String) {
        val m = Regex("(?i)\\bremind me\\b(?:\\s+to)?\\s+(.+)").find(text) ?: return
        val rest = m.groupValues[1].trim()
        var task: String
        val timeStr: String
        val rel = Regex("(?i)^in\\s+(\\d+\\s*\\w+)$").find(rest)
        if (rel != null) {
            task = "Reminder"
            timeStr = rel.groupValues[1]
        } else {
            var idx = -1
            for (k in listOf(" at ", " in ", " on ")) {
                val j = rest.lastIndexOf(k)
                if (j > idx) idx = j
            }
            if (idx <= 0) return
            task = rest.substring(0, idx).trim()
            timeStr = rest.substring(idx + 1).trim()
            // "remind me tomorrow at 5pm to take medicine": the real task
            // landed after the time - take it back from behind " to "
            if (task in listOf("tomorrow", "today", "tonight")) {
                val tIdx = rest.lastIndexOf(" to ")
                if (tIdx > idx) task = rest.substring(tIdx + 4).trim()
            }
            task = task.removePrefix("to ").trim()
            if (task.isEmpty()) return
        }
        val whenMs = parseReminderTime(timeStr) ?: return
        val human = java.text.SimpleDateFormat("EEE, d MMM h:mm a", Locale.getDefault())
            .format(java.util.Date(whenMs))
        AlertDialog.Builder(this)
            .setTitle("Set reminder?")
            .setMessage(task + "\n\n" + human)
            .setPositiveButton("Set") { _, _ ->
                Reminder.schedule(this, whenMs, task)
                toast("Reminder set: $human")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseReminderTime(s: String): Long? {
        val now = java.util.Calendar.getInstance()
        val t = s.trim().lowercase()
        // "in 20 minutes" / "in 3 hours" / "in 45 sec"
        Regex("(?i)^(?:in\\s+)?(\\d+)\\s*(sec|secs|second|seconds|min|mins|minute|minutes|hour|hours|hr|hrs)\\b").find(t)?.let { mm ->
            val n = mm.groupValues[1].toLongOrNull() ?: return null
            val unit = mm.groupValues[2]
            val ms = when {
                unit.startsWith("sec") -> n * 1000L
                unit.startsWith("min") -> n * 60_000L
                else -> n * 3_600_000L
            }
            return now.timeInMillis + ms
        }
        // "6pm", "18:30", "9 am", "tomorrow 10am"
        val tomorrow = t.contains("tomorrow")
        val tm = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(t.replace("tomorrow", "")) ?: return null
        var hour = tm.groupValues[1].toIntOrNull() ?: return null
        val minute = tm.groupValues[2].toIntOrNull() ?: 0
        val ampm = tm.groupValues[3]
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        if (hour > 23) return null
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        if (tomorrow) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        else if (cal.timeInMillis <= now.timeInMillis) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun scrollToEnd(force: Boolean = true) {
        if (adapter.itemCount == 0) return
        if (force || atBottom) messagesRv.scrollToPosition(adapter.itemCount - 1)
    }

    // ----------------------------------------------------------- settings

    private fun showSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun icon(res: Int, color: Int) = getDrawable(res)!!.mutate().apply {
        colorFilter = android.graphics.PorterDuffColorFilter(
            color, android.graphics.PorterDuff.Mode.SRC_IN)
    }

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

    /** Saves any crash to a file so it can be shared and diagnosed. */
    private fun installCrashReporter() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "last_crash.txt").writeText(
                    "time: " + java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(java.util.Date()) +
                        "\nthread: " + t.name + "\n\n" +
                        android.util.Log.getStackTraceString(e))
            } catch (x: Exception) { }
            previous?.uncaughtException(t, e)
        }
    }

    /** If the last session crashed, offer to share the stack trace. */
    private fun maybeShowCrashReport() {
        try {
            val f = File(filesDir, "last_crash.txt")
            if (!f.exists()) return
            val txt = f.readText()
            f.delete()
            AlertDialog.Builder(this)
                .setTitle("NOVA crashed last time")
                .setMessage(txt.take(1200))
                .setPositiveButton("Share") { _, _ ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, txt.take(8000))
                    }
                    startActivity(Intent.createChooser(send, "Share crash report"))
                }
                .setNegativeButton("Dismiss", null)
                .show()
        } catch (e: Exception) { }
    }

    companion object {
        private const val REQ_SPEECH = 4251
        private const val REQ_CHATS = 4252
        private var crashHandlerInstalled = false
    }
}

// ---------------------------------------------------------------- adapter

/**
 * Removes hidden model "thinking" blocks (e.g. Qwen3) so only the actual
 * answer is shown, spoken and saved. While a block is still open (streaming),
 * everything from the opening tag on is hidden.
 */
private val THINK_OPEN = "<" + "think" + ">"
private val THINK_CLOSE = "<" + "/" + "think" + ">"

fun stripThinking(s: String): String {
    var out = s.replace(
        Regex("(?s)" + java.util.regex.Pattern.quote(THINK_OPEN) +
            ".*?" + java.util.regex.Pattern.quote(THINK_CLOSE)), "")
    val open = out.indexOf(THINK_OPEN)
    if (open >= 0) out = out.substring(0, open)
    return out
}
private val CODE_BLOCK = Regex("(?s)```[a-zA-Z0-9+#.-]*\\n?(.*?)```")

/** Markdown stripped to plain text - clean for pasting as a prompt.
 *  Code blocks and inline code are stashed first so their underscores and
 *  asterisks (like __init__ or x * y) survive the markdown stripping. */
fun plainText(s: String): String {
    val stash = mutableListOf<String>()
    var t = CODE_BLOCK.replace(s) {
        stash.add(it.groupValues[1]); "\u0000${stash.size - 1}\u0000"
    }
    t = Regex("`[^`\\n]+`").replace(t) {
        stash.add(it.value.substring(1, it.value.length - 1)); "\u0000${stash.size - 1}\u0000"
    }
    t = t
        .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_~]+"), "")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^>\\s?"), "")
        .replace(Regex("(?m)^[-*+] "), "- ")
        .trim()
    for (i in stash.indices) t = t.replace("\u0000$i\u0000", stash[i])
    return t
}

/** If the added text starts by repeating the end of the old text, drop the overlap. */
private fun stripRepeatJoin(old: String, added: String): String {
    val a = old.trimEnd()
    val b = added.trimStart()
    val max = minOf(400, b.length)
    for (k in max downTo 10) {
        val head = b.take(k).trim()
        if (head.length >= 10 && a.endsWith(head)) {
            var rest = b.substring(k).trimStart()
            if (rest.startsWith(".")) rest = rest.substring(1).trimStart()
            return a + (if (rest.isNotEmpty()) " " + rest else "")
        }
    }
    return old + added
}

/** Drops the first line of a new reply when it just repeats the last
 *  line of a previous, stopped reply. */
private fun stripRepeatStart(newText: String, prevText: String): String {
    var last = prevText.lines().map { it.trim() }.lastOrNull { it.isNotBlank() }
        ?: return newText
    last = last.removeSuffix("⏹").trim()
    if (last.length < 12) return newText
    val t = newText.trimStart()
    if (!t.startsWith(last)) return newText
    var rest = t.substring(last.length).trimStart()
    if (rest.startsWith(".")) rest = rest.substring(1).trimStart()
    return rest.ifEmpty { newText }
}

private fun copyToClipboard(ctx: Context, text: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("NOVA", text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        // some devices (e.g. MIUI) block clipboard access - never crash,
        // let the user copy manually from a dialog instead
        AlertDialog.Builder(ctx)
            .setTitle("Copy manually")
            .setMessage(if (text.length > 4000) text.take(4000) + "\n…" else text)
            .setPositiveButton("Close", null)
            .show()
    }
}


class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = mutableListOf<Msg>()
    private var markwon: Markwon? = null
    var onContinue: (() -> Unit)? = null
    var onTool: ((String) -> Unit)? = null
    var onRegenerate: (() -> Unit)? = null
    var onEditResend: ((Msg) -> Unit)? = null

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
        val last = items[items.size - 1]
        last.done = true
        // permanently remove hidden thinking text - this is what gets
        // shown, copied, spoken and saved to the chat transcript
        last.text = stripThinking(last.text).trim()
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
            val prism4j = Prism4j(NovaGrammarLocator)
            markwon = Markwon.builder(ctx)
                .usePlugin(SyntaxHighlightPlugin.create(prism4j, Prism4jThemeDefault.create()))
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
            setPadding(dp(ctx, 15), 0, 0, dp(ctx, 2))
        }
        fun actIcon(res: Int) = ctx.getDrawable(res)!!.mutate().apply {
            colorFilter = android.graphics.PorterDuffColorFilter(
                NovaTheme.dim, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        val copyBtn = TextView(ctx).apply {
            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 18), dp(ctx, 6))
            setCompoundDrawablesWithIntrinsicBounds(actIcon(R.drawable.ic_copy), null, null, null)
        }
        val regenBtn = TextView(ctx).apply {
            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
            setCompoundDrawablesWithIntrinsicBounds(actIcon(R.drawable.ic_refresh), null, null, null)
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
            holder.bubble.background = GradientDrawable().apply {
                val r = dp(ctx, 20).toFloat()
                val s = dp(ctx, 5).toFloat()
                setCornerRadii(floatArrayOf(r, r, r, r, s, s, r, r))
                setColor(NovaTheme.bubble)
            }
            holder.bubble.setPadding(dp(ctx, 15), dp(ctx, 11), dp(ctx, 15), dp(ctx, 11))
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
            holder.bubble.background = null
            holder.bubble.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
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
            val tools = if (user) linkedMapOf(
                "Fix grammar" to "Fix the grammar and spelling of the text between the lines. Reply with ONLY the corrected text, nothing else:\n-----\n$msgText\n-----",
                "Rewrite better" to "Rewrite the text between the lines to be clearer and better written. Keep the same meaning and the same language. Reply with ONLY the rewritten text:\n-----\n$msgText\n-----",
                "Translate to Hindi" to "Translate the text between the lines into Hindi. Reply with ONLY the translation:\n-----\n$msgText\n-----",
                "Make shorter" to "Rewrite the text between the lines much shorter while keeping the key facts. Reply with ONLY the shortened text:\n-----\n$msgText\n-----",
                "Make longer" to "Expand the text between the lines with more detail and examples. Reply with ONLY the expanded text:\n-----\n$msgText\n-----"
            ) else linkedMapOf(
                "Make study cards" to "Create 8 study flashcards from this material. Format each card EXACTLY as:\nQ: <question>\nA: <answer>\nNo numbering, no text before or after.",
                "Regenerate" to ""
            )
            options += tools.keys
            AlertDialog.Builder(ctx)
                .setItems(options.toTypedArray()) { _, which ->
                    when (val chosen = options[which]) {
                        "Edit & resend" -> onEditResend?.invoke(m)
                        "Copy code" -> copyToClipboard(ctx, code)
                        "Copy" -> copyToClipboard(ctx, plainText(msgText))
                        "Share" -> {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, msgText)
                            }
                            ctx.startActivity(Intent.createChooser(send, "Share message"))
                        }
                        "Regenerate" -> onRegenerate?.invoke()
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
