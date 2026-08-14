package com.goings.kaidanzhushou.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File

@Composable
fun CameraScreen(viewModel: MainViewModel, batchId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var flash by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var captured by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }

    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) AndroidView(factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()
                        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        imageCapture = capture
                    }.onFailure { error = "相机启动失败" }
                }, ContextCompat.getMainExecutor(ctx))
            }
        }, modifier = Modifier.fillMaxSize()) else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("需要相机权限才能连续拍照", color = Color.White)
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 14.dp)) { Text("授予权限") }
            }
        }

        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 42.dp, start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White) }
            Text("连续拍照", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { flash = !flash }) { Icon(if (flash) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "闪光灯", tint = Color.White) }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .72f)).padding(bottom = 28.dp, top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("已拍 $captured 张 · 最多 100 张", color = Color.White)
            Text(error ?: if (capturing) "正在保存…" else "请让托运单尽量铺满取景框", color = if (error == null) Color.LightGray else Color(0xFFFF8C8C), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.size(78.dp).border(5.dp, Color.White, CircleShape).padding(7.dp).background(if (capturing) Color.Gray else Color.White, CircleShape).clickable(enabled = granted && !capturing && captured < 100) {
                val capture = imageCapture ?: return@clickable
                capturing = true
                error = null
                capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                val file = viewModel.newCameraFile(batchId)
                capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        viewModel.addCaptured(batchId, file) { captured++; capturing = false }
                    }
                    override fun onError(exception: ImageCaptureException) { file.delete(); capturing = false; error = "拍摄失败，请重试" }
                })
            })
            Spacer(Modifier.height(12.dp))
            Button(onClick = onDone) { Text("完成并返回") }
        }
    }

    DisposableEffect(Unit) {
        onDispose { if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() } }
    }
}
