package org.nova.ncie

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelReusePolicyTest {
    @Test
    fun samePathAndReadyModelCanBeReused() {
        assertTrue(ModelReusePolicy.canReuse("/models/lfm.gguf", "/models/lfm.gguf", true))
    }

    @Test
    fun differentPathMustReload() {
        assertFalse(ModelReusePolicy.canReuse("/models/lfm.gguf", "/models/qwen.gguf", true))
    }

    @Test
    fun samePathWithoutReadyEngineMustReload() {
        assertFalse(ModelReusePolicy.canReuse("/models/lfm.gguf", "/models/lfm.gguf", false))
    }

    @Test
    fun missingOrBlankPathsMustReload() {
        assertFalse(ModelReusePolicy.canReuse(null, "/models/lfm.gguf", true))
        assertFalse(ModelReusePolicy.canReuse("", "/models/lfm.gguf", true))
        assertFalse(ModelReusePolicy.canReuse("/models/lfm.gguf", "", true))
    }
}
