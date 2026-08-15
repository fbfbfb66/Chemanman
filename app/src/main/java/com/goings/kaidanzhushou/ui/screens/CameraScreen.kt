package com.goings.kaidanzhushou.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.OrientationEventListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CameraScreen(viewModel: MainViewModel, batchId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val records by viewModel.records(batchId).collectAsStateWithLifecycle(emptyList())
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraPreview by remember { mutableStateOf<Preview?>(null) }
    var useCaseRotation by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    providerFuture.addListener({
                        runCatching {
                            val provider = providerFuture.get()
                            val rotation = display.rotation
                            useCaseRotation = rotation
                            val capture = ImageCapture.Builder()
                                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                                .setTargetRotation(rotation)
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .build()
                            val preview = Preview.Builder()
                                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                                .setTargetRotation(rotation)
                                .build()
                                .also { it.surfaceProvider = surfaceProvider }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                            imageCapture = capture
                            cameraPreview = preview
                        }.onFailure { error = "相机启动失败" }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("需要相机权限才能拍照", color = Color.White)
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 14.dp)) { Text("授予权限") }
            }
        }

        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 22.dp, start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("连续拍照", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text("已拍 ${records.size} 张", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { flash = !flash }) { Icon(if (flash) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "闪光灯", tint = Color.White) }
        }
        error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 82.dp).background(Color(0xCCD92E2E), CircleShape).padding(horizontal = 14.dp, vertical = 7.dp))
        }
        ShutterButton(capturing || records.size >= 100, Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) {
            val capture = imageCapture ?: return@ShutterButton
            capturing = true
            error = null
            capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            val file = viewModel.newCameraFile(batchId)
            capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    viewModel.addCaptured(batchId, file) { capturing = false }
                }
                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    capturing = false
                    error = "拍摄失败，请重试"
                }
            })
        }
    }

    DisposableEffect(Unit) {
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                @Suppress("DEPRECATION")
                val rotation = activity?.windowManager?.defaultDisplay?.rotation ?: return
                if (rotation == useCaseRotation) return
                useCaseRotation = rotation
                imageCapture?.targetRotation = rotation
                cameraPreview?.targetRotation = rotation
            }
        }.apply { enable() }
        onDispose {
            orientationListener.disable()
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
        }
    }
}

@Composable
private fun ShutterButton(disabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).background(if (disabled) Color.Gray else Color.White, CircleShape).clickable(enabled = !disabled, onClick = onClick))
}
