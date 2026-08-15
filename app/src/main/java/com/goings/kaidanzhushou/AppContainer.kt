package com.goings.kaidanzhushou

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.goings.kaidanzhushou.data.BatchRepository
import com.goings.kaidanzhushou.data.local.KaidanDatabase
import com.goings.kaidanzhushou.data.remote.KimiClient
import com.goings.kaidanzhushou.export.ExportService
import com.goings.kaidanzhushou.export.PublicExportWriter
import com.goings.kaidanzhushou.export.XlsxExporter
import com.goings.kaidanzhushou.image.ImageStore
import com.goings.kaidanzhushou.security.SecureApiKeyStore
import com.goings.kaidanzhushou.worker.RecognitionManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val database: KaidanDatabase = Room.databaseBuilder(context, KaidanDatabase::class.java, "kaidan.db")
        .addMigrations(KaidanDatabase.MIGRATION_1_2, KaidanDatabase.MIGRATION_2_3)
        .build()
    val imageStore = ImageStore(context)
    val repository = BatchRepository(database, imageStore)
    val apiKeyStore = SecureApiKeyStore(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false)
        .build()
    val kimiClient = KimiClient(http)
    val recognitionManager = RecognitionManager(WorkManager.getInstance(context), database.dao())
    val exportService = ExportService(context, repository, XlsxExporter(), PublicExportWriter(context))
}
