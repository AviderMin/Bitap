package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** T07: one backspace must remove one visible Unicode character, not one code unit. */
class UnicodeGraphemeTest {

    private fun backspaceOnce(text: String): String = UnicodeGrapheme.dropLastGrapheme(text)

    @Test
    fun ordinaryLatinAndCjkCharactersDeleteOneCodePoint() {
        assertEquals("ab", backspaceOnce("abc"))
        assertEquals("你", backspaceOnce("你好"))
        assertEquals("", backspaceOnce("a"))
    }

    @Test
    fun emojiSurrogatePairsAndSkinToneModifiersStayWhole() {
        assertEquals("", backspaceOnce("👍"))
        assertEquals("", backspaceOnce("👍🏽"))
        assertEquals("ab", backspaceOnce("ab👍🏽"))
    }

    @Test
    fun zwjFamilySequencesAndVariationSelectorsStayWhole() {
        assertEquals("", backspaceOnce("👨‍👩‍👧‍👦"))
        assertEquals("a", backspaceOnce("a👩🏽‍🚀"))
        assertEquals("a", backspaceOnce("a❤️"))
        assertEquals("", backspaceOnce("1️⃣"))
    }

    @Test
    fun regionalIndicatorFlagsDeleteAsPairs() {
        assertEquals("", backspaceOnce("🇨🇳"))
        assertEquals("a", backspaceOnce("a🇨🇳"))
        // Two flags in sequence: one backspace removes the last flag, not both.
        assertEquals("🇨🇳", backspaceOnce("🇨🇳🇺🇸"))
        assertEquals("🇨🇳🇺🇸", backspaceOnce("🇨🇳🇺🇸a"))
    }

    @Test
    fun combiningMarksDeleteWithTheirBaseCharacter() {
        assertEquals("", backspaceOnce("e\u0301"))
        assertEquals("a", backspaceOnce("ae\u0301"))
        assertEquals("", backspaceOnce("a\u0301\u0323"))
    }

    @Test
    fun lastGraphemeLengthUsesUtf16CodeUnits() {
        assertEquals(1, UnicodeGrapheme.lastGraphemeLength("abc"))
        assertEquals(2, UnicodeGrapheme.lastGraphemeLength("a👍"))
        assertEquals(4, UnicodeGrapheme.lastGraphemeLength("x🇨🇳"))
        assertEquals(11, UnicodeGrapheme.lastGraphemeLength("👨‍👩‍👧‍👦"))
        assertEquals(2, UnicodeGrapheme.lastGraphemeLength("e\u0301"))
    }

    @Test
    fun forwardTraversalMovesOneGraphemeAtATime() {
        val text = "A😀B"
        assertEquals(1, UnicodeGrapheme.nextBoundary(text, 0))
        assertEquals(3, UnicodeGrapheme.nextBoundary(text, 1))
        assertEquals(4, UnicodeGrapheme.nextBoundary(text, 3))
        assertEquals(4, UnicodeGrapheme.nextBoundary(text, 4))
        assertEquals(3, UnicodeGrapheme.moveBy(text, 0, 2))
    }

    @Test
    fun backwardTraversalMovesOneGraphemeAtATime() {
        val text = "A😀B"
        assertEquals(3, UnicodeGrapheme.previousBoundary(text, 4))
        assertEquals(1, UnicodeGrapheme.previousBoundary(text, 3))
        assertEquals(0, UnicodeGrapheme.previousBoundary(text, 1))
        assertEquals(0, UnicodeGrapheme.previousBoundary(text, 0))
        assertEquals(1, UnicodeGrapheme.moveBy(text, 4, -2))
    }

    @Test
    fun forwardAndBackwardTraversalKeepsFlagsWhole() {
        val text = "a🇨🇳🇺🇸b"
        assertEquals(5, UnicodeGrapheme.nextBoundary(text, 1))
        assertEquals(9, UnicodeGrapheme.nextBoundary(text, 5))
        assertEquals(10, UnicodeGrapheme.nextBoundary(text, 9))
        assertEquals(9, UnicodeGrapheme.previousBoundary(text, 10))
        assertEquals(5, UnicodeGrapheme.previousBoundary(text, 9))
        assertEquals(1, UnicodeGrapheme.previousBoundary(text, 5))
        assertEquals(2, UnicodeGrapheme.graphemeCount("🇨🇳🇺🇸"))
    }

