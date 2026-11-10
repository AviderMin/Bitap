package com.bitap.keyboard.core

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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

    /**
     * Atomically replace entries only when both the clear generation and the
     * persisted revision still match the values observed before the caller
     * built [entries]. A stale snapshot (settings delete/clear won the race)
     * must not be written back; the caller reloads the store instead.
     *
     * Implementations shared by several instances must perform the checks and
     * the write under one lock; the default here is only a best-effort
     * contract for simple single-instance test stores.
     */
    fun saveEntriesIfCurrent(
        entries: List<UserLexiconEntry>,
        generation: Long,
        expectedRevision: Long,
    ): Boolean {
        if (generation != currentClearGeneration()) return false
        if (expectedRevision != currentRevision()) return false
        saveEntries(entries)
        return true
    }

    /** Legacy overload guarding only against clear(); prefer the revision form. */
    fun saveEntriesIfCurrent(entries: List<UserLexiconEntry>, generation: Long): Boolean =
        saveEntriesIfCurrent(entries, generation, currentRevision())

    /**
     * Atomically read the current entries, apply [transform] and persist the
     * result. Used by settings single-entry deletion so a concurrent engine
     * save cannot be lost between separate load and save calls. Returns the
     * entries that are now stored. Implementations shared by several instances
     * must override this with one lock for read + transform + write; the
     * default is a best-effort contract for simple test stores.
     */
    fun updateEntries(
        transform: (List<UserLexiconEntry>) -> List<UserLexiconEntry>,
    ): List<UserLexiconEntry> {
        val updated = transform(loadEntries())
        saveEntries(updated)
        return updated
    }

    fun clear()

    /**
     * Monotonic revision of this store's persisted content. Implementations
     * that can be replaced by another instance for the same backing storage
     * bump it on every save/clear so a running engine can detect external
     * edits instead of writing a stale snapshot back.
     */
    fun currentRevision(): Long = 0L

    companion object {
        private val clearGeneration = AtomicLong(0L)

        /** Monotonic process-wide signal used to invalidate running IME snapshots. */
        fun currentClearGeneration(): Long = clearGeneration.get()

        internal fun markCleared() {
            clearGeneration.incrementAndGet()
        }

        internal const val PREFERENCES_NAME = "bitap_user_lexicon"
        internal const val KEY_WEIGHTS = "weights"
        internal const val KEY_ENTRIES = "entries"

        /** Cached shared instance so settings and the IME observe one store. */
        private val instances = ConcurrentHashMap<String, UserLexiconStore>()

        /** Process/name-scoped revision counters for SharedPreferences stores. */
        private val sharedPreferenceRevisions = ConcurrentHashMap<String, AtomicLong>()

        fun create(context: Context): UserLexiconStore {
            val appContext = context.applicationContext ?: context
            return instances.computeIfAbsent(PREFERENCES_NAME) {
                SharedPreferencesUserLexiconStore(appContext)
            }
        }

        internal fun sharedPreferencesRevision(name: String): AtomicLong =
            sharedPreferenceRevisions.computeIfAbsent(name) { AtomicLong(0L) }

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

    private val canonicalPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    /** One lock per canonical path, shared by every store instance for that file. */
    private val lock = pathLock(canonicalPath)

    override fun currentRevision(): Long = revisionCounter(canonicalPath).get()

    override fun load(): Map<String, Int> = loadEntries().associate { it.word to it.count }

    override fun save(weights: Map<String, Int>) {
        val raw = UserLexiconStore.encode(weights)
        synchronized(lock) { writeRawLocked(raw) }
    }

    override fun loadEntries(): List<UserLexiconEntry> = synchronized(lock) {
        readEntriesLocked()
    }

    private fun readEntriesLocked(): List<UserLexiconEntry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            UserLexiconStore.decodeEntries(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    override fun saveEntries(entries: List<UserLexiconEntry>) {
        synchronized(lock) { writeEntriesLocked(entries) }
    }

    override fun saveEntriesIfCurrent(
        entries: List<UserLexiconEntry>,
        generation: Long,
        expectedRevision: Long,
    ): Boolean = synchronized(lock) {
        if (generation != UserLexiconStore.currentClearGeneration()) return@synchronized false
        if (expectedRevision != revisionCounter(canonicalPath).get()) return@synchronized false
        writeEntriesLocked(entries)
    }

    override fun updateEntries(
        transform: (List<UserLexiconEntry>) -> List<UserLexiconEntry>,
    ): List<UserLexiconEntry> = synchronized(lock) {
        val current = readEntriesLocked()
        val updated = transform(current)
        if (writeEntriesLocked(updated)) updated else current
    }

    /** Writes entries and bumps the shared revision; caller holds [lock]. */
    private fun writeEntriesLocked(entries: List<UserLexiconEntry>): Boolean =
        writeRawLocked(UserLexiconStore.encodeEntries(entries))

    /** Writes raw content and bumps the shared revision; caller holds [lock]. */
    private fun writeRawLocked(raw: String): Boolean {
        val written = runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(raw, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
                temp.delete()
            }
            true
        }.getOrDefault(false)
        if (written) revisionCounter(canonicalPath).incrementAndGet()
        return written
    }

    override fun clear() {
        synchronized(lock) {
            UserLexiconStore.markCleared()
            runCatching { file.delete() }
            revisionCounter(canonicalPath).incrementAndGet()
        }
    }

    companion object {
        /** Revision counters shared by every store instance for one canonical path. */
        private val revisions = ConcurrentHashMap<String, AtomicLong>()

        /** Locks shared by every store instance for one canonical path. */
        private val pathLocks = ConcurrentHashMap<String, Any>()

        internal fun revisionCounter(canonicalPath: String): AtomicLong =
            revisions.computeIfAbsent(canonicalPath) { AtomicLong(0L) }

        internal fun pathLock(canonicalPath: String): Any =
            pathLocks.computeIfAbsent(canonicalPath) { Any() }
    }
}

