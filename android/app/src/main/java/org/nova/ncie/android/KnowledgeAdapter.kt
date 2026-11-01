package org.nova.ncie.android

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Loads the NOVA app's existing knowledge.json into the NCIE core's
 * KnowledgeStore. The app stores chunks ([{"d": docName, "t": chunkText},
 * ...]).
 *
 * Two loaders:
 *  - [loadChunks] — the raw (docName, chunkText) pairs exactly as stored.
 *    Feed to KnowledgeStore.rebuildChunks: no re-chunking, byte-identical
 *    retrieval to the app's Knowledge.kt. This is what NcieKnowledge uses.
 *  - [loadPairs] — the chunks grouped back into (docName, fullText) pairs.
 *    Feed to KnowledgeStore.rebuild, which re-splits them with the same
 *    chunker the app used at import time.
 */
object KnowledgeAdapter {

    fun loadChunks(ctx: Context): List<Pair<String, String>> {
        val f = File(ctx.filesDir, "knowledge.json")
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    o.getString("d") to o.getString("t")
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun loadPairs(ctx: Context): List<Pair<String, String>> {
        val f = File(ctx.filesDir, "knowledge.json")
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val byDoc = LinkedHashMap<String, StringBuilder>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                byDoc.getOrPut(o.getString("d")) { StringBuilder() }
                    .append(o.getString("t")).append("\n\n")
            }
            byDoc.map { it.key to it.value.toString().trim() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
