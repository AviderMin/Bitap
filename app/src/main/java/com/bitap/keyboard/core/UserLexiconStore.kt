package com.bitap.keyboard.core

import android.content.Context
import java.io.File

/**
 * Persistent storage for the IME's per-word user selection counts.
 *
 * The production implementation uses private SharedPreferences and [apply] so
 * recording a tap never blocks the typing thread. A file-backed implementation is
 * provided for pure-JVM tests and for callers that need an explicit path.
 *
 * Only the word and a small integer count are stored; no prompt, input text or
 * surrounding context is ever written.
 */
interface UserLexiconStore {
    fun load(): Map<String, Int>

    fun save(weights: Map<String, Int>)

    fun clear()

    companion object {
        internal const val PREFERENCES_NAME = "bitap_user_lexicon"
        internal const val KEY_WEIGHTS = "weights"

        /** Production store backed by private SharedPreferences. */
        fun create(context: Context): UserLexiconStore =
            SharedPreferencesUserLexiconStore(context.applicationContext)

        /** Deterministic encoding shared by both implementations. */
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
    }
}

/** File-backed store used by tests and non-Android callers. */
class FileUserLexiconStore(private val file: File) : UserLexiconStore {

    private val lock = Any()

    override fun load(): Map<String, Int> = synchronized(lock) {
        if (!file.isFile) return emptyMap()
        runCatching {
            UserLexiconStore.decode(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyMap())
    }

    override fun save(weights: Map<String, Int>) {
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(UserLexiconStore.encode(weights), Charsets.UTF_8)
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

    override fun load(): Map<String, Int> = UserLexiconStore.decode(
        preferences.getString(UserLexiconStore.KEY_WEIGHTS, null),
    )

    override fun save(weights: Map<String, Int>) {
        preferences.edit()
            .putString(UserLexiconStore.KEY_WEIGHTS, UserLexiconStore.encode(weights))
            .apply()
    }

    override fun clear() {
        preferences.edit().remove(UserLexiconStore.KEY_WEIGHTS).apply()
    }
}
