package com.bitap.keyboard.core

import android.content.Context
import java.io.File

/** One persisted user-lexicon entry: pronunciation -> word plus a selection count. */
data class UserLexiconEntry(
    val pinyin: String,
    val word: String,
    val count: Int,
    val source: String = "user",
)

/**
 * Persistent storage for the IME's user lexicon.
 *
 * Production uses private SharedPreferences and [SharedPreferencesUserLexiconStore]
 * writes with apply() so recording never blocks the typing thread. FileUserLexiconStore
 * is used by pure-JVM tests and callers that need an explicit path.
 *
 * Only pinyin, word and a small selection count/source are stored. No prompt,
 * surrounding input text, clipboard or AI context is ever written.
 */
interface UserLexiconStore {
    /** Legacy word -> count view, retained for source compatibility. */
    fun load(): Map<String, Int>

    /** Legacy save API; entries have no pinyin and are only used for ranking. */
    fun save(weights: Map<String, Int>)

    fun loadEntries(): List<UserLexiconEntry>

    fun saveEntries(entries: List<UserLexiconEntry>)

    fun clear()

    companion object {
        internal const val PREFERENCES_NAME = "bitap_user_lexicon"
        internal const val KEY_WEIGHTS = "weights"
        internal const val KEY_ENTRIES = "entries"

        fun create(context: Context): UserLexiconStore =
            SharedPreferencesUserLexiconStore(context.applicationContext)

        internal fun encode(weights: Map<String, Int>): String = weights.entries
            .asSequence()
            .filter { it.key.isNotBlank() && it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString("\n") { entry -> entry.key + "\t" + entry.value }

        internal fun decode(raw: String?): Map<String, Int> {
            if (raw.isNullOrBlank()) return emptyMap()
            val result = LinkedHashMap<String, Int>()
            raw.lineSequence().forEach { line ->
                val parts = line.split('\t')
                if (parts.size < 2) return@forEach
                val word = parts[0].trim()
                val count = parts[1].trim().toIntOrNull() ?: return@forEach
                if (word.isNotEmpty() && count > 0) result[word] = count
            }
            return result
        }

        internal fun encodeEntries(entries: List<UserLexiconEntry>): String = entries
            .asSequence()
            .filter { it.word.isNotBlank() && it.count > 0 }
            .distinctBy { it.pinyin + "\t" + it.word }
            .sortedWith(
                compareByDescending<UserLexiconEntry> { it.count }
                    .thenBy { it.pinyin }
                    .thenBy { it.word },
            )
            .joinToString("\n") { entry ->
                entry.pinyin + "\t" + entry.word + "\t" + entry.count + "\t" + entry.source
            }

        internal fun decodeEntries(raw: String?): List<UserLexiconEntry> {
            if (raw.isNullOrBlank()) return emptyList()
            val result = ArrayList<UserLexiconEntry>()
            raw.lineSequence().forEach { line ->
                val parts = line.split('\t')
                if (parts.size < 2) return@forEach
                when (parts.size) {
                    2 -> {
                        // Legacy format: word<TAB>count
                        val word = parts[0].trim()
                        val count = parts[1].trim().toIntOrNull() ?: return@forEach
                        if (word.isNotEmpty() && count > 0) {
                            result += UserLexiconEntry(pinyin = "", word = word, count = count)
                        }
                    }

                    else -> {
                        val pinyin = parts[0].trim()
                        val word = parts[1].trim()
                        val count = parts[2].trim().toIntOrNull() ?: return@forEach
                        val source = parts.getOrNull(3)?.trim().orEmpty().ifEmpty { "user" }
                        if (word.isNotEmpty() && count > 0) {
                            result += UserLexiconEntry(pinyin, word, count, source)
                        }
                    }
                }
            }
            return result
        }
    }
}

/** File-backed store used by tests and non-Android callers. */
class FileUserLexiconStore(private val file: File) : UserLexiconStore {

    private val lock = Any()

    override fun load(): Map<String, Int> = loadEntries().associate { it.word to it.count }

    override fun save(weights: Map<String, Int>) {
        saveRaw(UserLexiconStore.encode(weights))
    }

    override fun loadEntries(): List<UserLexiconEntry> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        runCatching {
            UserLexiconStore.decodeEntries(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    override fun saveEntries(entries: List<UserLexiconEntry>) {
        saveRaw(UserLexiconStore.encodeEntries(entries))
    }

    private fun saveRaw(raw: String) {
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(raw, Charsets.UTF_8)
                if (!temp.renameTo(file)) {
                    file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
                    temp.delete()
                }
            }
        }
    }

    override fun clear() {
        synchronized(lock) {
            runCatching { file.delete() }
        }
    }
}

private class SharedPreferencesUserLexiconStore(context: Context) : UserLexiconStore {
    private val preferences = context.getSharedPreferences(
        UserLexiconStore.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): Map<String, Int> = loadEntries().associate { it.word to it.count }

    override fun save(weights: Map<String, Int>) {
        preferences.edit()
            .putString(UserLexiconStore.KEY_WEIGHTS, UserLexiconStore.encode(weights))
            .apply()
    }

    override fun loadEntries(): List<UserLexiconEntry> {
        val fromEntries = UserLexiconStore.decodeEntries(
            preferences.getString(UserLexiconStore.KEY_ENTRIES, null),
        )
        if (fromEntries.isNotEmpty()) return fromEntries
        return UserLexiconStore.decode(preferences.getString(UserLexiconStore.KEY_WEIGHTS, null))
            .map { (word, count) -> UserLexiconEntry("", word, count) }
    }

    override fun saveEntries(entries: List<UserLexiconEntry>) {
        preferences.edit()
            .putString(UserLexiconStore.KEY_ENTRIES, UserLexiconStore.encodeEntries(entries))
            .remove(UserLexiconStore.KEY_WEIGHTS)
            .apply()
    }

    override fun clear() {
        preferences.edit()
            .remove(UserLexiconStore.KEY_ENTRIES)
            .remove(UserLexiconStore.KEY_WEIGHTS)
            .apply()
    }
}
