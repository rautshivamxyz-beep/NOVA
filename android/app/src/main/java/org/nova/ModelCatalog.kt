package org.nova

/** Curated catalog of known-good GGUF models (Q4_K_M). 100% offline.
 * Smallest-first: fast models for budget phones at the top.
 * Sizes verified against Hugging Face.
 */
object ModelCatalog {

    data class Entry(
        val id: String,
        val name: String,
        val org: String,
        val params: String,
        val quant: String,
        val sizeBytes: Long,
        val minRamGb: Int,
        val notes: String,
        val url: String
    ) {
        val fileName: String
            get() = url.substringAfterLast('/').substringBefore('?')
    }

    val entries = listOf(
        Entry(
            "qwen3-0.6b", "Qwen 3 0.6B", "Alibaba", "0.6B", "Q4_K_M",
            396705472L /* ~0.38 GB */, 2,
            "Fastest chat model — roughly 3x faster than 1.7B. Best for quick everyday questions.",
            "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"
        ),
        Entry(
            "gemma3-1b", "Gemma 3 1B IT", "Google", "1B", "Q4_K_M",
            0x30000000L /* ~0.75 GB */, 2,
            "Ultra-light model. Good balance of speed and quality.",
            "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"
        ),
        Entry(
            "llama32-1b", "Llama 3.2 1B Instruct", "Meta", "1B", "Q4_K_M",
            0x30000000L /* ~0.75 GB */, 2,
            "Fast and reliable classic for any phone.",
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        ),
        Entry(
            "lfm25-1.2b-thinking", "LFM 2.5 1.2B Thinking", "Liquid AI", "1.2B", "Q4_K_M",
            730895584L /* ~0.70 GB */, 3,
            "Reasoning model: great at math and logic. Thinks silently before answering, so the first word takes longer.",
            "https://huggingface.co/unsloth/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q4_K_M.gguf"
        ),
        Entry(
            "qwen3-1.7b", "Qwen 3 1.7B", "Alibaba", "1.7B", "Q4_K_M",
            0x42000000L /* ~1.03 GB */, 3,
            "Your quality pick — best for study, documents and quizzes. Slower but smarter.",
            "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
        ),
        Entry(
            "qwen25-3b", "Qwen 2.5 3B Instruct", "Alibaba", "3B", "Q4_K_M",
            0x74000000L /* ~1.80 GB */, 4,
            "Strong multilingual + coding for 3B.",
            "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf"
        ),
        Entry(
            "llama32-3b", "Llama 3.2 3B Instruct", "Meta", "3B", "Q4_K_M",
            0x79000000L /* ~1.88 GB */, 4,
            "The classic sweet spot for mid-range phones.",
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"
        ),
        Entry(
            "phi4-mini", "Phi 4 Mini Instruct", "Microsoft", "3.8B", "Q4_K_M",
            0x95000000L /* ~2.32 GB */, 4,
            "Punchy reasoning; the newer Phi mini.",
            "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf"
        ),
        Entry(
            "qwen3-4b", "Qwen 3 4B", "Alibaba", "4B", "Q4_K_M",
            0x95000000L /* ~2.33 GB */, 5,
            "Current generation; excellent all-rounder if it fits.",
            "https://huggingface.co/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"
        ),
        Entry(
            "gemma3-4b", "Gemma 3 4B IT", "Google", "4B", "Q4_K_M",
            0x95000000L /* ~2.32 GB */, 5,
            "Great quality per GB; tight on 4 GB phones.",
            "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf"
        ),
        Entry(
            "qwen25-7b", "Qwen 2.5 7B Instruct", "Alibaba", "7B", "Q4_K_M",
            0x103000000L /* ~4.36 GB */, 6,
            "Excellent all-rounder, great at code.",
            "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf"
        ),
        Entry(
            "llama31-8b", "Llama 3.1 8B Instruct", "Meta", "8B", "Q4_K_M",
            0x124000000L /* ~4.58 GB */, 6,
            "Flagship small model. Needs a good phone.",
            "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"
        ),
        Entry(
            "qwen3-8b", "Qwen 3 8B", "Alibaba", "8B", "Q4_K_M",
            0x12B000000L /* ~4.68 GB */, 6,
            "Current generation 8B — the smartest model here for 8 GB phones.",
            "https://huggingface.co/unsloth/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf"
        )
    )

    /**
     * App-private directory where downloaded / imported models live.
     */
    fun modelsDir(context: android.content.Context): java.io.File {
        val ext = context.getExternalFilesDir(null)
        val dir = if (ext != null) java.io.File(ext, "models") else java.io.File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun labelFor(file: java.io.File): String {
        val n = file.name.removeSuffix(".gguf")
        return n.replace('-', ' ').replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
    }
}
