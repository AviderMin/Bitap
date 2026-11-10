package com.bitap.keyboard.core

import android.content.Context

/**
 * Persisted English prediction state. Only user-committed words and adjacent
 * bigram counts are stored; no raw sentence or surrounding editor text.
 */
data class EnglishPredictionData(
    val wordCounts: Map<String, Int> = emptyMap(),
    val bigrams: Map<String, Map<String, Int>> = emptyMap(),
) {
    companion object {
        private const val MAX_WORDS = 800
        private const val MAX_PREVIOUS_WORDS = 300
        private const val MAX_NEXT_WORDS = 8

        internal fun encode(data: EnglishPredictionData): String = buildString {
            data.wordCounts.entries
                .asSequence()
                .filter { it.key.isNotBlank() && it.value > 0 }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(MAX_WORDS)
                .forEach { (word, count) ->
                    append("W\t").append(word).append('\t').append(count).append('\n')
                }
            data.bigrams.entries
                .asSequence()
                .filter { it.key.isNotBlank() }
                .sortedBy { it.key }
                .take(MAX_PREVIOUS_WORDS)
                .forEach { (previous, nextWords) ->
                    nextWords.entries
                        .asSequence()
                        .filter { it.key.isNotBlank() && it.value > 0 }
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        .take(MAX_NEXT_WORDS)
                        .forEach { (next, count) ->
                            append("B\t").append(previous).append('\t').append(next).append('\t').append(count).append('\n')
                        }
                }
        }

        internal fun decode(raw: String?): EnglishPredictionData {
            if (raw.isNullOrBlank()) return EnglishPredictionData()
            val words = LinkedHashMap<String, Int>()
            val bigrams = HashMap<String, LinkedHashMap<String, Int>>()
            raw.lineSequence().forEach { line ->
                val parts = line.split('\t')
                when {
                    parts.size == 3 && parts[0] == "W" -> {
                        val word = normalizeWord(parts[1]) ?: return@forEach
                        val count = parts[2].trim().toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                        words[word] = count
                    }

                    parts.size == 4 && parts[0] == "B" -> {
                        val previous = normalizeWord(parts[1]) ?: return@forEach
                        val next = normalizeWord(parts[2]) ?: return@forEach
                        val count = parts[3].trim().toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                        bigrams.getOrPut(previous) { LinkedHashMap() }[next] = count
                    }
                }
            }
            return EnglishPredictionData(
                wordCounts = words.entries.take(MAX_WORDS).associate { it.key to it.value },
                bigrams = bigrams.entries.take(MAX_PREVIOUS_WORDS).associate { (previous, nextWords) ->
                    previous to nextWords.entries.take(MAX_NEXT_WORDS).associate { it.key to it.value }
                },
            )
        }

        internal fun normalizeWord(raw: String): String? {
            val word = raw.trim().lowercase()
            if (word.isEmpty() || word.length > 32) return null
            if (word.any { it !in 'a'..'z' }) return null
            return word
        }
    }
}

/** Storage boundary for English predictor learning. */
interface EnglishPredictionStore {
    fun load(): EnglishPredictionData

    fun save(data: EnglishPredictionData)

    fun clear()

    companion object {
        /** Headless/test default: no persistence. */
        val None: EnglishPredictionStore = object : EnglishPredictionStore {
            override fun load(): EnglishPredictionData = EnglishPredictionData()
            override fun save(data: EnglishPredictionData) = Unit
            override fun clear() = Unit
        }

        /**
         * One process-wide store instance so the settings screen and the live
         * IME share the same object; clear()/save() from either side stay
         * coherent instead of operating on unrelated in-memory copies.
         */
        fun create(context: Context): EnglishPredictionStore {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: SharedPreferencesEnglishPredictionStore(context.applicationContext)
                    .also { cached = it }
            }
        }

        @Volatile
        private var cached: EnglishPredictionStore? = null
    }
}

