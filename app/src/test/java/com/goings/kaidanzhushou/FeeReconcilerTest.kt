package com.goings.kaidanzhushou

import com.goings.kaidanzhushou.domain.FeeReconciler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeeReconcilerTest {
    @Test fun threeColumnsThatAddUpAreAccepted() {
        // 单据示例：运费 30 + 垫付款 250 = 总运费 280。
        val result = FeeReconciler.reconcile(freightFee = 30.0, advancePayment = 250.0, totalFreight = 280.0)
        assertEquals(280.0, result.freight)
        assertEquals(250.0, result.advancePayment)
        assertFalse(result.needsReview)
    }

    @Test fun threeColumnsThatDoNotAddUpLeaveBothBlank() {
        val result = FeeReconciler.reconcile(freightFee = 30.0, advancePayment = 250.0, totalFreight = 281.0)
        assertEquals(null, result.freight)
        assertEquals(null, result.advancePayment)
        assertTrue(result.needsReview)
    }

    @Test fun floatingPointNoiseStaysWithinTolerance() {
        val result = FeeReconciler.reconcile(freightFee = 0.1, advancePayment = 0.2, totalFreight = 0.3)
        assertEquals(0.3, result.freight)
        assertFalse(result.needsReview)
    }

    @Test fun missingTotalIsSummedLocally() {
        val result = FeeReconciler.reconcile(freightFee = 30.0, advancePayment = 250.0, totalFreight = null)
        assertEquals(280.0, result.freight)
        assertEquals(250.0, result.advancePayment)
        assertFalse(result.needsReview)
    }

    @Test fun missingFreightColumnIsFineWhenTotalCoversAdvance() {
        val result = FeeReconciler.reconcile(freightFee = null, advancePayment = 250.0, totalFreight = 280.0)
        assertEquals(280.0, result.freight)
        assertEquals(250.0, result.advancePayment)
        assertFalse(result.needsReview)
    }

    @Test fun totalSmallerThanAdvanceIsRejected() {
        val result = FeeReconciler.reconcile(freightFee = null, advancePayment = 250.0, totalFreight = 200.0)
        assertEquals(null, result.freight)
        assertEquals(null, result.advancePayment)
        assertTrue(result.needsReview)
    }

    @Test fun totalAboveFreightWithoutAdvanceColumnIsFlagged() {
        // 差额多半是漏读的垫付款那一栏，不许倒推，交给人工。
        val result = FeeReconciler.reconcile(freightFee = 30.0, advancePayment = null, totalFreight = 280.0)
        assertEquals(280.0, result.freight)
        assertEquals(null, result.advancePayment)
        assertTrue(result.needsReview)
    }

    @Test fun totalEqualToFreightMeansNoAdvance() {
        val result = FeeReconciler.reconcile(freightFee = 30.0, advancePayment = null, totalFreight = 30.0)
        assertEquals(30.0, result.freight)
        assertEquals(null, result.advancePayment)
        assertFalse(result.needsReview)
    }

    @Test fun advanceAloneIsTreatedAsAMisreadFreight() {
        // 单据不可能只有垫付款而没有运费：这是 AI 把运费栏抄进了垫付款，归位到总运费并留个提醒。
        val result = FeeReconciler.reconcile(freightFee = null, advancePayment = 30.0, totalFreight = null)
        assertEquals(30.0, result.freight)
        assertEquals(null, result.advancePayment)
        assertTrue(result.needsReview)
    }

    @Test fun singleColumnsPassThrough() {
        assertEquals(280.0, FeeReconciler.reconcile(null, null, 280.0).freight)
        assertEquals(30.0, FeeReconciler.reconcile(30.0, null, null).freight)
        val empty = FeeReconciler.reconcile(null, null, null)
        assertEquals(null, empty.freight)
        assertEquals(null, empty.advancePayment)
        assertFalse(empty.needsReview)
    }
}
