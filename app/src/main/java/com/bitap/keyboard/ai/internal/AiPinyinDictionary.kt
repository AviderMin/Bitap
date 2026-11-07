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

    fun matches(word: String, pinyinQuery: String): Boolean {
        val candidate = word.trim()
        if (candidate.isEmpty()) return false
        val query = normalizePinyin(pinyinQuery)
        if (query.isEmpty()) return false
        val readings = wordToPinyins[candidate] ?: return false
        return readings.any { reading -> reading.startsWith(query) }
    }

    fun containsWord(word: String): Boolean = wordToPinyins.containsKey(word.trim())

    val size: Int
        get() = wordToPinyins.size

    companion object {
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
