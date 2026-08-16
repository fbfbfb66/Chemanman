package com.goings.kaidanzhushou.domain

import kotlin.math.abs

/**
 * 落库口径：freight 是「总运费」（运费 + 垫付款），advancePayment 是其中的垫付款。
 * needsReview 为真时两个金额一律留空——宁可让人重填，也不放半真半假的数进导出。
 */
data class FeeReconciliation(
    val freight: Double? = null,
    val advancePayment: Double? = null,
    val needsReview: Boolean = false,
)

/**
 * AI 只照抄单据上的三栏原始数字（运费 / 垫付款 / 总运费），加总与核对都在本地做。
 * 让模型自己相加时，它既要读数又要判断哪栏是哪栏，错了 App 无从发现。
 */
object FeeReconciler {
    const val UNCERTAIN_TOKEN = "fee_review"

    // 金额最多两位小数，比半分钱大的差额就算对不上。
    private const val TOLERANCE = 0.005

    fun reconcile(freightFee: Double?, advancePayment: Double?, totalFreight: Double?): FeeReconciliation {
        val fee = freightFee
        val advance = advancePayment
        val total = totalFreight
        return when {
            fee == null && advance == null -> FeeReconciliation(freight = total)
            advance == null && total == null -> FeeReconciliation(freight = fee)
            // 单据不可能只有垫付款而没有运费/总运费——这种组合必是 AI 把运费栏的数抄进了垫付款。
            // 按「只写运费时总运费=运费」把它归位到总运费，并标一笔让人扫一眼。
            fee == null && total == null -> FeeReconciliation(freight = advance, needsReview = true)
            fee != null && advance != null && total == null ->
                FeeReconciliation(freight = fee + advance, advancePayment = advance)
            fee != null && advance != null ->
                if (matches(fee + advance, total!!)) FeeReconciliation(freight = total, advancePayment = advance)
                else FeeReconciliation(needsReview = true)
            advance != null -> // 有总运费和垫付款，没有运费栏
                if (total!! >= advance) FeeReconciliation(freight = total, advancePayment = advance)
                else FeeReconciliation(needsReview = true)
            // 有总运费和运费栏，没有垫付款栏：两者相等才是真的没有垫付款；
            // 总运费更大时多半是漏读了垫付款那一栏，不许倒推，交给人工。
            matches(fee!!, total!!) -> FeeReconciliation(freight = total)
            total > fee -> FeeReconciliation(freight = total, needsReview = true)
            else -> FeeReconciliation(needsReview = true)
        }
    }

    private fun matches(left: Double, right: Double) = abs(left - right) <= TOLERANCE
}
