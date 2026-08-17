package org.nova

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val PICK_MODEL = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
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
        status.text = "Select your Llama model"
        status.textSize = 15f
        status.gravity = Gravity.CENTER
        status.setPadding(0, 10, 0, 15)

        val choose = Button(this)
        choose.text = "SELECT MODEL"

        chat = LinearLayout(this)
        chat.orientation = LinearLayout.VERTICAL
        chat.setPadding(8, 8, 8, 8)

        val scroll = ScrollView(this)
        scroll.addView(chat)

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL

        input = EditText(this)
        input.hint = "Message NOVA..."
        input.textSize = 17f
        input.setSingleLine(true)

        send = Button(this)
        send.text = "SEND"
        send.isEnabled = false

        bottom.addView(
            input,
            LinearLayout.LayoutParams(0, 60, 1f)
        )

        bottom.addView(
            send,
            LinearLayout.LayoutParams(130, 60)
        )

        root.addView(title,
            LinearLayout.LayoutParams(-1, 70))

        root.addView(status,
            LinearLayout.LayoutParams(-1, 50))

        root.addView(choose,
            LinearLayout.LayoutParams(-1, 60))

        root.addView(scroll,
            LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(bottom)

        setContentView(root)

        choose.setOnClickListener {
            chooseModel()
        }

        send.setOnClickListener {
            val message = input.text.toString().trim()

            if (message.isNotEmpty()) {
                input.text.clear()
                sendMessage(message)
            }
        }
    }

    private fun chooseModel() {

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)

        intent.type = "*/*"

        intent.addCategory(Intent.CATEGORY_OPENABLE)

        startActivityForResult(intent, PICK_MODEL)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_MODEL &&
            resultCode == Activity.RESULT_OK &&
            data?.data != null) {

            val uri = data.data!!

            try {

                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

            } catch (_: Exception) {
            }

            loadSelectedModel(uri)
        }
    }

    private fun loadSelectedModel(uri: Uri) {

        status.text = "Loading Llama 3.2 1B..."

        scope.launch {

            try {

                val aiChat = AiChat::class.java.getField("INSTANCE").get(null) as AiChat
                engine = aiChat.getInferenceEngine(this@MainActivity)

                engine.setSystemPrompt(
                    "You are NOVA, a helpful offline AI assistant. " +
                    "Answer clearly and concisely."
                )

                val path = copyModelToPrivateStorage(uri)

                engine.loadModel(path)

                status.text = "🟢 NOVA ready — offline"

                send.isEnabled = true

                addMessage(
                    "NOVA",
                    "Hello! I'm NOVA.\nLlama 3.2 1B is ready."
                )

            } catch (e: Exception) {

                status.text = "🔴 Model failed"

                addMessage(
                    "ERROR",
                    e.message ?: e.toString()
                )
            }
        }
    }

    private suspend fun copyModelToPrivateStorage(
        uri: Uri
    ): String = withContext(Dispatchers.IO) {

        val destination =
            java.io.File(filesDir, "model.gguf")

        contentResolver.openInputStream(uri).use { input ->

            if (input == null)
                throw Exception("Cannot open model file")

            java.io.FileOutputStream(destination).use { output ->

                val buffer = ByteArray(1024 * 1024)

                while (true) {

                    val count = input.read(buffer)

                    if (count <= 0) break

                    output.write(buffer, 0, count)
                }
            }
        }

        destination.absolutePath
    }

    private fun sendMessage(message: String) {

        if (!::engine.isInitialized) {
            addMessage("NOVA", "Please load the model first.")
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

    private fun addMessage(
        sender: String,
        message: String
    ) {

        val text = TextView(this)

        text.text = "$sender\n$message"
        text.textSize = 17f
        text.setTextColor(Color.BLACK)
        text.setPadding(18, 14, 18, 14)

        val params =
            LinearLayout.LayoutParams(-1, -2)

        params.setMargins(0, 8, 0, 8)

        chat.addView(text, params)
    }

    override fun onDestroy() {

        if (::engine.isInitialized) {
            engine.cleanUp()
        }

        scope.cancel()

        super.onDestroy()
    }
}
