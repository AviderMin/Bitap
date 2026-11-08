package com.bitap.keyboard.qa

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.core.EditorInputKind
import com.bitap.keyboard.core.EditorTextSnapshot
import com.bitap.keyboard.core.InputLanguage
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.KeyboardLayer
import com.bitap.keyboard.core.KeyboardUiState
import com.bitap.keyboard.core.PendingConfirmation
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.core.TextSession
import com.bitap.keyboard.core.UnicodeGrapheme
import com.bitap.keyboard.core.EditorInputInfo
import com.bitap.keyboard.ime.KeyboardController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * D01 event-sequence replay.
 *
 * Reads eval/v2/events/event_cases.jsonl and replays every case through a real
 * KeyboardController + RecordingTextSession on the JVM. It asserts the final
 * editor text, composing state, candidates, language/layer/shift and pending
 * confirmation. The engine is a small synthetic dictionary defined below; the
 * production bundled dictionary is never read or modified by this test.
 *
 * This is a JVM harness. It does not represent real-device IME behavior, touch
 * timing, Compose rendering or system input-method lifecycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventSequenceReplayTest {

    @Test
    fun allEventSequenceCasesReplay() {
        val root = findRepoRoot()
        val casesFile = File(root, "eval/v2/events/event_cases.jsonl")
        assumeTrue("event cases missing: " + casesFile.absolutePath, casesFile.isFile)
        val cases = parseCases(casesFile)
        assertTrue("event cases need >=24 but were " + cases.size, cases.size >= 24)

        val requiredCategories = setOf(
            "continuous_input_selection",
            "pending_confirmation",
            "backspace_unicode",
            "selection_change",
            "cursor_move",
            "editor_switch",
            "recreate_controller",
            "language_switch",
            "punctuation",
            "space",
            "rapid_keys",
        )
        val actualCategories = cases.map { it.category }.toSet()
        assertEquals("event categories", requiredCategories, actualCategories)

        val failures = mutableListOf<String>()
        cases.forEach { case ->
            try {
                replay(case)
            } catch (error: Throwable) {
                failures += case.id + " [" + case.category + "]: " + (error.message ?: error.javaClass.simpleName)
            }
        }
        assertTrue(
            "event replay failures (" + failures.size + "/" + cases.size + "):\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
        println(
            "EventSequenceReplay: " + cases.size + " cases passed; categories=" +
                actualCategories.sorted().joinToString(","),
        )
    }

    private fun replay(case: ReplayCase) {
        val runtime = ReplayRuntime()
        runtime.controller.onStartInput(EditorInputInfo())
        try {
            case.steps.forEach { step -> execute(step, runtime) }
            assertEquals(case.id + " final text", case.expectedFinalText, runtime.session.text)
            assertState(case.id, case.expectedState, runtime.controller.state.value, runtime.session)
        } finally {
            runtime.dispose()
        }
    }

    private fun execute(step: ReplayStep, runtime: ReplayRuntime) {
        when (step.action) {
            "startInput" -> runtime.controller.onStartInput(editorInfo(step.args))
            "replaceEditorText" -> runtime.session.replaceText(step.args.string("text"))
            "appendInput" -> runtime.controller.dispatch(KeyboardAction.AppendInput(step.args.string("text")))
            "tapCandidate" -> runtime.controller.dispatch(KeyboardAction.CandidateTapped(step.args.int("index")))
            "space" -> runtime.controller.dispatch(KeyboardAction.Space)
            "backspace" -> runtime.controller.dispatch(KeyboardAction.Backspace)
            "enter" -> runtime.controller.dispatch(KeyboardAction.Enter)
            "toggleLanguage" -> runtime.controller.dispatch(KeyboardAction.ToggleLanguage)
            "showLayer" -> runtime.controller.dispatch(KeyboardAction.ShowLayer(layer(step.args.string("layer"))))
            "shiftTap" -> runtime.controller.dispatch(KeyboardAction.ShiftTapped(step.args.long("now")))
            "selectText" -> {
                runtime.session.select(step.args.int("start"), step.args.int("end"))
                runtime.controller.onSelectionChanged(
                    selectionStart = step.args.int("start"),
                    selectionEnd = step.args.int("end"),
                    candidatesStart = step.args.int("candidatesStart", -1),
                    candidatesEnd = step.args.int("candidatesEnd", -1),
                )
            }
            "settle" -> Unit
            "expectState" -> assertState("mid-state", step.args, runtime.controller.state.value, runtime.session)
            "recreateController" -> runtime.recreateController()
            else -> error("unknown event action: " + step.action)
        }
        if (step.settle) runtime.scheduler.advanceUntilIdle()
    }

    /** Mutable per-case controller lifetime so recreateController can swap scope/controller. */
    private inner class ReplayRuntime {
        val session = RecordingTextSession()
        private val engine = PinyinEngine.fromLines(DEFAULT_TEST_DICTIONARY.asSequence())
        var scheduler = TestCoroutineScheduler()
        var dispatcher = StandardTestDispatcher(scheduler)
        var scope = CoroutineScope(SupervisorJob() + dispatcher)
        var controller = newController()

        private fun newController(): KeyboardController =
            KeyboardController(scope, session, engine, StubAiService(), dispatcher)

        fun recreateController() {
            runCatching { controller.dispose() }
            scope.cancel()
            scheduler = TestCoroutineScheduler()
            dispatcher = StandardTestDispatcher(scheduler)
            scope = CoroutineScope(SupervisorJob() + dispatcher)
            controller = newController()
            controller.onStartInput(EditorInputInfo())
        }

        fun dispose() {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }

    private fun assertState(
        context: String,
        expected: Map<String, JsonValue>,
        state: KeyboardUiState,
        session: RecordingTextSession,
    ) {
        expected["composing"]?.let { assertEquals(context + " composing", it.asString(), state.composing) }
        expected["candidates"]?.let {
            assertEquals(context + " candidates", it.asArray().map { node -> node.asString() }, state.candidates.map { c -> c.word })
        }
        expected["firstCandidate"]?.let {
            assertEquals(context + " firstCandidate", it.asString(), state.candidates.firstOrNull()?.word)
        }
        expected["candidatesEmpty"]?.let {
            assertEquals(context + " candidatesEmpty", it.asBoolean(), state.candidates.isEmpty())
        }
        expected["candidatesFor"]?.let {
            assertEquals(context + " candidatesFor", it.asString(), state.candidatesFor)
        }
        expected["language"]?.let {
            val actual = if (state.language == InputLanguage.Chinese) "chinese" else "english"
            assertEquals(context + " language", it.asString(), actual)
        }
        expected["layer"]?.let {
            assertEquals(context + " layer", it.asString(), state.layer.name.lowercase())
        }
        expected["shift"]?.let {
            assertEquals(context + " shift", it.asString(), state.shift.name.lowercase())
        }
        expected["pendingConfirmation"]?.let {
            assertEquals(context + " pendingConfirmation", it.asString(), pendingName(state.pendingConfirmation))
        }
        expected["selectionStart"]?.let {
            assertEquals(context + " selectionStart", it.asInt(), session.selectionStart)
        }
        expected["selectionEnd"]?.let {
            assertEquals(context + " selectionEnd", it.asInt(), session.selectionEnd)
        }
    }

    private fun pendingName(pending: PendingConfirmation?): String = when (pending) {
        null -> "none"
        PendingConfirmation.Space -> "space"
        is PendingConfirmation.Candidate -> "candidate"
        is PendingConfirmation.Punctuation -> "punctuation"
    }

    private fun editorInfo(args: Map<String, JsonValue>): EditorInputInfo {
        val kind = when (args["kind"]?.asString() ?: "text") {
            "password" -> EditorInputKind.Password
            "number" -> EditorInputKind.Number
            "phone" -> EditorInputKind.Phone
            "datetime" -> EditorInputKind.DateTime
            "email" -> EditorInputKind.Email
            "url" -> EditorInputKind.Url
            else -> EditorInputKind.Text
        }
        return EditorInputInfo(kind = kind)
    }

    private fun layer(name: String): KeyboardLayer = when (name) {
        "numbers" -> KeyboardLayer.Numbers
        "symbols" -> KeyboardLayer.Symbols
        else -> KeyboardLayer.Letters
    }

    private fun findRepoRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "eval/v2/events/event_cases.jsonl").isFile &&
                File(directory, "app/src/main/assets/pinyin_dict.tsv").isFile
            ) {
                return directory
            }
            directory = directory.parentFile
        }
        error("repository root not found from " + System.getProperty("user.dir"))
    }

    // ---- JSONL parser ----------------------------------------------------

    private fun parseCases(file: File): List<ReplayCase> =
        file.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapIndexed { lineIndex, line ->
                try {
                    parseCase(JsonParser(line).parse())
                } catch (error: Throwable) {
                    throw IllegalArgumentException("bad event case at line " + (lineIndex + 1) + ": " + error.message, error)
                }
            }

    private fun parseCase(node: JsonValue): ReplayCase {
        val obj = node.asObject()
        val id = requireField(obj, "id").asString()
        val category = requireField(obj, "category").asString()
        val description = obj["description"]?.asString() ?: ""
        val steps = requireField(obj, "steps").asArray().map { stepNode ->
            val step = stepNode.asObject()
            ReplayStep(
                action = requireField(step, "action").asString(),
                args = step["args"]?.asObject() ?: emptyMap(),
                settle = (step["settle"] as? JsonValue.Bool)?.value ?: true,
            )
        }
        return ReplayCase(
            id = id,
            category = category,
            description = description,
            steps = steps,
            expectedFinalText = requireField(obj, "expectedFinalText").asString(),
            expectedState = requireField(obj, "expectedState").asObject(),
        )
    }

    private fun requireField(obj: Map<String, JsonValue>, name: String): JsonValue =
        obj[name] ?: error("missing field: " + name)

    private data class ReplayCase(
        val id: String,
        val category: String,
        val description: String,
        val steps: List<ReplayStep>,
        val expectedFinalText: String,
        val expectedState: Map<String, JsonValue>,
    )

    private data class ReplayStep(
        val action: String,
        val args: Map<String, JsonValue>,
        val settle: Boolean,
    )

    private sealed interface JsonValue {
        data class Obj(val values: Map<String, JsonValue>) : JsonValue
        data class Arr(val values: List<JsonValue>) : JsonValue
        data class Str(val value: String) : JsonValue
        data class Num(val value: Double) : JsonValue
        data class Bool(val value: Boolean) : JsonValue
        object Null : JsonValue
    }

    private class JsonParser(private val source: String) {
        private var index = 0

        fun parse(): JsonValue {
            val value = parseValue()
            skipWhitespace()
            require(index == source.length) { "trailing characters at " + index }
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            require(index < source.length) { "unexpected end of JSON" }
            return when (val char = source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> { expectLiteral("true"); JsonValue.Bool(true) }
                'f' -> { expectLiteral("false"); JsonValue.Bool(false) }
                'n' -> { expectLiteral("null"); JsonValue.Null }
                '-', in '0'..'9' -> parseNumber()
                else -> error("unexpected character '$char' at " + index)
            }
        }

        private fun parseObject(): JsonValue.Obj {
            index++
            val values = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') { index++; return JsonValue.Obj(values) }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                require(peek() == ':') { "expected ':' at " + index }
                index++
                values[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { index++; continue }
                    '}' -> { index++; return JsonValue.Obj(values) }
                    else -> error("expected ',' or '}' at " + index)
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            index++
            val values = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { index++; return JsonValue.Arr(values) }
            while (true) {
                values += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { index++; continue }
                    ']' -> { index++; return JsonValue.Arr(values) }
                    else -> error("expected ',' or ']' at " + index)
                }
            }
        }

        private fun parseString(): String {
            require(peek() == '"') { "expected string at " + index }
            index++
            val builder = StringBuilder()
            while (index < source.length) {
                when (val char = source[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        require(index < source.length) { "unterminated escape" }
                        when (val escaped = source[index++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "bad unicode escape" }
                                builder.append(source.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> error("bad escape '\\$escaped' at " + index)
                        }
                    }
                    else -> builder.append(char)
                }
            }
            error("unterminated string")
        }

        private fun parseNumber(): JsonValue.Num {
            val start = index
            while (index < source.length) {
                val char = source[index]
                if (char.isDigit() || char == '-' || char == '+' || char == '.' || char == 'e' || char == 'E') index++ else break
            }
            return JsonValue.Num(source.substring(start, index).toDouble())
        }

        private fun expectLiteral(literal: String) {
            require(source.startsWith(literal, index)) { "expected " + literal + " at " + index }
            index += literal.length
        }

        private fun peek(): Char {
            require(index < source.length) { "unexpected end of JSON" }
            return source[index]
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }
    }

    private fun JsonValue.asObject(): Map<String, JsonValue> =
        (this as? JsonValue.Obj)?.values ?: error("expected JSON object")

    private fun JsonValue.asArray(): List<JsonValue> =
        (this as? JsonValue.Arr)?.values ?: error("expected JSON array")

    private fun JsonValue.asString(): String =
        (this as? JsonValue.Str)?.value ?: error("expected JSON string")

    private fun JsonValue.asInt(): Int =
        (this as? JsonValue.Num)?.value?.toInt() ?: error("expected JSON number")

    private fun JsonValue.asBoolean(): Boolean =
        (this as? JsonValue.Bool)?.value ?: error("expected JSON boolean")

    private fun Map<String, JsonValue>.string(name: String): String =
        this[name]?.asString() ?: ""

    private fun Map<String, JsonValue>.int(name: String, default: Int = 0): Int =
        this[name]?.asInt() ?: default

    private fun Map<String, JsonValue>.long(name: String, default: Long = 0L): Long =
        this[name]?.asInt()?.toLong() ?: default

    // ---- JVM TextSession -------------------------------------------------

    private class RecordingTextSession(initialText: String = "") : TextSession {
        var text: String = initialText
            private set

        var selectionStart: Int = initialText.length
            private set

        var selectionEnd: Int = initialText.length
            private set

        private var composingStart: Int = -1
        private var composingEnd: Int = -1

        val log = mutableListOf<String>()

        fun select(start: Int, end: Int) {
            selectionStart = start.coerceIn(0, text.length)
            selectionEnd = end.coerceIn(0, text.length)
        }

        fun replaceText(newText: String) {
            text = newText
            composingStart = -1
            composingEnd = -1
            selectionStart = newText.length
            selectionEnd = newText.length
        }

        override val isActive: Boolean = true

        override fun commitText(text: String) {
            log += "commit:" + text
            val start = if (composingStart >= 0) composingStart else selectionStart
            val end = if (composingEnd >= 0) composingEnd else selectionEnd
            replaceRange(minOf(start, end), maxOf(start, end), text)
            composingStart = -1
            composingEnd = -1
            selectionStart = minOf(start, end) + text.length
            selectionEnd = selectionStart
        }

        override fun setComposingText(text: String) {
            log += "setComposing:" + text
            if (text.isEmpty()) {
                finishComposingText()
                return
            }
            val start = if (composingStart >= 0) composingStart else minOf(selectionStart, selectionEnd)
            val end = if (composingEnd >= 0) composingEnd else maxOf(selectionStart, selectionEnd)
            replaceRange(minOf(start, end), maxOf(start, end), text)
            composingStart = minOf(start, end)
            composingEnd = composingStart + text.length
            selectionStart = composingEnd
            selectionEnd = composingEnd
        }

        override fun finishComposingText() {
            log += "finishComposing"
            composingStart = -1
            composingEnd = -1
        }

        override fun deleteBackward() {
            log += "deleteBackward"
            if (selectionStart != selectionEnd) {
                val start = minOf(selectionStart, selectionEnd)
                val end = maxOf(selectionStart, selectionEnd)
                replaceRange(start, end, "")
                composingStart = -1
                composingEnd = -1
                selectionStart = start
                selectionEnd = start
                return
            }
            if (selectionStart > 0) {
                val before = text.substring(0, selectionStart)
                val deleteCount = UnicodeGrapheme.lastGraphemeLength(before).coerceAtLeast(1)
                replaceRange(selectionStart - deleteCount, selectionStart, "")
                if (composingStart >= 0) {
                    composingEnd -= deleteCount
                    if (composingEnd <= composingStart) {
                        composingStart = -1
                        composingEnd = -1
                    }
                }
                selectionStart = (selectionStart - deleteCount).coerceAtLeast(0)
                selectionEnd = selectionStart
            }
        }

        override fun sendEnter() {
            log += "sendEnter"
        }

        override fun getSelectedText(): String? =
            if (selectionStart != selectionEnd) {
                text.substring(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd))
            } else {
                null
            }

        override fun getTextBeforeCursor(maxChars: Int): String? {
            if (maxChars <= 0) return null
            val end = selectionStart.coerceIn(0, text.length)
            return text.substring((end - maxChars).coerceAtLeast(0), end)
        }

        override fun getSurroundingText(maxChars: Int): EditorTextSnapshot = EditorTextSnapshot(
            selected = getSelectedText(),
            beforeCursor = getTextBeforeCursor(maxChars),
        )

        override fun readAiSource(maxChars: Int): String {
            if (maxChars <= 0) return ""
            val selected = getSelectedText()
            if (!selected.isNullOrEmpty()) return selected.take(maxChars)
            return (getTextBeforeCursor(maxChars) ?: "").trimEnd().take(maxChars)
        }

        private fun replaceRange(start: Int, end: Int, value: String) {
            val safeStart = start.coerceIn(0, text.length)
            val safeEnd = end.coerceIn(safeStart, text.length)
            text = text.substring(0, safeStart) + value + text.substring(safeEnd)
        }
    }

    private class StubAiService : AiRewriteService {
        override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.NotInstalled)
        override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo())

        override fun refresh() = Unit

        override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
            Result.failure(UnsupportedOperationException("not used by event replay"))

        override fun deleteModel() = Unit

        override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> =
            flowOf(AiEvent.Completed("ok"))

        override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> =
            flowOf(AiEvent.Completed(""))

        override fun cancel() = Unit

        override fun close() = Unit
    }

    companion object {
        /** Synthetic dictionary for replay only; never written to production assets. */
        private val DEFAULT_TEST_DICTIONARY = listOf(
            "ni\t你\t500",
            "nihao\t你好\t500",
            "wo\t我\t500",
            "woaini\t我爱你\t500",
            "ai\t爱\t200",
            "mingtian\t明天\t500",
            "women\t我们\t500",
            "shangwu\t上午\t300",
            "kandianying\t看电影\t300",
            "hao\t好\t300",
            "ma\t吗\t200",
            "tianqi\t天气\t200",
            "henhao\t很好\t200",
            "jintian\t今天\t300",
            "tamen\t他们\t200",
            "zaijian\t再见\t200",
            "zhongguo\t中国\t200",
            "xiexie\t谢谢\t200",
            "duibuqi\t对不起\t200",
            "meishi\t没事\t200",
        )
    }
}
