package com.bitap.keyboard.ai

import android.net.Uri
import com.bitap.keyboard.ai.internal.AiEngine
import com.bitap.keyboard.ai.internal.AiModelStore
import com.bitap.keyboard.ai.internal.DefaultAiRewriteService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model-maintenance tests (D02 follow-up): import/delete must be serialised with
 * request registration so no generation can start against a model file that is
 * being replaced or deleted.
 *
 * Everything runs on a plain JVM: [MaintenanceFakeEngine] replaces LiteRT-LM and
 * [MaintenanceFakeModelStore] replaces Android file storage. The fake store can
 * block inside import/delete so the test can prove registration waits for the
 * maintenance transaction instead of racing it.
 */
class AiModelMaintenanceTest {

    @Test
    fun importBlocksRequestRegistrationAndRejectsStaleRequest() = runBlocking {
        val harness = MaintenanceHarness(blockImport = true)
        try {
            val importJob = async(Dispatchers.Default) {
                harness.service.importModel(unusedUri())
            }
            withTimeout(TIMEOUT_MS) { harness.store.importStarted.receive() }
            assertEquals(AiRuntimeState.Loading, harness.service.runtimeState.value)

            var events: List<AiEvent> = emptyList()
            val collector = launch {
                events = harness.service.rewrite("你好世界", RewriteTone.Polish).toList()
            }
            // The request has captured the pre-import model snapshot; its
            // registration must now wait on the maintenance lock.
            withTimeout(TIMEOUT_MS) { harness.store.modelPathReads.receive() }
            delay(SETTLE_MS)
            assertEquals(
                "request must not enter the native engine while the model is being imported",
                0,
                harness.engine.rewriteCalls,
            )
            assertTrue("request must wait for the import transaction", collector.isActive)

            harness.store.releaseImport()
            withTimeout(TIMEOUT_MS) { harness.store.importedSignal.receive() }
            val imported = importJob.await()
            assertTrue(imported.isSuccess)
            withTimeout(TIMEOUT_MS) { collector.join() }

            assertEquals("stale request must be rejected without native work", 0, harness.engine.rewriteCalls)
            assertEquals(1, events.size)
            assertTrue(events.single() is AiEvent.Failed)
            assertFalse(harness.service.isEngineLoaded)
            assertEquals(AiRuntimeState.Ready, harness.service.runtimeState.value)
        } finally {
            harness.store.releaseImport()
            harness.shutdown()
        }
    }

    @Test
    fun deleteBlocksRequestRegistrationAndRejectsRequestAgainstDeletingFile() = runBlocking {
        val harness = MaintenanceHarness(blockDelete = true)
        try {
            harness.service.deleteModel()
            withTimeout(TIMEOUT_MS) { harness.store.deleteStarted.receive() }

            var events: List<AiEvent> = emptyList()
            val collector = launch {
                events = harness.service.suggestCandidates("nihao", "wo").toList()
            }
            withTimeout(TIMEOUT_MS) { harness.store.modelPathReads.receive() }
            delay(SETTLE_MS)
            assertEquals(
                "request must not start against the model file that is being deleted",
                0,
                harness.engine.rewriteCalls,
            )
            assertTrue("request must wait for the delete transaction", collector.isActive)

            harness.store.releaseDelete()
            withTimeout(TIMEOUT_MS) { harness.store.deletedSignal.receive() }
            withTimeout(TIMEOUT_MS) { collector.join() }

            assertEquals("deleted model must not reach the native engine", 0, harness.engine.rewriteCalls)
            assertEquals(1, events.size)
            assertTrue(events.single() is AiEvent.Failed)
            assertFalse(harness.service.isEngineLoaded)
            assertEquals(AiRuntimeState.NotInstalled, harness.service.runtimeState.value)
        } finally {
            harness.store.releaseDelete()
            harness.shutdown()
        }
    }

