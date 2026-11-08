package com.bitap.keyboard.ai.internal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.bitap.keyboard.ai.AiModelInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Narrow model-storage surface consumed by [DefaultAiRewriteService]. The seam
 * lets lifecycle tests provide an in-memory model without touching Android
 * files or a real 328 MiB model file.
 */
interface AiModelStore {
    val modelInfo: StateFlow<AiModelInfo>

    fun refresh(): AiModelInfo

    suspend fun importModel(uri: Uri): Result<AiModelInfo>

    fun deleteModel()

    /** Absolute path of a complete model, or null when no usable model exists. */
    fun currentModelPath(): String?

    fun isInstalled(): Boolean
}

/**
 * Owns the single imported model file inside private app storage.
 *
 * Import contract: copy the SAF stream to a sibling .part file, validate it,
 * then atomically rename it into place. A failed import always removes the
 * temporary file. This class deliberately performs no logging and no network
 * I/O.
 */
class ModelStore(context: Context) : AiModelStore {

    private val appContext: Context = context.applicationContext
    private val modelsDir: File = File(appContext.filesDir, MODELS_DIR)
    private val modelFile: File = File(modelsDir, ModelFileRules.MODEL_FILE_NAME)
    private val partFile: File = File(modelsDir, ModelFileRules.MODEL_FILE_NAME + ".part")

    private val _modelInfo = MutableStateFlow(scanModel())
    override val modelInfo: StateFlow<AiModelInfo> = _modelInfo.asStateFlow()

    /** Rescan the file system and publish the result. */
    override fun refresh(): AiModelInfo {
        val info = scanModel()
        _modelInfo.value = info
        return info
    }

    /** Absolute path of a complete model, or null when no usable model exists. */
    override fun currentModelPath(): String? {
        if (!modelFile.isFile) return null
        val size = modelFile.length()
        return if (ModelFileRules.isSizeValid(size)) modelFile.absolutePath else null
    }

    override fun isInstalled(): Boolean = currentModelPath() != null

    /**
     * Copy [uri] into private storage. Suspends on [Dispatchers.IO]; the caller
     * already owns model-import serialisation.
     */
    override suspend fun importModel(uri: Uri): Result<AiModelInfo> = withContext(Dispatchers.IO) {
        var tempCreated = false
        try {
            val displayName = queryDisplayName(uri)
            if (displayName != null && !ModelFileRules.isSupportedImportName(displayName)) {
                return@withContext Result.failure(IllegalArgumentException("仅支持 .litertlm 模型文件"))
            }

            if (!modelsDir.exists() && !modelsDir.mkdirs()) {
                return@withContext Result.failure(IllegalStateException("无法创建模型目录"))
            }
            partFile.delete()
            tempCreated = true

            val copiedBytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        total += read
                    }
                    output.flush()
                    output.fd.sync()
                    total
                }
            } ?: return@withContext Result.failure(IllegalStateException("无法读取模型文件"))

            if (!ModelFileRules.isSizeValid(copiedBytes)) {
                partFile.delete()
                tempCreated = false
                return@withContext Result.failure(IllegalStateException("模型文件不完整：需要至少 50 MB"))
            }

            moveIntoPlace(partFile, modelFile)
            tempCreated = false

            val info = scanModel()
            _modelInfo.value = info
            Result.success(info)
        } catch (cancelled: CancellationException) {
            partFile.delete()
            throw cancelled
        } catch (error: Throwable) {
            partFile.delete()
            Result.failure(IllegalStateException("模型导入失败", error))
        } finally {
            if (tempCreated) partFile.delete()
        }
    }

    /** Delete the installed model and any leftover temporary file. */
    override fun deleteModel() {
        modelFile.delete()
        partFile.delete()
        _modelInfo.value = scanModel()
    }

    private fun moveIntoPlace(source: File, target: File) {
        val sourcePath = source.toPath()
        val targetPath = target.toPath()
        try {
            Files.move(
                sourcePath,
                targetPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun scanModel(): AiModelInfo {
        if (!modelFile.isFile) return AiModelInfo()
        val size = modelFile.length()
        return if (ModelFileRules.isSizeValid(size)) {
            AiModelInfo(
                installed = true,
                sizeBytes = size,
                path = modelFile.absolutePath,
            )
        } else {
            AiModelInfo(
                installed = false,
                sizeBytes = size,
                path = null,
                error = "模型文件不完整",
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MODELS_DIR = "models"
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
