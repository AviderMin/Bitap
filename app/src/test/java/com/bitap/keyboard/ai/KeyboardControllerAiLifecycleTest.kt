package com.bitap.keyboard.ai

import android.net.Uri
import com.bitap.keyboard.core.EditorTextSnapshot
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.core.TextSession
import com.bitap.keyboard.core.EditorInputInfo
import com.bitap.keyboard.ime.KeyboardController
import com.bitap.keyboard.settings.BitapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D02 controller wiring: the AI settings switch must call the measured unload
 * path instead of only cancelling the current generation, and IME dispose must
 * request an engine unload without permanently closing the app-wide service.
 */
class KeyboardControllerAiLifecycleTest {

    @Test
    fun aiOffSettingInvokesServiceUnloadPathAndReenableInvokesEnable() = runBlocking {
        val service = LifecycleRecordingAiService()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = KeyboardController(scope, FakeTextSession(), PinyinEngine.empty(), service)
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.onAiRuntimeChanged(AiRuntimeState.Ready)

            controller.onSettingsChanged(BitapSettings(aiEnabled = false))
            assertEquals(false, withTimeout(TIMEOUT_MS) { service.enableStates.receive() })

            controller.onSettingsChanged(BitapSettings(aiEnabled = true))
            assertEquals(true, withTimeout(TIMEOUT_MS) { service.enableStates.receive() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeatedAiOffSettingsDoNotScheduleRepeatedUnloads() = runBlocking {
        val service = LifecycleRecordingAiService()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = KeyboardController(scope, FakeTextSession(), PinyinEngine.empty(), service)
            controller.onStartInput(EditorInputInfo.from(0, 0))

            controller.onSettingsChanged(BitapSettings(aiEnabled = false))
            assertEquals(false, withTimeout(TIMEOUT_MS) { service.enableStates.receive() })

            // Another unrelated setting change while AI stays off must not re-run
            // the unload path (no second signal within a short window).
            controller.onSettingsChanged(BitapSettings(aiEnabled = false, soundEnabled = true))
            assertNull(withTimeoutOrNull(SHORT_WINDOW_MS) { service.enableStates.receive() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun disposeRequestsUnloadButDoesNotCloseSharedService() {
        val service = LifecycleRecordingAiService()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = KeyboardController(scope, FakeTextSession(), PinyinEngine.empty(), service)
            controller.dispose()

            assertEquals(1, service.unloadRequests)
            assertEquals("dispose must not permanently close the app-wide service", 0, service.closeCalls)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SHORT_WINDOW_MS = 250L
    }
}

private class LifecycleRecordingAiService : AiRewriteService {
    override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.Ready)
    override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo(installed = true))

    val enableStates = Channel<Boolean>(Channel.UNLIMITED)

    var unloadRequests: Int = 0
        private set
    var closeCalls: Int = 0
        private set

    override fun refresh() = Unit

    override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
        Result.failure(UnsupportedOperationException("not used by unit tests"))

    override fun deleteModel() = Unit

    override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = emptyFlow()

    override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> = emptyFlow()

    override fun cancel() = Unit

    override suspend fun setEnabled(enabled: Boolean) {
        enableStates.trySend(enabled)
    }

    override fun requestUnload() {
        unloadRequests++
    }

    override fun close() {
        closeCalls++
    }
}

private class FakeTextSession : TextSession {
    override val isActive: Boolean = true

    override fun commitText(text: String) = Unit

    override fun setComposingText(text: String) = Unit

    override fun finishComposingText() = Unit

    override fun deleteBackward() = Unit

    override fun sendEnter() = Unit

    override fun getSelectedText(): String? = null

    override fun getTextBeforeCursor(maxChars: Int): String? = null

    override fun getSurroundingText(maxChars: Int): EditorTextSnapshot = EditorTextSnapshot(null, null)

    override fun readAiSource(maxChars: Int): String = ""
}
