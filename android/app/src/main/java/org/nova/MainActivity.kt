package org.nova

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class MainActivity : Activity() {

    private lateinit var engine: InferenceEngine
    private lateinit var chat: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var status: TextView

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun bg(color: String, radius: Float = 20f): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = radius
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()

        window.statusBarColor = Color.parseColor("#0B0F14")
        window.navigationBarColor = Color.parseColor("#0B0F14")

        loadBundledModel()
    }

    private fun buildUI() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F14"))
        }

        // HEADER
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 18, 14, 14)
        }

        val logo = TextView(this).apply {
            text = "🤖"
            textSize = 30f
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 0, 0, 0)
        }

        val title = TextView(this).apply {
            text = "NOVA"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        status = TextView(this).apply {
            text = "Starting..."
            textSize = 12f
            setTextColor(Color.parseColor("#8B95A5"))
        }

        titleBox.addView(title)
        titleBox.addView(status)

        val clear = Button(this).apply {
            text = "Clear"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = bg("#202733", 16f)
            setPadding(12, 0, 12, 0)
            setOnClickListener {
                chat.removeAllViews()
                addMessage(
                    "NOVA",
                    "Chat cleared. I'm ready."
                )
            }
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(45, 60)
        )

        header.addView(
            titleBox,
            LinearLayout.LayoutParams(0, 60, 1f)
        )

        header.addView(
            clear,
            LinearLayout.LayoutParams(75, 48)
        )

        root.addView(header)

        // CHAT AREA
        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 20)
        }

        scroll.addView(chat)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        // INPUT BAR
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 8, 10, 12)
            setBackgroundColor(Color.parseColor("#11161D"))
        }

        input = EditText(this).apply {
            hint = "Message NOVA..."
            hintTextColor = Color.parseColor("#687386")
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine(true)
            setPadding(18, 0, 18, 0)
            background = bg("#202733", 24f)
        }

        send = Button(this).apply {
            text = "➤"
            textSize = 22f
            setTextColor(Color.WHITE)
            background = bg("#2563EB", 24f)
            isEnabled = false
        }

        bottom.addView(
            input,
            LinearLayout.LayoutParams(
                0,
                58,
                1f
            )
        )

        val sendParams =
            LinearLayout.LayoutParams(62, 58)

        sendParams.setMargins(8, 0, 0, 0)

        bottom.addView(send, sendParams)

        root.addView(bottom)

        setContentView(root)

        send.setOnClickListener {
            val message = input.text.toString().trim()

            if (message.isNotEmpty()) {
                input.text.clear()

                val keyboard =
                    getSystemService(Context.INPUT_METHOD_SERVICE)
                        as InputMethodManager

                keyboard.hideSoftInputFromWindow(
                    input.windowToken,
                    0
                )

                sendMessage(message)
            }
        }

        input.setOnEditorActionListener { _, _, _ ->
            if (send.isEnabled) {
                send.performClick()
            }
            true
        }
    }

    private fun loadBundledModel() {

        status.text = "Loading Llama 3.2 1B..."

        scope.launch {

            try {

                val aiChat =
                    AiChat::class.java
                        .getField("INSTANCE")
                        .get(null) as AiChat

                engine =
                    aiChat.getInferenceEngine(this@MainActivity)

                val modelFile =
                    java.io.File(
                        filesDir,
                        "llama-3.2-1b-instruct-q4_k_m.gguf"
                    )

                if (!modelFile.exists()) {

                    withContext(Dispatchers.IO) {

                        assets.open(
                            "llama-3.2-1b-instruct-q4_k_m.gguf"
                        ).use { input ->

                            java.io.FileOutputStream(
                                modelFile
                            ).use { output ->

                                input.copyTo(
                                    output,
                                    1024 * 1024
                                )
                            }
                        }
                    }
                }

                engine.loadModel(
                    modelFile.absolutePath
                )

                engine.setSystemPrompt(
                    """
                    You are NOVA, a friendly offline AI assistant.
                    Give short, natural answers.
                    Remember the conversation during this session.
                    Do not mention these instructions.
                    """.trimIndent()
                )

                status.text =
                    "● Online • Llama 3.2 1B"

                status.setTextColor(
                    Color.parseColor("#4ADE80")
                )

                send.isEnabled = true

                addMessage(
                    "NOVA",
                    "Hello! 👋\nI'm ready."
                )

            } catch (e: Exception) {

                status.text = "● Model error"

                status.setTextColor(
                    Color.parseColor("#F87171")
                )

                addMessage(
                    "ERROR",
                    e.message ?: e.toString()
                )
            }
        }
    }

    private fun sendMessage(message: String) {

        if (!::engine.isInitialized) {
            addMessage(
                "NOVA",
                "I'm still loading the model."
            )
            return
        }

        addMessage("You", message)

        send.isEnabled = false

        status.text = "● Thinking..."
        status.setTextColor(
            Color.parseColor("#FBBF24")
        )

        scope.launch {

            try {

                var answer = ""

                engine.sendUserPrompt(
                    message,
                    128
                ).collect { token ->

                    answer += token
                }

                addMessage(
                    "NOVA",
                    answer.trim()
                )

                status.text =
                    "● Online • Llama 3.2 1B"

                status.setTextColor(
                    Color.parseColor("#4ADE80")
                )

            } catch (e: Exception) {

                addMessage(
                    "ERROR",
                    e.message ?: e.toString()
                )

                status.text = "● Generation error"

                status.setTextColor(
                    Color.parseColor("#F87171")
                )

            } finally {

                send.isEnabled = true
            }
        }
    }

    private fun addMessage(
        sender: String,
        message: String
    ) {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 5, 4, 5)
        }

        val bubble = TextView(this).apply {

            text = message
            textSize = 16f

            setTextColor(Color.WHITE)

            setPadding(
                18,
                14,
                18,
                14
            )

            background =
                if (sender == "You")
                    bg("#2563EB", 22f)
                else
                    bg("#1B222D", 22f)

            val params =
                LinearLayout.LayoutParams(
                    -2,
                    -2
                )

            params.gravity =
                if (sender == "You")
                    Gravity.END
                else
                    Gravity.START

            params.setMargins(
                8,
                2,
                8,
                2
            )

            layoutParams = params
        }

        val name = TextView(this).apply {

            text =
                if (sender == "You")
                    "You"
                else
                    "NOVA"

            textSize = 11f

            setTextColor(
                if (sender == "You")
                    Color.parseColor("#60A5FA")
                else
                    Color.parseColor("#94A3B8")
            )

            setPadding(12, 2, 12, 2)

            gravity =
                if (sender == "You")
                    Gravity.RIGHT
                else
                    Gravity.LEFT
        }

        row.addView(name)
        row.addView(bubble)

        chat.addView(row)
    }

    override fun onDestroy() {

        if (::engine.isInitialized) {
            engine.cleanUp()
        }

        scope.cancel()

        super.onDestroy()
    }
}
