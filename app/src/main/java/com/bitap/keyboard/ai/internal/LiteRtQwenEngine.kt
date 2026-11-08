package com.bitap.keyboard.ai.internal

import com.bitap.keyboard.ai.AiEvent
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Lazily initialised wrapper around a single LiteRT-LM engine.
 *
 * The constructor is cheap: the native engine is only created on the first
 * [rewrite] collection. Requests are serialised with [requestLock], use a fresh
 * short-lived conversation and release it as soon as the request ends.
 *
 * Concurrency invariants (D02):
 *  - [ensureEngine] creates the native engine outside [engineLock] and only
 *    publishes it if [generation] did not change meanwhile. A concurrent
 *    [release]/[close] therefore can never close an engine while it is being
 *    initialised: the creator observes the bumped generation and closes its own
 *    temporary engine instead.
 *  - [release] detaches the engine under [engineLock] and closes it outside the
 *    lock. Callers must not release while a request is producing tokens; the
 *    service layer cancels and joins the request first.
 */
class LiteRtQwenEngine(private val cacheDir: String? = null) : AiEngine {

    private val requestLock = Mutex()
    private val engineLock = Any()
    private val activeConversation = AtomicReference<Conversation?>(null)
    private val closeRequested = AtomicBoolean(false)

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var closed: Boolean = false

    /** Guarded by [engineLock]. */
    private var generation: Long = 0L

    override val isLoaded: Boolean
        get() = synchronized(engineLock) { engine != null }

    /** Pre-D02 name kept as a read-only alias for diagnostics. */
    val isInitialized: Boolean
        get() = isLoaded

    /**
     * Streams one rewrite request. Emits [AiEvent.Started], zero or more
     * [AiEvent.Delta] chunks and exactly one terminal event.
     */
    override fun rewrite(
        modelPath: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        topK: Int,
        topP: Double,
        maxOutputTokens: Int,
    ): Flow<AiEvent> = flow {
        val finalText = withTimeout(TIMEOUT_MS) {
            requestLock.withLock {
                check(!closed) { "Engine is closed" }
                val activeEngine = ensureEngine(modelPath)
                emit(AiEvent.Started)
                val conversation = activeEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(systemPrompt),
                        samplerConfig = SamplerConfig(
                            topK = topK,
                            topP = topP,
                            temperature = temperature,
                            seed = SEED,
                        ),
                        maxOutputToken = maxOutputTokens,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                    ),
                )
                activeConversation.set(conversation)
                try {
                    val collected = StringBuilder()
                    conversation.sendMessageAsync(userPrompt).collect { message ->
                        val delta = message.toString()
                        if (delta.isNotEmpty()) {
                            collected.append(delta)
                            emit(AiEvent.Delta(delta))
                        }
                    }
                    collected.toString()
                } finally {
                    activeConversation.compareAndSet(conversation, null)
                    runCatching { conversation.cancelProcess() }
                    runCatching { conversation.close() }
                }
            }
        }
        emit(AiEvent.Completed(finalText))
    }.catch { error ->
        when (error) {
            is TimeoutCancellationException -> {
                cancel()
                emit(AiEvent.Failed(TIMEOUT_MESSAGE))
            }
            is OutOfMemoryError -> {
                release()
                emit(AiEvent.Failed(MEMORY_MESSAGE))
            }
            is CancellationException -> throw error
            else -> emit(AiEvent.Failed(RUNTIME_MESSAGE))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun ensureEngine(modelPath: String): Engine {
        synchronized(engineLock) {
            if (closed) throw IllegalStateException("Engine is closed")
            engine?.takeIf { it.isInitialized() }?.let { return it }
        }

        val expectedGeneration = synchronized(engineLock) { generation }
        runCatching { Engine.setNativeMinLogSeverity(LogSeverity.ERROR) }
        val created = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = cacheDir,
            ),
        )
        created.initialize()
        synchronized(engineLock) {
            if (closed || generation != expectedGeneration) {
                // release()/close() ran while the native engine was initialising.
                // Never publish (or keep) an engine that the release path has
                // already declared dead.
                runCatching { created.close() }
                throw IllegalStateException("Engine was released")
            }
            // Requests are serialised by requestLock, so no other creator can win.
            engine = created
        }
        return created
    }

    /** Stop the request that is currently generating, if any. */
    override fun cancel() {
        activeConversation.getAndSet(null)?.let { conversation ->
            runCatching { conversation.cancelProcess() }
        }
    }

    /**
     * Release the native engine so a different model file can be imported,
     * keeping this wrapper reusable for the next rewrite.
     *
     * Acquiring [requestLock] is the final safety net: even if the service-level
     * bookkeeping ever missed a request, native memory is never released while
     * [rewrite] is still inside the engine.
     */
    override suspend fun release() {
        requestLock.withLock {
            activeConversation.getAndSet(null)?.let { conversation ->
                runCatching { conversation.cancelProcess() }
                runCatching { conversation.close() }
            }
            val detached = synchronized(engineLock) {
                generation++
                val current = engine
                engine = null
                current
            }
            runCatching { detached?.close() }
        }
    }

    /** Release the engine permanently. Safe to call repeatedly. */
    override suspend fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        closed = true
        release()
    }

    private companion object {
        const val TIMEOUT_MS: Long = 20_000L
        const val SEED: Int = 0
        const val TIMEOUT_MESSAGE: String = "本地思考超时，请重试"
        const val MEMORY_MESSAGE: String = "设备内存不足"
        const val RUNTIME_MESSAGE: String = "本地模型运行失败"
    }
}
