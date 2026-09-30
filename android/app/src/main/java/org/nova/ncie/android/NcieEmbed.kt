package org.nova.ncie.android

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * v9.9.0 "Semantic RAG": the on-device embedder. all-MiniLM-L6-v2 as an
 * int8-quantized ONNX graph (bundled by CI as assets/embedder.onnx, ~23 MB,
 * downloaded at build time from a sha256-pinned URL - never committed) plus
 * its BERT wordpiece vocab (assets/embedder.vocab), run entirely locally
 * through ONNX Runtime - no network at runtime, no accounts.
 *
 *  - [tokenize] is a compact BERT wordpiece tokenizer: lowercase +
 *    accent-fold, split on whitespace and punctuation, greedy
 *    longest-match wordpiece (continuations as "##x"), [CLS]...[SEP],
 *    capped at 256 tokens.
 *  - [embed] runs the session (input_ids + attention_mask, input names
 *    read from the session metadata), mean-pools the token outputs and
 *    L2-normalizes - a 384-dim unit vector, directly comparable by dot
 *    product (cosine).
 *  - [indexChunks] / [search] persist per-source chunk embeddings as
 *    filesDir/embeddings/<name>.emb (chunk count int + 384 LE floats
 *    per chunk) and score them by cosine against the query vector.
 *
 * EVERYTHING here is best-effort: if either asset is missing or the
 * session cannot be created, [broken] is set and every call degrades to
 * a null/empty result, so NcieGround simply falls back to keyword-only
 * retrieval. Init must happen off the main thread (NcieGround already
 * calls from background threads; [ensure] is synchronized anyway).
 */
object NcieEmbed {

    /** all-MiniLM-L6-v2's hidden size - the embedding dimension. */
    const val DIM = 384

    @Volatile private var session: OrtSession? = null
    @Volatile private var vocab: HashMap<String, Int>? = null
    @Volatile private var broken = false
    @Volatile private var initDone = false
    private val initLock = Any()

    /** One-time lazy init: load the model bytes and the vocab. Sets
     *  [broken] (permanent keyword-only fallback) on any failure.
     *  v9.13.0 "Audit Fixes" (HIGH 4): init NEVER runs on the main
     *  thread - a call from it returns untouched (the caller's embed
     *  then sees no session and falls back to keyword scoring, the same
     *  graceful degradation as a missing model), so the 23 MB session
     *  can never again freeze the UI thread. The chat turn's gates hop
     *  to Dispatchers.IO (NcieGround.strongMatchIo) before embedding. */
    fun ensure(ctx: Context) {
        if (broken || initDone) return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return
        synchronized(initLock) {
            if (broken || initDone) return
            try {
                val bytes = ctx.assets.open("embedder.onnx").use { it.readBytes() }
                val env = OrtEnvironment.getEnvironment()
                val s = env.createSession(bytes)
                val v = HashMap<String, Int>(30522)
                ctx.assets.open("embedder.vocab").bufferedReader().useLines { ls ->
                    var i = 0
                    for (line in ls) { v[line] = i; i++ }
                }
                session = s
                vocab = v
            } catch (t: Throwable) {
                broken = true
                session = null
                vocab = null
            }
            initDone = true
        }
    }

    /** True when the embedder is unusable - callers use this to pick the
     *  keyword-only path without even trying. */
    fun isBroken(): Boolean = broken

    // ------------------------------------------------------------ tokenizer

    /** ASCII-fold: NFKD-normalize, drop combining marks, keep only the
     *  ASCII remainder, lowercase. "Photosynthèse" -> "photosynthese". */
    private fun fold(text: String): String {
        val n = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val sb = StringBuilder(n.length)
        for (c in n) {
            if (c.code < 128) sb.append(c)
        }
        return sb.toString().lowercase()
    }

    /** BERT wordpiece: fold, split on whitespace + punctuation, greedy
     *  longest-match against the vocab ("##" continuations), [CLS] 101
     *  prefix, [SEP] 102 suffix, hard cap of 256 tokens. Characters no
     *  wordpiece covers are dropped - never crashes, never invents ids. */
    fun tokenize(text: String): IntArray {
        val v = vocab ?: return IntArray(0)
        val words = fold(text).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val out = ArrayList<Int>(64)
        out.add(101)                                    // [CLS]
        outer@ for (w in words) {
            var start = 0
            while (start < w.length) {
                var end = w.length
                var id = -1
                while (start < end) {
                    val piece = if (start == 0) w.substring(start, end)
                                else "##" + w.substring(start, end)
                    val cand = v[piece]
                    if (cand != null) { id = cand; break }
                    end--
                }
                if (id == -1) { start++; continue }     // unknown char: skip it
                out.add(id)
                start = end
                if (out.size >= 255) break@outer        // leave room for [SEP]
            }
        }
        out.add(102)                                    // [SEP]
        return out.toIntArray()
    }

    // ------------------------------------------------------------ inference

