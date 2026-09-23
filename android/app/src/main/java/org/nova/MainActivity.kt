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
import org.json.JSONArray
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

    /** Guards the degenerate-reply retry (one retry per turn). */
    private var replyRetried = false
    // v5.4 grounded answers: escape-free newline, source citation, Q&A cache
    private val NL = 10.toChar().toString()
    private var pendingCitation: String? = null
    private var pendingQaKey: String? = null

    /** Notes document the user last summarized - "gimme the whole summary" returns to it. */
    private var lastNotesDoc: String? = null

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
    private var docInjectedText: String = ""

    /** Document read-aloud state. */
    private var readSents: List<String> = emptyList()
    private var readIdx = 0
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
            val wikiReady = WikiCore.isReady(this@MainActivity)
            val cardsDue = Study.dueCount(this@MainActivity)
            val examLine = Exams.promptLine(this@MainActivity)
            if (wikiReady || cardsDue > 0 || examLine != null) {
                val parts = mutableListOf<String>()
                if (wikiReady) parts += "Wikipedia ready"
                if (cardsDue > 0) parts += "$cardsDue study cards due"
                examLine?.let { parts += it }
                addView(TextView(this@MainActivity).apply {
                    text = parts.joinToString("  •  ")
                    textSize = 11f
                    setTextColor(NovaTheme.dim)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(6))
                })
            }
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
                docInjectedText = ""
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
        val dueCount = Study.dueCount(this)
        val studyRow = drawerRow(
            if (dueCount > 0) "Study ($dueCount due)" else "Study",
            R.drawable.ic_edit) { Study.review(this) }
        studyRow.setOnLongClickListener {
            val total = Study.totalCards(this)
            val d = Study.dueCount(this)
            AlertDialog.Builder(this)
                .setTitle("Study deck")
                .setMessage("$total cards, $d due for review.")
                .setPositiveButton("Review") { _, _ -> Study.review(this) }
                .setNegativeButton("Clear deck") { _, _ ->
                    AlertDialog.Builder(this)
                        .setTitle("Delete all cards?")
                        .setPositiveButton("Delete") { _, _ ->
                            Study.clear(this); toast("Study deck cleared")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                .show()
            true
        }
        drawerPane.addView(studyRow)
        drawerPane.addView(drawerRow("Share chat", R.drawable.ic_send) { shareChat() })
        drawerPane.addView(drawerRow("Exams", R.drawable.ic_doc) {
            startActivity(Intent(this, ExamsActivity::class.java))
        })
        drawerPane.addView(drawerRow("What did I miss?", R.drawable.ic_chat) { missedNotifications() })
        drawerPane.addView(drawerRow("Write in my style", R.drawable.ic_edit) { writeInMyStyle() })
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

    /**
     * Finds the best parts of the attached document for a question -
     * see docSearchIn for how the scoring works.
     */
    private fun docSearch(query: String, maxChars: Int = 4000): String =
        docSearchIn(docContext ?: "", query, maxChars)

    private fun send() {
        pendingCitation = null
        pendingQaKey = null
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
        // phone commands: call / text / alarm / open app - no model needed
        if (tryPhoneCommand(text)) return
        // while reading aloud: "explain that sentence" asks about the last spoken one
        if (readIdx > 0 && Regex("(?i)explain (that|this|the last) (sentence|part|line)")
                .containsMatchIn(text)) {
            tts?.stop()
            runTool("Explain this sentence from the document in simple words, " +
                "with an example if helpful:\n\"${readSents[readIdx - 1]}\"")
            return
        }
        // "show/gimme the notes" - paste the raw document text, no model needed
        if (docContext != null) {
            val wantsRaw = Regex("(?i)\\b(show|gimme|give|send|paste|display|want)\\b[^.]*\\b(notes?|document|text|pdf)\\b")
                .containsMatchIn(text)
            val asksSummary = Regex("(?i)\\bsummar").containsMatchIn(text) &&
                !Regex("(?i)\\b(don'?t|do not|stop|no)\\b[^.]*\\bsummar").containsMatchIn(text)
            if (wantsRaw && !asksSummary &&
                !Regex("(?i)simpl|explain|quiz|points").containsMatchIn(text)) {
                val part = docSearch(text, 6000)
                val um = Msg(Role.USER, text)
                currentChat.messages.add(um)
                adapter.add(um)
                val reply = Msg(Role.ASSISTANT, "(from $docName)\n\n$part")
                currentChat.messages.add(reply)
                adapter.add(reply)
                scrollToEnd()
                scope.launch(Dispatchers.IO) {
                    try { ChatStore.save(this@MainActivity, currentChat) } catch (e: Exception) { }
                }
                return
            }
            // "summarise this" -> the full section-by-section summary with
            // live progress (one-shot only covered the first pages)
            if (asksSummary) {
                summarizeDoc()
                return
            }
        }
        // "gimme the notes of federalism" - paste stored Knowledge notes,
        // even when no document is attached in this chat
        if (docContext == null && settings.knowledgeEnabled && Knowledge.hasDocs(this)) {
            val wantsNotes = Regex("(?i)\\b(show|gimme|give|send|paste|display|want|read)\\b[^.]*\\b(notes?|material|answers?)\\b")
                .containsMatchIn(text) &&
                !Regex("(?i)\\bsummar|explain|simpl|quiz|points").containsMatchIn(text)
            if (wantsNotes) {
                // the chapter window around the best match - scattered
                // top-4 fragments used to mix chapters ("money and credit"
                // returned Great Depression text)
                val ndoc = Knowledge.bestDocName(this, text)
                val parts = if (ndoc != null) Knowledge.bestChunks(this, text, 10) else emptyList()
                if (parts.isNotEmpty()) {
                    val um = Msg(Role.USER, text)
                    currentChat.messages.add(um)
                    adapter.add(um)
                    var body = parts.joinToString("\n\n")
                    if (body.length > 6000) body = body.substring(0, 6000) + "\n[...more]"
                    val reply = Msg(Role.ASSISTANT, "(from $ndoc)\n\n$body")
                    currentChat.messages.add(reply)
                    adapter.add(reply)
                    scrollToEnd()
                    scope.launch(Dispatchers.IO) {
                        try { ChatStore.save(this@MainActivity, currentChat) } catch (e: Exception) { }
                    }
                    return
                }
                // nothing matched - list what notes exist so the user can name one
                val names = Knowledge.docs(this).joinToString(", ") { it.first }
                if (names.isNotEmpty()) {
                    val um = Msg(Role.USER, text)
                    currentChat.messages.add(um)
                    adapter.add(um)
                    val reply = Msg(Role.ASSISTANT,
                        "I couldn't find notes on that. You have notes on: $names")
                    currentChat.messages.add(reply)
                    adapter.add(reply)
                    scrollToEnd()
                    return
                }
            }
        }
        // "summarise sst notes" / "gimme the whole summary" - summarize the
        // saved notes over the WHOLE chapter (map-reduce), clean engine
        if (docContext == null && settings.knowledgeEnabled && Knowledge.hasDocs(this)) {
            val wantsSumm = Regex("(?i)\\bsummaris|\\bsummariz").containsMatchIn(text)
            val summNoun = text.lowercase().contains("summary")
            // v5.4.1: "teach me whole power sharing chapter" - the user wants
            // the WHOLE chapter as a study summary, not a 2400-char answer
            val wholeTeach = text.lowercase().contains("whole") &&
                (text.lowercase().contains("chapter") || text.lowercase().contains("notes"))
            val followUp = lastNotesDoc != null &&
                Regex("(?i)\\b(whole|full|complete|entire|detailed)\\s+summar").containsMatchIn(text)
            if (wantsSumm || followUp || summNoun || wholeTeach) {
                // relaxed match: ANY query term can point at the document -
                // requiring every word in one chunk made "summarise power
                // sharing" silently fall through to chat (and hallucinate)
                // "summarise it notes" - "it" means the IT notes here,
                // not the pronoun the tokenizer throws away
                val qtext = text.replace(" it notes", " IT Revision notes", ignoreCase = true)
                val doc = if (wantsSumm || summNoun || wholeTeach) Knowledge.bestDocName(this, qtext)
                          else lastNotesDoc
                if (doc != null) {
                    lastNotesDoc = doc
                    // "summarise sst notes" NAMES the document -> the user
                    // wants the whole doc, not just the first 18 chunks
                    val whole = wholeTeach || !wantsSumm || Knowledge.nameOnlyQuery(qtext, doc)
                    summarizeNotes(doc, text, fullDoc = whole)
                    return
                }
                // nothing matched - NEVER fall back to guessing from chat:
                // list what notes exist so the user can name one
                val names = Knowledge.docs(this).joinToString(", ") { it.first }
                if (names.isNotEmpty()) {
                    val um = Msg(Role.USER, text)
                    currentChat.messages.add(um)
                    adapter.add(um)
                    val reply = Msg(Role.ASSISTANT,
                        "I couldn't find notes on that. You have notes on: $names")
                    currentChat.messages.add(reply)
                    adapter.add(reply)
                    scrollToEnd()
                    return
                }
            }
        }
        maybeAutoRemember(text)
        maybeSetReminder(text)

        // recover from transcript-echo poisoning: if NOVA's last reply came
        // out as a transcript ("NOVA: ..."), reset the engine so it answers fresh
        val lastReply = currentChat.messages.lastOrNull { it.role == Role.ASSISTANT }
        if (lastReply != null && Regex("(?m)^\\s*(?:NOVA|You)\\s*:").containsMatchIn(lastReply.text)) {
            needsContextCarry = true
            if (NovaEngine.isModelLoaded) NovaEngine.reloadAsync(this, settings.systemPrompt)
        }

        val docPart = if (docContext != null) {
            val win = docSearch(text)
            val qWords = text.lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length > 2 && it !in docStop }
            val overlap = qWords.count { it in win.lowercase() }
            // user explicitly off the document ("don't search the notes")
            val offDoc = Regex("(?i)\\b(?:don'?t|do not|stop)\\b[^.]*\\b(?:use|search|look)\\b[^.]*\\b(?:notes?|document|pdf|it)\\b|\\bfrom your own knowledge\\b|\\bwithout the (?:notes?|document)\\b")
                .containsMatchIn(text)
            when {
                offDoc -> {
                    docInjected = false
                    "(The document restriction from earlier is lifted - answer from your own knowledge.)\n\n"
                }
                overlap == 0 -> {
                    // question has nothing to do with the document: don't
                    // re-inject it, and lift any earlier restriction so
                    // general questions ("who is X?") still get answered
                    if (docInjected) {
                        docInjected = false
                        "(The document restriction from earlier is lifted - answer from your own knowledge.)\n\n"
                    } else ""
                }
                else -> {
                    docInjected = true
                    docInjectedText = win
                    "(The user shared a document titled \"$docName\". Its content is between the lines. Answer ONLY using this document; if the answer is not in it, say so honestly.\n-----\n$win\n-----\nEnd of document.)\n\n"
                }
            }
        } else ""
        val basePrompt: String = docPart + when {
            needsContextCarry && compactSummary != null && currentChat.messages.isNotEmpty() -> {
                val recent = currentChat.messages.takeLast(6).joinToString("\n") { m ->
                    (if (m.role == Role.USER) "You: " else "NOVA: ") + m.text.take(250)
                }
                "(Summary of earlier conversation: $compactSummary)\n\n(Recent messages:\n$recent\n— end)\n\nNew message: $text\n(Reply to the new message directly, even if it starts a completely new topic. Do not repeat the transcript.)"
            }
            needsContextCarry && currentChat.messages.isNotEmpty() -> {
                val recent = currentChat.messages.takeLast(6).joinToString("\n") { m ->
                    (if (m.role == Role.USER) "You: " else "NOVA: ") + m.text.take(250)
                }
                "(Earlier conversation for context:\n$recent\n— end of earlier conversation)\n\nNew message: $text\n(Reply to the new message directly, even if it starts a completely new topic. Do not repeat the transcript.)"
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
        // tiny models (Llama 3.2 1B) drown in stacked instructions - they
        // get ONE background source, no exam line, and short injections
        val mlabel = NovaEngine.activeModelLabel.lowercase()
        val tiny = "1b" in mlabel || "0.6b" in mlabel || "0.5b" in mlabel

        if (!tiny) {
            // exam countdown awareness
            Exams.promptLine(this)?.let { line ->
                prompt = "(The user's upcoming exams: $line.)\n\n$prompt"
            }
        }

        // knowledge base (offline RAG): relevant notes from the user's documents
        var knowledgePart = ""
        // v5.4: study questions get STRICT grounding + citation + cache
        val qLow = text.lowercase()
        val studyQ = qLow.startsWith("explain ") || qLow.startsWith("teach me ") ||
            qLow.startsWith("what is ") || qLow.startsWith("what are ") ||
            qLow.startsWith("who is ") || qLow.startsWith("who was ") ||
            qLow.startsWith("define ") || qLow.startsWith("describe ") ||
            qLow.startsWith("tell me about ") || qLow.contains(" explain ") ||
            qLow.contains(" teach me ") || qLow.contains(" what is ")
        if (settings.knowledgeEnabled && Knowledge.hasDocs(this)) {
            // v5.4: cached answer from last time? -> instant, no model run
            if (studyQ && docPart.isEmpty()) {
                val qaKey = "qa_" + Integer.toHexString(qLow.hashCode())
                val qaFile = File(File(filesDir, "summary_cache").apply { mkdirs() }, qaKey)
                val qaCached = if (qaFile.exists())
                    try { qaFile.readText() } catch (e: Exception) { "" } else ""
                if (qaCached.length > 30) {
                    val um = Msg(Role.USER, text)
                    currentChat.messages.add(um); adapter.add(um)
                    val cachedReply = Msg(Role.ASSISTANT, qaCached)
                    currentChat.messages.add(cachedReply); adapter.add(cachedReply)
                    scrollToEnd()
                    toast("Answer (cached from last time)")
                    try { ChatStore.save(this, currentChat) } catch (e: Exception) { }
                    return
                }
                pendingQaKey = qaKey
            }
            val hits = Knowledge.search(this, text)
            if (hits.isNotEmpty()) {
                var notes = hits.joinToString("\n---\n") { "[${it.doc}] ${it.text}" }
                if (notes.length > (if (tiny) 900 else 2400))
                    notes = notes.substring(0, if (tiny) 900 else 2400) + "\n[...more omitted]"
                knowledgePart = "(Relevant notes from the user's documents - use them if they help:\n$notes)\n\n"
            }
        }
        // offline Wikipedia: matching articles as background facts
        var wikiPart = ""
        if (settings.wikiEnabled && WikiCore.isReady(this)) {
            val wikiHits = WikiCore.search(this, text, if (tiny) 1 else 2)
            if (wikiHits.isNotEmpty()) {
                var facts = wikiHits.joinToString("\n---\n") { "${it.title}: ${it.text}" }
                val cap = if (tiny) 900 else 2400
                if (facts.length > cap) facts = facts.substring(0, cap) + "…"
                wikiPart = "(Wikipedia background - use it to answer, ignore if not relevant:\n$facts)\n\n"
            }
        }
        // v5.4: study questions - rewrap the notes as STRICT instructions,
        // record the source pages, and let the notes be the only background
        if (studyQ && docPart.isEmpty() && knowledgePart.isNotEmpty()) {
            val hits2 = Knowledge.search(this, text)
            var notes2 = hits2.joinToString(NL + "---" + NL) { "[" + it.doc + "] " + it.text }
            if (notes2.length > (if (tiny) 900 else 2400))
                notes2 = notes2.substring(0, if (tiny) 900 else 2400)
            knowledgePart = "(Study notes from the user's documents follow. " +
                "Answer ONLY using these notes. If the answer is not in the " +
                "notes, say plainly that the notes do not cover it. Copy key " +
                "terms and facts exactly as written." + NL + notes2 + ")" + NL + NL
            wikiPart = ""
            var pages = ""
            for (l in notes2.lines()) {
                val t2 = l.trim()
                if (t2 == "---") break
                if (t2.length < 22 && t2.contains("page ")) {
                    val d = t2.filter { it.isDigit() }
                    if (d.isNotEmpty() && !pages.contains(d)) {
                        if (pages.isNotEmpty()) pages += ", "
                        pages += d
                    }
                }
            }
            pendingCitation = if (pages.isEmpty()) "" else
                "Source: " + hits2.first().doc + ", " +
                (if (pages.contains(",")) "pages " else "page ") + pages
        }
        // one background source for tiny models, both for bigger ones
        prompt = (if (tiny) (if (knowledgePart.isNotEmpty()) knowledgePart else wikiPart)
                  else knowledgePart + wikiPart) + prompt

        // general chat: match the user's language, no guessing
        if (docPart.isEmpty()) {
            prompt += "\n(Reply in the same language the user writes in. If you don't know something, say so honestly instead of guessing.)"
        }
        autoContinueCount = 0
        replyRetried = false
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
                        val before = replyMsg.text.substring(0, junction)
                        val added = replyMsg.text.substring(junction)
                        val b2 = cutPartialLine(before, added)
                        replyMsg.text = stripRepeatJoin(b2, dropRepeatedBlocks(b2, added))
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
                    // blank, one-word or looping answers from tiny models:
                    // retry once with a firmer instruction instead of garbage
                    if (!speechCancelled && newBubble && userText != null && !replyRetried &&
                        (isDegenerateReply(stripThinking(replyMsg.text)) ||
                            chatDerailed(stripThinking(replyMsg.text), prompt))) {
                        replyRetried = true
                        pendingCitation = null
                        pendingQaKey = null
                        replyMsg.text = ""
                        adapter.setLastText("")
                        startGeneration(
                            "Question: $userText\nAnswer the question directly and clearly " +
                                "in one to three sentences. If you don't know the answer, " +
                                "say so honestly. Do not repeat the same point twice.",
                            null, newBubble = false)
                        return@withContext
                    }
                    // a reply that still came out completely empty - say so
                    // instead of showing a blank bubble
                    if (stripThinking(replyMsg.text).isBlank() && !speechCancelled)
                        replyMsg.text = "(no reply - tap the regenerate icon to try again)"
                    // v5.4: append the source citation, then cache the answer
                    if (pendingCitation != null && newBubble && userText != null) {
                        val cit = pendingCitation!!
                        pendingCitation = null
                        if (cit.isNotEmpty() && replyMsg.text.isNotBlank()) {
                            replyMsg.text = replyMsg.text.trim() + NL + NL + cit
                            adapter.setLastText(replyMsg.text)
                        }
                    }
                    if (pendingQaKey != null && newBubble && userText != null) {
                        val qaAns = stripThinking(replyMsg.text).trim()
                        if (qaAns.length > 30) try {
                            File(File(filesDir, "summary_cache").apply { mkdirs() },
                                pendingQaKey!!).writeText(qaAns)
                        } catch (e: Exception) { }
                        pendingQaKey = null
                    }
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

    /** Word edit distance (capped at 3) - fuzzy matching for typed/spoken commands. */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (Math.abs(a.length - b.length) > 2) return 3
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1,
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** True when a reply is blank, one or two words, or stuck repeating
     *  the same line over and over - too broken to show as-is. */
    private fun isDegenerateReply(t: String): Boolean {
        val s = t.trim().removeSuffix("\u23F9").trim()
        if (s.isEmpty()) return true
        if (s.split(Regex("\\s+")).filter { it.isNotBlank() }.size <= 2) return true
        // the same line (or bullet) 3+ times = the model is in a loop
        val counts = HashMap<String, Int>()
        for (raw in s.lines()) {
            val line = raw.trim().removePrefix("* ").removePrefix("- ").trim()
            if (line.length >= 15) {
                val c = (counts[line] ?: 0) + 1
                counts[line] = c
                if (c >= 3) return true
            }
        }
        return false
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

    /** Runs a hidden-prompt tool action (no duplicate user bubble).
     *  The document text is injected so "summarize this document"
     *  actually has the document to work on. */
    private fun runTool(prompt: String) {
        if (compacting) { toast("Compressing older messages — one moment"); return }
        if (generating) { toast("Wait for the current reply to finish"); return }
        if (!ensureModelReady()) return
        if (prompt.startsWith("__STYLE__")) {
            val sp = stylePrompt("Rewrite this text in the same personal style as the examples, keeping the meaning:\n-----\n${prompt.substring(9)}\n-----")
            if (sp == null) toast("Chat a bit more first so I can learn your style")
            else startGeneration(sp, null)
            return
        }
        pendingCards = prompt.startsWith("Create 8 study flashcards") ||
            prompt.startsWith("Create a quiz")
        val docPart = if (docContext != null && docName != null) {
            "(The user shared a document titled \"$docName\". Its content is between the lines.\n-----\n${docSearch(prompt, 8000)}\n-----\nEnd of document.)\n\n"
        } else ""
        startGeneration(docPart + prompt, null)
    }

    /**
     * Smart document summary for long PDFs: splits into sections, skips
     * table-of-contents / references / index pages, summarizes each
     * section with LIVE progress in the chat, then writes one final
     * summary from the section summaries (map-reduce). Finished summaries
     * are cached per document, so asking again for the same file is
     * instant.
     */
    private fun summarizeDoc() {
        if (compacting) { toast("Compressing older messages \u2014 one moment"); return }
        if (generating) { toast("Wait for the current reply to finish"); return }
        if (!ensureModelReady()) return
        val doc = docContext ?: return
        // cached summary from last time? -> instant
        val key = summaryCacheKey()
        if (key != null) {
            val cf = File(File(filesDir, "summary_cache").apply { mkdirs() }, key)
            if (cf.exists()) {
                val cached = try { cf.readText() } catch (e: Exception) { "" }
                if (cached.length > 50) {
                    val um = Msg(Role.USER, "Summarize ${docName ?: "document"}")
                    currentChat.messages.add(um); adapter.add(um)
                    val reply = Msg(Role.ASSISTANT, cached)
                    currentChat.messages.add(reply); adapter.add(reply)
                    scrollToEnd()
                    toast("Summary (cached from last time)")
                    return
                }
            }
        }
        // v5.2: small documents used to take a thin one-pass "short
        // overview" shortcut here - they now go through the full section
        // pipeline like every other document (1-2 sections, still fast)
        var chunks = docChunks(doc, 6500)
        // skip table-of-contents / references / index pages: faster, cleaner
        val real = chunks.filter { !isJunkChunkText(it) }
        var skipped = 0
        if (real.size >= 3 && real.size < chunks.size) {
            skipped = chunks.size - real.size
            chunks = real
        }
        var strided = false
        if (chunks.size > 12) {
            val step = chunks.size / 12
            chunks = chunks.filterIndexed { i, _ -> i % step == 0 }.take(12)
            strided = true
        }
        // live progress bubble: sections land one by one instead of a dead wait
        val um = Msg(Role.USER, "Summarize ${docName ?: "document"}")
        currentChat.messages.add(um)
        adapter.add(um)
        val reply = Msg(Role.ASSISTANT, "", done = false)
        currentChat.messages.add(reply)
        adapter.add(reply)
        scrollToEnd()
        adapter.setLastText(if (skipped > 0)
            "Reading ${chunks.size} sections ($skipped index/reference pages skipped)\u2026"
        else "Reading ${chunks.size} sections\u2026")
        generating = true
        sendBtn.setCompoundDrawablesWithIntrinsicBounds(
            icon(R.drawable.ic_stop, stopColor), null, null, null)
        setStatus()
        scope.launch {
            try {
                var sectionSummaries = StringBuilder()
                var emptyStreak = 0
                for ((i, c) in chunks.withIndex()) {
                    // fresh engine every few chunks: once the context fills
                    // the engine silently drops the oldest tokens, which
                    // quietly degrades later sections - reload in batches
                    if (i in 1 until chunks.size && i % 3 == 0 && NovaEngine.contextDirty) {
                        try { NovaEngine.load(this@MainActivity, NovaEngine.activeModelPath!!,
                            NovaEngine.activeModelLabel, settings.systemPrompt) } catch (e: Exception) { }
                    }
                    adapter.setLastText("Summarizing section ${i + 1}/${chunks.size}\u2026\n\n" +
                        tail300(sectionSummaries.toString()))
                    status.text = "summarizing section ${i + 1}/${chunks.size}\u2026"
                    val sb = StringBuilder()
                    try {
                        NovaEngine.send(
                            "Summarize this part of a document in 5-8 detailed sentences. " +
                                "Keep all names, numbers, dates and facts:" +
                                "\n-----\n$c\n-----", 400
                        ).collect { sb.append(it) }
                    } catch (e: Exception) { }
                    val s = stripThinking(sb.toString()).trim()
                    if (s.length > 10) {
                        val before = sectionSummaries.length
                        sectionSummaries.append(s).append("\n\n")
                        // tiny models echo the same sentence for similar
                        // sections - re-dedupe so the progress display and
                        // the final combine see each point only once
                        sectionSummaries = StringBuilder(dedupeLines(sectionSummaries.toString()))
                        // stop early when the document just repeats itself
                        if (sectionSummaries.length - before < 20) emptyStreak++ else emptyStreak = 0
                        if (emptyStreak >= 6 && i + 1 < chunks.size) {
                            adapter.setLastText("Remaining sections repeat earlier ones - skipping to the final summary")
                            break
                        }
                    }
                }
                adapter.setLastText("Writing the final summary\u2026")
                status.text = "writing final summary\u2026"
                // fresh engine: the section prompts filled the context - reload
                // so the final combine gets a clean window (overflow makes the
                // model derail into "Step 1..." nonsense mid-generation)
                if (NovaEngine.contextDirty) {
                    try { NovaEngine.load(this@MainActivity, NovaEngine.activeModelPath!!,
                        NovaEngine.activeModelLabel, settings.systemPrompt) } catch (e: Exception) { }
                }
                val sb2 = StringBuilder()
                NovaEngine.send(
                    "These are summaries of " +
                        (if (strided) "the main sections of a long document" else "the sections of a document") +
                        ". Write a DETAILED final summary organized topic by topic: for each topic " +
                        "start with a short bold heading line, then 2-4 bullet points (lines " +
                        "starting with \"- \") in full sentences with its names, dates, numbers " +
                        "and terms. Every bullet must be a complete sentence containing " +
                        "at least one date, name, number or term - never a single word. " +
                        "Do not skip any topic. Use only the information given:" +
                        "\n\n${dedupeLines(sectionSummaries.toString()).take(11000)}", 1500
                ).collect { sb2.append(it) }
                var finalText = stripThinking(sb2.toString()).trim()
                // if the model derailed (scratchpad / off-topic drivel) fall
                // back to the deduped section summaries - they are detailed
                if (looksDerailed(finalText, sectionSummaries.toString()))
                    finalText = dedupeLines(sectionSummaries.toString()).trim()
                else finalText = dedupeLines(finalText)
                reply.text = finalText
                adapter.finalizeLast()
                scrollToEnd()
                // cache it for next time
                if (key != null && finalText.length > 50) try {
                    File(File(filesDir, "summary_cache").apply { mkdirs() }, key)
                        .writeText(finalText)
                } catch (e: Exception) { }
                // the engine context now holds every section prompt - reset it
                needsContextCarry = true
                NovaEngine.reloadAsync(this@MainActivity, settings.systemPrompt)
                try {
                    withContext(Dispatchers.IO) { ChatStore.save(this@MainActivity, currentChat) }
                } catch (e: Exception) { }
                toast("Summary ready - long-press it to make study cards")
            } catch (e: Exception) {
                try {
                    reply.text = "Summary failed - try again"
                    adapter.finalizeLast()
                } catch (x: Exception) { }
                toast("Summary failed - try again")
            } finally {
                generating = false
                setStatus()
                updateSendLook()
            }
        }
    }

    /** Cache key for this document's summary (name + length = same file). */
    private fun summaryCacheKey(): String? {
        val n = docName ?: return null
        val d = docContext ?: return null
        return "doc5_" + Integer.toHexString(n.hashCode()) + "_" + d.length
    }

    /**
     * Summarizes saved notes over the WHOLE chapter: map-reduce with live
     * progress (like the PDF summarizer), on a CLEAN engine so earlier
     * topics can't bleed in. fullDoc=true takes every chunk of the
     * document; otherwise the chunks matching the user's topic. Cached.
     */
    private fun summarizeNotes(doc: String, userText: String, fullDoc: Boolean) {
        if (compacting) { toast("Compressing older messages \u2014 one moment"); return }
        if (generating) { toast("Wait for the current reply to finish"); return }
        if (!ensureModelReady()) return
        val chunks = if (fullDoc) Knowledge.docChunks(this, doc)
                      else Knowledge.bestChunks(this, userText)
        if (chunks.isEmpty()) { toast("Couldn't find those notes"); return }
        val totalLen = chunks.sumOf { it.length }
        // cached from last time? -> instant
        val key = "notes6_" + Integer.toHexString(doc.hashCode()) + "_" + totalLen
        val cf = File(File(filesDir, "summary_cache").apply { mkdirs() }, key)
        if (cf.exists()) {
            val cached = try { cf.readText() } catch (e: Exception) { "" }
            if (cached.length > 50) {
                val um = Msg(Role.USER, userText)
                currentChat.messages.add(um); adapter.add(um)
                val reply = Msg(Role.ASSISTANT, "(summary of $doc, cached)\n\n$cached")
                currentChat.messages.add(reply); adapter.add(reply)
                scrollToEnd()
                return
            }
        }
        // tiny models show their scratchpad ("Step 1...") - forbid it
        val antiCot = " Reply with ONLY the summary itself - no 'Step 1' plan, " +
            "no questions, no 'final answer' line."
        // ALWAYS map-reduce with live progress: a single huge notes prompt can
        // overflow the model's context window (the notes get cut off and the
        // model invents the rest), while ~1600-char sections always fit.
        // The progress lines also show WHICH document is being summarized.
        val sections = ArrayList<String>()
        val sbb = StringBuilder()
        for (c in chunks) {
            if (sbb.isNotEmpty() && sbb.length + c.length > 2600) {
                sections.add(sbb.toString()); sbb.setLength(0)
            }
            if (sbb.isNotEmpty()) sbb.append("\n\n")
            sbb.append(c)
        }
        if (sbb.isNotEmpty()) sections.add(sbb.toString())
        val allSections = ArrayList(sections)
        // a very large document would mean a 30+ minute run - sample
        // evenly over the whole doc instead of only the first sections
        if (sections.size > 26) {
            val step = sections.size / 26
            sections.clear()
            allSections.filterIndexed { i, _ -> i % step == 0 }.take(26).forEach { sections.add(it) }
        }
        val um = Msg(Role.USER, userText)
        currentChat.messages.add(um)
        adapter.add(um)
        val reply = Msg(Role.ASSISTANT, "", done = false)
        currentChat.messages.add(reply)
        adapter.add(reply)
        scrollToEnd()
        adapter.setLastText("Reading ${sections.size} sections of $doc\u2026")
        generating = true
        sendBtn.setCompoundDrawablesWithIntrinsicBounds(
            icon(R.drawable.ic_stop, stopColor), null, null, null)
        setStatus()
        scope.launch {
            try {
                // clean engine first so old topics can't leak in - but skip
                // the reload when the context is already clean (saves seconds)
                if (NovaEngine.contextDirty) {
                    try {
                        NovaEngine.load(this@MainActivity, NovaEngine.activeModelPath!!,
                            NovaEngine.activeModelLabel, settings.systemPrompt)
                    } catch (e: Exception) { }
                }
                // checkpoint: sections summarized in an earlier interrupted
                // run are resumed instead of redone from zero
                val cpFile = File(File(filesDir, "sum_cp").apply { mkdirs() }, key)
                val done = ArrayList<String>()
                try {
                    val arr = JSONArray(cpFile.readText())
                    for (k in 0 until arr.length()) done.add(arr.getString(k))
                    if (done.size > sections.size) done.clear()   // doc changed
                } catch (e: Exception) { }
                var sectionSummaries = StringBuilder(
                    dedupeLines(done.joinToString("\n\n")))
                var emptyStreak = 0
                for ((i, c) in sections.withIndex()) {
                    if (i < done.size) continue      // already summarized
                    // fresh engine every few sections: once the context fills
                    // the engine silently drops the oldest tokens, which
                    // quietly degrades later sections - reload in batches
                    if (i in 1 until sections.size && i % 6 == 0 && NovaEngine.contextDirty) {
                        try { NovaEngine.load(this@MainActivity, NovaEngine.activeModelPath!!,
                            NovaEngine.activeModelLabel, settings.systemPrompt) } catch (e: Exception) { }
                    }
                    adapter.setLastText("Summarizing section ${i + 1}/${sections.size}\u2026\n\n" +
                        tail300(sectionSummaries.toString()))
                    status.text = "summarizing section ${i + 1}/${sections.size}\u2026"
                    val sb = StringBuilder()
                    try {
                        NovaEngine.send(
                            "Summarize this part of the notes in 5-8 detailed sentences. " +
                                "Keep every date, name, number, term and fact exactly as " +
                                "stated in the text:$antiCot\n-----\n$c\n-----", 400
                        ).collect { sb.append(it) }
                    } catch (e: Exception) { }
                    val s = stripThinking(sb.toString()).trim()
                    if (s.length > 10) {
                        val before = sectionSummaries.length
                        sectionSummaries.append(s).append("\n\n")
                        // tiny models echo the same sentence for similar
                        // sections - re-dedupe so the progress display and
                        // the final combine see each point only once
                        sectionSummaries = StringBuilder(dedupeLines(sectionSummaries.toString()))
                        done.add(s)
                        try {
                            val ja = JSONArray()
                            for (d in done) ja.put(d)
                            cpFile.writeText(ja.toString())
                        } catch (e: Exception) { }
                        // stop early when the notes just repeat themselves
                        if (sectionSummaries.length - before < 20) emptyStreak++ else emptyStreak = 0
                        if (emptyStreak >= 6 && i + 1 < sections.size) {
                            adapter.setLastText("Remaining sections repeat earlier ones - skipping to the final summary")
                            break
                        }
                    }
                }
                adapter.setLastText("Writing the final summary\u2026")
                status.text = "writing final summary\u2026"
                // fresh engine: the section prompts filled the context - reload
                // so the final combine gets a clean window (overflow makes the
                // model derail into "Step 1..." nonsense mid-generation)
                if (NovaEngine.contextDirty) {
                    try { NovaEngine.load(this@MainActivity, NovaEngine.activeModelPath!!,
                        NovaEngine.activeModelLabel, settings.systemPrompt) } catch (e: Exception) { }
                }
                val sb2 = StringBuilder()
                NovaEngine.send(
                    "These are section summaries from the notes \"$doc\". Write a DETAILED " +
                        "final study summary. Organize it topic by topic: for each topic " +
                        "start with a short bold heading line, then 2-4 bullet points " +
                        "(lines starting with \"- \") in full sentences with that topic's " +
                        "dates, names, numbers and terms. Cover EVERY topic. Use ONLY what " +
                        "the summaries say. Every bullet must be a complete sentence " +
                        "containing at least one date, name, number or term - never a " +
                        "single word. Copy key terms exactly as written, do not add " +
                        "outside knowledge or invent terms.$antiCot\n\n" +
                        dedupeLines(sectionSummaries.toString()).take(11000), 1500
                ).collect { sb2.append(it) }
                var finalText = stripThinking(sb2.toString()).trim()
                // if the model derailed (scratchpad / off-topic drivel) fall
                // back to the deduped section summaries - they are detailed
                if (looksDerailed(finalText, sectionSummaries.toString()))
                    finalText = dedupeLines(sectionSummaries.toString()).trim()
                else finalText = dedupeLines(finalText)
                reply.text = "(from $doc)\n\n$finalText"
                adapter.finalizeLast()
                scrollToEnd()
                if (finalText.length > 50) try { cf.writeText(finalText) } catch (e: Exception) { }
                // the summary is cached now - drop the section checkpoint
                try { cpFile.delete() } catch (e: Exception) { }
                needsContextCarry = true
                NovaEngine.reloadAsync(this@MainActivity, settings.systemPrompt)
                try {
                    withContext(Dispatchers.IO) { ChatStore.save(this@MainActivity, currentChat) }
                } catch (e: Exception) { }
                toast("Summary ready - long-press it to make study cards")
            } catch (e: Exception) {
                try {
                    reply.text = "Summary failed - try again"
                    adapter.finalizeLast()
                } catch (x: Exception) { }
                toast("Summary failed - try again")
            } finally {
                generating = false
                setStatus()
                updateSendLook()
            }
        }
    }

    /** Splits a document into ~size-char chunks, breaking at headings
     *  (short standalone lines) and paragraphs, so each chunk is one
     *  topic - section summaries come out matching the document's real
     *  structure instead of arbitrary character cuts. */
    private fun docChunks(doc: String, size: Int = 5000): List<String> {
        val paras = doc.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) { out.add(sb.toString()); sb.setLength(0) }
        }
        for (p in paras) {
            val heading = p.length < 80 && p.lines().size == 1 &&
                !p.endsWith(".") && !p.endsWith("?") && !p.endsWith("!") &&
                !p.startsWith("\u2014 page")
            if (p.length > size) {
                flush()
                var i = 0
                while (i < p.length) {
                    out.add(p.substring(i, minOf(i + size, p.length)))
                    i += size
                }
                continue
            }
            // start a fresh chunk at a heading once the current one is big enough
            if (sb.isNotEmpty() && (sb.length + p.length > size ||
                    (heading && sb.length > size / 2))) flush()
            sb.append(p).append("\n\n")
        }
        flush()
        return out
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
                    if (isPdf) PdfDoc.extractText(this@MainActivity, uri,
                        onProgress = { p, n ->
                            runOnUiThread {
                                status.text = "reading with OCR \u2014 page $p/$n\u2026"
                            }
                        })
                    else readPlainDocument(uri)
                } catch (e: Exception) { "" }
            }
            if (text.isBlank() || text.trim().length < 40) {
                toast(if (isPdf)
                    "Couldn\u2019t read this PDF \u2014 even OCR found no text in it"
                else "NOVA can't read images \u2014 it reads PDF and text files")
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
        return if (text.length > 150_000)
            text.substring(0, 150_000) + "\n[...document truncated]"
        else text
    }

    private fun attachDocument(name: String, text: String) {
        docName = name
        docContext = text
        docInjected = false
        docInjectedText = ""
        updateDocBanner()
        val opts = arrayOf("Summarize it", "Key points", "Explain simply", "Quiz me", "Read aloud", "I'll ask questions")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("${text.length} characters loaded. What should NOVA do with it?")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> summarizeDoc()
                    1 -> runTool("List the key points of this document as short bullets. Group them under 2-4 short headings. Keep all important numbers, names and dates.")
                    2 -> runTool("Explain this document in very simple words, like teaching a beginner. Use short sentences and everyday examples.")
                    3 -> runTool("Create a quiz of 10 questions from this material. Format each EXACTLY as:\nQ: the question\nA: the answer\nNo numbering, no other text before or after.")
                    4 -> readDocAloud()
                    5 -> toast("Ask anything about $name — then tap ↑")
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

    // ---------- phone commands (no model needed) ----------

    /**
     * Understands "call X", "text X a message", "set alarm 6:30am",
     * "open YouTube", "on/off torch" and "open whatsapp and say hi to X".
     * Runs them with Android itself and returns true when handled -
     * the model never sees these.
     */
    private fun tryPhoneCommand(text: String): Boolean {
        val t = text.trim()
        // users often prefix commands with filler ("no open...", "hey open...")
        val t2 = t.replaceFirst(Regex("(?i)^(?:no|nah|nop|okay|ok|hey|please)[,!?\\s]+"), "").trim()
        // fuzzy token matching: one typo ("torch of", "flah") still works
        val toks = t2.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        fun near(want: String): Boolean = toks.any { editDistance(it, want) <= 1 }
        val hasOn = near("on")
        val hasOff = near("off")
        val torchWord = (near("torch") || near("flashlight") || near("flash") ||
            (near("light") && (hasOn || hasOff))) && (hasOn || hasOff || toks.size <= 2)
        val netWord = (near("wifi") || near("network") || near("internet") ||
            near("bluetooth") || near("hotspot") || toks.any { it == "data" }) &&
            (hasOn || hasOff) && toks.size <= 5
        val saySend = Regex("(?i)\\b(?:send|say|sending|write|type)\\s+(.+?)\\s+to\\s+([a-z]+)(?:\\s+(?:in|on|via)\\s+whatsapp)?\\s*$").find(t2)
            ?: Regex("(?i)^whatsapp\\s+(.+?)\\s+to\\s+([a-z]+)\\s*$").find(t2)
        val call = Regex("(?i)^(?:nova\\s*,?\\s*)?(?:please\\s+)?(?:call|phone|dial)\\s+(.+)$").find(t2)
        val textCmd = Regex("(?i)^(?:nova\\s*,?\\s*)?(?:text|whatsapp|message)\\s+(\\S+)\\s+(.+)$").find(t2)
        val alarm = Regex("(?i)^(?:nova\\s*,?\\s*)?(?:set\\s+)?(?:an?\\s+)?alarm\\s+(.+)$").find(t2)
        val open = Regex("(?i)^(?:nova\\s*,?\\s*)?open\\s+(.+)$").find(t2)
        when {
            torchWord -> {
                val on = !hasOff
                try {
                    val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    val id = cm.cameraIdList.firstOrNull {
                        cm.getCameraCharacteristics(it).get(
                            android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } ?: cm.cameraIdList.firstOrNull()
                    if (id == null) toast("No flash on this phone")
                    else {
                        cm.setTorchMode(id, on)
                        toast(if (on) "Torch on" else "Torch off")
                    }
                } catch (e: Exception) { toast("Couldn't control the torch") }
                return true
            }
            netWord -> {
                // Android doesn't let apps switch wifi/data - open the panel
                toast("Apps can't switch that from here - opening settings")
                try {
                    startActivity(android.content.Intent(
                        if (near("bluetooth")) android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                        else android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                } catch (e: Exception) { toast("Couldn't open settings") }
                return true
            }
            saySend != null -> {
                var who = saySend.groupValues[2].trim()
                val msg = saySend.groupValues[1].trim()
                val viaWhatsapp = Regex("(?i)whatsapp").containsMatchIn(t2)
                if (who in listOf("her", "him", "them", "it", "me", "my", "you", "us")) {
                    toast("Who is \"$who\"? Try: whatsapp Tannu $msg")
                    return true
                }
                if (!hasContacts()) {
                    requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 4254)
                    toast("Grant contacts access, then say it again")
                    return true
                }
                val number = lookupContact(who)
                if (number == null) toast("Couldn't find '$who' in contacts")
                else {
                    val send = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                        if (viaWhatsapp) setPackage("com.whatsapp")
                        putExtra("sms_body", msg)
                    }
                    try {
                        startActivity(send)
                    } catch (e: Exception) {
                        // no WhatsApp - fall back to the normal messaging app
                        try {
                            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
                                .apply { putExtra("sms_body", msg) })
                        } catch (x: Exception) { toast("No messaging app") }
                    }
                    toast("Message ready for $who - press send")
                }
                return true
            }
            call != null -> {
                val who = call.groupValues[1].trim()
                if (!hasContacts()) {
                    requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 4254)
                    toast("Grant contacts access, then say it again")
                    return true
                }
                val number = lookupContact(who)
                if (number == null) toast("Couldn't find '$who' in contacts")
                else {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                    toast("Calling $who…")
                }
                return true
            }
            textCmd != null -> {
                val who = textCmd.groupValues[1].trim()
                val msg = textCmd.groupValues[2].trim()
                if (!hasContacts()) {
                    requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 4254)
                    toast("Grant contacts access, then say it again")
                    return true
                }
                val number = lookupContact(who)
                if (number == null) toast("Couldn't find '$who' in contacts")
                else {
                    val send = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                        putExtra("sms_body", msg)
                    }
                    try { startActivity(send) } catch (e: Exception) {
                        toast("No messaging app")
                    }
                    toast("Message ready for $who - press send")
                }
                return true
            }
            alarm != null -> {
                val ms = parseReminderTime(alarm.groupValues[1])
                if (ms == null) { toast("Try: set alarm 6:30am"); return true }
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
                try {
                    startActivity(android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, cal.get(java.util.Calendar.HOUR_OF_DAY))
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, cal.get(java.util.Calendar.MINUTE))
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "NOVA")
                    })
                } catch (e: Exception) { toast("No clock app found") }
                return true
            }
            open != null -> {
                val want = open.groupValues[1].trim().lowercase()
                try {
                    val pm = packageManager
                    val apps = pm.queryIntentActivities(
                        android.content.Intent(android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                    val match = apps.firstOrNull {
                        it.loadLabel(pm).toString().lowercase().contains(want)
                    }
                    if (match == null) toast("No app called '$want'")
                    else pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.let {
                        startActivity(it)
                    }
                } catch (e: Exception) { toast("Couldn't open that") }
                return true
            }
            else -> return false
        }
    }

    private fun hasContacts(): Boolean =
        checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun lookupContact(name: String): String? = try {
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI
            .buildUpon().appendPath(name).build()
        contentResolver.query(uri, arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    // ---------- notification digest ----------

    /** "What did I miss?" - summarizes recent notifications privately. */
    private fun missedNotifications() {
        if (!NotifBrain.isEnabled(this)) {
            AlertDialog.Builder(this)
                .setTitle("Read your notifications?")
                .setMessage("NOVA needs notification access to tell you what you missed. " +
                    "Everything is summarized on this phone and never leaves it.")
                .setPositiveButton("Allow") { _, _ ->
                    try {
                        startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (e: Exception) { }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val digest = NotifBrain.digest(this)
        if (digest.isBlank()) {
            toast("No notifications collected yet - try again in a while")
            return
        }
        runTool("These are the phone notifications the user received, oldest first, " +
            "newest last:\n$digest\n\nSummarize what they missed: group by app or topic, " +
            "mention names and what they said, ignore ads and spam. Keep it short and clear.")
    }

    // ---------- write in my style ----------

    /**
     * Builds a prompt that writes like the user: real examples of their own
     * messages are shown to the model as style references.
     */
    private fun stylePrompt(request: String): String? {
        val mine = StringBuilder()
        for (chat in ChatStore.list(this)) {
            for (m in chat.messages) {
                if (m.role == Role.USER && m.text.length in 10..220) {
                    mine.append(m.text).append("\n")
                    if (mine.length > 1400) break
                }
            }
            if (mine.length > 1400) break
        }
        if (mine.length < 300) return null
        return "The user writes like this (real examples of their messages):\n-----\n" +
            "$mine\n-----\nNow write the following IN THE SAME STYLE - same tone, same " +
            "language mix, same habits, first person. Reply with only the text:\n$request"
    }

    /** Dialog: tell NOVA what to write, it writes it like you. */
    private fun writeInMyStyle() {
        if (!NovaEngine.isModelLoaded) { toast("Load a model first"); return }
        val edit = EditText(this).apply {
            hint = "What should NOVA write? (e.g. a reply to my teacher)"
            setHintTextColor(NovaTheme.dim)
            setTextColor(NovaTheme.text)
            textSize = 14f
            setSingleLine(false)
            minLines = 2
            maxLines = 5
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("Write in my style")
            .setMessage("NOVA learns how you write from your own past messages.")
            .setView(edit)
            .setPositiveButton("Write") { _, _ ->
                val req = edit.text.toString().trim()
                if (req.isEmpty()) return@setPositiveButton
                val sp = stylePrompt(req)
                if (sp == null) {
                    toast("Chat with NOVA a bit more first, so it can learn how you write")
                    return@setPositiveButton
                }
                startGeneration(sp, req)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- document read-aloud ----------

    /** Reads the attached document aloud, sentence by sentence. */
    private fun readDocAloud() {
        val doc = docContext ?: return
        if (!ttsReady || tts == null) { toast("Voice not ready yet - wait a moment"); return }
        readSents = doc.replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }
        if (readSents.isEmpty()) { toast("Nothing to read"); return }
        readIdx = 0
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) { }
            override fun onError(id: String?) { }
            override fun onDone(id: String?) {
                if (id?.startsWith("doc") == true) speakNext()
            }
        })
        speakNext()
        toast("Reading aloud - say \"explain that sentence\" anytime")
    }

    /** Queues the next document sentence (called when the last one ends). */
    private fun speakNext() {
        if (readIdx >= readSents.size) {
            readIdx = 0
            return
        }
        tts?.speak(readSents[readIdx], TextToSpeech.QUEUE_ADD, null, "doc$readIdx")
        readIdx++
    }

    // ---------- side drawer ----------

    /** Exports the whole current conversation as text. */
    private fun shareChat() {
        if (currentChat.messages.isEmpty()) { toast("Nothing to share yet"); return }
        val sb = StringBuilder("NOVA conversation\n\n")
        for (msg in currentChat.messages) {
            sb.append(if (msg.role == Role.USER) "You: " else "NOVA: ").append(msg.text).append("\n\n")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(send, "Share chat"))
    }

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
        docInjectedText = ""
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

    /**
     * Detects "remind me to X at/in TIME" - plus "every day" / "daily" /
     * "every monday" for repeating reminders - and schedules a local
     * notification. Repeating reminders re-arm after each fire.
     */
    private fun maybeSetReminder(text: String) {
        val m = Regex("(?i)\\bremind me\\b(?:\\s+to)?\\s+(.+)").find(text) ?: return
        var s = m.groupValues[1].trim()

        // repeating? "every day", "daily", "every monday"...
        var repeatMs = 0L
        var repeatLabel = ""
        val daily = Regex("(?i)\\b(every\\s*day|everyday|daily)\\b").find(s)
        val weekly = Regex("(?i)\\bevery\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?\\b").find(s)
        val daypart = Regex("(?i)\\bevery\\s+(morning|evening|night)\\b").find(s)
        if (daily != null) {
            repeatMs = 24 * 3_600_000L; repeatLabel = "daily"
            s = s.replace(daily.value, " ")
        } else if (weekly != null) {
            repeatMs = 7 * 24 * 3_600_000L; repeatLabel = "every " + weekly.groupValues[1].lowercase()
            s = s.replace(weekly.value, " ")
        } else if (daypart != null) {
            repeatMs = 24 * 3_600_000L; repeatLabel = "every " + daypart.groupValues[1].lowercase()
            s = s.replace(daypart.value, " ")
        }

        // find the time anywhere in the sentence
        var timeStr: String? = null
        val rel = Regex("(?i)\\bin\\s+(\\d+\\s*\\w+)\\b").find(s)
        val clock = Regex("(?i)\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b|\\b(\\d{1,2}):(\\d{2})\\b|(?<=\\bat\\s)(\\d{1,2})\\b").find(s)
        if (rel != null) timeStr = rel.groupValues[1]
        else if (clock != null) {
            // "tonight at 9" means 9 pm, not 9 am
            val pm = if (Regex("(?i)am|pm|:").containsMatchIn(clock.value)) ""
                else if (s.contains("tonight")) " pm" else ""
            timeStr = clock.value + pm + (if (s.contains("tomorrow")) " tomorrow" else "")
            s = s.replace(clock.value, " ")
        }
        // "every evening" / "every morning" without a clock time
        if (timeStr == null && repeatMs > 0) {
            timeStr = if (daypart != null)
                when (daypart.groupValues[1].lowercase()) {
                    "morning" -> "8am"
                    "evening" -> "7pm"
                    else -> "9pm"
                }
            else "9am"
        } else if (timeStr == null) {
            return
        }

        // whatever is left is the task
        var task = s
        if (rel != null) task = task.replace(rel.value, " ")
        task = task
            .replace(Regex("(?i)\\b(tomorrow|today|tonight)\\b"), " ")
            .replace(Regex("(?i)\\bevery\\s+(morning|evening|night)\\b"), " ")
            .replace(Regex("(?i)\\s+\\bat\\s*$"), "")
            .trim().trim(',', '.', ' ')
            .replace(Regex("(?i)^(at|to)\\s+"), "")
            .trim()
        // strip a leading "to "/"at " repeatedly ("at 6pm to revise sst")
        while (task.length >= 3 &&
            (task.startsWith("to ", true) || task.startsWith("at ", true)))
            task = task.substring(3).trim()
        if (task.isEmpty()) task = "Reminder"
        if (repeatMs > 0) task = task + " (repeats " + repeatLabel + ")"

        var whenMs = parseReminderTime(timeStr) ?: return
        // weekly: move to the next wanted weekday
        if (weekly != null) {
            val want = listOf("sunday", "monday", "tuesday", "wednesday",
                "thursday", "friday", "saturday").indexOf(weekly.groupValues[1].lowercase()) + 1
            if (want >= 0) {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = whenMs
                var diff = (want - cal.get(java.util.Calendar.DAY_OF_WEEK) + 7) % 7
                if (diff == 0 && cal.timeInMillis <= System.currentTimeMillis()) diff = 7
                cal.add(java.util.Calendar.DAY_OF_YEAR, diff)
                whenMs = cal.timeInMillis
            }
        }
        val human = java.text.SimpleDateFormat("EEE, d MMM h:mm a", Locale.getDefault())
            .format(java.util.Date(whenMs))
        AlertDialog.Builder(this)
            .setTitle("Set reminder?")
            .setMessage(task + "\n\n" + human)
            .setPositiveButton("Set") { _, _ ->
                Reminder.schedule(this, whenMs, task, repeatMs)
                toast(if (repeatMs > 0) "Reminder set ($repeatLabel): $human"
                else "Reminder set: $human")
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

// ------------------------------------------------------ document search

private val docStop = setOf("what", "who", "when", "where", "why", "how", "the", "and",
    "for", "are", "was", "were", "is", "does", "did", "do", "with", "about",
    "tell", "explain", "describe", "which", "that", "this", "from", "many",
    "much", "some", "give", "list", "name", "then", "than", "into", "also",
    "page", "please", "according", "document", "pdf", "notes", "show", "gimme",
    "send", "paste", "display", "want", "full", "whole", "actual", "instead")

/**
 * Finds the best parts of a document for a question. Scores EVERY
 * paragraph over the whole document (so matches near the end are found
 * too), weights rare words higher than common ones, matches word
 * beginnings ("photosynth" finds "photosynthesis") and boosts paragraphs
 * with dates / names / numbers for when / who / how-many questions. A
 * question naming a page returns exactly that page.
 */
fun docSearchIn(doc: String, query: String, maxChars: Int): String {
    if (doc.length <= maxChars) return doc
    // "page 12" question - answer from exactly that page
    Regex("(?i)\\bpage\\s+(\\d{1,4})\\b").find(query)?.let { m ->
        val markers = Regex("\u2014 page (\\d+) \u2014").findAll(doc).toList()
        val mi = markers.indexOfFirst { it.groupValues[1] == m.groupValues[1] }
        if (mi >= 0) {
            // a page marker sits AFTER that page's text
            val start = if (mi == 0) 0 else markers[mi - 1].range.last + 1
            var pageText = doc.substring(start, markers[mi].range.first).trim()
            if (pageText.length > maxChars) pageText = pageText.substring(0, maxChars)
            return "(page ${m.groupValues[1]} of the document)\n$pageText"
        }
    }
    val ql = query.lowercase()
    val qw = ql.split(Regex("[^a-z0-9]+"))
        .filter { it.length > 2 && it !in docStop }.toSet()
    if (qw.isEmpty()) return doc.take(maxChars)
    val paras = doc.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
    val lower = paras.map { it.lowercase() }
    // rarity weights: a word that appears in few paragraphs counts more
    val weights = HashMap<String, Double>()
    val prefixes = HashMap<String, Regex>()
    for (w in qw) {
        var c = 0
        for (pl in lower) if (w in pl) c++
        if (c > 0) { weights[w] = 1.0 / c + 0.05; continue }
        if (w.length >= 6) {   // no full hit - match the word beginning instead
            val pre = Regex(Regex.escape(w.substring(0, 5)))
            var pc = 0
            for (pl in lower) if (pre.containsMatchIn(pl)) pc++
            if (pc > 0) { weights[w] = 0.6 / pc + 0.05; prefixes[w] = pre }
        }
    }
    if (weights.isEmpty()) return doc.take(maxChars)
    // question-type routing
    val wantDates = Regex("\\bwhen\\b|\\byear\\b|\\bdate\\b").containsMatchIn(ql)
    val wantNames = Regex("\\bwho\\b|\\bwhom\\b").containsMatchIn(ql)
    val wantNums = Regex("\\bhow many\\b|\\bhow much\\b").containsMatchIn(ql)
    val dateRe = Regex("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b|\\b(18|19|20)\\d{2}\\b")
    val nameRe = Regex("\\b[A-Z][a-z]{2,}\\b")
    val scored = mutableListOf<Pair<Double, Int>>()
    for ((i, p) in paras.withIndex()) {
        val pl = lower[i]
        var sc = 0.0
        for ((w, wt) in weights) {
            val pre = prefixes[w]
            if (pre != null) { if (pre.containsMatchIn(pl)) sc += wt }
            else if (w in pl) sc += wt
        }
        if (sc <= 0.0) continue
        if (p.length < 80) sc *= 1.5            // headings weigh more
        if (wantDates && dateRe.containsMatchIn(p)) sc += 0.5
        if (wantNames && nameRe.findAll(p).take(3).count() >= 2) sc += 0.4
        if (wantNums && p.any { it.isDigit() }) sc += 0.4
        scored.add(sc to i)
    }
    if (scored.isEmpty()) return doc.take(maxChars)
    val best = scored.sortedByDescending { it.first }.take(12).map { it.second }.sorted()
    val out = StringBuilder()
    var last = -2
    for (i in best) {
        if (out.isNotEmpty() && i != last + 1) out.append("[...]\n")
        val p = paras[i]
        if (out.length + p.length > maxChars) break
        out.append(p).append("\n\n")
        last = i
    }
    return out.toString().trim()
}

/**
 * True for table-of-contents / references / index chunks - mostly page
 * references instead of real content. Skipping them makes summaries
 * faster and keeps them on-topic.
 */
fun isJunkChunkText(c: String): Boolean {
    val lines = c.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return true
    if (Regex("(?im)^(table of )?contents$|^references$|^bibliography$|^index$")
            .containsMatchIn(c)) return true
    var refs = 0
    for (l in lines) {
        val t = l.trim()
        if (Regex("\\.{2,}\\s*\\d{1,4}$").containsMatchIn(t) ||       // "Topic .... 12"
            Regex("^\\d{1,4}$").matches(t) ||                             // bare page number
            Regex("^[ivxlcdm]{1,7}$", RegexOption.IGNORE_CASE).matches(t)) refs++
    }
    return refs * 2 > lines.size
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

/**
 * Drops blocks from a continuation that merely repeat content already in
 * the existing reply - the model often restarts a whole section when a
 * cut-off reply is auto-continued. Runs of 3+ lines (or any 60+ char
 * line) that already appear in the old text are removed; genuinely new
 * lines are kept.
 */
private fun dropRepeatedBlocks(old: String, added: String): String {
    val oldSet = HashSet<String>()
    for (l in old.lines()) oldSet.add(l.trim().replace(Regex("\\s+"), " "))
    val out = ArrayList<String>()
    var run = ArrayList<String>()
    fun close(keep: Boolean) {
        if (keep) out.addAll(run)
        run = ArrayList()
    }
    for (raw in added.lines()) {
        val n = raw.trim().replace(Regex("\\s+"), " ")
        if (n.isEmpty() || oldSet.contains(n)) run.add(raw)
        else {
            val big = run.any { it.trim().length >= 60 }
            close(!(run.size >= 3 || big))
            out.add(raw)
        }
    }
    val big = run.any { it.trim().length >= 60 }
    close(!(run.size >= 3 || big))
    return out.joinToString("\n")
}

/**
 * When a reply was cut mid-sentence and the continuation repeats that
 * sentence in full, keep only the complete version: the half line at the
 * end of the old text is dropped.
 */
private fun cutPartialLine(old: String, added: String): String {
    val lines = old.trimEnd().split("\n").toMutableList()
    if (lines.size < 2) return old
    val last = lines.last().trim()
    val firstNew = added.trim().lines().firstOrNull()?.trim() ?: return old
    if (last.length >= 40 && (last.lastOrNull() ?: ' ') !in ".!?\"'*" &&
        (firstNew.startsWith(last) || last.startsWith(firstNew.take(40))))
        lines.removeAt(lines.size - 1)
    return lines.joinToString("\n")
}

/** Last ~300 chars of a progress text, starting at a word boundary
 *  so the first word is not cut in half ("chieving independence"). */
private fun tail300(t: String): String {
    val tail = t.takeLast(300)
    if (t.length <= 300) return tail
    val i = tail.indexOfFirst { it == ' ' || it == '\n' }
    return if (i >= 0) tail.substring(i + 1) else tail
}

/** True when a final summary derailed: too short, scratchpad "Step 1:"
 * style, or almost no keyword overlap with the source summaries (the model
 * wandered off-topic - typically a context-window overflow). */
private fun looksDerailed(t: String, source: String): Boolean {
    if (t.length < 30) return true
    if (Regex("(?i)step\\s*[0-9]+\\s*[:.]").containsMatchIn(t)) return true
    val src = source.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 4 }.toHashSet()
    val out = t.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 4 }
    if (src.isEmpty() || out.size < 10) return false
    val hit = out.count { it in src }
    return hit * 10 < out.size * 3
}

/**
 * Removes repeated lines from a summary - tiny 1B models often restate
 * the same sentence in several section summaries. Exact repeats are
 * always dropped; longer lines that share >= 65% of their words with an
 * earlier line are dropped too.
 */
/**
 * v5.4: detects a derailed CHAT answer - scratchpad steps or text with
 * almost nothing in common with the question and its notes.
 */
private fun chatDerailed(t: String, source: String): Boolean {
    if (t.length < 25) return false
    if (Regex("(?i)step [0-9]+[.:]").containsMatchIn(t)) return true
    // overlap only makes sense against a notes-rich (grounded) prompt;
    // a plain short question shares too few words with any good answer
    if (source.length < 400) return false
    val src = source.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 4 }.toHashSet()
    val out = t.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 4 }
    if (src.isEmpty() || out.size < 12) return false
    val hit = out.count { it in src }
    return hit * 10 < out.size * 2
}

private fun dedupeLines(t: String): String {
    val seen = ArrayList<Set<String>>()
    val out = ArrayList<String>()
    for (raw in t.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) { out.add(""); continue }
        val words = line.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 3 }.toSet()
        var dup = false
        if (line.length >= 40 && words.size >= 4) {
            for (prev in seen) {
                var inter = 0
                for (w in words) if (w in prev) inter++
                val j = inter.toDouble() / (words.size + prev.size - inter)
                // near-repeat: most of this line's words already appeared
                val contained = inter.toDouble() / words.size
                if (j >= 0.65 || (words.size >= 6 && contained >= 0.6)) { dup = true; break }
            }
        } else if (out.any { it.trim() == line }) {
            dup = true
        }
        if (!dup) {
            out.add(raw)
            if (line.length >= 40 && words.size >= 4) seen.add(words)
        }
    }
    return out.joinToString("\n")
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
                "Make it sound like me" to "",
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
