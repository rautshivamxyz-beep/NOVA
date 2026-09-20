package org.nova

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NOVA — local AI chat.
 *
 * Chat UI on top of the llama.cpp Android binding. Everything runs
 * on-device; nothing leaves the phone.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private val adapter = MessageAdapter()

    private lateinit var settings: Settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var generationJob: Job? = null

    private val bg = Color.parseColor("#080B10")
    private val surface = Color.parseColor("#10151D")
    private val accent = Color.parseColor("#60A5FA")
    private val textMain = Color.parseColor("#E8ECF3")
    private val textDim = Color.parseColor("#7D8797")
    private val stopColor = Color.parseColor("#F87171")

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(buildUi())
        // restoreLastModel() runs in onResume, which fires right after this
    }

    override fun onDestroy() {
        super.onDestroy()
        // The engine (native code + mmap) outlives the Activity instance by
        // design: it's a process-wide singleton. Do not destroy it here so
        // the model stays warm when the screen rotates / app is reopened.
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(0, dp(36), 0, dp(8))
        }

        // ---- Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "✦"
            textSize = 28f
            setTextColor(accent)
        })
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        titleCol.addView(TextView(this).apply {
            text = "NOVA"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            text = "starting…"
            textSize = 12f
            setTextColor(textDim)
        }
        titleCol.addView(status)
        header.addView(titleCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("Models", accent).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, ModelsActivity::class.java)) }
        })
        header.addView(smallButton("New", textDim).apply {
            setOnClickListener { newConversation() }
        })
        header.addView(smallButton("⚙", textDim).apply {
            setOnClickListener { showSettings() }
        })
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- Messages
        messagesRv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = this@MainActivity.adapter
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        root.addView(FrameLayout(this).apply {
            addView(TextView(this@MainActivity).apply {
                text = "No conversation yet.\nTap Models to download an AI, then say hi — fully offline."
                setTextColor(textDim)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(40), dp(80), dp(40), dp(80))
                also { emptyView = it }
            })
            addView(messagesRv)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            private fun refresh() { emptyView.visibility =
                if (adapter.itemCount == 0) View.VISIBLE else View.GONE }
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
        input = EditText(this).apply {
            hint = "Message NOVA…"
            setHintTextColor(textDim)
            setTextColor(textMain)
            textSize = 15f
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(22).toFloat()
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
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
            textSize = 16f
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(24).toFloat()
            }
            setTextColor(Color.parseColor("#080B10"))
            setOnClickListener { send() }
        }
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(
            dp(46), dp(46)).apply { leftMargin = dp(8) })
        root.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return root
    }

    // ------------------------------------------------------------- models

    private fun restoreLastModel() {
        if (NovaEngine.isLoading) {
            // a load started elsewhere (e.g. ModelsActivity) — just refresh UI
            setStatus()
            return
        }
        if (NovaEngine.isModelLoaded) {
            setStatus()
            return
        }
        val path = settings.lastModelPath
        if (path != null && java.io.File(path).exists()) {
            status.text = "loading ${settings.lastModelLabel}…"
            scope.launch {
                try {
                    NovaEngine.load(this@MainActivity, path, settings.lastModelLabel, settings.systemPrompt)
                    setStatus()
                } catch (e: Exception) {
                    status.text = "model failed to load"
                }
            }
        } else {
            setStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        restoreLastModel()
    }

    private fun setStatus(generating: Boolean = false) {
        val label = NovaEngine.activeModelLabel.ifBlank { settings.lastModelLabel }
        status.text = if (label.isBlank()) {
            "no model — open Models"
        } else {
            "$label · ${if (generating) "generating…" else "ready"}"
        }
        input.isEnabled = NovaEngine.isModelLoaded
        input.hint = if (NovaEngine.isModelLoaded) "Message NOVA…" else "Load a model first (Models ↑)"
    }

    // -------------------------------------------------------------- chat

    private fun send() {
        if (generationJob?.isActive == true) {
            // acts as Stop
            sendBtn.text = "➤"
            generationJob?.cancel()
            return
        }
        if (!NovaEngine.isModelLoaded) {
            Toast.makeText(this, "Load a model first — tap Models", Toast.LENGTH_SHORT).show()
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")

        adapter.add(Msg(Role.USER, text))
        scrollToEnd()
        setStatus(generating = true)
        sendBtn.text = "■"
        sendBtn.setTextColor(stopColor)

        val last = Msg(Role.ASSISTANT, "")
        adapter.add(last)
        scrollToEnd()

        generationJob = scope.launch {
            try {
                NovaEngine.send(text, settings.predictLength)
                    .collect { token ->
                        adapter.appendToLast(token)
                        scrollToEnd()
                    }
            } catch (e: CancellationException) {
                adapter.appendToLast(" ⏹")
            } catch (e: Exception) {
                adapter.appendToLast("\n[error: ${e.message}]")
                Toast.makeText(this@MainActivity, "Generation error", Toast.LENGTH_SHORT).show()
            } finally {
                withContext(Dispatchers.Main) {
                    sendBtn.text = "➤"
                    sendBtn.setTextColor(Color.parseColor("#080B10"))
                    setStatus()
                }
            }
        }
    }

    private fun scrollToEnd() {
        if (adapter.itemCount > 0) {
            messagesRv.smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    /**
     * Fresh conversation: reload the model (native side keeps chat history,
     * so reloading is the way to clear it) and wipe the bubbles.
     */
    private fun newConversation() {
        if (generationJob?.isActive == true) {
            generationJob?.cancel()
        }
        scope.launch {
            try {
                val hadModel = NovaEngine.reload(this@MainActivity, settings.systemPrompt)
                if (!hadModel) toast("No model loaded")
            } catch (e: Exception) {
                toast("Reload failed: ${e.message}")
            }
            adapter.clear()
            setStatus()
        }
    }

    // ----------------------------------------------------------- settings

    private fun showSettings() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
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
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        outer.addView(promptEdit)
        outer.addView(TextView(this).apply {
            text = "Max response length"
            setTextColor(textDim)
            textSize = 12f
            setPadding(0, dp(16), 0, dp(6))
        })
        val lengthRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lengthBtns = Settings.LENGTH_OPTIONS.map { tokens -> smallButton("$tokens", textDim) }
        lengthBtns.forEachIndexed { i, b ->
            val tokens = Settings.LENGTH_OPTIONS[i]
            b.setOnClickListener {
                settings.predictLength = tokens
                lengthBtns.forEach { it.setTextColor(textDim) }
                b.setTextColor(accent)
            }
            if (tokens == settings.predictLength) b.setTextColor(accent)
        }
        lengthBtns.forEach { b ->
            lengthRow.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })
        }
        outer.addView(lengthRow)

        AlertDialog.Builder(this)
            .setTitle("NOVA settings")
            .setView(outer)
            .setPositiveButton("Save") { _, _ ->
                val newPrompt = promptEdit.text.toString()
                val changed = newPrompt != settings.systemPrompt
                settings.systemPrompt = newPrompt
                if (changed && NovaEngine.isModelLoaded) {
                    AlertDialog.Builder(this)
                        .setMessage("Apply the new system prompt now? This clears the conversation.")
                        .setPositiveButton("Apply now") { _, _ -> newConversation() }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------- utils

    private fun smallButton(label: String, color: Int): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(color)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), color)
            cornerRadius = dp(20).toFloat()
        }
        setPadding(dp(14), dp(6), dp(14), dp(6))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

// ---------------------------------------------------------------- adapter

enum class Role { USER, ASSISTANT }

class Msg(val role: Role, var text: String)

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = mutableListOf<Msg>()

    fun add(m: Msg) {
        items.add(m)
        notifyItemInserted(items.size - 1)
    }

    fun appendToLast(token: String) {
        if (items.isEmpty()) return
        items[items.size - 1].text += token
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
        val bubble = TextView(ctx).apply {
            textSize = 15f
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        }
        val row = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }
        row.addView(bubble)
        return VH(row, bubble)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.bubble.context
        holder.bubble.text = m.text
        val lp = holder.bubble.layoutParams as FrameLayout.LayoutParams
        if (m.role == Role.USER) {
            (holder.bubble.background as? GradientDrawable)?.setColor(
                Color.parseColor("#1E3A8A")
            ) ?: run {
                holder.bubble.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E3A8A"))
                    cornerRadius = dp(ctx, 18).toFloat()
                }
            }
            holder.bubble.setTextColor(Color.parseColor("#F0F4FB"))
            lp.gravity = Gravity.END
            lp.rightMargin = 0
        } else {
            if (holder.bubble.background == null) {
                holder.bubble.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#141B26"))
                    cornerRadius = dp(ctx, 18).toFloat()
                }
            }
            holder.bubble.setTextColor(Color.parseColor("#E8ECF3"))
            lp.gravity = Gravity.START
        }
        holder.bubble.layoutParams = lp
        holder.bubble.visibility = if (m.text.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    class VH(row: FrameLayout, val bubble: TextView) : RecyclerView.ViewHolder(row)

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
