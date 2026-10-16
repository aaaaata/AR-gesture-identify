package com.example.gestureservice

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.rayneo.xr.client.FFalconXRClient
import com.rayneo.xr.sdk.FxrApi
import com.rayneo.xr.sdk.FxrApiFactory
import com.rayneo.xr.sdk.base.XRImage
import com.rayneo.xr.sdk.device.XRCamera
import com.rayneo.xr.sdk.device.XRCameraHelper
import com.rayneo.xr.sdk.listener.ICameraImageAvailable
import com.rayneo.xr.sdk.listener.StateEventAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Owns the vendor Runtime connection and records evidence that live frames are changing. */
internal class RayNeoRuntimeCameraSource(
    context: Context,
    private val onTempleClick: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val lifecycleMutex = Mutex()
    private val stateLock = Any()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val inferenceInFlight = AtomicBoolean(false)
    private val sessionGeneration = AtomicInteger(0)
    private val layoutLogged = AtomicBoolean(false)

    private var api: FxrApi? = null
    private var runtimeClient: FFalconXRClient? = null
    private var camera: XRCamera? = null
    private var previewChannelId = 0L
    private var initialized = false
    private var bound = false
    private var stateListenerRegistered = false

    private var state = State.IDLE
    private var detail = "尚未启动 Runtime 相机探针"
    private var runtimeConnected = false
    private var cameraOpen = false
    private var streaming = false
    private var cameraId = ""
    private var availableCameras = emptyList<String>()
    private var supportedResolutions = emptyList<String>()
    private var frameCount = 0L
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameTimestampNanos = 0L
    private var frameChecksum = ""
    private var streamStartedElapsedNanos = 0L
    private var cameraOrientation = 0
    private var recognitionCount = 0L
    private var gestureLabel = "None"
    private var gestureConfidence = 0f
    private var gestureFeatureCount = 0
    private var gestureInferenceTimeMs = 0L
    private var gestureFrameTimestampNanos = 0L
    private var recognitionError = ""
    private var templeControlReady = false
    private var templeEventCount = 0L
    private var lastTempleEventElapsedRealtimeMs = 0L
    private var lastControlSource = "NONE"
    private var lastControlElapsedRealtimeMs = 0L
    private var previewJpeg = ByteArray(0)
    private var previewTimestampNanos = 0L
    private var previewWidth = 0
    private var previewHeight = 0

    private val stateEventListener = object : StateEventAdapter() {
        override fun onRightButtonClicked() {
            val eventCount = synchronized(stateLock) {
                templeEventCount++
                lastTempleEventElapsedRealtimeMs = SystemClock.elapsedRealtime()
                templeEventCount
            }
            Log.i(TAG, "onRightButtonClicked count=$eventCount")
            onTempleClick()
        }
    }

    private val imageCallback = object : ICameraImageAvailable {
        override fun onImageAvailable(
            image: XRImage,
            timestamp: Long,
            width: Int,
            height: Int,
        ) {
            val checksum = image.sampledChecksum()
            val count = synchronized(stateLock) {
                frameCount++
                frameWidth = width
                frameHeight = height
                frameTimestampNanos = timestamp
                frameChecksum = checksum
                state = State.STREAMING
                detail = "正在接收 Runtime YUV420 图像帧"
                frameCount
            }
            if (count == 1L || count % LOG_FRAME_INTERVAL == 0L) {
                Log.i(TAG, "frame=$count size=${width}x$height timestamp=$timestamp checksum=$checksum")
            }
            if (count % RECOGNITION_FRAME_INTERVAL == 0L &&
                inferenceInFlight.compareAndSet(false, true)
            ) {
                val generation = sessionGeneration.get()
                runCatching { Yuv420Frame.copyFrom(image, timestamp, width, height) }
                    .onSuccess { frame -> scheduleInference(frame, generation) }
                    .onFailure { error ->
                        inferenceInFlight.set(false)
                        recordRecognitionError(error)
                    }
            }
        }
    }

    suspend fun connect(): RuntimeCameraSnapshot = lifecycleMutex.withLock {
        try {
            ensureRuntimeConnectedLocked()
        } catch (error: Throwable) {
            val failure = error.diagnosticMessage()
            Log.e(TAG, "Runtime connection failed", error)
            releaseConnectionResources()
            updateState(State.ERROR, failure)
        }
        snapshot()
    }

    suspend fun start(controlSource: String = CONTROL_APP): RuntimeCameraSnapshot =
        lifecycleMutex.withLock {
            if (synchronized(stateLock) { streaming }) return@withLock snapshot()
            try {
                ensureRuntimeConnectedLocked()
                startCameraLocked(controlSource)
            } catch (error: Throwable) {
                val failure = error.diagnosticMessage()
                Log.e(TAG, "Runtime camera start failed", error)
                releaseCameraResources()
                updateState(State.ERROR, failure)
            }
            snapshot()
        }

    suspend fun stop(controlSource: String = CONTROL_APP): RuntimeCameraSnapshot =
        lifecycleMutex.withLock {
            if (!synchronized(stateLock) { streaming || cameraOpen }) {
                return@withLock snapshot()
            }
            stopCameraLocked(controlSource)
            snapshot()
        }

    suspend fun toggleFromTemple(): RuntimeCameraSnapshot = lifecycleMutex.withLock {
        val now = SystemClock.elapsedRealtime()
        val ignoreDuplicatedUiClick = synchronized(stateLock) {
            lastControlSource == CONTROL_APP &&
                now - lastControlElapsedRealtimeMs < CROSS_SOURCE_DEBOUNCE_MS
        }
        if (ignoreDuplicatedUiClick) {
            Log.i(TAG, "Ignoring temple event paired with a recent app click")
            return@withLock snapshot()
        }
        if (synchronized(stateLock) { streaming }) {
            stopCameraLocked(CONTROL_TEMPLE)
        } else {
            try {
                ensureRuntimeConnectedLocked()
                startCameraLocked(CONTROL_TEMPLE)
            } catch (error: Throwable) {
                val failure = error.diagnosticMessage()
                Log.e(TAG, "Temple-triggered camera start failed", error)
                releaseCameraResources()
                updateState(State.ERROR, failure)
            }
        }
        snapshot()
    }

    private suspend fun ensureRuntimeConnectedLocked() {
        if (bound && stateListenerRegistered) return

        releaseConnectionResources()
        updateState(State.INITIALIZING, "正在加载 RayNeo Runtime 客户端")
        // FFalconXRClient constructs an Android Handler, so obtain it on a Looper thread.
        val obtainedApi = withContext(Dispatchers.Main.immediate) {
            FxrApiFactory.obtain()
        }
        val client = obtainedApi as? FFalconXRClient
            ?: error("FxrApiFactory did not return FFalconXRClient")
        val initSucceeded = withContext(Dispatchers.IO) {
            obtainedApi.initAndroid(appContext)
        }
        check(initSucceeded) { "RayNeo SDK initAndroid returned false" }
        api = obtainedApi
        runtimeClient = client
        initialized = true

        updateState(State.BINDING_RUNTIME, "正在绑定 com.rayneo.xr.runtime")
        val bindSucceeded = withContext(Dispatchers.IO) { client.bindService() }
        check(bindSucceeded) { "RayNeo Runtime bindService returned false or timed out" }
        bound = true
        synchronized(stateLock) { runtimeConnected = true }

        check(obtainedApi.registerStateEventListener(stateEventListener)) {
            "RayNeo Runtime rejected StateEventListener registration"
        }
        stateListenerRegistered = true
        synchronized(stateLock) {
            templeControlReady = true
            state = State.IDLE
            detail = "Runtime 已连接，镜腿单击控制已就绪"
        }
        Log.i(TAG, "StateEventListener registered; temple control ready")
    }

    private suspend fun startCameraLocked(controlSource: String) {
        recordControl(controlSource)
        releaseCameraResources()
        synchronized(stateLock) {
            cameraId = ""
            availableCameras = emptyList()
            supportedResolutions = emptyList()
            frameCount = 0L
            frameWidth = 0
            frameHeight = 0
            frameTimestampNanos = 0L
            frameChecksum = ""
            cameraOrientation = 0
            recognitionCount = 0L
            gestureLabel = "None"
            gestureConfidence = 0f
            gestureFeatureCount = 0
            gestureInferenceTimeMs = 0L
            gestureFrameTimestampNanos = 0L
            recognitionError = ""
            previewJpeg = ByteArray(0)
            previewTimestampNanos = 0L
            previewWidth = 0
            previewHeight = 0
        }
        layoutLogged.set(false)
        val detectedCameras = runCatching {
            XRCameraHelper.getCameraList()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val selectedCameraId = when {
            "0" in detectedCameras -> "0"
            detectedCameras.isNotEmpty() -> detectedCameras.first()
            else -> DEFAULT_CAMERA_ID
        }
        val detectedResolutions = runCatching {
            XRCameraHelper.getSupportedResolutions(selectedCameraId)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val detectedOrientation = runCatching {
            XRCameraHelper.getOrientation(selectedCameraId)
        }.getOrDefault(0)
        val selectedResolution = detectedResolutions.firstOrNull {
            it.width == DEFAULT_WIDTH && it.height == DEFAULT_HEIGHT
        } ?: detectedResolutions.firstOrNull()
        val width = selectedResolution?.width ?: DEFAULT_WIDTH
        val height = selectedResolution?.height ?: DEFAULT_HEIGHT
        synchronized(stateLock) {
            cameraId = selectedCameraId
            availableCameras = detectedCameras
            supportedResolutions = detectedResolutions.map { "${it.width}x${it.height}" }
            cameraOrientation = detectedOrientation
        }

        updateState(State.OPENING_CAMERA, "正在打开 Runtime 摄像头 $selectedCameraId")
        val openedCamera = XRCamera(selectedCameraId)
        check(withContext(Dispatchers.IO) { openedCamera.open() } == 0) {
            "XRCamera.open failed for camera $selectedCameraId"
        }
        camera = openedCamera
        synchronized(stateLock) { cameraOpen = true }

        val channelId = withContext(Dispatchers.IO) {
            openedCamera.startPreviewChannelWithParameters(
                width,
                height,
                DEFAULT_FRAME_RATE,
                XRCamera.FORMAT.kImageMemoryYUV420,
                imageCallback,
            )
        }
        check(channelId != 0L) { "Runtime did not create a YUV420 preview channel" }
        previewChannelId = channelId
        synchronized(stateLock) {
            streaming = true
            streamStartedElapsedNanos = SystemClock.elapsedRealtimeNanos()
            state = State.WAITING_FOR_FRAME
            detail = "预览通道已启动，正在等待第一帧"
        }
    }

    private suspend fun stopCameraLocked(controlSource: String) {
        recordControl(controlSource)
        updateState(State.STOPPING, "正在停止 Runtime 相机和实时识别")
        val cleanupError = withContext(Dispatchers.IO) { releaseCameraResources() }
        if (cleanupError == null) {
            updateState(State.IDLE, "实时识别已停止；镜腿单击控制保持就绪")
        } else {
            updateState(State.ERROR, cleanupError)
        }
    }

    fun close() {
        disconnect()
        inferenceExecutor.shutdownNow()
    }

    /** Releases camera and Runtime resources when the last app client unbinds. */
    fun disconnect() {
        releaseConnectionResources()
        updateState(State.IDLE, "无客户端连接，Runtime 相机和连接已释放")
    }

    fun snapshot(): RuntimeCameraSnapshot = synchronized(stateLock) {
        val elapsedSeconds = if (streamStartedElapsedNanos == 0L) {
            0f
        } else {
            (SystemClock.elapsedRealtimeNanos() - streamStartedElapsedNanos) / 1_000_000_000f
        }
        RuntimeCameraSnapshot(
            state = state.name,
            detail = detail,
            runtimeConnected = runtimeConnected,
            cameraOpen = cameraOpen,
            streaming = streaming,
            cameraId = cameraId,
            availableCameras = availableCameras,
            supportedResolutions = supportedResolutions,
            frameCount = frameCount,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            frameTimestampNanos = frameTimestampNanos,
            frameChecksum = frameChecksum,
            frameRate = if (elapsedSeconds > 0f) frameCount / elapsedSeconds else 0f,
            cameraOrientation = cameraOrientation,
            recognitionCount = recognitionCount,
            gestureLabel = gestureLabel,
            gestureConfidence = gestureConfidence,
            gestureFeatureCount = gestureFeatureCount,
            gestureInferenceTimeMs = gestureInferenceTimeMs,
            gestureFrameTimestampNanos = gestureFrameTimestampNanos,
            recognitionError = recognitionError,
            templeControlReady = templeControlReady,
            templeEventCount = templeEventCount,
            lastTempleEventElapsedRealtimeMs = lastTempleEventElapsedRealtimeMs,
            lastControlSource = lastControlSource,
            previewJpeg = previewJpeg,
            previewTimestampNanos = previewTimestampNanos,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
        )
    }

    private fun scheduleInference(frame: Yuv420Frame, generation: Int) {
        runCatching {
            inferenceExecutor.execute {
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    if (layoutLogged.compareAndSet(false, true)) {
                        Log.i(
                            TAG,
                            "YUV layout ${frame.layoutSummary()}, orientation=$cameraOrientation",
                        )
                    }
                    val orientation = synchronized(stateLock) { cameraOrientation }
                    val previewBitmap = frame.toAnalysisBitmap(orientation, PREVIEW_MAX_EDGE)
                    val encodedPreviewWidth = previewBitmap.width
                    val encodedPreviewHeight = previewBitmap.height
                    val encodedPreview = try {
                        ByteArrayOutputStream().use { output ->
                            check(previewBitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, output)) {
                                "Failed to encode Runtime preview frame"
                            }
                            output.toByteArray()
                        }
                    } finally {
                        previewBitmap.recycle()
                    }
                    val bitmap = frame.toAnalysisBitmap(orientation, ANALYSIS_MAX_EDGE)
                    val result = try {
                        DemoImageGestureRecognizer.recognize(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    if (generation == sessionGeneration.get()) {
                        synchronized(stateLock) {
                            recognitionCount++
                            gestureLabel = result.label
                            gestureConfidence = result.confidence
                            gestureFeatureCount = result.featureCount
                            gestureInferenceTimeMs = elapsed
                            gestureFrameTimestampNanos = frame.timestampNanos
                            recognitionError = ""
                            previewJpeg = encodedPreview
                            previewTimestampNanos = frame.timestampNanos
                            previewWidth = encodedPreviewWidth
                            previewHeight = encodedPreviewHeight
                        }
                        if (recognitionCount == 1L || recognitionCount % LOG_RECOGNITION_INTERVAL == 0L) {
                            Log.i(
                                TAG,
                                "gesture=${result.label} confidence=${result.confidence} " +
                                    "features=${result.featureCount} inferenceMs=$elapsed",
                            )
                        }
                    }
                } catch (error: Throwable) {
                    if (generation == sessionGeneration.get()) recordRecognitionError(error)
                } finally {
                    inferenceInFlight.set(false)
                }
            }
        }.onFailure { error ->
            inferenceInFlight.set(false)
            recordRecognitionError(error)
        }
    }

    private fun recordRecognitionError(error: Throwable) {
        val message = error.diagnosticMessage()
        synchronized(stateLock) { recognitionError = message }
        Log.e(TAG, "Live recognition failed", error)
    }

    private fun updateState(newState: State, message: String) {
        synchronized(stateLock) {
            state = newState
            detail = message
        }
        Log.i(TAG, "state=${newState.name} detail=$message")
    }

    private fun recordControl(controlSource: String) {
        synchronized(stateLock) {
            lastControlSource = controlSource
            lastControlElapsedRealtimeMs = SystemClock.elapsedRealtime()
        }
        Log.i(TAG, "controlSource=$controlSource")
    }

    /** Releases only camera resources so Runtime event callbacks remain available while idle. */
    private fun releaseCameraResources(): String? {
        sessionGeneration.incrementAndGet()
        val errors = mutableListOf<String>()
        val activeCamera = camera
        val activeChannel = previewChannelId
        if (activeCamera != null && activeChannel != 0L) {
            runCatching { activeCamera.stopPreviewChannel(activeChannel) }
                .onFailure { errors += "stopPreview: ${it.diagnosticMessage()}" }
        }
        if (activeCamera != null) {
            runCatching { activeCamera.close() }
                .onFailure { errors += "closeCamera: ${it.diagnosticMessage()}" }
        }
        camera = null
        previewChannelId = 0L
        synchronized(stateLock) {
            cameraOpen = false
            streaming = false
            streamStartedElapsedNanos = 0L
            previewJpeg = ByteArray(0)
            previewTimestampNanos = 0L
            previewWidth = 0
            previewHeight = 0
        }
        return errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun releaseConnectionResources(): String? {
        val errors = mutableListOf<String>()
        releaseCameraResources()?.let(errors::add)
        if (stateListenerRegistered) {
            runCatching { api?.unregisterStateEventListener(stateEventListener) }
                .onFailure { errors += "unregisterStateListener: ${it.diagnosticMessage()}" }
        }
        stateListenerRegistered = false
        if (bound) {
            runCatching { runtimeClient?.unbindService() }
                .onFailure { errors += "unbindRuntime: ${it.diagnosticMessage()}" }
        }
        if (initialized) {
            runCatching { api?.uninitAndroid() }
                .onFailure { errors += "uninitSdk: ${it.diagnosticMessage()}" }
        }
        runtimeClient = null
        api = null
        initialized = false
        bound = false
        synchronized(stateLock) {
            runtimeConnected = false
            templeControlReady = false
        }
        return errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun XRImage.sampledChecksum(): String {
        var hash = FNV_OFFSET_BASIS
        val imagePlanes = planes ?: emptyArray()
        val imageLengths = lengths
        imagePlanes.forEachIndexed { index, plane ->
            hash = hash.sampleBuffer(plane, imageLengths?.getOrNull(index))
        }
        return java.lang.Long.toUnsignedString(hash, 16)
            .uppercase(Locale.US)
            .padStart(16, '0')
    }

    private fun Long.sampleBuffer(buffer: ByteBuffer, declaredLength: Int?): Long {
        var hash = this
        val readableBytes = minOf(
            buffer.capacity(),
            declaredLength?.coerceAtLeast(0) ?: buffer.limit(),
        )
        if (readableBytes == 0) return hash
        val step = maxOf(1, readableBytes / CHECKSUM_SAMPLES_PER_PLANE)
        for (offset in 0 until readableBytes step step) {
            hash = (hash xor (buffer.get(offset).toLong() and 0xFFL)) * FNV_PRIME
        }
        return hash
    }

    private fun Throwable.diagnosticMessage(): String {
        val root = generateSequence(this) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message ?: "no detail"}"
    }

    private enum class State {
        IDLE,
        INITIALIZING,
        BINDING_RUNTIME,
        OPENING_CAMERA,
        WAITING_FOR_FRAME,
        STREAMING,
        STOPPING,
        ERROR,
    }

    private companion object {
        const val DEFAULT_CAMERA_ID = "0"
        const val DEFAULT_WIDTH = 640
        const val DEFAULT_HEIGHT = 400
        const val DEFAULT_FRAME_RATE = 30
        const val CHECKSUM_SAMPLES_PER_PLANE = 128
        const val RECOGNITION_FRAME_INTERVAL = 15L
        const val ANALYSIS_MAX_EDGE = 192
        const val PREVIEW_MAX_EDGE = 256
        const val PREVIEW_JPEG_QUALITY = 60
        const val CROSS_SOURCE_DEBOUNCE_MS = 750L
        const val CONTROL_APP = "APP"
        const val CONTROL_TEMPLE = "TEMPLE"
        const val LOG_RECOGNITION_INTERVAL = 10L
        const val LOG_FRAME_INTERVAL = 30L
        const val TAG = "RayNeoCameraProbe"
        const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL
        const val FNV_PRIME = 0x100000001b3L
    }
}

internal data class RuntimeCameraSnapshot(
    val state: String,
    val detail: String,
    val runtimeConnected: Boolean,
    val cameraOpen: Boolean,
    val streaming: Boolean,
    val cameraId: String,
    val availableCameras: List<String>,
    val supportedResolutions: List<String>,
    val frameCount: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val frameTimestampNanos: Long,
    val frameChecksum: String,
    val frameRate: Float,
    val cameraOrientation: Int,
    val recognitionCount: Long,
    val gestureLabel: String,
    val gestureConfidence: Float,
    val gestureFeatureCount: Int,
    val gestureInferenceTimeMs: Long,
    val gestureFrameTimestampNanos: Long,
    val recognitionError: String,
    val templeControlReady: Boolean,
    val templeEventCount: Long,
    val lastTempleEventElapsedRealtimeMs: Long,
    val lastControlSource: String,
    val previewJpeg: ByteArray,
    val previewTimestampNanos: Long,
    val previewWidth: Int,
    val previewHeight: Int,
)
