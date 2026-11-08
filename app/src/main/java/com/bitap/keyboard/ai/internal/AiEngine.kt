package com.bitap.keyboard.ai.internal

import com.bitap.keyboard.ai.AiEvent
import kotlinx.coroutines.flow.Flow

/**
 * Thin abstraction over the native LiteRT-LM engine.
 *
 * It exists so the request lifecycle (cancel / unload / close serialisation) can
 * be exercised on a plain JVM with a fake engine: no native library, no model
 * file and no Android runtime are needed. Implementations must tolerate
 * repeated [cancel], [release] and [close] calls.
 */
interface AiEngine {
    /** True while a native engine instance is allocated and may hold model memory. */
    val isLoaded: Boolean

    /**
     * Streams one generation request. Emits [AiEvent.Started], zero or more
     * [AiEvent.Delta] chunks and exactly one terminal event.
     */
    fun rewrite(
        modelPath: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = DEFAULT_TEMPERATURE,
        topK: Int = DEFAULT_TOP_K,
        topP: Double = DEFAULT_TOP_P,
        maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    ): Flow<AiEvent>

    /** Interrupt the in-flight generation without releasing the engine. */
    fun cancel()

    /**
     * Wait until no generation is using the native engine, then release it and
     * keep this wrapper reusable: the next [rewrite] lazily initialises a fresh
     * engine. Suspending so callers can never close a native engine under a
     * still-running inference.
     */
    suspend fun release()

    /** Release permanently. Repeated calls are no-ops. */
    suspend fun close()

    companion object {
        const val DEFAULT_TEMPERATURE: Double = 0.2
        const val DEFAULT_TOP_K: Int = 20
        const val DEFAULT_TOP_P: Double = 0.9
        const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 128
    }
}
