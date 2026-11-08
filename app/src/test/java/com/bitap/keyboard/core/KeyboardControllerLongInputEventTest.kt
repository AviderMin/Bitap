package com.bitap.keyboard.core

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.ime.KeyboardController
import java.io.File
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event-level controller test for the long-input consumption chain.
 *
 * Unlike the pure offset check, this drives KeyboardAction.AppendInput ->
 * CandidateTapped / CommitRaw through a real KeyboardController and
 * TestEditorTextSession, then asserts the actual editor text and the composing
 * state after the commit. It uses the production dictionary but never writes it.
 */
class KeyboardControllerLongInputEventTest {

    private val input = "jiedaobanshichuweiyuxinyushichengbeidiqudexianlaidadaonanhudebeibu"

    @Test
    fun candidateCommitWritesCommittedWordAndLeavesExactRemainingComposing() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput(input))
            runtime.scheduler.advanceUntilIdle()

            val before = runtime.controller.state.value
            assertEquals("full input must stay composing before selection", input, before.composing)
            assertTrue("long input must have candidates", before.candidates.isNotEmpty())
            val candidate = before.candidates.first()
            val consumed = candidate.consumedInputLength.coerceAtLeast(1)
            assertTrue("candidate must not consume beyond UI composing", consumed <= input.length)

            runtime.controller.dispatch(KeyboardAction.CandidateTapped(0))
            val after = runtime.controller.state.value
            val expectedRemaining = input.substring(consumed)
            assertEquals("editor text must be committed word plus exact remaining pinyin", candidate.word + expectedRemaining, runtime.session.text)
            assertEquals("controller composing must keep only the unconsumed suffix", expectedRemaining, after.composing)
            assertEquals("", after.candidatesFor)
            assertTrue("actual setComposing event must carry the exact suffix", runtime.session.log.contains("setComposing:" + expectedRemaining))
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun commitRawWritesFullLongInputAndClearsComposing() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput(input))
            runtime.scheduler.advanceUntilIdle()
            assertEquals(input, runtime.controller.state.value.composing)

            runtime.controller.dispatch(KeyboardAction.CommitRaw)
            assertEquals("commit-raw must write the complete original input", input, runtime.session.text)
            assertEquals("", runtime.controller.state.value.composing)
            assertTrue(runtime.session.log.contains("commit:" + input))
        } finally {
            runtime.dispose()
        }
    }

    private inner class Runtime {
        val session = TestEditorTextSession()
        val engine = loadEngine()
        var scheduler = TestCoroutineScheduler()
        var dispatcher = StandardTestDispatcher(scheduler)
        var scope = CoroutineScope(SupervisorJob() + dispatcher)
        var controller = KeyboardController(scope, session, engine, StubAiService(), dispatcher)

        fun dispose() {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }

    private fun loadEngine(): PinyinEngine {
        val root = findRepoRoot()
        val dictionary = File(root, "app/src/main/assets/pinyin_dict.tsv")
        assertTrue("dictionary missing", dictionary.isFile)
        return dictionary.useLines { lines -> PinyinEngine.fromLines(lines) }
    }

    private fun findRepoRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "app/src/main/assets/pinyin_dict.tsv").isFile &&
                File(directory, "eval/d05/dev/dev.tsv").isFile
            ) {
                return directory
            }
            directory = directory.parentFile
        }
        error("repository root not found")
    }

    private class StubAiService : AiRewriteService {
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
