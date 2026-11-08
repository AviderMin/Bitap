package com.bitap.keyboard.ai.internal

import android.content.Context
import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local AI service used by the IME and the settings screens.
 *
 * Construction only scans the model directory; the LiteRT-LM engine is created
 * on the first request and released again by [unload]/[setEnabled] false/close.
 * No prompt or result is ever written to disk or to a log.
 *
 * D02 lifecycle contract:
 *  - Every request registration, teardown and close goes through one
 *    [lifecycleMutex]. Teardown first detaches and cancels the active job, then
 *    waits for it to leave native inference OUTSIDE the lock (so a 20 s
 *    generation cannot block a UI toggle) and only then releases the engine.
 *  - The release is guarded by an epoch token: if a new request registered while
 *    the old one was being joined (the user toggled AI back on), the engine is
 *    kept for that request instead of being pulled from under it. A later
 *    teardown releases it.
 *  - [engine.release] is therefore never called while a generation is running.
 *  - Repeated [close] is a no-op; [requestUnload] schedules the same serialised
 *    teardown on the service-owned scope so an IME onDestroy cannot orphan it.
 */
class DefaultAiRewriteService internal constructor(
    private val modelStore: AiModelStore,
    private val engine: AiEngine,
    private val scope: CoroutineScope,
    private val dictionaryLoader: suspend () -> AiPinyinDictionary = { AiPinyinDictionary.EMPTY },
) : AiRewriteService {

    constructor(context: Context) : this(
        modelStore = ModelStore(context.applicationContext),
        engine = LiteRtQwenEngine(cacheDir = context.applicationContext.cacheDir.absolutePath),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        dictionaryLoader = {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.applicationContext.assets.open(PINYIN_DICT_ASSET)
                        .bufferedReader(Charsets.UTF_8)
                        .useLines { lines -> AiPinyinDictionary.parse(lines) }
                }.getOrElse { AiPinyinDictionary.EMPTY }
            }
        },
    )

    private val lifecycleMutex = Mutex()
    private val stateLock = Any()
    private val closed = AtomicBoolean(false)

    /** Guarded by [stateLock]. */
    private var activeJob: Job? = null

    /** Guarded by [stateLock]; bumped whenever a request starts or a teardown begins. */
    private var epoch = 0L

    /** Guarded by [lifecycleMutex]. */
    private var enabled = true

    @Volatile
    private var pinyinDictionary: AiPinyinDictionary? = null

    override val modelInfo: StateFlow<AiModelInfo> = modelStore.modelInfo

    private val _runtimeState = MutableStateFlow(
        if (modelStore.isInstalled()) AiRuntimeState.Ready else AiRuntimeState.NotInstalled,
    )
    override val runtimeState: StateFlow<AiRuntimeState> = _runtimeState.asStateFlow()

    override val isEngineLoaded: Boolean
        get() = engine.isLoaded

    override fun refresh() {
        val info = modelStore.refresh()
        _runtimeState.value = if (info.installed) AiRuntimeState.Ready else AiRuntimeState.NotInstalled
    }

    override suspend fun importModel(uri: Uri): Result<AiModelInfo> {
        if (closed.get()) return Result.failure(IllegalStateException("AI 服务已关闭"))
        // A new model file must never replace the file a live engine has mapped.
        cancelAndRelease()
        _runtimeState.value = AiRuntimeState.Loading
        val result = modelStore.importModel(uri)
        val info = modelStore.modelInfo.value
        _runtimeState.value = when {
            !info.installed -> if (result.isSuccess) AiRuntimeState.Error else AiRuntimeState.NotInstalled
            else -> AiRuntimeState.Ready
        }
        return result
    }

    override fun deleteModel() {
        if (closed.get()) return
        // Deletion and engine release are serialised on the service scope. The
        // file is only removed after the active generation left native inference.
        scope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { cancelAndRelease() }
                modelStore.deleteModel()
                _runtimeState.value = AiRuntimeState.NotInstalled
            }
        }
    }

    override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = flow {
        if (closed.get()) {
            emit(AiEvent.Failed("AI 服务已关闭"))
            return@flow
        }
        val input = AiPromptBuilder.normalizeInput(text)
        if (input.isEmpty()) {
            emit(AiEvent.Failed("没有可改写的文本"))
            return@flow
        }
        val modelPath = modelStore.currentModelPath()
        if (modelPath == null) {
            _runtimeState.value = AiRuntimeState.NotInstalled
            emit(AiEvent.Failed("未安装本地模型"))
            return@flow
        }

        val events = Channel<AiEvent>(Channel.BUFFERED)
        val request = scope.launch(start = CoroutineStart.LAZY) {
            runRewrite(modelPath, input, tone, events)
        }
        if (!registerRequest(request)) {
            request.cancel()
            events.close()
            emit(AiEvent.Failed("AI 已关闭"))
            return@flow
        }
        try {
            for (event in events) emit(event)
        } finally {
            request.cancel()
            events.close()
            unregisterRequest(request)
        }
    }.flowOn(Dispatchers.Default)

    override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> = flow {
        if (closed.get()) {
            emit(AiEvent.Failed("AI 服务已关闭"))
            return@flow
        }
        val query = pinyin.trim().take(48)
        if (query.isEmpty()) {
            emit(AiEvent.Failed("没有可用的拼音"))
            return@flow
        }
        // Critical D02 invariant: without a model no native engine is created.
        val modelPath = modelStore.currentModelPath()
        if (modelPath == null) {
            _runtimeState.value = AiRuntimeState.NotInstalled
            emit(AiEvent.Failed("未安装本地模型"))
            return@flow
        }

        val events = Channel<AiEvent>(Channel.BUFFERED)
        val request = scope.launch(start = CoroutineStart.LAZY) {
            runLexicon(modelPath, query, context.trim().take(160), events)
        }
        if (!registerRequest(request)) {
            request.cancel()
            events.close()
            emit(AiEvent.Failed("AI 已关闭"))
            return@flow
        }
        try {
            for (event in events) emit(event)
        } finally {
            request.cancel()
            events.close()
            unregisterRequest(request)
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun setEnabled(enabled: Boolean) {
        if (closed.get()) return
        if (enabled) {
            lifecycleMutex.withLock {
                if (!closed.get()) {
                    this.enabled = true
                    _runtimeState.value =
                        if (modelStore.isInstalled()) AiRuntimeState.Ready else AiRuntimeState.NotInstalled
                }
            }
        } else {
            lifecycleMutex.withLock { this.enabled = false }
            cancelAndRelease()
        }
    }

    override suspend fun unload() {
        if (closed.get()) return
        cancelAndRelease()
    }

    override fun requestUnload() {
        if (closed.get()) return
        scope.launch { runCatching { cancelAndRelease() } }
    }

    override fun cancel() {
        val job = synchronized(stateLock) { activeJob }
        job?.cancel()
        engine.cancel()
        if (_runtimeState.value == AiRuntimeState.Busy || _runtimeState.value == AiRuntimeState.Loading) {
            _runtimeState.value = if (modelStore.isInstalled()) AiRuntimeState.Ready else AiRuntimeState.NotInstalled
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // The IME/settings may drop the last reference right after this call;
        // the teardown therefore runs on the service scope and only then
        // cancels it.
        scope.launch {
            try {
                cancelAndRelease()
            } finally {
                runCatching {
                    withContext(NonCancellable + Dispatchers.IO) { engine.close() }
                }
                scope.cancel()
            }
        }
    }

    /**
     * Register [request] as the single active request and start it. Returns
     * false when the service is closed or AI was disabled, in which case the
     * caller must not start native work.
     */
    private suspend fun registerRequest(request: Job): Boolean {
        var previous: Job? = null
        val accepted = lifecycleMutex.withLock {
            if (closed.get() || !enabled) {
                false
            } else {
                synchronized(stateLock) {
                    epoch++
                    previous = activeJob
                    activeJob = request
                }
                true
            }
        }
        if (!accepted) return false
        previous?.cancel()
        engine.cancel()
        request.start()
        return true
    }

    private fun unregisterRequest(request: Job) {
        synchronized(stateLock) {
            if (activeJob === request) activeJob = null
        }
    }

    /**
     * Serialised "stop and release" path: detach + cancel, wait outside the
     * lock, then release only if no newer request needs the engine.
     */
    private suspend fun cancelAndRelease() = withContext(NonCancellable + Dispatchers.IO) {
        var job: Job? = null
        var token = 0L
        lifecycleMutex.withLock {
            synchronized(stateLock) {
                epoch++
                token = epoch
                job = activeJob
                activeJob = null
            }
        }
        job?.cancel()
        engine.cancel()
        job?.join()
        lifecycleMutex.withLock {
            val releaseNow = synchronized(stateLock) { epoch == token }
            if (releaseNow && engine.isLoaded) {
                engine.release()
                if (_runtimeState.value == AiRuntimeState.Busy || _runtimeState.value == AiRuntimeState.Loading) {
                    _runtimeState.value =
                        if (modelStore.isInstalled()) AiRuntimeState.Ready else AiRuntimeState.NotInstalled
                }
            }
        }
    }

    private suspend fun runRewrite(
        modelPath: String,
        input: String,
        tone: RewriteTone,
        output: SendChannel<AiEvent>,
    ) {
        try {
            if (!engine.isLoaded) _runtimeState.value = AiRuntimeState.Loading
            val prompt = AiPromptBuilder.build(input, tone)
            engine.rewrite(modelPath, prompt.system, prompt.user).collect { event ->
                when (event) {
                    is AiEvent.Started -> {
                        _runtimeState.value = AiRuntimeState.Busy
                        output.send(event)
                    }
                    is AiEvent.Delta -> output.send(event)
                    is AiEvent.Completed -> {
                        when (val sanitized = AiResultSanitizer.sanitize(event.text, input)) {
                            is AiResultSanitizer.Result.Success -> {
                                _runtimeState.value = AiRuntimeState.Ready
                                output.send(AiEvent.Completed(sanitized.text))
                            }
                            is AiResultSanitizer.Result.Failure -> {
                                _runtimeState.value = AiRuntimeState.Error
                                output.send(AiEvent.Failed(failureMessage(sanitized.reason)))
                            }
                        }
                    }
                    is AiEvent.Failed -> {
                        _runtimeState.value = AiRuntimeState.Error
                        output.send(event)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            _runtimeState.value = if (modelStore.isInstalled()) AiRuntimeState.Ready else AiRuntimeState.NotInstalled
            throw cancelled
        } catch (outOfMemory: OutOfMemoryError) {
            runCatching { engine.release() }
            _runtimeState.value = AiRuntimeState.Error
            output.send(AiEvent.Failed("设备内存不足"))
        } catch (error: Throwable) {
            _runtimeState.value = AiRuntimeState.Error
            output.send(AiEvent.Failed("本地模型运行失败"))
        } finally {
            output.close()
        }
    }

    private suspend fun runLexicon(
        modelPath: String,
        pinyin: String,
        context: String,
        output: SendChannel<AiEvent>,
    ) {
        try {
            if (!engine.isLoaded) _runtimeState.value = AiRuntimeState.Loading
            val dictionary = lexiconDictionary()
            val prompt = AiLexiconPromptBuilder.build(pinyin, context)
            engine.rewrite(
                modelPath = modelPath,
                systemPrompt = prompt.system,
                userPrompt = prompt.user,
                temperature = AiLexiconPromptBuilder.TEMPERATURE,
                topK = AiLexiconPromptBuilder.TOP_K,
                topP = AiLexiconPromptBuilder.TOP_P,
                maxOutputTokens = AiLexiconPromptBuilder.MAX_OUTPUT_TOKENS,
            ).collect { event ->
                when (event) {
                    is AiEvent.Started -> {
                        _runtimeState.value = AiRuntimeState.Busy
                        output.send(event)
                    }
                    is AiEvent.Delta -> output.send(event)
                    is AiEvent.Completed -> {
                        _runtimeState.value = AiRuntimeState.Ready
                        when (val outcome = AiLexiconQualityGate.evaluate(event.text, pinyin, dictionary)) {
                            is AiLexiconOutcome.Candidates -> {
                                output.send(AiEvent.Completed(AiCandidateParser.format(outcome.words)))
                            }
                            is AiLexiconOutcome.Fallback -> {
                                output.send(AiEvent.Failed(outcome.message))
                            }
                        }
                    }
                    is AiEvent.Failed -> {
                        _runtimeState.value = AiRuntimeState.Error
                        output.send(event)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            _runtimeState.value = if (modelStore.isInstalled()) AiRuntimeState.Ready else AiRuntimeState.NotInstalled
            throw cancelled
        } catch (outOfMemory: OutOfMemoryError) {
            runCatching { engine.release() }
            _runtimeState.value = AiRuntimeState.Error
            output.send(AiEvent.Failed("设备内存不足"))
        } catch (error: Throwable) {
            _runtimeState.value = AiRuntimeState.Error
            output.send(AiEvent.Failed("本地词库运行失败"))
        } finally {
            output.close()
        }
    }

    private suspend fun lexiconDictionary(): AiPinyinDictionary {
        pinyinDictionary?.let { return it }
        val loaded = dictionaryLoader()
        pinyinDictionary = loaded
        return loaded
    }

    private fun failureMessage(reason: AiResultSanitizer.FailureReason): String = when (reason) {
        AiResultSanitizer.FailureReason.EMPTY -> "这次没想好，请重试"
        AiResultSanitizer.FailureReason.TOO_LONG -> "改写结果过长，请重试"
    }

    private companion object {
        const val PINYIN_DICT_ASSET = "pinyin_dict.tsv"
    }
}
