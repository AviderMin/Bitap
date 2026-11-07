package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2: general shorthand/mixed-pinyin index and lightweight typo correction. */
class PinyinEngineP2Test {

    private fun engine(): PinyinEngine = PinyinEngine.fromLines(
        sequenceOf(
            // single-character entries provide the generic syllable inventory
            "ni\t你\t800", "hao\t好\t700", "wo\t我\t800", "men\t们\t600",
            "ta\t他\t600", "zhong\t中\t500", "guo\t国\t500", "chang\t长\t400",
            "cheng\t城\t400", "shang\t上\t500", "hai\t海\t400", "chi\t吃\t500",
            "fan\t饭\t500", "shou\t手\t400", "ji\t机\t400", "shi\t时\t500",
            "jian\t间\t400", "tian\t天\t500", "qi\t气\t400", "yin\t银\t300",
            "hang\t行\t300", "xue\t学\t400", "xi\t西\t300", "an\t安\t300",
            "ping\t平\t300", "yao\t要\t500", "jin\t今\t400", "ming\t明\t400",
            "peng\t朋\t300", "you\t友\t300", "xian\t先\t500",
            // multi-character words; shorthand is generated from these readings
            "nihao\t你好\t500", "women\t我们\t450", "nimen\t你们\t300",
            "tamen\t他们\t300", "zhongguo\t中国\t400", "changcheng\t长城\t150",
            "shanghai\t上海\t250", "chifan\t吃饭\t260", "shouji\t手机\t220",
            "shijian\t时间\t240", "tianqi\t天气\t180", "yinhang\t银行\t120",
            "xuexi\t学习\t200", "xian\t西安\t120", "pingan\t平安\t90",
            "mingtian\t明天\t200", "jintian\t今天\t210", "pengyou\t朋友\t190",
        ),
    )

    @Test
    fun fullShorthandIsGeneratedFromGenericSyllableIndex() {
        val engine = engine()
        // These keys are not hand-written: nh/wm/zg/cc/sh/yh/xx all come from
        // the dictionary syllable segmentation.
        assertTrue("nh -> 你好", engine.search("nh").any { it.word == "你好" })
        assertTrue("wm -> 我们", engine.search("wm").any { it.word == "我们" })
        assertTrue("zg -> 中国", engine.search("zg").any { it.word == "中国" })
        assertTrue("cc -> 长城", engine.search("cc").any { it.word == "长城" })
        assertTrue("sh -> 上海", engine.search("sh").any { it.word == "上海" })
        assertTrue("yh -> 银行", engine.search("yh").any { it.word == "银行" })
        assertTrue("xx -> 学习", engine.search("xx").any { it.word == "学习" })
    }

    @Test
    fun mixedPinyinCombinesFullSyllablesWithInitials() {
        val engine = engine()
        assertTrue("zhongg -> 中国", engine.search("zhongg").any { it.word == "中国" })
        assertTrue("chif -> 吃饭", engine.search("chif").any { it.word == "吃饭" })
        assertTrue("shangh -> 上海", engine.search("shangh").any { it.word == "上海" })
        assertTrue("mingt -> 明天", engine.search("mingt").any { it.word == "明天" })
        assertTrue("pengy -> 朋友", engine.search("pengy").any { it.word == "朋友" })
    }

    @Test
    fun zeroInitialAndSingleLetterInitialsFollowGenericRules() {
        val engine = engine()
        // 西安 = xi + an -> x + a; 平安 = ping + an -> p + a
        assertTrue("xa -> 西安", engine.search("xa").any { it.word == "西安" })
        assertTrue("pa -> 平安", engine.search("pa").any { it.word == "平安" })
        // Ambiguous shorthand returns several plausible words rather than one
        // hard-coded winner.
        val sj = engine.search("sj").map { it.word }
        assertTrue("sj should include 手机", sj.contains("手机"))
        assertTrue("sj should include 时间", sj.contains("时间"))
    }

    @Test
    fun typoCorrectionsCoverMissingExtraSwapAndAdjacentKey() {
        val engine = engine()
        fun corrected(input: String, expected: String): Boolean = engine.search(input).any {
            it.word == expected && it.isCorrection
        }
        // nhao is also a valid mixed-pinyin key for 你好 (ni + hao), so it may
        // be matched as a normal candidate rather than a correction.
        assertTrue("missing letter nhao", engine.search("nhao").any { it.word == "你好" })
        assertTrue("extra letter nihaoa", corrected("nihaoa", "你好"))
        assertTrue("adjacent swap nihoa", corrected("nihoa", "你好"))
        assertTrue("adjacent key nihap", corrected("nihap", "你好"))
        assertTrue("extra letter jintiann", corrected("jintiann", "今天"))
    }

    @Test
    fun correctPinyinIsNeverReplacedByCorrection() {
        val engine = engine()
        val first = engine.search("nihao").first()
        assertEquals("你好", first.word)
        assertFalse("correct candidate must not be marked as correction", first.isCorrection)
    }

    @Test
    fun garbageDoesNotProduceForcedCorrections() {
        assertTrue(engine().search("zzzz").isEmpty())
    }
}
