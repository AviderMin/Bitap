package com.bitap.keyboard.core

import android.net.Uri
import android.text.InputType
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.ime.KeyboardController
import com.bitap.keyboard.settings.BitapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controller-level coverage for English completion and next-word chips. */
class KeyboardControllerEnglishPredictionTest {

    @Test
    fun typingLettersShowsCompletionAndTapReplacesPrefix() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ToggleLanguage)

            listOf("h", "e", "l").forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it)) }
            assertEquals("hel", runtime.session.text)
            assertTrue(
                "completion should contain hello",
                runtime.controller.state.value.englishSuggestions.contains("hello"),
            )

            runtime.controller.dispatch(KeyboardAction.EnglishSuggestionTapped("hello"))
            assertEquals("hello ", runtime.session.text)
            assertTrue(runtime.controller.state.value.englishSuggestions.isEmpty())
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun externalCursorMoveInvalidatesCompletionBeforeTap() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ToggleLanguage)
            "abc hel".forEach { char ->
                runtime.controller.dispatch(
                    if (char == ' ') KeyboardAction.Space else KeyboardAction.AppendInput(char.toString()),
                )
            }
            assertTrue(
                "completion should be visible before the cursor move",
                runtime.controller.state.value.englishSuggestions.contains("hello"),
            )

            // External caret move to the middle of the text. The tracked prefix
            // no longer belongs before the caret.
            runtime.session.select(3, 3)
            runtime.controller.onSelectionChanged(3, 3, -1, -1, oldSelectionStart = 7, oldSelectionEnd = 7)
            assertTrue(
                "external cursor move must clear completion suggestions",
                runtime.controller.state.value.englishSuggestions.isEmpty(),
            )

            runtime.controller.dispatch(KeyboardAction.EnglishSuggestionTapped("hello"))
            assertEquals("tapped completion must not edit after a cursor move", "abc hel", runtime.session.text)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun completionTapRechecksPrefixEvenWithoutSelectionCallback() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ToggleLanguage)
            "abc hel".forEach { char ->
                runtime.controller.dispatch(
                    if (char == ' ') KeyboardAction.Space else KeyboardAction.AppendInput(char.toString()),
                )
            }
            // Move the editor caret directly; the controller still believes the
            // old tracked prefix until it re-reads the editor before deleting.
            runtime.session.select(3, 3)
            runtime.controller.dispatch(KeyboardAction.EnglishSuggestionTapped("hello"))
            assertEquals("prefix re-check must protect unrelated text", "abc hel", runtime.session.text)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun ownCommitSelectionCallbacksKeepTheTypedPrefix() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ToggleLanguage)

            // IME-generated selection callbacks after each committed letter must
            // not be mistaken for external moves (otherwise every letter resets).
            listOf("h", "e", "l").forEachIndexed { index, letter ->
                runtime.controller.dispatch(KeyboardAction.AppendInput(letter))
                runtime.controller.onSelectionChanged(
                    index + 1,
                    index + 1,
                    -1,
                    -1,
                    oldSelectionStart = index,
                    oldSelectionEnd = index,
                )
            }
            assertTrue(
                "own commit callbacks must preserve the typed prefix",
                runtime.controller.state.value.englishSuggestions.contains("hello"),
            )
            runtime.controller.dispatch(KeyboardAction.EnglishSuggestionTapped("hello"))
            assertEquals("hello ", runtime.session.text)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun settingsClearDoesNotResurrectEnglishLearning() {
        val store = InMemoryEnglishPredictionStore()
        val runtime = Runtime(store = store)
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ToggleLanguage)
            "privateword".forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            runtime.controller.dispatch(KeyboardAction.Space)
            assertTrue("the typed word must be persisted", store.data.wordCounts.containsKey("privateword"))

            // Settings clears the persisted store through a separate instance.
            store.clear()
            runtime.controller.onFinishInput()
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ToggleLanguage)
            "hello".forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            runtime.controller.dispatch(KeyboardAction.Space)

            assertFalse(
                "cleared word must not be saved back from stale memory",
                store.data.wordCounts.containsKey("privateword"),
            )
            assertTrue("the new word is still learned", store.data.wordCounts.containsKey("hello"))
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun spaceRecordsWordAndShowsNextWordSuggestion() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ToggleLanguage)

            "thank".forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            runtime.controller.dispatch(KeyboardAction.Space)
            assertEquals("thank ", runtime.session.text)
            assertEquals(listOf("you"), runtime.controller.state.value.englishSuggestions)

            runtime.controller.dispatch(KeyboardAction.EnglishSuggestionTapped("you"))
            assertEquals("thank you ", runtime.session.text)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun learningDisabledStillCompletesButDoesNotPersistWords() {
        val store = InMemoryEnglishPredictionStore()
        val runtime = Runtime(store = store)
        try {
            runtime.controller.onSettingsChanged(BitapSettings(learningEnabled = false))
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ToggleLanguage)

            "hel".forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            assertTrue(runtime.controller.state.value.englishSuggestions.contains("hello"))
            runtime.controller.dispatch(KeyboardAction.EnglishSuggestionTapped("hello"))
            assertEquals("hello ", runtime.session.text)
            assertTrue("learning-disabled must not save words", store.data.wordCounts.isEmpty())
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun passwordFieldNeverShowsEnglishSuggestions() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(
                EditorInputInfo.from(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    0,
                ),
            )
            runtime.controller.dispatch(KeyboardAction.AppendInput("h"))
            assertTrue(runtime.controller.state.value.englishSuggestions.isEmpty())
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun clearUserLexiconClearsEnglishLearning() {
        val store = InMemoryEnglishPredictionStore()
        val runtime = Runtime(store = store)
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ToggleLanguage)
            "bitap".forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            runtime.controller.dispatch(KeyboardAction.Space)
            assertTrue(store.data.wordCounts.containsKey("bitap"))

            runtime.controller.clearUserLexicon()
            assertTrue("store must be cleared", store.data.wordCounts.isEmpty())
        } finally {
            runtime.dispose()
        }
    }

    private class Runtime(store: InMemoryEnglishPredictionStore = InMemoryEnglishPredictionStore()) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = TestEditorTextSession()
        val controller = KeyboardController(
            scope = scope,
            textSession = session,
            pinyinEngine = PinyinEngine.empty(),
            aiService = EnglishAiService(),
            queryDispatcher = dispatcher,
            englishPredictionStore = store,
        )

        fun dispose() {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }

    private class InMemoryEnglishPredictionStore : EnglishPredictionStore {
        var data: EnglishPredictionData = EnglishPredictionData()
            private set

        override fun load(): EnglishPredictionData = data

        override fun save(data: EnglishPredictionData) {
            this.data = data
        }

        override fun clear() {
            data = EnglishPredictionData()
        }
    }

    private class EnglishAiService : AiRewriteService {
        override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.NotInstalled)
        override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo())

        override fun refresh() = Unit
        override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
            Result.failure(UnsupportedOperationException("not used"))

        override fun deleteModel() = Unit
        override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = emptyFlow()
        override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> = emptyFlow()
        override fun cancel() = Unit
        override fun close() = Unit
    }
}
