package com.bitap.keyboard.core.engine

import com.bitap.keyboard.core.Candidate
import com.bitap.keyboard.core.LongInputBoundaryMode
import com.bitap.keyboard.core.PinyinEngine

/**
 * [InputEngine] adapter over the legacy [PinyinEngine] baseline (D03).
 *
 * The adapter is intentionally thin: it forwards the exact query text and
 * limit to [PinyinEngine.search], in the same order, and maps every result
 * field one-to-one. Candidate words, ordering, [EngineCandidate.pinyin],
 * [EngineCandidate.frequency], [EngineCandidate.consumedInputLength] and
 * [EngineCandidate.isCorrection] are unchanged from the baseline. This is the
 * replacement seam for D04: a librime/other engine can implement [InputEngine]
 * and be compared against this adapter with identical caller code.
 *
 * It never writes editor text and never reads the editor; the restricted
 * [QueryContext] is passed by the caller but the pinyin baseline does not use
 * it yet, so behavior is exactly the pre-D03 behavior.
 */
class PinyinInputEngine(
    private val delegate: PinyinEngine,
    private val nanoTime: () -> Long = System::nanoTime,
) : InputEngine {

    override val engineId: String
        get() = ENGINE_ID

    override val isReady: Boolean
        get() = delegate.isReady

    /**
     * The legacy baseline rejects inputs longer than
     * [PinyinEngine.MAX_SEARCH_INPUT_LENGTH]. Declaring it here lets the
     * controller segment long compositions while the baseline keeps its own
     * hard guard for direct callers of [PinyinEngine.search].
     */
    override val maxQueryInputLength: Int
        get() = PinyinEngine.MAX_SEARCH_INPUT_LENGTH

    override fun queryInputFor(rawInput: String, mode: QueryInputMode): String =
        delegate.queryPrefixForBoundary(
            rawInput,
            maxQueryInputLength,
            when (mode) {
                QueryInputMode.FIXED -> LongInputBoundaryMode.FIXED
                QueryInputMode.SYLLABLE -> LongInputBoundaryMode.SYLLABLE
                QueryInputMode.WORD -> LongInputBoundaryMode.WORD
            },
        )

    override fun query(request: QueryRequest): QueryResult {
        val startedAt = nanoTime()
        var cancelled = false
        val recalled = delegate.search(request.rawInput, request.limit) {
            if (request.shouldContinue()) {
                true
            } else {
                cancelled = true
                false
            }
        }
        val elapsedNanos = (nanoTime() - startedAt).coerceAtLeast(0L)
        val candidates = recalled.map { it.toEngineCandidate() }
        return QueryResult(
            sessionId = request.sessionId,
            compositionVersion = request.compositionVersion,
            candidates = candidates,
            segments = request.segments.ifEmpty { segmentBoundariesOf(request.rawInput) },
            diagnostics = QueryDiagnostics(
                recallCount = recalled.size,
                returnedCount = candidates.size,
                elapsedNanos = elapsedNanos,
                cancelled = cancelled,
                // The legacy engine clips inside search(); an exact fill may mean
                // clipping. Callers should treat this as a hint, not a guarantee.
                truncated = request.limit > 0 && recalled.size >= request.limit,
            ),
        )
    }

    override fun recordSelection(event: SelectionEvent) {
        if (event.word.isBlank()) return
        delegate.recordSelection(
            word = event.word,
            pinyin = event.pinyin,
            source = event.source,
            // IME typing hot path: persistence is batched by the controller.
            persist = false,
        )
    }

    override fun setLearningEnabled(enabled: Boolean) {
        delegate.setLearningEnabled(enabled)
    }

    override fun clearLearning() {
        delegate.clearSelectionMemory()
    }

    override fun persistSelectionWeights() {
        delegate.persistSelectionWeights()
    }

    override fun close() {
        // PinyinEngine owns no native resources; nothing to release.
    }

    companion object {
        const val ENGINE_ID = "pinyin-baseline"
    }
}

/** Maps one baseline candidate to the engine-facing candidate DTO. */
fun Candidate.toEngineCandidate(): EngineCandidate = EngineCandidate(
    word = word,
    pinyin = pinyin,
    frequency = frequency,
    consumedInputLength = consumedInputLength,
    isCorrection = isCorrection,
    source = if (isCorrection) CandidateSource.Correction else CandidateSource.Dictionary,
)

/**
 * Maps an engine candidate to the keyboard UI/state model, stamping the
 * session/version it belongs to. Kept here (not in the contract) so the
 * contract has no dependency on the mutable UI state.
 */
fun EngineCandidate.toKeyboardCandidate(
    compositionVersion: Long = Candidate.UNVERSIONED,
    sessionId: String? = null,
): Candidate = Candidate(
    word = word,
    pinyin = pinyin,
    frequency = frequency,
    consumedInputLength = consumedInputLength,
    isCorrection = isCorrection,
    compositionVersion = compositionVersion,
    sessionId = sessionId,
)

/**
 * Letter-run boundaries of [rawInput], mirroring PinyinEngine.parseQuery:
 * apostrophes (and any other non-letter) separate segments, case is ignored.
 * Used only to report segmentation in [QueryResult.segments].
 */
internal fun segmentBoundariesOf(rawInput: String): List<InputSegment> {
    val out = ArrayList<InputSegment>()
    var position = 0
    while (position < rawInput.length) {
        val char = rawInput[position]
        if (char in 'a'..'z' || char in 'A'..'Z') {
            val start = position
            while (position < rawInput.length) {
                val inner = rawInput[position]
                if (inner in 'a'..'z' || inner in 'A'..'Z') {
                    position++
                } else {
                    break
                }
            }
            out += InputSegment(start, position)
        } else {
            position++
        }
    }
    return out
}
