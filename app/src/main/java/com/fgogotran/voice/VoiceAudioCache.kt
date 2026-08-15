package com.fgogotran.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceAudioCache @Inject constructor(
    @ApplicationContext context: Context
) {
    private val cacheDir = File(context.cacheDir, "voice_audio").apply { mkdirs() }
    private val tempDir = File(context.cacheDir, "voice_audio_tmp").apply { mkdirs() }

    fun cachedFile(cacheMaterial: String): File? {
        val file = cacheFile(cacheMaterial)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    fun write(cacheMaterial: String, audio: ByteArray): File {
        val file = cacheFile(cacheMaterial)
        file.writeBytes(audio)
        pruneOldFiles()
        return file
    }

    /**
     * 返回一个专属临时文件，供 Provider 直接写入音频。
     * 写入完成后调用 [promoteTempToCache] 移到正式缓存目录。
     */
    fun tempFileFor(cacheMaterial: String): File {
        return File(tempDir, "${sha256(cacheMaterial)}.${System.currentTimeMillis()}.tmp").apply {
            parentFile?.mkdirs()
        }
    }

    /**
     * 将 provider 写出的临时文件晋升为正式缓存文件。
     * 如果临时文件为空会删除并返回 null，调用方需回退使用临时文件本身。
     */
    fun promoteTempToCache(cacheMaterial: String, tempFile: File): File? {
        if (!tempFile.exists() || tempFile.length() == 0L) {
            runCatching { tempFile.delete() }
            return null
        }
        val target = cacheFile(cacheMaterial)
        return runCatching {
            if (target.exists()) target.delete()
            if (tempFile.renameTo(target)) {
                pruneOldFiles()
                target
            } else {
                // renameTo 失败（跨卷等情况）降级为 copy
                tempFile.copyTo(target, overwrite = true)
                runCatching { tempFile.delete() }
                pruneOldFiles()
                target
            }
        }.getOrElse {
            // 任何异常都至少保留 tempFile，让调用方能播放
            if (tempFile.exists()) tempFile else null
        }
    }

    private fun cacheFile(cacheMaterial: String): File {
        return File(cacheDir, "${sha256(cacheMaterial)}.mp3")
    }

    private fun pruneOldFiles() {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_CACHED_AUDIO_FILES)
            .forEach { it.delete() }
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_CACHED_AUDIO_FILES = 160
    }
}
