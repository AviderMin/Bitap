package com.bitap.keyboard.ime

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.core.DictionaryLoadState
import com.bitap.keyboard.core.EditorInputInfo
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.KeyboardReducer
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.core.TestEditorTextSession
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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P0-B controller coverage for the retry hook: dispatching RetryDictionaryLoad
 * must move the observable state to Loading immediately and invoke the injected
 * loader exactly once; a failed dictionary must still allow raw-text commit.
 */
class KeyboardControllerDictionaryTest {

    @Test
    fun retryDispatchInvokesLoaderOnceAndMovesToLoading() {
        var reloads = 0
        val runtime = Runtime(onDictionaryRetry = { reloads++ })
        try {
            runtime.controller.onDictionaryStateChanged(
                DictionaryLoadState.Failed,
                KeyboardReducer.DICTIONARY_LOAD_FAILED_MESSAGE,
            )
            assertEquals(
                DictionaryLoadState.Failed,
                runtime.controller.state.value.dictionaryState,
            )
            assertEquals(
                KeyboardReducer.DICTIONARY_LOAD_FAILED_MESSAGE,
                runtime.controller.state.value.dictionaryError,
            )

            runtime.controller.dispatch(KeyboardAction.RetryDictionaryLoad)

            assertEquals("the injected loader must run exactly once", 1, reloads)
            assertEquals(
                DictionaryLoadState.Loading,
                runtime.controller.state.value.dictionaryState,
            )
            assertNull(
                "retry clears the previous error before the reload starts",
                runtime.controller.state.value.dictionaryError,
            )
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun retryWithoutInjectedLoaderStillShowsLoading() {
        val runtime = Runtime()
        try {
            runtime.controller.dispatch(KeyboardAction.RetryDictionaryLoad)
            assertEquals(
                DictionaryLoadState.Loading,
                runtime.controller.state.value.dictionaryState,
            )
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun readyStateClearsDictionaryError() {
        val runtime = Runtime()
        try {
            runtime.controller.onDictionaryStateChanged(
                DictionaryLoadState.Failed,
                "词库加载失败",
            )
            runtime.controller.onDictionaryStateChanged(DictionaryLoadState.Ready)
            assertEquals(
                DictionaryLoadState.Ready,
                runtime.controller.state.value.dictionaryState,
            )
            assertNull(runtime.controller.state.value.dictionaryError)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun failedDictionaryStillCommitsRawText() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.onDictionaryStateChanged(
                DictionaryLoadState.Failed,
                KeyboardReducer.DICTIONARY_LOAD_FAILED_MESSAGE,
            )
            runtime.controller.dispatch(KeyboardAction.AppendInput("nihao"))
            runtime.controller.dispatch(KeyboardAction.CommitRaw)

            assertEquals("raw pinyin must reach the editor", "nihao", runtime.session.text)
            assertEquals("", runtime.controller.state.value.composing)
            assertEquals(
                "the retry entry stays available after the raw commit",
                DictionaryLoadState.Failed,
                runtime.controller.state.value.dictionaryState,
            )
        } finally {
            runtime.dispose()
        }
    }

    private inner class Runtime(onDictionaryRetry: (() -> Unit)? = null) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = TestEditorTextSession()
        val controller = KeyboardController(
            scope = scope,
            textSession = session,
            pinyinEngine = PinyinEngine.empty(),
            aiService = DictionaryAiService(),
            queryDispatcher = dispatcher,
            onDictionaryRetry = onDictionaryRetry,
        )

        fun dispose() {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }
}

private class DictionaryAiService : AiRewriteService {
    override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.NotInstalled)
    override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo())

    override fun refresh() = Unit

    override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
        Result.failure(UnsupportedOperationException("not used by unit tests"))

    override fun deleteModel() = Unit

    override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = emptyFlow()

    override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> = emptyFlow()

    override fun cancel() = Unit

    override fun close() = Unit
}
