package com.bitap.keyboard.core

import com.bitap.keyboard.core.engine.PinyinInputEngine
import com.bitap.keyboard.core.engine.QueryRequest
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in D04 candidate export. No tuning, learning, or validation-set access. */
class D04BaselineExportTest {
    @Test
    fun exportDevThroughInputEngine() {
        assumeTrue("D04 export is opt-in", System.getenv("BITAP_D04_EXPORT") == "1")
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "eval/v2/corpus/pinyin_cases_v2_dev.tsv").isFile }
        val corpus = File(root, "eval/v2/corpus/pinyin_cases_v2_dev.tsv")
        val dictionary = File(root, "app/src/main/assets/pinyin_dict.tsv")
        val engine = PinyinInputEngine(dictionary.useLines { PinyinEngine.fromLines(it) })
        engine.setLearningEnabled(false)
        val rows = corpus.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t').also { fields -> assertEquals(6, fields.size) } }
        assertTrue(rows.size >= 1000)
        val output = File(root, "build/d04/bitap-dev.jsonl")
        requireNotNull(output.parentFile).mkdirs()
        try {
            rows.take(24).forEachIndexed { i, row ->
                engine.query(QueryRequest("d04", i.toLong(), row[2], limit = 8))
            }
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                rows.forEachIndexed { i, row ->
                    val request = QueryRequest("d04", i.toLong(), row[2], limit = 8)
                    val top = engine.query(request)
                    val pool = engine.query(request.copy(limit = 64))
                    val coverage = row[2].count { it.isLetterOrDigit() || it == '\'' }
                    // The legacy collector can choose different partial quotas
                    // for different limits. Export both result sets explicitly.
                    fun candidates(items: List<com.bitap.keyboard.core.engine.EngineCandidate>) =
                        items.joinToString(",", "[", "]") { candidate ->
                            "{\"word\":" + json(candidate.word) +
                                ",\"full\":" + (candidate.consumedInputLength >= coverage) + "}"
                        }
                    writer.appendLine(
                        "{\"id\":" + json(row[0]) + ",\"candidates\":" + candidates(pool.candidates) +
                            ",\"top8\":" + candidates(top.candidates) +
                            ",\"latency8Nanos\":" + top.diagnostics.elapsedNanos + "}",
                    )
                }
            }
        } finally {
            engine.close()
        }
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        File(root, "build/d04/bitap-dev.meta.json").writeText(
            "{\"corpusSha256\":" + json(hash(corpus)) +
                ",\"dictionarySha256\":" + json(hash(dictionary)) +
                ",\"engineId\":" + json(engine.engineId) +
                ",\"learning\":false,\"hostOnly\":true}",
        )
    }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
