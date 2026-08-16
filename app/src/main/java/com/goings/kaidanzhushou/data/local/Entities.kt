package com.goings.kaidanzhushou.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.goings.kaidanzhushou.domain.AssociationFields
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
    // freight 是「总运费」（运费 + 垫付款）；advancePayment 是其中的垫付款，
    // advanceReturnType 是人工选定的去向（cashreturn 现返 / discount 欠返）。
    val freight: Double? = null,
    val advancePayment: Double? = null,
    val advanceReturnType: String? = null,
    val paymentType: String? = null,
    val documentPath: String? = null,
    val edgeDetectionWarning: Boolean = false,
    val uncertainFields: String = "",
    val editedFields: String = "",
    val notes: String? = null,
    val errorMessage: String? = null,
    val attemptCount: Int = 0,
    val rotationDegrees: Int = 0,
    // 到站归一产物：uniqueKey 是字典主键；display 是上级全称（导出用，落库避免导出时依赖字典）；
    // candidates 是候选 unique_key 逗号串（复核界面 chip）。
    val destinationUniqueKey: String? = null,
    val destinationDisplay: String = "",
    val destinationCandidates: String = "",
    // 关联 ID 只描述用户选择的常用信息；运单字段仍保存独立快照，导出不依赖关联表。
    val senderProfileId: String? = null,
    val receiverProfileId: String? = null,
    val goodsProfileId: String? = null,
    val updatedAt: Long = capturedAt,
) {
    fun editable() = EditableFields(
        destinationText = destinationText, deliveryType = deliveryType, senderName = senderName,
        receiverName = receiverName, receiverMobile = receiverMobile, goodsName = goodsName,
        packageName = packageName, quantity = quantity, weight = weight, volume = volume,
        freight = freight, advancePayment = advancePayment, advanceReturnType = advanceReturnType,
        paymentType = paymentType, destinationUniqueKey = destinationUniqueKey,
        senderProfileId = senderProfileId,
        receiverProfileId = receiverProfileId,
        goodsProfileId = goodsProfileId,
        senderAssociationResolved = AssociationFields.SENDER !in uncertainFieldSet(),
        receiverAssociationResolved = AssociationFields.RECEIVER !in uncertainFieldSet(),
    )

    fun destinationCandidateList(): List<String> = destinationCandidates.split(',').filter(String::isNotBlank)

    fun uncertainFieldSet(): Set<String> = uncertainFields.split(',').filter(String::isNotBlank).toSet()
    fun editedFieldSet(): Set<String> = editedFields.split(',').filter(String::isNotBlank).toSet()
}

// 发货人没有第二字段（收货人有手机号、货物有包装），同名即同一档案。
@Entity(
    tableName = "sender_profiles",
    indices = [Index("normalizedName"), Index("lastUsedAt")],
)
data class SenderProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val useCount: Int,
    val lastUsedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "receiver_profiles",
    indices = [Index("normalizedName"), Index("lastUsedAt")],
)
data class ReceiverProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val phone: String,
    val useCount: Int,
    val lastUsedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "goods_profiles",
    indices = [Index("normalizedName"), Index("lastUsedAt")],
)
data class GoodsProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val packageName: String,
    val useCount: Int,
    val lastUsedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "profile_learning_state")
data class ProfileLearningStateEntity(
    @PrimaryKey val key: String,
    val completedAt: Long,
)

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
    val publicUri: String? = null,
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
