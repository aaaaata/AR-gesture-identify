package com.example.gesturesdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import com.example.gesturesdk.internal.GestureIpcContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * App-facing SDK entry point. Inference runs in a separate process so the XR app process only
 * depends on this stable result API, not on MediaPipe implementation types.
 */
class GestureClient(
    context: Context,
    private val servicePackageName: String = context.packageName,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val firstConnection = CompletableDeferred<Messenger>()

    @Volatile
    private var remoteService: Messenger? = null

    @Volatile
    private var closed = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = Messenger(binder)
            remoteService = service
            if (!firstConnection.isCompleted) firstConnection.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remoteService = null
        }

        override fun onBindingDied(name: ComponentName) {
            remoteService = null
        }

        override fun onNullBinding(name: ComponentName) {
            val error = IllegalStateException("Gesture service returned a null binding")
            if (!firstConnection.isCompleted) firstConnection.completeExceptionally(error)
        }
    }

    init {
        val intent = Intent().setComponent(
            ComponentName(servicePackageName, GestureIpcContract.SERVICE_CLASS_NAME),
        )
        val bindResult = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        if (!bindResult.getOrDefault(false)) {
            val cause = bindResult.exceptionOrNull()
            firstConnection.completeExceptionally(
                IllegalStateException(
                    "Unable to bind gesture service in package $servicePackageName. " +
                        "Verify that the host app is installed and the caller has bind permission.",
                    cause,
                ),
            )
        }
    }

    suspend fun recognize(image: Uri): GestureResult {
        check(!closed) { "GestureClient is closed" }
        val imageDescriptor = appContext.contentResolver.openFileDescriptor(image, "r")
            ?: error("Unable to open the selected image")

        return try {
            recognize(imageDescriptor)
        } finally {
            imageDescriptor.close()
        }
    }

    /** Captures one frame from the SDK camera view, then sends it to the gesture service. */
    suspend fun recognize(cameraView: GestureCameraView): GestureResult {
        check(!closed) { "GestureClient is closed" }
        val imageFile = cameraView.captureTemporaryFile()
        val imageDescriptor = ParcelFileDescriptor.open(
            imageFile,
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        return try {
            recognize(imageDescriptor)
        } finally {
            imageDescriptor.close()
            imageFile.delete()
        }
    }

    /** Starts continuous YUV frame acquisition through the RayNeo Runtime. */
    suspend fun startRuntimeCameraProbe(): RuntimeCameraStatus =
        requestRuntimeCameraStatus(GestureIpcContract.MSG_START_RUNTIME_CAMERA)

    suspend fun startRuntimeRecognition(): RuntimeCameraStatus = startRuntimeCameraProbe()

    /** Stops the Runtime preview channel and releases the Runtime client connection. */
    suspend fun stopRuntimeCameraProbe(): RuntimeCameraStatus =
        requestRuntimeCameraStatus(GestureIpcContract.MSG_STOP_RUNTIME_CAMERA)

    suspend fun stopRuntimeRecognition(): RuntimeCameraStatus = stopRuntimeCameraProbe()

    /** Reads the latest counters without changing the Runtime camera state. */
    suspend fun getRuntimeCameraStatus(): RuntimeCameraStatus =
        requestRuntimeCameraStatus(GestureIpcContract.MSG_GET_RUNTIME_CAMERA_STATUS)

    suspend fun getRuntimeRecognitionStatus(): RuntimeCameraStatus = getRuntimeCameraStatus()

    private suspend fun recognize(imageDescriptor: ParcelFileDescriptor): GestureResult {
        val service = remoteService ?: withTimeout(SERVICE_CONNECTION_TIMEOUT_MS) {
            firstConnection.await()
        }
        return suspendCancellableCoroutine { continuation ->
                val callback = Messenger(
                    Handler(Looper.getMainLooper()) { response ->
                        when (response.what) {
                            GestureIpcContract.MSG_RESULT -> {
                                val data = response.data
                                if (continuation.isActive) {
                                    continuation.resume(
                                        GestureResult(
                                            label = data.getString(GestureIpcContract.KEY_LABEL, "None"),
                                            confidence = data.getFloat(GestureIpcContract.KEY_CONFIDENCE),
                                            handedness = data.getString(
                                                GestureIpcContract.KEY_HANDEDNESS,
                                                "Unknown",
                                            ),
                                            featureCount = data.getInt(
                                                GestureIpcContract.KEY_FEATURE_COUNT,
                                            ),
                                            inferenceTimeMs = data.getLong(
                                                GestureIpcContract.KEY_INFERENCE_TIME_MS,
                                            ),
                                            engine = data.getString(
                                                GestureIpcContract.KEY_ENGINE,
                                                "Unknown",
                                            ),
                                            inputFingerprint = data.getString(
                                                GestureIpcContract.KEY_INPUT_FINGERPRINT,
                                                "Unknown",
                                            ),
                                        ),
                                    )
                                }
                                true
                            }

                            GestureIpcContract.MSG_ERROR -> {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        GestureRecognitionException(
                                            response.data.getInt(GestureIpcContract.KEY_ERROR_CODE),
                                            response.data.getString(
                                                GestureIpcContract.KEY_ERROR_MESSAGE,
                                                "Gesture recognition failed",
                                            ),
                                        ),
                                    )
                                }
                                true
                            }

                            else -> false
                        }
                    },
                )
                val request = Message.obtain(null, GestureIpcContract.MSG_RECOGNIZE).apply {
                    data = Bundle().apply {
                        putParcelable(GestureIpcContract.KEY_IMAGE_DESCRIPTOR, imageDescriptor)
                    }
                    replyTo = callback
                }
                try {
                    service.send(request)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
    }

    private suspend fun requestRuntimeCameraStatus(requestCode: Int): RuntimeCameraStatus {
        check(!closed) { "GestureClient is closed" }
        val service = remoteService ?: withTimeout(SERVICE_CONNECTION_TIMEOUT_MS) {
            firstConnection.await()
        }
        return withTimeout(RUNTIME_REQUEST_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = Messenger(
                    Handler(Looper.getMainLooper()) { response ->
                        when (response.what) {
                            GestureIpcContract.MSG_RUNTIME_CAMERA_STATUS -> {
                                if (continuation.isActive) {
                                    val data = response.data
                                    continuation.resume(
                                        RuntimeCameraStatus(
                                            state = data.getString(
                                                GestureIpcContract.KEY_RUNTIME_STATE,
                                                "UNKNOWN",
                                            ),
                                            detail = data.getString(
                                                GestureIpcContract.KEY_RUNTIME_DETAIL,
                                                "",
                                            ),
                                            runtimeConnected = data.getBoolean(
                                                GestureIpcContract.KEY_RUNTIME_CONNECTED,
                                            ),
                                            cameraOpen = data.getBoolean(
                                                GestureIpcContract.KEY_CAMERA_OPEN,
                                            ),
                                            streaming = data.getBoolean(
                                                GestureIpcContract.KEY_CAMERA_STREAMING,
                                            ),
                                            cameraId = data.getString(
                                                GestureIpcContract.KEY_CAMERA_ID,
                                                "",
                                            ),
                                            availableCameras = data.getStringArrayList(
                                                GestureIpcContract.KEY_AVAILABLE_CAMERAS,
                                            ).orEmpty(),
                                            supportedResolutions = data.getStringArrayList(
                                                GestureIpcContract.KEY_SUPPORTED_RESOLUTIONS,
                                            ).orEmpty(),
                                            frameCount = data.getLong(
                                                GestureIpcContract.KEY_FRAME_COUNT,
                                            ),
                                            frameWidth = data.getInt(
                                                GestureIpcContract.KEY_FRAME_WIDTH,
                                            ),
                                            frameHeight = data.getInt(
                                                GestureIpcContract.KEY_FRAME_HEIGHT,
                                            ),
                                            frameTimestampNanos = data.getLong(
                                                GestureIpcContract.KEY_FRAME_TIMESTAMP_NANOS,
                                            ),
                                            frameChecksum = data.getString(
                                                GestureIpcContract.KEY_FRAME_CHECKSUM,
                                                "",
                                            ),
                                            frameRate = data.getFloat(
                                                GestureIpcContract.KEY_FRAME_RATE,
                                            ),
                                            cameraOrientation = data.getInt(
                                                GestureIpcContract.KEY_CAMERA_ORIENTATION,
                                            ),
                                            recognitionCount = data.getLong(
                                                GestureIpcContract.KEY_RECOGNITION_COUNT,
                                            ),
                                            gestureLabel = data.getString(
                                                GestureIpcContract.KEY_GESTURE_LABEL,
                                                "None",
                                            ),
                                            gestureConfidence = data.getFloat(
                                                GestureIpcContract.KEY_GESTURE_CONFIDENCE,
                                            ),
                                            gestureFeatureCount = data.getInt(
                                                GestureIpcContract.KEY_GESTURE_FEATURE_COUNT,
                                            ),
                                            gestureInferenceTimeMs = data.getLong(
                                                GestureIpcContract.KEY_GESTURE_INFERENCE_TIME_MS,
                                            ),
                                            gestureFrameTimestampNanos = data.getLong(
                                                GestureIpcContract.KEY_GESTURE_FRAME_TIMESTAMP_NANOS,
                                            ),
                                            recognitionError = data.getString(
                                                GestureIpcContract.KEY_RECOGNITION_ERROR,
                                                "",
                                            ),
                                            templeControlReady = data.getBoolean(
                                                GestureIpcContract.KEY_TEMPLE_CONTROL_READY,
                                            ),
                                            templeEventCount = data.getLong(
                                                GestureIpcContract.KEY_TEMPLE_EVENT_COUNT,
                                            ),
                                            lastTempleEventElapsedRealtimeMs = data.getLong(
                                                GestureIpcContract.KEY_LAST_TEMPLE_EVENT_ELAPSED_REALTIME_MS,
                                            ),
                                            lastControlSource = data.getString(
                                                GestureIpcContract.KEY_LAST_CONTROL_SOURCE,
                                                "NONE",
                                            ),
                                            previewJpeg = data.getByteArray(
                                                GestureIpcContract.KEY_PREVIEW_JPEG,
                                            ) ?: ByteArray(0),
                                            previewTimestampNanos = data.getLong(
                                                GestureIpcContract.KEY_PREVIEW_TIMESTAMP_NANOS,
                                            ),
                                            previewWidth = data.getInt(
                                                GestureIpcContract.KEY_PREVIEW_WIDTH,
                                            ),
                                            previewHeight = data.getInt(
                                                GestureIpcContract.KEY_PREVIEW_HEIGHT,
                                            ),
                                        ),
                                    )
                                }
                                true
                            }

                            GestureIpcContract.MSG_ERROR -> {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        GestureRecognitionException(
                                            response.data.getInt(GestureIpcContract.KEY_ERROR_CODE),
                                            response.data.getString(
                                                GestureIpcContract.KEY_ERROR_MESSAGE,
                                                "Runtime camera request failed",
                                            ),
                                        ),
                                    )
                                }
                                true
                            }

                            else -> false
                        }
                    },
                )
                val request = Message.obtain(null, requestCode).apply {
                    replyTo = callback
                }
                try {
                    service.send(request)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { appContext.unbindService(connection) }
        remoteService = null
    }

    private companion object {
        const val SERVICE_CONNECTION_TIMEOUT_MS = 10_000L
        const val RUNTIME_REQUEST_TIMEOUT_MS = 20_000L
    }
}

class GestureRecognitionException(
    val errorCode: Int,
    message: String,
) : RuntimeException(message)
