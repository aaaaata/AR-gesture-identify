package com.example.gesturesdk.internal

/** Shared wire constants for the Messenger-based Binder protocol. */
object GestureIpcContract {
    const val SERVICE_CLASS_NAME =
        "com.example.gestureservice.GestureRecognitionService"

    const val MSG_RECOGNIZE = 1
    const val MSG_RESULT = 2
    const val MSG_ERROR = 3
    const val MSG_START_RUNTIME_CAMERA = 4
    const val MSG_STOP_RUNTIME_CAMERA = 5
    const val MSG_GET_RUNTIME_CAMERA_STATUS = 6
    const val MSG_RUNTIME_CAMERA_STATUS = 7

    const val KEY_IMAGE_DESCRIPTOR = "image_descriptor"
    const val KEY_LABEL = "label"
    const val KEY_CONFIDENCE = "confidence"
    const val KEY_HANDEDNESS = "handedness"
    const val KEY_FEATURE_COUNT = "feature_count"
    const val KEY_INFERENCE_TIME_MS = "inference_time_ms"
    const val KEY_ENGINE = "engine"
    const val KEY_INPUT_FINGERPRINT = "input_fingerprint"
    const val KEY_ERROR_CODE = "error_code"
    const val KEY_ERROR_MESSAGE = "error_message"
    const val KEY_RUNTIME_STATE = "runtime_state"
    const val KEY_RUNTIME_DETAIL = "runtime_detail"
    const val KEY_RUNTIME_CONNECTED = "runtime_connected"
    const val KEY_CAMERA_OPEN = "camera_open"
    const val KEY_CAMERA_STREAMING = "camera_streaming"
    const val KEY_CAMERA_ID = "camera_id"
    const val KEY_AVAILABLE_CAMERAS = "available_cameras"
    const val KEY_SUPPORTED_RESOLUTIONS = "supported_resolutions"
    const val KEY_FRAME_COUNT = "frame_count"
    const val KEY_FRAME_WIDTH = "frame_width"
    const val KEY_FRAME_HEIGHT = "frame_height"
    const val KEY_FRAME_TIMESTAMP_NANOS = "frame_timestamp_nanos"
    const val KEY_FRAME_CHECKSUM = "frame_checksum"
    const val KEY_FRAME_RATE = "frame_rate"
    const val KEY_CAMERA_ORIENTATION = "camera_orientation"
    const val KEY_RECOGNITION_COUNT = "recognition_count"
    const val KEY_GESTURE_LABEL = "gesture_label"
    const val KEY_GESTURE_CONFIDENCE = "gesture_confidence"
    const val KEY_GESTURE_FEATURE_COUNT = "gesture_feature_count"
    const val KEY_GESTURE_INFERENCE_TIME_MS = "gesture_inference_time_ms"
    const val KEY_GESTURE_FRAME_TIMESTAMP_NANOS = "gesture_frame_timestamp_nanos"
    const val KEY_RECOGNITION_ERROR = "recognition_error"
    const val KEY_TEMPLE_CONTROL_READY = "temple_control_ready"
    const val KEY_TEMPLE_EVENT_COUNT = "temple_event_count"
    const val KEY_LAST_TEMPLE_EVENT_ELAPSED_REALTIME_MS =
        "last_temple_event_elapsed_realtime_ms"
    const val KEY_LAST_CONTROL_SOURCE = "last_control_source"
    const val KEY_PREVIEW_JPEG = "preview_jpeg"
    const val KEY_PREVIEW_TIMESTAMP_NANOS = "preview_timestamp_nanos"
    const val KEY_PREVIEW_WIDTH = "preview_width"
    const val KEY_PREVIEW_HEIGHT = "preview_height"
}
