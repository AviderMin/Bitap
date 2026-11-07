package com.bitap.keyboard.ai.internal

/**
 * Offline pronunciation lookup used to verify that a Chinese candidate really
 * matches the pinyin the user typed.
 *
 * The data comes from the packaged [pinyin_dict.tsv] asset (pinyin, word,
 * frequency). No network, no logging and no disk writes are involved.
 */
class AiPinyinDictionary private constructor(
    private val wordToPinyins: Map<String, Set<String>>,
) {

    /** Single-character readings used to verify new multi-character words. */
    private val charToPinyins: Map<Char, Set<String>> = wordToPinyins
        .filterKeys { it.length == 1 }
        .mapKeys { it.key[0] }
        .mapValues { it.value }

    fun matches(word: String, pinyinQuery: String): Boolean {
        val candidate = word.trim()
        if (candidate.isEmpty()) return false
        val query = normalizePinyin(pinyinQuery)
        if (query.isEmpty()) return false
        val directReadings = wordToPinyins[candidate]
        if (directReadings != null && directReadings.any { it.startsWith(query) }) return true
        return matchesComposedWord(candidate, query)
    }

    /**
     * Trusted offline character composition: a new word is accepted only when
     * every character has a known reading and at least one reading combination
     * starts with the user's pinyin (or is a prefix of it). This permits
     * genuinely new words without reopening the gate to arbitrary Chinese.
     */
    private fun matchesComposedWord(candidate: String, query: String): Boolean {
        if (candidate.length !in 2..MAX_COMPOSED_WORD_CHARS) return false
        val readingsByChar = candidate.map { char -> charToPinyins[char] ?: return false }
        fun search(index: Int, prefix: String): Boolean {
            if (index == candidate.length) return prefix.startsWith(query)
            for (reading in readingsByChar[index]) {
                val next = prefix + reading
                if (next.length > query.length && !next.startsWith(query)) continue
                if (search(index + 1, next)) return true
            }
            return false
        }
        return search(0, "")
    }

    fun containsWord(word: String): Boolean = wordToPinyins.containsKey(word.trim())

    val size: Int
        get() = wordToPinyins.size

    companion object {
        private const val MAX_COMPOSED_WORD_CHARS = 4

        val EMPTY: AiPinyinDictionary = AiPinyinDictionary(emptyMap())

        fun parse(lines: Sequence<String>): AiPinyinDictionary {
            val map = LinkedHashMap<String, MutableSet<String>>()
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split('\t')
                if (parts.size < 2) continue
                val pronunciation = normalizePinyin(parts[0])
                val word = parts[1].trim()
                if (pronunciation.isEmpty() || word.isEmpty()) continue
                if (word.any { it.isWhitespace() }) continue
                map.getOrPut(word) { LinkedHashSet() }.add(pronunciation)
            }
            // Trusted offline readings for common polyphonic words. The TSV
            // keeps one convenience reading per word; these additions prevent
            // legitimate candidates from being rejected.
            for ((word, readings) in AiPolyphonicReadings.EXTRA) {
                map[word]?.addAll(readings)
            }
            return AiPinyinDictionary(map.mapValues { entry -> entry.value.toSet() })
        }

        fun fromMap(source: Map<String, Set<String>>): AiPinyinDictionary {
            val normalized = source.entries
                .map { it.key.trim() to it.value.map(::normalizePinyin).filter { p -> p.isNotEmpty() }.toSet() }
                .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
                .associate { it.first to it.second }
            return AiPinyinDictionary(normalized)
        }

        fun normalizePinyin(input: String): String {
            val builder = StringBuilder(input.length)
            for (char in input.lowercase()) {
                if (char in 'a'..'z') builder.append(char)
            }
            return builder.toString()
        }
    }
}

/** Small, curated pronunciation map for common polyphonic words. */
internal object AiPolyphonicReadings {
    val EXTRA: Map<String, Set<String>> = mapOf(
        "行" to setOf("xing", "hang"),
        "长" to setOf("zhang", "chang"),
        "重" to setOf("zhong", "chong"),
        "乐" to setOf("le", "yue"),
        "了" to setOf("le", "liao"),
        "的" to setOf("de", "di"),
        "还" to setOf("hai", "huan"),
        "都" to setOf("dou", "du"),
        "和" to setOf("he", "huo"),
        "得" to setOf("de", "dei"),
        "地" to setOf("di", "de"),
        "着" to setOf("zhe", "zhao", "zhuo"),
    )
}
