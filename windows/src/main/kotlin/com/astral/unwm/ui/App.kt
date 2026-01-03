package com.astral.unwm.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.astral.unwm.DetectionState
import com.astral.unwm.WatermarkAlphaGuesser
import com.astral.unwm.WatermarkDetection
import com.astral.unwm.WatermarkDetector
import com.astral.unwm.WatermarkExtractor
import com.astral.unwm.WatermarkRemover
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay

private const val AUTOMATION_ITEM_TIMEOUT_MS = 30_000L

fun openFileDialog(
    title: String,
    mode: Int = FileDialog.LOAD,
    multipleMode: Boolean = false
): List<File> {
    val fileDialog = FileDialog(null as Frame?, title, mode)
    fileDialog.isMultipleMode = multipleMode
    fileDialog.isVisible = true
    return fileDialog.files.toList()
}

private data class QueuedImage(val file: File, val displayName: String)

private data class DetectionGuessResult(
    val detections: List<WatermarkDetection>,
    val guessedAlphas: List<Float?>
)

private sealed interface AutomationProcessingResult {
    data class Success(val bitmap: BufferedImage, val guessedAlpha: Float?) :
        AutomationProcessingResult
    object NoDetections : AutomationProcessingResult
}

private enum class AppTab(val title: String) {
    Unwatermarker("Unwatermarker"),
    Extractor("Extractor")
}

