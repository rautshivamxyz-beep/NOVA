package org.nova

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
import org.nova.ncie.android.NcieKnowledge
import org.nova.ncie.android.NcieLearn
import org.nova.ncie.android.NcieSkills
import org.nova.ncie.android.NcieSummary
import org.nova.ncie.android.NovaEngineAdapter
import org.nova.ncie.android.ncieSend
import io.noties.markwon.Markwon
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
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
    internal lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var symRow: android.widget.HorizontalScrollView
    internal val adapter = MessageAdapter()

    init {
        adapter.onContinue = { continueAnswer() }
        adapter.onEditResend = { showEditResend(it) }
        adapter.onTool = { runTool(it) }
        adapter.onRegenerate = { regenerateLast() }
        adapter.onRunJs = { runJs(it) }
    }

    internal lateinit var settings: Settings
    internal lateinit var currentChat: Chat

    /** True when the displayed history is NOT in the engine's context (chat was resumed). */
    internal var needsContextCarry = false

    /** Last memory text injected into this engine context. */
    internal var lastInjectedMemory: String? = null

    /** Last exam line injected into this engine context (null = none). */
    internal var lastInjectedExamKey: String? = null

    /** Guards runaway auto-continues. */
    internal var autoContinueCount = 0

    /** Guards the degenerate-reply retry (one retry per turn). */
    internal var replyRetried = false
    /** v8.2.0: the note chunks that grounded the current answer — set by
     *  ncieSend when the prompt is assembled, read by the LEARN record
     *  when the turn completes. */
    internal var lastAnswerSources: List<String> = emptyList()
    /** v8.4.0 (stage 3): the skill that shaped the current answer, if
     *  any - set by ncieSend at prompt assembly, read by the generation
     *  budget and the LEARN record at turn completion. */
    internal var lastSkillMatched: String? = null
    // v5.4 grounded answers: escape-free newline, source citation, Q&A cache
    internal val NL = 10.toChar().toString()
    internal var pendingCitation: String? = null
    // v5.4.5: notes that fed the last grounded answer in this chat, so
    // keyword-less follow-ups ("explain it in more detail") stay grounded
    internal var lastNotesHit: List<Knowledge.Chunk> = emptyList()

    /** v7.0.0: quick follow-up buttons shown after each answer. */
    private var chipsRow: LinearLayout? = null
    internal var lastNotesChatId: String = ""
    internal var pendingQaKey: String? = null
    /** v9.6.0 "Engine Pack": the Predictive Cache - the exact question
     *  under which the completing turn's final answer is stored
     *  (answer_cache.txt). Set by the normal chat path and by regenerate;
     *  commands, skills and grounded answers never set it. */
    internal var pendingAnswerQ: String? = null

    /** Notes document the user last summarized - "gimme the whole summary" returns to it. */
    internal var lastNotesDoc: String? = null

    /** v9.13.0 "Audit Fixes": the question the online fetch flow already
     *  showed and persisted (OnlineFetch.fetch), or null. The fetch's
     *  completion turn matches on the exact text, so a DIFFERENT message
     *  sent mid-fetch never consumes it, and the flag survives until its
     *  own completion turn runs. */
    internal var offeredQuestionShown: String? = null

    /** Set while a flashcard-generating reply is running. */
    internal var pendingCards = false
    private var pendingAutosend: String? = null

    /** Compressed summary of older turns (auto-compact). */
    internal var compactSummary: String? = null
    internal var compacting = false

    /** Message count at the last auto-compact - throttles re-compaction. */
    private var compactedAtCount = 0

    /** Attached document (PDF / text file) the user can ask about. */
    internal var docName: String? = null
    internal var docContext: String? = null
    internal var docInjected = false
    internal var docInjectedText: String = ""

    /** Document read-aloud state. */
    internal var readSents: List<String> = emptyList()
    internal var readIdx = 0
    private lateinit var docBanner: LinearLayout
    private lateinit var docLabel: TextView
    private lateinit var docBtn: Button

    /** Side drawer. */
    private lateinit var drawerPane: LinearLayout
    private lateinit var drawerScroller: ScrollView
    private lateinit var scrim: View
    private lateinit var drawerList: LinearLayout

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal var generationJob: Job? = null
    private var generating = false
    // v9.0.0 "Voice": NcieVoice owns the engine now - this alias keeps
    // the document read-aloud and the streaming read-aloud on the ONE
    // shared TextToSpeech instance (init/ready/shutdown live in NcieVoice)
    internal var tts: TextToSpeech?
        get() = NcieVoice.tts
        set(value) { NcieVoice.tts = value }

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
        // v9.3.0 "Audit Fixes I": v9.2.0's rolling summary was one
        // global file that leaked between chats. The summary and its
        // counter are keyed per chat now; the old global files are
        // deleted once, here (a no-op on every later run).
        NcieSummary.migrateLegacy(this)

        // v9.4.0 "Audit Fixes II" (audit: a restore killed every alarm):
        // AlarmManager alarms die with a restore or a force-stop, but
        // reminders.json survives both - re-arm every future-dated
        // reminder here, exactly like BootReceiver does after a reboot.
        // Fire times are recoverable from the store ("at" + "rep").
        try {
            val nowMs = System.currentTimeMillis()
            for ((at, t, rep) in ReminderStore.load(this)) {
                val next = when {
                    rep > 0 -> { var n = at; while (n <= nowMs) n += rep; n }
                    at > nowMs -> at
                    else -> continue
                }
                Reminder.schedule(this, next, t, rep)
            }
            // v9.8.0: scheduled texts ride the same re-arm pass - one that
            // came due while NOVA was dead fires now, the nearest future
            // one is re-armed (mirrors what BootReceiver does on boot).
            // v9.13.0 "Audit Fixes": foreground = true - the startup pass
            // runs from a visible Activity, the one caller allowed to open
            // the messaging draft directly (background receivers notify).
            ScheduledSends.fireDue(this, foreground = true)
        } catch (e: Exception) { }

        if (WikiCore.isReady(this)) scope.launch(Dispatchers.IO) {
            WikiCore.warmUp(this@MainActivity)
        }
        // v7.6: warm the notes cache too - the first message of every
        // session otherwise parsed knowledge.json on the main thread
        scope.launch(Dispatchers.IO) { NcieKnowledge.warmUp(this@MainActivity); NcieKnowledge.warmUpNotes(this@MainActivity); NcieSkills.ensure(this@MainActivity) }
        installCrashReporter()
        setContentView(buildUi())
        displayChatMessages()
        // v7.1: welcome brand-new users and point at the model download
        maybeOnboard()
        observeEngine()
        handleSharedText()
        maybeShowCrashReport()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4253)
        }

        // v9.0.0 "Voice": the shared engine for spoken replies and the
        // document read-aloud (replaces the private TextToSpeech instance)
        NcieVoice.init(this)
    }


    override fun onDestroy() {
        super.onDestroy()
        // v9.0.0 "Voice": release the shared engine
        NcieVoice.stop()
        NcieVoice.shutdown(this)
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
            letterSpacing = 0.18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textMain)
        })
        // v8.0.0: the model label wears a chip now
        status = TextView(this).apply {
            text = "starting…"
            textSize = 10.5f
            setTextColor(textDim)
            background = GradientDrawable().apply {
                setColor(NovaTheme.pill)
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        busyDot = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
            visibility = View.GONE
        }
        brandCol.addView(brandRow)
        brandCol.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        header.addView(brandCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(busyDot, FrameLayout.LayoutParams(dp(18), dp(18)).apply {
            rightMargin = dp(10)
        })
        header.addView(roundButton("", textDim).apply {
            setCompoundDrawablesWithIntrinsicBounds(icon(R.drawable.ic_add, textDim), null, null, null)
            setOnClickListener { newConversation() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(7) })
        header.addView(roundButton("", textDim).apply {
            setCompoundDrawablesWithIntrinsicBounds(icon(R.drawable.ic_menu, textDim), null, null, null)
            setOnClickListener { openDrawer() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(View(this).apply { setBackgroundColor(NovaTheme.divider) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))

        // ---- Messages
        messagesRv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = this@MainActivity.adapter
            setPadding(dp(16), dp(12), dp(16), dp(8))
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
                textSize = 42f
                setTextColor(accent)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = "How can I help you today?"
                textSize = 23f
                setTextColor(textMain)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Your private AI. Runs 100% on this phone."
                textSize = 13f
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
                    setPadding(dp(16), 0, dp(16), 0)
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    // v7.9.2: icon on the leading edge, text right after
                    // it - centering the icon+text block pushed every
                    // label off-balance against the left icon
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    background = rippleOverlay(GradientDrawable().apply {
                        setColor(NovaTheme.pill)
                        cornerRadius = dp(26).toFloat()
                        setStroke(dp(1), NovaTheme.border)
                    })
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
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
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
            background = rippleOverlay(null, dp(16).toFloat())
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
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        docBtn = roundButton("", textDim).apply {
            setCompoundDrawablesWithIntrinsicBounds(icon(R.drawable.ic_attach, textDim), null, null, null)
            setOnClickListener { openDocPicker() }
        }
        pill.addView(docBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
            rightMargin = dp(2)
        })
        val symBtn = Button(this).apply {
            text = "√x"; textSize = 12f; isAllCaps = false
            setTextColor(textDim); background = null
            minWidth = 0; minimumWidth = 0
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener {
                symRow.visibility =
                    if (symRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        // v7.9.2: fixed height so the sqrt glyph lines up with the icons
        pill.addView(symBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
            rightMargin = dp(4)
        })
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
            background = rippleOverlay(GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(accent, accentDeep)).apply {
                cornerRadius = dp(21).toFloat()
            })
            setOnClickListener { send() }
        }
        // v7.9.3: one smart button on the right - mic when the input
        // is empty, send when there is text (see updateSendLook)
        pill.addView(sendBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        inputRow.addView(pill, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // v5.6.0: math symbol row - tap a symbol to insert it at the cursor
        symRow = android.widget.HorizontalScrollView(this).apply {
            visibility = View.GONE
            setPadding(dp(8), 0, dp(8), 0)
        }
        val symLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (sym in listOf("π", "√", "²", "³", "½", "×", "÷", "±", "≤", "≥", "≠", "≈", "°", "∑", "θ", "→")) {
            symLine.addView(Button(this).apply {
                text = sym; textSize = 16f; isAllCaps = false
                setTextColor(NovaTheme.text); background = rippleOverlay(null, dp(16).toFloat())
                minWidth = 0; minimumWidth = 0
                setPadding(dp(10), dp(2), dp(10), dp(2))
                setOnClickListener {
                    input.text.insert(input.selectionStart, sym)
                    input.requestFocus()
                }
            })
        }
        symRow.addView(symLine)
        root.addView(symRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // v7.0.0: quick follow-up chips, shown above the input pill
        val chipsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12), 0, dp(12), dp(2))
            visibility = View.GONE
        }
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipsScroll.addView(chipsRow)
        root.addView(chipsScroll, LinearLayout.LayoutParams(
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
            setBackgroundColor(NovaTheme.surface)
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
            setTypeface(typeface, Typeface.BOLD)
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
        // v7.9.1 redesign: the drawer held 13 rows and overflowed small
        // screens - the daily actions stay in the drawer, everything
        // else moved one tap deeper into "More".
        drawerPane.addView(drawerRow("New chat", R.drawable.ic_add) { newConversation() })
        drawerPane.addView(drawerRow("All chats", R.drawable.ic_chat) {
            startActivityForResult(Intent(this@MainActivity, ChatsActivity::class.java), REQ_CHATS)
        })
        drawerPane.addView(drawerRow("Knowledge", R.drawable.ic_doc) {
            startActivity(Intent(this, KnowledgeActivity::class.java))
        })
        // v0.9.2 phase 3: the memory screen lives in the drawer now -
        // one tap from the chat, no separate launcher icon
        drawerPane.addView(drawerRow("Memory", R.drawable.ic_edit) {
            startActivity(Intent(this, MemoryActivity::class.java))
        })
        // v8.5.0: every online fetch, visible and deletable
        drawerPane.addView(drawerRow("Fetches", R.drawable.ic_globe) {
            startActivity(Intent(this, FetchLogActivity::class.java))
        })
        // v8.7.0: the whole local state, saved to and restored from a zip
        drawerPane.addView(drawerRow("Backup", R.drawable.ic_copy) {
            startActivity(Intent(this, BackupActivity::class.java))
        })
        // v9.8.0 "OCR Notes": scan a photo of handwritten notes, fix the
        // recognized text, save it as a Knowledge document - all on-device
        drawerPane.addView(drawerRow("Scan notes", R.drawable.ic_doc) { scanNotes() })
        // v8.8.0: the eyes - notification access; NovaListener logs
        // notifications locally for "what did I miss" and "messages from X"
        drawerPane.addView(drawerRow("Notifications", R.drawable.ic_chat) {
            if (NovaListener.isEnabled(this)) toast("Notification access already on")
            else toast("Grant notification access to NOVA")
            try {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) { }
        })
        // v8.8.0: the update row; v9.7.0 "Delta Updates" - it opens the
        // Update screen now: the private releases page, or apply a
        // downloaded NOVA-delta-*.patch against the installed APK on
        // device and install the result
        drawerPane.addView(drawerRow("Update", R.drawable.ic_refresh) {
            startActivity(Intent(this, UpdateActivity::class.java))
        })
        // v9.1.0: the app lock - set or remove the PIN (only a salted
        // SHA-256 hash is stored, never the PIN); the blocking launch
        // gate itself lives in onResume
        drawerPane.addView(drawerRow("App lock", R.drawable.ic_settings) { appLockDialog() })
        // v9.0.0 "Voice": spoken replies - one persisted toggle right
        // under Update (key "voiceReplies" in the shared nova prefs)
        drawerPane.addView(drawerRow("Voice replies", R.drawable.ic_mic) {
            NcieVoice.enabled = !NcieVoice.enabled
            toast(if (NcieVoice.enabled) "Voice replies on" else "Voice replies off")
        })
        // v9.4.0 "Audit Fixes II" (audit: the speech locale was stuck on
        // the recognizer's default): cycles en-IN -> hi-IN -> phone default
        // (voice_lang.txt deleted). startSpeech() passes the choice as
        // EXTRA_LANGUAGE when the file exists.
        drawerPane.addView(drawerRow("Voice language", R.drawable.ic_mic) {
            cycleVoiceLanguage()
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
        drawerPane.addView(drawerRow("Study plan", R.drawable.ic_lightbulb) {
            startActivity(Intent(this, PlannerActivity::class.java))
        })
        drawerPane.addView(drawerRow("More", R.drawable.ic_globe) {
            closeDrawer()
            AlertDialog.Builder(this)
                .setTitle("More")
                .setItems(arrayOf(
                    "Help & Tips", "Notes filter", "Share chat", "Exams",
                    "What did I miss?", "Write in my style", "Check for updates")) { _, which ->
                    when (which) {
                        0 -> showHelpTips()
                        1 -> showNotesFilter()
                        2 -> shareChat()
                        3 -> startActivity(Intent(this, ExamsActivity::class.java))
                        4 -> missedNotifications()
                        5 -> writeInMyStyle()
                        6 -> checkForUpdates()
                    }
                }
                .show()
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
        // v7.9.1: the pane scrolls now - the drawer outgrew small screens
        drawerScroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            addView(drawerPane, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        frame.addView(drawerScroller, FrameLayout.LayoutParams(dp(304), FrameLayout.LayoutParams.MATCH_PARENT))
        return frame
    }

    // ------------------------------------------------------------- chats

    private fun displayChatMessages() {
        // v9.0.0 "Voice": replaying stored history must not read every
        // old reply aloud
        adapter.suppressSpeak = true
        adapter.clear()
        for (m in currentChat.messages) adapter.add(m)
        adapter.suppressSpeak = false
        if (currentChat.messages.isNotEmpty()) scrollToEnd()
        updateDocBanner()
    }

    private fun newConversation() {
        if (generationJob?.isActive == true) generationJob?.cancel()
        tts?.stop()
        currentChat = ChatStore.newChat()
        settings.currentChatId = currentChat.id
        adapter.clear()
        docName = null; docContext = null; docInjected = false
        compactSummary = null; compactedAtCount = 0
        updateDocBanner()
        if (NovaEngine.isModelLoaded) {
            // v5.4.3 fix: wait for any in-flight generation to stop before
            // reloading - issuing the reload mid-generation made the load
            // appear stuck until the old reply finished in the background
            scope.launch {
                val t0 = android.os.SystemClock.elapsedRealtime()
                while (NovaEngine.isGenerating &&
                    android.os.SystemClock.elapsedRealtime() - t0 < 60_000) delay(200)
                NovaEngine.resetConversationAsync(this@MainActivity, settings.systemPrompt)
            }
        }
        needsContextCarry = false
        toast("New conversation")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7700 && resultCode == RESULT_OK) {
            // v6.3.0: a photo goes to on-device OCR and lands in the chat
            // box; documents keep the old knowledge-base path
            data?.data?.let { uri ->
                val isImage = (contentResolver.getType(uri) ?: "").startsWith("image/")
                if (isImage) ocrImage(uri) else loadSharedDocument(uri)
            }
            return
        }
        // v9.8.0: "Scan notes" - a photo picked for the OCR notes import
        if (requestCode == 7800 && resultCode == RESULT_OK) {
            data?.data?.let { uri -> scanNotesImage(uri) }
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
        // v9.2.1: the phone's voice input app returned the recognized
        // text - put it in the input and send it through the SAME send
        // path the send button uses. RESULT_CANCELED (user backed out
        // of the voice dialog) does nothing.
        if (requestCode == VOICE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.trim()
                if (!heard.isNullOrBlank()) {
                    input.setText(heard)
                    input.setSelection(heard.length)
                    send()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText()
        // v5.4.9: Knowledge screen handed us a command (e.g. Summarize X)
        val auto = intent?.getStringExtra("nova_autosend")
        if (!auto.isNullOrBlank() && !generating) {
            if (NovaEngine.isModelLoaded) { input.setText(auto); send() }
            else pendingAutosend = auto
        }
    }

    private fun openChat(id: String) {
        if (generationJob?.isActive == true) generationJob?.cancel()
        tts?.stop()
        val chat = ChatStore.load(this, id) ?: return
        currentChat = chat
        settings.currentChatId = chat.id
        // v7.6: the previously attached document leaked into the opened chat
        docName = null; docContext = null; docInjected = false
        if (NovaEngine.isModelLoaded) NovaEngine.resetConversationAsync(this, settings.systemPrompt)
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
                // v5.4.9: a handed-off command that waited for the model
                pendingAutosend?.let {
                    if (!generating) { input.setText(it); send() }
                    pendingAutosend = null
                }
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
        // v7.4: keep the input usable without a model - the calculator and
        // phone commands run before any model is needed
        input.isEnabled = !NovaEngine.isLoading
        input.hint = if (ready) "Message NOVA…"
            else if (NovaEngine.isLoading) "Loading model..."
            else "No model - tap the menu (calculator and phone commands work anyway)"
    }

    // -------------------------------------------------------------- chat

    /**
     * True when the model is ready. If it died (e.g. after a stopped reply)
     * it silently restarts it instead of nagging the user.
     */
    internal fun ensureModelReady(): Boolean {
        // v6.2.0: also gate on a pending instant reset
        if (NovaEngine.isModelLoaded && !NovaEngine.resetting) return true
        return when {
            NovaEngine.isLoading -> {
                toast("Model is still loading — one moment"); false
            }
            NovaEngine.resetting -> {
                toast("Resetting conversation — one moment"); false
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
    internal fun docSearch(query: String, maxChars: Int = 4000): String =
        docSearchIn(docContext ?: "", query, maxChars)

    private fun send() {
        // NCIE Stage 5 (#1): the collapsed dispatch. The full routing body
        // - pre-model gates, notes branches, study cards, prompt assembly
        // with memory/exam/notes/wiki injection - lives behind the kernel
        // boundary now: ncie/android/NcieChat.kt (MainActivity.ncieSend).
        // v9.13.0 "Audit Fixes": ncieSend is a SUSPEND function now - its
        // strength-gate embeds hop to Dispatchers.IO (the ONNX embedder,
        // and its 23 MB session init, never runs on the main thread). The
        // body still runs on the main dispatcher (scope), so all UI work
        // is unchanged; the routing flag keeps a second rapid send out
        // while the first is between suspension points.
        if (NcieChat.routing) return
        NcieChat.routing = true
        scope.launch {
            try { ncieSend() } finally { NcieChat.routing = false }
        }
    }

    /**
     * Runs one generation turn. userText == null for internal prompts
     * (chips, auto-continue, edit-resend) - no user bubble is shown.
     * newBubble == false keeps appending to the existing last reply.
     */
    /** v5.6.0: run a JavaScript snippet offline in a WebView and show its
     *  console output - the only language Android executes on-device
     *  without shipping an extra engine. */
    private fun runJs(code: String) {
        val out = StringBuilder()
        val wv = android.webkit.WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                out.append(m.message()).append('\n')
                return true
            }
        }
        val tv = TextView(this).apply {
            text = "running…"
            textSize = 13f; setTextColor(NovaTheme.text)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("JavaScript output")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .show()
        // v7.6: the WebView leaked on every run - destroy it with the dialog
        dlg.setOnDismissListener { try { wv.destroy() } catch (e: Exception) { } }
        val html = "<html><body><script>try{\n" + code + "\n}catch(e){console.log('Error: '+e.message)}</script></body></html>"
        wv.loadData(html, "text/html", "utf-8")
        // WebView renders asynchronously - poll the captured output briefly
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var ticks = 0
        val poll = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                tv.text = if (out.isBlank()) "running…" else out.toString()
                if (ticks++ < 8) h.postDelayed(this, 500)
            }
        }
        h.postDelayed(poll, 400)
    }

    /** v5.5.0: strict mode - grounded answers only, no invented facts. */
    private fun effectivePrompt(p: String): String =
        if (settings.strictMode)
            "STRICT MODE: Answer from the user's notes and the Wikipedia extracts in this " +
                "conversation WHEN they cover the question. If they do not cover it, begin the " +
                "reply with 'From general knowledge (not in your notes):' and answer from your " +
                "own knowledge. Say that phrase once at the start only - never again inside " +
                "the reply. Never invent facts, names, dates or numbers.\n\n" + p
        else p

    /** v6.1.0: ask for LaTeX so formulas render like a textbook. */
    private fun mathPrompt(p: String): String =
        p + "\n(If your answer includes mathematical formulas, write each formula in LaTeX, wrapped in dollar signs.)"

    /** v7.6: strips model-echoed boilerplate - repeated strict-mode
     *  markers (keep only the first) and "The final answer is:" lines. */
    private fun cleanReplyText(s: String): String {
        val out = ArrayList<String>()
        var seenMarker = false
        for (raw in s.lines()) {
            var line = raw
            val lt = line.trim()
            if (Regex("(?i)^the final answer ").containsMatchIn(lt)) {
                val ci = lt.indexOf(':')
                if (ci >= 0) {
                    // "The final answer is: X..." -> keep only the content
                    line = line.substring(line.indexOf(':') + 1).trimStart()
                } else if (lt.length < 90) {
                    continue   // pure announcement line - drop it
                }
            }
            val marker = Regex("(?i)^\\s*From general knowledge \\(not in your notes?\\)\\s*[:：]?\\s*")
            if (marker.containsMatchIn(line)) {
                if (seenMarker) {
                    line = marker.replace(line, "")
                } else {
                    seenMarker = true
                }
            }
            out.add(line)
        }
        return out.joinToString("\n").trim()
    }

    /** v7.3: does this message actually involve maths? If not, the LaTeX
     *  instruction is skipped - jokes and greetings stop coming out in
     *  boxed notation. */
    private fun looksMathy(p: String): Boolean =
        p.any { it.isDigit() } ||
            Regex("(?i)\\b(calc|math|solve|equation|formula|sqrt|prime|percentage|integral|derivative|algebra|geometry)\\b")
                .containsMatchIn(p)

    internal fun startGeneration(prompt: String, userText: String?, newBubble: Boolean = true, plain: Boolean = false) {
        // v7.4: if the user switches chats mid-reply, this generation must
        // never touch the newly opened chat - remember whose reply this is
        val genChat = currentChat
        if (userText != null) {
            val userMsg = Msg(Role.USER, userText)
            currentChat.messages.add(userMsg)
            adapter.add(userMsg)
            // v9.10.0 hardening: persist the question IMMEDIATELY on
            // send. The save used to happen only in the turn-end finally
            // block, so an app kill mid-answer lost the question itself
            // - it was in the UI but never on disk. The turn-end save
            // still runs (it also stores the answer); this one is the
            // parachute.
            scope.launch(Dispatchers.IO) {
                try { ChatStore.save(this@MainActivity, genChat) } catch (e: Exception) { }
            }
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
        NcieChat.generating = true
        setStatus()

        generationJob = scope.launch {
            // v5.4.2: performance timers (time-to-first-token + total)
            val tStart = android.os.SystemClock.elapsedRealtime()
            var tFirstToken = 0L
            // efficiency: batch tokens, redraw + speak ~8x per second
            val pending = StringBuilder()
            var lastFlush = 0L
            fun flush() {
                if (pending.isNotEmpty()) {
                    if (currentChat === genChat) adapter.appendToLast(pending.toString())
                    pending.setLength(0)
                }
            }
            try {
                // v7.3: strict wrapper (and the LaTeX instruction inside
                // mathPrompt) only when relevant - greetings/chit-chat get
                // none of it
                val p2 = if (plain) prompt else effectivePrompt(prompt)
                // v7.6: LaTeX only for real user questions - internal prompts
                // (chips/continue/retry) contain digits and injected notes are
                // full of dates, which made every one of them "mathy"
                val wantMath = !plain && userText != null && looksMathy(userText!!)
                // v0.8.1 (#1): the kernel sizes the generation leash per
                // request — lean turns get half the user cap, everything
                // else the full cap.
                NovaEngineAdapter.stream(if (wantMath) mathPrompt(p2) else p2,
                    // v8.4.0 (stage 3): a matched skill is a structured
                    // task - never the lean leash
                    if (lastSkillMatched != null) settings.predictLength
                    else NcieKnowledge.generationBudget(
                        if (!plain && userText != null) userText else null,
                        settings.predictLength))
                    .collect { token ->
                        if (tFirstToken == 0L) tFirstToken = android.os.SystemClock.elapsedRealtime()
                        pending.append(token)
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastFlush >= 250 || pending.length > 400) {
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
                if (currentChat === genChat) adapter.appendToLast(" ⏹")
                speechCancelled = true
                // stopped replies are truncated - never cache them
                pendingQaKey = null
                pendingCitation = null
                pendingAnswerQ = null
            } catch (e: Exception) {
                adapter.appendToLast("\n[error: ${e.message}]")
                // error replies must never be cached as answers
                pendingQaKey = null
                pendingCitation = null
                pendingAnswerQ = null
            } finally {
                flush()
                withContext(Dispatchers.Main) {
                    if (currentChat !== genChat) {
                        // v7.4: a different chat is open now - do not touch
                        // its UI or state; save the partial turn into ITS
                        // chat and stop here
                        generating = false
                        NcieChat.generating = false
                        updateSendLook()
                        setStatus()
                        try {
                            withContext(Dispatchers.IO) { ChatStore.save(this@MainActivity, genChat) }
                        } catch (e: Exception) { }
                        return@withContext
                    }
                    generating = false
                    NcieChat.generating = false
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
                    // v5.4.2: performance readout (v5.4.3: fixed locale)
                    val tEnd = android.os.SystemClock.elapsedRealtime()
                    if (tFirstToken > 0L) {
                        status.text =
                            "first word " + String.format(java.util.Locale.US, "%.1f", (tFirstToken - tStart) / 1000.0) +
                                "s - total " + String.format(java.util.Locale.US, "%.1f", (tEnd - tStart) / 1000.0) + "s"
                        // v7.2.1: pure WRITING speed - measured after the first
                        // word, so slow question/notes reading (prefill) no longer
                        // drags the tok/s down (tokens ~= chars/4)
                        // v7.6: count only what THIS segment wrote -
                        // continuations used the whole bubble and read ~2x
                        val segStart = if (newBubble) 0 else junction.coerceAtMost(replyMsg.text.length)
                        val genChars = stripThinking(replyMsg.text.substring(segStart)).length
                        if (tEnd > tFirstToken && genChars > 60) {
                            val tps = genChars / 4.0 / ((tEnd - tFirstToken) / 1000.0)
                            status.text = status.text.toString() + "  -  ~" +
                                String.format(java.util.Locale.US, "%.1f", tps) + " tok/s"
                        }
                    }
                    // v7.0.0: quick follow-up chips after each completed answer
                    if (newBubble) showFollowUps()
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
                    // v7.6: small models copy the strict-mode marker into
                    // reply after reply and announce "The final answer is:" -
                    // keep the first marker, drop the rest and the announcements
                    val cleaned = cleanReplyText(replyMsg.text)
                    if (cleaned != replyMsg.text) {
                        replyMsg.text = cleaned
                        adapter.setLastText(cleaned)
                    }
                    // v5.4: append the source citation, then cache the answer
                    if (pendingCitation != null && newBubble && userText != null) {
                        val cit = pendingCitation!!
                        pendingCitation = null
                        if (cit.isNotEmpty() && replyMsg.text.isNotBlank()) {
                            replyMsg.text = replyMsg.text.trim() + NL + NL + cit
                            adapter.setLastText(replyMsg.text)
                        }
                    }
                    needsContextCarry = false
                    // show chips the moment the reply ends - before anything
                    // that could fail (storage, voice) gets a chance to skip it
                    val willContinue = newBubble && !speechCancelled &&
                        autoContinueCount < 2 &&
                        shouldAutoContinue(replyMsg.text)
                    // v9.13.0 "Audit Fixes" (HIGH 3): the study-Q cache write
                    // now runs ONLY on the FINAL segment - after willContinue
                    // is known. It used to run before it was computed, so a
                    // token-cap-truncated first segment was cached verbatim
                    // and every re-ask replayed the stub forever, early-
                    // returning before any repair could happen. A turn that
                    // continues is simply never cached (the assembled text
                    // lives on in the chat, and the next fresh ask rewrites
                    // the entry).
                    if (pendingQaKey != null) {
                        val qaKey = pendingQaKey!!
                        pendingQaKey = null
                        if (newBubble && userText != null && !willContinue) {
                            val qaAns = stripThinking(replyMsg.text).trim()
                            if (qaAns.length > 30) try {
                                File(File(filesDir, "summary_cache").apply { mkdirs() },
                                    qaKey).writeText(qaAns)
                            } catch (e: Exception) { }
                        }
                    }
                    // v9.6.0 "Engine Pack": the Predictive Cache write -
                    // the completed turn's final answer under its exact
                    // question (capped, > 4000 chars never stored). Only
                    // when nothing continues the reply, so the cached text
                    // is the whole answer.
                    if (pendingAnswerQ != null && !willContinue) {
                        val ansQ = pendingAnswerQ!!
                        val ans = stripThinking(replyMsg.text).trim()
                        pendingAnswerQ = null
                        if (!speechCancelled && ans.length > 30 && ans.length <= 4000)
                            scope.launch(Dispatchers.IO) {
                                NcieEngines.cacheAnswer(this@MainActivity, ansQ, ans)
                            }
                    }
                    // persist the conversation
                    try {
                        withContext(Dispatchers.IO) { ChatStore.save(this@MainActivity, genChat) }
                    } catch (e: Exception) { }
                    // v7.6: the save above SUSPENDS the Main thread - the
                    // user can switch chats during it. Re-check before
                    // continuing/compacting, or the continuation lands in
                    // (or crashes on) the wrong chat
                    if (currentChat !== genChat) return@withContext
                    // speak whatever is left of the reply
                    if (!speechCancelled) {
                        try { speakNewSentences(stripThinking(replyMsg.text), flush = true) }
                        catch (e: Exception) { }
                    }
                    // v9.0.0 "Voice": spoken replies - the model's answer is
                    // read aloud ONCE, in full, when the turn truly ends.
                    // Continuations keep appending to this same bubble, so
                    // only the final segment (which holds the whole text)
                    // speaks; every streamed chunk before it stays silent
                    // (deterministic replies speak at the display point
                    // in MessageAdapter.add instead).
                    if (!speechCancelled && !willContinue)
                        NcieVoice.speak(stripThinking(replyMsg.text))
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
                        // NCIE v0.7.0 (#1): the turn is complete (no continuation
                        // pending) - hand the final answer to the kernel's LEARN
                        // phase. NcieLearn quality-gates it (blank/leaked/boiler-
                        // plate replies and internal prompts never enter the
                        // cache) and writes asynchronously; nothing blocks here.
                        NcieLearn.record(this@MainActivity, userText, replyMsg.text, lastAnswerSources)
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
                // v6.1.0: numbers normalized so 'published in 1948 /
                // 1951 / 1952 ...' counts as one repeated line
                val key = Regex("\\d+").replace(line, "#")
                val c = (counts[key] ?: 0) + 1
                counts[key] = c
                if (c >= 3) return true
            }
        }
        return false
    }

    /** v0.8.1: live progress for the summarizers — the token stream is
     *  already flowing; this shows it in the reply bubble (throttled to
     *  ~4 updates/s, bounded by tail300) instead of a frozen progress
     *  line. The header names the phase, so the user always knows what
     *  is running. */
    private var lastStreamUi = 0L
    private fun uiProgress(header: String, sb: StringBuilder) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStreamUi < 250) return
        lastStreamUi = now
        adapter.setLastText(header + tail300(sb.toString()))
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
            sendBtn.background = rippleOverlay(GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(accent, accentDeep)).apply {
                cornerRadius = dp(21).toFloat()
            })
            sendBtn.setCompoundDrawablesWithIntrinsicBounds(
                icon(R.drawable.ic_send, Color.WHITE), null, null, null)
            sendBtn.setOnClickListener { send() }
        } else {
            // v7.9.3: empty input turns the button into the mic - one
            // smart button that morphs, like the big chat apps
            sendBtn.background = rippleOverlay(GradientDrawable().apply {
                setColor(NovaTheme.surface)
                setStroke(dp(1), NovaTheme.border)
                cornerRadius = dp(21).toFloat()
            })
            sendBtn.setCompoundDrawablesWithIntrinsicBounds(
                icon(R.drawable.ic_mic, textDim), null, null, null)
            sendBtn.setOnClickListener { startSpeech() }
        }
    }

    /** Runs a hidden-prompt tool action (no duplicate user bubble).
     *  The document text is injected so "summarize this document"
     *  actually has the document to work on. */
    internal fun runTool(prompt: String) {
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
    internal fun summarizeDoc(query: String? = null) {
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
                    val reply = Msg(Role.ASSISTANT,
                        verifySummaryFacts(stripSummaryMeta(cached), doc))
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
        // v9.13.2 "Small Model Honesty": a topic named in the ask
        // scopes the ATTACHED document's sections too - "summarise
        // anne frank" over the open PDF stays on the Anne Frank chapter
        // instead of mixing in monks, Coorg and tea. Top 3 sections or
        // 40% of the document, whichever is smaller (topicChunks); a
        // bare "summarise" with no scoring topic keeps the whole doc.
        if (query != null) chunks = topicChunks(query, chunks).ifEmpty { chunks }
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
        NcieChat.generating = true
        sendBtn.setCompoundDrawablesWithIntrinsicBounds(
            icon(R.drawable.ic_stop, stopColor), null, null, null)
        setStatus()
        scope.launch {
            try {
                var sectionSummaries = StringBuilder()
                var emptyStreak = 0
                for ((i, c) in chunks.withIndex()) {
                    // v7.5.1: 10% overlap with the previous chunk - a fact
                    // sitting on a chunk boundary stays whole somewhere
                    val c2 = if (i > 0) chunks[i - 1].takeLast(650) + NL + c else c
                    // fresh engine every few chunks: once the context fills
                    // the engine silently drops the oldest tokens, which
                    // quietly degrades later sections - reload in batches
                    if (i in 1 until chunks.size && i % 3 == 0 && NovaEngine.contextDirty) {
                        // v7.5.1: instant KV reset instead of a full model
                        // reload from flash - same clean context, sub-second
                        try { NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt) } catch (e: Exception) { }
                    }
                    adapter.setLastText("Summarizing section ${i + 1}/${chunks.size}\u2026\n\n" +
                        tail300(sectionSummaries.toString()))
                    status.text = "summarizing section ${i + 1}/${chunks.size}\u2026"
                    val sb = StringBuilder()
                    try {
                        NovaEngineAdapter.stream(
                            // v7.5: dense fact bullets instead of long sentences - half the
                            // writing time, MORE facts for the final combine to organize
                            "Extract the key facts from this part of a " +
                                "document as a bullet list. One fact per " +
                                "line, short lines. Keep every name, number, " +
                                "date and term exactly as written. No full " +
                                "sentences, no commentary. Only use facts " +
                                "present in the text - never invent:" +
                                "\n-----\n$c2\n-----", 280
                        ).collect { sb.append(it); uiProgress("Summarizing section ${i + 1}/${chunks.size}\u2026\n\n", sb) }
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
                    // v7.5.1: instant KV reset, not a full flash reload
                    try { NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt) } catch (e: Exception) { }
                }
                val sb2 = StringBuilder()
                NovaEngineAdapter.stream(
                    "These are summaries of " +
                        (if (strided) "the main sections of a long document" else "the sections of a document") +
                        ". Write a DETAILED final summary organized topic by topic: for each topic " +
                        "start with a short bold heading line, then 2-4 bullet points (lines " +
                        "starting with \"- \") in full sentences with its names, dates, numbers " +
                        "and terms. Every bullet must be a complete sentence containing " +
                        "at least one date, name, number or term - never a single word. " +
                        "Do not skip any topic. Use only the information given. Use only facts that appear in the TEXT. If you are not sure a fact is in the TEXT, leave it out. Do not add any commentary about the summary itself:" +
                        "\n\n${dedupeLines(sectionSummaries.toString()).take(11000)}", 1500
                ).collect { sb2.append(it); uiProgress("Writing the final summary\u2026\n\n", sb2) }
                var finalText = stripThinking(sb2.toString()).trim()
                // if the model derailed (scratchpad / off-topic drivel) fall
                // back to the deduped section summaries - they are detailed
                if (looksDerailed(finalText, sectionSummaries.toString()))
                    finalText = dedupeLines(sectionSummaries.toString()).trim()
                else finalText = dedupeLines(finalText)
                // v9.13.1: verify every date/number line against the source
                // chunks, and drop trailing model meta-commentary
                finalText = verifySummaryFacts(stripSummaryMeta(finalText),
                    chunks.joinToString("\n\n"))
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
                NovaEngine.resetConversationAsync(this@MainActivity, settings.systemPrompt)
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
                NcieChat.generating = false
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
    internal fun summarizeNotes(doc: String, userText: String, fullDoc: Boolean) {
        if (compacting) { toast("Compressing older messages \u2014 one moment"); return }
        if (generating) { toast("Wait for the current reply to finish"); return }
        if (!ensureModelReady()) return
        val all = NcieKnowledge.docChunks(this, doc)
        // v9.13.2 "Small Model Honesty": a topic named in the query
        // scopes the map-reduce to the sections that score best against
        // its terms (top 3 sections or 40% of the document, whichever is
        // smaller - topicChunks) - "summary a baker from goa" no longer
        // drags monks, Coorg and tea into the baker summary. A bare
        // "summarise <docname>" keeps the whole document (fullDoc).
        val chunks = if (fullDoc) all
                     else topicChunks(userText, all).ifEmpty {
                         NcieKnowledge.bestChunks(this, userText) }
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
                val reply = Msg(Role.ASSISTANT, "(summary of $doc, cached)\n\n" +
                    verifySummaryFacts(stripSummaryMeta(cached), chunks.joinToString("\n\n")))
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
        NcieChat.generating = true
        sendBtn.setCompoundDrawablesWithIntrinsicBounds(
            icon(R.drawable.ic_stop, stopColor), null, null, null)
        setStatus()
        scope.launch {
            try {
                // clean engine first so old topics can't leak in - but skip
                // the reload when the context is already clean (saves seconds)
                if (NovaEngine.contextDirty) {
                    try {
                        NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt)
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
                    // v7.5.1: overlap with the previous section - boundary
                    // facts survive whole in at least one section
                    val c2 = if (i > 0) sections[i - 1].takeLast(260) + NL + c else c
                    if (i < done.size) continue      // already summarized
                    // fresh engine every few sections: once the context fills
                    // the engine silently drops the oldest tokens, which
                    // quietly degrades later sections - reload in batches
                    if (i in 1 until sections.size && i % 6 == 0 && NovaEngine.contextDirty) {
                        // v7.5.1: instant KV reset instead of a full reload
                        try { NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt) } catch (e: Exception) { }
                    }
                    adapter.setLastText("Summarizing section ${i + 1}/${sections.size}\u2026\n\n" +
                        tail300(sectionSummaries.toString()))
                    status.text = "summarizing section ${i + 1}/${sections.size}\u2026"
                    val sb = StringBuilder()
                    try {
                        NovaEngineAdapter.stream(
                            // v7.5: dense fact bullets - see summarizeDoc
                            "Extract the key facts from this part of the notes " +
                                "as a bullet list. One fact per line, short lines. " +
                                "Keep every date, name, number, term and fact " +
                                "stated in the text. Only use facts present - never invent:$antiCot\n-----\n$c2\n-----", 280
                        ).collect { sb.append(it); uiProgress("Summarizing section ${i + 1}/${sections.size}\u2026\n\n", sb) }
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
                    // v7.5.1: instant KV reset, not a full flash reload
                    try { NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt) } catch (e: Exception) { }
                }
                val sb2 = StringBuilder()
                NovaEngineAdapter.stream(
                    "These are section summaries from the notes \"$doc\". Write a DETAILED " +
                        "final study summary. Organize it topic by topic: for each topic " +
                        "start with a short bold heading line, then 2-4 bullet points " +
                        "(lines starting with \"- \") in full sentences with that topic's " +
                        "dates, names, numbers and terms. Cover EVERY topic. Use ONLY what " +
                        "the summaries say. Every bullet must be a complete sentence " +
                        "containing at least one date, name, number or term - never a " +
                        "single word. Copy key terms exactly as written, do not add " +
                        "outside knowledge or invent terms. Use only facts that appear in the TEXT. If you are not sure a fact is in the TEXT, leave it out. Do not add any commentary about the summary itself.$antiCot\n\n" +
                        dedupeLines(sectionSummaries.toString()).take(11000), 1500
                ).collect { sb2.append(it); uiProgress("Writing the final summary\u2026\n\n", sb2) }
                var finalText = stripThinking(sb2.toString()).trim()
                // if the model derailed (scratchpad / off-topic drivel) fall
                // back to the deduped section summaries - they are detailed
                if (looksDerailed(finalText, sectionSummaries.toString()))
                    finalText = dedupeLines(sectionSummaries.toString()).trim()
                else finalText = dedupeLines(finalText)
                // v9.13.1: verify every date/number line against the source
                // sections, and drop trailing model meta-commentary
                finalText = verifySummaryFacts(stripSummaryMeta(finalText),
                    sections.joinToString("\n\n"))
                reply.text = "(from $doc)\n\n$finalText"
                adapter.finalizeLast()
                scrollToEnd()
                if (finalText.length > 50) try { cf.writeText(finalText) } catch (e: Exception) { }
                // the summary is cached now - drop the section checkpoint
                try { cpFile.delete() } catch (e: Exception) { }
                needsContextCarry = true
                NovaEngine.resetConversationAsync(this@MainActivity, settings.systemPrompt)
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
                NcieChat.generating = false
                setStatus()
                updateSendLook()
            }
        }
    }

    /** v9.13.2 "Small Model Honesty": the sections of a document that
     *  score best against the query's terms - top 3 sections or 40% of
     *  the document, whichever is smaller, kept in document order so
     *  the map-reduce reads coherently. A section scores when a query
     *  term appears in it whole-word (the same normalized matching the
     *  retrieval layer uses). Empty when no section scores at all - the
     *  caller then keeps its previous behavior. */
    private fun topicChunks(query: String, all: List<String>): List<String> {
        if (all.isEmpty()) return emptyList()
        val terms = NcieGround.questionTerms(query)
        if (terms.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Int, Int>>()   // (score, chunk index)
        for ((i, c) in all.withIndex()) {
            val norm = " " + c.lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "
            var s = 0
            for (t in terms) if (norm.contains(" " + t + " ")) s++
            if (s > 0) scored.add(s to i)
        }
        if (scored.isEmpty()) return emptyList()
        // ceil(40%) of the document, capped at 3 sections, at least 1
        val k = minOf(3, (all.size * 2 + 4) / 5).coerceAtLeast(1)
        return scored.sortedWith(
            compareByDescending<Pair<Int, Int>> { it.first }.thenBy { it.second })
            .take(k).sortedBy { it.second }.map { all[it.second] }
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
        // v9.6.0 "Engine Pack": the Experience Engine - the user was not
        // satisfied with this answer; the question is logged (capped) so
        // future answers to it try harder. File work off the main thread.
        val expQ = lastUser.text
        scope.launch(Dispatchers.IO) { NcieEngines.logExperience(this@MainActivity, expQ) }
        // v5.4.3 fix: the old answer is still in the engine's context. The old
        // code just re-sent the question, leaving "Q, A, Q" (and a growing
        // pile of duplicates after every regenerate tap) in the context.
        // Instead: reload to a clean engine when needed, then re-send the
        // question WITH the earlier transcript attached.
        if (NovaEngine.isModelLoaded && NovaEngine.contextDirty) {
            needsContextCarry = true
            // v6.2.0: instant context reset (no model reload) - suspend
            // until the engine is clean, then re-send the question.
            scope.launch {
                if (NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt))
                    regenerateFrom(lastUser)
                else toast("Reload failed - try again")
            }
        } else {
            regenerateFrom(lastUser)
        }
    }

    /** v5.4.3: re-asks [lastUser] with the earlier conversation attached,
     *  so regenerating never pollutes the engine context. */
    private fun regenerateFrom(lastUser: Msg) {
        // v9.6.0: regenerating bypasses the Predictive Cache read (this
        // path never consults it) and refreshes its entry at completion.
        pendingAnswerQ = lastUser.text
        // v9.13.0 "Audit Fixes" (HIGH 2): a rejected answer must never
        // replay verbatim on the repeat. The Experience Engine was
        // already logged by regenerateLast (which keeps the study-Q
        // cache and the kernel recall off this question now too); the
        // stale entries themselves are dropped here alongside the
        // Predictive Cache refresh - the study-Q cache file, and the
        // learned answer (fact + cached answer, memory and disk) via
        // the surgical memoryForget.
        try {
            val qaKey = "qa_" + Integer.toHexString(lastUser.text.lowercase().hashCode()) + "_" +
                Integer.toHexString(NovaEngine.activeModelLabel.hashCode())
            File(File(filesDir, "summary_cache").apply { mkdirs() }, qaKey).delete()
        } catch (e: Exception) { }
        NcieLearn.memoryForget(lastUser.text)
        val recent = currentChat.messages.dropLast(1).takeLast(6)
            .joinToString("\n") { m ->
                (if (m.role == Role.USER) "You: " else "NOVA: ") + m.text.take(250)
            }
        val prompt = if (recent.isBlank()) lastUser.text else
            "(Earlier conversation for context:\n$recent\n— end of earlier conversation)\n\n" +
                "New message: ${lastUser.text}\n" +
                "(Reply to the new message directly, even if it starts a completely new topic. Do not repeat the transcript.)"
        startGeneration(prompt, null)
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
        // v7.6: cap the read at 2 MB - readBytes() on a huge shared
        // text file loaded it all into RAM before any limit applied
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { ins ->
                val cap = 2 * 1024 * 1024
                val buf = java.io.ByteArrayOutputStream(64 * 1024)
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() >= cap) break
                }
                buf.toByteArray()
            } ?: return ""
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
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/plain", "image/jpeg", "image/png"))
            }
            startActivityForResult(pick, 7700)
        } catch (e: Exception) {
            toast("No file picker available")
        }
    }

    /** v6.3.0: photo of a question -> on-device OCR -> editable text in the
     *  input box. Printed textbook questions read well; the user can fix
     *  any garbled math symbols in the box before sending. */
    /** v9.13.0 "Audit Fixes" (re-derived): decode a picked image bounded
     *  to ~2048px on its longest edge before ML Kit reads it. A full-
     *  resolution phone photo (up to ~50 MP) used to be decoded whole
     *  inside the recognizer - an easy OOM. inSampleSize halves until
     *  the image fits, which keeps textbook-size print readable. */
    private fun decodeBounded(uri: android.net.Uri, maxDim: Int = 2048): android.graphics.Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) { null }

    private fun ocrImage(uri: android.net.Uri) {
        try {
            val bmp = decodeBounded(uri)
            if (bmp == null) { toast("Couldn't open image"); return }
            val img = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
            val rec = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
            rec.process(img)
                .addOnSuccessListener { t ->
                    rec.close()
                    val txt = t.text.trim().replace(Regex("\n{3,}"), "\n\n")
                    if (txt.isEmpty()) {
                        toast("No readable text in that image")
                        return@addOnSuccessListener
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Text from image")
                        .setMessage(if (txt.length > 400) txt.take(400) + "\n\u2026" else txt)
                        .setPositiveButton("Solve it") { _, _ ->
                            // v6.3.1: send straight away - the preview above
                            // already showed the OCR text
                            input.setText("Solve this step by step:\n\n$txt")
                            send()
                        }
                        .setNegativeButton("Add text") { _, _ ->
                            input.setText(txt)
                            input.setSelection(input.text.length)
                            input.requestFocus()
                        }
                        .show()
                }
                .addOnFailureListener {
                    try { rec.close() } catch (e: Exception) { }
                    toast("Couldn't read image: " + (it.message ?: "error"))
                }
        } catch (e: Exception) {
            toast("Couldn't open image")
        }
    }

    /** v9.8.0 "Scan notes": pick a photo from the gallery (SAF - no
     *  camera permission needed) and import its text as a document. */
    private fun scanNotes() {
        try {
            val pick = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            startActivityForResult(pick, 7800)
        } catch (e: Exception) { toast("No gallery app available") }
    }

    /** v9.8.0: on-device ML Kit OCR -> editable preview -> Knowledge doc.
     *  The user corrects OCR mistakes before anything is saved. */
    private fun scanNotesImage(uri: Uri) {
        try {
            val bmp = decodeBounded(uri)
            if (bmp == null) { toast("Text recognition failed on this image."); return }
            val img = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
            val rec = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
            rec.process(img)
                .addOnSuccessListener { t ->
                    rec.close()
                    val txt = t.text.trim()
                    if (txt.isEmpty()) {
                        toast("No text found in that image.")
                        return@addOnSuccessListener
                    }
                    val edit = EditText(this).apply {
                        setText(txt)
                        setTextIsSelectable(true)
                        minLines = 6
                        gravity = Gravity.TOP or Gravity.START
                        setPadding(dp(20), dp(12), dp(20), dp(12))
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Import as notes?")
                        .setView(edit)
                        .setPositiveButton("Save document") { _, _ ->
                            val finalTxt = edit.text.toString().trim()
                            if (finalTxt.isEmpty()) {
                                toast("No text found in that image.")
                                return@setPositiveButton
                            }
                            val nm = "Scan " + java.text.SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                                .format(java.util.Date())
                            Thread { NcieKnowledge.addDoc(this, nm, finalTxt) }.start()
                            toast("Saved as document — ask me to quiz you on it.")
                        }
                        .setNegativeButton("Discard", null)
                        .show()
                }
                .addOnFailureListener {
                    try { rec.close() } catch (e: Exception) { }
                    toast("Text recognition failed on this image.")
                }
        } catch (e: Exception) {
            toast("Text recognition failed on this image.")
        }
    }

    /** v7.0.0: every NOVA feature explained in one place. */
    private fun showHelpTips() {
        android.app.AlertDialog.Builder(this)
            .setTitle("How to use NOVA")
            .setMessage(("HOW TO CHAT\n" +
                "\u2022 LFM 1.2B Instruct = fast everyday chat. Qwen3 1.7B or LFM Thinking = smarter for study and maths (slower).\n" +
                "\u2022 Tap \u221ax for math symbols, the mic for voice, and NOVA can read answers aloud.\n\n" +
                "PHOTO TO ANSWER\n" +
                "\u2022 Take a photo of a printed question, tap the attach button and pick it. NOVA reads the text on-device and offers Solve it.\n\n" +
                "YOUR NOTES\n" +
                "\u2022 Add PDFs in the Knowledge screen, then ask things like: summarise federalism, or quiz me on power sharing.\n" +
                "\u2022 Strict mode answers from your notes when they cover the topic; otherwise it answers from general knowledge and says so.\n\n" +
                "MATHS\n" +
                "\u2022 Pure calculations like 12*(3+4)/2 or sqrt(144) are computed exactly, instantly.\n" +
                "\u2022 For hard problems switch to Qwen3 1.7B or LFM Thinking first.\n\n" +
                "PHONE COMMANDS (short messages only)\n" +
                "\u2022 torch on / torch off\n" +
                "\u2022 call <name>\n\u2022 text <name> <message>\n\u2022 open whatsapp and say hi to <name>\n" +
                "\u2022 set alarm 6:30am\n\u2022 open youtube / chrome / camera\n" +
                "\u2022 wifi / bluetooth / hotspot (opens settings)").trim())
            .setPositiveButton("Close", null)
            .show()
    }

    /** v7.0.0: tappable follow-ups after each answer - rule-based, so they
     *  appear instantly with no model call. */
    private fun showFollowUps() {
        val line = chipsRow ?: return
        line.removeAllViews()
        val picks = listOf(
            "Explain simply" to "Explain that more simply, like I am 12 years old.",
            "Give an example" to "Give me one clear real-life example of that.",
            "Quiz me" to "Quiz me on this topic with 3 questions, one at a time.",
            "3-point summary" to "Summarize that in exactly 3 short bullet points."
        )
        for ((label, prompt) in picks) {
            line.addView(Button(this).apply {
                text = label; textSize = 12f; isAllCaps = false
                setTextColor(NovaTheme.text)
                minWidth = 0; minimumWidth = 0
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = rippleOverlay(GradientDrawable().apply {
                    setColor(NovaTheme.pill); cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), NovaTheme.border)
                })
                setOnClickListener {
                    (line.parent as? View)?.visibility = View.GONE
                    input.setText(prompt)
                    send()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })
        }
        (line.parent as? View)?.visibility = View.VISIBLE
    }

    /** v7.0.0 (NCIE Stage 2 of #1): pure arithmetic still gets an EXACT
     *  instant answer — but the go/no-go decision now routes through the
     *  NCIE DecisionKernel (org.nova.ncie.android.NcieArithmetic), the
     *  app's first live kernel route. The expression language is byte-for-
     *  byte the one shipped since v7.0.0 (trig in degrees, log base 10,
     *  sqrt, pi/e, unicode operators, comma separators); it moved into
     *  ArithmeticTool, and canHandle only claims text it fully evaluated,
     *  so a TOOL route guarantees a real answer. Word problems still go
     *  to the AI. */
    internal fun solveArithmetic(text: String): Boolean {
        val t = text.trim()
        val shown = NcieArithmetic.solve(t) ?: return false
        val um = Msg(Role.USER, t)
        currentChat.messages.add(um); adapter.add(um)
        val reply = Msg(Role.ASSISTANT,
            "**= $shown**\n\n(Exact calculation - instant and never wrong. Word problems still go to the AI.)")
        currentChat.messages.add(reply); adapter.add(reply)
        scrollToEnd()
        scope.launch(Dispatchers.IO) {
            try { ChatStore.save(this@MainActivity, currentChat) } catch (e: Exception) { }
        }
        return true
    }

    /** v7.1: welcome a brand-new user and point at the model download. */
    private fun maybeOnboard() {
        try {
            val ggufs = ModelCatalog.modelsDir(this)
                .listFiles { f: java.io.File -> f.extension == "gguf" }
            if ((ggufs?.isNotEmpty() == true) || settings.lastModelPath != null) return
            android.app.AlertDialog.Builder(this)
                .setTitle("Welcome to NOVA")
                .setMessage(("NOVA is your private AI. It runs fully offline on this phone - " +
                    "nothing you type ever leaves the device.\n\n" +
                    "First, download a model (about 0.4-0.7 GB - use Wi-Fi):\n\n" +
                    "1. Open the menu (top-left)\n" +
                    "2. Tap Models\n" +
                    "3. Pick LFM 2.5 1.2B Instruct - the fast, smart everyday model\n\n" +
                    "Then just chat. For everything NOVA can do, tap Help & Tips in the menu.").trim())
                .setPositiveButton("Got it") { _, _ -> showHelpTips() }
                .setNegativeButton("Later", null)
                .show()
        } catch (e: Exception) { }
    }

    // ---------- phone commands (no model needed) ----------

    /**
     * Understands "call X", "text X a message", "set alarm 6:30am",
     * "open YouTube", "on/off torch" and "open whatsapp and say hi to X".
     * Runs them with Android itself and returns true when handled -
     * the model never sees these.
     */
    internal fun tryPhoneCommand(text: String): Boolean {
        val t = text.trim()
        // v6.3.1: OCR'd question text ("...the torch is switched off...")
        // must reach the model - phone commands are short typed requests,
        // never long multi-line question text
        // v9.8.0: scheduled texts ("text mom at 6pm remember the cake and
        // pick up my sister too") are naturally longer than immediate
        // commands - they pass the gate, everything else keeps it
        if (t.length > 60 || t.contains('\n')) {
            val longOk = Regex("(?i)^(?:text|message)\\s+\\S+\\s+at\\s+").containsMatchIn(t.trim()) ||
                Regex("(?i)^cancel\\s+(?:the\\s+)?scheduled").containsMatchIn(t.trim())
            if (!longOk) return false
        }
        // users often prefix commands with filler ("no open...", "hey open...")
        // v8.5.2: polite fillers stripped REPEATEDLY - "hey nova can you
        // please open youtube" must still land on the open command
        var t2 = t.trim()
        repeat(5) {
            t2 = t2.replaceFirst(Regex("(?i)^(?:no|nah|nop|okay|ok|hey|hello|hi|" +
                "please|kindly|nova|can\\s+you|can\\s+u|could\\s+you|will\\s+you|" +
                "would\\s+you|i\\s+want\\s+you\\s+to|i\\s+want\\s+to|help\\s+me)[,!?\\s]+"), "").trim()
        }
        // fuzzy token matching: one typo ("torch of", "flah") still works
        val toks = t2.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
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
        // v9.8.0: "text <name> at <time>: <message>" - the scheduled send.
        // The time is matched structurally ("in 20 minutes" / "6pm" /
        // "18:30" / "tomorrow 9am") so the message after it is free text.
        val textSched = Regex("(?i)^(?:nova\\s*,?\\s*)?(?:text|message)\\s+(\\S+)\\s+at\\s+" +
            "((?:in\\s+\\d+\\s*(?:sec(?:ond)?s?|min(?:ute)?s?|hr?s?|hours?)\\b|(?:tomorrow\\s+)?\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?))" +
            "(?::\\s+|\\s+)(.+)$").find(t2)
        // v9.8.0: undo for the scheduled sends
        val cancelSends = Regex("(?i)^cancel\\s+(?:the\\s+)?(?:all\\s+)?(?:my\\s+)?scheduled\\s+(?:texts?|messages?|sends?)$")
            .containsMatchIn(t2)
        // v8.5.2: "wake me up at 6" is the alarm too
        val alarm = Regex("(?i)^(?:nova\\s*,?\\s*)?(?:(?:set\\s+)?(?:an?\\s+)?alarm|wake\\s+me\\s+up(?:\\s+at)?)\\s+(.+)$").find(t2)
        val open = Regex("(?i)^(?:nova\\s*,?\\s*)?open\\s+(.+)$").find(t2)
        // v8.5.0: hands gap-fill - timers and email drafts
        val timer = Regex("(?i)^(?:nova\\s*,?\\s*)?(?:set\\s+)?(?:a\\s+)?timer\\s+(.+)$").find(t2)
        val email = Regex("(?i)^(?:nova\\s*,?\\s*)?(?:send\\s+)?(?:an?\\s+)?email\\s+(?:to\\s+)?(.+)$").find(t2)
        // v8.5.2: volume control - the phone control everyone reaches for first
        val volume = Regex("(?i)^(?:set\\s+)?(?:the\\s+)?volume\\s+(?:to\\s+)?(.+)$").find(t2)
        val phoneHelp = Regex("(?i)what\\s+(?:can|do)\\s+you\\s+(?:do|control)|^phone\\s+commands$|^list\\s+commands").containsMatchIn(t2)
        when {
            // v8.5.2: "what can you do" answered in chat, no model needed
            phoneHelp -> {
                val help = """Here's what I can do - just type it:

Phone:
- open youtube (or any app)
- set alarm 6:30am, wake me up at 6
- timer 10 minutes
- torch on / torch off
- volume up / volume down / volume 50 / volume mute
- wifi on / bluetooth off (opens the panel)
- call mom, text john <message>, whatsapp tannu <message>
- email dad about the trip (drafts it, you review and send)

Study:
- explain <topic>, teach me <chapter>
- quiz me on <topic>, flashcards
- summarize my notes
- remember that <fact>
- what do you know about me"""
                val um = Msg(Role.USER, t)
                currentChat.messages.add(um)
                adapter.add(um)
                val reply = Msg(Role.ASSISTANT, help)
                currentChat.messages.add(reply)
                adapter.add(reply)
                scrollToEnd()
                return true
            }
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
            cancelSends -> {
                // v9.8.0: clears every pending scheduled text and the alarm
                ScheduledSends.clear(this)
                chatCommandReply(t, "Scheduled texts cancelled.")
                return true
            }
            textSched != null -> {
                // v9.8.0 "Scheduled Sends": the user approves the message
                // once, here, at command time - the alarm fires it later
                val who = textSched.groupValues[1].trim()
                val timeStr = textSched.groupValues[2].trim()
                val msg = textSched.groupValues[3].trim()
                val ms = parseScheduledTime(timeStr)
                when {
                    ms == null -> chatCommandReply(t,
                        "I couldn't read that time - try: text $who at 6:30pm get milk")
                    ms < System.currentTimeMillis() - 60_000L -> chatCommandReply(t,
                        "That time has already passed — give me a future time.")
                    else -> {
                        if (!hasContacts()) {
                            requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 4254)
                            toast("Grant contacts access, then say it again")
                            return true
                        }
                        if (lookupContact(who) == null) {
                            toast("Couldn't find '$who' in contacts")
                            return true
                        }
                        ScheduledSends.add(this, ms, who, msg)
                        val human = java.text.SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault())
                            .format(java.util.Date(ms))
                        chatCommandReply(t,
                            "Scheduled: $msg to $who at $human. Say 'cancel scheduled texts' to undo.")
                    }
                }
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
                    // v9.8.0: the send itself moved to NovaSms.openDraft -
                    // the SAME draft path SendReceiver fires scheduled
                    // texts through
                    if (NovaSms.openDraft(this, number, msg))
                        toast("Message ready for $who - press send")
                    else toast("No messaging app")
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
            timer != null -> {
                // v8.5.0: "timer 10 minutes" -> the clock app's timer
                val m = Regex("(?i)(\\d+)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b")
                    .find(timer.groupValues[1])
                if (m == null) { toast("Try: timer 10 minutes"); return true }
                val n = m.groupValues[1].toInt()
                val secs = when (m.groupValues[2].lowercase()[0]) {
                    'h' -> n * 3600
                    'm' -> n * 60
                    else -> n
                }
                try {
                    if (startClockTimer(secs)) toast("Timer set: " + m.value)
                } catch (e: Exception) { toast("No clock app found") }
                return true
            }
            email != null -> {
                // v8.5.0: "email mom about the trip" -> a Gmail draft, the
                // user reviews and sends - NOVA never sees the account
                val rest = email.groupValues[1].trim()
                val about = Regex("(?i)\\s+about\\s+(.+)$").find(rest)
                val subject = about?.groupValues?.get(1)?.trim() ?: "From NOVA"
                val addr = (if (about != null) rest.substringBefore(about.value) else rest).trim()
                val uri = if (addr.contains("@")) "mailto:" + addr else "mailto:"
                try {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(uri)).apply {
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                    })
                    toast("Email drafted - review and send")
                } catch (e: Exception) { toast("No email app found") }
                return true
            }
            volume != null -> {
                val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val arg = volume.groupValues[1].trim().lowercase()
                val stream = android.media.AudioManager.STREAM_MUSIC
                val max = am.getStreamMaxVolume(stream)
                when {
                    arg == "up" || arg.startsWith("increase") || arg.startsWith("louder") ->
                        am.setStreamVolume(stream,
                            (am.getStreamVolume(stream) + max / 10).coerceAtMost(max), 0)
                    arg == "down" || arg.startsWith("decrease") || arg.startsWith("lower") ||
                        arg.startsWith("quieter") ->
                        am.setStreamVolume(stream,
                            (am.getStreamVolume(stream) - max / 10).coerceAtLeast(0), 0)
                    arg == "full" || arg == "max" || arg.startsWith("maximum") ->
                        am.setStreamVolume(stream, max, 0)
                    arg == "mute" || arg == "silent" || arg.startsWith("zero") ->
                        am.setStreamVolume(stream, 0, 0)
                    else -> {
                        val n = Regex("(\\d+)").find(arg)?.groupValues?.get(1)?.toIntOrNull()
                        if (n == null) { toast("Try: volume 50, volume up, volume mute"); return true }
                        am.setStreamVolume(stream, (max * n / 100).coerceIn(0, max), 0)
                    }
                }
                toast("Volume set")
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
        if (!NcieVoice.isReady || tts == null) { toast("Voice not ready yet - wait a moment"); return }
        readSents = doc.replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }
        if (readSents.isEmpty()) { toast("Nothing to read"); return }
        readIdx = 0
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) { }
            // v7.6: one failed utterance used to stall the whole read
            override fun onError(id: String?) {
                if (id?.startsWith("doc") == true) speakNext()
            }
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
            // v7.1: never export hidden thinking-block text
            sb.append(if (msg.role == Role.USER) "You: " else "NOVA: ")
                .append(stripThinking(msg.text).trim()).append("\n\n")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(send, "Share chat"))
    }


    // ---- v7.6.3: in-app update ----

    /** Latest published version from the public download repo, as (version, apkUrl). */
    private fun fetchLatestRelease(): Pair<String, String>? {
        val conn = java.net.URL(
            "https://raw.githubusercontent.com/rautshivamxyz-beep/NOVA-APK/main/version.txt"
        ).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP " + conn.responseCode)
            val tag = conn.inputStream.bufferedReader().readText().trim().removePrefix("v")
            if (tag.isEmpty()) return null
            return Pair(
                tag,
                "https://raw.githubusercontent.com/rautshivamxyz-beep/NOVA-APK/main/NOVA-latest.apk"
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Compares dotted versions: negative if a < b, 0 if equal. */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** Downloads the APK to the cache dir, then launches the system installer. */
    private fun downloadUpdate(apkUrl: String) {
        Toast.makeText(this, "Downloading update...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(cacheDir, "updates")
                    dir.mkdirs()
                    val part = java.io.File(dir, "nova-update.apk.part")
                    val done = java.io.File(dir, "nova-update.apk")
                    java.net.URL(apkUrl).openStream().use { input ->
                        java.io.FileOutputStream(part).use { fs -> input.copyTo(fs) }
                    }
                    if (done.exists()) done.delete()
                    if (!part.renameTo(done)) {
                        part.delete()
                        throw RuntimeException("could not save download")
                    }
                    done
                }
            }
            result.fold(
                { apk ->
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity, "org.nova.fileprovider", apk
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                },
                { e ->
                    Toast.makeText(
                        this@MainActivity,
                        "Download failed: " + (e.message ?: "error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    /** Drawer action: check GitHub for a newer release and offer to install it. */
    private fun checkForUpdates() {
        Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val fetched = withContext(Dispatchers.IO) { runCatching { fetchLatestRelease() } }
            fetched.fold(
                { latest ->
                    val current = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                    if (latest == null || compareVersions(latest.first, current) <= 0) {
                        Toast.makeText(
                            this@MainActivity,
                            "NOVA is up to date (v" + current + ")",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Update available")
                            .setMessage(
                                "NOVA v" + latest.first + " is available (you have v" + current + ").\n\n" +
                                    "Download and install now? The download is about 35 MB."
                            )
                            .setPositiveButton("Download") { _, _ -> downloadUpdate(latest.second) }
                            .setNegativeButton("Later", null)
                            .show()
                    }
                },
                { e ->
                    Toast.makeText(
                        this@MainActivity,
                        "Update check failed: " + (e.message ?: "network error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun drawerRow(label: String, iconRes: Int, onClick: () -> Unit): View =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(NovaTheme.text)
            background = rippleOverlay(GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
            }, dp(14).toFloat())
            setPadding(dp(4), dp(12), dp(4), dp(12))
            compoundDrawablePadding = dp(14)
            if (iconRes != 0)
                setCompoundDrawablesWithIntrinsicBounds(icon(iconRes, NovaTheme.dim), null, null, null)
            setOnClickListener { closeDrawer(); onClick() }
        }

    private fun openDrawer() {
        refreshDrawer()
        drawerScroller.visibility = View.VISIBLE
        drawerPane.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()
        drawerScroller.translationX = -dp(304).toFloat()
        drawerScroller.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        if (scrim.visibility != View.VISIBLE) return
        scrim.animate().alpha(0f).setDuration(180)
            .withEndAction { scrim.visibility = View.GONE }.start()
        drawerScroller.animate().translationX(-drawerScroller.width.toFloat()).setDuration(200)
            .withEndAction {
                drawerPane.visibility = View.GONE
                drawerScroller.visibility = View.GONE
            }.start()
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
        if (NovaEngine.isModelLoaded) NovaEngine.resetConversationAsync(this, settings.systemPrompt)
        displayChatMessages()
    }

    override fun onResume() {
        super.onResume()
        // v9.1.0: the PIN gate - covers launch (onCreate is always
        // followed by onResume) and every return to the app; the
        // process latch keeps it from re-asking mid-session
        maybePinLock()
        if (appliedTheme.isNotEmpty() && settings.theme != appliedTheme) {
            recreate()
            return
        }
        restoreLastModel()
    }

    private fun speakNewSentences(full: String, flush: Boolean) {
        if (!settings.readAloud || !NcieVoice.isReady || tts == null) return
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

    /** v9.2.1 "Voice": tap-to-talk hands off to the phone's own voice
     *  input app via ACTION_RECOGNIZE_SPEECH. Works on ROMs with no
     *  bundled SpeechRecognizer service, needs no RECORD_AUDIO
     *  permission from NOVA (the external app holds the mic), and
     *  stays fully local - the recognized text lands in the input and
     *  goes through the SAME send path as the send button. */
    private fun startSpeech() {
        if (generating) return
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to NOVA")
                // v9.4.0: the chosen voice language, when one is set
                try {
                    val lf = File(filesDir, "voice_lang.txt")
                    if (lf.exists()) {
                        val l = lf.readText().trim()
                        if (l.isNotEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, l)
                    }
                } catch (e: Exception) { }
            }, VOICE_REQUEST_CODE)
        } catch (e: ActivityNotFoundException) {
            toast("No voice input app found on this phone")
        }
    }

    /**
     * v9.4.0 "Audit Fixes II": cycles the recognition language
     * en-IN -> hi-IN -> phone default. The choice persists in
     * filesDir/voice_lang.txt (deleted = phone default) and is passed
     * to the recognizer in startSpeech().
     */
    private fun cycleVoiceLanguage() {
        val f = File(filesDir, "voice_lang.txt")
        val cur = if (f.exists()) try { f.readText().trim() } catch (e: Exception) { "" } else ""
        val next: String? = when (cur) {
            "" -> "en-IN"
            "en-IN" -> "hi-IN"
            else -> null   // back to the phone default - no file
        }
        try {
            if (next == null) f.delete() else f.writeText(next)
        } catch (e: Exception) { }
        toast("Voice language: " + (next ?: "phone default"))
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
        // v7.6: tell the user instead of silently dropping the share
        if (generating) { toast("A reply is still running - share again when it ends"); return }
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
    /** v5.4.6: pick which documents Knowledge searches - e.g. only the
     *  English PDFs during an English exam, so SST can never leak in. */
    private fun showNotesFilter() {
        val names = NcieKnowledge.docs(this).map { it.first }
        if (names.isEmpty()) { toast("Import notes first (Knowledge screen)"); return }
        val excl = settings.knowledgeExcluded.toMutableSet()
        val checked = names.map { it !in excl }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Search these notes")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                val n = names[which]
                if (isChecked) excl.remove(n) else excl.add(n)
            }
            .setPositiveButton("OK") { _, _ ->
                settings.knowledgeExcluded = excl
                toast("Searching ${names.size - excl.size} of ${names.size} document(s)")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    internal fun maybeAutoRemember(text: String) {
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
        // v9.13.0 "Audit Fixes" (HIGH 6): the compaction stream is a real
        // generation - mirror the process-wide flag here (the other five
        // MainActivity sites already do) so the embedder's pause logic
        // and the sampling deferral are accurate while it runs.
        NcieChat.generating = true
        toast("Compressing older messages to keep replies fast…")
        scope.launch {
            // v7.6: remember which chat this compaction belongs to
            val chatAtStart = currentChat
            val old = currentChat.messages.dropLast(6)
                .joinToString("\n") { m ->
                    (if (m.role == Role.USER) "User: " else "NOVA: ") + m.text.take(250)
                }
            val sb = StringBuilder()
            try {
                NovaEngineAdapter.stream(
                    "Summarize this conversation in one short paragraph. " +
                        "Keep all key facts, decisions, names and numbers:\n\n$old",
                    256
                ).collect { sb.append(it) }
                val summary = stripThinking(sb.toString()).trim()
                // v7.6: user switched chats while the summary was generating -
                // never write the old chat's summary into the new one
                if (summary.length > 40 && currentChat === chatAtStart) {
                    compactSummary = summary
                    compactedAtCount = currentChat.messages.size
                    needsContextCarry = true
                    docInjected = false
                    NovaEngine.resetConversationAsync(this@MainActivity, settings.systemPrompt)
                }
            } catch (e: Exception) {
                // failed - keep full context, retry next turn
            }
            compacting = false
            NcieChat.generating = false
        }
    }

    /**
     * Detects "remind me to X at/in TIME" - plus "every day" / "daily" /
     * "every monday" for repeating reminders - and schedules a local
     * notification. Repeating reminders re-arm after each fire.
     */
    internal fun maybeSetReminder(text: String) {
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
            val pm = if (Regex("(?i)am|pm").containsMatchIn(clock.value)) ""
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
                ReminderStore.add(this, whenMs, task, repeatMs)
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

    /**
     * v9.8.0: time parser for the scheduled sends. "in 20 minutes" and
     * "tomorrow 9am" reuse parseReminderTime's semantics; a bare clock
     * time ("6pm", "18:30") means TODAY, even when that moment already
     * passed - the caller answers honestly instead of silently rolling
     * the send over to tomorrow.
     */
    private fun parseScheduledTime(s: String): Long? {
        val t = s.trim().lowercase()
        if (t.startsWith("in ") || t.contains("tomorrow")) return parseReminderTime(t)
        val tm = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(t) ?: return null
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
        return cal.timeInMillis
    }

    // v9.8.0: phone-command answers that belong in the chat, not a toast
    // (the same reply shape the phone-help branch uses)
    private fun chatCommandReply(userText: String, replyText: String) {
        val um = Msg(Role.USER, userText)
        currentChat.messages.add(um)
        adapter.add(um)
        val reply = Msg(Role.ASSISTANT, replyText)
        currentChat.messages.add(reply)
        adapter.add(reply)
        scrollToEnd()
    }

    internal fun scrollToEnd(force: Boolean = true) {
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
        background = rippleOverlay(GradientDrawable().apply {
            setColor(surface)
            setStroke(dp(1), NovaTheme.border)
            cornerRadius = dp(18).toFloat()
        })
        setPadding(0, 0, 0, 0)
        minWidth = 0
        minimumWidth = 0
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    internal fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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

    // ------------------------------------------- v9.1.0: the app lock

    /** pin.txt - one line, the salted SHA-256 hex of the PIN. */
    private fun pinFile(): File = File(filesDir, "pin.txt")

    private fun pinSet(): Boolean = try { pinFile().exists() } catch (e: Exception) { false }

    private fun pinHashMatches(pin: String): Boolean = try {
        pinFile().readText().trim() == pinHash(pin)
    } catch (e: Exception) { false }

    /** The launch gate: a blocking dialog while a PIN is set and this
     *  process has not unlocked yet. setCancelable(false) means the
     *  back button cannot dismiss it - only the right PIN can. */
    private fun maybePinLock() {
        if (!pinSet() || pinUnlocked) return
        val pinBox = EditText(this).apply {
            hint = "PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val lockDialog = AlertDialog.Builder(this)
            .setTitle("Enter PIN")
            .setView(pinBox)
            .setCancelable(false)
            .setPositiveButton("OK", null)
            .show()
        lockDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (pinHashMatches(pinBox.text.toString())) {
                pinUnlocked = true
                lockDialog.dismiss()
            } else {
                toast("Wrong PIN")
                pinBox.setText("")
            }
        }
    }

    /** The drawer row: set a PIN when none is set, remove the lock
     *  (current PIN required) when one is. */
    private fun appLockDialog() {
        if (!pinSet()) {
            val pinBox = EditText(this).apply {
                hint = "PIN (4-6 digits)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            val confirmBox = EditText(this).apply {
                hint = "Confirm PIN"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(pinBox); addView(confirmBox)
            }
            val setDialog = AlertDialog.Builder(this)
                .setTitle("Set a PIN (4-6 digits)")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .show()
            setDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val p = pinBox.text.toString()
                if (p != confirmBox.text.toString()) {
                    toast("PINs do not match"); return@setOnClickListener
                }
                if (p.length !in 4..6 || !p.all { it.isDigit() }) {
                    toast("PIN must be 4-6 digits"); return@setOnClickListener
                }
                try {
                    pinFile().writeText(pinHash(p) + "\n")
                    pinUnlocked = true
                    toast("App lock on")
                    setDialog.dismiss()
                } catch (e: Exception) { toast("Could not save the PIN") }
            }
        } else {
            val pinBox = EditText(this).apply {
                hint = "Current PIN"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            val removeDialog = AlertDialog.Builder(this)
                .setTitle("Enter current PIN to remove the lock")
                .setView(pinBox)
                .setPositiveButton("Remove", null)
                .setNegativeButton("Cancel", null)
                .show()
            removeDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                if (pinHashMatches(pinBox.text.toString())) {
                    try { pinFile().delete() } catch (e: Exception) { }
                    toast("App lock off")
                    removeDialog.dismiss()
                } else {
                    toast("Wrong PIN")
                    pinBox.setText("")
                }
            }
        }
    }

    /** v9.1.0: the ONE clock-app timer launcher - the phone command
     *  "timer 10 minutes" and NcieRoutines.startStudy both go through
     *  here, so the mechanism and its failure handling stay identical. */
    internal fun startClockTimer(secs: Int): Boolean = try {
        startActivity(android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "NOVA")
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
        })
        true
    } catch (e: Exception) { toast("No clock app found"); false }

    companion object {
        private const val REQ_CHATS = 4252
        // v9.2.1: fresh code for the ACTION_RECOGNIZE_SPEECH handoff -
        // 4251 (old intent flow) and 4254 (RECORD_AUDIO) retired with
        // the direct-SpeechRecognizer code; 4261 collides with nothing
        private const val VOICE_REQUEST_CODE = 4261
        private var crashHandlerInstalled = false
        // v9.1.0 "Automation I": the app lock - pin.txt holds only the
        // salted SHA-256 hex of the PIN (never the PIN), and the
        // process-unlocked latch keeps the launch gate from re-asking
        // inside one session.
        private const val PIN_SALT = "a7f3d09b2e6c4158"
        @Volatile private var pinUnlocked = false

        private fun pinHash(pin: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest((PIN_SALT + pin).toByteArray(Charsets.UTF_8))
            val hex = StringBuilder()
            for (b in bytes) hex.append(String.format("%02x", b))
            return hex.toString()
        }
    }
}

// ------------------------------------------------------ document search

internal val docStop = setOf("what", "who", "when", "where", "why", "how", "the", "and",
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

/** v5.4.5: follow-up questions with no keywords of their own - they mean
 *  "the same notes again", so the last grounded notes are carried forward. */
internal val FOLLOW_UP_Q = Regex("(?i)\\b(explain (it|that|this)|in more detail|more detail|tell me more|explain more|elaborate|go on)\\b")

// v7.3: bare greetings / smalltalk - matched on the WHOLE message
internal val SMALLTALK_REGEX = Regex(
    "(?i)^[\\s']*(hi+|hey+|hello+|yo|sup|namaste|hola|good (morning|afternoon|evening|night)" +
        "|how are (you|u)|how r (you|u)|what'?s up|how'?s it going)[\\s.!~?]*$"
)

// v7.4: the four follow-up chip prompts - recognized so they never hit
// the QA cache (stale-answer replay) or the notes summarizer
internal val CHIP_PROMPTS = setOf(
    "Explain that more simply, like I am 12 years old.",
    "Give me one clear real-life example of that.",
    "Quiz me on this topic with 3 questions, one at a time.",
    "Summarize that in exactly 3 short bullet points."
)
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
 * v9.13.1 "Honest Summaries": deterministic fact verification for study
 * summaries. The LLM only generates - the checks are pure code, fully
 * local, no new dependencies. Every line carrying a factual marker (a
 * 4-digit year, a "June 12"/"12 June" style date, or digits with a unit)
 * is checked against the SOURCE text the summary was built from: the
 * exact marker string must appear there. Unmatched lines are KEPT but
 * flagged as unverified - nothing is silently deleted and the summary
 * is never rewritten by another model pass. If more than half of the
 * marker lines are unverifiable, one warning line is prepended.
 */
private fun verifySummaryFacts(summary: String, source: String): String {
    val ordRe = Regex("(?i)(?<=\\d)(st|nd|rd|th)\\b")
    fun norm(s: String) =
        ordRe.replace(s.lowercase(), "").replace(Regex("\\s+"), " ")
    val src = norm(source)
    val dateRe = Regex("(?i)\\b(?:january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{1,2}\\b|\\b\\d{1,2}\\s+(?:january|february|march|april|may|june|july|august|september|october|november|december)\\b")
    val yearRe = Regex("\\b(?:1[0-9]{3}|20[0-9]{2})\\b")
    val unitRe = Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:%|percent|km|cm|mm|kg|mg|ml|million|billion|thousand|crore|lakh|years?|days?|hours?|minutes?|seconds?|people|students|feet|foot|metres?|meters?|inches?|months?|weeks?)\\b")
    // exact-token containment: the marker must appear in the source
    // with no digit glued to either end, so "august 1" does NOT match
    // inside "august 1944" and "1944" does not match inside "21944"
    fun inSrc(mark: String): Boolean {
        var i = src.indexOf(mark)
        while (i >= 0) {
            val beforeOk = i == 0 || !src[i - 1].isDigit()
            val after = i + mark.length
            val afterOk = after >= src.length || !src[after].isDigit()
            if (beforeOk && afterOk) return true
            i = src.indexOf(mark, i + 1)
        }
        return false
    }
    val out = ArrayList<String>()
    var markerLines = 0
    var unverified = 0
    for (line in summary.lines()) {
        val marks = ArrayList<String>()
        for (m in dateRe.findAll(line)) marks.add(m.value)
        for (m in yearRe.findAll(line)) marks.add(m.value)
        for (m in unitRe.findAll(line)) marks.add(m.value)
        if (marks.isEmpty()) { out.add(line); continue }
        markerLines++
        if (marks.all { inSrc(norm(it)) }) out.add(line)
        else { unverified++; out.add(line.trimEnd() + " (unverified)") }
    }
    var res = out.joinToString("\n")
    if (markerLines > 0 && unverified * 2 > markerLines)
        res = "Warning: many details in this summary could not be matched to your notes \u2014 treat with care.\n\n" + res
    return res
}

/**
 * v9.13.1 "Honest Summaries": deterministic meta-commentary stripper.
 * Tiny models close their study summaries with lines ABOUT the summary
 * ("This summary covers all the key information...", "Note: ...").
 * TRAILING blank/meta lines are dropped, and a leading "(from ...)"
 * label the model echoed is dropped too (the caller adds the real
 * source label). Pure string matching - no model involved, and the
 * "(from <file>)" label itself is kept.
 */
private fun stripSummaryMeta(text: String): String {
    val metaRe = Regex("(?i)this summary covers|providing a comprehensive overview|in summary, this|covers all the key|the summary above")
    val leadRe = Regex("(?i)^\\s*(note|disclaimer)\\s*:")
    val lines = text.lines().toMutableList()
    while (lines.isNotEmpty()) {
        val last = lines[lines.size - 1].trim()
        if (last.isNotEmpty() && !metaRe.containsMatchIn(last) &&
            !leadRe.containsMatchIn(last)) break
        lines.removeAt(lines.size - 1)
    }
    var out = lines.joinToString("\n").trim()
    // a leading "(from ...)" the model echoed - the caller is about to
    // add the real source label, so drop the duplicate
    out = Regex("(?i)^\\(from[^)]*\\)\\s*(?:\\r?\\n)+").replace(out, "")
    return out
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


/** v7.6.5: pressed-state feedback for custom-drawn controls. Wraps any
 *  background (or none) in a bounded ripple tinted with the theme accent;
 *  radiusPx adds a round mask so icon-only buttons ripple in their own
 *  shape instead of a rectangle. */
private fun rippleOverlay(
    bg: android.graphics.drawable.Drawable?,
    radiusPx: Float? = null,
): android.graphics.drawable.RippleDrawable {
    val tint = android.content.res.ColorStateList.valueOf(Color.argb(
        46, Color.red(NovaTheme.accent), Color.green(NovaTheme.accent), Color.blue(NovaTheme.accent)))
    return android.graphics.drawable.RippleDrawable(
        tint, bg,
        if (radiusPx == null) null
        else GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radiusPx })
}

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = mutableListOf<Msg>()
    private var markwon: Markwon? = null
    var onContinue: (() -> Unit)? = null
    var onTool: ((String) -> Unit)? = null
    var onRegenerate: (() -> Unit)? = null
    var onRunJs: ((String) -> Unit)? = null
    var onEditResend: ((Msg) -> Unit)? = null

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
                .usePlugin(JLatexMathPlugin.create(15.5f))
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
        fun actIcon(res: Int) = ctx.getDrawable(res)!!.mutate().apply {
            colorFilter = android.graphics.PorterDuffColorFilter(
                NovaTheme.dim, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        val copyBtn = TextView(ctx).apply {
            background = rippleOverlay(null, dp(ctx, 14).toFloat())
            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 18), dp(ctx, 6))
            setCompoundDrawablesWithIntrinsicBounds(actIcon(R.drawable.ic_copy), null, null, null)
        }
        val regenBtn = TextView(ctx).apply {
            background = rippleOverlay(null, dp(ctx, 14).toFloat())
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
