package com.bitap.keyboard.ai.internal

/**
 * Pure validation rules for the externally supplied LiteRT-LM model file.
 * Kept free of Android imports so it can be unit-tested on the JVM.
 */
object ModelFileRules {

    /** Canonical filename inside filesDir/models/. */
    const val MODEL_FILE_NAME: String = "bitap-qwen3-0.6b.litertlm"

    /** The model must be at least 50 MiB; smaller files are treated as truncated. */
    const val MIN_MODEL_BYTES: Long = 50L * 1024L * 1024L

    private const val MODEL_EXTENSION: String = ".litertlm"

    /** Accept only .litertlm files picked through SAF. */
    fun isSupportedImportName(displayName: String?): Boolean {
        val name = displayName?.trim() ?: return false
        return name.endsWith(MODEL_EXTENSION, ignoreCase = true)
    }

    fun isSizeValid(sizeBytes: Long): Boolean = sizeBytes >= MIN_MODEL_BYTES
}