    @Test
    fun forwardAndBackwardTraversalKeepsZwjFamilyWhole() {
        val text = "X👨‍👩‍👧‍👦Y"
        assertEquals(12, UnicodeGrapheme.nextBoundary(text, 1))
        assertEquals(13, UnicodeGrapheme.nextBoundary(text, 12))
        assertEquals(12, UnicodeGrapheme.previousBoundary(text, 13))
        assertEquals(1, UnicodeGrapheme.previousBoundary(text, 12))
        assertEquals(1, UnicodeGrapheme.moveBy(text, 13, -2))
    }

    @Test
    fun forwardAndBackwardTraversalKeepsCombiningMarksWithBase() {
        val text = "ae\u0301b"
        assertEquals(1, UnicodeGrapheme.nextBoundary(text, 0))
        assertEquals(3, UnicodeGrapheme.nextBoundary(text, 1))
        assertEquals(1, UnicodeGrapheme.previousBoundary(text, 3))
        assertEquals(3, UnicodeGrapheme.moveBy(text, 1, 1))
        assertEquals(1, UnicodeGrapheme.moveBy(text, 3, -1))
    }

    @Test
    fun graphemeCountCountsVisibleCharacters() {
        assertEquals(3, UnicodeGrapheme.graphemeCount("A😀B"))
        assertEquals(1, UnicodeGrapheme.graphemeCount("👨‍👩‍👧‍👦"))
        assertEquals(2, UnicodeGrapheme.graphemeCount("e\u0301a"))
        assertEquals(0, UnicodeGrapheme.graphemeCount(""))
    }

    @Test
    fun wordBoundariesNeverSplitSurrogatePairs() {
        assertEquals(1, WordBoundary.nextWordEnd("A😀B", 0))
        assertEquals(3, WordBoundary.previousWordStart("A😀B", 4))
        assertEquals(1, WordBoundary.previousWordStart("A😀B", 3))
    }

    @Test
    fun wordDeletionKeepsZwjFamilyAndSkinToneWhole() {
        val family = "A👨‍👩‍👧‍👦"
        assertEquals(1, WordBoundary.previousWordStart(family, family.length))
        assertEquals(family.length, WordBoundary.nextWordEnd(family, 1))
        assertEquals(11, WordBoundary.backwardDeletionLength(family, family.length))

        val rocket = "a👩🏽‍🚀"
        assertEquals(1, WordBoundary.previousWordStart(rocket, rocket.length))
        assertEquals(rocket.length, WordBoundary.nextWordEnd(rocket, 1))
        assertEquals(rocket.length - 1, WordBoundary.backwardDeletionLength(rocket, rocket.length))
    }

    @Test
    fun wordDeletionKeepsFlagsAndCombiningMarksWhole() {
        val flag = "a🇨🇳"
        assertEquals(1, WordBoundary.previousWordStart(flag, flag.length))
        assertEquals(flag.length, WordBoundary.nextWordEnd(flag, 1))
        assertEquals(4, WordBoundary.backwardDeletionLength(flag, flag.length))

        val accented = "aé"
        assertEquals(1, WordBoundary.previousWordStart(accented, accented.length))
        assertEquals(accented.length, WordBoundary.nextWordEnd(accented, 1))
        assertEquals(2, WordBoundary.backwardDeletionLength(accented, accented.length))
    }

    @Test
    fun wordBoundariesKeepNormalWordsAndCjkSemantics() {
        val sentence = "hello world"
        assertEquals(6, WordBoundary.previousWordStart(sentence, sentence.length))
        assertEquals(5, WordBoundary.nextWordEnd(sentence, 0))
        assertEquals(5, WordBoundary.backwardDeletionLength(sentence, 5))
        assertEquals(1, WordBoundary.previousWordStart("你好", 2))
        assertEquals(1, WordBoundary.nextWordEnd("你好", 0))
    }

    @Test
    fun longAsciiWordAndIdentifierBoundariesStayExact() {
        val word = "a".repeat(4096)
        assertEquals(0, WordBoundary.previousWordStart(word, word.length))
        assertEquals(word.length, WordBoundary.nextWordEnd(word, 0))
        assertEquals(word.length, WordBoundary.backwardDeletionLength(word, word.length))

        val identifier = "a1".repeat(2048)
        assertEquals(0, WordBoundary.previousWordStart(identifier, identifier.length))
        assertEquals(identifier.length, WordBoundary.nextWordEnd(identifier, 0))
    }