    @Test
    fun normalRequestAfterMaintenanceCompletesStillRuns() = runBlocking {
        val harness = MaintenanceHarness()
        try {
            val imported = harness.service.importModel(unusedUri())
            assertTrue(imported.isSuccess)
            assertNotNull(harness.service.modelInfo.value.path)
            assertEquals(AiRuntimeState.Ready, harness.service.runtimeState.value)

            val collector = launch {
                harness.service.suggestCandidates("nihao", "wo").collect { }
            }
            withTimeout(TIMEOUT_MS) { harness.engine.loads.receive() }

            assertEquals(
                "a request after a completed maintenance transaction must enter the engine",
                1,
                harness.engine.rewriteCalls,
            )
            assertTrue(harness.service.isEngineLoaded)
            collector.cancelAndJoin()
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun cancelledImportRestoresReadyStateAndKeepsServiceUsable() = runBlocking {
        val harness = MaintenanceHarness(blockImport = true)
        try {
            val importJob = async(Dispatchers.Default) {
                harness.service.importModel(unusedUri())
            }
            withTimeout(TIMEOUT_MS) { harness.store.importStarted.receive() }
            assertEquals(AiRuntimeState.Loading, harness.service.runtimeState.value)

            importJob.cancelAndJoin()

            assertEquals(
                "cancelled import must publish the still-installed model, not stay Loading",
                AiRuntimeState.Ready,
                harness.service.runtimeState.value,
            )

            // The service must remain usable after the cancelled maintenance.
            val collector = launch {
                harness.service.suggestCandidates("nihao", "wo").collect { }
            }
            withTimeout(TIMEOUT_MS) { harness.engine.loads.receive() }
            assertEquals(1, harness.engine.rewriteCalls)
            collector.cancelAndJoin()
        } finally {
            harness.store.releaseImport()
            harness.shutdown()
        }
    }

    @Test
    fun cancelledImportAfterStoreReplacementPublishesReplacementModel() = runBlocking {
        val harness = MaintenanceHarness(blockImport = true, installBeforeBlock = true)
        try {
            val importJob = async(Dispatchers.Default) {
                harness.service.importModel(unusedUri())
            }
            withTimeout(TIMEOUT_MS) { harness.store.importStarted.receive() }
            assertEquals(AiRuntimeState.Loading, harness.service.runtimeState.value)

            // The fake has already atomically replaced the file and published
            // the new modelInfo before it blocks; cancellation lands right after.
            assertEquals(REPLACED_MODEL_PATH, harness.store.modelInfo.value.path)
            importJob.cancelAndJoin()

            assertEquals(
                "cancellation after the atomic move must publish Ready, not NotInstalled",
                AiRuntimeState.Ready,
                harness.service.runtimeState.value,
            )
            assertEquals(REPLACED_MODEL_PATH, harness.service.modelInfo.value.path)
        } finally {
            harness.store.releaseImport()
            harness.shutdown()
        }
    }

    @Test
    fun cancelledImportNeverLeavesRuntimeStateLoading() = runBlocking {
        val harness = MaintenanceHarness(blockImport = true)
        try {
            val importJob = async(Dispatchers.Default) {
                harness.service.importModel(unusedUri())
            }
            withTimeout(TIMEOUT_MS) { harness.store.importStarted.receive() }
            assertEquals(AiRuntimeState.Loading, harness.service.runtimeState.value)

            importJob.cancelAndJoin()

            assertTrue(
                "runtimeState must be terminal after cancellation but was " + harness.service.runtimeState.value,
                harness.service.runtimeState.value != AiRuntimeState.Loading,
            )
        } finally {
            harness.store.releaseImport()
            harness.shutdown()
        }
    }

    private class MaintenanceHarness(
        initialPath: String? = DEFAULT_MODEL_PATH,
        blockImport: Boolean = false,
        blockDelete: Boolean = false,
        installBeforeBlock: Boolean = false,
    ) {
        val engine = MaintenanceFakeEngine()
        val store = MaintenanceFakeModelStore(initialPath, blockImport, blockDelete, installBeforeBlock)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = DefaultAiRewriteService(store, engine, scope)

        init {
            // The service constructor probes the model path once; drop that
            // initial signal so tests observe only their own request's read.
            while (store.modelPathReads.tryReceive().isSuccess) {
                // drain
            }
        }

        fun shutdown() {
            service.close()
            scope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 150L
        const val DEFAULT_MODEL_PATH = "/models/test-model.litertlm"
    }
}

/**
 * Plain JVM tests run against the mockable android.jar, which has no real
 * [Uri] implementation (every factory throws and the static fields stay null).
 * [DefaultAiRewriteService] only forwards the handle to [AiModelStore], and the
 * fake below never dereferences it, so a null handle is enough here.
 */
@Suppress("UNCHECKED_CAST")
private fun <T> unusedUri(): T = null as T

private const val REPLACED_MODEL_PATH = "/models/replaced-model.litertlm"

private class MaintenanceFakeModelStore(
    initialPath: String?,
    private val blockImport: Boolean,
    private val blockDelete: Boolean,
    private val installBeforeBlock: Boolean = false,
) : AiModelStore {
    private val _modelInfo = MutableStateFlow(
        if (initialPath == null) {
            AiModelInfo()
        } else {
            AiModelInfo(installed = true, sizeBytes = 64L, path = initialPath)
        },
    )
    override val modelInfo: StateFlow<AiModelInfo> = _modelInfo

    @Volatile
    private var path: String? = initialPath

    /** Emitted when the service enters the fake import. */
    val importStarted = Channel<Unit>(Channel.UNLIMITED)

    /** Emitted after the fake import published its new model. */
    val importedSignal = Channel<Unit>(Channel.UNLIMITED)

    /** Emitted when the service enters the fake delete. */
    val deleteStarted = Channel<Unit>(Channel.UNLIMITED)

    /** Emitted after the fake delete removed the model. */
    val deletedSignal = Channel<Unit>(Channel.UNLIMITED)

    /** Emitted whenever a request resolves [currentModelPath]. */
    val modelPathReads = Channel<Unit>(Channel.UNLIMITED)

    private val importGate = Channel<Unit>(Channel.UNLIMITED)
    private val deleteGate = CountDownLatch(if (blockDelete) 1 else 0)

    fun releaseImport() {
        importGate.trySend(Unit)
    }

    fun releaseDelete() {
        deleteGate.countDown()
    }

    override fun refresh(): AiModelInfo = _modelInfo.value

    override suspend fun importModel(uri: Uri): Result<AiModelInfo> {
        importStarted.trySend(Unit)
        if (installBeforeBlock) {
            installReplacementModel()
        }
        if (blockImport) {
            importGate.receive()
        }
        if (!installBeforeBlock) {
            installReplacementModel()
        }
        importedSignal.trySend(Unit)
        return Result.success(_modelInfo.value)
    }

    private fun installReplacementModel() {
        path = REPLACED_MODEL_PATH
        _modelInfo.value = AiModelInfo(installed = true, sizeBytes = 64L, path = REPLACED_MODEL_PATH)
    }

    override fun deleteModel() {
        deleteStarted.trySend(Unit)
        // deleteModel() is not suspending, so block the maintenance coroutine's
        // IO thread on a latch instead of a channel.
        deleteGate.await(5, TimeUnit.SECONDS)
        path = null
        _modelInfo.value = AiModelInfo()
        deletedSignal.trySend(Unit)
    }

    override fun currentModelPath(): String? {
        modelPathReads.trySend(Unit)
        return path
    }

    override fun isInstalled(): Boolean = path != null
}

private class MaintenanceFakeEngine : AiEngine {
    val loads = Channel<Unit>(Channel.UNLIMITED)

    @Volatile
    var rewriteCalls: Int = 0
        private set

    @Volatile
    private var loaded: Boolean = false

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
        loads.trySend(Unit)
        emit(AiEvent.Started)
        awaitCancellation()
    }

    override fun cancel() = Unit

    override suspend fun release() {
        loaded = false
    }

    override suspend fun close() {
        release()
    }
}
