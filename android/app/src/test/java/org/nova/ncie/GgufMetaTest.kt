package org.nova.ncie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * v9.33.79: the GGUF header reader. Built from synthetic bytes so the parser is
 * pinned by real assertions instead of by "the app seemed fine" - a header
 * walker that loses sync on one value type would otherwise hand back a blank
 * card and silently put every model on the full prompt profile.
 */
class GgufMetaTest {

    // ---- a tiny GGUF writer, only what the parser reads ----

    private fun u32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    private fun u64(v: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = ((v shr (8 * i)) and 0xFF).toByte()
        return b
    }

    private fun str(s: String): ByteArray {
        val p = s.toByteArray(Charsets.UTF_8)
        return u64(p.size.toLong()) + p
    }

    private fun gguf(vararg pairs: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x47, 0x47, 0x55, 0x46))   // "GGUF"
        out.write(u32(3))                                 // version
        out.write(u64(0))                                 // tensor count
        out.write(u64(pairs.size.toLong()))               // metadata key count
        for (p in pairs) out.write(p)
        return out.toByteArray()
    }

    private fun kvString(key: String, value: String): ByteArray = str(key) + u32(8) + str(value)

    private fun kvU32(key: String, value: Long): ByteArray = str(key) + u32(4) + u32(value)

    private fun kvStrArray(key: String, values: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(str(key))
        out.write(u32(9))                                 // array
        out.write(u32(8))                                 // of strings
        out.write(u64(values.size.toLong()))
        for (v in values) out.write(str(v))
        return out.toByteArray()
    }

    private fun kvFloatArray(key: String, values: List<Float>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(str(key))
        out.write(u32(9))                                 // array
        out.write(u32(6))                                 // of float32
        out.write(u64(values.size.toLong()))
        for (v in values) out.write(u32(java.lang.Float.floatToRawIntBits(v).toLong() and 0xFFFFFFFFL))
        return out.toByteArray()
    }

    private fun kvBool(key: String, value: Boolean): ByteArray =
        str(key) + u32(7) + byteArrayOf(if (value) 1 else 0)

    // ---- tests ----

    @Test
    fun theCardStatesTheModelsOwnIdentity() {
        val bytes = gguf(
            kvString("general.architecture", "qwen3"),
            kvString("general.name", "Qwen3-0.6B"),
            kvString("general.size_label", "0.6B"),
            kvU32("qwen3.context_length", 40960)
        )
        val card = GgufMeta.parse(bytes)!!
        assertEquals("qwen3", card.architecture)
        assertEquals("Qwen3-0.6B", card.name)
        assertEquals("0.6B", card.sizeLabel)
        assertEquals(40960, card.contextLength)
        assertEquals("", card.chatTemplate)
    }

    @Test
    fun aStringArrayIsSkippedBeforeTheTemplate() {
        // The real ordering that broke a naive prefix read: the token list sits
        // between the general keys and tokenizer.chat_template.
        val tokens = (1..500).map { "tok$it" }
        val bytes = gguf(
            kvString("general.architecture", "llama"),
            kvString("general.size_label", "1B"),
            kvString("tokenizer.ggml.model", "gpt2"),
            kvStrArray("tokenizer.ggml.tokens", tokens),
            kvFloatArray("tokenizer.ggml.scores", listOf(0f, -1.5f, 2f)),
            kvBool("tokenizer.ggml.add_bos_token", true),
            kvString("tokenizer.chat_template",
                "{% for m in messages %}{% if m['role'] == 'system' %}<|system|>{% endif %}{{ m['content'] }}{% endfor %}")
        )
        val card = GgufMeta.parse(bytes)!!
        assertEquals("llama", card.architecture)
        assertEquals("1B", card.sizeLabel)
        assertTrue(card.chatTemplate.contains("{% for m in messages %}"))
        // the template was reached even though a 500-entry token list and a
        // float array sat in front of it
        assertTrue(card.hasSystemRole)
    }

    @Test
    fun aTemplateWithNoSystemRoleIsReportedAsSuch() {
        val bytes = gguf(
            kvString("general.architecture", "gemma3"),
            kvString("tokenizer.chat_template", "{{ bos_token }}{% for m in messages %}{{ m['content'] }}{% endfor %}")
        )
        val card = GgufMeta.parse(bytes)!!
        assertFalse(card.hasSystemRole)
    }

    @Test
    fun aFileThatIsNotAGgufIsNull() {
        assertNull(GgufMeta.parse("not a model at all".toByteArray()))
        // "GGUF" but a version from before the u64 header
        val v1 = byteArrayOf(0x47, 0x47, 0x55, 0x46) + u32(1) + u64(0) + u64(0)
        assertNull(GgufMeta.parse(v1))
        assertNull(GgufMeta.parse(ByteArray(0)))
    }

    @Test
    fun aHeaderWithNothingUsefulIsNull() {
        // No general keys, no template, no context length: an empty answer is
        // not a card, and callers must be able to tell the difference.
        assertNull(GgufMeta.parse(gguf(kvString("some.other.key", "x"))))
    }

    @Test
    fun aTruncatedFileIsNullNotAnException() {
        val full = gguf(
            kvString("general.architecture", "qwen3"),
            kvString("general.name", "Qwen3-0.6B"),
            kvStrArray("tokenizer.ggml.tokens", (1..50).map { "tok$it" }),
            kvString("tokenizer.chat_template", "x")
        )
        assertNull(GgufMeta.parse(full.copyOf(full.size / 2)))
    }
}
