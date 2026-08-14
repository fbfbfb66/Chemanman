package com.goings.kaidanzhushou.image

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.sqrt

data class ImageQuality(val blurWarning: Boolean, val darknessWarning: Boolean)
data class StoredImage(val original: File, val upload: File, val thumbnail: File, val quality: ImageQuality)

class ImageStore(private val context: Context) {
    fun cameraFile(batchId: String): File {
        val directory = File(context.filesDir, "photos/$batchId/original").apply { mkdirs() }
        return File(directory, "capture_${System.currentTimeMillis()}.jpg")
    }

    fun import(batchId: String, uri: Uri, recordId: String): StoredImage {
        val original = originalFile(batchId, recordId)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选照片" }
            original.outputStream().use(input::copyTo)
        }
        return finalize(batchId, recordId, original)
    }

    fun finalize(batchId: String, recordId: String, source: File): StoredImage {
        val original = originalFile(batchId, recordId)
        if (source.absolutePath != original.absolutePath) source.copyTo(original, overwrite = true)
        val normalized = File(original.parentFile, "$recordId.normalized.jpg")
        resize(original, normalized, 4096, 94)
        check(normalized.renameTo(original) || normalized.copyTo(original, overwrite = true).let { normalized.delete(); true })
        val upload = derivedFile(batchId, "upload", recordId)
        val thumbnail = derivedFile(batchId, "thumb", recordId)
        resize(original, upload, 2200, 82)
        resize(original, thumbnail, 360, 78)
        return StoredImage(original, upload, thumbnail, inspect(thumbnail))
    }

    fun deleteBatch(batchId: String) {
        File(context.filesDir, "photos/$batchId").deleteRecursively()
    }

    private fun originalFile(batchId: String, recordId: String): File =
        File(context.filesDir, "photos/$batchId/original/$recordId.jpg").also { it.parentFile?.mkdirs() }

    private fun derivedFile(batchId: String, type: String, recordId: String): File =
        File(context.filesDir, "photos/$batchId/$type/$recordId.jpg").also { it.parentFile?.mkdirs() }

    private fun resize(source: File, target: File, maxEdge: Int, quality: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxEdge * 2) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })) {
            "照片格式无法解析"
        }
        val rotated = rotateFromExif(decoded, source)
        val scale = minOf(1f, maxEdge.toFloat() / max(rotated.width, rotated.height))
        val output = if (scale < 1f) Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true) else rotated
        FileOutputStream(target).use { output.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (output !== rotated) output.recycle()
        if (rotated !== decoded) rotated.recycle()
        decoded.recycle()
    }

    private fun rotateFromExif(bitmap: Bitmap, file: File): Bitmap {
        val orientation = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
        if (orientation == 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, android.graphics.Matrix().apply { postRotate(orientation.toFloat()) }, true)
    }

    private fun inspect(file: File): ImageQuality {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ImageQuality(false, false)
        val width = bitmap.width
        val height = bitmap.height
        val gray = DoubleArray(width * height)
        var sum = 0.0
        for (y in 0 until height) for (x in 0 until width) {
            val color = bitmap.getPixel(x, y)
            val luma = 0.299 * ((color shr 16) and 255) + 0.587 * ((color shr 8) and 255) + 0.114 * (color and 255)
            gray[y * width + x] = luma
            sum += luma
        }
        var lapSum = 0.0
        var lapSq = 0.0
        var count = 0
        for (y in 1 until height - 1 step 2) for (x in 1 until width - 1 step 2) {
            val i = y * width + x
            val lap = gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width] - 4 * gray[i]
            lapSum += lap
            lapSq += lap * lap
            count++
        }
        bitmap.recycle()
        val meanLap = if (count == 0) 0.0 else lapSum / count
        val variance = if (count == 0) 0.0 else lapSq / count - meanLap * meanLap
        return ImageQuality(blurWarning = variance < 75.0, darknessWarning = sum / gray.size < 48.0)
    }
}