@Composable
fun App() {
    AstralUnwmTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var selectedTab by remember { mutableStateOf(AppTab.Unwatermarker) }
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    AppTab.values().forEach { tab ->
                        Tab(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            text = { Text(text = tab.title) }
                        )
                    }
                }
                when (selectedTab) {
                    AppTab.Unwatermarker -> {
                        UnwatermarkerScreen()
                    }
                    AppTab.Extractor -> {
                        ExtractorScreen()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnwatermarkerScreen() {
    var baseBitmap by remember { mutableStateOf<BufferedImage?>(null) }
    var watermarkBitmap by remember { mutableStateOf<BufferedImage?>(null) }
    var resultBitmap by remember { mutableStateOf<BufferedImage?>(null) }
    var baseImageFile by remember { mutableStateOf<File?>(null) }

    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var alphaAdjust by remember { mutableStateOf(1f) }
    var autoGuessAlpha by remember { mutableStateOf(false) }
    var transparencyThreshold by remember { mutableStateOf(0f) }
    var opaqueThreshold by remember { mutableStateOf(255f) }
    var detectionThreshold by remember { mutableStateOf(0.9f) }
    var isRealTimeUnwatermarking by remember { mutableStateOf(false) }

    var isProcessing by remember { mutableStateOf(false) }
    var lastToastMessage by remember { mutableStateOf<String?>(null) }
    var detectionState by remember { mutableStateOf<DetectionState>(DetectionState.Idle) }
    var detectionResults by remember { mutableStateOf<List<WatermarkDetection>>(emptyList()) }
    var detectionAlphaGuesses by remember { mutableStateOf<List<Float?>>(emptyList()) }
    var selectedDetectionIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var applyAllDetections by remember { mutableStateOf(true) }

    val bulkQueue = remember { mutableStateListOf<QueuedImage>() }
    var currentQueueItemName by remember { mutableStateOf<String?>(null) }
    var isLoadingQueueItem by remember { mutableStateOf(false) }
    var isAutomationRunning by remember { mutableStateOf(false) }
    var automationProgress by remember { mutableStateOf(0) }
    var automationTotal by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()

    // Real-time preview state
    var realTimePreviewBitmap by remember { mutableStateOf<BufferedImage?>(null) }

    val maxOffsetX = baseBitmap?.width?.toFloat()?.takeIf { it > 0f } ?: 1000f
    val maxOffsetY = baseBitmap?.height?.toFloat()?.takeIf { it > 0f } ?: 1000f

    LaunchedEffect(maxOffsetX) { offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX) }
    LaunchedEffect(maxOffsetY) { offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY) }

    // Real Time Unwatermarking Logic
    LaunchedEffect(
        isRealTimeUnwatermarking,
        baseBitmap,
        watermarkBitmap,
        offsetX,
        offsetY,
        alphaAdjust,
        transparencyThreshold,
        opaqueThreshold,
        autoGuessAlpha
    ) {
        if (!isRealTimeUnwatermarking || baseBitmap == null || watermarkBitmap == null) {
            realTimePreviewBitmap = null
            return@LaunchedEffect
        }

        // Debounce slightly to avoid heavy CPU usage during fast drag
        delay(16)

        val base = baseBitmap!!
        val wm = watermarkBitmap!!
        val cx = offsetX.roundToInt()
        val cy = offsetY.roundToInt()
        val alpha = alphaAdjust
        val trans = transparencyThreshold.roundToInt()
        val opaque = opaqueThreshold.roundToInt()

        val processed = withContext(Dispatchers.Default) {
            WatermarkRemover.removeWatermark(
                base = base,
                watermark = wm,
                offsetX = cx,
                offsetY = cy,
                alphaAdjust = alpha,
                transparencyThreshold = trans,
                opaqueThreshold = opaque
            )
        }
        realTimePreviewBitmap = processed
    }

    fun updateBase(
        bitmap: BufferedImage?,
        file: File?,
        queueLabel: String? = if (bulkQueue.isNotEmpty()) file?.name else null
    ) {
        baseBitmap = bitmap
        baseImageFile = file
        resultBitmap = null
        realTimePreviewBitmap = null
        detectionState = DetectionState.Idle
        detectionResults = emptyList()
        detectionAlphaGuesses = emptyList()
        selectedDetectionIndices = emptySet()
        applyAllDetections = true
        currentQueueItemName = queueLabel
        isProcessing = false
        offsetX = 0f
        offsetY = 0f
    }

    fun loadNextQueueImage() {
        val next = bulkQueue.firstOrNull()
        if (next == null) {
            isLoadingQueueItem = false
            updateBase(null, null)
            return
        }
        isLoadingQueueItem = true
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadImageFromFile(next.file) }
            if (bitmap != null) {
                updateBase(bitmap, next.file, next.displayName)
                isLoadingQueueItem = false
            } else {
                isLoadingQueueItem = false
                if (bulkQueue.isNotEmpty()) bulkQueue.removeAt(0)
                lastToastMessage = "Failed to load ${next.displayName}"
                loadNextQueueImage()
            }
        }
    }

    fun advanceQueue() {
        if (bulkQueue.isEmpty()) {
            updateBase(null, null)
            return
        }
        bulkQueue.removeAt(0)
        loadNextQueueImage()
    }

    suspend fun detectWatermarkCandidates(
        base: BufferedImage,
        watermark: BufferedImage,
        threshold: Float,
        autoGuess: Boolean,
        transparencyClamp: Int,
        opaqueClamp: Int
    ): DetectionGuessResult {
        return withContext(Dispatchers.Default) {
            val primaryDetections =
                WatermarkDetector.detect(
                    base = base,
                    watermark = watermark,
                    matchThreshold = threshold.toDouble()
                )
            val relaxedThreshold = max(0.3f, threshold - 0.15f)
            val candidateDetections =
                if (autoGuess && primaryDetections.isEmpty() && relaxedThreshold < threshold) {
                    WatermarkDetector.detect(
                        base = base,
                        watermark = watermark,
                        matchThreshold = relaxedThreshold.toDouble()
                    )
                } else {
                    primaryDetections
                }
            val orderedDetections =
                candidateDetections
                    .map { detection ->
                        val guessedAlpha =
                            if (autoGuess) {
                                WatermarkAlphaGuesser.guessAlpha(
                                    base = base,
                                    watermark = watermark,
                                    offsetX = detection.offsetX.roundToInt(),
                                    offsetY = detection.offsetY.roundToInt(),
                                    transparencyThreshold = transparencyClamp,
                                    opaqueThreshold = opaqueClamp
                                )
                            } else {
                                null
                            }
                        detection to guessedAlpha
                    }
                    .sortedWith(
                        compareByDescending<Pair<WatermarkDetection, Float?>> { it.second != null }
                            .thenByDescending { it.first.score }
                    )
            DetectionGuessResult(
                detections = orderedDetections.map { it.first },
                guessedAlphas = orderedDetections.map { it.second }
            )
        }
    }

    fun clearQueue() {
        if (bulkQueue.isEmpty()) return
        bulkQueue.clear()
        updateBase(null, null)
    }

    fun startAutomation() {
        if (isAutomationRunning) return
        if (isLoadingQueueItem) {
            lastToastMessage = "Queue is loading..."
            return
        }
        val watermark = watermarkBitmap
        if (watermark == null) {
            lastToastMessage = "Automation needs a watermark"
            return
        }
        if (bulkQueue.isEmpty()) {
            lastToastMessage = "Queue is empty"
            return
        }
        isAutomationRunning = true
        automationProgress = 0
        automationTotal = bulkQueue.size
        resultBitmap = null
        scope.launch {
            fun saveFailedAutomationResult(bitmap: BufferedImage, file: File) {
                // Save with -failed suffix
                saveBitmapToFile(
                    bitmap,
                    file.parentFile,
                    file.nameWithoutExtension + "-failed." + file.extension
                )
            }
            val queueSnapshot = bulkQueue.toList()
            var savedCount = 0
            var processedCount = 0
            try {
                for (item in queueSnapshot) {
                    val base = withContext(Dispatchers.IO) { loadImageFromFile(item.file) }
                    processedCount++
                    automationProgress = processedCount
                    if (base == null) {
                        continue
                    }
                    val threshold = detectionThreshold
                    val transparencyClampInt = transparencyThreshold.roundToInt()
                    val opaqueClampInt = opaqueThreshold.roundToInt()
                    val processingResult =
                        withTimeoutOrNull(AUTOMATION_ITEM_TIMEOUT_MS) {
                            val detectionOutcome =
                                detectWatermarkCandidates(
                                    base = base,
                                    watermark = watermark,
                                    threshold = threshold,
                                    autoGuess = autoGuessAlpha,
                                    transparencyClamp = transparencyClampInt,
                                    opaqueClamp = opaqueClampInt
                                )
                            val detections = detectionOutcome.detections
                            if (detections.isEmpty()) {
                                AutomationProcessingResult.NoDetections
                            } else {
                                val offsets =
                                    collectOffsets(
                                        manualOffset = null,
                                        detectionResults = detections,
                                        applyAll = true,
                                        selectedIndices = emptySet()
                                    )
                                val manualAlphaAdjust = alphaAdjust
                                val shouldGuessAlpha = autoGuessAlpha
                                val (processedBitmap, guessedAlpha) =
                                    withContext(Dispatchers.Default) {
                                        val guessedAlphaByOffset =
                                            detectionOutcome.detections
                                                .zip(detectionOutcome.guessedAlphas)
                                                .associate { (detection, guess) ->
                                                    (detection.offsetX.roundToInt() to
                                                        detection.offsetY.roundToInt()) to guess
                                                }
                                        var firstGuessedAlpha: Float? = null
                                        val processed =
                                            offsets.fold(base) { current, detection ->
                                                val detectionKey =
                                                    detection.offsetX.roundToInt() to
                                                        detection.offsetY.roundToInt()
                                                val detectionAlpha =
                                                    if (shouldGuessAlpha) {
                                                        val hasStoredGuess =
                                                            guessedAlphaByOffset.containsKey(
                                                                detectionKey
                                                            )
                                                        val storedGuess =
                                                            guessedAlphaByOffset[detectionKey]
                                                        val guess =
                                                            if (hasStoredGuess) {
                                                                storedGuess
                                                            } else {
                                                                WatermarkAlphaGuesser.guessAlpha(
                                                                    base = base,
                                                                    watermark = watermark,
                                                                    offsetX = detectionKey.first,
                                                                    offsetY = detectionKey.second,
                                                                    transparencyThreshold =
                                                                        transparencyClampInt,
                                                                    opaqueThreshold = opaqueClampInt
                                                                )
                                                            }
                                                        if (firstGuessedAlpha == null &&
                                                                guess != null
                                                        ) {
                                                            firstGuessedAlpha = guess
                                                        }
                                                        guess ?: manualAlphaAdjust
                                                    } else {
                                                        manualAlphaAdjust
                                                    }
                                                WatermarkRemover.removeWatermark(
                                                    base = current,
                                                    watermark = watermark,
                                                    offsetX = detectionKey.first,
                                                    offsetY = detectionKey.second,
                                                    alphaAdjust = detectionAlpha,
                                                    transparencyThreshold = transparencyClampInt,
                                                    opaqueThreshold = opaqueClampInt
                                                )
                                            }
                                        processed to firstGuessedAlpha
                                    }
                                AutomationProcessingResult.Success(processedBitmap, guessedAlpha)
                            }
                        }
                    when (processingResult) {
                        null -> {
                            saveFailedAutomationResult(base, item.file)
                            continue
                        }
                        AutomationProcessingResult.NoDetections -> {
                            saveFailedAutomationResult(base, item.file)
                            continue
                        }
                        is AutomationProcessingResult.Success -> {
                            val shouldGuessAlpha = autoGuessAlpha
                            val guessedAlpha = processingResult.guessedAlpha
                            if (shouldGuessAlpha && guessedAlpha != null) {
                                alphaAdjust = guessedAlpha
                            }
                            val saved =
                                withContext(Dispatchers.IO) {
                                    saveBitmapToFile(
                                        processingResult.bitmap,
                                        item.file.parentFile,
                                        "UNWM_" + item.file.name
                                    )
                                }
                            if (saved) {
                                savedCount++
                                resultBitmap = processingResult.bitmap
                            }
                        }
                    }
                }
                lastToastMessage = "Automation done. Saved $savedCount / ${queueSnapshot.size}"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastToastMessage = "Automation failed: ${e.message}"
            } finally {
                isAutomationRunning = false
                automationProgress = 0
                automationTotal = 0
                bulkQueue.clear()
                updateBase(null, null)
                isLoadingQueueItem = false
            }
        }
    }

    LaunchedEffect(lastToastMessage) {
        // Show simple snackbar or dialog logic in main UI if needed
    }

    LaunchedEffect(
        baseBitmap,
        watermarkBitmap,
        detectionThreshold,
        autoGuessAlpha,
        transparencyThreshold,
        opaqueThreshold
    ) {
        val base = baseBitmap
        val wm = watermarkBitmap
        detectionResults = emptyList()
        detectionAlphaGuesses = emptyList()
        selectedDetectionIndices = emptySet()
        applyAllDetections = true
        if (base != null && wm != null) {
            detectionState = DetectionState.Running
            try {
                val detectionOutcome =
                    detectWatermarkCandidates(
                        base = base,
                        watermark = wm,
                        threshold = detectionThreshold,
                        autoGuess = autoGuessAlpha,
                        transparencyClamp = transparencyThreshold.roundToInt(),
                        opaqueClamp = opaqueThreshold.roundToInt()
                    )
                val detections = detectionOutcome.detections
                detectionResults = detections
                detectionAlphaGuesses = detectionOutcome.guessedAlphas
                if (detections.isEmpty()) {
                    detectionState = DetectionState.NoMatch
                } else {
                    detectionState = DetectionState.Success
                    val first = detections.first()
                    offsetX = first.offsetX
                    offsetY = first.offsetY
                    selectedDetectionIndices = setOf(0)
                    if (autoGuessAlpha) {
                        detectionOutcome.guessedAlphas.firstOrNull()?.let { guessedAlpha ->
                            if (guessedAlpha != null) {
                                alphaAdjust = guessedAlpha
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                detectionAlphaGuesses = emptyList()
                detectionState = DetectionState.Error(e.message ?: "Unknown error")
            }
        } else {
            detectionState = DetectionState.Idle
            detectionAlphaGuesses = emptyList()
        }
    }

    Scaffold(snackbarHost = { /* Implement SnackbarHost if needed using a state */}) { padding ->
        Row(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // SIDEBAR
            Column(
                modifier =
                    Modifier.weight(0.3f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (lastToastMessage != null) {
                    Text(
                        text = lastToastMessage!!,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier =
                            Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)
                    )
                }

                Text("Controls", style = MaterialTheme.typography.headlineSmall)

                // Image Selection
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            val files = openFileDialog("Select Base Image")
                            files.firstOrNull()?.let { file ->
                                bulkQueue.clear()
                                currentQueueItemName = null
                                scope.launch {
                                    val bitmap = withContext(Dispatchers.IO) { loadImageFromFile(file) }
                                    updateBase(bitmap, file)
                                }
                            }
                        },
                        enabled = !isAutomationRunning,
                        modifier = Modifier.weight(1f, fill = true)
                    ) {
                        Text("Select Image")
                    }
                    Button(
                        onClick = {
                            val files = openFileDialog("Select Watermark")
                            files.firstOrNull()?.let { file ->
                                scope.launch {
                                    val bitmap = withContext(Dispatchers.IO) { loadImageFromFile(file) }
                                    watermarkBitmap = bitmap
                                    resultBitmap = null
                                    detectionState = DetectionState.Idle
                                    detectionResults = emptyList()
                                    detectionAlphaGuesses = emptyList()
                                    selectedDetectionIndices = emptySet()
                                    applyAllDetections = true
                                }
                            }
                        },
                        enabled = !isAutomationRunning,
                        modifier = Modifier.weight(1f, fill = true)
                    ) {
                        Text("Select Watermark")
                    }
                    OutlinedButton(
                        onClick = {
                            val files = openFileDialog("Select Images Bulk", multipleMode = true)
                            if (files.isNotEmpty()) {
                                scope.launch {
                                    val queueWasEmpty = bulkQueue.isEmpty()
                                    val startIndex = bulkQueue.size
                                    var added = 0
                                    files.forEachIndexed { _, file ->
                                        bulkQueue.add(QueuedImage(file, file.name))
                                        added++
                                    }
                                    if (added > 0) {
                                        lastToastMessage = "Added $added images to queue"
                                        if (queueWasEmpty || baseBitmap == null) {
                                            loadNextQueueImage()
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isAutomationRunning,
                        modifier = Modifier.weight(1f, fill = true)
                    ) {
                        Text("Bulk Select")
                    }
                }

                if (baseBitmap != null || watermarkBitmap != null || bulkQueue.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            clearQueue()
                            baseBitmap = null
                            baseImageFile = null
                            watermarkBitmap = null
                            resultBitmap = null
                            realTimePreviewBitmap = null
                            offsetX = 0f
                            offsetY = 0f
                            detectionState = DetectionState.Idle
                            detectionResults = emptyList()
                            selectedDetectionIndices = emptySet()
                            applyAllDetections = true
                        }
                    ) {
                        Text("Reset All")
                    }
                }

                if (bulkQueue.isNotEmpty() || currentQueueItemName != null) {
                    BulkQueueCard(
                        queueSize = bulkQueue.size,
                        currentItemName = currentQueueItemName,
                        isLoadingCurrent = isLoadingQueueItem,
                        canMarkComplete =
                            !isProcessing &&
                                !isAutomationRunning &&
                                !isLoadingQueueItem &&
                                bulkQueue.isNotEmpty() &&
                                (resultBitmap != null || realTimePreviewBitmap != null),
                        isAutomationRunning = isAutomationRunning,
                        automationProgress = automationProgress,
                        automationTotal = automationTotal,
                        onMarkComplete = { advanceQueue() },
                        onSkipCurrent = { advanceQueue() },
                        onClearQueue = { clearQueue() },
                        onAutomate = { startAutomation() }
                    )
                }

                WatermarkPreviewCard(watermarkBitmap)

                DetectionCard(
                    detectionState = detectionState,
                    detectionResults = detectionResults,
                    selectedDetections = selectedDetectionIndices,
                    applyAllDetections = applyAllDetections,
                    onDetectionToggled = { index ->
                        detectionResults.getOrNull(index)?.let { detection ->
                            val next =
                                selectedDetectionIndices.toMutableSet().apply {
                                    if (!add(index)) {
                                        remove(index)
                                    }
                                }
                            if (index !in selectedDetectionIndices) {
                                offsetX = detection.offsetX
                                offsetY = detection.offsetY
                            }
                            selectedDetectionIndices = next
                        }
                    },
                    onApplyAllDetectionsChanged = { checked -> applyAllDetections = checked }
                )

                SliderCard(
                    title = "Detection Threshold",
                    value = detectionThreshold,
                    onValueChange = { value ->
                        val rounded = (value * 100).roundToInt() / 100f
                        detectionThreshold = rounded.coerceIn(0f, 1f)
                    },
                    valueRange = 0f..1f,
                    valueFormatter = { value -> String.format("%.2f", value) }
                )

                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Real Time Unwatermarking")
                            Switch(
                                checked = isRealTimeUnwatermarking,
                                onCheckedChange = { isRealTimeUnwatermarking = it }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Auto Guess Alpha")
                            Switch(
                                checked = autoGuessAlpha,
                                onCheckedChange = { isChecked -> autoGuessAlpha = isChecked }
                            )
                        }
                    }
                }

                SliderCard(
                    title = "Offset X (Manual)",
                    value = offsetX,
                    onValueChange = {
                        offsetX = it
                        selectedDetectionIndices = emptySet()
                    },
                    valueRange = -maxOffsetX..maxOffsetX,
                    valueFormatter = { value -> "${value.roundToInt()} px" },
                    allowManualInput = true
                )
                SliderCard(
                    title = "Offset Y (Manual)",
                    value = offsetY,
                    onValueChange = {
                        offsetY = it
                        selectedDetectionIndices = emptySet()
                    },
                    valueRange = -maxOffsetY..maxOffsetY,
                    valueFormatter = { value -> "${value.roundToInt()} px" },
                    allowManualInput = true
                )
                SliderCard(
                    title = "Alpha Adjust",
                    value = alphaAdjust,
                    onValueChange = { alphaAdjust = it },
                    valueRange = 0.1f..2f,
                    steps = 37,
                    valueFormatter = { value -> String.format("%.2fx", value) },
                    enabled = !autoGuessAlpha
                )
                SliderCard(
                    title = "Transparency Threshold",
                    value = transparencyThreshold,
                    onValueChange = { transparencyThreshold = it },
                    valueRange = 0f..255f,
                    steps = 254,
                    valueFormatter = { value -> value.roundToInt().toString() }
                )
                SliderCard(
                    title = "Opaque Threshold",
                    value = opaqueThreshold,
                    onValueChange = { opaqueThreshold = it },
                    valueRange = 0f..255f,
                    steps = 254,
                    valueFormatter = { value -> value.roundToInt().toString() }
                )

                Button(
                    enabled = !isProcessing && baseBitmap != null && watermarkBitmap != null,
                    onClick = {
                        val base = baseBitmap
                        val wm = watermarkBitmap
                        if (base == null || wm == null) {
                            lastToastMessage = "Images not loaded"
                            return@Button
                        }
                        isProcessing = true
                        scope.launch {
                            val manualAlphaAdjust = alphaAdjust
                            val transparencyClamp = transparencyThreshold.roundToInt()
                            val opaqueClamp = opaqueThreshold.roundToInt()
                            val shouldGuessAlpha = autoGuessAlpha
                            val (result, guessedAlpha) =
                                withContext(Dispatchers.Default) {
                                    val offsetsToApply =
                                        collectOffsets(
                                            manualOffset = WatermarkDetection(offsetX, offsetY, 1f),
                                            detectionResults = detectionResults,
                                            applyAll = applyAllDetections,
                                            selectedIndices = selectedDetectionIndices
                                        )
                                    val guessedAlphaByOffset =
                                        detectionResults
                                            .zip(detectionAlphaGuesses)
                                            .associate { (detection, guess) ->
                                                (detection.offsetX.roundToInt() to
                                                    detection.offsetY.roundToInt()) to guess
                                            }
                                    var firstGuessedAlpha: Float? = null
                                    val processed =
                                        offsetsToApply.fold(base) { currentBitmap, detection ->
                                            val detectionKey =
                                                detection.offsetX.roundToInt() to
                                                    detection.offsetY.roundToInt()
                                            val detectionAlpha =
                                                if (shouldGuessAlpha) {
                                                    val hasStoredGuess =
                                                        guessedAlphaByOffset.containsKey(
                                                            detectionKey
                                                        )
                                                    val storedGuess =
                                                        guessedAlphaByOffset[detectionKey]
                                                    val guess =
                                                        if (hasStoredGuess) {
                                                            storedGuess
                                                        } else {
                                                            WatermarkAlphaGuesser.guessAlpha(
                                                                base = base,
                                                                watermark = wm,
                                                                offsetX = detectionKey.first,
                                                                offsetY = detectionKey.second,
                                                                transparencyThreshold =
                                                                    transparencyClamp,
                                                                opaqueThreshold = opaqueClamp
                                                            )
                                                        }
                                                    if (firstGuessedAlpha == null &&
                                                            guess != null
                                                    ) {
                                                        firstGuessedAlpha = guess
                                                    }
                                                    guess ?: manualAlphaAdjust
                                                } else {
                                                    manualAlphaAdjust
                                                }
                                            WatermarkRemover.removeWatermark(
                                                base = currentBitmap,
                                                watermark = wm,
                                                offsetX = detectionKey.first,
                                                offsetY = detectionKey.second,
                                                alphaAdjust = detectionAlpha,
                                                transparencyThreshold = transparencyClamp,
                                                opaqueThreshold = opaqueClamp
                                            )
                                        }
                                    processed to firstGuessedAlpha
                                }
                            if (shouldGuessAlpha && guessedAlpha != null) {
                                alphaAdjust = guessedAlpha
                            }
                            resultBitmap = result
                            isProcessing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isProcessing) "Processing..." else "Process Image")
                }

                if (resultBitmap != null) {
                     ResultCard(
                        resultBitmap = resultBitmap,
                        onSaveResult = { bitmap ->
                            val files = openFileDialog("Save Result", mode = FileDialog.SAVE)
                            files.firstOrNull()?.let { file ->
                                scope.launch {
                                    val saved =
                                        withContext(Dispatchers.IO) {
                                            saveBitmapToFile(bitmap, file.parentFile, file.name)
                                        }
                                    lastToastMessage = if (saved) "Saved to ${file.name}" else "Save failed"
                                    if (saved && bulkQueue.isNotEmpty() && !isAutomationRunning) {
                                        advanceQueue()
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // WORKSPACE (Right side)
            Box(
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxHeight()
                    .background(Color.DarkGray)
            ) {
                if (baseBitmap != null) {
                    val base = baseBitmap!!
                    val density = LocalDensity.current

                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val containerWidth = maxWidth.value
                        val containerHeight = maxHeight.value

                        // Calculate scale to fit
                        val scale = min(containerWidth / base.width, containerHeight / base.height)
                        val displayWidth = base.width * scale
                        val displayHeight = base.height * scale

                        val imageBitmap = remember(base) { base.toComposeImageBitmap() }

                        // Draw Base Image
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "Workspace",
                            modifier = Modifier.size(displayWidth.dp, displayHeight.dp),
                            contentScale = ContentScale.FillBounds
                        )

                        // If Real Time is active and we have a preview, show it ON TOP
                        // Actually, if Real Time is active, we should just show the resultBitmap if available (which is realTimePreviewBitmap)
                        // But realTimePreviewBitmap is updated async.

                        if (isRealTimeUnwatermarking && realTimePreviewBitmap != null) {
                             Image(
                                bitmap = realTimePreviewBitmap!!.toComposeImageBitmap(),
                                contentDescription = "Preview",
                                modifier = Modifier.size(displayWidth.dp, displayHeight.dp),
                                contentScale = ContentScale.FillBounds
                             )
                        } else if (watermarkBitmap != null) {
                             // Show Draggable Watermark Overlay
                             val wm = watermarkBitmap!!
                             val wmWidth = (wm.width * scale).dp
                             val wmHeight = (wm.height * scale).dp

                             // Offset is in image pixels. Convert to dp relative to display
                             val xOffsetDp = (offsetX * scale).dp
                             val yOffsetDp = (offsetY * scale).dp

                             Box(
                                 modifier = Modifier
                                    .offset(xOffsetDp, yOffsetDp)
                                    .size(wmWidth, wmHeight)
                                    .pointerInput(base, wm, scale) {
                                        detectDragGestures { _, dragAmount ->
                                            // dragAmount is in pixels (screen pixels)
                                            // We need to convert screen pixels to image pixels
                                            // 1 dp = density pixels
                                            // scale maps 1 image pixel to 'scale' dp.
                                            // So 1 image pixel = 'scale * density' screen pixels?
                                            // Wait, maxWidth.value is in dp.
                                            // 'scale' is dp / image_pixel.
                                            // Compose 'dragAmount' is in raw pixels (screen px).
                                            // We need to convert raw px to dp, then to image px.
                                            // Or:
                                            // displayWidth (dp) corresponds to base.width (px).
                                            // displayWidthPx = displayWidth * density.
                                            // ratio = base.width / displayWidthPx.

                                            val displayWidthPx = displayWidth * density.density
                                            val ratio = base.width.toFloat() / displayWidthPx

                                            val dx = dragAmount.x * ratio
                                            val dy = dragAmount.y * ratio

                                            offsetX += dx
                                            offsetY += dy

                                            // Clear auto-selection when manually dragging
                                            selectedDetectionIndices = emptySet()
                                        }
                                    }
                             ) {
                                 // Draw watermark
                                 Image(
                                     bitmap = wm.toComposeImageBitmap(),
                                     contentDescription = "Watermark",
                                     modifier = Modifier.fillMaxSize().alpha(0.7f),
                                     contentScale = ContentScale.FillBounds
                                 )
                                 // Draw bounding box
                                 Canvas(modifier = Modifier.fillMaxSize()) {
                                     drawRect(color = Color.Green, style = Stroke(width = 2f))
                                 }
                             }
                        } else if (isRealTimeUnwatermarking && watermarkBitmap != null) {
                             // Case where RealTime is ON, but we still need the interactive overlay
                             // We show the overlay with very low alpha so bounding box and drag still work
                             val wm = watermarkBitmap!!
                             val wmWidth = (wm.width * scale).dp
                             val wmHeight = (wm.height * scale).dp

                             val xOffsetDp = (offsetX * scale).dp
                             val yOffsetDp = (offsetY * scale).dp

                             Box(
                                 modifier = Modifier
                                    .offset(xOffsetDp, yOffsetDp)
                                    .size(wmWidth, wmHeight)
                                    .pointerInput(base, wm, scale) {
                                        detectDragGestures { _, dragAmount ->
                                            val displayWidthPx = displayWidth * density.density
                                            val ratio = base.width.toFloat() / displayWidthPx

                                            val dx = dragAmount.x * ratio
                                            val dy = dragAmount.y * ratio

                                            offsetX += dx
                                            offsetY += dy

                                            selectedDetectionIndices = emptySet()
                                        }
                                    }
                             ) {
                                 // Draw bounding box only, or very faint watermark
                                 // Draw bounding box
                                 Canvas(modifier = Modifier.fillMaxSize()) {
                                     drawRect(color = Color.Green.copy(alpha=0.5f), style = Stroke(width = 2f))
                                 }
                             }
                        }
                    }
                } else {
                    Text(
                        "Load an image to start",
                        color = Color.LightGray,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

// --------------------- EXTRACTOR SCREEN IMPLEMENTATION ---------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtractorScreen() {
    var originalBaseBitmap by remember { mutableStateOf<BufferedImage?>(null) }
    var originalOverlayBitmap by remember { mutableStateOf<BufferedImage?>(null) }
    var baseBitmap by remember { mutableStateOf<BufferedImage?>(null) }
    var overlayBitmap by remember { mutableStateOf<BufferedImage?>(null) }

    var overlayOffsetX by remember { mutableStateOf(0f) }
    var overlayOffsetY by remember { mutableStateOf(0f) }
    var extractionX by remember { mutableStateOf(0f) }
    var extractionY by remember { mutableStateOf(0f) }
    var extractionWidth by remember { mutableStateOf(0f) }
    var extractionHeight by remember { mutableStateOf(0f) }

    var applyContrastStretch by remember { mutableStateOf(false) }
    var backgroundColorBase by remember { mutableStateOf(Color.Black) }
    var backgroundColorOverlay by remember { mutableStateOf(Color.White) }

    var extractedBitmap by remember { mutableStateOf<BufferedImage?>(null) }
    var isExtracting by remember { mutableStateOf(false) }
    var lastToastMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    fun clampOverlayOffset(base: BufferedImage?, overlay: BufferedImage?) {
        val baseImage = base ?: return
        val overlayImage = overlay ?: return
        val maxX = max(0, baseImage.width - overlayImage.width)
        val maxY = max(0, baseImage.height - overlayImage.height)
        overlayOffsetX = overlayOffsetX.coerceIn(0f, maxX.toFloat())
        overlayOffsetY = overlayOffsetY.coerceIn(0f, maxY.toFloat())
    }

    fun clampExtractionWindow(base: BufferedImage?) {
        val baseImage = base ?: return
        extractionWidth = extractionWidth.coerceIn(1f, baseImage.width.toFloat())
        extractionHeight = extractionHeight.coerceIn(1f, baseImage.height.toFloat())
        val maxX = max(0f, baseImage.width.toFloat() - extractionWidth)
        val maxY = max(0f, baseImage.height.toFloat() - extractionHeight)
        extractionX = extractionX.coerceIn(0f, maxX)
        extractionY = extractionY.coerceIn(0f, maxY)
    }

    LaunchedEffect(applyContrastStretch, originalBaseBitmap) {
        val original = originalBaseBitmap
        baseBitmap = if (original != null) {
            withContext(Dispatchers.Default) {
                if (applyContrastStretch) WatermarkExtractor.contrastStretch(original) else original
            }
        } else {
            null
        }
        if (baseBitmap != null && extractionWidth <= 0f && extractionHeight <= 0f) {
            extractionWidth = min(300f, baseBitmap!!.width.toFloat())
            extractionHeight = min(150f, baseBitmap!!.height.toFloat())
        }
        clampExtractionWindow(baseBitmap)
    }

    LaunchedEffect(applyContrastStretch, originalOverlayBitmap) {
        val original = originalOverlayBitmap
        overlayBitmap = if (original != null) {
            withContext(Dispatchers.Default) {
                if (applyContrastStretch) WatermarkExtractor.contrastStretch(original) else original
            }
        } else {
            null
        }
        clampOverlayOffset(baseBitmap, overlayBitmap)
    }

    LaunchedEffect(baseBitmap, overlayBitmap) {
        clampOverlayOffset(baseBitmap, overlayBitmap)
        clampExtractionWindow(baseBitmap)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (lastToastMessage != null) {
             Text(
                text = lastToastMessage!!,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Extraction Settings", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyContrastStretch, onCheckedChange = { applyContrastStretch = it })
                    Text("Apply Contrast Stretch")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val files = openFileDialog("Select Base Image")
                        files.firstOrNull()?.let { file ->
                             scope.launch {
                                originalBaseBitmap = withContext(Dispatchers.IO) { loadImageFromFile(file) }
                             }
                        }
                    }) {
                        Text("Load Base Image")
                    }
                    Button(onClick = {
                        val files = openFileDialog("Select Overlay Image")
                        files.firstOrNull()?.let { file ->
                             scope.launch {
                                originalOverlayBitmap = withContext(Dispatchers.IO) { loadImageFromFile(file) }
                             }
                        }
                    }) {
                        Text("Load Overlay Image")
                    }
                }
            }
        }

        if (baseBitmap != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                     Text("Preview & Selection", style = MaterialTheme.typography.titleMedium)
                     BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                         val density = LocalDensity.current
                         val base = baseBitmap!!
                         val imageBitmap = remember(base) { base.toComposeImageBitmap() }
                         val overlay = overlayBitmap
                         val overlayImage = remember(overlay) { overlay?.toComposeImageBitmap() }

                         // Scale logic for preview
                         val scale = min(maxWidth.value / base.width, maxHeight.value / base.height)
                         val displayWidth = base.width * scale
                         val displayHeight = base.height * scale

                         Box(modifier = Modifier.size(displayWidth.dp, displayHeight.dp)) {
                             Image(
                                 bitmap = imageBitmap,
                                 contentDescription = null,
                                 modifier = Modifier.fillMaxSize(),
                                 contentScale = ContentScale.FillBounds
                             )

                             if (overlayImage != null) {
                                 val w = (overlay!!.width * scale).dp
                                 val h = (overlay!!.height * scale).dp
                                 val x = (overlayOffsetX * scale).dp
                                 val y = (overlayOffsetY * scale).dp

                                 Image(
                                     bitmap = overlayImage,
                                     contentDescription = null,
                                     modifier = Modifier
                                         .offset(x, y)
                                         .size(w, h)
                                         .pointerInput(Unit) {
                                             detectDragGestures { _, dragAmount ->
                                                 // Adjust drag amount back to original image coordinates
                                                 // This is a rough approximation because scale is in dp/px
                                                 val ratio = base.width.toFloat() / (displayWidth * density.density)
                                                 overlayOffsetX += dragAmount.x * ratio
                                                 overlayOffsetY += dragAmount.y * ratio
                                                 clampOverlayOffset(base, overlay)
                                             }
                                         },
                                     alpha = 0.5f,
                                     contentScale = ContentScale.FillBounds
                                 )
                             }

                             // Draw extraction rect
                             Canvas(modifier = Modifier.fillMaxSize()) {
                                 val x = extractionX * scale * density.density
                                 val y = extractionY * scale * density.density
                                 val w = extractionWidth * scale * density.density
                                 val h = extractionHeight * scale * density.density
                                 drawRect(
                                     color = Color.Red,
                                     topLeft = Offset(x, y),
                                     size = Size(w, h),
                                     style = Stroke(width = 2f)
                                 )
                             }
                         }
                     }
                }
            }

            SliderCard("Window X", extractionX, { extractionX = it; clampExtractionWindow(baseBitmap) }, 0f..baseBitmap!!.width.toFloat(), valueFormatter = { "${it.roundToInt()}" })
            SliderCard("Window Y", extractionY, { extractionY = it; clampExtractionWindow(baseBitmap) }, 0f..baseBitmap!!.height.toFloat(), valueFormatter = { "${it.roundToInt()}" })
            SliderCard("Window Width", extractionWidth, { extractionWidth = it; clampExtractionWindow(baseBitmap) }, 1f..baseBitmap!!.width.toFloat(), valueFormatter = { "${it.roundToInt()}" })
            SliderCard("Window Height", extractionHeight, { extractionHeight = it; clampExtractionWindow(baseBitmap) }, 1f..baseBitmap!!.height.toFloat(), valueFormatter = { "${it.roundToInt()}" })

            Button(
                onClick = {
                    val base = baseBitmap
                    val overlay = overlayBitmap
                    if (base != null && overlay != null) {
                        val window = Rectangle(
                            extractionX.roundToInt(),
                            extractionY.roundToInt(),
                            extractionWidth.roundToInt(),
                            extractionHeight.roundToInt() // Rectangle(x, y, width, height) in AWT
                        )
                        isExtracting = true
                        scope.launch {
                             val result = withContext(Dispatchers.Default) {
                                 WatermarkExtractor.extract(
                                     base = base,
                                     overlay = overlay,
                                     offsetX = overlayOffsetX.roundToInt(),
                                     offsetY = overlayOffsetY.roundToInt(),
                                     window = window,
                                     backgroundBase = backgroundColorBase.toArgb(),
                                     backgroundOverlay = backgroundColorOverlay.toArgb()
                                 )
                             }
                             extractedBitmap = result
                             isExtracting = false
                             if (result == null) {
                                 lastToastMessage = "Extraction failed"
                             }
                        }
                    }
                },
                enabled = !isExtracting && overlayBitmap != null
            ) {
                Text(if (isExtracting) "Extracting..." else "Extract Watermark")
            }
        }

        if (extractedBitmap != null) {
            ResultCard(extractedBitmap) { bitmap ->
                 val files = openFileDialog("Save Extracted", mode = FileDialog.SAVE)
                 files.firstOrNull()?.let { file ->
                     scope.launch {
                         saveBitmapToFile(bitmap, file.parentFile, file.name)
                         lastToastMessage = "Saved to ${file.name}"
                     }
                 }
            }
        }
    }
}

// ... BulkQueueCard, PreviewCard, etc. helpers remain the same ...
// (Re-including them to ensure file completeness)

@Composable
private fun BulkQueueCard(
    queueSize: Int,
    currentItemName: String?,
    isLoadingCurrent: Boolean,
    canMarkComplete: Boolean,
    isAutomationRunning: Boolean,
    automationProgress: Int,
    automationTotal: Int,
    onMarkComplete: () -> Unit,
    onSkipCurrent: () -> Unit,
    onClearQueue: () -> Unit,
    onAutomate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Bulk Queue", style = MaterialTheme.typography.titleMedium)
            Text("Queue Size: $queueSize")
            if (isLoadingCurrent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text("Loading current item...", modifier = Modifier.padding(start = 8.dp))
                }
            } else if (!currentItemName.isNullOrBlank()) {
                Text("Current: $currentItemName")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onMarkComplete,
                    enabled = canMarkComplete,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Mark Complete")
                }
                OutlinedButton(
                    onClick = onSkipCurrent,
                    enabled = !isAutomationRunning && queueSize > 0 && !isLoadingCurrent,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Skip")
                }
            }
            TextButton(
                onClick = onClearQueue,
                enabled = !isAutomationRunning && !isLoadingCurrent,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Clear Queue")
            }
            Button(
                onClick = onAutomate,
                enabled = !isAutomationRunning && queueSize > 0 && !isLoadingCurrent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text =
                        if (isAutomationRunning) {
                            "Running Automation..."
                        } else {
                            "Start Automation"
                        }
                )
            }
            if (isAutomationRunning && automationTotal > 0) {
                Text(
                    "Progress: ${automationProgress.coerceAtMost(automationTotal)} / $automationTotal"
                )
            }
        }
    }
}

