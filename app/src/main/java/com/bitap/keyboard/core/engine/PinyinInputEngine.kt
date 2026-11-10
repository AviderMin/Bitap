package com.bitap.keyboard.core.engine

import com.bitap.keyboard.core.Candidate
import com.bitap.keyboard.core.LongInputBoundaryMode
import com.bitap.keyboard.core.MixedInputResolver
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
 *
 * D07: [query] additionally asks [MixedInputResolver] for continuous
 * Chinese-English-digit candidates and prepends them to the baseline results
 * (deduped by word, tagged [CandidateSource.Other], truncated to the caller
 * limit). Pure pinyin tokens that the baseline fully decodes are untouched
 * because the resolver returns no mixed candidates for them.
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
        val shouldContinue: () -> Boolean = {
            if (request.shouldContinue()) {
                true
            } else {
                cancelled = true
                false
            }
        }
        val recalled = delegate.search(request.rawInput, request.limit, shouldContinue)

        // D07: prepend continuous mixed Chinese-English-digit candidates. The
        // resolver only decodes a token when the baseline has no full-coverage
        // pinyin decoding, so pure-pinyin results are unchanged. Its prefix
        // callback calls [PinyinEngine.search] directly (never this adapter) to
        // avoid recursion.
        val mixed = if (request.limit > 0 && request.shouldContinue()) {
            val rawCandidates = candidatesForResolver(request, recalled, shouldContinue)
            val resolved = MixedInputResolver.resolve(request.rawInput, request.limit) { prefix ->
                if (prefix == request.rawInput) {
                    rawCandidates
                } else {
                    delegate.search(prefix, MIXED_PREFIX_SEARCH_LIMIT, shouldContinue)
                }
            }
            if (cancelled) emptyList() else resolved
        } else {
            emptyList()
        }

        val elapsedNanos = (nanoTime() - startedAt).coerceAtLeast(0L)
        val candidates = mergeCandidates(mixed, recalled, request.limit)
        return QueryResult(
            sessionId = request.sessionId,
            compositionVersion = request.compositionVersion,
            candidates = candidates,
            segments = request.segments.ifEmpty { segmentBoundariesOf(request.rawInput) },
            diagnostics = QueryDiagnostics(
                recallCount = recalled.size + mixed.size,
                returnedCount = candidates.size,
                elapsedNanos = elapsedNanos,
                cancelled = cancelled,
                // The legacy engine clips inside search(); an exact fill may mean
                // clipping. Callers should treat this as a hint, not a guarantee.
                truncated = request.limit > 0 && recalled.size + mixed.size >= request.limit,
            ),
        )
    }

    /**
     * Candidate list used by the resolver's whole-token check. The baseline
     * search above is clipped at [QueryRequest.limit]; when that clip might
     * hide a lower-ranked full-coverage candidate, ask for a slightly wider
     * window once. Otherwise the already computed list is reused.
     */
    private fun candidatesForResolver(
        request: QueryRequest,
        recalled: List<Candidate>,
        shouldContinue: () -> Boolean,
    ): List<Candidate> {
        if (recalled.size < request.limit || request.limit >= MIXED_PREFIX_SEARCH_LIMIT) return recalled
        return delegate.search(request.rawInput, MIXED_PREFIX_SEARCH_LIMIT, shouldContinue)
    }

    /**
     * Prepends mixed candidates to baseline candidates, deduping by word and
     * respecting [limit]. Mixed candidates are tagged [CandidateSource.Other];
     * baseline candidates keep their legacy mapping.
     */
    private fun mergeCandidates(
        mixed: List<Candidate>,
        recalled: List<Candidate>,
        limit: Int,
    ): List<EngineCandidate> {
        if (limit <= 0) return emptyList()
        val merged = ArrayList<EngineCandidate>(minOf(limit, mixed.size + recalled.size))
        val seenWords = HashSet<String>(mixed.size + recalled.size)
        mixed.forEach { candidate ->
            if (merged.size < limit && seenWords.add(candidate.word)) {
                merged += candidate.toMixedEngineCandidate()
            }
        }
        recalled.forEach { candidate ->
            if (merged.size < limit && seenWords.add(candidate.word)) {
                merged += candidate.toEngineCandidate()
            }
        }
        return merged
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

        /** Candidate window used for the resolver's internal prefix probes. */
        private const val MIXED_PREFIX_SEARCH_LIMIT = 8
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

/** Maps one mixed-input candidate to the engine-facing DTO (D07). */
private fun Candidate.toMixedEngineCandidate(): EngineCandidate = EngineCandidate(
    word = word,
    pinyin = pinyin,
    frequency = frequency,
    consumedInputLength = consumedInputLength,
    isCorrection = false,
    source = CandidateSource.Other,
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