private class SharedPreferencesEnglishPredictionStore(context: Context) : EnglishPredictionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): EnglishPredictionData =
        EnglishPredictionData.decode(preferences.getString(KEY_DATA, null))

    override fun save(data: EnglishPredictionData) {
        preferences.edit().putString(KEY_DATA, EnglishPredictionData.encode(data)).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_DATA).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "bitap_english_prediction"
        const val KEY_DATA = "data"
    }
}

/**
 * Small on-device English completion/next-word model.
 *
 * Completion uses a bundled list of very common words plus words the user has
 * committed; next-word uses bigrams learned from the user's own commits, with a
 * tiny seed mapping for cold start. Nothing is sent off device.
 */
class EnglishPredictor(
    initial: EnglishPredictionData = EnglishPredictionData(),
    private val seedWords: List<String> = DEFAULT_ENGLISH_WORDS,
) {
    private val wordCounts = LinkedHashMap<String, Int>()
    private val bigrams = HashMap<String, LinkedHashMap<String, Int>>()
    private val seedRank: Map<String, Int> = seedWords.withIndex().associate { it.value to it.index }

    init {
        loadFrom(initial)
    }

    /**
     * Replaces the whole in-memory model with [data]. Used when the persisted
     * store was cleared or changed by another instance (for example the settings
     * screen) so stale words can never be saved back.
     */
    fun resetFrom(data: EnglishPredictionData) {
        wordCounts.clear()
        bigrams.clear()
        loadFrom(data)
    }

    private fun loadFrom(data: EnglishPredictionData) {
        data.wordCounts.forEach { (word, count) ->
            EnglishPredictionData.normalizeWord(word)?.let { wordCounts[it] = count.coerceAtLeast(1) }
        }
        data.bigrams.forEach { (previous, nextWords) ->
            val normalizedPrevious = EnglishPredictionData.normalizeWord(previous) ?: return@forEach
            val target = bigrams.getOrPut(normalizedPrevious) { LinkedHashMap() }
            nextWords.forEach { (next, count) ->
                EnglishPredictionData.normalizeWord(next)?.let { target[it] = count.coerceAtLeast(1) }
            }
        }
    }

    fun recordCommittedWord(word: String, previousWord: String?) {
        val normalized = EnglishPredictionData.normalizeWord(word) ?: return
        wordCounts[normalized] = (wordCounts[normalized] ?: 0) + 1
        val previous = previousWord?.let(EnglishPredictionData::normalizeWord)
        if (previous != null && previous != normalized) {
            val nextWords = bigrams.getOrPut(previous) { LinkedHashMap() }
            nextWords[normalized] = (nextWords[normalized] ?: 0) + 1
        }
        prune()
    }

    fun suggestions(prefix: String, previousWord: String?): List<String> {
        val normalizedPrefix = EnglishPredictionData.normalizeWord(prefix)
        if (normalizedPrefix != null) {
            val learned = wordCounts.entries
                .asSequence()
                .filter { it.key.startsWith(normalizedPrefix) && it.key != normalizedPrefix }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
            val seeded = seedWords.asSequence().filter {
                it.startsWith(normalizedPrefix) && it != normalizedPrefix
            }
            return (learned + seeded).distinct().take(MAX_SUGGESTIONS).toList()
        }
        val previous = previousWord?.let(EnglishPredictionData::normalizeWord) ?: return emptyList()
        val learned = bigrams[previous].orEmpty().entries
            .asSequence()
            .sortedByDescending { it.value }
            .map { it.key }
        val seeded = SEED_BIGRAMS[previous].orEmpty().asSequence()
        return (learned + seeded).distinct().take(MAX_SUGGESTIONS).toList()
    }

    fun snapshot(): EnglishPredictionData = EnglishPredictionData(
        wordCounts = LinkedHashMap(wordCounts),
        bigrams = bigrams.mapValues { LinkedHashMap(it.value) },
    )

    fun clear() {
        wordCounts.clear()
        bigrams.clear()
    }

    private fun prune() {
        if (wordCounts.size > MAX_LEARNED_WORDS) {
            wordCounts.entries
                .sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(wordCounts.size - MAX_LEARNED_WORDS)
                .forEach { wordCounts.remove(it.key) }
        }
        if (bigrams.size > MAX_LEARNED_PREVIOUS_WORDS) {
            bigrams.entries
                .sortedBy { entry -> entry.value.values.sum() }
                .take(bigrams.size - MAX_LEARNED_PREVIOUS_WORDS)
                .forEach { bigrams.remove(it.key) }
        }
        bigrams.values.forEach { nextWords ->
            if (nextWords.size > MAX_LEARNED_NEXT_WORDS) {
                nextWords.entries
                    .sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take(nextWords.size - MAX_LEARNED_NEXT_WORDS)
                    .forEach { nextWords.remove(it.key) }
            }
        }
    }

    private companion object {
        const val MAX_SUGGESTIONS = 3
        const val MAX_LEARNED_WORDS = 800
        const val MAX_LEARNED_PREVIOUS_WORDS = 300
        const val MAX_LEARNED_NEXT_WORDS = 8
    }
}