    /** The text's 384-dim unit vector, or null when the embedder is
     *  broken/uninitialized or the run fails - callers fall back to
     *  keyword scoring. Every ONNX call is wrapped; a RAG failure must
     *  never crash the app. */
    fun embed(ctx: Context, text: String): FloatArray? {
        if (broken) return null
        ensure(ctx)
        val s = session ?: return null
        return try {
            val ids = tokenize(text)
            if (ids.isEmpty()) return null
            val env = OrtEnvironment.getEnvironment()
            val shape = longArrayOf(1, ids.size.toLong())
            val idsArr = LongArray(ids.size) { ids[it].toLong() }
            val maskArr = LongArray(ids.size) { 1L }
            val typeArr = LongArray(ids.size) { 0L }
            OnnxTensor.createTensor(env, LongBuffer.wrap(idsArr), shape).use { tIds ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(maskArr), shape).use { tMask ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(typeArr), shape).use { tTypes ->
                        // input names come from the session metadata:
                        // model_quantized.onnx takes input_ids +
                        // attention_mask (token_type_ids only if asked).
                        val inputs = HashMap<String, OnnxTensor>()
                        for (n in s.inputNames) {
                            inputs[n] = when {
                                n.contains("mask") -> tMask
                                n.contains("type") -> tTypes
                                else -> tIds
                            }
                        }
                        s.run(inputs).use { res ->
                            // last_hidden_state: [1][seq][384] -> [seq][384]
                            val tokens = (res[0].value as Array<Array<FloatArray>>)[0]
                            val dim = tokens[0].size
                            val out = FloatArray(dim)
                            for (tok in tokens)
                                for (j in 0 until dim) out[j] += tok[j]
                            var sq = 0f
                            for (j in 0 until dim) {
                                out[j] /= tokens.size.toFloat()   // mean pool
                                sq += out[j] * out[j]
                            }
                            if (sq > 0f) {                        // L2 normalize
                                val inv = 1f / kotlin.math.sqrt(sq)
                                for (j in 0 until dim) out[j] *= inv
                            }
                            out
                        }
                    }
                }
            }
        } catch (t: Throwable) { null }
    }

    /** Cosine of two vectors (unit vectors: plain dot product). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var d = 0f
        for (i in 0 until n) d += a[i] * b[i]
        return d
    }

    // ------------------------------------------------------------ index store

    /** filesDir/embeddings/<safe-name>.emb - one file per source. */
    fun indexFile(ctx: Context, name: String): File =
        File(File(ctx.filesDir, "embeddings"), safe(name) + ".emb")

    private fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(120)

    /** True when this source has an embedding index on disk. */
    fun hasIndex(ctx: Context, name: String): Boolean = indexFile(ctx, name).exists()

    /** The index's chunk count (for staleness checks), or -1. */
    fun indexSize(ctx: Context, name: String): Int = try {
        DataInputStream(BufferedInputStream(indexFile(ctx, name).inputStream())).use { it.readInt() }
    } catch (e: Exception) { -1 }

    /** (Re)build the source's embedding index: embeds every chunk and
     *  atomically writes filesDir/embeddings/<name>.emb (chunk count int
     *  + 384 LE floats per chunk). Any failure deletes the stale file so
     *  retrieval degrades to keyword-only. Off the main thread only. */
    fun indexChunks(ctx: Context, name: String, texts: List<String>) {
        // v9.12.1 "Context Diet": never run the embedder while a
        // generation is in flight - the ONNX model and the LLM fight for
        // the same CPU cores and the chat's token rate collapsed to a
        // third. Indexing is best-effort: skip now, the next
        // indexUpdate call picks it up.
        if (NcieChat.generating) return
        val f = indexFile(ctx, name)
        try {
            if (broken) { f.delete(); return }
            ensure(ctx)
            if (broken) { f.delete(); return }
            if (texts.isEmpty()) { f.delete(); return }
            val vecs = ArrayList<FloatArray>(texts.size)
            for (t in texts) {
                if (NcieChat.generating) return   // abort - the next indexUpdate retries
                val e = embed(ctx, t)
                if (e == null || e.size != DIM) { f.delete(); return }
                vecs.add(e)
            }
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { w ->
                w.writeInt(vecs.size)
                for (e in vecs)
                    for (x in e) w.writeFloat(x)
                w.flush()
            }
            if (!tmp.renameTo(f)) {
                // rename can fail across odd mount states - copy over instead
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            try { f.delete() } catch (e: Exception) { }
        }
    }

    /** Drop one source's embedding index (source removed or edited to
     *  nothing). */
    fun deleteIndex(ctx: Context, name: String) {
        try { indexFile(ctx, name).delete() } catch (e: Exception) { }
    }

    /** The whole wiki store was replaced: drop every embedding index. */
    fun resetIndexes(ctx: Context) {
        try { File(ctx.filesDir, "embeddings").deleteRecursively() } catch (e: Exception) { }
    }

    /** The top [topK] (chunkIdx, cosine-score) pairs for a query unit
     *  vector, best first; empty when there is no usable index. */
    fun search(ctx: Context, name: String, queryVec: FloatArray, topK: Int): List<Pair<Int, Float>> {
        return try {
            val f = indexFile(ctx, name)
            if (!f.exists() || queryVec.size != DIM) return emptyList()
            DataInputStream(BufferedInputStream(f.inputStream())).use { r ->
                val n = r.readInt()
                if (n <= 0) return emptyList()
                val out = ArrayList<Pair<Int, Float>>(n)
                val buf = FloatArray(DIM)
                for (i in 0 until n) {
                    for (j in 0 until DIM) buf[j] = r.readFloat()
                    out.add(i to cosine(queryVec, buf))   // both unit vectors
                }
                out.sortByDescending { it.second }
                out.take(topK)
            }
        } catch (e: Exception) { emptyList() }
    }
}
