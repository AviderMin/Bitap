package com.bitap.keyboard.core

import com.bitap.keyboard.core.engine.CandidateSource
import com.bitap.keyboard.core.engine.InputEngine
import com.bitap.keyboard.core.engine.PinyinInputEngine
import com.bitap.keyboard.core.engine.QueryRequest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D07: EnglishLexicon + MixedInputResolver contract and the PinyinInputEngine
 * integration (mixed candidates prepended, Other source, dedupe, limit,
 * unchanged pure-pinyin behavior).
 */
class MixedInputResolverTest {

    private fun request(input: String, limit: Int = InputEngine.DEFAULT_LIMIT): QueryRequest =
        QueryRequest(
            sessionId = "session-d07",
            compositionVersion = 7L,
            rawInput = input,
            limit = limit,
        )

    private fun engine(): PinyinEngine = PinyinEngine.fromLines(
        sequenceOf(
            "# Bitap D07 mixed input test dictionary",
            "woshi	我是	100",
            "jiage	价格	100",
            "fangan	方案	100",
            "yong	用	100",
            "nihao	你好	100",
            "an	安	100",
        ),
    )

    /** Separate fixture for duplicate-by-word coverage (baseline also emits Android). */
    private fun engineWithEnglishAndroid(): PinyinEngine = PinyinEngine.fromLines(
        sequenceOf(
            "android	Android	100",
        ),
    )

    private fun fullCandidate(
        word: String,
        pinyin: String,
        consumed: Int,
        frequency: Long = 100L,
    ): Candidate = Candidate(
        word = word,
        pinyin = pinyin,
        frequency = frequency,
        consumedInputLength = consumed,
    )

    // ------------------------------------------------------------------
    // EnglishLexicon
    // ------------------------------------------------------------------

    @Test
    fun lexiconCanonicalizesRequiredTechNamesCaseInsensitively() {
        val expected = mapOf(
            "android" to "Android",
            "kotlin" to "Kotlin",
            "python" to "Python",
            "github" to "GitHub",
            "redis" to "Redis",
            "docker" to "Docker",
            "json" to "JSON",
            "sql" to "SQL",
            "aws" to "AWS",
            "api" to "API",
            "iphone" to "iPhone",
            "windows" to "Windows",
            "linux" to "Linux",
        )
        expected.forEach { (lower, canonical) ->
            assertEquals("lowercase $lower", canonical, EnglishLexicon.canonicalWord(lower))
            assertEquals("uppercase $lower", canonical, EnglishLexicon.canonicalWord(lower.uppercase()))
            assertEquals("lookup $lower", canonical, EnglishLexicon.lookup(lower))
        }
        assertEquals("hello", EnglishLexicon.canonicalWord("HELLO"))
        assertNull(EnglishLexicon.canonicalWord(""))
        assertNull(EnglishLexicon.canonicalWord("notalexiconword"))
        assertNull(EnglishLexicon.canonicalWord("a".repeat(EnglishLexicon.MAX_TOKEN_LENGTH + 1)))
        assertTrue(EnglishLexicon.isProperName("API"))
        assertFalse(EnglishLexicon.isProperName("hello"))
        assertTrue(EnglishLexicon.size > 100)
    }

    // ------------------------------------------------------------------
    // MixedInputResolver
    // ------------------------------------------------------------------

    @Test
    fun resolverBuildsMixedCandidateFromPinyinPrefixAndEnglishSuffix() {
        val queries = mutableListOf<String>()
        val result = MixedInputResolver.resolve("woshiandroid") { prefix ->
            queries += prefix
            if (prefix == "woshi") listOf(fullCandidate("我是", "woshi", 5)) else emptyList()
        }
        assertEquals(1, result.size)
        val candidate = result.single()
        assertEquals("我是Android", candidate.word)
        assertEquals("woshiandroid", candidate.pinyin)
        assertEquals(100L, candidate.frequency)
        assertEquals("woshiandroid".length, candidate.consumedInputLength)
        assertFalse(candidate.isCorrection)
        assertEquals("woshiandroid", queries.first())
    }

    @Test
    fun resolverAppendsDigitSuffix() {
        val result = MixedInputResolver.resolve("jiage123") { prefix ->
            if (prefix == "jiage") listOf(fullCandidate("价格", "jiage", 5, 80L)) else emptyList()
        }
        assertEquals(1, result.size)
        assertEquals("价格123", result.single().word)
        assertEquals("jiage123", result.single().pinyin)
        assertEquals("jiage123".length, result.single().consumedInputLength)
        assertEquals(80L, result.single().frequency)
    }

