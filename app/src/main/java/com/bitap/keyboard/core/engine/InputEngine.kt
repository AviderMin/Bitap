package com.bitap.keyboard.core.engine

/**
 * Replaceable pinyin input-engine contract (D03).
 *
 * An [InputEngine] is a pure text-to-candidates service. Implementations:
 *  - never write editor text and never touch an InputConnection;
 *  - never read arbitrary editor content; only the short, explicitly supplied
 *    [QueryContext] may be inspected (and only under [ContextPolicy.Restricted]);
 *  - never access the network, the clipboard, logs or persistent input history;
 *  - must cooperate with cancellation through [QueryRequest.shouldContinue].
 *
 * The legacy [com.bitap.keyboard.core.PinyinEngine] stays the baseline
 * implementation; [PinyinInputEngine] adapts it without changing candidate
 * order, candidate fields, consumed prefix lengths or correction flags.
 */
interface InputEngine : AutoCloseable {

    /** Stable engine id used by diagnostics and A/B reports. */
    val engineId: String

    /** True when the engine can serve queries (dictionary/model loaded). */
    val isReady: Boolean

    /**
     * Runs one synchronous query. Callers execute this on a background
     * dispatcher; implementations must never block the IME main thread.
     */
    fun query(request: QueryRequest): QueryResult

    /**
     * Records one explicit user selection for local learning. The default is a
     * no-op; engines that learn override it. The engine must only keep local,
     * user-controlled statistics and must never write editor text.
     */
    fun recordSelection(event: SelectionEvent) {}

    /**
     * Enables or disables local learning for this engine session. Default
     * no-op so non-learning engines need no lifecycle code. The IME controller
     * calls this from settings + editor policy changes.
     */
    fun setLearningEnabled(enabled: Boolean) {}

    /**
     * Clears all in-memory and persisted learning data owned by this engine.
     * Default no-op. Must never touch editor text or other engines' data.
     */
    fun clearLearning() {}

    /**
     * Flushes pending learning data to persistent storage, if the engine keeps
     * any. Default no-op; the caller controls when I/O happens (never per key).
     */
    fun persistSelectionWeights() {}

    /** Releases engine-owned resources. Must be idempotent. */
    override fun close()

    companion object {
        /** Default candidate cap; matches the legacy keyboard page size. */
        const val DEFAULT_LIMIT = 64
    }
}

/**
 * One contiguous letter-run segment inside [QueryRequest.rawInput].
 *
 * Offsets are UTF-16 indices; [start] is inclusive and [end] is exclusive.
 * Segment boundaries are what makes prefix consumption auditable: a candidate
 * may cover only the prefix up to one of these boundaries.
 */
data class InputSegment(
    val start: Int,
    val end: Int,
) {
    init {
        require(start >= 0 && end >= start) { "invalid segment [$start, $end)" }
    }

    val length: Int
        get() = end - start
}

/** How much editor context an engine is allowed to read for one query. */
enum class ContextPolicy {
    /** No context at all; only [QueryRequest.rawInput] may be used. */
    None,

    /**
     * A short, caller-supplied context is allowed (committed characters right
     * before the composition + the focused segment). Never full history, never
     * password fields: the caller decides and truncates before passing it.
     */
    Restricted,
}

/**
 * Restricted, in-memory-only query context (D03/F03).
 *
 * The contract never lets an engine read the editor itself. Password fields
 * must pass [None]; callers that allow context use [restricted] which caps the
 * size so a whole input history can never be accumulated.
 */
data class QueryContext(
    val policy: ContextPolicy = ContextPolicy.None,
    /** Committed characters immediately before the composing region (short). */
    val committedBefore: String = "",
    /** Current composing segment, when the caller already split it. */
    val focusedSegment: String = "",
) {
    companion object {
        /** Hard cap for any context field; deliberately small (no full history). */
        const val MAX_CONTEXT_CHARS = 32

        val None = QueryContext()

        /** Builds a size-capped restricted context; never exceed [MAX_CONTEXT_CHARS]. */
        fun restricted(
            committedBefore: String = "",
            focusedSegment: String = "",
        ): QueryContext = QueryContext(
            policy = ContextPolicy.Restricted,
            committedBefore = committedBefore.take(MAX_CONTEXT_CHARS),
            focusedSegment = focusedSegment.take(MAX_CONTEXT_CHARS),
        )
    }
}

