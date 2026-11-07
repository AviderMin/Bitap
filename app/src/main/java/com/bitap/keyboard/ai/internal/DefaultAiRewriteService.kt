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

/**
 * Local AI rewrite service used by the IME and the settings screens.
 *
 * Construction only scans the model directory. The LiteRT-LM engine is loaded
 * on the first rewrite, one request runs at a time and every conversation is
 * short-lived. No prompt or result is ever written to disk or to a log.
 */
class DefaultAiRewriteService(private val context: Context) : AiRewriteService {

    private val modelStore = ModelStore(context)
    private val engine = LiteRtQwenEngine(cacheDir = context.cacheDir.absolutePath)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val requestLock = Any()

    private var activeJob: Job? = null

    override val modelInfo: StateFlow<AiModelInfo> = modelStore.modelInfo

    private val _runtimeState = MutableStateFlow(
        if (modelStore.isInstalled()) AiRuntimeState.Ready else AiRuntimeState.NotInstalled,
    )
    override val runtimeState: StateFlow<AiRuntimeState> = _runtimeState.asStateFlow()

    override fun refresh() {
        val info = modelStore.refresh()
        _runtimeState.value = if (info.installed) AiRuntimeState.Ready else AiRuntimeState.NotInstalled
    }

    override suspend fun importModel(uri: Uri): Result<AiModelInfo> {
        if (closed.get()) return Result.failure(IllegalStateException("AI 服务已关闭"))
        cancelActiveRequest()
        if (engine.isInitialized) engine.release()
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
        cancelActiveRequest()
        engine.release()
        modelStore.deleteModel()
        _runtimeState.value = AiRuntimeState.NotInstalled
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
        val previous = synchronized(requestLock) {
            val old = activeJob
            activeJob = request
            old
        }
        previous?.cancel()
        engine.cancel()
        request.start()

        try {
            for (event in events) emit(event)
        } finally {
            request.cancel()
            events.close()
            synchronized(requestLock) {
                if (activeJob === request) activeJob = null
            }
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
        val previous = synchronized(requestLock) {
            val old = activeJob
            activeJob = request
            old
        }
        previous?.cancel()
        engine.cancel()
        request.start()

        try {
            for (event in events) emit(event)
        } finally {
            request.cancel()
            events.close()
            synchronized(requestLock) {
                if (activeJob === request) activeJob = null
            }
        }
    }.flowOn(Dispatchers.Default)

    override fun cancel() {
        cancelActiveRequest()
        if (_runtimeState.value == AiRuntimeState.Busy || _runtimeState.value == AiRuntimeState.Loading) {
            _runtimeState.value = if (modelStore.isInstalled()) AiRuntimeState.Ready else AiRuntimeState.NotInstalled
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelActiveRequest()
        engine.close()
        scope.cancel()
    }

    private suspend fun runRewrite(
        modelPath: String,
        input: String,
        tone: RewriteTone,
        output: SendChannel<AiEvent>,
    ) {
        try {
            if (!engine.isInitialized) _runtimeState.value = AiRuntimeState.Loading
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
            engine.release()
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
            if (!engine.isInitialized) _runtimeState.value = AiRuntimeState.Loading
            val prompt = AiLexiconPromptBuilder.build(pinyin, context)
            engine.rewrite(modelPath, prompt.system, prompt.user).collect { event ->
                when (event) {
                    is AiEvent.Started -> {
                        _runtimeState.value = AiRuntimeState.Busy
                        output.send(event)
                    }
                    is AiEvent.Delta -> output.send(event)
                    is AiEvent.Completed -> {
                        val candidates = AiCandidateParser.parse(event.text)
                        _runtimeState.value = AiRuntimeState.Ready
                        if (candidates.isEmpty()) {
                            output.send(AiEvent.Failed(AI_LEXICON_EMPTY_MESSAGE))
                        } else {
                            output.send(AiEvent.Completed(AiCandidateParser.format(candidates)))
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
            engine.release()
            _runtimeState.value = AiRuntimeState.Error
            output.send(AiEvent.Failed("设备内存不足"))
        } catch (error: Throwable) {
            _runtimeState.value = AiRuntimeState.Error
            output.send(AiEvent.Failed("本地词库运行失败"))
        } finally {
            output.close()
        }
    }

    private fun cancelActiveRequest() {
        val job = synchronized(requestLock) {
            val current = activeJob
            activeJob = null
            current
        }
        job?.cancel()
        engine.cancel()
    }

    private companion object {
        const val AI_LEXICON_EMPTY_MESSAGE = "AI 词库没有返回候选"
    }

    private fun failureMessage(reason: AiResultSanitizer.FailureReason): String = when (reason) {
        AiResultSanitizer.FailureReason.EMPTY -> "这次没想好，请重试"
        AiResultSanitizer.FailureReason.TOO_LONG -> "改写结果过长，请重试"
    }
}
