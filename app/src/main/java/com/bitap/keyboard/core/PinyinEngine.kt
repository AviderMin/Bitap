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
     * Candidates from exact pinyin, longer pinyin prefixes and the phrase
     * segmenter are merged and ranked together. Words the user has explicitly
     * selected earlier in this session get the highest priority (per-word
     * weight), then exact/prefix/phrase group order, then frequency.
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

        // Apostrophes/spaces are explicit syllable boundaries: xi'an must never
        // silently become the same query as xian.
        if (parsed.segments.size > 1) {
            return searchSegmented(parsed, current, cappedLimit, selectionWeights, shouldContinue)
        }
        return searchContinuous(
            query = parsed.segments.single(),
            current = current,
            cappedLimit = cappedLimit,
            selectionWeights = selectionWeights,
            consumedInputLength = parsed.consumedLength,
            shouldContinue = shouldContinue,
        )
    }

    private fun searchContinuous(
        query: String,
        current: Index,
        cappedLimit: Int,
        selectionWeights: Map<String, Int>,
        consumedInputLength: Int,
        shouldContinue: () -> Boolean,
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

        if (!shouldContinue()) return emptyList()
        entriesForPinyin(query, current)
            .take(MAX_COLLECT_PER_GROUP)
            .forEach { consider(it, GROUP_EXACT) }

        val prefixEntries = collectPrefixEntries(query, current, shouldContinue)
        prefixEntries.forEach { consider(it, GROUP_PREFIX) }

        // Learned weights only change ordering; they must not force full phrase
        // decoding when exact/prefix candidates already fill the page.
        if (collected.size < cappedLimit) {
            phraseCandidates(query, current, cappedLimit, shouldContinue).forEach { consider(it, GROUP_PHRASE) }
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
        shouldContinue: () -> Boolean,
    ): List<Candidate> {
        if (!shouldContinue()) return emptyList()
        if (parsed.consumedLength > MAX_SEARCH_INPUT_LENGTH) return emptyList()
        val lastSegmentIndex = parsed.segments.lastIndex
        val options = parsed.segments.mapIndexed { segmentIndex, segment ->
            val exact = entriesForPinyin(segment, index)
            val entries = if (segmentIndex == lastSegmentIndex && exact.isEmpty()) {
                // Last syllable may be incomplete: allow a prefix lookup (xi'a,
                // xi'ha) while earlier syllables must match exactly.
                collectPrefixEntries(segment, index, shouldContinue)
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
        entriesForPinyin(combined, index)
            .asSequence()
            .filter { it.word.length >= parsed.segments.size }
            .take(MAX_SEGMENT_OPTIONS)
            .forEach { entry ->
                consider(SegmentCandidate(entry.word, entry.frequency, GROUP_EXACT))
            }

        var combinations = listOf(SegmentCandidate("", 0L, GROUP_PHRASE))
        options.forEach { segmentOptions ->
            if (!shouldContinue()) return emptyList()
            val next = ArrayList<SegmentCandidate>(
                minOf(MAX_SEGMENT_COMBINATIONS, combinations.size * segmentOptions.size),
            )
            combinations.forEach { combination ->
                if (!shouldContinue()) return emptyList()
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

    private fun entriesForPinyin(pinyin: String, index: Index): List<Entry> {
        val indexed = index.entriesByPinyin[pinyin].orEmpty()
        val learned = synchronized(selectionLock) {
            userEntries.values
                .asSequence()
                .filter { it.pinyin == pinyin }
                .map { it.toEntry() }
                .toList()
        }
        return if (learned.isEmpty()) indexed else indexed + learned
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
        val learnedPrefixes = synchronized(selectionLock) {
            userEntries.values
                .asSequence()
                .filter { it.pinyin.isNotEmpty() && it.pinyin.startsWith(query) && it.pinyin.length > query.length }
                .map { it.toEntry() }
                .toList()
        }
        if (learnedPrefixes.isNotEmpty()) prefixEntries.addAll(learnedPrefixes)
        return prefixEntries
            .sortedWith(
                compareByDescending<Entry> { it.frequency }
                    .thenBy { it.pinyin.length }
                    .thenBy { it.word },
            )
            .take(MAX_COLLECT_PER_GROUP)
    }

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
        shouldContinue: () -> Boolean,
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

        var steps = 0
        for (position in 0 until n) {
            if (!shouldContinue() || ++steps > MAX_PHRASE_STEPS) return emptyList()
            val paths = buckets[position]
                .sortedByDescending { it.score }
                .take(MAX_PATHS_PER_POSITION)
            for (path in paths) {
                val maxLength = minOf(maxKeyLength, n - position)
                for (length in 1..maxLength) {
                    if (++steps > MAX_PHRASE_STEPS) return emptyList()
                    val key = input.substring(position, position + length)
                    val entries = entriesForPinyin(key, index)
                    if (entries.isEmpty()) continue
                    for (entry in entries.take(MAX_ENTRIES_PER_KEY)) {
                        if (entry.word.isEmpty()) continue
                        if (++steps > MAX_PHRASE_STEPS) return emptyList()
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
            if (!shouldContinue()) return out
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
        private const val DECAY_INTERVAL = 500
        /** User-lexicon capacity: least-used entries are evicted above this. */
        const val MAX_USER_ENTRIES = 200
        private const val GROUP_EXACT = 3
        private const val GROUP_PREFIX = 2
        private const val GROUP_PHRASE = 1
        private const val MAX_SEARCH_INPUT_LENGTH = 64
        private const val MAX_PHRASE_KEY_LENGTH = 64
        private const val MAX_PHRASE_INPUT_LENGTH = 64
        private const val MAX_PHRASE_STEPS = 20_000
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
