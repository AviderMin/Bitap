package com.bitap.keyboard.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real-asset regression for sentence-final particle shorthand (D07 follow-up):
 * users type nihaom -> 你好吗 and zenml -> 怎么了, not just the full pinyin
 * nihaoma/zenmele.
 */
class PinyinParticleSuffixTest {
    @Test
    fun realAssetParticleShorthandQueries() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/pinyin_dict.tsv").isFile } ?: return
        val engine = PinyinEngine.fromLines(
            File(root, "app/src/main/assets/pinyin_dict.tsv").readLines().asSequence(),
        )
        assertEquals("你好吗", engine.search("nihaom", limit = 8).first().word)
        assertEquals("怎么了", engine.search("zenml", limit = 8).first().word)
        assertEquals("你好吗", engine.search("nihaoma", limit = 8).first().word)
        assertEquals("怎么了", engine.search("zenmele", limit = 8).first().word)
        assertEquals("怎么", engine.search("zenm", limit = 8).first().word)
    }
}