@Composable
private fun WatermarkPreviewCard(watermarkBitmap: BufferedImage?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Watermark Preview", style = MaterialTheme.typography.titleMedium)
            if (watermarkBitmap != null) {
                Image(
                    bitmap = watermarkBitmap.toComposeImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.height(100.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("No watermark loaded")
            }
        }
    }
}

@Composable
private fun SliderCard(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueFormatter: (Float) -> String,
    enabled: Boolean = true,
    allowManualInput: Boolean = false
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("$title: ${valueFormatter(value)}")
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun DetectionCard(
    detectionState: DetectionState,
    detectionResults: List<WatermarkDetection>,
    selectedDetections: Set<Int>,
    applyAllDetections: Boolean,
    onDetectionToggled: (Int) -> Unit,
    onApplyAllDetectionsChanged: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Detection", style = MaterialTheme.typography.titleMedium)
            // Implementation similar to original
            Text(detectionState.toString())
            if (detectionResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Detections: ${detectionResults.size}")
                detectionResults.forEachIndexed { index, detection ->
                    val isSelected = index in selectedDetections
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onDetectionToggled(index) }
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onDetectionToggled(index) }
                        )
                        Column {
                             Text("Score: ${"%.4f".format(detection.score)}")
                             Text("Offset: (${detection.offsetX.toInt()}, ${detection.offsetY.toInt()})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = applyAllDetections,
                        onCheckedChange = onApplyAllDetectionsChanged
                    )
                    Text("Apply all detections")
                }
            }
        }
    }
}

