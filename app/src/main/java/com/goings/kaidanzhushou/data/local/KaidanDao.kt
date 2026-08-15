package com.goings.kaidanzhushou.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.goings.kaidanzhushou.domain.RecognitionStatus
import kotlinx.coroutines.flow.Flow
import com.goings.kaidanzhushou.domain.Revision

@Dao
interface KaidanDao {
    @Query("""
        SELECT b.*,
          COUNT(r.id) AS recordCount,
          COALESCE(SUM(CASE WHEN r.reviewStatus = 'CONFIRMED' THEN 1 ELSE 0 END), 0) AS confirmedCount,
          COALESCE(SUM(CASE WHEN r.reviewStatus = 'NEEDS_REVIEW' THEN 1 ELSE 0 END), 0) AS needsReviewCount,
          COALESCE(SUM(CASE WHEN r.recognitionStatus = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedCount
        FROM batches b LEFT JOIN records r ON b.id = r.batchId
        GROUP BY b.id ORDER BY b.updatedAt DESC
    """)
    fun observeBatches(): Flow<List<BatchWithStats>>

    @Query("SELECT * FROM batches WHERE id = :id")
    fun observeBatch(id: String): Flow<BatchEntity?>

    @Query("SELECT * FROM batches WHERE id = :id")
    suspend fun getBatch(id: String): BatchEntity?

    @Query("SELECT name FROM batches WHERE name LIKE :prefix || '%'")
    suspend fun getBatchNames(prefix: String): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatch(batch: BatchEntity)

    @Update suspend fun updateBatch(batch: BatchEntity)
    @Delete suspend fun deleteBatch(batch: BatchEntity)

    @Query("SELECT * FROM records WHERE batchId = :batchId ORDER BY ordinal")
    fun observeRecords(batchId: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE batchId = :batchId ORDER BY ordinal")
    suspend fun getRecords(batchId: String): List<RecordEntity>

    @Query("SELECT * FROM records WHERE reviewStatus = 'CONFIRMED' ORDER BY updatedAt, id")
    suspend fun getConfirmedRecords(): List<RecordEntity>

    @Query("SELECT * FROM records WHERE id = :id")
    fun observeRecord(id: String): Flow<RecordEntity?>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getRecord(id: String): RecordEntity?

    @Query("SELECT COUNT(*) FROM records WHERE batchId = :batchId")
    suspend fun recordCount(batchId: String): Int

    @Query("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM records WHERE batchId = :batchId")
    suspend fun nextOrdinal(batchId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecord(record: RecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecords(records: List<RecordEntity>)

    @Update suspend fun updateRecord(record: RecordEntity)

    @Query("UPDATE records SET receiverProfileId = :receiverProfileId, goodsProfileId = :goodsProfileId WHERE id = :id")
    suspend fun updateRecordProfileLinks(id: String, receiverProfileId: String?, goodsProfileId: String?)

    @Query("UPDATE records SET rotationDegrees = :rotationDegrees WHERE id = :id")
    suspend fun updateRotation(id: String, rotationDegrees: Int)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun deleteRecordById(id: String)

    @Query("DELETE FROM records WHERE id IN (:ids)")
    suspend fun deleteRecords(ids: List<String>)

    @Query("UPDATE records SET recognitionStatus = :to, errorMessage = NULL WHERE batchId = :batchId AND recognitionStatus IN (:from)")
    suspend fun moveRecognitionStates(batchId: String, from: List<RecognitionStatus>, to: RecognitionStatus)

    @Query("SELECT * FROM records WHERE batchId = :batchId AND recognitionStatus IN (:statuses) ORDER BY ordinal")
    suspend fun recordsWithStatus(batchId: String, statuses: List<RecognitionStatus>): List<RecordEntity>

    @Insert suspend fun insertExport(export: ExportEntity)

    @Query("SELECT * FROM exports WHERE batchId = :batchId ORDER BY exportedAt DESC")
    fun observeExports(batchId: String): Flow<List<ExportEntity>>

    @Query("SELECT * FROM receiver_profiles ORDER BY lastUsedAt DESC, useCount DESC, name")
    fun observeReceiverProfiles(): Flow<List<ReceiverProfileEntity>>

    @Query("SELECT * FROM receiver_profiles ORDER BY lastUsedAt DESC, useCount DESC, name")
    suspend fun getReceiverProfiles(): List<ReceiverProfileEntity>

    @Query("SELECT * FROM receiver_profiles WHERE id = :id")
    suspend fun getReceiverProfile(id: String): ReceiverProfileEntity?

    @Query("SELECT * FROM receiver_profiles WHERE normalizedName = :normalizedName ORDER BY lastUsedAt DESC, useCount DESC")
    suspend fun getReceiverProfilesByName(normalizedName: String): List<ReceiverProfileEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceiverProfile(profile: ReceiverProfileEntity)

    @Update suspend fun updateReceiverProfile(profile: ReceiverProfileEntity)

    @Delete suspend fun deleteReceiverProfile(profile: ReceiverProfileEntity)

    @Query("UPDATE records SET receiverProfileId = :targetId WHERE receiverProfileId = :sourceId")
    suspend fun replaceReceiverProfileLinks(sourceId: String, targetId: String)

    @Query("SELECT * FROM goods_profiles ORDER BY lastUsedAt DESC, useCount DESC, name")
    fun observeGoodsProfiles(): Flow<List<GoodsProfileEntity>>

    @Query("SELECT * FROM goods_profiles ORDER BY lastUsedAt DESC, useCount DESC, name")
    suspend fun getGoodsProfiles(): List<GoodsProfileEntity>

    @Query("SELECT * FROM goods_profiles WHERE id = :id")
    suspend fun getGoodsProfile(id: String): GoodsProfileEntity?

    @Query("SELECT * FROM goods_profiles WHERE normalizedName = :normalizedName ORDER BY lastUsedAt DESC, useCount DESC")
    suspend fun getGoodsProfilesByName(normalizedName: String): List<GoodsProfileEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGoodsProfile(profile: GoodsProfileEntity)

    @Update suspend fun updateGoodsProfile(profile: GoodsProfileEntity)

    @Delete suspend fun deleteGoodsProfile(profile: GoodsProfileEntity)

    @Query("UPDATE records SET goodsProfileId = :targetId WHERE goodsProfileId = :sourceId")
    suspend fun replaceGoodsProfileLinks(sourceId: String, targetId: String)

    @Query("SELECT * FROM profile_learning_state WHERE `key` = :key")
    suspend fun getProfileLearningState(key: String): ProfileLearningStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProfileLearningState(state: ProfileLearningStateEntity)

    @Transaction
    suspend fun bumpRevision(batchId: String) {
        val batch = getBatch(batchId) ?: return
        updateBatch(batch.copy(dataRevision = Revision.next(batch.dataRevision), updatedAt = System.currentTimeMillis()))
    }
}
