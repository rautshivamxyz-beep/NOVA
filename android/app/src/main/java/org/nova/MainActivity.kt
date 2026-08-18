package org.nova

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File

class MainActivity : Activity() {

    private lateinit var engine: InferenceEngine
    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        loadBuiltInModel()
    }

    private fun buildUI() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 24, 24, 24)

        val title = TextView(this)
        title.text = "🤖 NOVA"
        title.textSize = 28f
        title.gravity = Gravity.CENTER

        status = TextView(this)
        status.text = "Loading Llama 3.2 1B..."
        status.gravity = Gravity.CENTER

        chat = LinearLayout(this)
        chat.orientation = LinearLayout.VERTICAL

        val scroll = ScrollView(this)
        scroll.addView(chat)

        input = EditText(this)
        input.hint = "Message NOVA..."
        input.setSingleLine(true)

        send = Button(this)
        send.text = "SEND"
        send.isEnabled = false

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.addView(input, LinearLayout.LayoutParams(0, 70, 1f))
        bottom.addView(send, LinearLayout.LayoutParams(130, 70))

        root.addView(title, LinearLayout.LayoutParams(-1, 70))
        root.addView(status, LinearLayout.LayoutParams(-1, 50))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottom)

        setContentView(root)

        send.setOnClickListener {
            val message = input.text.toString().trim()
            if (message.isNotEmpty()) {
                input.text.clear()
                sendMessage(message)
            }
        }
    }

    private fun loadBuiltInModel() {
        scope.launch {
            try {
                status.text = "🟡 Loading Llama 3.2 1B..."

                val modelFile = withContext(Dispatchers.IO) {
                    val file = File(filesDir, "llama.gguf")

                    if (!file.exists()) {
                        assets.open("llama-3.2-1b-instruct-q4_k_m.gguf").use { input ->
                            file.outputStream().use { output ->
                                input.copyTo(output, 1024 * 1024)
                            }
                        }
                    }

                    file
                }

                val aiChat =
                    AiChat::class.java.getField("INSTANCE").get(null) as AiChat

                engine = aiChat.getInferenceEngine(this@MainActivity)

                engine.loadModel(modelFile.absolutePath)

                engine.setSystemPrompt(
                    "You are NOVA, a friendly offline AI assistant. " +
                    "Give short, natural answers. " +
                    "Remember the conversation during this session."
                )

                status.text = "🟢 NOVA ready • Offline"

                send.isEnabled = true

                addMessage(
                    "NOVA",
                    "Hello! I'm NOVA. I'm ready."
                )

            } catch (e: Exception) {
                status.text = "🔴 Model failed"
                addMessage("ERROR", e.message ?: e.toString())
            }
        }
    }

    private fun sendMessage(message: String) {
        if (!::engine.isInitialized) return

        addMessage("You", message)

        send.isEnabled = false
        status.text = "🟡 Thinking..."

        scope.launch {
            try {
                var answer = ""

                engine.sendUserPrompt(message, 128).collect { token ->
                    answer += token
                }

                addMessage("NOVA", answer)
                status.text = "🟢 NOVA ready • Offline"

            } catch (e: Exception) {
                addMessage("ERROR", e.message ?: e.toString())
                status.text = "🔴 Generation error"
            } finally {
                send.isEnabled = true
            }
        }
    }

    private fun addMessage(sender: String, message: String) {
        val text = TextView(this)
        text.text = "$sender\n$message"
        text.textSize = 17f
        text.setTextColor(Color.BLACK)
        text.setPadding(18, 14, 18, 14)

        chat.addView(text)
    }

    override fun onDestroy() {
        if (::engine.isInitialized) {
            engine.cleanUp()
        }

        scope.cancel()
        super.onDestroy()
    }
}
