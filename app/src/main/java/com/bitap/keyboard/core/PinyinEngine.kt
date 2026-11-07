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
class PinyinEngine private constructor(private var index: Index?) {

    data class Entry(
        val pinyin: String,
        val word: String,
        val frequency: Long,
    )

    /** True once a dictionary has been loaded. */
    val isReady: Boolean
        get() = index?.keys?.isNotEmpty() == true

    val entryCount: Int
        get() = index?.entryCount ?: 0

    /**
     * Synchronous lookup used by tests and by [query] on Dispatchers.Default.
     * Returns at most [limit] candidates: exact pinyin matches first (frequency
     * descending), then longer pinyin words that start with the query.
     */
    fun search(input: String, limit: Int = DEFAULT_LIMIT): List<Candidate> {
        val cappedLimit = limit.coerceAtMost(DEFAULT_LIMIT)
        if (cappedLimit <= 0) return emptyList()
        val query = normalize(input)
        if (query.isEmpty()) return emptyList()
        val current = index ?: return emptyList()

        val result = ArrayList<Candidate>(cappedLimit)
        val seenWords = HashSet<String>(cappedLimit * 2)

        fun add(entry: Entry) {
            if (result.size >= cappedLimit) return
            if (!seenWords.add(entry.word)) return
            result.add(Candidate(entry.word, entry.pinyin, entry.frequency))
        }

        current.entriesByPinyin[query]?.forEach(::add)

        if (result.size < cappedLimit) {
            val prefixEntries = ArrayList<Entry>()
            var position = lowerBound(current.keys, query)
            var scanned = 0
            while (position < current.keys.size && scanned < MAX_PREFIX_KEYS) {
                val key = current.keys[position]
                if (!key.startsWith(query)) break
                if (key.length > query.length) {
                    current.entriesByPinyin[key]?.let(prefixEntries::addAll)
                }
                position++
                scanned++
            }
            prefixEntries.sortWith(
                compareByDescending<Entry> { it.frequency }
                    .thenBy { it.pinyin.length }
                    .thenBy { it.word },
            )
            prefixEntries.forEach(::add)
        }

        // Exact and prefix lookups are not enough for continuous pinyin such as
        // "woaini" or "jintiantianqihenhao".  Fill the remaining slots with
        // whole-sentence candidates produced by a small dynamic-programming
        // word segmenter over the same dictionary.
        if (result.size < cappedLimit) {
            phraseCandidates(query, current, cappedLimit - result.size).forEach(::add)
        }
        return result
    }

    private fun phraseCandidates(
        input: String,
        index: Index,
        limit: Int,
    ): List<Entry> {
        if (limit <= 0 || input.length < 2) return emptyList()
        data class Path(val position: Int, val words: List<String>, val score: Double)

        val n = input.length
        val buckets = Array(n + 1) { ArrayList<Path>() }
        buckets[0].add(Path(0, emptyList(), 0.0))
        val maxKeyLength = minOf(MAX_WORD_KEY_LENGTH, n)

        for (position in 0 until n) {
            val paths = buckets[position].sortedByDescending { it.score }.take(MAX_PATHS_PER_POSITION)
            for (path in paths) {
                val maxLength = minOf(maxKeyLength, n - position)
                for (length in 1..maxLength) {
                    val key = input.substring(position, position + length)
                    val entries = index.entriesByPinyin[key] ?: continue
                    for (entry in entries.take(MAX_ENTRIES_PER_KEY)) {
                        if (entry.word.isEmpty()) continue
                        val nextWords = path.words + entry.word
                        val score = path.score + kotlin.math.ln(entry.frequency.toDouble() + 1.0) +
                            0.35 * entry.word.length - SEGMENT_PENALTY
                        buckets[position + length].add(Path(position + length, nextWords, score))
                    }
                }
            }
        }

        val out = ArrayList<Entry>(limit)
        val outWords = HashSet<String>()
        var added = 0
        buckets[n]
            .asSequence()
            .filter { it.words.size >= 2 }
            .sortedByDescending { it.score }
            .forEach { path ->
                val entry = Entry(input, path.words.joinToString(""), path.words.size.toLong())
                if (outWords.add(entry.word)) {
                    out.add(entry)
                    added++
                    if (added >= limit) return out
                }
            }
        return out
    }

    /** Runs [search] on Dispatchers.Default so the IME main thread is never blocked. */
    suspend fun query(input: String, limit: Int = DEFAULT_LIMIT): List<Candidate> =
        withContext(Dispatchers.Default) { search(input, limit) }

    /** Loads the bundled dictionary off the main thread. Failure leaves the engine empty. */
    suspend fun loadFromAssets(context: Context) {
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
    )

    companion object {
        const val ASSET_NAME = "pinyin_dict.tsv"
        const val DEFAULT_LIMIT = 8
        private const val MAX_PREFIX_KEYS = 256
        private const val MAX_WORD_KEY_LENGTH = 12
        private const val MAX_PATHS_PER_POSITION = 8
        private const val MAX_ENTRIES_PER_KEY = 5
        private const val SEGMENT_PENALTY = 15.0

        /** Builds an engine from raw TSV lines; used by JVM tests and [loadFromAssets]. */
        fun fromLines(lines: Sequence<String>): PinyinEngine = PinyinEngine(buildIndex(lines))

        /** Builds an empty engine; useful before assets are loaded. */
        fun empty(): PinyinEngine = PinyinEngine(null)

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
            return Index(sorted, sorted.keys.sorted(), total)
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
