package org.nova.ncie

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * v9.33.79 "GGUF card": read the model's OWN metadata instead of guessing from
 * its file name.
 *
 * Every quality decision NOVA makes about a model - the lean prompt profile,
 * the no-thinking hint, the tiny-model brevity rule - is driven by a size
 * parsed out of the LABEL ("LFM 2.5 1.2B Instruct"). That label is whatever
 * the catalog entry or the file name says. A model the user renamed, copied
 * in by hand, or downloaded from outside the catalog carries no size at all,
 * so a 0.6B model silently got the FULL profile: two background sources, the
 * exam line and a wider context. That is both slower and worse - a 0.6B
 * drowns in stacked instructions.
 *
 * A GGUF file states its own identity in its header: general.architecture,
 * general.name, general.size_label, <arch>.context_length and the tokenizer's
 * chat_template. This reads that header - and ONLY the header. It never reads
 * tensor data, it never allocates a whole array of token strings, and it stops
 * after a fixed byte budget, so a multi-GB model costs a few milliseconds.
 *
 * Pure JVM: no Android, no engine, so it is unit-tested with synthetic bytes.
 * Anything unreadable is null, never an exception - a model that loads fine
 * must never fail because its card could not be read.
 */
object GgufMeta {

    /** What the model says about itself. Any field may be empty/zero. */
    data class Card(
        val architecture: String,
        val name: String,
        val sizeLabel: String,
        val contextLength: Int,
        val chatTemplate: String
    ) {
        /**
         * Does the model's own chat template declare a system role? Gemma and
         * Llama-2-style templates do not, and a template with no system turn
         * is exactly the case where a system prompt can be dropped on the
         * floor. Reported, not yet acted on - see the plan.
         */
        val hasSystemRole: Boolean
            get() = chatTemplate.isNotBlank() &&
                (chatTemplate.contains("'system'") || chatTemplate.contains("\"system\""))
    }

    /** "GGUF" read as a little-endian u32. */
    private const val MAGIC: Long = 0x46554747L

    /** The header is walked in order; past this many bytes we stop and keep
     *  whatever was found. tokenizer.ggml.tokens sits between the general
     *  keys and tokenizer.chat_template in most files, so the budget has to
     *  cover it - but it is bytes read sequentially, not held in memory. */
    private const val BUDGET: Long = 64L * 1024 * 1024

    /** No real GGUF has more metadata keys than this; a bigger count means the
     *  file is not a GGUF and we should not loop over garbage. */
    private const val MAX_KEYS: Long = 200_000L

    private const val T_UINT8: Long = 0
    private const val T_INT8: Long = 1
    private const val T_UINT16: Long = 2
    private const val T_INT16: Long = 3
    private const val T_UINT32: Long = 4
    private const val T_INT32: Long = 5
    private const val T_FLOAT32: Long = 6
    private const val T_BOOL: Long = 7
    private const val T_STRING: Long = 8
    private const val T_ARRAY: Long = 9
    private const val T_UINT64: Long = 10
    private const val T_INT64: Long = 11
    private const val T_FLOAT64: Long = 12

    /** The card for a model file, or null when it cannot be read. */
    fun read(path: String): Card? = try {
        val f = File(path)
        if (!f.isFile || f.length() <= 0) null else FileInputStream(f).use { fis ->
            parseStream(BufferedInputStream(fis, 256 * 1024))
        }
    } catch (e: Exception) {
        null
    }

    /** Test entry point: parse a whole GGUF image held in memory. */
    fun parse(bytes: ByteArray): Card? = try {
        parseStream(ByteArrayInputStream(bytes))
    } catch (e: Exception) {
        null
    }

    private fun parseStream(s: InputStream): Card? {
        val r = Reader(s)
        if (r.u32() != MAGIC) return null
        val version = r.u32()
        // v2 added the u64 counts; v3 is the current form. A v1 file has a
        // different header and is not worth supporting.
        if (version < 2) return null
        r.u64()                       // tensor count - not needed
        val keys = r.u64()
        if (keys < 0 || keys > MAX_KEYS) return null

        var arch = ""
        var name = ""
        var sizeLabel = ""
        var ctx = 0
        var template = ""

        var i = 0L
        while (i < keys) {
            if (r.used > BUDGET) break
            val key = r.str()
            val type = r.u32()
            if (type == T_STRING && key == "general.architecture") {
                arch = r.str()
            } else if (type == T_STRING && key == "general.name") {
                name = r.str()
            } else if (type == T_STRING && key == "general.size_label") {
                sizeLabel = r.str()
            } else if (type == T_STRING && key == "tokenizer.chat_template") {
                template = r.str()
            } else if ((type == T_UINT32 || type == T_INT32) && key.endsWith(".context_length")) {
                ctx = r.u32().toInt()
            } else {
                skipValue(r, type)
            }
            i++
        }

        if (arch.isBlank() && name.isBlank() && sizeLabel.isBlank() &&
            template.isBlank() && ctx <= 0) return null
        return Card(arch, name, sizeLabel, ctx, template)
    }

    /** Walks a value we do not care about, without materialising it. */
    private fun skipValue(r: Reader, type: Long) {
        when (type) {
            T_UINT8, T_INT8, T_BOOL -> r.skip(1)
            T_UINT16, T_INT16 -> r.skip(2)
            T_UINT32, T_INT32, T_FLOAT32 -> r.skip(4)
            T_UINT64, T_INT64, T_FLOAT64 -> r.skip(8)
            T_STRING -> r.skipString()
            T_ARRAY -> {
                val elem = r.u32()
                val count = r.u64()
                if (count < 0) throw IOException("bad array count")
                if (elem == T_STRING) {
                    // The token list is the big one: 100k+ short strings. Walk
                    // their lengths and skip the bytes - never allocate them.
                    var i = 0L
                    while (i < count) {
                        r.skipString()
                        i++
                    }
                } else {
                    r.skip(count * sizeOf(elem))
                }
            }
            else -> throw IOException("unknown value type $type")
        }
    }

    private fun sizeOf(type: Long): Long = when (type) {
        T_UINT8, T_INT8, T_BOOL -> 1L
        T_UINT16, T_INT16 -> 2L
        T_UINT32, T_INT32, T_FLOAT32 -> 4L
        T_UINT64, T_INT64, T_FLOAT64 -> 8L
        else -> throw IOException("unknown array element type $type")
    }

    /** A forward-only reader that counts what it has consumed. */
    private class Reader(private val s: InputStream) {
        var used: Long = 0L
            private set

        fun readN(n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = s.read(out, got, n - got)
                if (r < 0) throw IOException("eof")
                got += r
            }
            used += n.toLong()
            return out
        }

        fun skip(n: Long) {
            if (n <= 0) return
            val buf = ByteArray(8192)
            var left = n
            while (left > 0) {
                val want = if (left < buf.size.toLong()) left.toInt() else buf.size
                val r = s.read(buf, 0, want)
                if (r < 0) throw IOException("eof")
                left -= r.toLong()
                used += r.toLong()
            }
        }

        fun u32(): Long {
            val b = readN(4)
            return (b[0].toLong() and 0xFF) or
                ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or
                ((b[3].toLong() and 0xFF) shl 24)
        }

        fun u64(): Long {
            val b = readN(8)
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }

        fun str(): String {
            val n = u64()
            if (n < 0 || n > 16L * 1024 * 1024) throw IOException("bad string length")
            return String(readN(n.toInt()), Charsets.UTF_8)
        }

        fun skipString() {
            val n = u64()
            if (n < 0) throw IOException("bad string length")
            skip(n)
        }
    }
}
