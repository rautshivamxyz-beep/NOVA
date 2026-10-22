package org.nova

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * v9.33.42 "Borrowed brain": run the model on a machine that can afford a
 * bigger one - a laptop on the same Wi-Fi, or another phone - and let NOVA use
 * it from here.
 *
 * The protocol is the one llama.cpp's own server already speaks
 * (`/v1/chat/completions`, the OpenAI-compatible shape), so the machine at the
 * other end is just `llama-server` with a bigger model loaded. Nothing goes to
 * a third party: the address is one the user typed, on their own network.
 *
 * Streaming is used on purpose. An 8B model on a laptop takes a moment to
 * start, and a chat that shows nothing for thirty seconds reads as broken.
 */
object RemoteEngine {

    /** enabled, base url, model name, system prompt. */
    @Volatile private var cfg: Array<String> = arrayOf("", "", "", "")
    @Volatile private var on = false

    /** Read the current settings once. Called at app start and on every edit. */
    fun configure(ctx: android.content.Context) {
        try {
            val s = Settings(ctx)
            cfg = arrayOf(s.remoteUrl.trim(), s.remoteModel.trim(), s.systemPrompt, "")
            on = s.remoteEnabled && s.remoteUrl.isNotBlank()
        } catch (e: Exception) { NovaLog.quiet("RemoteEngine", e) }
    }

    fun enabled(): Boolean = on

    /** The address as the user typed it, for messages and diagnostics. */
    fun address(): String = cfg.getOrNull(0) ?: ""

    private fun endpoint(): String {
        val base = (cfg.getOrNull(0) ?: "").trim().trimEnd('/')
        return if (base.endsWith("/v1/chat/completions")) base else "$base/v1/chat/completions"
    }

    private fun modelName(): String = cfg.getOrNull(1)?.ifBlank { "local" } ?: "local"

    private fun systemPrompt(): String = cfg.getOrNull(2) ?: ""

    /** Minimal JSON string escaping - the prompt is arbitrary text. */
    private fun q(s: String): String = buildString(s.length + 16) {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append(' ')
            else -> append(c)
        }
        append('"')
    }

    /**
     * Streams the answer from the remote machine. Throws on any failure - the
     * caller falls back to the phone, so a laptop that is asleep or off the
     * network never turns into a chat that does not answer.
     */
    fun stream(prompt: String, maxTokens: Int): Flow<String> = flow {
        val sys = systemPrompt()
        val body = buildString {
            append("{\"model\":").append(q(modelName()))
            append(",\"max_tokens\":").append(maxTokens)
            append(",\"stream\":true,\"messages\":[")
            if (sys.isNotBlank()) append("{\"role\":\"system\",\"content\":").append(q(sys)).append("},")
            append("{\"role\":\"user\",\"content\":").append(q(prompt)).append("}]}")
        }
        val conn = (URL(endpoint()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 180000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("remote server said $code")
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val piece = contentOf(payload) ?: continue
                    if (piece.isNotEmpty()) emit(piece)
                }
            }
        } finally {
            try { conn.disconnect() } catch (e: Exception) { NovaLog.quiet("RemoteEngine", e) }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Pulls the text out of one streamed chunk: `choices[0].delta.content`
     * (OpenAI shape) or `content`/`text` (llama.cpp's own shape). A tiny scan
     * rather than a JSON parser - the app carries no JSON dependency, and a
     * malformed chunk must never break the answer.
     */
    private fun contentOf(json: String): String? {
        var key = json.indexOf("\"content\"")
        if (key >= 0) key += 9 else key = json.indexOf("\"text\"")
        if (key < 0) return null
        var i = key
        while (i < json.length && json[i] != ':') i++
        i++
        while (i < json.length && json[i] == ' ') i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (json[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < json.length) {
                            json.substring(i + 2, i + 6).toIntOrNull(16)
                                ?.let { sb.append(it.toChar()) }
                            i += 6
                            continue
                        }
                    }
                    else -> sb.append(json[i + 1])
                }
                i += 2
                continue
            }
            if (c == '"') break
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
