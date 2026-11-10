package com.bitap.keyboard.core

/**
 * Pure, offline resolver for continuous mixed Chinese-English-digit input (D07).
 *
 * Given one raw Latin token and a prefix-search callback, it returns mixed
 * candidates whose word is a decoded CJK prefix followed by a canonical English
 * or digit suffix, for example:
 *
 *  - woshiandroid -> 我是Android (prefix woshi -> 我是, suffix android)
 *  - jiage123     -> 价格123
 *  - yongpython   -> 用Python
 *  - android      -> Android when the token has no full pinyin decoding
 *
 * Rules:
 *  1. A whole-token English candidate is emitted only when the callback finds
 *     no full-coverage CJK candidate for the whole token. A complete pinyin
 *     decoding always wins, so fangan stays 方案 and never becomes 放an.
 *  2. Otherwise suffixes are tried from longest to shortest. A suffix must be
 *     all digits, or a lexicon entry that is either at least
 *     MIN_ENGLISH_SUFFIX_LENGTH characters or a known acronym/proper noun.
 *     This blocks short function words (a, an, in, to) from hijacking ordinary
 *     pinyin fragments.
 *  3. The prefix must be non-empty lowercase ASCII letters and must decode to
 *     a full-coverage CJK candidate.
 *  4. Every returned candidate consumes the whole raw token
 *     (Candidate.consumedInputLength equal to raw.length) and keeps
 *     pinyin = raw. At most MAX_CANDIDATES candidates are returned, deduped
 *     by word.
 *
 * The resolver is stateless, does no I/O, never touches the Android platform,
 * and never calls PinyinInputEngine (which would recurse); production
 * integration passes PinyinEngine.search in.
 */
object MixedInputResolver {

    /** Upper bound on mixed candidates produced for one token. */
    const val MAX_CANDIDATES = 3

    /** Same token budget as EnglishLexicon. */
    const val MAX_TOKEN_LENGTH = EnglishLexicon.MAX_TOKEN_LENGTH

    /** Shortest plain-English suffix accepted during suffix splitting. */
    private const val MIN_ENGLISH_SUFFIX_LENGTH = 3

    /**
     * Resolves raw into mixed candidates using searchPrefix to decode a
     * candidate pinyin prefix. limit is clamped to MAX_CANDIDATES.
     */
    fun resolve(
        raw: String,
        limit: Int = MAX_CANDIDATES,
        searchPrefix: (String) -> List<Candidate>,
    ): List<Candidate> {
        val capacity = limit.coerceAtMost(MAX_CANDIDATES)
        if (capacity <= 0 || !isValidToken(raw)) return emptyList()

        // A complete pinyin decoding wins over every mixed interpretation.
        val wholeTokenCandidates = searchPrefix(raw)
        if (wholeTokenCandidates.any { it.isFullCoverageOf(raw.length) && it.hasCjk() }) {
            return emptyList()
        }

        val results = ArrayList<Candidate>(capacity)
        val seenWords = HashSet<String>(capacity)

        // Whole-token English is only considered when pinyin cannot cover the token.
        EnglishLexicon.canonicalWord(raw)?.let { canonical ->
            addCandidate(results, seenWords, mixedCandidate(canonical, raw, frequency = 0L), capacity)
        }

        // Longest suffix first: split at 1 gives the longest suffix, split at
        // raw.length - 1 the shortest. Each split is a real token boundary.
        for (split in 1 until raw.length) {
            if (results.size >= capacity) break
            val suffix = raw.substring(split)
            val canonicalSuffix = canonicalSuffixOf(suffix) ?: continue
            val prefix = raw.substring(0, split)
            if (!prefix.isLowercaseAsciiLetters()) continue

            val prefixCandidates = searchPrefix(prefix)
            for (candidate in prefixCandidates) {
                if (results.size >= capacity) break
                if (!candidate.isFullCoverageOf(prefix.length) || !candidate.hasCjk()) continue
                addCandidate(
                    results,
                    seenWords,
                    mixedCandidate(
                        word = candidate.word + canonicalSuffix,
                        raw = raw,
                        frequency = candidate.frequency,
                    ),
                    capacity,
                )
            }
        }
        return results
    }

    private fun mixedCandidate(word: String, raw: String, frequency: Long): Candidate = Candidate(
        word = word,
        pinyin = raw,
        frequency = frequency,
        consumedInputLength = raw.length,
        isCorrection = false,
    )

    private fun addCandidate(
        results: MutableList<Candidate>,
        seenWords: MutableSet<String>,
        candidate: Candidate,
        capacity: Int,
    ) {
        if (results.size >= capacity) return
        if (!seenWords.add(candidate.word)) return
        results += candidate
    }

    /**
     * Canonical suffix for one split, or null when the suffix is not eligible.
     * Digits are always eligible; English entries need enough length to avoid
     * accidental function-word splits, unless they are known proper names.
     */
    private fun canonicalSuffixOf(suffix: String): String? {
        if (suffix.all { it in '0'..'9' }) return suffix
        val canonical = EnglishLexicon.canonicalWord(suffix) ?: return null
        if (suffix.length >= MIN_ENGLISH_SUFFIX_LENGTH) return canonical
        return if (EnglishLexicon.isProperName(suffix)) canonical else null
    }

    private fun isValidToken(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > MAX_TOKEN_LENGTH) return false
        if (raw.none { it in 'a'..'z' || it in 'A'..'Z' }) return false
        return raw.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    }

    private fun String.isLowercaseAsciiLetters(): Boolean =
        isNotEmpty() && all { it in 'a'..'z' }

    /**
     * True when the candidate covers at least length raw-input characters.
     * Zero follows the legacy Candidate convention and means the whole current
     * composition, so it counts as full coverage.
     */
    private fun Candidate.isFullCoverageOf(length: Int): Boolean =
        consumedInputLength == 0 || consumedInputLength >= length

    /** True when word contains at least one CJK Unified Ideograph. */
    private fun Candidate.hasCjk(): Boolean = word.any { it.isCjkIdeograph() }

    private fun Char.isCjkIdeograph(): Boolean =
        this in '\u4E00'..'\u9FFF' ||
            this in '\u3400'..'\u4DBF' ||
            this in '\uF900'..'\uFAFF'
}
