package com.example.gestureconsumer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.gesturesdk.GestureClient
import com.example.gesturesdk.RuntimeCameraStatus
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ConsumerTheme {
                ConsumerScreen(onReturnToHost = ::returnToHost)
            }
        }
    }

    private fun returnToHost() {
        val intent = packageManager.getLaunchIntentForPackage(APP_A_PACKAGE)
        if (intent == null) {
            finish()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(intent)
    }
}

@Composable
private fun ConsumerScreen(onReturnToHost: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isSideBySideDisplay =
        configuration.screenWidthDp >= configuration.screenHeightDp * 2
    val client = remember {
        GestureClient(context, servicePackageName = SERVICE_HOST_PACKAGE)
    }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<RuntimeCameraStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(client) {
        onDispose(client::close)
    }

    LaunchedEffect(client) {
        while (true) {
            runCatching { client.getRuntimeRecognitionStatus() }
                .onSuccess {
                    status = it
                    error = null
                }
                .onFailure {
                    error = it.message ?: "无法连接独立手势服务"
                }
            delay(STATUS_POLL_INTERVAL_MS)
        }
    }

    fun setStreaming(start: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                if (start) {
                    client.startRuntimeRecognition()
                } else {
                    client.stopRuntimeRecognition()
                }
            }.onSuccess {
                status = it
            }.onFailure {
                error = it.message ?: "手势服务请求失败"
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = if (isSideBySideDisplay) 0.dp else 24.dp,
                vertical = 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConsumerServiceStatus()
        ConsumerPanel(
            status = status,
            error = error,
            busy = busy,
            onToggle = { setStreaming(status?.streaming != true) },
            onReturnToHost = onReturnToHost,
            isSideBySideDisplay = isSideBySideDisplay,
        )
    }
}

@Composable
private fun ConsumerServiceStatus() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(9.dp)
                .height(9.dp)
                .background(Color(0xFF16835E), RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Gesture Capability Service", style = MaterialTheme.typography.labelLarge)
            Text(
                "独立宿主进程 · 跨应用 Binder API",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("客户端 B", color = Color(0xFF0C6B4F), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ConsumerPanel(
    status: RuntimeCameraStatus?,
    error: String?,
    busy: Boolean,
    onToggle: () -> Unit,
    onReturnToHost: () -> Unit,
    isSideBySideDisplay: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                "跨应用实时手势识别",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Service Host: com.example.gesturehost",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (isSideBySideDisplay) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(2) {
                        ConsumerToggleButton(
                            status = status,
                            busy = busy,
                            onClick = onToggle,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(2) {
                        ReturnToAppButton(
                            onClick = onReturnToHost,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                ConsumerToggleButton(
                    status = status,
                    busy = busy,
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                ReturnToAppButton(
                    onClick = onReturnToHost,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (status == null) {
                Text("正在连接独立手势服务…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                StatusRow("Service", "已跨应用连接")
                StatusRow("状态", status.state)
                StatusRow("实时流", if (status.streaming) "STREAMING" else "IDLE")
                StatusRow("Runtime", if (status.runtimeConnected) "已连接" else "未连接")
                StatusRow(
                    "手势",
                    if (status.recognitionCount > 0) gestureDisplayName(status.gestureLabel) else "-",
                )
                StatusRow(
                    "置信度",
                    if (status.recognitionCount > 0) {
                        String.format(Locale.US, "%.1f%%", status.gestureConfidence * 100f)
                    } else {
                        "-"
                    },
                )
                StatusRow("图像帧", status.frameCount.toString())
                StatusRow("推理次数", status.recognitionCount.toString())
                StatusRow("最近控制", status.lastControlSource)
                if (status.recognitionError.isNotEmpty()) {
                    StatusRow("识别错误", status.recognitionError)
                }
            }
        }
    }
}

@Composable
private fun ConsumerToggleButton(
    status: RuntimeCameraStatus?,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(6.dp),
        colors = if (status?.streaming == true) {
            ButtonDefaults.filledTonalButtonColors()
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                if (status?.streaming == true) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (status?.streaming == true) "停止实时识别" else "启动实时识别")
        }
    }
}

@Composable
private fun ReturnToAppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.filledTonalButtonColors(),
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("返回应用 A")
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(76.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
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
    "None" -> "未识别"
    else -> label
}

@Composable
private fun ConsumerTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF176B5B),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFA8F2DB),
        onPrimaryContainer = Color(0xFF002019),
        background = Color(0xFFF7F8FA),
        surface = Color.White,
        surfaceContainer = Color(0xFFEFF1F3),
        outline = Color(0xFFC4C8C7),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

private const val SERVICE_HOST_PACKAGE = "com.example.gesturehost"
private const val APP_A_PACKAGE = "com.example.xrgesturedemo"
private const val STATUS_POLL_INTERVAL_MS = 500L
