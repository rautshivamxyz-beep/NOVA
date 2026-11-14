package org.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.nova.ncie.android.NcieFiles
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

    /** Default name of a fresh conversation (was a bare string literal). */
    private const val NEW_CHAT_NAME = "New chat"

    private fun dir(context: Context): File =
        File(context.filesDir, "chats").apply { if (!exists()) mkdirs() }

    fun newChat(): Chat =
        Chat(
            id = java.util.UUID.randomUUID().toString(),
            name = NEW_CHAT_NAME,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

    /**
     * v9.33.148 "The conversation answers for itself": [save] now answers
     * whether the conversation is on the disk.
     *
     * v9.33.147 gave every best-effort save a way to be heard and fixed the
     * six sites that were throwing their answer away - but the conversation
     * store, the one file the user cannot re-type, was still written with
     * `NcieFiles.write(...)` and the answer dropped on the floor. A full
     * disk or a storage fault therefore lost a whole chat in silence, and
     * the screen went on showing it as if it were saved.
     *
     * The JSON is still built inside the try, because building it can throw;
     * the write itself never does (v9.33.90), so it has moved out of the
     * catch - a `catch` around a call that cannot throw is the dead code
     * v9.33.147 was about. The answer is recorded in the swallowed-failure
     * tally by [NcieFiles.writeOrNote] and returned to the caller.
     */
    fun save(context: Context, chat: Chat): Boolean {
        val json = try {
            // Auto-name from the first user message until renamed
            if (chat.name == NEW_CHAT_NAME) {
                chat.messages.firstOrNull { it.role == Role.USER && it.text.isNotBlank() }?.let {
                    chat.name = it.text.take(28).replace('\n', ' ').ifBlank { NEW_CHAT_NAME }
                }
            }
            chat.updatedAt = System.currentTimeMillis()
            val arr = JSONArray()
            for (m in chat.messages) {
                arr.put(JSONObject().put("r", if (m.role == Role.USER) "u" else "a").put("t", m.text).put("d", m.done))
            }
            JSONObject()
                .put("id", chat.id)
                .put("name", chat.name)
                .put("created", chat.createdAt)
                .put("updated", chat.updatedAt)
                .put("messages", arr)
                .toString()
        } catch (e: Exception) {
            // a message NOVA cannot serialise must not crash the app
            NovaLog.quiet("ChatStore", e)
            return false
        }
        // v9.33.100 "The conversation store joins the one atomic save":
        // this was the last store still hand-rolling its own `<id>.tmp`.
        // It is also the most-written state file in the app - MainActivity
        // saves a chat from the UI thread while a Dispatchers.IO coroutine
        // saves the same chat mid-stream - so the fixed temp name meant two
        // threads sharing one buffer (v9.33.99), and its own fallback
        // dropped the temp before writing the target directly. Both are
        // gone: NcieFiles.write takes one lock per destination path and
        // never removes the old file until the new one is really in place.
        return NcieFiles.writeOrNote(File(dir(context), chat.id + ".json"), json, "ChatStore")
    }

    /**
     * v9.33.148: what to say when [save] answers false, or null when there is
     * nothing to say because it worked.
     *
     * Pure on purpose: the write needs a Context and a real filesystem, so
     * the sentence is the half of this a JVM unit test can pin. A screen that
     * has just claimed "Message deleted" or accepted a rename is the place a
     * silent save failure turns into a lie, and it needs the words.
     */
    fun saveNotice(saved: Boolean): String? =
        if (saved) null
        else "Could not save this chat to the phone - it will be gone when NOVA closes"

    /**
     * v9.33.124 "A conversation that could not be read is not a new chat": the
     * conversation in [text], or null when [text] is not one.
     *
     * v9.33.101 made one malformed message ROW cost that row only. A malformed
     * top-level object still cost the whole conversation, because the parse
     * lived inside [load] and [load] answers null - so the caller could not
     * tell "there is no conversation here" from "there is one and it did not
     * parse". Pure, so a JVM test can pin both halves of that answer.
     */
    fun parse(text: String, id: String): Chat? {
        // v9.33.124: a conversation is a JSON OBJECT and nothing else is one,
        // so the cheapest answer is also the honest one - and it costs no
        // exception for the common garbage (an empty file, a fragment, an
        // array, the literal null). It is also the half of the answer a JVM
        // unit test can pin: a local test has no JSON runtime at all, because
        // org.json ships inside android.jar and Gradle replaces that jar with
        // the mockable one, whose every method throws.
        if (!isObject(text)) return null
        return try {
            val json = JSONObject(text)
            val messages = mutableListOf<Msg>()
            val arr = json.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until arr.length()) {
                // v9.33.101: one malformed row used to throw straight out of
                // this loop and take the WHOLE conversation with it. A row
                // that is not an object is skipped; every other message still
                // opens.
                val o = arr.optJSONObject(i) ?: continue
                messages.add(Msg(
                    if (o.optString("r") == "u") Role.USER else Role.ASSISTANT,
                    o.optString("t"),
                    // v9.29.4: a message read from disk is NEVER "generating".
                    // A mid-stream save (done=false) used to reload as a
                    // permanent animated bubble - the "stuck on generating"
                    // report. History is always done.
                    true
                ))
            }
            Chat(
                id = json.optString("id", id),
                name = json.optString("name", NEW_CHAT_NAME),
                createdAt = json.optLong("created", 0L),
                updatedAt = json.optLong("updated", 0L),
                messages = messages
            )
        } catch (e: Exception) {
            NovaLog.quiet("ChatStore", e)
            null
        }
    }

    /**
     * v9.33.124: true when [text] is shaped like a JSON object - which is the
     * only thing a conversation can be. Pure string work: no JSON runtime, no
     * exception, nothing to mock.
     */
    internal fun isObject(text: String): Boolean {
        val t = text.trim()
        return t.length >= 2 && t.startsWith("{") && t.endsWith("}")
    }

    fun load(context: Context, id: String): Chat? = try {
        val f = File(dir(context), id + ".json")
        if (!f.exists()) return null
        parse(f.readText(), id)
    } catch (e: Exception) {
        NovaLog.quiet("ChatStore", e)
        null
    }

    /**
     * v9.33.124: true when the conversation file is THERE and could not be
     * read - the question [load] cannot answer, because it says null for a
     * missing file too.
     *
     * The open path used to take that null and start a brand new chat, with
     * nothing said. The file was never deleted (the new chat has its own id),
     * but the chat list skips it as well, so from the inside the app looked
     * exactly as if every message had been thrown away.
     */
    fun unreadable(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        val f = File(dir(context), id + ".json")
        if (!f.exists()) return false
        return try { parse(f.readText(), id) == null }
        catch (e: Exception) { NovaLog.quiet("ChatStore", e); true }
    }

    /** All chats, most recently used first. v9.29.1: reads only each chat's
     *  header (id / name / timestamps) instead of decoding every message -
     *  the list screen shows names, so a chat with hundreds of messages no
     *  longer has to be fully rebuilt just to appear. Same order, same data
     *  shown. */
    fun list(context: Context): List<Chat> =
        dir(context).listFiles { f: File -> f.name.endsWith(".json") }
            ?.mapNotNull { loadMeta(context, it.name.removeSuffix(".json")) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    /** Header-only read for the chat list - never builds the messages. */
    private fun loadMeta(context: Context, id: String): Chat? = try {
        val f = File(dir(context), id + ".json")
        if (!f.exists()) return null
        val json = JSONObject(f.readText())
        Chat(
            id = json.optString("id", id),
            name = json.optString("name", NEW_CHAT_NAME),
            createdAt = json.optLong("created", 0L),
            updatedAt = json.optLong("updated", 0L)
        )
    } catch (e: Exception) { NovaLog.quiet("ChatStore", e); null }

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
