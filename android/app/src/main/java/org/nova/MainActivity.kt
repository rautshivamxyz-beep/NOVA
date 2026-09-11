package org.nova

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*
import android.content.Context
import android.view.inputmethod.InputMethodManager
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

    private fun box(color: String, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = radius
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.parseColor("#080B10")
        window.navigationBarColor = Color.parseColor("#080B10")

        createUI()
        loadModel()
    }

    private fun createUI() {

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#080B10"))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(18, 16, 14, 12)

        val logo = TextView(this)
        logo.text = "✦"
        logo.textSize = 32f
        logo.setTextColor(Color.parseColor("#60A5FA"))
        logo.gravity = Gravity.CENTER

        val titleBox = LinearLayout(this)
        titleBox.orientation = LinearLayout.VERTICAL
        titleBox.setPadding(12, 0, 0, 0)

        val title = TextView(this)
        title.text = "NOVA"
        title.textSize = 24f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Color.WHITE)

        status = TextView(this)
        status.text = "Starting..."
        status.textSize = 12f
        status.setTextColor(Color.parseColor("#7D8797"))

        titleBox.addView(title)
        titleBox.addView(status)

        val clear = Button(this)
        clear.text = "CLEAR"
        clear.textSize = 11f
        clear.setTextColor(Color.WHITE)
        clear.background = box("#1A202A", 18f)

        clear.setOnClickListener {
            chat.removeAllViews()
            addMessage("NOVA", "Chat cleared. I'm ready.")
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(48, 60)
        )

        header.addView(
            titleBox,
            LinearLayout.LayoutParams(0, 60, 1f)
        )

        header.addView(
            clear,
            LinearLayout.LayoutParams(82, 46)
        )

        root.addView(header)

        val scroll = ScrollView(this)

        chat = LinearLayout(this)
        chat.orientation = LinearLayout.VERTICAL
        chat.setPadding(12, 8, 12, 20)

        scroll.addView(chat)

        root.addView(
            scroll,
            LinearLayout.LayoutParams( -1, 0, 1f)
        )

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.setPadding(10, 8, 10, 12)
        bottom.setBackgroundColor(Color.parseColor("#0E131A"))

        input = EditText(this)
        input.hint = "Message NOVA..."
        input.setHintTextColor(Color.parseColor("#697386"))
        input.setTextColor(Color.WHITE)
        input.textSize = 16f
        input.setSingleLine(true)
        input.setPadding(18, 0, 18, 0)
        input.background = box("#1A202A", 28f)

        send = Button(this)
        send.text = "➤"
        send.textSize = 21f
        send.typeface = Typeface.DEFAULT_BOLD
        send.setTextColor(Color.WHITE)
        send.background = box("#2563EB", 28f)
        send.isEnabled = false

        bottom.addView(
            input,
            LinearLayout.LayoutParams(0, 58, 1f)
        )

        val sendParams = LinearLayout.LayoutParams(62, 58)
        sendParams.setMargins(8, 0, 0, 0)

        bottom.addView(send, sendParams)

        root.addView(bottom)

        setContentView(root)

        send.setOnClickListener {

            val message = input.text.toString().trim()

            if (message.isEmpty()) {
                return@setOnClickListener
            }

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

    private fun loadModel() {

        status.text = "Loading Llama 3.2 1B..."

        scope.launch {

            try {

                val aiChat =
                    AiChat::class.java
                        .getField("INSTANCE")
                        .get(null) as AiChat

                engine =
                    aiChat.getInferenceEngine(this@MainActivity)

                val modelFile = java.io.File(
                    filesDir,
                    "llama-3.2-1b-instruct-q4_k_m.gguf"
                )

                if (!modelFile.exists()) {

                    withContext(Dispatchers.IO) {

                        assets.open(
                            "llama-3.2-1b-instruct-q4_k_m.gguf"
                        ).use { source ->

                            java.io.FileOutputStream(
                                modelFile
                            ).use { destination ->

                                source.copyTo(
                                    destination,
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
                    "You are NOVA, a friendly offline AI assistant. " +
                    "Give clear and natural answers."
                )

                status.text = "● READY • OFFLINE"
                status.setTextColor(
                    Color.parseColor("#4ADE80")
                )

                send.isEnabled = true

                addMessage(
                    "NOVA",
                    "Hello! 👋\nI'm ready. Llama 3.2 1B is running offline."
                )

            } catch (e: Exception) {

                status.text = "● MODEL ERROR"
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
            addMessage("NOVA", "I'm still loading the model.")
            return
        }

        addMessage("You", message)

        send.isEnabled = false
        status.text = "● THINKING..."
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

                status.text = "● READY • OFFLINE"
                status.setTextColor(
                    Color.parseColor("#4ADE80")
                )

            } catch (e: Exception) {

                addMessage(
                    "ERROR",
                    e.message ?: e.toString()
                )

                status.text = "● GENERATION ERROR"
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

        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(4, 5, 4, 5)

        val name = TextView(this)
        name.text = sender.uppercase()
        name.textSize = 10f
        name.typeface = Typeface.DEFAULT_BOLD
        name.setPadding(12, 2, 12, 3)

        if (sender == "You") {
            name.setTextColor(Color.parseColor("#60A5FA"))
            name.gravity = Gravity.END
        } else {
            name.setTextColor(Color.parseColor("#94A3B8"))
            name.gravity = Gravity.START
        }

        val bubble = TextView(this)
        bubble.text = message
        bubble.textSize = 16f
        bubble.setTextColor(Color.WHITE)
        bubble.setPadding(18, 14, 18, 14)

        if (sender == "You") {
            bubble.background = box("#2563EB", 22f)
        } else {
            bubble.background = box("#171D26", 22f)
        }

        val params = LinearLayout.LayoutParams(
            -2,
            -2
        )

        if (sender == "You") {
            params.gravity = Gravity.END
        } else {
            params.gravity = Gravity.START
        }

        params.setMargins(8, 0, 8, 0)

        bubble.layoutParams = params

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
