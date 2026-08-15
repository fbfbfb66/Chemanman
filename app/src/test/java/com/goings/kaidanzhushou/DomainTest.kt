package com.goings.kaidanzhushou

import com.goings.kaidanzhushou.data.remote.KimiErrorKind
import com.goings.kaidanzhushou.domain.EditableFields
import com.goings.kaidanzhushou.domain.RecognitionStatus
import com.goings.kaidanzhushou.domain.RecognitionTransitions
import com.goings.kaidanzhushou.domain.RecordValidator
import com.goings.kaidanzhushou.domain.PaymentType
import com.goings.kaidanzhushou.domain.Revision
import com.goings.kaidanzhushou.domain.SourceNaming
import com.goings.kaidanzhushou.worker.AdaptiveConcurrency
import com.goings.kaidanzhushou.worker.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTest {
    private val valid = EditableFields(
        destinationText = "杭州", deliveryType = "delivery", senderName = "张三", receiverName = "李四",
        goodsName = "配件", quantity = 2, freight = 12.5, paymentType = "pay_billing",
    )

    @Test fun validatesRequiredFieldsAndNumbers() {
        assertTrue(RecordValidator.validate(valid).isEmpty())
        val fields = valid.copy(destinationText = "", quantity = 0, freight = -1.0)
        assertEquals(setOf("destination_text", "quantity", "freight"), RecordValidator.validate(fields).map { it.field }.toSet())
    }

    @Test fun acceptsAllPaymentTypesAndRejectsMissingPayment() {
        PaymentType.entries.forEach { assertTrue(RecordValidator.validate(valid.copy(paymentType = it.code)).isEmpty()) }
        assertEquals("payment_type", RecordValidator.validate(valid.copy(paymentType = null)).single().field)
        assertEquals("提付", PaymentType.fromCode("pay_arrival")?.label)
    }

    @Test fun stateTransitionsAreExplicit() {
        assertTrue(RecognitionTransitions.canMove(RecognitionStatus.UNRECOGNIZED, RecognitionStatus.QUEUED))
        assertTrue(RecognitionTransitions.canMove(RecognitionStatus.IN_FLIGHT, RecognitionStatus.PARSED))
        assertFalse(RecognitionTransitions.canMove(RecognitionStatus.PARSED, RecognitionStatus.IN_FLIGHT))
    }

    @Test fun adaptiveConcurrencyRampsAndDropsOn429() {
        val controller = AdaptiveConcurrency()
        assertEquals(4, controller.limit)
        controller.failure(KimiErrorKind.RATE_LIMIT)
        assertEquals(2, controller.limit)
        controller.failure(KimiErrorKind.RATE_LIMIT)
        assertEquals(1, controller.limit)
        repeat(5) { controller.success() }
        assertEquals(2, controller.limit)
        repeat(5) { controller.success() }
        assertEquals(4, controller.limit)
    }

    @Test fun adaptiveConcurrencyDropsOnTimeoutButNotOnPermanentErrors() {
        val controller = AdaptiveConcurrency()
        controller.failure(KimiErrorKind.TIMEOUT)
        assertEquals(2, controller.limit)
        controller.failure(KimiErrorKind.TIMEOUT)
        assertEquals(1, controller.limit)
        controller.failure(KimiErrorKind.INVALID_RESPONSE)
        assertEquals(1, controller.limit)
    }

    @Test fun retriesOnlyTransientErrors() {
        assertTrue(RetryPolicy.isRetryable(KimiErrorKind.NETWORK))
        assertTrue(RetryPolicy.isRetryable(KimiErrorKind.RATE_LIMIT))
        assertTrue(RetryPolicy.isRetryable(KimiErrorKind.TIMEOUT))
        assertFalse(RetryPolicy.isRetryable(KimiErrorKind.UNAUTHORIZED))
        assertTrue(RetryPolicy.pausesBatch(KimiErrorKind.QUOTA))
        assertEquals(4000, RetryPolicy.delayMillis(attempt = 3, jitter = 0))
    }

    @Test fun namingAndRevisionAreStable() {
        assertEquals("照片 001", SourceNaming.label(1))
        assertEquals("照片 100", SourceNaming.label(100))
        assertEquals(8L, Revision.next(7))
    }
}
