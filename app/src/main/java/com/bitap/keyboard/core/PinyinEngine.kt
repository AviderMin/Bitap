package com.bitap.keyboard.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.min

/**
 * Offline pinyin dictionary loaded from assets/pinyin_dict.tsv.
 *
 * Format: one entry per line, pinyin TAB word TAB frequency; blank lines and
 * lines starting with '#' are ignored. The index is built once on a background
 * thread and then only read, so [search] is thread-safe and allocation-light.
 *
 * Decoding is a data-driven word lattice:
 *  - the syllable inventory is derived from single-character dictionary
 *    entries (no hand-written syllable list);
 *  - dynamic programming combines dictionary words into sentences that need
 *    not exist as whole entries, scored with a log-frequency unigram language
 *    model plus generic collocation signals (adjacent words forming a longer
 *    dictionary entry, character bigram statistics, numeral+measure phrases);
 *  - full-sentence paths rank before prefix-only partial selections, and the
 *    learning boost is bounded so one partial pick cannot permanently bury a
 *    sensible whole sentence.
 */
class PinyinEngine private constructor(
    private var index: Index?,
    private var userLexiconStore: UserLexiconStore? = null,
) {

    private val selectionLock = Any()
    private val selectionCounts = HashMap<String, Int>()
    private val userEntries = LinkedHashMap<String, UserLexiconEntry>()
    private var lastClearGeneration = UserLexiconStore.currentClearGeneration()
    private var selectionsSinceDecay = 0

    /** Session learning switch; controller updates it from settings + editor policy. */
    var learningEnabled: Boolean = true
        private set

    init {
        loadPersistedSelectionWeights()
    }

    data class Entry(
        val pinyin: String,
        val word: String,
        val frequency: Long,
        /** Number of composing characters this entry covers; derived for phrases. */
        val consumedInputLength: Int = pinyin.length,
    )

    /** True once a dictionary has been loaded. */
    val isReady: Boolean
        get() = index?.keys?.isNotEmpty() == true

    val entryCount: Int
        get() = index?.entryCount ?: 0

    /**
     * Synchronous lookup used by tests and by [query] on Dispatchers.Default.
     *
     * Candidates from exact pinyin, longer pinyin prefixes, the phrase lattice,
     * shorthand/mixed-pinyin keys and (when correct candidates are weak)
     * bounded typo corrections are merged and ranked on one score scale.
     */
    fun search(
        input: String,
        limit: Int = DEFAULT_LIMIT,
        shouldContinue: () -> Boolean = { true },
    ): List<Candidate> {
        val cappedLimit = limit.coerceAtMost(DEFAULT_LIMIT)
        if (cappedLimit <= 0) return emptyList()
        syncClearGeneration()
        // Hard input budget: overlong/invalid composing must stay cheap and
        // deletable rather than occupying the decoder.
        if (input.length > MAX_SEARCH_INPUT_LENGTH) return emptyList()
        if (!shouldContinue()) return emptyList()
        val parsed = parseQuery(input)
        if (parsed.segments.isEmpty()) return emptyList()
        val current = index ?: return emptyList()
        val selectionWeights = snapshotSelectionWeights()
        val userSnapshot = snapshotUserEntries()
        val collector = CandidateCollector()

        if (parsed.segments.size > 1) {
            collectSegmented(parsed, current, userSnapshot, collector, shouldContinue)
        } else {
            collectContinuous(
                query = parsed.segments.single(),
                current = current,
                userSnapshot = userSnapshot,
                collector = collector,
                shouldContinue = shouldContinue,
            )
        }
        return collector.toCandidates(cappedLimit, parsed.consumedLength, selectionWeights)
    }

    // ------------------------------------------------------------------
    // Query parsing
    // ------------------------------------------------------------------

    private data class ParsedQuery(
        val segments: List<String>,
        /** Start/end offsets (in the normalized input) of each letter run. */
        val segmentStarts: List<Int>,
        val segmentEnds: List<Int>,
        /** Characters consumed by the whole query, used by the reducer. */
        val consumedLength: Int,
    )

    private fun parseQuery(input: String): ParsedQuery {
        val normalized = input
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('　', ' ')
            .lowercase()
        val segments = ArrayList<String>()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var position = 0
        while (position < normalized.length) {
            val char = normalized[position]
            if (char in 'a'..'z') {
                val start = position
                while (position < normalized.length && normalized[position] in 'a'..'z') position++
                segments += normalized.substring(start, position)
                starts += start
                ends += position
            } else {
                position++
            }
        }
        return ParsedQuery(segments, starts, ends, ends.lastOrNull() ?: 0)
    }

    // ------------------------------------------------------------------
    // Candidate collection and unified ranking
    // ------------------------------------------------------------------

    private data class Ranked(
        val word: String,
        val pinyin: String,
        val score: Double,
        /** Composing characters consumed; equal to query length for full candidates. */
        val consumed: Int,
        val frequency: Long,
        val order: Int,
        val isCorrection: Boolean = false,
        val isPrefix: Boolean = false,
    )

    private class CandidateCollector {
        private val byWord = HashMap<String, Ranked>()
        private var counter = 0

        fun bestFullScore(totalConsumed: Int): Double =
            byWord.values.filter { it.consumed >= totalConsumed }.maxOfOrNull { it.score } ?: Double.NEGATIVE_INFINITY

        fun add(candidate: Ranked) {
            val existing = byWord[candidate.word]
            if (existing == null) {
                byWord[candidate.word] = candidate.copy(order = counter++)
            } else if (candidate.score > existing.score + SCORE_EPSILON) {
                byWord[candidate.word] = candidate.copy(order = existing.order)
            }
        }

        fun toCandidates(
            limit: Int,
            totalConsumed: Int,
            selectionWeights: Map<String, Int>,
        ): List<Candidate> {
            if (byWord.isEmpty()) return emptyList()
            // Learning only re-orders full-coverage candidates; partial prefix
            // picks never get an absolute boost over whole sentences.
            fun boost(candidate: Ranked): Double = if (candidate.consumed < totalConsumed) {
                0.0
            } else {
                min((selectionWeights[candidate.word] ?: 0) * SELECTION_BOOST_PER_SELECTION, MAX_SELECTION_BOOST)
            }

            val all = byWord.values
            val full = ArrayList<Ranked>()
            val partial = ArrayList<Ranked>()
            all.forEach { candidate ->
                if (candidate.consumed >= totalConsumed) full += candidate else partial += candidate
            }
            full.sortWith(
                compareBy<Ranked> { it.isCorrection }
                    .thenBy { it.isPrefix && (selectionWeights[it.word] ?: 0) == 0 }
                    .thenByDescending { it.score + boost(it) }
                    .thenBy { it.order },
            )
            partial.sortWith(
                compareByDescending<Ranked> { it.score + boost(it) }.thenBy { it.order },
            )

            val out = ArrayList<Candidate>(min(limit, all.size))
            if (partial.isEmpty()) {
                full.forEach { ranked -> if (out.size < limit) out += ranked.toCandidate() }
                return out
            }
            // Reserve a slice for prefix selections so a long full-sentence
            // candidate page can never hide "我"/"我爱" style partial picks.
            val partialReserve = min(PARTIAL_QUOTA, maxOf(1, limit / 4))
            val fullBudget = maxOf(1, limit - partialReserve)
            full.take(fullBudget).forEach { out += it.toCandidate() }
            partial.take(partialReserve).forEach { out += it.toCandidate() }
            if (out.size < limit) {
                full.drop(fullBudget).forEach { ranked -> if (out.size < limit) out += ranked.toCandidate() }
                partial.drop(partialReserve).forEach { ranked -> if (out.size < limit) out += ranked.toCandidate() }
            }
            return out
        }

        private fun Ranked.toCandidate(): Candidate = Candidate(
            word = word,
            pinyin = pinyin,
            frequency = frequency,
            consumedInputLength = consumed,
            isCorrection = isCorrection,
        )
    }

    // ------------------------------------------------------------------
    // Continuous (no explicit syllable separators) decoding
    // ------------------------------------------------------------------

    private fun collectContinuous(
        query: String,
        current: Index,
        userSnapshot: Map<String, List<Entry>>,
        collector: CandidateCollector,
        shouldContinue: () -> Boolean,
    ) {
        val n = query.length
        val lnTotal = current.lnTotalFrequency

        // Exact whole-query dictionary words.
        entriesForPinyin(query, current, userSnapshot)
            .take(MAX_EXACT_ENTRIES)
            .forEach { entry ->
                if (!shouldContinue()) return
                collector.add(
                    Ranked(
                        word = entry.word,
                        pinyin = entry.pinyin,
                        score = exactWordScore(entry, current),
                        consumed = n,
                        frequency = entry.frequency,
                        order = 0,
                    ),
                )
            }

        // Words whose pronunciation merely starts with the typed input.
        collectPrefixEntries(query, current, userSnapshot, shouldContinue)
            .take(MAX_PREFIX_ENTRIES)
            .forEach { entry ->
                if (!shouldContinue()) return
                val omitted = (entry.pinyin.length - n).coerceAtLeast(0)
                collector.add(
                    Ranked(
                        word = entry.word,
                        pinyin = entry.pinyin,
                        // A prefix candidate has not consumed the untyped
                        // letters, so it must not earn the length bonus of the
                        // longer word (otherwise 意识形态 would outrank 意识).
                        score = wordBaseScore(entry.frequency, 1, lnTotal) -
                            PREFIX_BASE_PENALTY - PREFIX_PENALTY_PER_LETTER * omitted,
                        consumed = n,
                        frequency = entry.frequency,
                        order = 0,
                        isPrefix = true,
                    ),
                )
            }

        // Word-lattice sentence candidates (full paths and partial prefixes).
        if (shouldContinue()) {
            collectLattice(query, current, userSnapshot, collector, shouldContinue)
        }

        // Shorthand / mixed-pinyin index built from dictionary syllables.
        if (shouldContinue()) {
            current.shorthandByKey[query]
                ?.take(MAX_SHORTHAND_ENTRIES)
                ?.forEach { entry ->
                    if (!shouldContinue()) return
                    val omitted = (entry.pinyin.length - n).coerceAtLeast(0)
                    collector.add(
                        Ranked(
                            word = entry.word,
                            pinyin = entry.pinyin,
                            score = entryBaseScore(entry, current) +
                                SHORTHAND_CORPUS_WEIGHT * current.corpusWordScore(entry.word) -
                                SHORTHAND_PENALTY_PER_OMITTED_LETTER * omitted,
                            consumed = n,
                            frequency = entry.frequency,
                            order = 0,
                        ),
                    )
                }
        }

        // Light typo correction only fills space left by weak correct candidates;
        // a healthy phrase decodes normally, so correction variants can never
        // pollute a sentence that already has a plausible full-coverage parse.
        if (shouldContinue() && collector.bestFullScore(n) < CORRECTION_TRIGGER_SCORE) {
            collectCorrections(query, current, userSnapshot, collector, shouldContinue)
        }
    }

    /**
     * Base unigram score of one dictionary word: log frequency, a mild length
     * prior and a corpus character-bigram likelihood inside the word. The
     * corpus part separates homophone words with similar jieba frequencies
     * (再见 vs 在建, 到家 vs 道家) in a data-driven way.
     */
    private fun entryBaseScore(entry: Entry, index: Index): Double {
        val base = wordBaseScore(entry.frequency, entry.word.length, index.lnTotalFrequency)
        val internal = index.ngramModel?.wordInternalScore(entry.word) ?: 0.0
        return base + WORD_NGRAM_WEIGHT * internal
    }

    /**
     * Score of a word when it is one step of a multi-word lattice path.
     *
     * Single characters carry a data-driven prior/penalty from the corpus
     * character distribution: frequent function characters stay competitive
     * while rare homophones lose, without any hand-written stop list. Exact
     * one-syllable lookups bypass this penalty because there the single
     * character is the complete intended word.
     */
    private fun latticeWordScore(entry: Entry, index: Index): Double {
        val base = entryBaseScore(entry, index)
        if (entry.word.length == 1) {
            val ngram = index.ngramModel
            if (ngram != null) return base + CHAR_FREQ_WEIGHT * ngram.charPrior(entry.word[0])
            // No corpus model: fall back to a generic single-character penalty.
            return base - SINGLE_CHAR_FALLBACK_PENALTY
        }
        return base
    }

    /**
     * Word score with a bounded word-insertion cost. Subtracting the full
     * ln(total tokens) per word is the textbook unigram model, but it makes a
     * long sentence pay ~18 points for every extra word and therefore prefers
     * fewer, longer (sometimes wrong) compounds such as 九曲 over 就+去. The
     * compensation keeps a word-count penalty without that systematic bias;
     * single-character and rare-word penalties still control over-segmentation.
     */
    private fun wordBaseScore(frequency: Long, wordLength: Int, lnTotal: Double): Double =
        ln(frequency.toDouble() + 1.0) - (lnTotal - WORD_INSERTION_COMPENSATION) +
            WORD_LENGTH_BONUS * (wordLength - 1).coerceAtLeast(0)

    private fun exactWordScore(entry: Entry, index: Index): Double {
        var score = entryBaseScore(entry, index) + EXACT_WORD_BONUS
        if (index.entryCount >= MIN_EXACT_MATCH_BONUS_ENTRIES) score += EXACT_MATCH_BONUS
        if (entry.word.length == 1) score += SINGLE_CHAR_EXACT_BONUS
        return score + index.numeralExpressionBonus(entry.word)
    }

    private class PathNode(
        val word: String,
        val previous: PathNode?,
        val score: Double,
        val wordCount: Int,
    ) {
        fun text(): String {
            if (previous == null) return word
            val parts = ArrayList<String>(wordCount)
            var node: PathNode? = this
            while (node != null && node.previous != null) {
                parts += node.word
                node = node.previous
            }
            parts.reverse()
            return parts.joinToString("")
        }
    }

    /**
     * Word-lattice dynamic programming over the generic syllable boundaries.
     *
     * Every dictionary pinyin is a syllable sequence, so matching dictionary
     * keys between syllable boundaries is complete. The beam per position keeps
     * the best-scoring prefixes; the score is additive and only depends on the
     * previous word (for collocation bonuses), so a bounded beam preserves the
     * best sentence hypotheses in practice.
     */
    private fun collectLattice(
        query: String,
        index: Index,
        userSnapshot: Map<String, List<Entry>>,
        collector: CandidateCollector,
        shouldContinue: () -> Boolean,
    ) {
        val n = query.length
        val buckets = Array(n + 1) { ArrayList<PathNode>() }
        buckets[0].add(PathNode("", null, 0.0, 0))
        val maxKeyLength = min(index.maxKeyLength, MAX_LATTICE_KEY_LENGTH)
        val entryCache = HashMap<String, List<Entry>>()

        for (position in 0 until n) {
            val paths = buckets[position]
            if (paths.isEmpty()) continue
            if (!shouldContinue()) return
            val beam = paths.sortedByDescending { it.score }.take(MAX_LATTICE_PATHS)
            var length = 1
            val maxLength = min(maxKeyLength, n - position)
            while (length <= maxLength) {
                val end = position + length
                val key = query.substring(position, end)
                val entries = entryCache.getOrPut(key) { entriesForPinyin(key, index, userSnapshot) }
                if (entries.isNotEmpty()) {
                    val previousWord = beam.firstOrNull()?.word ?: ""
                    val wordScoreCache = HashMap<String, Double>()
                    for (entry in entries.take(MAX_ENTRIES_PER_KEY)) {
                        if (entry.word.isEmpty()) continue
                        val base = wordScoreCache.getOrPut(entry.word) {
                            latticeWordScore(entry, index) + index.numeralExpressionBonus(entry.word)
                        }
                        beam.forEach { path ->
                            val boundary = if (path.wordCount == 0) 0.0 else {
                                boundaryBonus(index, path.word, entry.word)
                            }
                            buckets[end].add(
                                PathNode(
                                    word = entry.word,
                                    previous = path,
                                    score = path.score + base + boundary,
                                    wordCount = path.wordCount + 1,
                                ),
                            )
                        }
                    }
                }
                length++
            }
        }

        // Complete sentence candidates.
        val seenFull = HashSet<String>()
        var emitted = 0
        buckets[n].sortedByDescending { it.score }.forEach { path ->
            if (emitted >= MAX_FULL_CANDIDATES) return
            if (path.wordCount == 0) return@forEach
            val text = path.text()
            if (!seenFull.add(text)) return@forEach
            val sentenceBonus = if (path.wordCount >= 2) FULL_SENTENCE_BONUS else 0.0
            collector.add(
                Ranked(
                    word = text,
                    pinyin = query,
                    score = path.score + sentenceBonus,
                    consumed = n,
                    frequency = path.wordCount.toLong(),
                    order = 0,
                ),
            )
            emitted++
        }

        // Partial selections: best path at each position a word ends. Their
        // score is per-character so short and longer partials compare fairly.
        if (!shouldContinue()) return
        for (position in 1 until n) {
            if (buckets[position].isEmpty()) continue
            buckets[position].sortedByDescending { it.score }
                .take(MAX_PARTIAL_PATHS_PER_POSITION)
                .forEach { path ->
                    if (path.wordCount == 0) return@forEach
                    collector.add(
                        Ranked(
                            word = path.text(),
                            pinyin = query.substring(0, position),
                            score = path.score / position,
                            consumed = position,
                            frequency = path.wordCount.toLong(),
                            order = 0,
                        ),
                    )
                }
        }
    }

    private fun syllableBoundaries(query: String, syllables: Set<String>): BooleanArray {
        val n = query.length
        val reachable = BooleanArray(n + 1)
        reachable[0] = true
        for (position in 0 until n) {
            if (!reachable[position]) continue
            val maxLength = min(MAX_SYLLABLE_LENGTH, n - position)
            for (length in 1..maxLength) {
                if (query.substring(position, position + length) in syllables) {
                    reachable[position + length] = true
                }
            }
        }
        return reachable
    }

    /** Generic collocation bonus between two adjacent words. */
    private fun boundaryBonus(index: Index, previous: String, next: String): Double {
        var bonus = 0.0
        val compoundLength = previous.length + next.length
        if (compoundLength <= MAX_COMPOUND_LENGTH) {
            val compoundFrequency = index.wordFrequency[previous + next]
            if (compoundFrequency != null && compoundFrequency > 0) {
                bonus += COMPOUND_BONUS * min(1.0, ln(compoundFrequency.toDouble() + 1.0) / 10.0)
            }
        }
        val ngram = index.ngramModel
        if (ngram != null) {
            bonus += CONTEXT_WEIGHT * ngram.contextScore(previous, next)
        } else {
            index.bigramModel?.let { bonus += BIGRAM_WEIGHT * it.score(previous.last(), next.first()) }
        }
        if (index.isNumeralMeasurePair(previous, next)) bonus += NUMERAL_MEASURE_BONUS
        if (index.isNumeralContinuation(previous, next)) bonus += NUMERAL_CONTINUATION_BONUS
        return bonus
    }

    // ------------------------------------------------------------------
    // Explicit syllable separators (xi'an, bei jing)
    // ------------------------------------------------------------------

    private data class SegmentOption(val entry: Entry, val score: Double)

    private fun collectSegmented(
        parsed: ParsedQuery,
        index: Index,
        userSnapshot: Map<String, List<Entry>>,
        collector: CandidateCollector,
        shouldContinue: () -> Boolean,
    ) {
        val segments = parsed.segments
        val lnTotal = index.lnTotalFrequency
        val lastIndex = segments.lastIndex

        val options: List<List<SegmentOption>> = segments.mapIndexed { segmentIndex, segment ->
            var entries = entriesForPinyin(segment, index, userSnapshot)
            if (entries.isEmpty() && segmentIndex == lastIndex) {
                entries = collectPrefixEntries(segment, index, userSnapshot, shouldContinue)
            }
            entries.take(MAX_SEGMENT_OPTIONS).map { entry ->
                SegmentOption(
                    entry,
                    entryBaseScore(entry, index) + index.numeralExpressionBonus(entry.word),
                )
            }
        }
        if (options.any { it.isEmpty() }) return

        // Whole words whose pronunciation spans an explicit separator, e.g.
        // 西安 = xi|an. These are real dictionary words, not raw combinations.
        val combined = segments.joinToString("")
        entriesForPinyin(combined, index, userSnapshot)
            .asSequence()
            .filter { it.word.length >= segments.size }
            .take(MAX_SEGMENT_OPTIONS)
            .forEach { entry ->
                collector.add(
                    Ranked(
                        word = entry.word,
                        pinyin = combined,
                        score = exactWordScore(entry, index) + FULL_SENTENCE_BONUS,
                        consumed = parsed.consumedLength,
                        frequency = entry.frequency,
                        order = 0,
                    ),
                )
            }

        // Cartesian product of the per-segment words, bounded.
        var combinations = listOf<Pair<List<Entry>, Double>>(emptyList<Entry>() to 0.0)
        segments.indices.forEach { segmentIndex ->
            if (!shouldContinue()) return
            val next = ArrayList<Pair<List<Entry>, Double>>(
                minOf(MAX_SEGMENT_COMBINATIONS, combinations.size * options[segmentIndex].size),
            )
            combinations.forEach { (words, score) ->
                options[segmentIndex].forEach { option ->
                    if (next.size < MAX_SEGMENT_COMBINATIONS) {
                        val previousWord = words.lastOrNull()?.word ?: ""
                        val boundary = if (previousWord.isEmpty()) 0.0 else {
                            boundaryBonus(index, previousWord, option.entry.word)
                        }
                        next += (words + option.entry) to (score + option.score + boundary)
                    }
                }
            }
            combinations = next
                .sortedByDescending { it.second }
                .take(MAX_SEGMENT_COMBINATIONS)
        }
        combinations.forEach { (words, score) ->
            if (words.isEmpty()) return@forEach
            val text = words.joinToString("") { it.word }
            collector.add(
                Ranked(
                    word = text,
                    pinyin = combined,
                    score = score + if (words.size >= 2) FULL_SENTENCE_BONUS else 0.0,
                    consumed = parsed.consumedLength,
                    frequency = words.size.toLong(),
                    order = 0,
                ),
            )
        }

        // Partial selections consuming the first k segments; their pinyin covers
        // only the consumed segments (T03: never the whole query).
        for (prefixCount in 1 until segments.size) {
            if (!shouldContinue()) return
            var prefixCombinations = listOf<Pair<List<Entry>, Double>>(emptyList<Entry>() to 0.0)
            for (segmentIndex in 0 until prefixCount) {
                val next = ArrayList<Pair<List<Entry>, Double>>()
                prefixCombinations.forEach { (words, score) ->
                    options[segmentIndex].take(4).forEach { option ->
                        if (next.size < 64) {
                            val previousWord = words.lastOrNull()?.word ?: ""
                            val boundary = if (previousWord.isEmpty()) 0.0 else {
                                boundaryBonus(index, previousWord, option.entry.word)
                            }
                            next += (words + option.entry) to (score + option.score + boundary)
                        }
                    }
                }
                prefixCombinations = next.sortedByDescending { it.second }.take(4)
            }
            val consumed = parsed.segmentEnds[prefixCount - 1]
            prefixCombinations.forEach { (words, score) ->
                if (words.isEmpty()) return@forEach
                collector.add(
                    Ranked(
                        word = words.joinToString("") { it.word },
                        pinyin = segments.take(prefixCount).joinToString("'"),
                        score = score / consumed.coerceAtLeast(1),
                        consumed = consumed,
                        frequency = words.size.toLong(),
                        order = 0,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Index-backed entry lookups
    // ------------------------------------------------------------------

    private fun entriesForPinyin(
        pinyin: String,
        index: Index,
        userSnapshot: Map<String, List<Entry>>,
    ): List<Entry> {
        val indexed = index.entriesByPinyin[pinyin].orEmpty()
        val learned = userSnapshot[pinyin].orEmpty()
        return if (learned.isEmpty()) indexed else indexed + learned
    }

    private fun snapshotUserEntries(): Map<String, List<Entry>> = synchronized(selectionLock) {
        if (userEntries.isEmpty()) return emptyMap()
        val grouped = HashMap<String, ArrayList<Entry>>()
        userEntries.values.forEach { entry ->
            grouped.getOrPut(entry.pinyin) { ArrayList() } += entry.toEntry()
        }
        grouped.mapValues { (_, entries) ->
            entries.sortedWith(compareByDescending<Entry> { it.frequency }.thenBy { it.word })
        }
    }

    private fun UserLexiconEntry.toEntry(): Entry = Entry(
        pinyin = pinyin,
        word = word,
        frequency = count.toLong(),
        consumedInputLength = pinyin.length,
    )

    private fun collectPrefixEntries(
        query: String,
        index: Index,
        userSnapshot: Map<String, List<Entry>>,
        shouldContinue: () -> Boolean,
    ): List<Entry> {
        val prefixEntries = ArrayList<Entry>()
        var position = lowerBound(index.keys, query)
        var scanned = 0
        while (position < index.keys.size && scanned < MAX_PREFIX_KEYS) {
            if (!shouldContinue()) break
            val key = index.keys[position]
            if (!key.startsWith(query)) break
            if (key.length > query.length) {
                index.entriesByPinyin[key]?.let(prefixEntries::addAll)
            }
            position++
            scanned++
        }
        userSnapshot.forEach { (pinyin, entries) ->
            if (pinyin.length > query.length && pinyin.startsWith(query)) {
                prefixEntries.addAll(entries)
            }
        }
        return prefixEntries
            .sortedWith(
                compareByDescending<Entry> { it.frequency }
                    .thenBy { it.pinyin.length }
                    .thenBy { it.word },
            )
            .take(MAX_COLLECT_PER_GROUP)
    }

    // ------------------------------------------------------------------
    // Typo correction (light, only when correct candidates are weak)
    // ------------------------------------------------------------------

    /**
     * Bounded edit-distance-1 candidate lookup. Variants are generated from the
     * query on demand, so no large delete index is kept in memory. Correct
     * decodings always outrank a correction because corrections carry a fixed
     * penalty; they only appear when the normal lattice produces little.
     */
    private fun collectCorrections(
        query: String,
        index: Index,
        userSnapshot: Map<String, List<Entry>>,
        collector: CandidateCollector,
        shouldContinue: () -> Boolean,
    ) {
        if (query.length < 2 || query.length > MAX_CORRECTION_QUERY_LENGTH) return
        // Skip when the normal decoder already found a plausible exact word.
        val exact = entriesForPinyin(query, index, userSnapshot)
        if (exact.any { it.word.length >= 2 }) return
        val variants = LinkedHashSet<String>()
        // Extra character in the query: delete one.
        for (position in query.indices) variants += query.removeRange(position, position + 1)
        // Missing or wrong character: subscriptions/insertions.
        val alphabet = 'a'..'z'
        for (position in 0..query.length) {
            for (char in alphabet) variants += query.substring(0, position) + char + query.substring(position)
        }
        // Substitution (includes adjacent-key slips; those get no extra penalty
        // in this lightweight model, they are just edit distance one).
        for (position in query.indices) {
            for (char in alphabet) {
                if (char == query[position]) continue
                variants += query.substring(0, position) + char + query.substring(position + 1)
            }
        }
        // Adjacent transposition.
        for (position in 0 until query.length - 1) {
            if (query[position] == query[position + 1]) continue
            variants += query.substring(0, position) + query[position + 1] + query[position] +
                query.substring(position + 2)
        }
        val found = ArrayList<Ranked>()
        for (variant in variants) {
            if (!shouldContinue()) return
            if (variant == query) continue
            // Missing-letter corrections insert one character; give them a small
            // edge over same-length substitutions so "mintian -> 明天" wins over
            // the equally close but wrong "jintian -> 今天".
            val insertionBonus = if (variant.length == query.length + 1) 0.8 else 0.0
            val entries = entriesForPinyin(variant, index, userSnapshot)
            for (entry in entries.take(2)) {
                if (entry.word.length < 2) continue
                found += Ranked(
                    word = entry.word,
                    pinyin = entry.pinyin,
                    score = entryBaseScore(entry, index) +
                        CORRECTION_EXACT_BONUS + insertionBonus - CORRECTION_PENALTY,
                    consumed = query.length,
                    frequency = entry.frequency,
                    order = 0,
                    isCorrection = true,
                )
                if (found.size >= MAX_CORRECTION_ENTRIES * 4) break
            }
            if (found.size >= MAX_CORRECTION_ENTRIES * 4) break
        }
        found
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.word })
            .take(MAX_CORRECTION_ENTRIES)
            .forEach(collector::add)
    }

    /** Best full-cover word segmentation of a corrected pinyin string. */
    private fun bestSegmentation(
        query: String,
        index: Index,
        userSnapshot: Map<String, List<Entry>>,
    ): List<Entry>? {
        val n = query.length
        val best = arrayOfNulls<Pair<Double, List<Entry>>>(n + 1)
        best[0] = 0.0 to emptyList()
        val maxLength = min(index.maxKeyLength, n)
        for (position in 0 until n) {
            val current = best[position] ?: continue
            val limit = min(maxLength, n - position)
            for (length in 1..limit) {
                val key = query.substring(position, position + length)
                val entries = entriesForPinyin(key, index, userSnapshot)
                if (entries.isEmpty()) continue
                val entry = entries.first()
                val score = current.first + latticeWordScore(entry, index)
                val end = position + length
                val existing = best[end]
                if (existing == null || score > existing.first) {
                    best[end] = score to (current.second + entry)
                }
            }
        }
        return best[n]?.second
    }

    // ------------------------------------------------------------------
    // Learning (unchanged behaviour, bounded ranking weight)
    // ------------------------------------------------------------------

    /** Enables/disables learning for the current input session (R08). */
    fun setLearningEnabled(enabled: Boolean) {
        learningEnabled = enabled
    }

    /**
     * Records one explicit local/AI candidate choice.
     *
     * [pinyin] is the pronunciation used for the choice; when present it becomes
     * a searchable user entry so AI-provided words absent from the bundled
     * dictionary can be found after an IME restart. [persist] is false for the
     * typing hot path: KeyboardController batches persistence off the main thread.
     */
    fun recordSelection(
        word: String,
        pinyin: String = "",
        source: String = "user",
        persist: Boolean = true,
    ) {
        syncClearGeneration()
        if (!learningEnabled) return
        val key = word.trim()
        if (key.isEmpty()) return
        val normalizedPinyin = normalize(pinyin)
        synchronized(selectionLock) {
            val next = ((selectionCounts[key] ?: 0) + 1).coerceAtMost(MAX_SELECTION_WEIGHT)
            selectionCounts[key] = next
            if (normalizedPinyin.isNotEmpty()) {
                val entryKey = normalizedPinyin + "\t" + key
                val existing = userEntries[entryKey]
                userEntries[entryKey] = UserLexiconEntry(
                    pinyin = normalizedPinyin,
                    word = key,
                    count = maxOf(next, existing?.count ?: 0),
                    source = source,
                )
            }
            pruneUserEntriesLocked()
            selectionsSinceDecay++
            if (selectionsSinceDecay >= DECAY_INTERVAL) {
                selectionsSinceDecay = 0
                decaySelectionWeightsLocked()
            }
        }
        if (persist) persistSelectionWeights()
    }

    /** Number of times [word] was explicitly selected in this session. */
    fun selectionCount(word: String): Int = synchronized(selectionLock) {
        selectionCounts[word.trim()] ?: 0
    }

    /** Number of distinct searchable pinyin->word entries currently retained. */
    val userEntryCount: Int
        get() = synchronized(selectionLock) { userEntries.size }

    /** Number of in-memory selection-count words, including non-searchable legacy words. */
    val selectionWordCount: Int
        get() = synchronized(selectionLock) { selectionCounts.size }

    /** Clears both in-memory and persisted user selection memory on this engine. */
    fun clearSelectionMemory() {
        synchronized(selectionLock) {
            selectionCounts.clear()
            userEntries.clear()
        }
        runCatching { userLexiconStore?.clear() }
        lastClearGeneration = UserLexiconStore.currentClearGeneration()
    }

    /** Applies a simple count decay; production maintenance calls this explicitly. */
    fun decaySelectionWeights() {
        syncClearGeneration()
        synchronized(selectionLock) { decaySelectionWeightsLocked() }
        persistSelectionWeights()
    }

    private fun decaySelectionWeightsLocked() {
        selectionCounts.keys.toList().forEach { key ->
            val next = (selectionCounts[key] ?: 0) / 2
            if (next <= 0) selectionCounts.remove(key) else selectionCounts[key] = next
        }
        userEntries.entries.toList().forEach { (key, entry) ->
            val next = entry.count / 2
            if (next <= 0) userEntries.remove(key) else userEntries[key] = entry.copy(count = next)
        }
    }

    /** Persists searchable entries if a store was attached. */
    fun persistSelectionWeights() {
        syncClearGeneration()
        val store = userLexiconStore ?: return
        val generation = UserLexiconStore.currentClearGeneration()
        val entries = synchronized(selectionLock) { userEntries.values.toList() }
        val saved = runCatching { store.saveEntriesIfCurrent(entries, generation) }.getOrDefault(false)
        // A clear during a delayed save must not be followed by the old snapshot
        // being left on disk. Re-clear if the generation changed.
        if (generation != UserLexiconStore.currentClearGeneration()) {
            synchronized(selectionLock) {
                selectionCounts.clear()
                userEntries.clear()
            }
            lastClearGeneration = UserLexiconStore.currentClearGeneration()
            runCatching { store.clear() }
        } else if (!saved) {
            syncClearGeneration()
        }
    }

    private fun pruneUserEntriesLocked() {
        if (userEntries.size > MAX_USER_ENTRIES) {
            val keep = userEntries.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, UserLexiconEntry>> { it.value.count }
                        .thenBy { it.key },
                )
                .take(MAX_USER_ENTRIES)
            userEntries.clear()
            keep.forEach { (key, entry) -> userEntries[key] = entry }
        }
        if (selectionCounts.size > MAX_USER_ENTRIES) {
            val keepCounts = selectionCounts.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenBy { it.key },
                )
                .take(MAX_USER_ENTRIES)
            selectionCounts.clear()
            keepCounts.forEach { (word, count) -> selectionCounts[word] = count }
        }
    }

    private fun syncClearGeneration() {
        val current = UserLexiconStore.currentClearGeneration()
        if (current == lastClearGeneration) return
        synchronized(selectionLock) {
            selectionCounts.clear()
            userEntries.clear()
        }
        lastClearGeneration = current
    }

    private fun loadPersistedSelectionWeights() {
        syncClearGeneration()
        val store = userLexiconStore ?: return
        val generation = UserLexiconStore.currentClearGeneration()
        val persisted = runCatching { store.loadEntries() }.getOrDefault(emptyList())
        if (generation != UserLexiconStore.currentClearGeneration()) {
            syncClearGeneration()
            return
        }
        mergeSelectionEntries(persisted)
    }

    private fun mergeSelectionEntries(persisted: List<UserLexiconEntry>) {
        if (persisted.isEmpty()) return
        synchronized(selectionLock) {
            persisted.forEach { entry ->
                val word = entry.word.trim()
                val count = entry.count.coerceAtMost(MAX_SELECTION_WEIGHT)
                if (word.isEmpty() || count <= 0) return@forEach
                selectionCounts[word] = maxOf(selectionCounts[word] ?: 0, count)
                val pinyin = normalize(entry.pinyin)
                if (pinyin.isNotEmpty()) {
                    val key = pinyin + "\t" + word
                    userEntries[key] = entry.copy(pinyin = pinyin, word = word, count = count)
                }
            }
            pruneUserEntriesLocked()
        }
    }

    private fun snapshotSelectionWeights(): Map<String, Int> = synchronized(selectionLock) {
        HashMap(selectionCounts)
    }

    /** Runs [search] on Dispatchers.Default so the IME main thread is never blocked. */
    suspend fun query(input: String, limit: Int = DEFAULT_LIMIT): List<Candidate> =
        withContext(Dispatchers.Default) { search(input, limit) }

    /**
     * Loads the bundled dictionary and the persisted user lexicon off the main
     * thread. The production IME calls this on every service creation, which is
     * what restores selection weights after an IME restart.
     */
    suspend fun loadFromAssets(context: Context) {
        syncClearGeneration()
        val store = userLexiconStore ?: UserLexiconStore.create(context).also {
            userLexiconStore = it
        }
        val generation = UserLexiconStore.currentClearGeneration()
        // R08: user-lexicon decoding/loading is I/O work, not a main-thread read.
        val persistedEntries = withContext(Dispatchers.IO) {
            runCatching { store.loadEntries() }.getOrDefault(emptyList())
        }
        if (generation != UserLexiconStore.currentClearGeneration()) {
            syncClearGeneration()
        } else {
            mergeSelectionEntries(persistedEntries)
        }
        val loaded = withContext(Dispatchers.IO) {
            context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
                buildIndex(reader.lineSequence())
            }
        }
        index = loaded
    }

    // ------------------------------------------------------------------
    // Index construction
    // ------------------------------------------------------------------

    private class Index(
        val entriesByPinyin: Map<String, List<Entry>>,
        val keys: List<String>,
        val entryCount: Int,
        val maxKeyLength: Int,
        val syllableSet: Set<String>,
        val wordFrequency: Map<String, Long>,
        val bigramModel: BigramModel?,
        /** Corpus-trained character n-gram model (optional). */
        val ngramModel: NgramModel?,
        /** Frequency-weighted occurrence of each character across all dictionary words. */
        val charAggregateFrequency: Map<Char, Long>,
        val measureChars: Set<Char>,
        val numeralChars: Set<Char>,
        val lnTotalFrequency: Double,
        val corpusWordCounts: Map<String, Long>,
        val corpusWordTotal: Long,
        val shorthandByKey: Map<String, List<Entry>>,
    ) {
        /** Smoothed corpus word log-probability (0 when the word was unseen). */
        fun corpusWordScore(word: String): Double {
            val count = corpusWordCounts[word] ?: return 0.0
            return ln((count + 0.5) / (corpusWordTotal.toDouble() + corpusWordCounts.size))
        }
        val syllables: Set<String>
            get() = syllableSet

        fun numeralExpressionBonus(word: String): Double {
            if (word.length < 2) return 0.0
            if (word[0] !in numeralChars) return 0.0
            val rest = word.substring(1)
            if (rest.length == 1 && rest[0] in measureChars) return NUMERAL_MEASURE_BONUS
            return 0.0
        }

        fun isNumeralMeasurePair(previous: String, next: String): Boolean {
            if (previous.length != 1 || previous[0] !in numeralChars) return false
            if (next.length != 1) return false
            return next[0] in measureChars
        }

        /** A measure word followed by a numeral continuation (点+半, 点+五). */
        fun isNumeralContinuation(previous: String, next: String): Boolean {
            if (previous.isEmpty() || next.length != 1) return false
            if (previous.last() !in measureChars) return false
            return next[0] in numeralChars
        }
    }

    /**
     * Character n-gram statistics derived from a bundled Chinese dialogue
     * corpus. Only aggregate counts are stored; no source sentence is shipped.
     * Used for cross-word collocation scoring (e.g. 了吗, 吃饭吧, 马上到).
     */
    private class NgramModel(
        private val charCounts: Map<Char, Long>,
        private val bigramCounts: Map<Long, Long>,
        private val trigramCounts: Map<Long, Long>,
    ) {
        private val totalChars: Long = charCounts.values.sum().coerceAtLeast(1L)
        private val vocabulary: Int = charCounts.size.coerceAtLeast(1)

        /** Smoothed log probability of one character in the corpus text. */
        fun charPrior(char: Char): Double {
            val count = charCounts[char] ?: 0L
            return ln((count + 0.5) / (totalChars.toDouble() + vocabulary))
        }

        /** Average internal character-bigram log probability of a dictionary word. */
        fun wordInternalScore(word: String): Double {
            if (word.length < 2) return 0.0
            var sum = 0.0
            for (index in 0 until word.length - 1) {
                val first = word[index]
                val second = word[index + 1]
                val count = bigramCounts[bigramKey(first, second)] ?: 0L
                val charCount = charCounts[first] ?: 0L
                sum += ln((count + 0.5) / (charCount.toDouble() + vocabulary))
            }
            return sum / (word.length - 1)
        }

        /**
         * Log probability of seeing [nextWord] after [previousWord], backed off
         * from a character trigram (last two characters of the previous word +
         * first character of the next) to a character bigram.
         */
        fun contextScore(previousWord: String, nextWord: String): Double {
            if (previousWord.isEmpty() || nextWord.isEmpty()) return 0.0
            val first = nextWord[0]
            val last = previousWord[previousWord.length - 1]
            val bigramCount = bigramCounts[bigramKey(last, first)] ?: 0L
            val charCount = charCounts[last] ?: 0L
            val bigramScore = ln((bigramCount + 0.5) / (charCount.toDouble() + vocabulary))
            if (previousWord.length >= 2) {
                val secondLast = previousWord[previousWord.length - 2]
                val trigramCount = trigramCounts[trigramKey(secondLast, last, first)] ?: 0L
                if (trigramCount >= MIN_TRIGRAM_CONTEXT) {
                    val contextCount = bigramCounts[bigramKey(secondLast, last)] ?: 0L
                    return ln((trigramCount + 0.5) / (contextCount.toDouble() + 0.5))
                }
            }
            return bigramScore
        }

        companion object {
            fun bigramKey(first: Char, second: Char): Long =
                (first.code.toLong() shl 32) or second.code.toLong()

            fun trigramKey(first: Char, second: Char, third: Char): Long =
                (first.code.toLong() shl 42) or (second.code.toLong() shl 21) or third.code.toLong()
        }
    }

    /**
     * Character bigram statistics derived from dictionary word frequencies.
     * Used only across word boundaries so whole-word frequencies are not
     * double-counted; this is a generic, dictionary-trained collocation signal.
     */
    private class BigramModel(
        private val charCounts: Map<Char, Long>,
        private val bigramCounts: Map<Long, Long>,
        private val vocabularySize: Int,
    ) {
        fun score(first: Char, second: Char): Double {
            val denominator = charCounts[first] ?: return -12.0
            val count = bigramCounts[bigramKey(first, second)] ?: 0L
            return ln((count + 0.5) / (denominator.toDouble() + vocabularySize))
        }

        companion object {
            fun bigramKey(first: Char, second: Char): Long =
                (first.code.toLong() shl 32) or second.code.toLong()
        }
    }

    companion object {
        const val ASSET_NAME = "pinyin_dict.tsv"

        /** Number of candidates retained for one composition; the keyboard UI pages them. */
        const val MAX_CANDIDATES = 64

        /** Backwards-compatible name; now equals [MAX_CANDIDATES]. */
        const val DEFAULT_LIMIT = MAX_CANDIDATES

        private const val SCORE_EPSILON = 1e-9
        private const val MAX_SEARCH_INPUT_LENGTH = 64
        private const val MAX_EXACT_ENTRIES = 96
        private const val MAX_PREFIX_ENTRIES = 96
        private const val MAX_PREFIX_KEYS = 512
        private const val MAX_COLLECT_PER_GROUP = 512
        private const val MAX_SEGMENT_OPTIONS = 8
        private const val MAX_SEGMENT_COMBINATIONS = 512
        private const val MAX_LATTICE_KEY_LENGTH = 32
        private const val MAX_LATTICE_PATHS = 32
        private const val MAX_ENTRIES_PER_KEY = 4
        private const val MAX_FULL_CANDIDATES = 96
        private const val MAX_PARTIAL_PATHS_PER_POSITION = 4
        private const val MAX_SHORTHAND_ENTRIES = 96
        private const val MAX_SHORTHAND_BUCKET = 256
        private const val PARTIAL_QUOTA = 16

        /** Ranking cap: learning gives priority, not unbounded dominance. */
        private const val MAX_RANKING_SELECTION_WEIGHT = 8
        private const val MAX_SELECTION_WEIGHT = 1_000
        private const val SELECTION_BOOST_PER_SELECTION = 16.0
        private const val MAX_SELECTION_BOOST = 32.0
        private const val DECAY_INTERVAL = 500
        /** User-lexicon capacity: least-used entries are evicted above this. */
        const val MAX_USER_ENTRIES = 200

        private const val MAX_SYLLABLE_LENGTH = 6
        private const val MAX_COMPOUND_LENGTH = 10
        private const val MAX_CORRECTION_QUERY_LENGTH = 12
        private const val MIN_CORRECTION_SKIP_FREQUENCY = 1_000L
        private const val CORRECTION_TRIGGER_SCORE = 12.0
        private const val MAX_CORRECTION_ENTRIES = 64

        // Generic scoring weights (calibrated on a natural-language dev corpus,
        // never on individual acceptance sentences).
        private const val WORD_LENGTH_BONUS = 2.5
        private const val EXACT_WORD_BONUS = 3.0
        private const val EXACT_MATCH_BONUS = 2.0
        private const val SINGLE_CHAR_EXACT_BONUS = 3.0
        private const val MIN_EXACT_MATCH_BONUS_ENTRIES = 1_000
        private const val WORD_INSERTION_COMPENSATION = 12.0
        private const val MIN_RARITY_GATE_ENTRIES = 1_000
        private const val SINGLE_CHAR_FALLBACK_PENALTY = 8.0
        private const val PREFIX_PENALTY_PER_LETTER = 0.25
        private const val PREFIX_BASE_PENALTY = 0.25
        private const val FULL_SENTENCE_BONUS = 2.5
        private const val COMPOUND_BONUS = 1.5
        private const val BIGRAM_WEIGHT = 0.15
        /** Weight of the corpus character n-gram context score per word boundary. */
        private const val CONTEXT_WEIGHT = 1.2
        /** Weight of the corpus character prior for single-character words. */
        private const val CHAR_FREQ_WEIGHT = 0.8
        /** Weight of the corpus character-bigram score inside a word. */
        private const val WORD_NGRAM_WEIGHT = 0.35
        private const val MIN_TRIGRAM_CONTEXT = 3L
        private const val NUMERAL_MEASURE_BONUS = 3.0
        private const val NUMERAL_CONTINUATION_BONUS = 3.0
        private const val MIN_SINGLE_CHAR_AGGREGATE = 20_000L
        private const val RARE_CHAR_PENALTY = 3.0
        private const val SHORTHAND_PENALTY_PER_OMITTED_LETTER = 1.5
        private const val SHORTHAND_CORPUS_WEIGHT = 0.0
        private const val PREFIX_CORPUS_WEIGHT = 0.5
        private const val CORRECTION_PENALTY = 4.5
        private const val CORRECTION_EXACT_BONUS = 2.0

        private val NUMERAL_CHARS = "零〇一二三四五六七八九十百千万亿两半".toSet()
        private const val MAX_MEASURE_COUNT = 30

        /** Builds an engine from raw TSV lines; used by JVM tests and [loadFromAssets]. */
        fun fromLines(
            lines: Sequence<String>,
            store: UserLexiconStore? = null,
        ): PinyinEngine = PinyinEngine(buildIndex(lines), store)

        /** Builds an empty engine; useful before assets are loaded. */
        fun empty(store: UserLexiconStore? = null): PinyinEngine = PinyinEngine(null, store)

        private fun buildIndex(lines: Sequence<String>): Index {
            val grouped = HashMap<String, MutableList<Entry>>()
            val wordFrequency = HashMap<String, Long>()
            val corpusCharCounts = HashMap<Char, Long>()
            val corpusBigrams = HashMap<Long, Long>()
            val corpusTrigrams = HashMap<Long, Long>()
            val corpusWordCounts = HashMap<String, Long>()
            var corpusWordTotal = 0L
            var total = 0
            lines.forEach { rawLine ->
                val line = rawLine.removePrefix("\uFEFF").trimEnd('\r')
                if (line.startsWith("#@")) {
                    val parts = line.split('\t')
                    when {
                        line.startsWith("#@c\t") && parts.size >= 3 && parts[2].isNotEmpty() -> {
                            val count = parts[1].toLongOrNull() ?: 0L
                            if (count > 0) corpusCharCounts[parts[2][0]] = count
                        }
                        line.startsWith("#@b\t") && parts.size >= 3 && parts[2].length >= 2 -> {
                            val count = parts[1].toLongOrNull() ?: 0L
                            if (count > 0) {
                                corpusBigrams[NgramModel.bigramKey(parts[2][0], parts[2][1])] = count
                            }
                        }
                        line.startsWith("#@t\t") && parts.size >= 3 && parts[2].length >= 3 -> {
                            val count = parts[1].toLongOrNull() ?: 0L
                            if (count > 0) {
                                corpusTrigrams[
                                    NgramModel.trigramKey(parts[2][0], parts[2][1], parts[2][2])
                                ] = count
                            }
                        }
                        line.startsWith("#@w\t") && parts.size >= 3 && parts[2].isNotEmpty() -> {
                            val count = parts[1].toLongOrNull() ?: 0L
                            if (count > 0) {
                                corpusWordCounts[parts[2]] = count
                                corpusWordTotal += count
                            }
                        }
                    }
                    return@forEach
                }
                if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
                val parts = line.split('\t')
                if (parts.size < 2) return@forEach
                val pinyin = normalize(parts[0])
                val word = parts[1].trim()
                if (pinyin.isEmpty() || word.isEmpty()) return@forEach
                val frequency = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                grouped.getOrPut(pinyin) { ArrayList() }.add(Entry(pinyin, word, frequency))
                wordFrequency[word] = maxOf(wordFrequency[word] ?: 0L, frequency)
                total++
            }
            val sorted = HashMap<String, List<Entry>>(grouped.size)
            grouped.forEach { (pinyin, entries) ->
                sorted[pinyin] = entries
                    .distinctBy { it.word }
                    .sortedWith(compareByDescending<Entry> { it.frequency }.thenBy { it.word })
            }
            val sortedKeys = sorted.keys.sorted()
            val maxKeyLength = sortedKeys.maxOfOrNull { it.length } ?: 0

            // Generic syllable inventory: single-character readings only.
            val syllableSet = HashSet<String>()
            sorted.forEach { (pinyin, entries) ->
                if (pinyin.length in 1..MAX_SYLLABLE_LENGTH && entries.any { it.word.length == 1 }) {
                    syllableSet += pinyin
                }
            }

            val charCounts = HashMap<Char, Long>()
            val bigramCounts = HashMap<Long, Long>()
            sorted.values.forEach { entries ->
                entries.forEach { entry ->
                    val word = entry.word
                    if (word.length > MAX_COMPOUND_LENGTH) return@forEach
                    val frequency = entry.frequency.coerceAtLeast(1L)
                    for (index in word.indices) {
                        val char = word[index]
                        if (char !in '\u4e00'..'\u9fff') continue
                        charCounts[char] = (charCounts[char] ?: 0L) + frequency
                        if (index + 1 < word.length) {
                            val next = word[index + 1]
                            if (next in '\u4e00'..'\u9fff') {
                                val key = BigramModel.bigramKey(char, next)
                                bigramCounts[key] = (bigramCounts[key] ?: 0L) + frequency
                            }
                        }
                    }
                }
            }

            // Generic numeral + measure pattern: learn measure characters from
            // dictionary words that start with a numeral (一点/十点/三年...).
            val measureCounts = HashMap<Char, Long>()
            wordFrequency.forEach { (word, frequency) ->
                if (word.length in 2..4 && word[0] in NUMERAL_CHARS) {
                    val rest = word.substring(1)
                    if (rest.length == 1 && rest[0] !in NUMERAL_CHARS && rest[0] in '\u4e00'..'\u9fff') {
                        measureCounts[rest[0]] = (measureCounts[rest[0]] ?: 0L) + frequency
                    }
                }
            }
            val measureChars = measureCounts.entries
                .sortedByDescending { it.value }
                .take(MAX_MEASURE_COUNT)
                .filter { it.value >= 50 }
                .map { it.key }
                .toSet()

            val lnTotal = ln(wordFrequency.values.sum().coerceAtLeast(1L).toDouble())
            val bigramModel = if (charCounts.isEmpty()) null else {
                BigramModel(charCounts, bigramCounts, charCounts.size)
            }
            val ngramModel = if (corpusCharCounts.isEmpty()) null else {
                NgramModel(corpusCharCounts, corpusBigrams, corpusTrigrams)
            }

            return Index(
                entriesByPinyin = sorted,
                keys = sortedKeys,
                entryCount = total,
                maxKeyLength = maxKeyLength,
                syllableSet = syllableSet,
                wordFrequency = wordFrequency,
                bigramModel = bigramModel,
                ngramModel = ngramModel,
                charAggregateFrequency = charCounts,
                measureChars = measureChars,
                numeralChars = NUMERAL_CHARS,
                lnTotalFrequency = lnTotal,
                corpusWordCounts = corpusWordCounts,
                corpusWordTotal = corpusWordTotal.coerceAtLeast(1L),
                shorthandByKey = buildShorthandIndex(sorted, syllableSet),
            )
        }

        /**
         * Builds shorthand and mixed-pinyin keys from the dictionary itself.
         * No word/abbreviation pairs are hand-written: every key is generated
         * from the entry's syllable segmentation (character count == syllable
         * count for Han words, which disambiguates xian as 先 = xian vs
         * 西安 = xi + an).
         */
        private fun buildShorthandIndex(
            entriesByPinyin: Map<String, List<Entry>>,
            syllables: Set<String>,
        ): Map<String, List<Entry>> {
            val buckets = HashMap<String, LinkedHashMap<String, Entry>>()
            val segmentationCache = HashMap<Long, List<String>?>()
            entriesByPinyin.values.forEach { entries ->
                entries.forEach { entry ->
                    shorthandVariants(entry, syllables, segmentationCache).forEach { variant ->
                        if (variant.isNotEmpty()) {
                            buckets.getOrPut(variant) { LinkedHashMap() }[entry.word] = entry
                        }
                    }
                }
            }
            val frozen = HashMap<String, List<Entry>>(buckets.size)
            buckets.forEach { (key, values) ->
                frozen[key] = values.values
                    .sortedWith(compareByDescending<Entry> { it.frequency }.thenBy { it.word })
                    .take(MAX_SHORTHAND_BUCKET)
            }
            return frozen
        }

        private fun shorthandVariants(
            entry: Entry,
            syllables: Set<String>,
            cache: HashMap<Long, List<String>?>,
        ): Set<String> {
            val pinyin = entry.pinyin
            val syllableCount = entry.word.length
            if (syllableCount <= 1 || pinyin.length > MAX_SYLLABLE_LENGTH * 4) return emptySet()
            val cacheKey = (pinyin.hashCode().toLong() shl 8) or (syllableCount.toLong() and 0xff)
            val segments = cache.getOrPut(cacheKey) {
                segmentSyllables(pinyin, syllableCount, syllables)
            } ?: return emptySet()
            if (segments.size != syllableCount) return emptySet()
            val out = LinkedHashSet<String>()
            val initials = buildString {
                segments.forEach { append(it.first()) }
            }
            if (initials != pinyin && initials.isNotEmpty()) out += initials
            if (segments.size in 2..4) {
                val combinations = 1 shl segments.size
                // Bit i set means "use the full syllable", clear means initial.
                for (mask in 1 until combinations - 1) {
                    val key = buildString {
                        segments.forEachIndexed { index, syllable ->
                            if ((mask shr index) and 1 == 1) append(syllable) else append(syllable.first())
                        }
                    }
                    if (key != pinyin) out += key
                }
            } else if (segments.size > 4) {
                // Long words: full shorthand plus a few prefix-heavy variants.
                val firstFull = segments.first() + segments.drop(1).joinToString("") { it.first().toString() }
                if (firstFull != initials && firstFull != pinyin) out += firstFull
                if (segments.size >= 3) {
                    val firstTwoFull = segments[0] + segments[1] +
                        segments.drop(2).joinToString("") { it.first().toString() }
                    if (firstTwoFull != pinyin) out += firstTwoFull
                }
            }
            return out
        }

        private fun segmentSyllables(
            pinyin: String,
            syllableCount: Int,
            syllables: Set<String>,
        ): List<String>? {
            val current = ArrayList<String>(syllableCount)
            fun visit(position: Int): Boolean {
                if (position == pinyin.length) return current.size == syllableCount
                if (current.size >= syllableCount) return false
                val maxLength = min(MAX_SYLLABLE_LENGTH, pinyin.length - position)
                for (length in 1..maxLength) {
                    if (pinyin.length - position - length < syllableCount - current.size - 1) break
                    val candidate = pinyin.substring(position, position + length)
                    if (candidate !in syllables) continue
                    current += candidate
                    if (visit(position + length)) return true
                    current.removeAt(current.lastIndex)
                }
                return false
            }
            return if (visit(0)) current.toList() else null
        }

        private fun lowerBound(keys: List<String>, target: String): Int {
            var low = 0
            var high = keys.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (keys[mid] < target) low = mid + 1 else high = mid
            }
            return low
        }

        private fun normalize(value: String): String {
            if (value.isEmpty()) return ""
            val builder = StringBuilder(value.length)
            value.forEach { char ->
                val lower = char.lowercaseChar()
                if (lower in 'a'..'z') builder.append(lower)
            }
            return builder.toString()
        }
    }
}
