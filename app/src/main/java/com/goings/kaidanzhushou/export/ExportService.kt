package com.goings.kaidanzhushou.export

import android.content.Context
import com.goings.kaidanzhushou.data.BatchRepository
import com.goings.kaidanzhushou.data.local.ExportEntity
import com.goings.kaidanzhushou.domain.ReviewStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExportService(
    private val context: Context,
    private val repository: BatchRepository,
    private val exporter: XlsxExporter,
) {
    suspend fun export(batchId: String): File = withContext(Dispatchers.IO) {
        val batch = repository.dao.getBatch(batchId) ?: error("照片集不存在")
        val records = repository.dao.getRecords(batchId)
        require(records.isNotEmpty()) { "照片集没有可导出的记录" }
        require(records.all { it.reviewStatus == ReviewStatus.CONFIRMED }) { "所有记录人工确认后才能导出" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = batch.name.replace(Regex("[\\/:*?\"<>|]"), "_").take(40)
        val file = File(context.filesDir, "exports/${safeName}_$stamp.xlsx")
        exporter.write(file, batch, records)
        repository.saveExport(ExportEntity(
            id = UUID.randomUUID().toString(), batchId = batchId, fileName = file.name,
            exportedAt = System.currentTimeMillis(), recordCount = records.size,
            dataRevision = batch.dataRevision, localPath = file.absolutePath,
        ))
        file
    }
}