    @Test
    fun resolverTriesLongestSuffixFirst() {
        val result = MixedInputResolver.resolve("woshiphone") { prefix ->
            when (prefix) {
                "woshi" -> listOf(fullCandidate("我是", "woshi", 5))
                "woshiph" -> listOf(fullCandidate("我", "woshiph", 7))
                else -> emptyList()
            }
        }
        assertEquals(listOf("我是phone", "我one"), result.map { it.word })
    }

    @Test
    fun wholeTokenEnglishOnlyWithoutFullCoveragePinyin() {
        // Full pinyin decoding wins: 安 covers "an", so no English "an" candidate.
        val blocked = MixedInputResolver.resolve("an") {
            listOf(fullCandidate("安", "an", 2))
        }
        assertTrue(blocked.isEmpty())

        // Partial pinyin does not block the whole-token English fallback.
        val allowed = MixedInputResolver.resolve("an") {
            listOf(fullCandidate("安", "an", 1))
        }
        assertEquals(listOf("an"), allowed.map { it.word })

        val android = MixedInputResolver.resolve("android") { emptyList() }
        assertEquals(listOf("Android"), android.map { it.word })
        assertEquals("android", android.single().pinyin)
        assertEquals("android".length, android.single().consumedInputLength)
    }

    @Test
    fun resolverRequiresFullCoverageCjkPrefixCandidate() {
        val partial = MixedInputResolver.resolve("woshiandroid") { prefix ->
            if (prefix == "woshi") listOf(fullCandidate("我是", "woshi", 2)) else emptyList()
        }
        assertTrue("partial prefix candidate must not mix", partial.isEmpty())

        val nonCjk = MixedInputResolver.resolve("woshiandroid") { prefix ->
            if (prefix == "woshi") listOf(fullCandidate("abc", "woshi", 5)) else emptyList()
        }
        assertTrue("non-CJK prefix candidate must not mix", nonCjk.isEmpty())
    }

    @Test
    fun resolverRejectsShortLowerCaseFunctionWordSuffixes() {
        // "an" is in the language, but suffix matching must not use it.
        val queries = mutableListOf<String>()
        val result = MixedInputResolver.resolve("fangan") { prefix ->
            queries += prefix
            if (prefix == "fang") listOf(fullCandidate("放", "fang", 4)) else emptyList()
        }
        assertTrue(result.isEmpty())
        assertEquals(listOf("fangan"), queries)

        // A short proper acronym with canonical casing is allowed.
        val acronym = MixedInputResolver.resolve("woshiai") { prefix ->
            if (prefix == "woshi") listOf(fullCandidate("我是", "woshi", 5)) else emptyList()
        }
        assertEquals(listOf("我是AI"), acronym.map { it.word })
    }

    @Test
    fun resolverAcceptsUppercaseSuffixButRequiresLowercasePrefix() {
        val upperSuffix = MixedInputResolver.resolve("woshiANDROID") { prefix ->
            if (prefix == "woshi") listOf(fullCandidate("我是", "woshi", 5)) else emptyList()
        }
        assertEquals(listOf("我是Android"), upperSuffix.map { it.word })

        val upperPrefix = MixedInputResolver.resolve("WoshiAndroid") { emptyList() }
        assertTrue("prefix must be lowercase ASCII letters", upperPrefix.isEmpty())
    }

    @Test
    fun resolverCapsCandidatesAndDeduplicatesByWord() {
        val prefixCandidates = listOf(
            fullCandidate("我是", "woshi", 5),
            fullCandidate("我是", "woshi", 5),
            fullCandidate("我时", "woshi", 5),
            fullCandidate("我事", "woshi", 5),
        )
        val result = MixedInputResolver.resolve("woshiandroid") { prefix ->
            if (prefix == "woshi") prefixCandidates else emptyList()
        }
        assertEquals(MixedInputResolver.MAX_CANDIDATES, result.size)
        assertEquals(result.size, result.map { it.word }.distinct().size)
        assertEquals("我是Android", result.first().word)

        val limited = MixedInputResolver.resolve("woshiandroid", limit = 1) { prefix ->
            if (prefix == "woshi") prefixCandidates else emptyList()
        }
        assertEquals(1, limited.size)
    }

