package com.bitap.keyboard.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline pinyin dictionary loaded from assets/pinyin_dict.tsv.
 *
 * Format: one entry per line, pinyin TAB word TAB frequency; blank lines and
 * lines starting with '#' are ignored. The index is built once on a background
 * thread and then only read, so [search] is thread-safe and allocation-light.
 */
class PinyinEngine private constructor(
    private var index: Index?,
    private var userLexiconStore: UserLexiconStore? = null,
) {

    private val selectionLock = Any()
    private val selectionCounts = HashMap<String, Int>()

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
     * Candidates from exact pinyin, longer pinyin prefixes and the phrase
     * segmenter are merged and ranked together. Words the user has explicitly
     * selected earlier in this session get the highest priority (per-word
     * weight), then exact/prefix/phrase group order, then frequency.
     */
    fun search(input: String, limit: Int = DEFAULT_LIMIT): List<Candidate> {
        val cappedLimit = limit.coerceAtMost(DEFAULT_LIMIT)
        if (cappedLimit <= 0) return emptyList()
        val parsed = parseQuery(input)
        if (parsed.segments.isEmpty()) return emptyList()
        val current = index ?: return emptyList()
        val selectionWeights = snapshotSelectionWeights()

        // Apostrophes/spaces are explicit syllable boundaries: xi'an must never
        // silently become the same query as xian.
        if (parsed.segments.size > 1) {
            return searchSegmented(parsed, current, cappedLimit, selectionWeights)
        }
        return searchContinuous(
            query = parsed.segments.single(),
            current = current,
            cappedLimit = cappedLimit,
            selectionWeights = selectionWeights,
            consumedInputLength = parsed.consumedLength,
        )
    }

    private fun searchContinuous(
        query: String,
        current: Index,
        cappedLimit: Int,
        selectionWeights: Map<String, Int>,
        consumedInputLength: Int,
    ): List<Candidate> {
        val collected = LinkedHashMap<String, RankedEntry>()
        var insertionOrder = 0

        fun consider(entry: Entry, group: Int) {
            val existing = collected[entry.word]
            if (existing == null) {
                collected[entry.word] = RankedEntry(entry, group, insertionOrder++)
            } else if (group > existing.group ||
                (group == existing.group && entry.frequency > existing.entry.frequency)
            ) {
                collected[entry.word] = RankedEntry(entry, group, existing.order)
            }
        }

        current.entriesByPinyin[query]
            ?.take(MAX_COLLECT_PER_GROUP)
            ?.forEach { consider(it, GROUP_EXACT) }

        val prefixEntries = collectPrefixEntries(query, current)
        prefixEntries.forEach { consider(it, GROUP_PREFIX) }

        if (collected.size < cappedLimit || selectionWeights.isNotEmpty()) {
            phraseCandidates(query, current, cappedLimit).forEach { consider(it, GROUP_PHRASE) }
        }

        return collected.values
            .sortedWith(
                compareByDescending<RankedEntry> { selectionWeights[it.entry.word] ?: 0 }
                    .thenByDescending { it.group }
                    .thenByDescending { if (it.group == GROUP_PHRASE) 0L else it.entry.frequency }
                    .thenBy { it.order },
            )
            .take(cappedLimit)
            .map { ranked ->
                Candidate(
                    word = ranked.entry.word,
                    pinyin = ranked.entry.pinyin,
                    frequency = ranked.entry.frequency,
                    consumedInputLength = if (ranked.group == GROUP_PHRASE) {
                        ranked.entry.consumedInputLength
                    } else {
                        consumedInputLength
                    },
                )
            }
    }

    private data class ParsedQuery(
        val segments: List<String>,
        /** Characters consumed in the original composition, including separators. */
        val consumedLength: Int,
    )

    private fun parseQuery(input: String): ParsedQuery {
        val normalizedSeparators = input
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('　', ' ')
        val segments = normalizedSeparators
            .split(Regex("[\\s'\\-]+"))
            .map { segment -> segment.filter { char -> char in 'a'..'z' || char in 'A'..'Z' }.lowercase() }
            .filter { it.isNotEmpty() }
        val consumedLength = normalizedSeparators.count { char ->
            char in 'a'..'z' || char in 'A'..'Z' || char == '\''
        }
        return ParsedQuery(segments, consumedLength)
    }

    private data class SegmentCandidate(
        val word: String,
        val frequency: Long,
        val group: Int,
    )

    /**
     * Candidate path for explicit syllable separators (e.g. xi'an). Each
     * apostrophe-separated segment must match a dictionary syllable exactly and
     * the segments are combined into words; xian is deliberately not considered
     * as a single syllable here. Whole-word dictionary entries that can span the
     * boundary (e.g. 西安 for xian) are preferred over raw character combinations
     * (e.g. 西按).
     */
    private fun searchSegmented(
        parsed: ParsedQuery,
        index: Index,
        limit: Int,
        selectionWeights: Map<String, Int>,
    ): List<Candidate> {
        val lastSegmentIndex = parsed.segments.lastIndex
        val options = parsed.segments.mapIndexed { segmentIndex, segment ->
            val exact = index.entriesByPinyin[segment].orEmpty()
            val entries = if (segmentIndex == lastSegmentIndex && exact.isEmpty()) {
                // Last syllable may be incomplete: allow a prefix lookup (xi'a,
                // xi'ha) while earlier syllables must match exactly.
                collectPrefixEntries(segment, index)
            } else {
                exact
            }
            entries.take(MAX_SEGMENT_OPTIONS).map { entry ->
                SegmentCandidate(entry.word, entry.frequency, GROUP_PHRASE)
            }
        }
        if (options.any { it.isEmpty() }) return emptyList()

        val collected = LinkedHashMap<String, SegmentCandidate>()
        fun consider(candidate: SegmentCandidate) {
            val existing = collected[candidate.word]
            if (existing == null || candidate.group > existing.group ||
                (candidate.group == existing.group && candidate.frequency > existing.frequency)
            ) {
                collected[candidate.word] = candidate
            }
        }

        // Prefer real multi-character dictionary words whose pinyin spans the
        // apostrophe boundary (西安 = xian, 你好 = nihao). Single-syllable words
        // such as 先/县 for "xian" are intentionally excluded here.
        val combined = parsed.segments.joinToString("")
        index.entriesByPinyin[combined]
            ?.asSequence()
            ?.filter { it.word.length >= parsed.segments.size }
            ?.take(MAX_SEGMENT_OPTIONS)
            ?.forEach { entry ->
                consider(SegmentCandidate(entry.word, entry.frequency, GROUP_EXACT))
            }

        var combinations = listOf(SegmentCandidate("", 0L, GROUP_PHRASE))
        options.forEach { segmentOptions ->
            val next = ArrayList<SegmentCandidate>(
                minOf(MAX_SEGMENT_COMBINATIONS, combinations.size * segmentOptions.size),
            )
            combinations.forEach { combination ->
                segmentOptions.forEach { option ->
                    if (next.size < MAX_SEGMENT_COMBINATIONS) {
                        next += SegmentCandidate(
                            word = combination.word + option.word,
                            frequency = combination.frequency + option.frequency,
                            group = GROUP_PHRASE,
                        )
                    }
                }
            }
            if (next.isEmpty()) return emptyList()
            combinations = next
                .sortedWith(compareByDescending<SegmentCandidate> { it.frequency }.thenBy { it.word })
                .take(MAX_SEGMENT_COMBINATIONS)
        }
        combinations.forEach(::consider)

        return collected.values
            .sortedWith(
                compareByDescending<SegmentCandidate> { selectionWeights[it.word] ?: 0 }
                    .thenByDescending { it.group }
                    .thenByDescending { it.frequency }
                    .thenBy { it.word.length }
                    .thenBy { it.word },
            )
            .take(limit)
            .map { candidate ->
                Candidate(
                    word = candidate.word,
                    pinyin = parsed.segments.joinToString("'"),
                    frequency = candidate.frequency,
                    consumedInputLength = parsed.consumedLength,
                )
            }
    }

    private fun collectPrefixEntries(query: String, index: Index): List<Entry> {
        val prefixEntries = ArrayList<Entry>()
        var position = lowerBound(index.keys, query)
        var scanned = 0
        while (position < index.keys.size && scanned < MAX_PREFIX_KEYS) {
            val key = index.keys[position]
            if (!key.startsWith(query)) break
            if (key.length > query.length) {
                index.entriesByPinyin[key]?.let(prefixEntries::addAll)
            }
            position++
            scanned++
        }
        return prefixEntries
            .sortedWith(
                compareByDescending<Entry> { it.frequency }
                    .thenBy { it.pinyin.length }
                    .thenBy { it.word },
            )
            .take(MAX_COLLECT_PER_GROUP)
    }

    /** Records one explicit local/AI candidate choice for the current session. */
    fun recordSelection(word: String) {
        val key = word.trim()
        if (key.isEmpty()) return
        synchronized(selectionLock) {
            val next = (selectionCounts[key] ?: 0) + 1
            selectionCounts[key] = next.coerceAtMost(MAX_SELECTION_WEIGHT)
        }
        persistSelectionWeights()
    }

    /** Number of times [word] was explicitly selected in this session. */
    fun selectionCount(word: String): Int = synchronized(selectionLock) {
        selectionCounts[word.trim()] ?: 0
    }

    /** Clears both in-memory and persisted user selection memory. */
    fun clearSelectionMemory() {
        synchronized(selectionLock) { selectionCounts.clear() }
        runCatching { userLexiconStore?.clear() }
    }

    /** Persists the current word counts if a store was attached. */
    fun persistSelectionWeights() {
        val store = userLexiconStore ?: return
        val snapshot = synchronized(selectionLock) { HashMap(selectionCounts) }
        runCatching { store.save(snapshot) }
    }

    private fun loadPersistedSelectionWeights() {
        val store = userLexiconStore ?: return
        val persisted = runCatching { store.load() }.getOrDefault(emptyMap())
        mergeSelectionWeights(persisted)
    }

    private fun mergeSelectionWeights(persisted: Map<String, Int>) {
        if (persisted.isEmpty()) return
        synchronized(selectionLock) {
            persisted.forEach { (word, count) ->
                val normalizedWord = word.trim()
                if (normalizedWord.isEmpty() || count <= 0) return@forEach
                val capped = count.coerceAtMost(MAX_SELECTION_WEIGHT)
                selectionCounts[normalizedWord] = maxOf(selectionCounts[normalizedWord] ?: 0, capped)
            }
        }
    }

    private fun snapshotSelectionWeights(): Map<String, Int> = synchronized(selectionLock) {
        HashMap(selectionCounts)
    }

    private data class RankedEntry(
        val entry: Entry,
        val group: Int,
        val order: Int,
    )

    /**
     * Dynamic-programming phrase candidates. Besides the complete input this
     * emits prefix candidates (consumedInputLength < input.length), which lets
     * the user confirm the first half of a sentence and keep the rest composing.
     *
     * Budget: key length comes from the dictionary index (not a hard-coded 12),
     * capped by MAX_PHRASE_KEY_LENGTH; paths per position and entries per key are
     * bounded so a long input cannot explode the search.
     */
    private fun phraseCandidates(
        input: String,
        index: Index,
        limit: Int,
    ): List<Entry> {
        if (limit <= 0 || input.length < 2 || input.length > MAX_PHRASE_INPUT_LENGTH) {
            return emptyList()
        }
        data class Path(val position: Int, val words: List<String>, val score: Double)

        val n = input.length
        val buckets = Array(n + 1) { ArrayList<Path>() }
        buckets[0].add(Path(0, emptyList(), 0.0))
        val maxKeyLength = minOf(index.maxKeyLength, MAX_PHRASE_KEY_LENGTH, n)
        if (maxKeyLength <= 0) return emptyList()

        for (position in 0 until n) {
            val paths = buckets[position]
                .sortedByDescending { it.score }
                .take(MAX_PATHS_PER_POSITION)
            for (path in paths) {
                val maxLength = minOf(maxKeyLength, n - position)
                for (length in 1..maxLength) {
                    val key = input.substring(position, position + length)
                    val entries = index.entriesByPinyin[key] ?: continue
                    for (entry in entries.take(MAX_ENTRIES_PER_KEY)) {
                        if (entry.word.isEmpty()) continue
                        val nextWords = path.words + entry.word
                        val score = path.score +
                            kotlin.math.ln(entry.frequency.toDouble() + 1.0) +
                            PHRASE_WORD_LENGTH_WEIGHT * entry.word.length -
                            PHRASE_SEGMENT_PENALTY
                        buckets[position + length].add(Path(position + length, nextWords, score))
                    }
                }
            }
        }

        val out = ArrayList<Entry>(limit)
        val outWords = HashSet<String>()
        // Full-composition phrases first, then descending prefix lengths. This
        // keeps the mature full-sentence candidate as the default while exposing
        // prefix candidates for two-step selection.
        for (position in n downTo 2) {
            buckets[position]
                .asSequence()
                .filter { it.words.size >= 2 }
                .sortedByDescending { it.score }
                .forEach { path ->
                    if (out.size >= limit) return out
                    val entry = Entry(
                        pinyin = input.substring(0, position),
                        word = path.words.joinToString(""),
                        frequency = path.words.size.toLong(),
                        consumedInputLength = position,
                    )
                    if (outWords.add(entry.word)) out.add(entry)
                }
        }
        return out
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
        if (userLexiconStore == null) {
            userLexiconStore = UserLexiconStore.create(context)
        }
        loadPersistedSelectionWeights()
        val loaded = withContext(Dispatchers.IO) {
            context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
                buildIndex(reader.lineSequence())
            }
        }
        index = loaded
    }

    private class Index(
        val entriesByPinyin: Map<String, List<Entry>>,
        val keys: List<String>,
        val entryCount: Int,
        val maxKeyLength: Int,
    )

    companion object {
        const val ASSET_NAME = "pinyin_dict.tsv"

        /** Number of candidates retained for one composition; the keyboard UI pages them. */
        const val MAX_CANDIDATES = 64

        /** Backwards-compatible name; now equals [MAX_CANDIDATES]. */
        const val DEFAULT_LIMIT = MAX_CANDIDATES

        private const val MAX_PREFIX_KEYS = 256
        private const val MAX_COLLECT_PER_GROUP = 512
        private const val MAX_SEGMENT_OPTIONS = 8
        private const val MAX_SEGMENT_COMBINATIONS = 512
        private const val MAX_SELECTION_WEIGHT = 1_000
        private const val GROUP_EXACT = 3
        private const val GROUP_PREFIX = 2
        private const val GROUP_PHRASE = 1
        private const val MAX_PHRASE_KEY_LENGTH = 64
        private const val MAX_PHRASE_INPUT_LENGTH = 64
        private const val MAX_PATHS_PER_POSITION = 16
        private const val MAX_ENTRIES_PER_KEY = 5
        private const val PHRASE_WORD_LENGTH_WEIGHT = 0.55
        private const val PHRASE_SEGMENT_PENALTY = 8.0

        /** Builds an engine from raw TSV lines; used by JVM tests and [loadFromAssets]. */
        fun fromLines(
            lines: Sequence<String>,
            store: UserLexiconStore? = null,
        ): PinyinEngine = PinyinEngine(buildIndex(lines), store)

        /** Builds an empty engine; useful before assets are loaded. */
        fun empty(store: UserLexiconStore? = null): PinyinEngine = PinyinEngine(null, store)

        private fun buildIndex(lines: Sequence<String>): Index {
            val grouped = HashMap<String, MutableList<Entry>>()
            var total = 0
            lines.forEach { rawLine ->
                val line = rawLine.removePrefix("\uFEFF").trimEnd('\r')
                if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
                val parts = line.split('\t')
                if (parts.size < 2) return@forEach
                val pinyin = normalize(parts[0])
                val word = parts[1].trim()
                if (pinyin.isEmpty() || word.isEmpty()) return@forEach
                val frequency = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                grouped.getOrPut(pinyin) { ArrayList() }.add(Entry(pinyin, word, frequency))
                total++
            }
            val sorted = HashMap<String, List<Entry>>(grouped.size)
            grouped.forEach { (pinyin, entries) ->
                sorted[pinyin] = entries.sortedWith(
                    compareByDescending<Entry> { it.frequency }.thenBy { it.word },
                )
            }
            val sortedKeys = sorted.keys.sorted()
            val maxKeyLength = sortedKeys.maxOfOrNull { it.length } ?: 0
            return Index(sorted, sortedKeys, total, maxKeyLength)
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
