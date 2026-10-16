package com.example.gesturesdk

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SDK-owned camera surface. It uses the standard Android CameraX stack and does not depend on a
 * vendor XR runtime. The front camera is selected because the Android XR emulator maps webcam0 to
 * its front-facing Camera2 device.
 */
class GestureCameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var captureReady = CompletableDeferred<ImageCapture>()

    init {
        addView(
            previewView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(::bindCamera)
    }

    override fun onDetachedFromWindow() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        super.onDetachedFromWindow()
    }

    internal suspend fun captureTemporaryFile(): File {
        val imageCapture = withTimeout(CAMERA_READY_TIMEOUT_MS) {
            captureReady.await()
        }
        withContext(Dispatchers.Main.immediate) {
            imageCapture.targetRotation = display?.rotation ?: Surface.ROTATION_0
        }

        val outputDirectory = File(context.cacheDir, CAMERA_CACHE_DIRECTORY).apply {
            check(exists() || mkdirs()) { "无法创建摄像头临时目录" }
        }
        val outputFile = File.createTempFile("gesture-", ".jpg", outputDirectory)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        return suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                outputOptions,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (continuation.isActive) {
                            continuation.resume(outputFile)
                        } else {
                            outputFile.delete()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        outputFile.delete()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException(
                                    "摄像头拍照失败：${exception.message ?: exception.imageCaptureError}",
                                    exception,
                                ),
                            )
                        }
                    }
                },
            )
        }
    }

    private fun bindCamera() {
        if (!isAttachedToWindow) return
        if (captureReady.isCompleted) captureReady = CompletableDeferred()

        val lifecycleOwner = findViewTreeLifecycleOwner()
        if (lifecycleOwner == null) {
            captureReady.completeExceptionally(
                IllegalStateException("GestureCameraView 必须放在具有 LifecycleOwner 的界面中"),
            )
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(display?.rotation ?: Surface.ROTATION_0)
                        .build()

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        imageCapture,
                    )
                    cameraProvider = provider
                    imageCapture
                }.onSuccess { imageCapture ->
                    captureReady.complete(imageCapture)
                }.onFailure { error ->
                    captureReady.completeExceptionally(
                        IllegalStateException(
                            "无法启动前置摄像头，请确认相机权限和 webcam0 配置",
                            error,
                        ),
                    )
                }
            },
            mainExecutor,
        )
    }

    private companion object {
        const val CAMERA_READY_TIMEOUT_MS = 15_000L
        const val CAMERA_CACHE_DIRECTORY = "gesture-camera"
    }
}
