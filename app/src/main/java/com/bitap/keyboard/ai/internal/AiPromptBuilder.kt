package com.bitap.keyboard.ai.internal

import com.bitap.keyboard.ai.RewriteTone

/** System and user prompts for one short rewriting turn. */
data class AiPrompt(
    val system: String,
    val user: String,
)

/**
 * Builds the fixed single-turn prompt described in docs/03-ai-design.md.
 *
 * Pure Kotlin: this object is safe to exercise from JVM unit tests and never
 * reads or persists user input beyond the in-memory string it is given.
 */
object AiPromptBuilder {

    const val MAX_INPUT_CHARS: Int = 512

    const val SYSTEM_PROMPT: String =
        "你是 Bitap 输入法的本地文本改写引擎。只输出改写后的文本，不解释、不寒暄、不加引号、不使用 Markdown。保留原意与语言；若要求翻译，只输出译文。不要展示思考过程。"

    /** Trim and cap the user text at 512 characters. */
    fun normalizeInput(text: String): String = text.trim().take(MAX_INPUT_CHARS)

    fun buildUserPrompt(text: String, tone: RewriteTone): String {
        val normalized = normalizeInput(text)
        return "任务：${tone.label}\n原文：$normalized\n/no_think"
    }

    fun build(text: String, tone: RewriteTone): AiPrompt =
        AiPrompt(system = SYSTEM_PROMPT, user = buildUserPrompt(text, tone))
}