    @Test
    fun longCjkAndSymbolRunsKeepExistingSemantics() {
        val cjk = "你".repeat(100)
        assertEquals(99, WordBoundary.previousWordStart(cjk, cjk.length))
        assertEquals(1, WordBoundary.nextWordEnd(cjk, 0))

        val emoji = "😀".repeat(100)
        assertEquals(emoji.length - 2, WordBoundary.previousWordStart(emoji, emoji.length))
        assertEquals(2, WordBoundary.nextWordEnd(emoji, 0))
    }

    @Test
    fun longWordBoundaryScanAllocatesBoundedMemory() {
        // java.lang.management is not on the Android unit-test compile classpath,
        // so probe the JVM's allocation counter reflectively and skip the test
        // when the metrics are unavailable.
        val allocationProbe = runCatching {
            val bean = Class.forName("java.lang.management.ManagementFactory")
                .getMethod("getThreadMXBean")
                .invoke(null)!!
            val threadMxBean = Class.forName("com.sun.management.ThreadMXBean")
            check(threadMxBean.isInstance(bean)) { "JDK thread bean lacks allocation metrics" }
            check(threadMxBean.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true)
            if (threadMxBean.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) != true) {
                threadMxBean
                    .getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType!!)
                    .invoke(bean, true)
            }
            check(threadMxBean.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) == true)
            val bytesMethod = threadMxBean
                .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType!!)
            bean to bytesMethod
        }.getOrNull()
        assumeTrue("thread allocation metrics unavailable", allocationProbe != null)
        val (bean, allocatedBytesMethod) = allocationProbe!!

        // Warm up on a short word so the measured call exercises steady state.
        val warmup = "a".repeat(256)
        repeat(3) {
            WordBoundary.previousWordStart(warmup, warmup.length)
            WordBoundary.nextWordEnd(warmup, 0)
        }
        val word = "a".repeat(4096)
        val threadId = Thread.currentThread().id
        val allocatedBefore = allocatedBytesMethod.invoke(bean, threadId) as Long
        val startedAt = System.nanoTime()
        val start = WordBoundary.previousWordStart(word, word.length)
        val end = WordBoundary.nextWordEnd(word, 0)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
        val allocatedBytes = (allocatedBytesMethod.invoke(bean, threadId) as Long) - allocatedBefore

        assertEquals(0, start)
        assertEquals(word.length, end)
        val allocatedMiB = allocatedBytes / (1024.0 * 1024.0)
        println(
            "long-word boundary scan: n=${word.length} elapsedMs=%.3f allocatedMiB=%.2f"
                .format(elapsedMs, allocatedMiB),
        )
        assertTrue(
            "one 4096-char word scan should allocate far below 8 MiB, was %.2f MiB".format(allocatedMiB),
            allocatedBytes < 8L * 1024 * 1024,
        )
    }

    @Test
    fun clusterBoundaryPredicateHandlesSurrogatesZwjAndFlags() {
        assertTrue(UnicodeGrapheme.isClusterBoundary("abc", 0))
        assertTrue(UnicodeGrapheme.isClusterBoundary("abc", 1))
        assertTrue(UnicodeGrapheme.isClusterBoundary("abc", 3))
        assertFalse(UnicodeGrapheme.isClusterBoundary("e\u0301", 1))
        assertTrue(UnicodeGrapheme.isClusterBoundary("e\u0301", 2))
        assertFalse("an offset inside a surrogate pair is never a boundary", UnicodeGrapheme.isClusterBoundary("A😀B", 2))
        assertTrue(UnicodeGrapheme.isClusterBoundary("A😀B", 1))
        assertTrue(UnicodeGrapheme.isClusterBoundary("A😀B", 3))
        assertFalse("ZWJ keeps the family cluster whole", UnicodeGrapheme.isClusterBoundary("👨‍👩‍👧‍👦", 2))
        assertFalse("a flag pair has no internal boundary", UnicodeGrapheme.isClusterBoundary("🇨🇳", 2))
        assertTrue("the boundary between two flag pairs is real", UnicodeGrapheme.isClusterBoundary("🇨🇳🇺🇸", 4))
        assertFalse("the second flag of a pair joins its predecessor", UnicodeGrapheme.isClusterBoundary("🇨🇳🇺🇸", 6))
    }
}
