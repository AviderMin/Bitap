package com.bitap.keyboard.ai

import android.net.Uri
import com.bitap.keyboard.ai.internal.AiEngine
import com.bitap.keyboard.ai.internal.AiModelStore
import com.bitap.keyboard.ai.internal.DefaultAiRewriteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D02 lifecycle tests. Everything here runs on a plain JVM: [FakeAiEngine]
 * replaces LiteRT-LM and [FakeModelStore] replaces Android file storage, so the
 * real serialisation logic in [DefaultAiRewriteService] is exercised without a
 * device or a 328 MiB model.
 */
class AiServiceLifecycleTest {

    @Test
    fun cancelOnlyStopsGenerationAndKeepsEngineLoaded() = runBlocking {
        val harness = ServiceHarness()
        try {
            val collector = launch { harness.service.suggestCandidates("nihao", "wo").collect { } }
            withTimeout(TIMEOUT_MS) { harness.engine.loads.receive() }
            assertTrue(harness.engine.isLoaded)

            harness.service.cancel()
            collector.cancelAndJoin()

            assertTrue("cancel() must not release the native engine", harness.engine.isLoaded)
            assertEquals(0, harness.engine.releaseCount)

            harness.service.unload()
            assertFalse(harness.engine.isLoaded)
            assertEquals(1, harness.engine.releaseCount)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun disablingAiCancelsActiveGenerationAndReleasesEngineExactlyOnce() = runBlocking {
        val harness = ServiceHarness()
        try {
            val collector = launch { harness.service.suggestCandidates("nihao", "wo").collect { } }
            withTimeout(TIMEOUT_MS) { harness.engine.loads.receive() }
            assertTrue(harness.engine.isLoaded)
            assertTrue(harness.service.isEngineLoaded)

            harness.service.setEnabled(false)

            assertFalse("native engine must be released on AI off", harness.engine.isLoaded)
            assertFalse(harness.service.isEngineLoaded)
            assertEquals("release must happen exactly once", 1, harness.engine.releaseCount)
            assertTrue("the active generation must be cancelled", harness.engine.cancelCalls >= 1)
            assertFalse(
                "release must never run while native inference is active",
                harness.engine.releasedWhileInferenceActive,
            )
            collector.cancelAndJoin()

            // While disabled, requests must be rejected before any native work.
            val rejected = harness.service.suggestCandidates("ni", "").toList()
            assertTrue(rejected.single() is AiEvent.Failed)
            assertEquals(1, harness.engine.rewriteCalls)
            assertFalse(harness.engine.isLoaded)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun rapidEnableDisableCyclesDoNotLeakOrCrash() = runBlocking {
        val harness = ServiceHarness()
        try {
            val first = launch { harness.service.suggestCandidates("ni", "").collect { } }
            withTimeout(TIMEOUT_MS) { harness.engine.loads.receive() }

            harness.service.setEnabled(false)
            assertEquals(1, harness.engine.releaseCount)

            harness.service.setEnabled(true)
            val second = launch { harness.service.suggestCandidates("ni", "").collect { } }
            withTimeout(TIMEOUT_MS) { harness.engine.loads.receive() }
            assertTrue("re-enabling must lazily load again", harness.engine.isLoaded)

            // Fast on -> off -> on -> off without any request in between.
            harness.service.setEnabled(false)
            harness.service.setEnabled(true)
            harness.service.setEnabled(false)
            assertFalse(harness.engine.isLoaded)
            assertEquals(2, harness.engine.releaseCount)

            // The service must still be usable after the cycle storm.
            harness.service.setEnabled(true)
            val third = launch { harness.service.suggestCandidates("ni", "").collect { } }
            withTimeout(TIMEOUT_MS) { harness.engine.loads.receive() }
            assertTrue(harness.engine.isLoaded)
            harness.service.setEnabled(false)
            assertEquals(3, harness.engine.releaseCount)

            first.cancelAndJoin()
            second.cancelAndJoin()
            third.cancelAndJoin()
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun requestUnloadReleasesLoadedEngineExactlyOnce() = runBlocking {
        val harness = ServiceHarness()
        try {
            val collector = launch { harness.service.suggestCandidates("ni", "").collect { } }
            withTimeout(TIMEOUT_MS) { harness.engine.loads.receive() }
            assertTrue(harness.engine.isLoaded)

            // IME onDestroy path: non-suspending fire-and-forget teardown.
            harness.service.requestUnload()
            withTimeout(TIMEOUT_MS) { harness.engine.releases.receive() }

            assertEquals(1, harness.engine.releaseCount)
            assertFalse(harness.engine.isLoaded)
            collector.cancelAndJoin()
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun closeIsIdempotentAndReleasesLoadedEngineOnce() = runBlocking {
        val harness = ServiceHarness()
        try {
            val collector = launch { harness.service.suggestCandidates("ni", "").collect { } }
            withTimeout(TIMEOUT_MS) { harness.engine.loads.receive() }
            assertTrue(harness.engine.isLoaded)

            harness.service.close()
            harness.service.close()
            withTimeout(TIMEOUT_MS) { harness.engine.releases.receive() }

            assertEquals(1, harness.engine.releaseCount)
            assertFalse(harness.engine.isLoaded)
            assertFalse(harness.engine.releasedWhileInferenceActive)
            collector.cancelAndJoin()

            // Closed service must reject requests without touching the engine.
            val events = harness.service.suggestCandidates("ni", "").toList()
            assertEquals(1, events.size)
            assertTrue(events.single() is AiEvent.Failed)
            assertEquals(1, harness.engine.rewriteCalls)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun deleteModelAndCloseConcurrentlyReleaseEngineExactlyOnce() = runBlocking {
        val harness = ServiceHarness()
        try {
            val collector = launch { harness.service.suggestCandidates("ni", "").collect { } }
            withTimeout(TIMEOUT_MS) { harness.engine.loads.receive() }
            assertTrue(harness.engine.isLoaded)

            harness.service.deleteModel()
            harness.service.close()

            withTimeout(TIMEOUT_MS) { harness.modelStore.deletedSignal.receive() }
            withTimeout(TIMEOUT_MS) { harness.engine.releases.receive() }

            assertEquals("delete + close must release once", 1, harness.engine.releaseCount)
            assertFalse(harness.engine.isLoaded)
            assertFalse(harness.engine.releasedWhileInferenceActive)
            collector.cancelAndJoin()
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun suggestCandidatesWithoutModelNeverInitializesEngine() = runBlocking {
        val harness = ServiceHarness(modelPath = null)
        try {
            val events = harness.service.suggestCandidates("nihao", "wo").toList()

            assertEquals(1, events.size)
            assertTrue(events.single() is AiEvent.Failed)
            assertEquals("no model => no engine rewrite call", 0, harness.engine.rewriteCalls)
            assertFalse(harness.engine.isLoaded)
            assertFalse(harness.service.isEngineLoaded)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun closeWhileIdleDoesNotThrowAndStaysIdempotent() = runBlocking {
        val harness = ServiceHarness()
        try {
            harness.service.close()
            harness.service.close()
            harness.service.unload()
            assertEquals(0, harness.engine.releaseCount)
            assertFalse(harness.service.isEngineLoaded)
        } finally {
            harness.shutdown()
        }
    }

    private class ServiceHarness(modelPath: String? = "/models/test-model.litertlm") {
        val engine = FakeAiEngine()
        val modelStore = FakeModelStore(modelPath)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = DefaultAiRewriteService(modelStore, engine, scope)

        fun shutdown() {
            service.close()
            scope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}

private class FakeModelStore(private val path: String?) : AiModelStore {
    private val _modelInfo = MutableStateFlow(
        if (path == null) {
            AiModelInfo()
        } else {
            AiModelInfo(installed = true, sizeBytes = 64L, path = path)
        },
    )
    override val modelInfo: StateFlow<AiModelInfo> = _modelInfo

    val deletedSignal = Channel<Unit>(Channel.UNLIMITED)
    @Volatile
    var deleted: Boolean = false
        private set

    override fun refresh(): AiModelInfo = _modelInfo.value

    override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
        Result.failure(UnsupportedOperationException("not used by unit tests"))

    override fun deleteModel() {
        deleted = true
        _modelInfo.value = AiModelInfo()
        deletedSignal.trySend(Unit)
    }

    override fun currentModelPath(): String? = if (deleted) null else path

    override fun isInstalled(): Boolean = currentModelPath() != null
}

private class FakeAiEngine : AiEngine {
    val loads = Channel<Unit>(Channel.UNLIMITED)
    val releases = Channel<Unit>(Channel.UNLIMITED)

    var rewriteCalls: Int = 0
        private set
    var cancelCalls: Int = 0
        private set
    var releaseCount: Int = 0
        private set
    var closeCount: Int = 0
        private set

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var activeFlows: Int = 0

    @Volatile
    var releasedWhileInferenceActive: Boolean = false
        private set

    override val isLoaded: Boolean get() = loaded

    override fun rewrite(
        modelPath: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        topK: Int,
        topP: Double,
        maxOutputTokens: Int,
    ): Flow<AiEvent> = flow {
        rewriteCalls++
        loaded = true
        activeFlows++
        loads.trySend(Unit)
        try {
            emit(AiEvent.Started)
            awaitCancellation()
        } finally {
            activeFlows--
        }
    }

    override fun cancel() {
        cancelCalls++
    }

    override suspend fun release() {
        if (activeFlows > 0) releasedWhileInferenceActive = true
        if (loaded) releaseCount++
        loaded = false
        releases.trySend(Unit)
    }

    private var closed: Boolean = false

    override suspend fun close() {
        closeCount++
        if (closed) return
        closed = true
        release()
    }
}
