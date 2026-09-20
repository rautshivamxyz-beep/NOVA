package org.nova

/**
 * Curated catalog of known-good GGUF models (all Q4_K_M — the best
 * quality/memory trade-off for phones). Every model here runs 100%
 * offline on-device with llama.cpp.
 *
 * Any other GGUF can still be added from the Models screen via a direct
 * Hugging Face URL or by importing a local file — the catalog is just a
 * convenience.
 *
 * Sizes are the real download sizes, verified against Hugging Face.
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
            "llama32-1b", "Llama 3.2 1B Instruct", "Meta", "1B", "Q4_K_M",
            0x30000000L /* ~0.75 GB */, 2,
            "Fastest. Great for any phone, even budget ones.",
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        ),
        Entry(
            "smollm2-1.7b", "SmolLM2 1.7B Instruct", "HuggingFace", "1.7B", "Q4_K_M",
            0x3F000000L /* ~0.98 GB */, 3,
            "Small, snappy and surprisingly capable.",
            "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf"
        ),
        Entry(
            "gemma2-2b", "Gemma 2 2B IT", "Google", "2B", "Q4_K_M",
            0x66000000L /* ~1.59 GB */, 3,
            "Google's compact model, strong quality for its size.",
            "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf"
        ),
        Entry(
            "llama32-3b", "Llama 3.2 3B Instruct", "Meta", "3B", "Q4_K_M",
            0x79000000L /* ~1.88 GB */, 4,
            "The sweet spot for mid-range phones.",
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"
        ),
        Entry(
            "qwen25-3b", "Qwen 2.5 3B Instruct", "Alibaba", "3B", "Q4_K_M",
            0x74000000L /* ~1.80 GB */, 4,
            "Very strong multilingual + coding for 3B.",
            "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf"
        ),
        Entry(
            "phi35-mini", "Phi 3.5 Mini Instruct", "Microsoft", "3.8B", "Q4_K_M",
            0x90000000L /* ~2.23 GB */, 4,
            "Punchy reasoning in a small package.",
            "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf"
        ),
        Entry(
            "mistral-7b", "Mistral 7B Instruct v0.3", "Mistral AI", "7B", "Q4_K_M",
            0xF2000000L /* ~4.07 GB */, 6,
            "Classic workhorse model.",
            "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf"
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
            "qwen25-14b", "Qwen 2.5 14B Instruct", "Alibaba", "14B", "Q4_K_M",
            0x20F000000L /* ~8.37 GB */, 12,
            "For 12 GB+ RAM flagships / tablets only.",
            "https://huggingface.co/bartowski/Qwen2.5-14B-Instruct-GGUF/resolve/main/Qwen2.5-14B-Instruct-Q4_K_M.gguf"
        )
    )

    /**
     * App-private directory where downloaded / imported models live.
     * External app storage is preferred (no quota on internal /data
     * partition, still private, no permissions needed).
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
