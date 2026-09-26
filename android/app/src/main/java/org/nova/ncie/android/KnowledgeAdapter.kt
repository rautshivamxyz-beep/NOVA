package org.nova.ncie.android

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Loads the NOVA app's existing knowledge.json into the NCIE core's
 * KnowledgeStore. The app stores chunks ([{"d": docName, "t": chunkText},
 * ...]); this adapter groups them back into (docName, fullText) pairs, and
 * the core's identical chunker re-splits them — so the store indexes exactly
 * what the app's Knowledge.kt would have found.
 *
 * Usage in the app:
 *
 *     val knowledge = KnowledgeStore().apply {
 *         rebuild(KnowledgeAdapter.loadPairs(context))
 *         setExcluded(Settings(context).knowledgeExcluded)
 *     }
 *     val nova = NovaKernel(..., knowledge = knowledge)
 */
object KnowledgeAdapter {

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
