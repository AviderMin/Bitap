package com.bitap.keyboard.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R08: user lexicon stores pronunciation -> word so AI new words are searchable
 * after restart; learning switch/privacy flag gate writes; clear acts on the
 * running engine instance.
 */
class KeyboardR08Test {

    private fun tempFile(): File = File(
        System.getProperty("java.io.tmpdir"),
        "bitap-user-lexicon-r08-" + UUID.randomUUID() + ".tsv",
    )

    @Test
    fun aiNewWordBecomesSearchableAndSurvivesRestart() {
        val file = tempFile()
        try {
            val lines = sequenceOf("ni\t你\t100")
            val first = PinyinEngine.fromLines(lines, FileUserLexiconStore(file))
            assertTrue(first.search("tianqi").isEmpty())

            first.recordSelection(word = "天气", pinyin = "tianqi", source = "ai", persist = true)
            assertTrue(
                "new AI word should be searchable immediately: " +
                    first.search("tianqi").map { it.word },
                first.search("tianqi").map { it.word }.contains("天气"),
            )

            val restarted = PinyinEngine.fromLines(
                sequenceOf("ni\t你\t100"),
                FileUserLexiconStore(file),
            )
            assertTrue(
                "new AI word should survive IME restart: " +
                    restarted.search("tianqi").map { it.word },
                restarted.search("tianqi").map { it.word }.contains("天气"),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun learningDisabledDoesNotWriteUserLexicon() {
        val file = tempFile()
        try {
            val engine = PinyinEngine.fromLines(sequenceOf("ni\t你\t100"), FileUserLexiconStore(file))
            engine.setLearningEnabled(false)
            engine.recordSelection("天气", "tianqi")
            assertEquals(0, engine.selectionCount("天气"))
            assertFalse(file.exists())

            engine.setLearningEnabled(true)
            engine.recordSelection("天气", "tianqi")
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun personalizedLearningFlagBlocksLearning() {
        val info = EditorInputInfo.from(
            inputType = InputType.TYPE_CLASS_TEXT,
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        assertFalse(info.allowsPersonalizedLearning)
        assertFalse(info.learningAllowed)
        assertFalse(KeyboardUiState(editorInfo = info).learningAllowed)
    }

    @Test
    fun clearSelectionMemoryActsOnRunningEngineAndRestart() {
        val file = tempFile()
        try {
            val lines = sequenceOf("ni\t你\t100")
            val engine = PinyinEngine.fromLines(lines, FileUserLexiconStore(file))
            engine.recordSelection("天气", "tianqi")
            assertTrue(engine.search("tianqi").map { it.word }.contains("天气"))

            engine.clearSelectionMemory()
            assertEquals(0, engine.selectionCount("天气"))
            assertTrue(engine.search("tianqi").isEmpty())
            assertFalse(file.exists())

            val restarted = PinyinEngine.fromLines(lines, FileUserLexiconStore(file))
            assertTrue(restarted.search("tianqi").isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun externalClearIsNotResurrectedByRunningEnginePersist() {
        val file = tempFile()
        try {
            val store = FileUserLexiconStore(file)
            val engine = PinyinEngine.fromLines(sequenceOf("ni\t你\t100"), store)
            engine.recordSelection("你", "ni")
            assertTrue(store.loadEntries().isNotEmpty())

            // Settings clears the persisted entries; the live engine's deferred
            // save must adopt the empty store instead of restoring its snapshot.
            store.saveEntries(emptyList())
            engine.persistSelectionWeights()

            assertTrue("cleared entry must not be written back", store.loadEntries().isEmpty())
            assertEquals(0, engine.selectionCount("你"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun externalSingleEntryDeletionDoesNotResurrectOrDropOtherEntries() {
        val file = tempFile()
        try {
            val store = FileUserLexiconStore(file)
            val engine = PinyinEngine.fromLines(sequenceOf("ni\t你\t100"), store)
            engine.recordSelection("你", "ni")
            engine.recordSelection("天气", "tianqi", source = "ai")
            assertEquals(2, store.loadEntries().size)

            // The settings screen deletes one entry through another store
            // instance for the same file. The shared revision must let the
            // running engine reload the remaining entry, not drop it.
            val settingsStore = FileUserLexiconStore(file)
            settingsStore.saveEntries(settingsStore.loadEntries().filterNot { it.word == "你" })
            assertEquals(store.currentRevision(), settingsStore.currentRevision())

            engine.persistSelectionWeights()

            val persisted = store.loadEntries()
            assertEquals(listOf("天气"), persisted.map { it.word })
            assertEquals(0, engine.selectionCount("你"))
            assertEquals(1, engine.userEntryCount)
        } finally {
            file.delete()
        }
    }

    @Test
    fun userLexiconCapacityIsBounded() {
        val engine = PinyinEngine.fromLines(emptySequence())
        repeat(PinyinEngine.MAX_USER_ENTRIES + 20) { index ->
            engine.recordSelection("词" + index, "ci" + index, persist = false)
        }
        assertEquals(PinyinEngine.MAX_USER_ENTRIES, engine.userEntryCount)
    }

    @Test
    fun decayReducesSelectionCounts() {
        val engine = PinyinEngine.fromLines(sequenceOf("ni\t你\t100"))
        engine.recordSelection("你", "ni", persist = false)
        engine.recordSelection("你", "ni", persist = false)
        engine.recordSelection("你", "ni", persist = false)
        engine.recordSelection("你", "ni", persist = false)
        assertEquals(4, engine.selectionCount("你"))
        engine.decaySelectionWeights()
        assertEquals(2, engine.selectionCount("你"))
    }
}
