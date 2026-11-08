package com.bitap.keyboard.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class AiRuntimeState {
    NotInstalled,
    Loading,
    Ready,
    Busy,
    Error,
}

data class AiModelInfo(
    val displayName: String = "Qwen3-0.6B",
    val installed: Boolean = false,
    val sizeBytes: Long = 0L,
    val path: String? = null,
    val error: String? = null,
)

enum class RewriteTone(val label: String) {
    Polish("润色"),
    Concise("精简"),
    Formal("正式"),
    English("译为英文"),
}

sealed interface AiEvent {
    data object Started : AiEvent
    data class Delta(val text: String) : AiEvent
    data class Completed(val text: String) : AiEvent
    data class Failed(val message: String) : AiEvent
}

/**
 * Contract between the IME/UI and the local AI workstream.
 * Implementations must never persist text or perform network I/O.
 *
 * Lifecycle semantics (D02):
 *  - [cancel] stops the current generation only. The native engine stays loaded.
 *  - [setEnabled] with `false` / [unload] is the measured "AI off" path: cancel
 *    the active request, wait until it left native inference, then release the
 *    engine so model memory can come back down.
 *  - [close] is the permanent teardown; it is idempotent and serialised with
 *    every other lifecycle operation.
 *  - Re-enabling AI never warms the engine eagerly; the next request lazily
 *    loads it again.
 *  - With no model installed no request path may initialise the native engine.
 */
interface AiRewriteService {
    val runtimeState: StateFlow<AiRuntimeState>
    val modelInfo: StateFlow<AiModelInfo>

    /**
     * Diagnostic: true while a native inference engine is allocated and holds
     * model memory. Used by JVM tests and on-device verification to prove that
     * turning AI off actually unloads the runtime.
     */
    val isEngineLoaded: Boolean get() = false

    /** Re-check the model file and publish a fresh [modelInfo]. */
    fun refresh()

    /** Import an externally supplied .litertlm file into private storage. */
    suspend fun importModel(uri: Uri): Result<AiModelInfo>

    fun deleteModel()

    /** One request at a time; calling again cancels the previous request. */
    fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent>

    /** AI used as a local Chinese lexicon: pinyin + context -> candidate words. */
    fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent>

    /** Cancel the current generation. This does NOT release the native engine. */
    fun cancel()

    /**
     * Enable or disable the AI runtime. Disabling must cancel the active
     * generation, wait for it to leave native inference and release the native
     * engine. Enabling only marks the runtime usable; loading stays lazy.
     *
     * The default implementation is deliberately conservative so lightweight
     * test doubles only need to override what they exercise: disabling falls
     * back to [unload], which falls back to [cancel].
     */
    suspend fun setEnabled(enabled: Boolean) {
        if (!enabled) unload()
    }

    /**
     * Cancel the active request, wait for it to exit native inference, then
     * release the engine while keeping this service reusable. Serialised with
     * request starts and idempotent.
     */
    suspend fun unload() {
        cancel()
    }

    /**
     * Non-suspending variant of [unload] for lifecycle callbacks whose own
     * coroutine scope may be cancelled immediately (IME onDestroy). The
     * implementation schedules the same serialised teardown on a service-owned
     * scope, so it survives the caller going away.
     */
    fun requestUnload() {
        cancel()
    }

    fun close()
}

/** Created by [com.bitap.keyboard.BitapApplication]; never loads the engine in its constructor. */
class LocalAiRewriteService(context: Context) : AiRewriteService {
    private val delegate = com.bitap.keyboard.ai.internal.DefaultAiRewriteService(context.applicationContext)
    override val runtimeState: StateFlow<AiRuntimeState> get() = delegate.runtimeState
    override val modelInfo: StateFlow<AiModelInfo> get() = delegate.modelInfo
    override val isEngineLoaded: Boolean get() = delegate.isEngineLoaded
    override fun refresh() = delegate.refresh()
    override suspend fun importModel(uri: Uri): Result<AiModelInfo> = delegate.importModel(uri)
    override fun deleteModel() = delegate.deleteModel()
    override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = delegate.rewrite(text, tone)
    override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> =
        delegate.suggestCandidates(pinyin, context)
    override fun cancel() = delegate.cancel()
    override suspend fun setEnabled(enabled: Boolean) = delegate.setEnabled(enabled)
    override suspend fun unload() = delegate.unload()
    override fun requestUnload() = delegate.requestUnload()
    override fun close() = delegate.close()
}
