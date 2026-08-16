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

// 垫付款的去向，由人工按实际情况选择，AI 不参与判断。code 直接就是网站开单页的 data-path，
// 也是导出 Excel 的列名，脚本拿到后零转换即可写进对应输入框。
enum class AdvanceReturnType(val code: String, val label: String) {
    CASH_RETURN("cashreturn", "现返"),
    DISCOUNT("discount", "欠返");

    companion object {
        val codes = entries.mapTo(linkedSetOf()) { it.code }
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code }
    }
}

// App 的金额输入框恒 >= 0，0 和空是同一件事：没有垫付款就没有去向可选。
// 全案只认「advancePayment != null」这一个判据，下游不再出现 > 0 的分支。
fun normalizeAdvancePayment(value: Double?): Double? = value?.takeIf { it != 0.0 }

@Serializable
data class RecognitionDraft(
    // 到站只做「读图取证」：raw_tokens 逐字照抄、checked_token 是带标记的那项、canonical 受字典 enum 约束。
    // 最终写入记录的标准站名由本地 DestinationNormalizer 决定，不信 AI 的结论。
    val destination_raw_tokens: List<String> = emptyList(),
    val destination_checked_token: String? = null,
    val destination_mark_type: String? = null,
    val destination_layout: String? = null,
    val destination_canonical: String? = null,
    val delivery_type: String? = null,
    val sender_name: String? = null,
    val receiver_name: String? = null,
    val receiver_mobile: String? = null,
    val goods_name: String? = null,
    @SerialName("package") val packageName: String? = null,
    val quantity: Int? = null,
    val weight: Double? = null,
    val volume: Double? = null,
    // 三个费用字段全是「照抄」：freight_fee=运费栏、advance_payment=垫付款栏、total_freight=总运费栏。
    // 模型禁止做加法，落库口径（freight=总运费）由 FeeReconciler 在本地推导。
    val freight_fee: Double? = null,
    val advance_payment: Double? = null,
    val total_freight: Double? = null,
    val payment_type: String? = null,
) {
    fun destinationEvidence() = DestinationEvidence(
        rawTokens = destination_raw_tokens,
        checkedToken = destination_checked_token,
        markType = destination_mark_type,
        layout = destination_layout,
        aiCanonical = destination_canonical,
    )
}

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
    // 垫付款与其去向同生共死：金额为空时去向必须一并为空，否则会有脏值进 Excel。
    val advancePayment: Double? = null,
    val advanceReturnType: String? = null,
    val paymentType: String? = null,
    // 与 destinationText 同生共死：人工手改文本时必须置 null（名/键错配的行不允许导出）。
    val destinationUniqueKey: String? = null,
    val senderProfileId: String? = null,
    val receiverProfileId: String? = null,
    val goodsProfileId: String? = null,
    // 只有发货人和收货人需要人工确认关联；货物只做下拉辅助，不拦人。
    val senderAssociationResolved: Boolean = true,
    val receiverAssociationResolved: Boolean = true,
    // 仅用于当前核对表单：记录用户明确选择了候选或“使用当前内容”。
    val senderAssociationAccepted: Boolean = false,
    val receiverAssociationAccepted: Boolean = false,
) {
    // 唯一的垫付款写入口：顺手做 0→null 归一，并在归零时清掉去向。
    fun withAdvancePayment(value: Double?): EditableFields {
        val amount = normalizeAdvancePayment(value)
        return copy(advancePayment = amount, advanceReturnType = if (amount == null) null else advanceReturnType)
    }
}

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
        // 每张单必有运费，只写运费时总运费就等于运费——所以总运费永远填得出来，空着一定是漏了。
        if (fields.freight == null) {
            add(ValidationIssue("freight", "总运费不能为空"))
        } else if (fields.freight < 0 || fields.freight * 100 % 1 != 0.0) {
            add(ValidationIssue("freight", "总运费不能小于 0，且最多两位小数"))
        }
        // 0 已在 withAdvancePayment 里归一为 null，走到这里的垫付款必然是真金额。
        if (fields.advancePayment != null) {
            if (fields.advancePayment < 0 || fields.advancePayment * 100 % 1 != 0.0) {
                add(ValidationIssue("advance_payment", "垫付款不能小于 0，且最多两位小数"))
            }
            if (fields.advanceReturnType !in AdvanceReturnType.codes) {
                add(ValidationIssue("advance_return_type", "请选择垫付款是现返还是欠返"))
            }
            if (fields.freight != null && fields.freight < fields.advancePayment) {
                add(ValidationIssue("freight", "总运费不能小于垫付款"))
            }
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
