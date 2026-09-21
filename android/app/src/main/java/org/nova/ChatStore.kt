package org.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Role { USER, ASSISTANT }

class Msg(val role: Role, var text: String, var done: Boolean = true)

/**
 * One saved conversation: id, name, messages. Stored as JSON in the app's
 * private filesDir/chats — survives app restarts.
 */
class Chat(
    val id: String,
    var name: String,
    val createdAt: Long,
    var updatedAt: Long,
    val messages: MutableList<Msg> = mutableListOf()
)

/**
 * Tiny JSON-on-disk store for conversations. No dependencies.
 */
object ChatStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "chats").apply { if (!exists()) mkdirs() }

    fun newChat(): Chat =
        Chat(
            id = System.currentTimeMillis().toString(36) + "-" + (0..999).random(),
            name = "New chat",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

    fun save(context: Context, chat: Chat) {
        try {
            // Auto-name from the first user message until renamed
            if (chat.name == "New chat") {
                chat.messages.firstOrNull { it.role == Role.USER && it.text.isNotBlank() }?.let {
                    chat.name = it.text.take(28).replace('\n', ' ').ifBlank { "New chat" }
                }
            }
            chat.updatedAt = System.currentTimeMillis()
            val arr = JSONArray()
            for (m in chat.messages) {
                arr.put(JSONObject().put("r", if (m.role == Role.USER) "u" else "a").put("t", m.text))
            }
            val json = JSONObject()
                .put("id", chat.id)
                .put("name", chat.name)
                .put("created", chat.createdAt)
                .put("updated", chat.updatedAt)
                .put("messages", arr)
            File(dir(context), chat.id + ".json").writeText(json.toString())
        } catch (e: Exception) {
            // best effort — never crash the app for storage
        }
    }

    fun load(context: Context, id: String): Chat? = try {
        val f = File(dir(context), id + ".json")
        if (!f.exists()) return null
        val json = JSONObject(f.readText())
        val messages = mutableListOf<Msg>()
        val arr = json.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            messages.add(Msg(
                if (o.optString("r") == "u") Role.USER else Role.ASSISTANT,
                o.optString("t")
            ))
        }
        Chat(
            id = json.optString("id", id),
            name = json.optString("name", "New chat"),
            createdAt = json.optLong("created", 0L),
            updatedAt = json.optLong("updated", 0L),
            messages = messages
        )
    } catch (e: Exception) {
        null
    }

    /** All chats, most recently used first. */
    fun list(context: Context): List<Chat> =
        dir(context).listFiles { f: File -> f.name.endsWith(".json") }
            ?.mapNotNull { load(context, it.name.removeSuffix(".json")) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun clearAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    fun delete(context: Context, id: String) {
        File(dir(context), id + ".json").delete()
    }

    /** Plain-text transcript of a chat, for export/share. */
    fun transcript(chat: Chat): String = buildString {
        appendLine("NOVA conversation — ${chat.name}")
        appendLine()
        for (m in chat.messages) {
            appendLine(if (m.role == Role.USER) "You:" else "NOVA:")
            appendLine(m.text)
            appendLine()
        }
    }
}
