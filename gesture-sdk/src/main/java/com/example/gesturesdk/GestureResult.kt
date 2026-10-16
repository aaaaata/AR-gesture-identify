package com.example.gesturesdk

/** A stable app-facing result that does not expose MediaPipe implementation types. */
data class GestureResult(
    val label: String,
    val confidence: Float,
    val handedness: String,
    val featureCount: Int,
    val inferenceTimeMs: Long,
    val engine: String,
    val inputFingerprint: String,
)
