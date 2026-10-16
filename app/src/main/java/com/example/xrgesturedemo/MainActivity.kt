package com.example.xrgesturedemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.gesturesdk.GestureCameraView
import com.example.gesturesdk.GestureClient
import com.example.gesturesdk.GestureResult
import com.example.gesturesdk.RuntimeCameraStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var exitGestureDetector: GestureDetector
    private var exitInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exitGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    exitAfterStoppingCamera("DOUBLE_TAP")
                    return true
                }
            },
        )
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    exitAfterStoppingCamera("SYSTEM_BACK")
                }
            },
        )
        setContent {
            GestureDemoTheme {
                GestureDemoScreen()
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        exitGestureDetector.onTouchEvent(event)
        return if (exitInProgress) true else super.dispatchTouchEvent(event)
    }

    private fun exitAfterStoppingCamera(source: String) {
        if (exitInProgress) return
        exitInProgress = true
        Log.i(UI_TAG, "exit requested source=$source")
        lifecycleScope.launch {
            val client = GestureClient(
                this@MainActivity,
                servicePackageName = SERVICE_HOST_PACKAGE,
            )
            try {
                withTimeout(EXIT_CAMERA_TIMEOUT_MS) {
                    client.stopRuntimeRecognition()
                }
                Log.i(UI_TAG, "camera stopped before exit")
            } catch (error: Throwable) {
                Log.w(UI_TAG, "camera stop failed during exit; destroying service", error)
            } finally {
                client.close()
                finishAndRemoveTask()
            }
        }
    }
}

private sealed interface RecognitionState {
    data object Empty : RecognitionState
    data object Running : RecognitionState
    data class Success(val result: GestureResult) : RecognitionState
    data class Failure(val message: String) : RecognitionState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureDemoScreen() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isSideBySideDisplay =
        configuration.screenWidthDp >= configuration.screenHeightDp * 2
    val scope = rememberCoroutineScope()
    val client = remember {
        GestureClient(context, servicePackageName = SERVICE_HOST_PACKAGE)
    }
    var cameraView by remember { mutableStateOf<GestureCameraView?>(null) }
    var cameraXEnabled by remember { mutableStateOf(false) }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var state by remember { mutableStateOf<RecognitionState>(RecognitionState.Empty) }
    var runtimeStatus by remember { mutableStateOf<RuntimeCameraStatus?>(null) }
    var runtimePreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var runtimeBusy by remember { mutableStateOf(false) }
    var pendingRuntimeOperation by remember { mutableStateOf<String?>(null) }
    var runtimeError by remember { mutableStateOf<String?>(null) }
    var consumerLaunchError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(client) {
        onDispose(client::close)
    }

    val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        cameraPermissionGranted = granted
        if (!granted) {
            state = RecognitionState.Failure("需要相机权限才能从摄像头识别手势")
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun recognize() {
        val camera = cameraView
        if (!cameraPermissionGranted || camera == null) {
            state = RecognitionState.Failure("摄像头尚未就绪")
            return
        }
        state = RecognitionState.Running
        scope.launch {
            state = runCatching { client.recognize(camera) }
                .fold(
                    onSuccess = { RecognitionState.Success(it) },
                    onFailure = { RecognitionState.Failure(it.message ?: "摄像头识别失败") },
                )
        }
    }

    fun updateRuntimeProbe(
        operationName: String,
        operation: suspend () -> RuntimeCameraStatus,
    ) {
        Log.i(UI_TAG, "runtimeOperation=$operationName requested busy=$runtimeBusy")
        if (runtimeBusy) return
        runtimeBusy = true
        pendingRuntimeOperation = operationName
        runtimeError = null
        scope.launch {
            runCatching { operation() }
                .onSuccess { runtimeStatus = it }
                .onFailure { runtimeError = it.message ?: "Runtime 相机请求失败" }
            runtimeBusy = false
            pendingRuntimeOperation = null
        }
    }

    fun openConsumerApp() {
        consumerLaunchError = null
        runCatching {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(CONSUMER_APP_PACKAGE)
                ?: error("未找到应用 B，请先安装 consumer-app-debug.apk")
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(launchIntent)
        }.onFailure {
            consumerLaunchError = it.message ?: "无法打开应用 B"
        }
    }

    LaunchedEffect(client) {
        while (true) {
            runCatching { client.getRuntimeRecognitionStatus() }
                .onSuccess {
                    runtimeStatus = it
                    runtimeError = null
                }
                .onFailure { runtimeError = it.message ?: "无法刷新 Runtime 相机状态" }
            delay(RUNTIME_STATUS_POLL_INTERVAL_MS)
        }
    }

    LaunchedEffect(runtimeStatus?.previewTimestampNanos) {
        val jpeg = runtimeStatus?.previewJpeg ?: ByteArray(0)
        runtimePreview = if (jpeg.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
            }
        }
    }

