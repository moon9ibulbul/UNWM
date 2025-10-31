package com.astral.unwm

data class WatermarkDetection(
    val offsetX: Float,
    val offsetY: Float,
    val score: Float
)

sealed class DetectionState {
    object Idle : DetectionState()
    object Running : DetectionState()
    object Success : DetectionState()
    object NoMatch : DetectionState()
    data class Error(val message: String) : DetectionState()
}
