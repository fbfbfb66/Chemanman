package com.goings.kaidanzhushou.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

enum class RecognitionStatus { UNRECOGNIZED, QUEUED, PREPARING, IN_FLIGHT, PARSED, RETRY_WAIT, FAILED }
enum class ReviewStatus { UNREVIEWED, NEEDS_REVIEW, CONFIRMED }
enum class PaymentType(val code: String, val label: String) {
    BILLING("pay_billing", "现付"),
    ARRIVAL("pay_arrival", "提付"),
    RECEIPT("pay_receipt", "回付");

    companion object {
        val codes = entries.mapTo(linkedSetOf()) { it.code }
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code }
    }
}

@Serializable
data class RecognitionDraft(
    val destination_text: String? = null,
    val delivery_type: String? = null,
    val sender_name: String? = null,
    val receiver_name: String? = null,
    val receiver_mobile: String? = null,
    val goods_name: String? = null,
    @SerialName("package") val packageName: String? = null,
    val quantity: Int? = null,
    val weight: Double? = null,
    val volume: Double? = null,
    val freight: Double? = null,
    val payment_type: String? = null,
)

data class EditableFields(
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
    val paymentType: String? = null,
)

data class ValidationIssue(val field: String, val message: String)

object RecordValidator {
    fun validate(fields: EditableFields): List<ValidationIssue> = buildList {
        if (fields.destinationText.isNullOrBlank()) add(ValidationIssue("destination_text", "目的地不能为空"))
        if (fields.senderName.isNullOrBlank()) add(ValidationIssue("sender_name", "发货人不能为空"))
        if (fields.receiverName.isNullOrBlank()) add(ValidationIssue("receiver_name", "收货人不能为空"))
        if (fields.goodsName.isNullOrBlank()) add(ValidationIssue("goods_name", "货物名称不能为空"))
        if (fields.deliveryType !in setOf("delivery", "pickup")) add(ValidationIssue("delivery_type", "请选择送货或自提"))
        if (fields.quantity == null || fields.quantity <= 0) add(ValidationIssue("quantity", "件数必须是正整数"))
        if (fields.weight != null && fields.weight < 0) add(ValidationIssue("weight", "重量不能小于 0"))
        if (fields.volume != null && fields.volume < 0) add(ValidationIssue("volume", "体积不能小于 0"))
        if (fields.freight != null && (fields.freight < 0 || fields.freight * 100 % 1 != 0.0)) {
            add(ValidationIssue("freight", "运费不能小于 0，且最多两位小数"))
        }
        if (fields.paymentType !in PaymentType.codes) add(ValidationIssue("payment_type", "请选择付款方式"))
    }
}

object SourceNaming {
    fun label(ordinal: Int): String = "照片 %03d".format(ordinal)
}

object Revision {
    fun next(current: Long): Long = current + 1
}

object RecognitionTransitions {
    private val allowed = mapOf(
        RecognitionStatus.UNRECOGNIZED to setOf(RecognitionStatus.QUEUED),
        RecognitionStatus.QUEUED to setOf(RecognitionStatus.PREPARING, RecognitionStatus.IN_FLIGHT, RecognitionStatus.FAILED),
        RecognitionStatus.PREPARING to setOf(RecognitionStatus.IN_FLIGHT, RecognitionStatus.QUEUED, RecognitionStatus.FAILED),
        RecognitionStatus.IN_FLIGHT to setOf(RecognitionStatus.PARSED, RecognitionStatus.RETRY_WAIT, RecognitionStatus.QUEUED, RecognitionStatus.FAILED),
        RecognitionStatus.RETRY_WAIT to setOf(RecognitionStatus.IN_FLIGHT, RecognitionStatus.QUEUED, RecognitionStatus.FAILED),
        RecognitionStatus.PARSED to emptySet(),
        RecognitionStatus.FAILED to setOf(RecognitionStatus.QUEUED),
    )
    fun canMove(from: RecognitionStatus, to: RecognitionStatus) = to in allowed.getValue(from)
}