/**
 * Common English words used only for cold-start completion. The list is a
 * hand-curated vocabulary of everyday words, not third-party frequency data.
 */
internal val DEFAULT_ENGLISH_WORDS: List<String> = listOf(
    "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
    "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
    "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
    "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
    "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
    "when", "make", "can", "like", "time", "no", "just", "him", "know", "take",
    "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
    "than", "then", "now", "look", "only", "come", "its", "over", "think", "also",
    "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
    "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",
    "is", "was", "are", "been", "has", "had", "were", "said", "did", "more",
    "very", "still", "here", "hello", "help", "please", "thanks", "thank", "sorry", "yes",
    "maybe", "welcome", "morning", "night", "today", "tomorrow", "great", "nice", "happy", "birthday",
    "love", "friend", "family", "home", "phone", "email", "message", "name", "address", "number",
    "date", "meeting", "project", "team", "office", "school", "class", "student", "teacher", "computer",
    "keyboard", "input", "method", "english", "chinese", "goodbye", "bye", "later", "soon", "again",
    "always", "never", "sometimes", "need", "must", "should", "try", "keep", "let", "put",
    "turn", "open", "close", "start", "stop", "finish", "wait", "send", "receive", "read",
    "write", "call", "check", "confirm", "update", "install", "download", "upload", "file", "folder",
    "photo", "video", "music", "game", "news", "app", "website", "link", "code", "test",
    "bug", "fix", "release", "version", "build", "android", "google", "microsoft", "apple", "mobile",
    "device", "battery", "wifi", "network", "market", "price", "order", "payment", "account", "password",
)

/** Tiny hand-written cold-start next-word pairs. User bigrams override them. */
internal val SEED_BIGRAMS: Map<String, List<String>> = mapOf(
    "thank" to listOf("you"),
    "how" to listOf("are", "is", "to"),
    "i" to listOf("am", "have", "will", "think"),
    "you" to listOf("are", "can", "have"),
    "we" to listOf("are", "can", "will"),
    "they" to listOf("are", "have"),
    "it" to listOf("is", "was", "has"),
    "this" to listOf("is", "has"),
    "that" to listOf("is"),
    "what" to listOf("is", "are"),
    "where" to listOf("is", "are"),
    "when" to listOf("is", "do"),
    "please" to listOf("send", "check", "let"),
    "let" to listOf("me", "us"),
    "see" to listOf("you"),
    "nice" to listOf("to"),
    "good" to listOf("morning", "night", "luck"),
    "happy" to listOf("birthday"),
    "looking" to listOf("for", "forward"),
    "forward" to listOf("to"),
    "best" to listOf("regards"),
    "kind" to listOf("regards"),
    "can" to listOf("you"),
    "do" to listOf("you"),
    "have" to listOf("a"),
)