    @Test
    fun resolverIgnoresEmptyInvalidAndOverlongTokens() {
        val invocations = mutableListOf<String>()
        val invalid = listOf(
            "",
            "123",
            "woshi android",
            "woshi-android",
            "woshi_android",
            "a".repeat(EnglishLexicon.MAX_TOKEN_LENGTH + 1),
        )
        invalid.forEach { raw ->
            val result = MixedInputResolver.resolve(raw) { prefix ->
                invocations += prefix
                emptyList()
            }
            assertTrue("expected no candidates for '$raw'", result.isEmpty())
        }
        assertTrue("invalid tokens must not reach the search callback", invocations.isEmpty())
    }

    // ------------------------------------------------------------------
    // PinyinInputEngine integration
    // ------------------------------------------------------------------

    @Test
    fun pinyinInputEnginePrependsMixedCandidatesWithOtherSource() {
        val adapter = PinyinInputEngine(engine())
        val result = adapter.query(request("woshiandroid"))
        assertEquals(0, result.candidates.indexOfFirst { it.word == "我是Android" })
        val mixed = result.candidates.first { it.word == "我是Android" }
        assertEquals("woshiandroid", mixed.pinyin)
        assertEquals("woshiandroid".length, mixed.consumedInputLength)
        assertEquals(CandidateSource.Other, mixed.source)
        assertFalse(mixed.isCorrection)
        assertEquals(result.candidates.size, result.diagnostics.returnedCount)
    }

    @Test
    fun pinyinInputEngineResolvesTheDocumentedMixedExamples() {
        val adapter = PinyinInputEngine(engine())
        assertEquals("价格123", adapter.query(request("jiage123")).candidates.first().word)
        assertEquals("用Python", adapter.query(request("yongpython")).candidates.first().word)
        assertEquals("Android", adapter.query(request("android")).candidates.first().word)
    }

    @Test
    fun pinyinInputEngineKeepsBaselineResultsForPurePinyin() {
        val legacy = engine()
        val adapter = PinyinInputEngine(legacy)
        listOf("nihao", "jiage", "fangan", "woshi", "an").forEach { input ->
            val expected = legacy.search(input)
            val actual = adapter.query(request(input)).candidates
            assertEquals("candidate count for $input", expected.size, actual.size)
            expected.forEachIndexed { index, candidate ->
                val adapted = actual[index]
                assertEquals("word[$index] for $input", candidate.word, adapted.word)
                assertEquals("pinyin[$index] for $input", candidate.pinyin, adapted.pinyin)
                assertEquals("frequency[$index] for $input", candidate.frequency, adapted.frequency)
                assertEquals(
                    "consumed[$index] for $input",
                    candidate.consumedInputLength,
                    adapted.consumedInputLength,
                )
                assertEquals("correction[$index] for $input", candidate.isCorrection, adapted.isCorrection)
                assertEquals("source[$index] for $input", CandidateSource.Dictionary, adapted.source)
            }
        }
    }

    @Test
    fun pinyinInputEngineRespectsLimitAndDedupesAgainstBaseline() {
        val adapter = PinyinInputEngine(engine())
        val one = adapter.query(request("woshiandroid", limit = 1))
        assertEquals(1, one.candidates.size)
        assertEquals("我是Android", one.candidates.single().word)

        val dedupAdapter = PinyinInputEngine(engineWithEnglishAndroid())
        val android = dedupAdapter.query(request("android"))
        assertEquals(1, android.candidates.count { it.word == "Android" })
        assertEquals(CandidateSource.Other, android.candidates.first { it.word == "Android" }.source)
    }

    @Test
    fun realAssetMixedInputExamples() {
        val workingDir = System.getProperty("user.dir") ?: return
        val root = generateSequence(File(workingDir)) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/pinyin_dict.tsv").isFile } ?: return
        val lines = File(root, "app/src/main/assets/pinyin_dict.tsv").readLines().asSequence()
        val adapter = PinyinInputEngine(PinyinEngine.fromLines(lines))
        val cases = mapOf(
            "daimajianchayongsonar" to "代码检查用Sonar",
            "zhegebanbenyongkotlin" to "这个版本用Kotlin",
            "jiage123" to "价格123",
            "yongpython" to "用Python",
        )
        cases.forEach { (input, expected) ->
            val candidates = adapter.query(request(input, limit = 8)).candidates
            assertTrue(
                "expected $expected for $input, got " + candidates.map { it.word },
                candidates.any { it.word == expected },
            )
        }
    }

    @Test
    fun cancelledRequestReturnsNoCandidatesEvenForMixedTokens() {
        val adapter = PinyinInputEngine(engine())
        val result = adapter.query(
            QueryRequest(
                sessionId = "session-d07",
                compositionVersion = 1L,
                rawInput = "woshiandroid",
                shouldContinue = { false },
            ),
        )
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.diagnostics.cancelled)
    }
}