    LaunchedEffect(runtimeStatus?.streaming) {
        if (runtimeStatus?.streaming == true) {
            cameraXEnabled = false
            cameraView = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Gesture Link", fontWeight = FontWeight.SemiBold)
                        Text(
                            "摄像头到 XR 应用的扩展能力链路",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (isSideBySideDisplay) 0.dp else 24.dp,
                    vertical = 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServiceStatus()
            RuntimeCameraPanel(
                status = runtimeStatus,
                busy = runtimeBusy,
                pendingOperation = pendingRuntimeOperation,
                error = runtimeError,
                onStart = {
                    cameraXEnabled = false
                    cameraView = null
                    updateRuntimeProbe("START", client::startRuntimeRecognition)
                },
                onStop = { updateRuntimeProbe("STOP", client::stopRuntimeRecognition) },
                onRefresh = {
                    updateRuntimeProbe("REFRESH", client::getRuntimeRecognitionStatus)
                },
                onOpenConsumer = ::openConsumerApp,
                consumerLaunchError = consumerLaunchError,
            )
            InputPanel(
                enabled = cameraXEnabled,
                canEnable = runtimeStatus?.streaming != true,
                runtimeStatus = runtimeStatus,
                runtimePreview = runtimePreview,
                cameraPermissionGranted = cameraPermissionGranted,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onCameraReady = { cameraView = it },
                onEnabledChange = { enabled ->
                    cameraXEnabled = enabled
                    if (!enabled) cameraView = null
                },
            )
            Button(
                onClick = ::recognize,
                enabled = cameraPermissionGranted &&
                    cameraView != null &&
                    state !is RecognitionState.Running,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(6.dp),
            ) {
                if (state is RecognitionState.Running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("识别中")
                } else {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(if (state is RecognitionState.Success) "重新拍照识别" else "拍照并识别")
                }
            }
            ResultPanel(state)
        }
    }
}

@Composable
private fun ServiceStatus() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .background(Color(0xFF16835E), RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Gesture Capability Service", style = MaterialTheme.typography.labelLarge)
            Text(
                "Runtime/CameraX 输入 · 独立进程 · Binder API",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("就绪", color = Color(0xFF0C6B4F), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RuntimeToggleButton(
    streaming: Boolean,
    busy: Boolean,
    pendingOperation: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    controlHeight: Dp = 48.dp,
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.height(controlHeight),
        shape = RoundedCornerShape(6.dp),
        colors = if (streaming) {
            ButtonDefaults.filledTonalButtonColors()
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = if (streaming) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                contentDescription = null,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when (pendingOperation) {
                "START" -> "正在启动"
                "STOP" -> "正在停止"
                else -> if (streaming) "停止实时识别" else "启动实时识别"
            },
        )
    }
}

@Composable
private fun RuntimeCameraPanel(
    status: RuntimeCameraStatus?,
    busy: Boolean,
    pendingOperation: String?,
    error: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onOpenConsumer: () -> Unit,
    consumerLaunchError: String?,
) {
    val configuration = LocalConfiguration.current
    val isSideBySideDisplay =
        configuration.screenWidthDp >= configuration.screenHeightDp * 2
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Runtime 实时手势识别",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "XRCamera 0 · YUV420 · 640x400@30fps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!isSideBySideDisplay) {
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新 Runtime 状态")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (isSideBySideDisplay) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(2) {
                        RuntimeToggleButton(
                            streaming = status?.streaming == true,
                            busy = busy,
                            pendingOperation = pendingOperation,
                            onClick = if (status?.streaming == true) onStop else onStart,
                            modifier = Modifier.weight(1f),
                            controlHeight = 64.dp,
                        )
                    }
                }
            } else {
                RuntimeToggleButton(
                    streaming = status?.streaming == true,
                    busy = busy,
                    pendingOperation = pendingOperation,
                    onClick = if (status?.streaming == true) onStop else onStart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
            if (isSideBySideDisplay) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(2) {
                        OpenConsumerButton(
                            onClick = onOpenConsumer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                OpenConsumerButton(
                    onClick = onOpenConsumer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (consumerLaunchError != null) {
                Spacer(Modifier.height(8.dp))
                Text(consumerLaunchError, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
            } else if (status == null) {
                Text(
                    "单击镜腿启动实时识别",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                RuntimeStatusRows(status)
            }
        }
    }
}

@Composable
private fun OpenConsumerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    controlHeight: Dp = 56.dp,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(controlHeight),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.filledTonalButtonColors(),
    ) {
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("打开应用 B")
    }
}

@Composable
private fun RuntimeStatusRows(status: RuntimeCameraStatus) {
    ResultRow(
        "实时手势",
        when {
            status.recognitionCount > 0 -> gestureDisplayName(status.gestureLabel)
            status.streaming -> "等待识别结果"
            else -> "-"
        },
    )
    ResultRow(
        "置信度",
        if (status.recognitionCount > 0) {
            String.format(Locale.US, "%.1f%%", status.gestureConfidence * 100f)
        } else {
            "-"
        },
    )
    ResultRow("推理次数", status.recognitionCount.toString())
    ResultRow(
        "推理耗时",
        status.gestureInferenceTimeMs.takeIf { it > 0 }?.let { "$it ms" } ?: "-",
    )
    if (status.recognitionError.isNotEmpty()) {
        ResultRow("识别错误", status.recognitionError)
    }
    ResultRow("状态", status.state)
    ResultRow("详情", status.detail)
    ResultRow("Runtime", if (status.runtimeConnected) "已连接" else "未连接")
    ResultRow("镜腿控制", if (status.templeControlReady) "已就绪" else "未就绪")
    ResultRow("镜腿事件", "${status.templeEventCount} 次")
    ResultRow(
        "最近控制",
        when (status.lastControlSource) {
            "TEMPLE" -> "镜腿单击"
            "APP" -> "应用按钮"
            else -> "-"
        },
    )
    ResultRow("摄像头", if (status.cameraOpen) "${status.cameraId} 已打开" else "未打开")
    ResultRow(
        "设备列表",
        status.availableCameras.takeIf { it.isNotEmpty() }?.joinToString() ?: "未返回",
    )
    ResultRow(
        "支持尺寸",
        status.supportedResolutions.takeIf { it.isNotEmpty() }?.joinToString() ?: "未返回",
    )
    ResultRow("帧数", status.frameCount.toString())
    ResultRow(
        "帧尺寸",
        if (status.frameWidth > 0) "${status.frameWidth}x${status.frameHeight}" else "-",
    )
    ResultRow(
        "时间戳",
        status.frameTimestampNanos.takeIf { it > 0 }?.toString() ?: "-",
    )
    ResultRow("Checksum", status.frameChecksum.ifEmpty { "-" })
    ResultRow("平均 FPS", String.format(Locale.US, "%.1f", status.frameRate))
    ResultRow("相机方向", "${status.cameraOrientation}°")
    ResultRow(
        "预览帧",
        if (status.previewWidth > 0) {
            "${status.previewWidth}x${status.previewHeight}"
        } else {
            "-"
        },
    )
}

@Composable
private fun InputPanel(
    enabled: Boolean,
    canEnable: Boolean,
    runtimeStatus: RuntimeCameraStatus?,
    runtimePreview: ImageBitmap?,
    cameraPermissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onCameraReady: (GestureCameraView) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isSideBySideDisplay =
        configuration.screenWidthDp >= configuration.screenHeightDp * 2
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("摄像头取景", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (runtimeStatus?.streaming == true) {
                            "RayNeo Runtime · 实时预览"
                        } else {
                            "Android CameraX · 模拟器回归输入"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!enabled && runtimeStatus?.streaming != true) {
                    Button(
                        onClick = { onEnabledChange(true) },
                        enabled = canEnable,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("启用")
                    }
                } else if (!cameraPermissionGranted) {
                    Button(
                        onClick = onRequestPermission,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("授权")
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            BoxWithConstraints(
                modifier = if (isSideBySideDisplay) {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                },
                contentAlignment = Alignment.Center,
            ) {
                val previewSize = minOf(maxWidth, 420.dp)
                Box(
                    modifier = (if (isSideBySideDisplay) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.size(previewSize)
                    })
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center,
                ) {
                    if (runtimeStatus?.streaming == true) {
                        if (runtimePreview != null) {
                            if (isSideBySideDisplay) {
                                Row(Modifier.fillMaxSize()) {
                                    repeat(2) { eyeIndex ->
                                        Image(
                                            bitmap = runtimePreview,
                                            contentDescription = if (eyeIndex == 0) {
                                                "Runtime 摄像头左眼预览"
                                            } else {
                                                "Runtime 摄像头右眼预览"
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxSize(),
                                            contentScale = ContentScale.Fit,
                                        )
                                    }
                                }
                            } else {
                                Image(
                                    bitmap = runtimePreview,
                                    contentDescription = "Runtime 摄像头实时画面",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "正在等待 Runtime 预览帧",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else if (!enabled) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.CameraAlt,
                                contentDescription = null,
                                modifier = Modifier.size(38.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (canEnable) "CameraX 默认关闭" else "Runtime 相机正在运行",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (!cameraPermissionGranted) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.CameraAlt,
                                contentDescription = null,
                                modifier = Modifier.size(38.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "需要相机权限",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        AndroidView(
                            factory = { viewContext ->
                                GestureCameraView(viewContext).also(onCameraReady)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultPanel(state: RecognitionState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("识别结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))
            when (state) {
                RecognitionState.Empty -> Text(
                    "结果将在完成识别后显示",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RecognitionState.Running -> Text("能力服务正在处理图片…")
                is RecognitionState.Failure -> Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                )
                is RecognitionState.Success -> ResultRows(state.result)
            }
        }
    }
}

@Composable
private fun ResultRows(result: GestureResult) {
    val displayName = gestureDisplayName(result.label)
    ResultRow("手势", displayName)
    ResultRow("模型类别", result.label)
    ResultRow("置信度", String.format(Locale.US, "%.1f%%", result.confidence * 100f))
    ResultRow("左右手", handednessDisplayName(result.handedness))
    ResultRow("轮廓分支", "${result.featureCount} 个")
    ResultRow("推理耗时", "${result.inferenceTimeMs} ms")
    ResultRow("识别后端", result.engine)
    ResultRow("输入摘要", result.inputFingerprint)
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, modifier = Modifier.width(92.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
    }
}

private fun gestureDisplayName(label: String): String = when (label) {
    "Closed_Fist" -> "握拳"
    "Open_Palm" -> "张开手掌"
    "Pointing_Up" -> "食指向上"
    "Thumb_Down" -> "拇指向下"
    "Thumb_Up" -> "点赞"
    "Victory" -> "胜利手势"
    "ILoveYou" -> "我爱你手势"
    "None" -> "未识别到预设手势"
    else -> label
}

private fun handednessDisplayName(label: String): String = when (label) {
    "Left" -> "左手"
    "Right" -> "右手"
    else -> label
}

@Composable
private fun GestureDemoTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF176B5B),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFA8F2DB),
        onPrimaryContainer = Color(0xFF002019),
        secondary = Color(0xFF4C635D),
        background = Color(0xFFF7F8FA),
        surface = Color.White,
        surfaceContainer = Color(0xFFEFF1F3),
        surfaceContainerLow = Color(0xFFF2F4F5),
        outline = Color(0xFFC4C8C7),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

private const val RUNTIME_STATUS_POLL_INTERVAL_MS = 500L
private const val EXIT_CAMERA_TIMEOUT_MS = 5_000L
private const val UI_TAG = "GestureDemoUI"
private const val CONSUMER_APP_PACKAGE = "com.example.gestureconsumer"
private const val SERVICE_HOST_PACKAGE = "com.example.gesturehost"