@Composable
private fun ResultCard(resultBitmap: BufferedImage?, onSaveResult: (BufferedImage) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Result", style = MaterialTheme.typography.titleMedium)
            if (resultBitmap != null) {
                Image(
                    bitmap = resultBitmap.toComposeImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.height(200.dp).fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
                Button(onClick = { onSaveResult(resultBitmap) }) { Text("Save Result") }
            } else {
                Text("No result yet")
            }
        }
    }
}

private fun collectOffsets(
    manualOffset: WatermarkDetection?,
    detectionResults: List<WatermarkDetection>,
    applyAll: Boolean,
    selectedIndices: Set<Int>
): List<WatermarkDetection> {
    val offsetsToApply = mutableListOf<WatermarkDetection>()
    val uniqueOffsets = mutableSetOf<Pair<Int, Int>>()
    fun addOffset(detection: WatermarkDetection) {
        val key = detection.offsetX.roundToInt() to detection.offsetY.roundToInt()
        if (uniqueOffsets.add(key)) {
            offsetsToApply.add(detection)
        }
    }
    manualOffset?.let { addOffset(it) }
    if (applyAll) {
        detectionResults.forEach { addOffset(it) }
    } else {
        selectedIndices.sorted().forEach { index ->
            detectionResults.getOrNull(index)?.let { addOffset(it) }
        }
    }
    return offsetsToApply
}

fun loadImageFromFile(file: File): BufferedImage? {
    return try {
        ImageIO.read(file)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun saveBitmapToFile(bitmap: BufferedImage, dir: File, name: String): Boolean {
    return try {
        val file = File(dir, name)
        ImageIO.write(bitmap, "png", file)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