/**
 * One engine query. [sessionId] + [compositionVersion] make results
 * attributable: a result may only be applied to the composition it was
 * produced for.
 */
data class QueryRequest(
    /** Opaque id of the current editor/input session. */
    val sessionId: String,
    /** Monotonic composition version from KeyboardUiState. */
    val compositionVersion: Long,
    /** Exact composing text (raw pinyin) this query is for. */
    val rawInput: String,
    /** Maximum number of candidates the caller wants back. */
    val limit: Int = InputEngine.DEFAULT_LIMIT,
    /**
     * Optional explicit segment boundaries (e.g. user-typed apostrophes).
     * Empty means the engine segments internally; results report the actual
     * boundaries it used in [QueryResult.segments].
     */
    val segments: List<InputSegment> = emptyList(),
    /** Context policy for this query; [QueryContext.None] by default. */
    val context: QueryContext = QueryContext.None,
    /** Cooperative cancellation; false lets the engine stop early. */
    val shouldContinue: () -> Boolean = { true },
) {
    /** Convenience access to [QueryContext.policy] so callers can gate on it cheaply. */
    val contextPolicy: ContextPolicy
        get() = context.policy
}

/** Where a candidate originated. */
enum class CandidateSource {
    Dictionary,
    UserLexicon,
    Correction,
    Ai,
    Other,
}

/** One candidate returned by an [InputEngine]. */
data class EngineCandidate(
    /** Candidate text that would be committed verbatim. */
    val word: String,
    /** Pronunciation key; empty when the engine has none. */
    val pinyin: String = "",
    /** Dictionary/user frequency used for ordering. */
    val frequency: Long = 0L,
    /**
     * Number of raw-input characters this candidate consumes. 0 means the whole
     * current composition (legacy convention), matching KeyboardUiState.Candidate.
     */
    val consumedInputLength: Int = 0,
    /** True for a typo-correction candidate; the UI must mark it as 纠错. */
    val isCorrection: Boolean = false,
    /** Where this candidate came from; used by diagnostics/evaluation only. */
    val source: CandidateSource = CandidateSource.Dictionary,
)

/** Per-query diagnostic counters, kept separate from candidate ordering. */
data class QueryDiagnostics(
    /**
     * Candidates recalled by the engine for this request. Engines with an
     * internal cap report the post-cap count; adapters must not inflate it.
     */
    val recallCount: Int = 0,
    /** Candidates actually returned to the caller. */
    val returnedCount: Int = 0,
    /** Wall-clock time spent inside the engine, nanoseconds. */
    val elapsedNanos: Long = 0L,
    /** True when [QueryRequest.shouldContinue] stopped the engine early. */
    val cancelled: Boolean = false,
    /** True when the request limit clipped a larger recall set. */
    val truncated: Boolean = false,
)

/** Result of one [InputEngine.query] call. */
data class QueryResult(
    /** Echo of [QueryRequest.sessionId]; a mismatch means the result is stale. */
    val sessionId: String,
    /** Echo of [QueryRequest.compositionVersion]; mismatch means stale. */
    val compositionVersion: Long,
    val candidates: List<EngineCandidate> = emptyList(),
    /** Segmentation actually used, as offsets into [QueryRequest.rawInput]. */
    val segments: List<InputSegment> = emptyList(),
    val diagnostics: QueryDiagnostics = QueryDiagnostics(),
)

/**
 * One explicit selection the user made, reported back to the engine for local
 * learning. [consumedInputLength] is the prefix of the composition that the
 * selection covered; 0 means the whole composition (legacy convention).
 */
data class SelectionEvent(
    val word: String,
    val pinyin: String = "",
    val source: String = "candidate",
    val consumedInputLength: Int = 0,
)
