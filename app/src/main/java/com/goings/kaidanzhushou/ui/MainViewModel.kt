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
import com.goings.kaidanzhushou.data.local.ExportEntity

class MainViewModel(private val container: AppContainer) : ViewModel() {
    val batches = container.repository.observeBatches()
    private val _events = MutableSharedFlow<UiNotice>(extraBufferCapacity = 4)
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

    fun createDatedBatch(done: (String) -> Unit) = viewModelScope.launch {
        runCatching { container.repository.createDatedBatch() }.onSuccess(done).onFailure(::error)
    }

    fun renameBatch(id: String, name: String) = viewModelScope.launch {
        runCatching { container.repository.renameBatch(id, name) }.onFailure(::error)
    }

    fun deleteBatch(id: String, done: () -> Unit) = viewModelScope.launch {
        container.recognitionManager.cancel(id)
        runCatching { container.repository.deleteBatch(id) }.onSuccess { done() }.onFailure(::error)
    }

    fun deleteBatches(ids: Set<String>, done: () -> Unit = {}) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        ids.forEach { container.recognitionManager.cancel(it) }
        runCatching { ids.forEach { container.repository.deleteBatch(it) } }
            .onSuccess { _events.emit(UiNotice.success("已删除 ${ids.size} 个照片集")); done() }
            .onFailure(::error)
    }

    fun deleteRecords(batchId: String, ids: Set<String>, done: () -> Unit = {}) = viewModelScope.launch {
        runCatching { container.repository.deleteRecords(batchId, ids) }
            .onSuccess { _events.emit(UiNotice.success("已删除 $it 张照片")); done() }
            .onFailure(::error)
    }

    fun importPhotos(batchId: String, uris: List<Uri>) = viewModelScope.launch {
        runCatching { container.repository.addImported(batchId, uris) }
            .onSuccess { _events.emit(UiNotice.success("已导入 $it 张照片")) }.onFailure(::error)
    }

    fun addCaptured(batchId: String, file: File, done: (Boolean) -> Unit = {}) = viewModelScope.launch {
        runCatching { container.repository.addCaptured(batchId, file) }
            .onSuccess { _events.emit(UiNotice.success("照片已保存")); done(true) }
            .onFailure { error(it); done(false) }
    }

    fun startRecognition(batchId: String, retryFailed: Boolean = false) = viewModelScope.launch {
        if (!hasApiKey()) { _events.emit(UiNotice.warning("请先设置 Kimi API Key")); return@launch }
        container.recognitionManager.start(batchId, retryFailed)
    }

    fun pauseRecognition(batchId: String) = viewModelScope.launch { container.recognitionManager.pause(batchId) }

    fun updateRecord(id: String, fields: EditableFields, changed: Set<String>) = viewModelScope.launch {
        runCatching { container.repository.updateFields(id, fields, changed) }.onFailure(::error)
    }

    fun confirmRecord(id: String, done: () -> Unit) = viewModelScope.launch {
        val issues = container.repository.confirm(id)
        if (issues.isEmpty()) { _events.emit(UiNotice.success("已确认")); done() } else _events.emit(UiNotice.warning(issues.joinToString("；")))
    }

    fun saveAndConfirm(id: String, fields: EditableFields, changed: Set<String>, done: () -> Unit) = viewModelScope.launch {
        runCatching {
            container.repository.updateFields(id, fields, changed)
            container.repository.confirm(id)
        }.onSuccess { issues ->
            if (issues.isEmpty()) { _events.emit(UiNotice.success("已确认")); done() } else _events.emit(UiNotice.warning(issues.joinToString("；")))
        }.onFailure(::error)
    }

    fun export(batchId: String, done: (ExportEntity) -> Unit = {}) = viewModelScope.launch {
        runCatching { container.exportService.export(batchId) }
            .onSuccess { _events.emit(UiNotice.success("已保存到 下载/开单助手")); done(it) }
            .onFailure(::error)
    }

    fun saveApiKey(value: String) {
        runCatching { container.apiKeyStore.save(value) }
            .onSuccess { _events.tryEmit(UiNotice.success("API Key 已保存")) }.onFailure(::error)
    }

    fun clearApiKey() { container.apiKeyStore.clear(); _events.tryEmit(UiNotice.success("API Key 已清除")) }

    fun showWarning(message: String) { _events.tryEmit(UiNotice.warning(message)) }

    private fun error(error: Throwable) { _events.tryEmit(UiNotice.error(error.message ?: "操作失败")) }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
    }
}

enum class NoticeKind { SUCCESS, WARNING, ERROR }
data class UiNotice(val text: String, val kind: NoticeKind) {
    companion object {
        fun success(text: String) = UiNotice(text, NoticeKind.SUCCESS)
        fun warning(text: String) = UiNotice(text, NoticeKind.WARNING)
        fun error(text: String) = UiNotice(text, NoticeKind.ERROR)
    }
}
