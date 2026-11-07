package com.bitap.keyboard.core

import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S04: App clear must invalidate a running engine and old IO snapshots. */
class KeyboardS04Test {

    @Test
    fun clearDuringDelayedSaveCannotWriteBackOldSnapshot() {
        val store = BlockingUserLexiconStore()
        val engine = PinyinEngine.fromLines(emptySequence(), store)
        engine.recordSelection("天气", "tianqi", source = "ai", persist = false)

        val executor = Executors.newSingleThreadExecutor()
        try {
            val save = executor.submit { engine.persistSelectionWeights() }
            assertTrue("save did not start", store.saveStarted.await(5, TimeUnit.SECONDS))
            store.clear()
            store.releaseSave.countDown()
            save.get(5, TimeUnit.SECONDS)

            assertTrue("old IO snapshot was written back after clear", store.loadEntries().isEmpty())
            assertEquals(0, engine.selectionCount("天气"))
        } finally {
            store.releaseSave.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun clearingTheStoreInvalidatesRunningEngineOnNextSearch() {
        val file = tempFile()
        try {
            val engine = PinyinEngine.fromLines(
                emptySequence(),
                FileUserLexiconStore(file),
            )
            engine.recordSelection("天气", "tianqi", source = "ai")
            assertTrue(engine.search("tianqi").map { it.word }.contains("天气"))

            // This is the path currently used by the App dialog: a fresh store
            // instance clears disk but the live engine is not recreated.
            FileUserLexiconStore(file).clear()

            assertTrue("running engine must see the clear generation", engine.search("tianqi").isEmpty())
            assertEquals(0, engine.selectionCount("天气"))
            assertTrue(FileUserLexiconStore(file).loadEntries().isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun bothMemoryAndPersistedSetsAreCappedAtTwoHundred() {
        val file = tempFile()
        try {
            val engine = PinyinEngine.fromLines(emptySequence(), FileUserLexiconStore(file))
            repeat(PinyinEngine.MAX_USER_ENTRIES + 50) { index ->
                engine.recordSelection("词" + index, "ci" + index, persist = false)
            }
            assertEquals(PinyinEngine.MAX_USER_ENTRIES, engine.userEntryCount)
            assertEquals(PinyinEngine.MAX_USER_ENTRIES, engine.selectionWordCount)

            engine.persistSelectionWeights()
            assertTrue(
                "persisted entries exceeded capacity: " + FileUserLexiconStore(file).loadEntries().size,
                FileUserLexiconStore(file).loadEntries().size <= PinyinEngine.MAX_USER_ENTRIES,
            )
        } finally {
            file.delete()
        }
    }

    private fun tempFile(): File = File(
        System.getProperty("java.io.tmpdir"),
        "bitap-user-lexicon-s04-" + UUID.randomUUID() + ".tsv",
    )
}

private class BlockingUserLexiconStore : UserLexiconStore {
    private val lock = Any()
    private var backing: List<UserLexiconEntry> = emptyList()

    val saveStarted = CountDownLatch(1)
    val releaseSave = CountDownLatch(1)

    override fun load(): Map<String, Int> = loadEntries().associate { it.word to it.count }

    override fun save(weights: Map<String, Int>) {
        saveEntries(weights.map { (word, count) -> UserLexiconEntry("", word, count) })
    }

    override fun loadEntries(): List<UserLexiconEntry> = synchronized(lock) { backing.toList() }

    override fun saveEntries(entries: List<UserLexiconEntry>) {
        saveStarted.countDown()
        releaseSave.await(5, TimeUnit.SECONDS)
        synchronized(lock) { backing = entries.toList() }
    }

    override fun clear() {
        UserLexiconStore.markCleared()
        synchronized(lock) { backing = emptyList() }
    }
}
