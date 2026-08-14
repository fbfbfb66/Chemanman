package com.goings.kaidanzhushou.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.goings.kaidanzhushou.KaidanApplication
import com.goings.kaidanzhushou.data.local.RecordEntity
import com.goings.kaidanzhushou.data.remote.KimiErrorKind
import com.goings.kaidanzhushou.data.remote.KimiException
import com.goings.kaidanzhushou.domain.RecognitionStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File

class RecognitionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as KaidanApplication).container
    private val dao = container.database.dao()
    private val controller = AdaptiveConcurrency()

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID) ?: return Result.failure()
        setForeground(foreground("正在准备识别"))
        dao.moveRecognitionStates(batchId, listOf(RecognitionStatus.PREPARING, RecognitionStatus.IN_FLIGHT, RecognitionStatus.RETRY_WAIT), RecognitionStatus.QUEUED)
        while (!isStopped) {
            val batch = dao.getBatch(batchId) ?: return Result.failure()
            if (batch.recognitionPaused) return Result.success()
            val pending = dao.recordsWithStatus(batchId, listOf(RecognitionStatus.QUEUED)).take(controller.limit)
            if (pending.isEmpty()) break
            setForeground(foreground("正在并行识别 ${pending.size} 张托运单"))
            coroutineScope { pending.map { async { process(it) } }.awaitAll() }
        }
        return Result.success()
    }

    private suspend fun process(initial: RecordEntity) {
        var record = initial
        for (attempt in (record.attemptCount + 1)..3) {
            val apiKey = container.apiKeyStore.read()
            if (apiKey.isNullOrBlank()) {
                pause(record.batchId, record, "请先在设置中填写 Kimi API Key")
                return
            }
            val upload = record.uploadPath?.let(::File)
            if (upload == null || !upload.exists()) {
                dao.updateRecord(record.copy(recognitionStatus = RecognitionStatus.FAILED, errorMessage = "上传图片不存在"))
                return
            }
            record = record.copy(recognitionStatus = RecognitionStatus.IN_FLIGHT, attemptCount = attempt, errorMessage = null)
            dao.updateRecord(record)
            try {
                val draft = container.kimiClient.recognize(apiKey, upload)
                container.repository.applyDraft(record.id, draft)
                controller.success()
                return
            } catch (error: KimiException) {
                controller.failure(error.kind)
                if (RetryPolicy.pausesBatch(error.kind)) {
                    pause(record.batchId, record, error.message ?: "识别已暂停")
                    return
                }
                if (!RetryPolicy.isRetryable(error.kind) || attempt >= 3) {
                    dao.updateRecord(record.copy(recognitionStatus = RecognitionStatus.FAILED, errorMessage = error.message))
                    return
                }
                dao.updateRecord(record.copy(recognitionStatus = RecognitionStatus.RETRY_WAIT, errorMessage = error.message))
                delay(RetryPolicy.delayMillis(attempt, error.retryAfterMillis))
            } catch (_: Exception) {
                dao.updateRecord(record.copy(recognitionStatus = RecognitionStatus.FAILED, errorMessage = "识别过程中发生本地错误"))
                return
            }
        }
    }

    private suspend fun pause(batchId: String, record: RecordEntity, message: String) {
        dao.updateRecord(record.copy(recognitionStatus = RecognitionStatus.FAILED, errorMessage = message))
        dao.getBatch(batchId)?.let { dao.updateBatch(it.copy(recognitionPaused = true, updatedAt = System.currentTimeMillis())) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("正在识别托运单")

    private fun foreground(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "AI 识别进度", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(com.goings.kaidanzhushou.R.drawable.ic_app)
            .setContentTitle("开单助手")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_BATCH_ID = "batch_id"
        private const val CHANNEL_ID = "recognition"
        private const val NOTIFICATION_ID = 8127
        fun input(batchId: String) = Data.Builder().putString(KEY_BATCH_ID, batchId).build()
    }
}
