package com.goings.kaidanzhushou.data

import android.net.Uri
import androidx.room.withTransaction
import com.goings.kaidanzhushou.data.local.BatchEntity
import com.goings.kaidanzhushou.data.local.ExportEntity
import com.goings.kaidanzhushou.data.local.KaidanDatabase
import com.goings.kaidanzhushou.data.local.RecordEntity
import com.goings.kaidanzhushou.domain.EditableFields
import com.goings.kaidanzhushou.domain.RecognitionDraft
import com.goings.kaidanzhushou.domain.RecognitionStatus
import com.goings.kaidanzhushou.domain.RecordValidator
import com.goings.kaidanzhushou.domain.ReviewStatus
import com.goings.kaidanzhushou.domain.SourceNaming
import com.goings.kaidanzhushou.image.ImageStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BatchRepository(private val db: KaidanDatabase, private val images: ImageStore) {
    val dao = db.dao()
    fun observeBatches() = dao.observeBatches()
    fun observeBatch(id: String) = dao.observeBatch(id)
    fun observeRecords(batchId: String) = dao.observeRecords(batchId)
    fun observeRecord(id: String) = dao.observeRecord(id)
    fun observeExports(batchId: String) = dao.observeExports(batchId)

    suspend fun createBatch(name: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insertBatch(BatchEntity(id, name.trim().ifBlank { "未命名照片集" }, now, now))
        return id
    }

    suspend fun renameBatch(id: String, name: String) {
        val batch = dao.getBatch(id) ?: return
        dao.updateBatch(batch.copy(name = name.trim().ifBlank { batch.name }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteBatch(id: String) {
        dao.getBatch(id)?.let { batch -> dao.deleteBatch(batch) }
        images.deleteBatch(id)
    }

    suspend fun addImported(batchId: String, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val available = (100 - dao.recordCount(batchId)).coerceAtLeast(0)
        var added = 0
        uris.take(available).forEach { uri ->
            val id = UUID.randomUUID().toString()
            val stored = images.import(batchId, uri, id)
            addStored(batchId, id, stored.original, stored.upload, stored.thumbnail, stored.quality.blurWarning, stored.quality.darknessWarning)
            added++
        }
        added
    }

    fun newCameraFile(batchId: String): File = images.cameraFile(batchId)

    suspend fun addCaptured(batchId: String, cameraFile: File): String = withContext(Dispatchers.IO) {
        check(dao.recordCount(batchId) < 100) { "每个照片集最多 100 张" }
        val id = UUID.randomUUID().toString()
        val stored = images.finalize(batchId, id, cameraFile)
        addStored(batchId, id, stored.original, stored.upload, stored.thumbnail, stored.quality.blurWarning, stored.quality.darknessWarning)
        cameraFile.takeIf { it.exists() && it.absolutePath != stored.original.absolutePath }?.delete()
        id
    }

    private suspend fun addStored(batchId: String, id: String, original: File, upload: File, thumb: File, blur: Boolean, dark: Boolean) {
        db.withTransaction {
            val ordinal = dao.recordCount(batchId) + 1
            val now = System.currentTimeMillis()
            dao.insertRecord(RecordEntity(
                id = id, batchId = batchId, ordinal = ordinal,
                sourceLabel = SourceNaming.label(ordinal), originalPath = original.absolutePath,
                uploadPath = upload.absolutePath, thumbnailPath = thumb.absolutePath, capturedAt = now,
                blurWarning = blur, darknessWarning = dark,
            ))
            dao.bumpRevision(batchId)
        }
    }

    suspend fun updateFields(recordId: String, fields: EditableFields, changed: Set<String>) {
        db.withTransaction {
            val old = dao.getRecord(recordId) ?: return@withTransaction
            val edited = (old.editedFieldSet() + changed).sorted().joinToString(",")
            val issues = RecordValidator.validate(fields)
            dao.updateRecord(old.copy(
                destinationText = fields.destinationText, deliveryType = fields.deliveryType,
                senderName = fields.senderName, receiverName = fields.receiverName, receiverMobile = fields.receiverMobile,
                goodsName = fields.goodsName, packageName = fields.packageName, quantity = fields.quantity,
                weight = fields.weight, volume = fields.volume, freight = fields.freight, editedFields = edited,
                reviewStatus = if (issues.isEmpty() && old.reviewStatus == ReviewStatus.CONFIRMED) ReviewStatus.CONFIRMED else ReviewStatus.NEEDS_REVIEW,
                updatedAt = System.currentTimeMillis(),
            ))
            dao.bumpRevision(old.batchId)
        }
    }

    suspend fun confirm(recordId: String): List<String> {
        val record = dao.getRecord(recordId) ?: return listOf("记录不存在")
        val issues = RecordValidator.validate(record.editable())
        if (issues.isEmpty()) {
            dao.updateRecord(record.copy(reviewStatus = ReviewStatus.CONFIRMED, updatedAt = System.currentTimeMillis()))
            dao.bumpRevision(record.batchId)
        }
        return issues.map { it.message }
    }

    suspend fun applyDraft(recordId: String, draft: RecognitionDraft) {
        db.withTransaction {
            val old = dao.getRecord(recordId) ?: return@withTransaction
            val edited = old.editedFieldSet()
            fun <T> keep(field: String, current: T?, ai: T?): T? = if (field in edited) current else ai
            val updated = old.copy(
                recognitionStatus = RecognitionStatus.PARSED,
                reviewStatus = ReviewStatus.NEEDS_REVIEW,
                destinationText = keep("destination_text", old.destinationText, draft.destination_text),
                deliveryType = keep("delivery_type", old.deliveryType, draft.delivery_type),
                senderName = keep("sender_name", old.senderName, draft.sender_name),
                receiverName = keep("receiver_name", old.receiverName, draft.receiver_name),
                receiverMobile = keep("receiver_mobile", old.receiverMobile, draft.receiver_mobile),
                goodsName = keep("goods_name", old.goodsName, draft.goods_name),
                packageName = keep("package", old.packageName, draft.packageName),
                quantity = keep("quantity", old.quantity, draft.quantity),
                weight = keep("weight", old.weight, draft.weight),
                volume = keep("volume", old.volume, draft.volume),
                freight = keep("freight", old.freight, draft.freight),
                uncertainFields = draft.uncertain_fields.joinToString(","), notes = draft.notes,
                errorMessage = null, updatedAt = System.currentTimeMillis(),
            )
            dao.updateRecord(updated)
            dao.bumpRevision(old.batchId)
        }
    }

    suspend fun saveExport(export: ExportEntity) {
        db.withTransaction {
            dao.insertExport(export)
            val batch = dao.getBatch(export.batchId) ?: return@withTransaction
            dao.updateBatch(batch.copy(lastExportedRevision = export.dataRevision, updatedAt = System.currentTimeMillis()))
        }
    }
}
