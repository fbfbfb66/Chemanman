package com.goings.kaidanzhushou.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.goings.kaidanzhushou.data.local.KaidanDao
import com.goings.kaidanzhushou.domain.RecognitionStatus

class RecognitionManager(private val workManager: WorkManager, private val dao: KaidanDao) {
    suspend fun start(batchId: String, retryFailed: Boolean = false) {
        val batch = dao.getBatch(batchId) ?: return
        dao.updateBatch(batch.copy(recognitionPaused = false, updatedAt = System.currentTimeMillis()))
        val from = buildList {
            add(RecognitionStatus.UNRECOGNIZED)
            if (retryFailed) add(RecognitionStatus.FAILED)
        }
        dao.moveRecognitionStates(batchId, from, RecognitionStatus.QUEUED)
        val request = OneTimeWorkRequestBuilder<RecognitionWorker>().setInputData(RecognitionWorker.input(batchId)).build()
        workManager.enqueueUniqueWork("recognize-$batchId", ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun pause(batchId: String) {
        dao.getBatch(batchId)?.let { dao.updateBatch(it.copy(recognitionPaused = true, updatedAt = System.currentTimeMillis())) }
    }

    fun cancel(batchId: String) = workManager.cancelUniqueWork("recognize-$batchId")
}
