package com.example.gestureservice

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.example.gesturesdk.internal.GestureIpcContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GestureRecognitionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var runtimeCameraSource: RayNeoRuntimeCameraSource

    private val requestMessenger = Messenger(
        Handler(Looper.getMainLooper()) { request ->
            when (request.what) {
                GestureIpcContract.MSG_RECOGNIZE -> handleRecognitionRequest(request)
                GestureIpcContract.MSG_START_RUNTIME_CAMERA -> {
                    handleRuntimeCameraRequest(request.replyTo, start = true)
                    true
                }
                GestureIpcContract.MSG_STOP_RUNTIME_CAMERA -> {
                    handleRuntimeCameraRequest(request.replyTo, start = false)
                    true
                }
                GestureIpcContract.MSG_GET_RUNTIME_CAMERA_STATUS -> {
                    sendRuntimeCameraStatus(request.replyTo, runtimeCameraSource.snapshot())
                    true
                }
                else -> false
            }
        },
    )

    override fun onCreate() {
        super.onCreate()
        runtimeCameraSource = RayNeoRuntimeCameraSource(this) {
            serviceScope.launch { runtimeCameraSource.toggleFromTemple() }
        }
        serviceScope.launch { runtimeCameraSource.connect() }
    }

    override fun onBind(intent: Intent?): IBinder = requestMessenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Last client unbound; releasing Runtime camera and connection")
        serviceScope.cancel()
        runtimeCameraSource.close()
        return false
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runtimeCameraSource.close()
        super.onDestroy()
    }

    private fun handleRecognitionRequest(request: Message): Boolean {
        val image = request.data.parcelableImageDescriptor()
        val callback = request.replyTo
        if (image == null || callback == null) {
            sendError(callback, "识别请求缺少图片数据")
            return true
        }
        serviceScope.launch {
            runCatching { recognizeImage(image) }
                .onSuccess { result -> sendResult(callback, result) }
                .onFailure { error -> sendError(callback, error.message ?: "手势识别失败") }
        }
        return true
    }

    private fun handleRuntimeCameraRequest(callback: Messenger?, start: Boolean) {
        if (callback == null) return
        serviceScope.launch {
            val snapshot = if (start) {
                runtimeCameraSource.start()
            } else {
                runtimeCameraSource.stop()
            }
            sendRuntimeCameraStatus(callback, snapshot)
        }
    }

    private suspend fun recognizeImage(image: ParcelFileDescriptor): InternalGestureResult =
        withContext(Dispatchers.Default) {
            image.use { descriptor ->
                val bitmap = decodeOrientedBitmap(descriptor)
                try {
                    val startedAt = SystemClock.elapsedRealtime()
                    val analysis = DemoImageGestureRecognizer.recognize(bitmap)
                    InternalGestureResult(
                        label = analysis.label,
                        confidence = analysis.confidence,
                        handedness = "Unknown",
                        featureCount = analysis.featureCount,
                        inferenceTimeMs = SystemClock.elapsedRealtime() - startedAt,
                        engine = "Demo contour heuristic",
                        inputFingerprint = bitmap.inputFingerprint(),
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        }

    private fun sendResult(callback: Messenger, result: InternalGestureResult) {
        val response = Message.obtain(null, GestureIpcContract.MSG_RESULT).apply {
            data = Bundle().apply {
                putString(GestureIpcContract.KEY_LABEL, result.label)
                putFloat(GestureIpcContract.KEY_CONFIDENCE, result.confidence)
                putString(GestureIpcContract.KEY_HANDEDNESS, result.handedness)
                putInt(GestureIpcContract.KEY_FEATURE_COUNT, result.featureCount)
                putLong(GestureIpcContract.KEY_INFERENCE_TIME_MS, result.inferenceTimeMs)
                putString(GestureIpcContract.KEY_ENGINE, result.engine)
                putString(
                    GestureIpcContract.KEY_INPUT_FINGERPRINT,
                    result.inputFingerprint,
                )
            }
        }
        runCatching { callback.send(response) }
    }

    private fun sendError(callback: Messenger?, message: String) {
        if (callback == null) return
        val response = Message.obtain(null, GestureIpcContract.MSG_ERROR).apply {
            data = Bundle().apply {
                putInt(GestureIpcContract.KEY_ERROR_CODE, ERROR_INFERENCE)
                putString(GestureIpcContract.KEY_ERROR_MESSAGE, message)
            }
        }
        runCatching { callback.send(response) }
    }

    private fun sendRuntimeCameraStatus(
        callback: Messenger?,
        snapshot: RuntimeCameraSnapshot,
    ) {
        if (callback == null) return
        val response = Message.obtain(null, GestureIpcContract.MSG_RUNTIME_CAMERA_STATUS).apply {
            data = Bundle().apply {
                putString(GestureIpcContract.KEY_RUNTIME_STATE, snapshot.state)
                putString(GestureIpcContract.KEY_RUNTIME_DETAIL, snapshot.detail)
                putBoolean(
                    GestureIpcContract.KEY_RUNTIME_CONNECTED,
                    snapshot.runtimeConnected,
                )
                putBoolean(GestureIpcContract.KEY_CAMERA_OPEN, snapshot.cameraOpen)
                putBoolean(GestureIpcContract.KEY_CAMERA_STREAMING, snapshot.streaming)
                putString(GestureIpcContract.KEY_CAMERA_ID, snapshot.cameraId)
                putStringArrayList(
                    GestureIpcContract.KEY_AVAILABLE_CAMERAS,
                    ArrayList(snapshot.availableCameras),
                )
                putStringArrayList(
                    GestureIpcContract.KEY_SUPPORTED_RESOLUTIONS,
                    ArrayList(snapshot.supportedResolutions),
                )
                putLong(GestureIpcContract.KEY_FRAME_COUNT, snapshot.frameCount)
                putInt(GestureIpcContract.KEY_FRAME_WIDTH, snapshot.frameWidth)
                putInt(GestureIpcContract.KEY_FRAME_HEIGHT, snapshot.frameHeight)
                putLong(
                    GestureIpcContract.KEY_FRAME_TIMESTAMP_NANOS,
                    snapshot.frameTimestampNanos,
                )
                putString(GestureIpcContract.KEY_FRAME_CHECKSUM, snapshot.frameChecksum)
                putFloat(GestureIpcContract.KEY_FRAME_RATE, snapshot.frameRate)
                putInt(
                    GestureIpcContract.KEY_CAMERA_ORIENTATION,
                    snapshot.cameraOrientation,
                )
                putLong(
                    GestureIpcContract.KEY_RECOGNITION_COUNT,
                    snapshot.recognitionCount,
                )
                putString(GestureIpcContract.KEY_GESTURE_LABEL, snapshot.gestureLabel)
                putFloat(
                    GestureIpcContract.KEY_GESTURE_CONFIDENCE,
                    snapshot.gestureConfidence,
                )
                putInt(
                    GestureIpcContract.KEY_GESTURE_FEATURE_COUNT,
                    snapshot.gestureFeatureCount,
                )
                putLong(
                    GestureIpcContract.KEY_GESTURE_INFERENCE_TIME_MS,
                    snapshot.gestureInferenceTimeMs,
                )
                putLong(
                    GestureIpcContract.KEY_GESTURE_FRAME_TIMESTAMP_NANOS,
                    snapshot.gestureFrameTimestampNanos,
                )
                putString(
                    GestureIpcContract.KEY_RECOGNITION_ERROR,
                    snapshot.recognitionError,
                )
                putBoolean(
                    GestureIpcContract.KEY_TEMPLE_CONTROL_READY,
                    snapshot.templeControlReady,
                )
                putLong(
                    GestureIpcContract.KEY_TEMPLE_EVENT_COUNT,
                    snapshot.templeEventCount,
                )
                putLong(
                    GestureIpcContract.KEY_LAST_TEMPLE_EVENT_ELAPSED_REALTIME_MS,
                    snapshot.lastTempleEventElapsedRealtimeMs,
                )
                putString(
                    GestureIpcContract.KEY_LAST_CONTROL_SOURCE,
                    snapshot.lastControlSource,
                )
                putByteArray(GestureIpcContract.KEY_PREVIEW_JPEG, snapshot.previewJpeg)
                putLong(
                    GestureIpcContract.KEY_PREVIEW_TIMESTAMP_NANOS,
                    snapshot.previewTimestampNanos,
                )
                putInt(GestureIpcContract.KEY_PREVIEW_WIDTH, snapshot.previewWidth)
                putInt(GestureIpcContract.KEY_PREVIEW_HEIGHT, snapshot.previewHeight)
            }
        }
        runCatching { callback.send(response) }
    }

    @Suppress("DEPRECATION")
    private fun Bundle.parcelableImageDescriptor(): ParcelFileDescriptor? =
        getParcelable(GestureIpcContract.KEY_IMAGE_DESCRIPTOR)

    private fun decodeOrientedBitmap(descriptor: ParcelFileDescriptor): Bitmap {
        val exifDescriptor = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        val orientation = exifDescriptor.use {
            ExifInterface(it.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
        Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
        val source = BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
            ?: error("无法解码所选图片")
        val scaled = source.scaledDown(MAX_IMAGE_EDGE)
        if (scaled !== source) source.recycle()
        return scaled.rotatedForExif(orientation)
    }

    private fun Bitmap.scaledDown(maxEdge: Int): Bitmap {
        val largestEdge = maxOf(width, height)
        if (largestEdge <= maxEdge) return this
        val scale = maxEdge.toFloat() / largestEdge
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt(),
            (height * scale).toInt(),
            true,
        )
    }

    private fun Bitmap.rotatedForExif(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return this
        }
        val transformed = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (transformed !== this) recycle()
        return transformed
    }

    private fun Bitmap.inputFingerprint(): String {
        var hash = 0x811C9DC5u
        val xStep = maxOf(1, width / 16)
        val yStep = maxOf(1, height / 16)
        for (y in 0 until height step yStep) {
            for (x in 0 until width step xStep) {
                hash = (hash xor getPixel(x, y).toUInt()) * 0x01000193u
            }
        }
        return "${width}x${height} · ${hash.toString(16).uppercase().padStart(8, '0')}"
    }

    private data class InternalGestureResult(
        val label: String,
        val confidence: Float,
        val handedness: String,
        val featureCount: Int,
        val inferenceTimeMs: Long,
        val engine: String,
        val inputFingerprint: String,
    )

    private companion object {
        const val TAG = "GestureRecognitionService"
        const val MAX_IMAGE_EDGE = 1280
        const val ERROR_INFERENCE = 1
    }
}
