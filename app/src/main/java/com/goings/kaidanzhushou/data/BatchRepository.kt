package com.goings.kaidanzhushou.data

import android.net.Uri
import androidx.room.withTransaction
import com.goings.kaidanzhushou.data.local.BatchEntity
import com.goings.kaidanzhushou.data.local.ExportEntity
import com.goings.kaidanzhushou.data.local.GoodsProfileEntity
import com.goings.kaidanzhushou.data.local.KaidanDatabase
import com.goings.kaidanzhushou.data.local.ProfileLearningStateEntity
import com.goings.kaidanzhushou.data.local.RecordEntity
import com.goings.kaidanzhushou.data.local.ReceiverProfileEntity
import com.goings.kaidanzhushou.domain.AssociationFields
import com.goings.kaidanzhushou.domain.AssociationMatcher
import com.goings.kaidanzhushou.domain.DestinationResolution
import com.goings.kaidanzhushou.domain.EditableFields
import com.goings.kaidanzhushou.domain.RecognitionDraft
import com.goings.kaidanzhushou.domain.RecognitionStatus
import com.goings.kaidanzhushou.domain.RecordValidator
import com.goings.kaidanzhushou.domain.ReviewStatus
import com.goings.kaidanzhushou.domain.SourceNaming
import com.goings.kaidanzhushou.image.ImageStore
import java.io.File
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BatchRepository(private val db: KaidanDatabase, private val images: ImageStore) {
    val dao = db.dao()
    fun observeBatches() = dao.observeBatches()
    fun observeBatch(id: String) = dao.observeBatch(id)
    fun observeRecords(batchId: String) = dao.observeRecords(batchId)
    fun observeRecord(id: String) = dao.observeRecord(id)
    fun observeExports(batchId: String) = dao.observeExports(batchId)
    fun observeReceiverProfiles() = dao.observeReceiverProfiles()
    fun observeGoodsProfiles() = dao.observeGoodsProfiles()

    suspend fun seedProfilesFromConfirmedRecords() {
        db.withTransaction {
            if (dao.getProfileLearningState(PROFILE_SEED_KEY) != null) return@withTransaction
            dao.getConfirmedRecords().forEach { record ->
                val receiverId = learnReceiver(
                    name = record.receiverName,
                    phone = record.receiverMobile,
                    selectedId = record.receiverProfileId,
                    usedAt = record.updatedAt,
                )
                val goodsId = learnGoods(
                    name = record.goodsName,
                    packageName = record.packageName,
                    selectedId = record.goodsProfileId,
                    usedAt = record.updatedAt,
                )
                dao.updateRecordProfileLinks(record.id, receiverId, goodsId)
            }
            dao.putProfileLearningState(ProfileLearningStateEntity(PROFILE_SEED_KEY, System.currentTimeMillis()))
        }
    }

    suspend fun createBatch(name: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insertBatch(BatchEntity(id, name.trim().ifBlank { "未命名照片集" }, now, now))
        return id
    }

    suspend fun createDatedBatch(now: Long = System.currentTimeMillis()): String {
        val base = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val existing = dao.getBatchNames(base).toSet()
        val name = if (base !in existing) base else generateSequence(2) { it + 1 }
            .map { "$base ($it)" }
            .first { it !in existing }
        return createBatch(name)
    }

    suspend fun renameBatch(id: String, name: String) {
        val batch = dao.getBatch(id) ?: return
        dao.updateBatch(batch.copy(name = name.trim().ifBlank { batch.name }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteBatch(id: String) {
        dao.getBatch(id)?.let { batch -> dao.deleteBatch(batch) }
        images.deleteBatch(id)
    }

    suspend fun deleteRecords(batchId: String, ids: Set<String>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val records = dao.getRecords(batchId).filter { it.id in ids }
        db.withTransaction {
            dao.deleteRecords(records.map { it.id })
            dao.bumpRevision(batchId)
        }
        records.forEach { record ->
            images.deleteRecord(record.id, listOf(record.originalPath, record.documentPath, record.uploadPath, record.thumbnailPath))
        }
        records.size
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
            // Deleted records keep their labels; new photos always receive a fresh, non-conflicting ordinal.
            val ordinal = dao.nextOrdinal(batchId)
            val now = System.currentTimeMillis()
            dao.insertRecord(RecordEntity(
                id = id, batchId = batchId, ordinal = ordinal,
                sourceLabel = SourceNaming.label(ordinal), originalPath = original.absolutePath,
                uploadPath = upload.absolutePath, thumbnailPath = thumb.absolutePath, capturedAt = now,
                documentPath = null, blurWarning = blur, darknessWarning = dark, edgeDetectionWarning = false,
            ))
            dao.bumpRevision(batchId)
        }
    }

    suspend fun updateFields(recordId: String, fields: EditableFields, changed: Set<String>, destinationDisplay: String? = null) {
        db.withTransaction {
            val old = dao.getRecord(recordId) ?: return@withTransaction
            val edited = (old.editedFieldSet() + changed).sorted().joinToString(",")
            val issues = RecordValidator.validate(fields)
            val uncertain = updatedUncertainFields(old, fields, changed)
            dao.updateRecord(copyFields(
                old = old,
                fields = fields,
                editedFields = edited,
                uncertainFields = uncertain,
                destinationDisplay = destinationDisplay,
                reviewStatus = if (issues.isEmpty() && old.reviewStatus == ReviewStatus.CONFIRMED) ReviewStatus.CONFIRMED else ReviewStatus.NEEDS_REVIEW,
                updatedAt = System.currentTimeMillis(),
            ))
            dao.bumpRevision(old.batchId)
        }
    }

    suspend fun confirm(recordId: String): List<String> {
        val record = dao.getRecord(recordId) ?: return listOf("记录不存在")
        return saveAndConfirm(recordId, record.editable(), emptySet(), record.destinationDisplay)
    }

    suspend fun saveAndConfirm(
        recordId: String,
        fields: EditableFields,
        changed: Set<String>,
        destinationDisplay: String? = null,
    ): List<String> = db.withTransaction {
        val old = dao.getRecord(recordId) ?: return@withTransaction listOf("记录不存在")
        val messages = RecordValidator.validate(fields).map { it.message }.toMutableList()
        if (!fields.receiverAssociationResolved) messages += "请选择正确的收货人"
        if (!fields.goodsAssociationResolved) messages += "请选择正确的货物"
        val now = System.currentTimeMillis()
        val uncertain = updatedUncertainFields(old, fields, changed)
        val edited = (old.editedFieldSet() + changed).sorted().joinToString(",")
        if (messages.isNotEmpty()) {
            dao.updateRecord(copyFields(old, fields, edited, uncertain, destinationDisplay, ReviewStatus.NEEDS_REVIEW, now))
            dao.bumpRevision(old.batchId)
            return@withTransaction messages
        }

        val receiverId = learnReceiver(fields.receiverName, fields.receiverMobile, fields.receiverProfileId, now)
        val goodsId = learnGoods(fields.goodsName, fields.packageName, fields.goodsProfileId, now)
        val confirmedFields = fields.copy(receiverProfileId = receiverId, goodsProfileId = goodsId)
        dao.updateRecord(copyFields(
            old = old,
            fields = confirmedFields,
            editedFields = edited,
            uncertainFields = uncertain,
            destinationDisplay = destinationDisplay,
            reviewStatus = ReviewStatus.CONFIRMED,
            updatedAt = now,
        ))
        dao.bumpRevision(old.batchId)
        emptyList()
    }

    suspend fun updateRotation(recordId: String, rotationDegrees: Int) = withContext(Dispatchers.IO) {
        dao.updateRotation(recordId, rotationDegrees)
    }

    suspend fun applyDraft(recordId: String, draft: RecognitionDraft, resolution: DestinationResolution = DestinationResolution()) {
        db.withTransaction {
            val old = dao.getRecord(recordId) ?: return@withTransaction
            val edited = old.editedFieldSet()
            fun <T> keep(field: String, current: T?, ai: T?): T? = if (field in edited) current else ai
            val destinationEdited = "destination_text" in edited
            val receiverEdited = "receiver_name" in edited || "receiver_mobile" in edited
            val goodsEdited = "goods_name" in edited || "package" in edited
            val receiverResolution = if (receiverEdited) null else AssociationMatcher.resolve(
                query = draft.receiver_name,
                values = dao.getReceiverProfiles(),
                normalizedName = ReceiverProfileEntity::normalizedName,
                useCount = ReceiverProfileEntity::useCount,
                lastUsedAt = ReceiverProfileEntity::lastUsedAt,
            )
            val goodsResolution = if (goodsEdited) null else AssociationMatcher.resolve(
                query = draft.goods_name,
                values = dao.getGoodsProfiles(),
                normalizedName = GoodsProfileEntity::normalizedName,
                useCount = GoodsProfileEntity::useCount,
                lastUsedAt = GoodsProfileEntity::lastUsedAt,
            )
            val automaticReceiver = receiverResolution?.automatic
            val automaticGoods = goodsResolution?.automatic
            val uncertain = old.uncertainFieldSet().toMutableSet().apply {
                if (!destinationEdited) {
                    remove("destination_text")
                    if (resolution.needsReview) add("destination_text")
                }
                if (!receiverEdited) {
                    remove(AssociationFields.RECEIVER)
                    if (receiverResolution?.needsChoice == true) add(AssociationFields.RECEIVER)
                }
                if (!goodsEdited) {
                    remove(AssociationFields.GOODS)
                    if (goodsResolution?.needsChoice == true) add(AssociationFields.GOODS)
                }
            }.sorted().joinToString(",")
            val updated = old.copy(
                recognitionStatus = RecognitionStatus.PARSED,
                reviewStatus = ReviewStatus.NEEDS_REVIEW,
                // 到站三件套共用同一个保护 key，防止重识别时出现「人工名字 + AI 键」的半保留错配。
                destinationText = keep("destination_text", old.destinationText, resolution.name),
                destinationUniqueKey = keep("destination_text", old.destinationUniqueKey, resolution.uniqueKey),
                destinationDisplay = if (destinationEdited) old.destinationDisplay else resolution.display,
                destinationCandidates = if (destinationEdited) old.destinationCandidates else resolution.candidates.joinToString(","),
                deliveryType = keep("delivery_type", old.deliveryType, draft.delivery_type),
                senderName = keep("sender_name", old.senderName, draft.sender_name),
                receiverName = if (receiverEdited) old.receiverName else automaticReceiver?.name ?: draft.receiver_name,
                receiverMobile = if (receiverEdited) old.receiverMobile else automaticReceiver?.phone ?: draft.receiver_mobile,
                receiverProfileId = if (receiverEdited) old.receiverProfileId else automaticReceiver?.id,
                goodsName = if (goodsEdited) old.goodsName else automaticGoods?.name ?: draft.goods_name,
                packageName = if (goodsEdited) old.packageName else automaticGoods?.packageName ?: draft.packageName,
                goodsProfileId = if (goodsEdited) old.goodsProfileId else automaticGoods?.id,
                quantity = keep("quantity", old.quantity, draft.quantity),
                weight = keep("weight", old.weight, draft.weight),
                volume = keep("volume", old.volume, draft.volume),
                freight = keep("freight", old.freight, draft.freight),
                paymentType = keep("payment_type", old.paymentType, draft.payment_type),
                uncertainFields = uncertain,
                notes = null,
                errorMessage = null, updatedAt = System.currentTimeMillis(),
            )
            dao.updateRecord(updated)
            dao.bumpRevision(old.batchId)
        }
    }

    private fun updatedUncertainFields(old: RecordEntity, fields: EditableFields, changed: Set<String>): String =
        old.uncertainFieldSet().toMutableSet().apply {
            if ("destination_text" in changed) remove("destination_text")
            if (fields.receiverAssociationResolved) remove(AssociationFields.RECEIVER) else add(AssociationFields.RECEIVER)
            if (fields.goodsAssociationResolved) remove(AssociationFields.GOODS) else add(AssociationFields.GOODS)
        }.sorted().joinToString(",")

    private fun copyFields(
        old: RecordEntity,
        fields: EditableFields,
        editedFields: String,
        uncertainFields: String,
        destinationDisplay: String?,
        reviewStatus: ReviewStatus,
        updatedAt: Long,
    ) = old.copy(
        destinationText = fields.destinationText,
        deliveryType = fields.deliveryType,
        senderName = fields.senderName,
        receiverName = fields.receiverName,
        receiverMobile = fields.receiverMobile,
        receiverProfileId = fields.receiverProfileId,
        goodsName = fields.goodsName,
        packageName = fields.packageName,
        goodsProfileId = fields.goodsProfileId,
        quantity = fields.quantity,
        weight = fields.weight,
        volume = fields.volume,
        freight = fields.freight,
        paymentType = fields.paymentType,
        destinationUniqueKey = fields.destinationUniqueKey,
        destinationDisplay = when {
            fields.destinationUniqueKey == null -> ""
            fields.destinationUniqueKey == old.destinationUniqueKey -> old.destinationDisplay
            else -> destinationDisplay.orEmpty()
        },
        editedFields = editedFields,
        uncertainFields = uncertainFields,
        reviewStatus = reviewStatus,
        updatedAt = updatedAt,
    )

    private suspend fun learnReceiver(
        name: String?,
        phone: String?,
        selectedId: String?,
        usedAt: Long,
    ): String? {
        val cleanName = name?.trim().orEmpty()
        val cleanPhone = phone?.trim().orEmpty()
        if (cleanName.isBlank() || cleanPhone.isBlank()) return selectedId
        val normalizedName = AssociationMatcher.normalize(cleanName)
        val normalizedPhone = AssociationMatcher.normalizePhone(cleanPhone)
        if (normalizedName.isBlank() || normalizedPhone.isBlank()) return selectedId
        val selected = selectedId?.let { dao.getReceiverProfile(it) }
        val matching = dao.getReceiverProfilesByName(normalizedName).firstOrNull {
            AssociationMatcher.normalizePhone(it.phone) == normalizedPhone
        }
        if (selected != null && matching != null && matching.id != selected.id) {
            dao.updateReceiverProfile(matching.copy(
                name = cleanName,
                normalizedName = normalizedName,
                phone = cleanPhone,
                useCount = matching.useCount + selected.useCount + 1,
                lastUsedAt = maxOf(matching.lastUsedAt, selected.lastUsedAt, usedAt),
                updatedAt = maxOf(matching.updatedAt, selected.updatedAt, usedAt),
            ))
            dao.replaceReceiverProfileLinks(selected.id, matching.id)
            dao.deleteReceiverProfile(selected)
            return matching.id
        }
        val existing = selected ?: matching
        if (existing != null) {
            dao.updateReceiverProfile(existing.copy(
                name = cleanName,
                normalizedName = normalizedName,
                phone = cleanPhone,
                useCount = existing.useCount + 1,
                lastUsedAt = maxOf(existing.lastUsedAt, usedAt),
                updatedAt = maxOf(existing.updatedAt, usedAt),
            ))
            return existing.id
        }
        val id = UUID.randomUUID().toString()
        dao.insertReceiverProfile(ReceiverProfileEntity(id, cleanName, normalizedName, cleanPhone, 1, usedAt, usedAt, usedAt))
        return id
    }

    private suspend fun learnGoods(
        name: String?,
        packageName: String?,
        selectedId: String?,
        usedAt: Long,
    ): String? {
        val cleanName = name?.trim().orEmpty()
        val cleanPackage = packageName?.trim().orEmpty()
        if (cleanName.isBlank() || cleanPackage.isBlank()) return selectedId
        val normalizedName = AssociationMatcher.normalize(cleanName)
        if (normalizedName.isBlank()) return selectedId
        val selected = selectedId?.let { dao.getGoodsProfile(it) }
        val matching = dao.getGoodsProfilesByName(normalizedName).firstOrNull {
            AssociationMatcher.normalize(it.packageName) == AssociationMatcher.normalize(cleanPackage)
        }
        if (selected != null && matching != null && matching.id != selected.id) {
            dao.updateGoodsProfile(matching.copy(
                name = cleanName,
                normalizedName = normalizedName,
                packageName = cleanPackage,
                useCount = matching.useCount + selected.useCount + 1,
                lastUsedAt = maxOf(matching.lastUsedAt, selected.lastUsedAt, usedAt),
                updatedAt = maxOf(matching.updatedAt, selected.updatedAt, usedAt),
            ))
            dao.replaceGoodsProfileLinks(selected.id, matching.id)
            dao.deleteGoodsProfile(selected)
            return matching.id
        }
        val existing = selected ?: matching
        if (existing != null) {
            dao.updateGoodsProfile(existing.copy(
                name = cleanName,
                normalizedName = normalizedName,
                packageName = cleanPackage,
                useCount = existing.useCount + 1,
                lastUsedAt = maxOf(existing.lastUsedAt, usedAt),
                updatedAt = maxOf(existing.updatedAt, usedAt),
            ))
            return existing.id
        }
        val id = UUID.randomUUID().toString()
        dao.insertGoodsProfile(GoodsProfileEntity(id, cleanName, normalizedName, cleanPackage, 1, usedAt, usedAt, usedAt))
        return id
    }

    suspend fun saveExport(export: ExportEntity) {
        db.withTransaction {
            dao.insertExport(export)
            val batch = dao.getBatch(export.batchId) ?: return@withTransaction
            dao.updateBatch(batch.copy(lastExportedRevision = export.dataRevision, updatedAt = System.currentTimeMillis()))
        }
    }

    companion object {
        private const val PROFILE_SEED_KEY = "confirmed_history_v1"
    }
}
