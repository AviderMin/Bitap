package com.bitap.keyboard.ai

import com.bitap.keyboard.ai.internal.ModelFileRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFileRulesTest {

    @Test
    fun canonicalModelNameIsStable() {
        assertEquals("bitap-qwen3-0.6b.litertlm", ModelFileRules.MODEL_FILE_NAME)
    }

    @Test
    fun acceptsLitertlmExtensionCaseInsensitively() {
        assertTrue(ModelFileRules.isSupportedImportName("bitap-qwen3-0.6b.litertlm"))
        assertTrue(ModelFileRules.isSupportedImportName("MODEL.LITERTLM"))
    }

    @Test
    fun rejectsOtherExtensionsAndNull() {
        assertFalse(ModelFileRules.isSupportedImportName("model.bin"))
        assertFalse(ModelFileRules.isSupportedImportName("model.litertlm.zip"))
        assertFalse(ModelFileRules.isSupportedImportName(null))
    }

    @Test
    fun minimumSizeIsFiftyMebibytes() {
        assertEquals(50L * 1024L * 1024L, ModelFileRules.MIN_MODEL_BYTES)
        assertTrue(ModelFileRules.isSizeValid(ModelFileRules.MIN_MODEL_BYTES))
        assertFalse(ModelFileRules.isSizeValid(ModelFileRules.MIN_MODEL_BYTES - 1))
    }
}
