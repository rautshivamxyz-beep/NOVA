package org.nova

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import kotlinx.coroutines.*

import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.collect

class MainActivity : Activity() {

    private lateinit var engine: InferenceEngine
    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val modelPath =
        "/storage/emulated/0/NOVA/llama-3.2-1b-instruct-q4_k_m.gguf"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildUI()

        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                100
            )
        } else {
            startNOVA()
        }
    }

    private fun buildUI() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 24, 24, 24)

        val title = TextView(this)
        title.text = "🤖 NOVA"
        title.textSize = 28f
        title.gravity = Gravity.CENTER
        title.setTextColor(Color.BLACK)

        status = TextView(this)
        status.text = "Starting..."
        status.textSize = 14f
        status.gravity = Gravity.CENTER
        status.setPadding(0, 10, 0, 15)

        val scroll = ScrollView(this)

        chat = LinearLayout(this)
        chat.orientation = LinearLayout.VERTICAL
        chat.setPadding(8, 8, 8, 8)

        scroll.addView(chat)

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL

        input = EditText(this)
        input.hint = "Message NOVA..."
        input.textSize = 17f
        input.singleLine = true

        send = Button(this)
        send.text = "SEND"
        send.isEnabled = false

        bottom.addView(
            input,
            LinearLayout.LayoutParams(0, 60, 1f)
        )

        bottom.addView(
            send,
            LinearLayout.LayoutParams(
                130,
                60
            )
        )

        root.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                70
            )
        )

        root.addView(
            status,
            LinearLayout.LayoutParams(
                -1,
                50
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

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

    private fun startNOVA() {
        scope.launch {
            try {
                status.text = "Loading Llama 3.2 1B..."

                engine = AiChat.INSTANCE.getInferenceEngine(this@MainActivity)

                engine.setSystemPrompt(
                    "You are NOVA, a helpful offline AI assistant. " +
                    "Answer the user's latest message clearly and concisely."
                )

                engine.loadModel(modelPath)

                status.text = "🟢 NOVA ready — offline"

                send.isEnabled = true

                addMessage(
                    "NOVA",
                    "Hello! I'm NOVA.\nLlama 3.2 1B is ready."
                )

            } catch (e: Exception) {
                status.text = "🔴 Model failed to load"

                addMessage(
                    "ERROR",
                    e.message ?: e.toString()
                )
            }
        }
    }

    private fun sendMessage(message: String) {

        if (!::engine.isInitialized) {
            addMessage("NOVA", "Model is still loading.")
            return
        }

        addMessage("You", message)

        send.isEnabled = false
        status.text = "🟡 NOVA is thinking..."

        scope.launch {

            try {
                var answer = ""

                engine.sendUserPrompt(
                    message,
                    128
                ).collect { token ->

                    answer += token

                    status.text = "🟡 NOVA is generating..."

                }

                if (answer.isEmpty()) {
                    answer = "I'm here."
                }

                addMessage("NOVA", answer)

                status.text = "🟢 NOVA ready — offline"

            } catch (e: Exception) {

                addMessage(
                    "ERROR",
                    e.message ?: e.toString()
                )

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

        val params = LinearLayout.LayoutParams(
            -1,
            -2
        )

        params.setMargins(0, 8, 0, 8)

        chat.addView(text, params)
    }

    override fun onDestroy() {
        super.onDestroy()

        if (::engine.isInitialized) {
            engine.cleanUp()
        }

        scope.cancel()
    }
}
