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
 */
interface AiRewriteService {
    val runtimeState: StateFlow<AiRuntimeState>
    val modelInfo: StateFlow<AiModelInfo>

    /** Re-check the model file and publish a fresh [modelInfo]. */
    fun refresh()

    /** Import an externally supplied .litertlm file into private storage. */
    suspend fun importModel(uri: Uri): Result<AiModelInfo>

    fun deleteModel()

    /** One request at a time; calling again cancels the previous request. */
    fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent>

    fun cancel()

    fun close()
}

/** Created by [com.bitap.keyboard.BitapApplication]; never loads the engine in its constructor. */
class LocalAiRewriteService(context: Context) : AiRewriteService {
    private val delegate = com.bitap.keyboard.ai.internal.DefaultAiRewriteService(context.applicationContext)
    override val runtimeState: StateFlow<AiRuntimeState> get() = delegate.runtimeState
    override val modelInfo: StateFlow<AiModelInfo> get() = delegate.modelInfo
    override fun refresh() = delegate.refresh()
    override suspend fun importModel(uri: Uri): Result<AiModelInfo> = delegate.importModel(uri)
    override fun deleteModel() = delegate.deleteModel()
    override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = delegate.rewrite(text, tone)
    override fun cancel() = delegate.cancel()
    override fun close() = delegate.close()
}