private class SharedPreferencesUserLexiconStore(context: Context) : UserLexiconStore {
    private val preferences = context.getSharedPreferences(
        UserLexiconStore.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val revision = UserLexiconStore.sharedPreferencesRevision(
        UserLexiconStore.PREFERENCES_NAME,
    )

    /** One instance lock: read/check/transform/write must not interleave. */
    private val lock = Any()

    override fun currentRevision(): Long = revision.get()

    override fun load(): Map<String, Int> = loadEntries().associate { it.word to it.count }

    override fun save(weights: Map<String, Int>) {
        synchronized(lock) {
            preferences.edit()
                .putString(UserLexiconStore.KEY_WEIGHTS, UserLexiconStore.encode(weights))
                .apply()
            revision.incrementAndGet()
        }
    }

    override fun loadEntries(): List<UserLexiconEntry> = synchronized(lock) {
        readEntriesLocked()
    }

    private fun readEntriesLocked(): List<UserLexiconEntry> {
        val fromEntries = UserLexiconStore.decodeEntries(
            preferences.getString(UserLexiconStore.KEY_ENTRIES, null),
        )
        if (fromEntries.isNotEmpty()) return fromEntries
        return UserLexiconStore.decode(preferences.getString(UserLexiconStore.KEY_WEIGHTS, null))
            .map { (word, count) -> UserLexiconEntry("", word, count) }
    }

    override fun saveEntries(entries: List<UserLexiconEntry>) {
        synchronized(lock) { writeEntriesLocked(entries) }
    }

    override fun saveEntriesIfCurrent(
        entries: List<UserLexiconEntry>,
        generation: Long,
        expectedRevision: Long,
    ): Boolean = synchronized(lock) {
        if (generation != UserLexiconStore.currentClearGeneration()) return@synchronized false
        if (expectedRevision != revision.get()) return@synchronized false
        writeEntriesLocked(entries)
        true
    }

    override fun updateEntries(
        transform: (List<UserLexiconEntry>) -> List<UserLexiconEntry>,
    ): List<UserLexiconEntry> = synchronized(lock) {
        val updated = transform(readEntriesLocked())
        writeEntriesLocked(updated)
        updated
    }

    /** Writes entries and bumps the process/name-scoped revision; caller holds [lock]. */
    private fun writeEntriesLocked(entries: List<UserLexiconEntry>) {
        preferences.edit()
            .putString(UserLexiconStore.KEY_ENTRIES, UserLexiconStore.encodeEntries(entries))
            .remove(UserLexiconStore.KEY_WEIGHTS)
            .apply()
        revision.incrementAndGet()
    }

    override fun clear() {
        synchronized(lock) {
            UserLexiconStore.markCleared()
            preferences.edit()
                .remove(UserLexiconStore.KEY_ENTRIES)
                .remove(UserLexiconStore.KEY_WEIGHTS)
                .apply()
            revision.incrementAndGet()
        }
    }
}
