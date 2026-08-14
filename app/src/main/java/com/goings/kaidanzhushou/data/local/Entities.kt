package com.goings.kaidanzhushou.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.goings.kaidanzhushou.domain.EditableFields
import com.goings.kaidanzhushou.domain.RecognitionStatus
import com.goings.kaidanzhushou.domain.ReviewStatus

@Entity(tableName = "batches")
data class BatchEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val dataRevision: Long = 0,
    val lastExportedRevision: Long? = null,
    val recognitionPaused: Boolean = false,
)

@Entity(
    tableName = "records",
    foreignKeys = [ForeignKey(
        entity = BatchEntity::class,
        parentColumns = ["id"],
        childColumns = ["batchId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("batchId"), Index(value = ["batchId", "ordinal"], unique = true)],
)
data class RecordEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val ordinal: Int,
    val sourceLabel: String,
    val originalPath: String,
    val uploadPath: String? = null,
    val thumbnailPath: String? = null,
    val capturedAt: Long,
    val blurWarning: Boolean = false,
    val darknessWarning: Boolean = false,
    val recognitionStatus: RecognitionStatus = RecognitionStatus.UNRECOGNIZED,
    val reviewStatus: ReviewStatus = ReviewStatus.UNREVIEWED,
    val destinationText: String? = null,
    val deliveryType: String? = null,
    val senderName: String? = null,
    val receiverName: String? = null,
    val receiverMobile: String? = null,
    val goodsName: String? = null,
    val packageName: String? = null,
    val quantity: Int? = null,
    val weight: Double? = null,
    val volume: Double? = null,
    val freight: Double? = null,
    val uncertainFields: String = "",
    val editedFields: String = "",
    val notes: String? = null,
    val errorMessage: String? = null,
    val attemptCount: Int = 0,
    val updatedAt: Long = capturedAt,
) {
    fun editable() = EditableFields(
        destinationText, deliveryType, senderName, receiverName, receiverMobile,
        goodsName, packageName, quantity, weight, volume, freight,
    )

    fun uncertainFieldSet(): Set<String> = uncertainFields.split(',').filter(String::isNotBlank).toSet()
    fun editedFieldSet(): Set<String> = editedFields.split(',').filter(String::isNotBlank).toSet()
}

@Entity(
    tableName = "exports",
    foreignKeys = [ForeignKey(
        entity = BatchEntity::class,
        parentColumns = ["id"],
        childColumns = ["batchId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("batchId")],
)
data class ExportEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val fileName: String,
    val exportedAt: Long,
    val recordCount: Int,
    val dataRevision: Long,
    val localPath: String,
)

data class BatchWithStats(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val dataRevision: Long,
    val lastExportedRevision: Long?,
    val recognitionPaused: Boolean,
    val recordCount: Int,
    val confirmedCount: Int,
    val needsReviewCount: Int,
    val failedCount: Int,
)
