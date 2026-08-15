package com.goings.kaidanzhushou

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goings.kaidanzhushou.data.BatchRepository
import com.goings.kaidanzhushou.data.local.BatchEntity
import com.goings.kaidanzhushou.data.local.KaidanDatabase
import com.goings.kaidanzhushou.data.local.RecordEntity
import com.goings.kaidanzhushou.domain.AssociationFields
import com.goings.kaidanzhushou.domain.DestinationResolution
import com.goings.kaidanzhushou.domain.RecognitionDraft
import com.goings.kaidanzhushou.domain.RecognitionStatus
import com.goings.kaidanzhushou.domain.ReviewStatus
import com.goings.kaidanzhushou.image.ImageStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileLearningTest {
    @Test fun confirmedHistoryIsMergedAndSeedingIsIdempotent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KaidanDatabase::class.java).build()
        try {
            val repository = BatchRepository(db, ImageStore(context))
            db.dao().insertBatch(BatchEntity("batch", "测试", 1, 1))
            db.dao().insertRecords(listOf(
                record("one", 1, 10, ReviewStatus.CONFIRMED),
                record("two", 2, 20, ReviewStatus.CONFIRMED),
            ))

            repository.seedProfilesFromConfirmedRecords()
            repository.seedProfilesFromConfirmedRecords()

            val receivers = db.dao().getReceiverProfiles()
            val goods = db.dao().getGoodsProfiles()
            assertEquals(1, receivers.size)
            assertEquals(2, receivers.single().useCount)
            assertEquals(20, receivers.single().lastUsedAt)
            assertEquals(1, goods.size)
            assertEquals(2, goods.single().useCount)
            assertEquals(db.dao().getRecord("one")?.receiverProfileId, db.dao().getRecord("two")?.receiverProfileId)
        } finally {
            db.close()
        }
    }

    @Test fun confirmingSelectedReceiverUpdatesItsPhone() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KaidanDatabase::class.java).build()
        try {
            val repository = BatchRepository(db, ImageStore(context))
            db.dao().insertBatch(BatchEntity("batch", "测试", 1, 1))
            db.dao().insertRecord(record("one", 1, 10, ReviewStatus.CONFIRMED))
            repository.seedProfilesFromConfirmedRecords()
            val profile = db.dao().getReceiverProfiles().single()
            val next = record("two", 2, 20, ReviewStatus.NEEDS_REVIEW)
            db.dao().insertRecord(next)

            val issues = repository.saveAndConfirm(
                next.id,
                next.editable().copy(receiverMobile = "13912345678", receiverProfileId = profile.id),
                setOf("receiver_mobile"),
            )

            assertEquals(emptyList<String>(), issues)
            assertEquals("13912345678", db.dao().getReceiverProfile(profile.id)?.phone)
            assertEquals(ReviewStatus.CONFIRMED, db.dao().getRecord(next.id)?.reviewStatus)
            assertNotNull(db.dao().getRecord(next.id)?.receiverProfileId)
        } finally {
            db.close()
        }
    }

    @Test fun recognitionAutoAppliesOnlyUniqueExactProfiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KaidanDatabase::class.java).build()
        try {
            val repository = BatchRepository(db, ImageStore(context))
            db.dao().insertBatch(BatchEntity("batch", "测试", 1, 1))
            db.dao().insertRecord(record("history", 1, 10, ReviewStatus.CONFIRMED))
            repository.seedProfilesFromConfirmedRecords()

            db.dao().insertRecord(record("exact", 2, 20, ReviewStatus.NEEDS_REVIEW))
            repository.applyDraft(
                "exact",
                RecognitionDraft(
                    receiver_name = "任云飞",
                    receiver_mobile = "15085792192",
                    goods_name = "硫酸铜",
                    packageName = "错误包装",
                ),
                DestinationResolution(needsReview = false),
            )
            val exact = db.dao().getRecord("exact")!!
            assertEquals("15087192190", exact.receiverMobile)
            assertEquals("25公斤/袋", exact.packageName)
            assertNotNull(exact.receiverProfileId)

            db.dao().insertRecord(record("fuzzy", 3, 30, ReviewStatus.NEEDS_REVIEW))
            repository.applyDraft(
                "fuzzy",
                RecognitionDraft(
                    receiver_name = "任运飞",
                    receiver_mobile = "15085792192",
                    goods_name = "硫酸同",
                    packageName = "错误包装",
                ),
                DestinationResolution(needsReview = false),
            )
            val fuzzy = db.dao().getRecord("fuzzy")!!
            assertEquals("任运飞", fuzzy.receiverName)
            assertEquals("15085792192", fuzzy.receiverMobile)
            assertEquals(null, fuzzy.receiverProfileId)
            assertEquals(setOf(AssociationFields.RECEIVER, AssociationFields.GOODS), fuzzy.uncertainFieldSet())
            assertEquals(RecognitionStatus.PARSED, fuzzy.recognitionStatus)
        } finally {
            db.close()
        }
    }

    private fun record(id: String, ordinal: Int, updatedAt: Long, reviewStatus: ReviewStatus) = RecordEntity(
        id = id,
        batchId = "batch",
        ordinal = ordinal,
        sourceLabel = "照片 %03d".format(ordinal),
        originalPath = "/tmp/$id.jpg",
        capturedAt = updatedAt,
        reviewStatus = reviewStatus,
        destinationText = "昆明",
        deliveryType = "delivery",
        senderName = "张三",
        receiverName = "任云飞",
        receiverMobile = "15087192190",
        goodsName = "硫酸铜",
        packageName = "25公斤/袋",
        quantity = 1,
        paymentType = "pay_billing",
        updatedAt = updatedAt,
    )
}
