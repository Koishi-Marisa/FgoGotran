package com.fgogotran.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.fgogotran.util.FgoLogger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class MlKitOcrScript(
    val displayName: String,
    val modelLabel: String,
    val engineId: OcrEngineId
) {
    JAPANESE("ML Kit Japanese OCR", "Japanese", OcrEngineId.ML_KIT),
    CHINESE("ML Kit Chinese OCR", "Chinese", OcrEngineId.ML_KIT_CHINESE)
}

internal class MlKitOcrProvider(
    private val script: MlKitOcrScript = MlKitOcrScript.JAPANESE
) : OcrProvider {
    private val recognizer: TextRecognizer = when (script) {
        MlKitOcrScript.CHINESE -> TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
        MlKitOcrScript.JAPANESE -> TextRecognition.getClient(
            JapaneseTextRecognizerOptions.Builder().build()
        )
    }
    private val tag = "OCR"
    @Volatile
    private var warmedUp = false

    override suspend fun warmUp() {
        if (warmedUp) return
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            FgoLogger.debug(tag, "${script.displayName} warm-up starting")
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.processSuspending(image)
            FgoLogger.info(tag, "${script.displayName} warm-up complete")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FgoLogger.warn(tag, "ML Kit OCR warm-up failed; first capture will initialize normally", e)
        } finally {
            bitmap.recycle()
            warmedUp = true
        }
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val startTime = System.currentTimeMillis()
        FgoLogger.debug(tag, "${script.displayName} starting on ${bitmap.width}x${bitmap.height}")

        // 预处理（灰度 + 对比度拉伸 + 深底浅字反色）：
        // ML Kit 对"浅底深字"识别最佳。FGO 日服/部分剧情界面是深色半透明底 + 白字，
        // 直接识别容易漏字；反色为白底黑字可显著提升准确率。
        val enhanced = enhanceForOcr(bitmap)
        val image = InputImage.fromBitmap(enhanced, 0)
        val result = recognizer.processSuspending(image)
        if (enhanced !== bitmap) enhanced.recycle()
        val lines = mutableListOf<OcrTextLine>()

        for (block in result.getTextBlocks()) {
            for (line in block.getLines()) {
                lines.add(
                    OcrTextLine(
                        text = line.getText(),
                        boundingBox = line.getBoundingBox() ?: Rect(),
                        confidence = line.getConfidence() ?: 0f
                    )
                )
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        if (lines.isEmpty()) {
            FgoLogger.warn(tag, "${script.displayName} returned 0 text lines after ${elapsed}ms")
        } else {
            FgoLogger.info(
                tag,
                "${script.displayName} complete: ${lines.size} lines, ${result.text.length} chars, ${elapsed}ms"
            )
        }

        return OcrResult(
            lines = lines,
            fullText = result.text,
            engine = script.engineId
        )
    }

    override fun close() {
        recognizer.close()
        warmedUp = false
    }

    /**
     * 轻量 OCR 预处理：灰度化 → 直方图对比度拉伸 → 深底浅字整体反色。
     * 保持与输入完全相同的尺寸与坐标，调用方的 boundingBox 换算不受影响。
     * 已二值化（白底黑字）的输入（红色台词 / 选择按钮路径）经拉伸后保持不变。
     */
    private fun enhanceForOcr(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var min = 255
        var max = 0
        var sum = 0L
        for (i in pixels.indices) {
            val c = pixels[i]
            val gray = (
                ((c shr 16) and 0xFF) * 299 +
                    ((c shr 8) and 0xFF) * 587 +
                    (c and 0xFF) * 114
                ) / 1000
            if (gray < min) min = gray
            if (gray > max) max = gray
            sum += gray
        }
        val avg = sum / pixels.size
        val range = (max - min).coerceAtLeast(1)
        // 深底浅字（平均亮度低）时反色为白底黑字，贴近 ML Kit 最优输入
        val invert = avg < 128

        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val gray = (
                ((c shr 16) and 0xFF) * 299 +
                    ((c shr 8) and 0xFF) * 587 +
                    (c and 0xFF) * 114
                ) / 1000
            var v = (gray - min) * 255 / range
            if (invert) v = 255 - v
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val enhanced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        enhanced.setPixels(out, 0, width, 0, 0, width, height)
        return enhanced
    }

    private suspend fun TextRecognizer.processSuspending(
        image: InputImage
    ): Text = suspendCancellableCoroutine { continuation ->
        process(image)
            .addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }
}
