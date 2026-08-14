package com.goings.kaidanzhushou.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.goings.kaidanzhushou.AppContainer
import com.goings.kaidanzhushou.domain.EditableFields
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(private val container: AppContainer) : ViewModel() {
    val batches = container.repository.observeBatches()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    fun batch(id: String) = container.repository.observeBatch(id)
    fun records(batchId: String) = container.repository.observeRecords(batchId)
    fun record(id: String) = container.repository.observeRecord(id)
    fun exports(batchId: String) = container.repository.observeExports(batchId)
    fun hasApiKey() = container.apiKeyStore.hasKey()
    fun newCameraFile(batchId: String) = container.repository.newCameraFile(batchId)

    fun createBatch(name: String, done: (String) -> Unit) = viewModelScope.launch {
        runCatching { container.repository.createBatch(name) }.onSuccess(done).onFailure(::error)
    }

    fun renameBatch(id: String, name: String) = viewModelScope.launch {
        runCatching { container.repository.renameBatch(id, name) }.onFailure(::error)
    }

    fun deleteBatch(id: String, done: () -> Unit) = viewModelScope.launch {
        container.recognitionManager.cancel(id)
        runCatching { container.repository.deleteBatch(id) }.onSuccess { done() }.onFailure(::error)
    }

    fun importPhotos(batchId: String, uris: List<Uri>) = viewModelScope.launch {
        runCatching { container.repository.addImported(batchId, uris) }
            .onSuccess { _events.emit("已导入 $it 张照片") }.onFailure(::error)
    }

    fun addCaptured(batchId: String, file: File, done: () -> Unit = {}) = viewModelScope.launch {
        runCatching { container.repository.addCaptured(batchId, file) }
            .onSuccess { _events.emit("照片已保存"); done() }.onFailure(::error)
    }

    fun startRecognition(batchId: String, retryFailed: Boolean = false) = viewModelScope.launch {
        if (!hasApiKey()) { _events.emit("请先在设置中填写 Kimi API Key"); return@launch }
        container.recognitionManager.start(batchId, retryFailed)
    }

    fun pauseRecognition(batchId: String) = viewModelScope.launch { container.recognitionManager.pause(batchId) }

    fun updateRecord(id: String, fields: EditableFields, changed: Set<String>) = viewModelScope.launch {
        runCatching { container.repository.updateFields(id, fields, changed) }.onFailure(::error)
    }

    fun confirmRecord(id: String, done: () -> Unit) = viewModelScope.launch {
        val issues = container.repository.confirm(id)
        if (issues.isEmpty()) { _events.emit("记录已确认"); done() } else _events.emit(issues.joinToString("；"))
    }

    fun saveAndConfirm(id: String, fields: EditableFields, changed: Set<String>, done: () -> Unit) = viewModelScope.launch {
        runCatching {
            container.repository.updateFields(id, fields, changed)
            container.repository.confirm(id)
        }.onSuccess { issues ->
            if (issues.isEmpty()) { _events.emit("记录已确认"); done() } else _events.emit(issues.joinToString("；"))
        }.onFailure(::error)
    }

    fun export(batchId: String, done: (File) -> Unit) = viewModelScope.launch {
        runCatching { container.exportService.export(batchId) }.onSuccess(done).onFailure(::error)
    }

    fun saveApiKey(value: String) {
        runCatching { container.apiKeyStore.save(value) }
            .onSuccess { _events.tryEmit("API Key 已安全保存") }.onFailure(::error)
    }

    fun clearApiKey() { container.apiKeyStore.clear(); _events.tryEmit("API Key 已清除") }

    private fun error(error: Throwable) { _events.tryEmit(error.message ?: "操作失败") }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
    }
}
