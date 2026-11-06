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
 * [rewrite] collection. Requests are serialised with a mutex, use a fresh
 * short-lived conversation and release it as soon as the request ends.
 */
class LiteRtQwenEngine(private val cacheDir: String? = null) {

    private val initLock = Mutex()
    private val requestLock = Mutex()
    private val activeConversation = AtomicReference<Conversation?>(null)

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var generation: Long = 0L

    val isInitialized: Boolean
        get() = engine?.isInitialized() == true

    /**
     * Streams one rewrite request. Emits [AiEvent.Started], zero or more
     * [AiEvent.Delta] chunks and exactly one terminal event.
     */
    fun rewrite(modelPath: String, systemPrompt: String, userPrompt: String): Flow<AiEvent> = flow {
        val finalText = withTimeout(TIMEOUT_MS) {
            requestLock.withLock {
                check(!closed) { "Engine is closed" }
                val activeEngine = ensureEngine(modelPath)
                emit(AiEvent.Started)
                val conversation = activeEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(systemPrompt),
                        samplerConfig = SamplerConfig(
                            topK = TOP_K,
                            topP = TOP_P,
                            temperature = TEMPERATURE,
                            seed = SEED,
                        ),
                        maxOutputToken = MAX_OUTPUT_TOKENS,
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

    private suspend fun ensureEngine(modelPath: String): Engine = initLock.withLock {
        if (closed) throw IllegalStateException("Engine is closed")
        val existing = engine
        if (existing != null && existing.isInitialized()) return@withLock existing

        val expectedGeneration = generation
        runCatching { Engine.setNativeMinLogSeverity(LogSeverity.ERROR) }
        val created = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = cacheDir,
            ),
        )
        created.initialize()
        if (closed || generation != expectedGeneration) {
            runCatching { created.close() }
            throw IllegalStateException("Engine was released")
        }
        engine = created
        created
    }

    /** Stop the request that is currently generating, if any. */
    fun cancel() {
        activeConversation.getAndSet(null)?.let { conversation ->
            runCatching { conversation.cancelProcess() }
        }
    }

    /**
     * Release the native engine so a different model file can be imported,
     * keeping this wrapper reusable for the next rewrite.
     */
    fun release() {
        generation++
        activeConversation.getAndSet(null)?.let { conversation ->
            runCatching { conversation.cancelProcess() }
            runCatching { conversation.close() }
        }
        val current = engine
        engine = null
        runCatching { current?.close() }
    }

    /** Release the engine permanently. Safe to call repeatedly. */
    fun close() {
        closed = true
        release()
    }

    private companion object {
        const val TIMEOUT_MS: Long = 20_000L
        const val MAX_OUTPUT_TOKENS: Int = 128
        const val TOP_K: Int = 20
        const val TOP_P: Double = 0.9
        const val TEMPERATURE: Double = 0.2
        const val SEED: Int = 0
        const val TIMEOUT_MESSAGE: String = "本地思考超时，请重试"
        const val MEMORY_MESSAGE: String = "设备内存不足"
        const val RUNTIME_MESSAGE: String = "本地模型运行失败"
    }
}
