package com.astral.unwm

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.annotation.StringRes
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!org.opencv.android.OpenCVLoader.initLocal() && !org.opencv.android.OpenCVLoader.initDebug()) {
            Log.e("AstralUNWM", "Failed to initialize OpenCV")
        }
        setContent {
            AstralUnwmTheme {
                AstralUNWMApp()
            }
        }
    }
}

private data class QueuedImage(
    val uri: Uri,
    val displayName: String
)

private enum class AppTab(@StringRes val titleRes: Int) {
    Unwatermarker(R.string.tab_unwatermarker),
    Extractor(R.string.tab_extractor)
}

@Composable
fun AstralUNWMApp() {
    var selectedTab by remember { mutableStateOf(AppTab.Unwatermarker) }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            AppTab.values().forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { selectedTab = tab },
                    text = { Text(text = stringResource(id = tab.titleRes)) }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnwatermarkerScreen() {
    val context = LocalContext.current
    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var watermarkBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var alphaAdjust by remember { mutableFloatStateOf(1f) }
    var transparencyThreshold by remember { mutableFloatStateOf(0f) }
    var opaqueThreshold by remember { mutableFloatStateOf(255f) }
    var detectionThreshold by remember { mutableFloatStateOf(0.9f) }

    var isProcessing by remember { mutableStateOf(false) }
    var lastToastMessage by remember { mutableStateOf<String?>(null) }
    var detectionState by remember { mutableStateOf<DetectionState>(DetectionState.Idle) }
    var detectionResults by remember { mutableStateOf<List<WatermarkDetection>>(emptyList()) }
    var selectedDetectionIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var applyAllDetections by remember { mutableStateOf(true) }

    val bulkQueue = remember { mutableStateListOf<QueuedImage>() }
    var currentQueueItemName by remember { mutableStateOf<String?>(null) }
    var isLoadingQueueItem by remember { mutableStateOf(false) }
    var isAutomationRunning by remember { mutableStateOf(false) }
    var automationProgress by remember { mutableStateOf(0) }
    var automationTotal by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()

    fun updateBase(bitmap: Bitmap?, label: String?) {
        baseBitmap = bitmap
        resultBitmap = null
        detectionState = DetectionState.Idle
        detectionResults = emptyList()
        selectedDetectionIndices = emptySet()
        applyAllDetections = true
        currentQueueItemName = label
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
            val bitmap = withContext(Dispatchers.IO) { loadBitmapFromUri(context, next.uri) }
            if (bitmap != null) {
                updateBase(bitmap, next.displayName)
                isLoadingQueueItem = false
            } else {
                isLoadingQueueItem = false
                bulkQueue.removeAt(0)
                lastToastMessage = context.getString(R.string.toast_bulk_failed)
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

    fun clearQueue() {
        if (bulkQueue.isEmpty()) return
        bulkQueue.clear()
        updateBase(null, null)
    }

    fun startAutomation() {
        if (isAutomationRunning) {
            return
        }
        if (isLoadingQueueItem) {
            lastToastMessage = context.getString(R.string.toast_queue_loading)
            return
        }
        val watermark = watermarkBitmap
        if (watermark == null) {
            lastToastMessage = context.getString(R.string.toast_automation_needs_watermark)
            return
        }
        if (bulkQueue.isEmpty()) {
            lastToastMessage = context.getString(R.string.toast_bulk_empty)
            return
        }
        isAutomationRunning = true
        automationProgress = 0
        automationTotal = bulkQueue.size
        resultBitmap = null
        scope.launch {
            val queueSnapshot = bulkQueue.toList()
            var savedCount = 0
            var processedCount = 0
            try {
                queueSnapshot.forEach { item ->
                    val base = withContext(Dispatchers.IO) { loadBitmapFromUri(context, item.uri) }
                    processedCount++
                    automationProgress = processedCount
                    if (base == null) {
                        return@forEach
                    }
                    val threshold = detectionThreshold
                    val detections = withContext(Dispatchers.Default) {
                        WatermarkDetector.detect(
                            base = base,
                            watermark = watermark,
                            matchThreshold = threshold.toDouble()
                        )
                    }
                    if (detections.isEmpty()) {
                        return@forEach
                    }
                    val offsets = collectOffsets(
                        manualOffset = null,
                        detectionResults = detections,
                        applyAll = true,
                        selectedIndices = emptySet()
                    )
                    val processedBitmap = withContext(Dispatchers.Default) {
                        offsets.fold(base) { current, detection ->
                            WatermarkRemover.removeWatermark(
                                base = current,
                                watermark = watermark,
                                offsetX = detection.offsetX.roundToInt(),
                                offsetY = detection.offsetY.roundToInt(),
                                alphaAdjust = alphaAdjust,
                                transparencyThreshold = transparencyThreshold.roundToInt(),
                                opaqueThreshold = opaqueThreshold.roundToInt()
                            )
                        }
                    }
                    val saved = withContext(Dispatchers.IO) {
                        saveBitmapToGallery(context, processedBitmap)
                    }
                    if (saved) {
                        savedCount++
                        resultBitmap = processedBitmap
                    }
                }
                lastToastMessage = context.getString(
                    R.string.toast_automation_done,
                    savedCount,
                    queueSnapshot.size
                )
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                lastToastMessage = context.getString(
                    R.string.toast_automation_failed,
                    e.message ?: context.getString(R.string.toast_unknown_error)
                )
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

    val pickBaseImage = rememberImagePickerLauncher(context) { bitmap ->
        bulkQueue.clear()
        currentQueueItemName = null
        updateBase(bitmap, null)
    }
    val pickWatermark = rememberImagePickerLauncher(context) { bitmap ->
        watermarkBitmap = bitmap
        resultBitmap = null
        detectionState = DetectionState.Idle
        detectionResults = emptyList()
        selectedDetectionIndices = emptySet()
        applyAllDetections = true
    }
    val pickBulkImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val queueWasEmpty = bulkQueue.isEmpty()
            val startIndex = bulkQueue.size
            var added = 0
            uris.forEachIndexed { index, uri ->
                val displayName = resolveDisplayName(context, uri)?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.queue_item_fallback, startIndex + index + 1)
                bulkQueue.add(QueuedImage(uri, displayName))
                added++
            }
            if (added > 0) {
                lastToastMessage = context.getString(R.string.toast_bulk_loaded, added)
                if (queueWasEmpty || baseBitmap == null) {
                    loadNextQueueImage()
                }
            }
        }
    }

    LaunchedEffect(lastToastMessage) {
        lastToastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            lastToastMessage = null
        }
    }

    LaunchedEffect(baseBitmap, watermarkBitmap, detectionThreshold) {
        val base = baseBitmap
        val wm = watermarkBitmap
        detectionResults = emptyList()
        selectedDetectionIndices = emptySet()
        applyAllDetections = true
        if (base != null && wm != null) {
            detectionState = DetectionState.Running
            try {
                val detections = withContext(Dispatchers.Default) {
                    WatermarkDetector.detect(
                        base = base,
                        watermark = wm,
                        matchThreshold = detectionThreshold.toDouble()
                    )
                }
                detectionResults = detections
                detectionState = if (detections.isEmpty()) {
                    DetectionState.NoMatch
                } else {
                    val first = detections.first()
                    offsetX = first.offsetX
                    offsetY = first.offsetY
                    selectedDetectionIndices = setOf(0)
                    DetectionState.Success
                }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                detectionState = DetectionState.Error(e.message ?: "Unknown error")
            }
        } else {
            detectionState = DetectionState.Idle
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { pickBaseImage.launch("image/*") },
                enabled = !isAutomationRunning,
                modifier = Modifier.weight(1f, fill = true)
            ) {
                Text(text = stringResource(id = R.string.select_image))
            }
            Button(
                onClick = { pickWatermark.launch("image/*") },
                enabled = !isAutomationRunning,
                modifier = Modifier.weight(1f, fill = true)
            ) {
                Text(text = stringResource(id = R.string.select_watermark))
            }
            OutlinedButton(
                onClick = { pickBulkImages.launch("image/*") },
                enabled = !isAutomationRunning,
                modifier = Modifier.weight(1f, fill = true)
            ) {
                Text(text = stringResource(id = R.string.select_images_bulk))
            }
        }
        if (baseBitmap != null || watermarkBitmap != null || bulkQueue.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    clearQueue()
                    baseBitmap = null
                    watermarkBitmap = null
                    resultBitmap = null
                    offsetX = 0f
                    offsetY = 0f
                    detectionState = DetectionState.Idle
                    detectionResults = emptyList()
                    selectedDetectionIndices = emptySet()
                    applyAllDetections = true
                }) {
                    Text(text = stringResource(id = R.string.reset))
                }
            }
        }

        if (bulkQueue.isNotEmpty() || currentQueueItemName != null) {
            BulkQueueCard(
                queueSize = bulkQueue.size,
                currentItemName = currentQueueItemName,
                isLoadingCurrent = isLoadingQueueItem,
                canMarkComplete = !isProcessing && !isAutomationRunning && !isLoadingQueueItem && bulkQueue.isNotEmpty() && resultBitmap != null,
                isAutomationRunning = isAutomationRunning,
                automationProgress = automationProgress,
                automationTotal = automationTotal,
                onMarkComplete = { advanceQueue() },
                onSkipCurrent = { advanceQueue() },
                onClearQueue = { clearQueue() },
                onAutomate = { startAutomation() }
            )
        }

        PreviewCard(
            baseBitmap = baseBitmap,
            watermarkBitmap = watermarkBitmap,
            offsetX = offsetX,
            offsetY = offsetY,
            detectionResults = detectionResults,
            selectedDetectionIndices = selectedDetectionIndices,
            onSetOffset = { x, y ->
                offsetX = x
                offsetY = y
                selectedDetectionIndices = emptySet()
            }
        )
        WatermarkPreviewCard(watermarkBitmap)
        SliderCard(
            title = stringResource(id = R.string.detection_threshold),
            value = detectionThreshold,
            onValueChange = { value ->
                val rounded = (value * 100).roundToInt() / 100f
                detectionThreshold = rounded.coerceIn(0f, 1f)
            },
            valueRange = 0f..1f,
            valueFormatter = { value -> String.format("%.2f", value) }
        )
        DetectionCard(
            detectionState = detectionState,
            detectionResults = detectionResults,
            selectedDetections = selectedDetectionIndices,
            applyAllDetections = applyAllDetections,
            onDetectionToggled = { index ->
                detectionResults.getOrNull(index)?.let { detection ->
                    val next = selectedDetectionIndices.toMutableSet().apply {
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
            onApplyAllDetectionsChanged = { checked ->
                applyAllDetections = checked
            }
        )

        SliderCard(
            title = stringResource(id = R.string.offset_x),
            value = offsetX,
            onValueChange = {
                offsetX = it
                selectedDetectionIndices = emptySet()
            },
            valueRange = -1000f..1000f,
            valueFormatter = { value -> "${value.roundToInt()} px" }
        )
        SliderCard(
            title = stringResource(id = R.string.offset_y),
            value = offsetY,
            onValueChange = {
                offsetY = it
                selectedDetectionIndices = emptySet()
            },
            valueRange = -1000f..1000f,
            valueFormatter = { value -> "${value.roundToInt()} px" }
        )
        SliderCard(
            title = stringResource(id = R.string.alpha_adjust),
            value = alphaAdjust,
            onValueChange = { alphaAdjust = it },
            valueRange = 0.1f..2f,
            steps = 37,
            valueFormatter = { value -> String.format("%.2fx", value) }
        )
        SliderCard(
            title = stringResource(id = R.string.transparency_threshold),
            value = transparencyThreshold,
            onValueChange = { transparencyThreshold = it },
            valueRange = 0f..255f,
            steps = 254,
            valueFormatter = { value -> value.roundToInt().toString() }
        )
        SliderCard(
            title = stringResource(id = R.string.opaque_threshold),
            value = opaqueThreshold,
            onValueChange = { opaqueThreshold = it },
            valueRange = 0f..255f,
            steps = 254,
            valueFormatter = { value -> value.roundToInt().toString() }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                enabled = !isProcessing && baseBitmap != null && watermarkBitmap != null,
                onClick = {
                    val base = baseBitmap
                    val wm = watermarkBitmap
                    if (base == null || wm == null) {
                        Toast.makeText(
                            context,
                            R.string.no_image_loaded,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@Button
                    }
                    isProcessing = true
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            val offsetsToApply = collectOffsets(
                                manualOffset = WatermarkDetection(offsetX, offsetY, 1f),
                                detectionResults = detectionResults,
                                applyAll = applyAllDetections,
                                selectedIndices = selectedDetectionIndices
                            )
                            offsetsToApply.fold(base) { currentBitmap, detection ->
                                WatermarkRemover.removeWatermark(
                                    base = currentBitmap,
                                    watermark = wm,
                                    offsetX = detection.offsetX.roundToInt(),
                                    offsetY = detection.offsetY.roundToInt(),
                                    alphaAdjust = alphaAdjust,
                                    transparencyThreshold = transparencyThreshold.roundToInt(),
                                    opaqueThreshold = opaqueThreshold.roundToInt()
                                )
                            }
                        }
                        resultBitmap = result
                        isProcessing = false
                    }
                }
            ) {
                Text(
                    text = if (isProcessing) {
                        stringResource(id = R.string.processing)
                    } else {
                        stringResource(id = R.string.process_image)
                    }
                )
            }
        }

        ResultCard(
            resultBitmap = resultBitmap,
            onSaveResult = { bitmap ->
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        saveBitmapToGallery(context, bitmap)
                    }
                    lastToastMessage = context.getString(
                        if (saved) R.string.saved_to_gallery else R.string.save_failed
                    )
                    if (saved && bulkQueue.isNotEmpty() && !isAutomationRunning) {
                        advanceQueue()
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtractorScreen() {
    val context = LocalContext.current
    var originalBaseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var originalOverlayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var overlayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var overlayOffsetX by remember { mutableFloatStateOf(0f) }
    var overlayOffsetY by remember { mutableFloatStateOf(0f) }
    var extractionX by remember { mutableFloatStateOf(0f) }
    var extractionY by remember { mutableFloatStateOf(0f) }
    var extractionWidth by remember { mutableFloatStateOf(0f) }
    var extractionHeight by remember { mutableFloatStateOf(0f) }
    var applyContrastStretch by remember { mutableStateOf(false) }
    var defaultPosition by remember { mutableStateOf(DefaultOverlayPosition.TopLeft) }
    var overlayFilter by remember { mutableStateOf(OverlayPreviewFilter.None) }
    var blendModeOption by remember { mutableStateOf(OverlayBlendModeOption.Normal) }
    var resultFilter by remember { mutableStateOf(ResultFilterOption.None) }
    var backgroundColorBase by remember { mutableStateOf(Color.Black) }
    var backgroundColorOverlay by remember { mutableStateOf(Color.White) }
    var extractedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isExtracting by remember { mutableStateOf(false) }
    var lastToastMessage by remember { mutableStateOf<String?>(null) }
    var hasManualOverlayPosition by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    fun clampOverlayOffset(base: Bitmap?, overlay: Bitmap?) {
        val baseImage = base ?: return
        val overlayImage = overlay ?: return
        val maxX = max(0, baseImage.width - overlayImage.width)
        val maxY = max(0, baseImage.height - overlayImage.height)
        overlayOffsetX = overlayOffsetX.coerceIn(0f, maxX.toFloat())
        overlayOffsetY = overlayOffsetY.coerceIn(0f, maxY.toFloat())
    }

    fun clampExtractionWindow(base: Bitmap?) {
        val baseImage = base ?: return
        extractionWidth = extractionWidth.coerceIn(1f, baseImage.width.toFloat())
        extractionHeight = extractionHeight.coerceIn(1f, baseImage.height.toFloat())
        val maxX = max(0f, baseImage.width.toFloat() - extractionWidth)
        val maxY = max(0f, baseImage.height.toFloat() - extractionHeight)
        extractionX = extractionX.coerceIn(0f, maxX)
        extractionY = extractionY.coerceIn(0f, maxY)
    }

    fun applyDefaultPosition() {
        val baseImage = baseBitmap ?: return
        val overlayImage = overlayBitmap ?: return
        val (defaultX, defaultY) = defaultPosition.resolve(baseImage, overlayImage)
        overlayOffsetX = defaultX.toFloat()
        overlayOffsetY = defaultY.toFloat()
        clampOverlayOffset(baseImage, overlayImage)
        if (extractionWidth <= 0f || extractionHeight <= 0f) {
            extractionWidth = min(300f, baseImage.width.toFloat())
            extractionHeight = min(150f, baseImage.height.toFloat())
        }
        extractionX = overlayOffsetX.coerceIn(0f, max(0f, baseImage.width.toFloat() - extractionWidth))
        extractionY = overlayOffsetY.coerceIn(0f, max(0f, baseImage.height.toFloat() - extractionHeight))
    }

    val pickBaseImage = rememberImagePickerLauncher(context) { bitmap ->
        if (bitmap == null) {
            originalBaseBitmap = null
            baseBitmap = null
            extractedBitmap = null
            hasManualOverlayPosition = false
            return@rememberImagePickerLauncher
        }
        originalBaseBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        extractedBitmap = null
        extractionWidth = 0f
        extractionHeight = 0f
        extractionX = 0f
        extractionY = 0f
        hasManualOverlayPosition = false
    }

    val pickOverlayImage = rememberImagePickerLauncher(context) { bitmap ->
        if (bitmap == null) {
            originalOverlayBitmap = null
            overlayBitmap = null
            extractedBitmap = null
            hasManualOverlayPosition = false
            return@rememberImagePickerLauncher
        }
        originalOverlayBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        extractedBitmap = null
        hasManualOverlayPosition = false
        val baseImage = baseBitmap ?: originalBaseBitmap
        if (baseImage != null && originalOverlayBitmap != null) {
            val (defaultX, defaultY) = defaultPosition.resolveDimensions(
                baseWidth = baseImage.width,
                baseHeight = baseImage.height,
                overlayWidth = originalOverlayBitmap!!.width,
                overlayHeight = originalOverlayBitmap!!.height
            )
            overlayOffsetX = defaultX.toFloat()
            overlayOffsetY = defaultY.toFloat()
        }
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
        val baseImage = baseBitmap
        val overlayImage = overlayBitmap
        if (!hasManualOverlayPosition && baseImage != null && overlayImage != null) {
            applyDefaultPosition()
        }
    }

    LaunchedEffect(lastToastMessage) {
        lastToastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            lastToastMessage = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = stringResource(id = R.string.extractor_contrast_stretch))
                    Switch(
                        checked = applyContrastStretch,
                        onCheckedChange = {
                            applyContrastStretch = it
                        }
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(onClick = { pickBaseImage.launch("image/*") }) {
                        Text(text = stringResource(id = R.string.extractor_load_image1))
                    }
                    Button(onClick = { pickOverlayImage.launch("image/*") }) {
                        Text(text = stringResource(id = R.string.extractor_load_image2))
                    }
                }
                SettingDropdown(
                    label = stringResource(id = R.string.extractor_default_position),
                    options = DefaultOverlayPosition.values(),
                    selected = defaultPosition,
                    optionLabel = { it.labelRes },
                    onOptionSelected = {
                        defaultPosition = it
                        hasManualOverlayPosition = false
                        applyDefaultPosition()
                    }
                )
                SettingDropdown(
                    label = stringResource(id = R.string.extractor_preview_filter),
                    options = OverlayPreviewFilter.values(),
                    selected = overlayFilter,
                    optionLabel = { it.labelRes },
                    onOptionSelected = { overlayFilter = it }
                )
                SettingDropdown(
                    label = stringResource(id = R.string.extractor_preview_blend_mode),
                    options = OverlayBlendModeOption.values(),
                    selected = blendModeOption,
                    optionLabel = { it.labelRes },
                    onOptionSelected = { blendModeOption = it }
                )
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ColorPickerField(
                    label = stringResource(id = R.string.extractor_background_color1),
                    color = backgroundColorBase,
                    onColorChange = { backgroundColorBase = it }
                )
                ColorPickerField(
                    label = stringResource(id = R.string.extractor_background_color2),
                    color = backgroundColorOverlay,
                    onColorChange = { backgroundColorOverlay = it }
                )
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.extractor_preview_card_title),
                    style = MaterialTheme.typography.titleMedium
                )
                val base = baseBitmap
                if (base == null) {
                    Text(text = stringResource(id = R.string.extractor_missing_images))
                } else {
                    val baseImage = base.asImageBitmap()
                    val overlayImage = overlayBitmap?.asImageBitmap()
                    val density = LocalDensity.current
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val baseWidthPx = with(density) { maxWidth.toPx() }
                        val scale = if (base.width == 0) 1f else baseWidthPx / base.width
                        val aspectRatio = if (base.height == 0) 1f else base.width.toFloat() / base.height.toFloat()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(aspectRatio)
                        ) {
                            Image(
                                bitmap = baseImage,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                            val highlightColor = MaterialTheme.colorScheme.primary
                            val overlayBitmapLocal = overlayBitmap
                            if (overlayImage != null && overlayBitmapLocal != null) {
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(overlayBitmapLocal, scale) {
                                            detectDragGestures { _, dragAmount ->
                                                val deltaX = dragAmount.x / scale
                                                val deltaY = dragAmount.y / scale
                                                hasManualOverlayPosition = true
                                                overlayOffsetX = overlayOffsetX + deltaX
                                                overlayOffsetY = overlayOffsetY + deltaY
                                                clampOverlayOffset(baseBitmap, overlayBitmap)
                                            }
                                        }
                                ) {
                                    val overlayWidthPx = overlayBitmapLocal.width * scale
                                    val overlayHeightPx = overlayBitmapLocal.height * scale
                                    val offsetXPx = overlayOffsetX * scale
                                    val offsetYPx = overlayOffsetY * scale
                                    drawImage(
                                        image = overlayImage,
                                        srcOffset = IntOffset.Zero,
                                        srcSize = IntSize(
                                            overlayImage.width,
                                            overlayImage.height
                                        ),
                                        dstOffset = IntOffset(
                                            offsetXPx.roundToInt(),
                                            offsetYPx.roundToInt()
                                        ),
                                        dstSize = IntSize(
                                            overlayWidthPx.roundToInt(),
                                            overlayHeightPx.roundToInt()
                                        ),
                                        alpha = 0.6f,
                                        colorFilter = overlayFilter.colorFilter(),
                                        blendMode = blendModeOption.blendMode
                                    )
                                }
                            }
                            val rectWidth = extractionWidth * scale
                            val rectHeight = extractionHeight * scale
                            if (rectWidth > 0f && rectHeight > 0f) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawRect(
                                        color = highlightColor,
                                        topLeft = androidx.compose.ui.geometry.Offset(
                                            extractionX * scale,
                                            extractionY * scale
                                        ),
                                        size = androidx.compose.ui.geometry.Size(rectWidth, rectHeight),
                                        style = Stroke(width = 2.dp.toPx())
                                    )
                                }
                            }
                        }
                    }
                    if (overlayBitmap != null) {
                        Text(
                            text = stringResource(id = R.string.extractor_overlay_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        val base = baseBitmap
        if (base != null) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.extractor_window_card_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    SliderCard(
                        title = stringResource(id = R.string.extractor_window_position_x),
                        value = extractionX,
                        onValueChange = {
                            extractionX = it
                            clampExtractionWindow(baseBitmap)
                        },
                        valueRange = 0f..base.width.toFloat(),
                        valueFormatter = { value -> value.roundToInt().toString() },
                        enabled = true
                    )
                    SliderCard(
                        title = stringResource(id = R.string.extractor_window_position_y),
                        value = extractionY,
                        onValueChange = {
                            extractionY = it
                            clampExtractionWindow(baseBitmap)
                        },
                        valueRange = 0f..base.height.toFloat(),
                        valueFormatter = { value -> value.roundToInt().toString() },
                        enabled = true
                    )
                    SliderCard(
                        title = stringResource(id = R.string.extractor_window_width),
                        value = extractionWidth,
                        onValueChange = {
                            extractionWidth = it
                            clampExtractionWindow(baseBitmap)
                        },
                        valueRange = 1f..base.width.toFloat(),
                        valueFormatter = { value -> value.roundToInt().toString() },
                        enabled = true
                    )
                    SliderCard(
                        title = stringResource(id = R.string.extractor_window_height),
                        value = extractionHeight,
                        onValueChange = {
                            extractionHeight = it
                            clampExtractionWindow(baseBitmap)
                        },
                        valueRange = 1f..base.height.toFloat(),
                        valueFormatter = { value -> value.roundToInt().toString() },
                        enabled = true
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val baseImage = baseBitmap
                    val overlayImage = overlayBitmap
                    if (baseImage == null || overlayImage == null) {
                        lastToastMessage = context.getString(R.string.extractor_missing_images)
                        return@Button
                    }
                    val window = Rect(
                        extractionX.roundToInt(),
                        extractionY.roundToInt(),
                        (extractionX + extractionWidth).roundToInt(),
                        (extractionY + extractionHeight).roundToInt()
                    )
                    isExtracting = true
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            WatermarkExtractor.extract(
                                base = baseImage,
                                overlay = overlayImage,
                                offsetX = overlayOffsetX.roundToInt(),
                                offsetY = overlayOffsetY.roundToInt(),
                                window = window,
                                backgroundBase = backgroundColorBase.toArgb(),
                                backgroundOverlay = backgroundColorOverlay.toArgb()
                            )
                        }
                        isExtracting = false
                        if (result == null) {
                            lastToastMessage = context.getString(R.string.extractor_toast_extract_failed)
                        } else {
                            extractedBitmap = result
                        }
                    }
                },
                enabled = !isExtracting
            ) {
                if (isExtracting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = if (isExtracting) {
                        stringResource(id = R.string.extractor_extracting)
                    } else {
                        stringResource(id = R.string.extractor_extract_button)
                    }
                )
            }
            val result = extractedBitmap
            if (result != null) {
                Button(
                    onClick = {
                        scope.launch {
                            val bitmapForSave = withContext(Dispatchers.Default) {
                                resultFilter.applyForSaving(result)
                            }
                            val saved = withContext(Dispatchers.IO) {
                                saveBitmapToGallery(context, bitmapForSave)
                            }
                            lastToastMessage = context.getString(
                                if (saved) R.string.extractor_toast_saved else R.string.extractor_toast_save_failed
                            )
                        }
                    }
                ) {
                    Text(text = stringResource(id = R.string.extractor_save_button))
                }
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.extractor_result_card_title),
                    style = MaterialTheme.typography.titleMedium
                )
                SettingDropdown(
                    label = stringResource(id = R.string.extractor_result_filter),
                    options = ResultFilterOption.values(),
                    selected = resultFilter,
                    optionLabel = { it.labelRes },
                    onOptionSelected = { resultFilter = it }
                )
                val result = extractedBitmap
                if (result == null) {
                    Text(text = stringResource(id = R.string.extractor_no_result))
                } else {
                    val image = result.asImageBitmap()
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Fit,
                        colorFilter = resultFilter.colorFilter()
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> SettingDropdown(
    label: String,
    options: Array<T>,
    selected: T,
    optionLabel: (T) -> Int,
    onOptionSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                @StringRes val selectedLabel = optionLabel(selected)
                Text(text = stringResource(id = selectedLabel))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    @StringRes val optionLabelRes = optionLabel(option)
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = optionLabelRes)) },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorPickerField(
    label: String,
    color: Color,
    onColorChange: (Color) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .background(color, RoundedCornerShape(8.dp))
            )
            Text(
                text = String.format("#%06X", color.toArgb() and 0xFFFFFF),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        var red by remember(color) { mutableFloatStateOf(color.red * 255f) }
        var green by remember(color) { mutableFloatStateOf(color.green * 255f) }
        var blue by remember(color) { mutableFloatStateOf(color.blue * 255f) }
        ColorChannelSlider(
            label = stringResource(id = R.string.extractor_channel_red),
            value = red,
            onValueChange = { value ->
                red = value
                onColorChange(
                    Color(
                        red = red / 255f,
                        green = green / 255f,
                        blue = blue / 255f,
                        alpha = 1f
                    )
                )
            }
        )
        ColorChannelSlider(
            label = stringResource(id = R.string.extractor_channel_green),
            value = green,
            onValueChange = { value ->
                green = value
                onColorChange(
                    Color(
                        red = red / 255f,
                        green = green / 255f,
                        blue = blue / 255f,
                        alpha = 1f
                    )
                )
            }
        )
        ColorChannelSlider(
            label = stringResource(id = R.string.extractor_channel_blue),
            value = blue,
            onValueChange = { value ->
                blue = value
                onColorChange(
                    Color(
                        red = red / 255f,
                        green = green / 255f,
                        blue = blue / 255f,
                        alpha = 1f
                    )
                )
            }
        )
    }
}

@Composable
private fun ColorChannelSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "$label: ${value.roundToInt()}")
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..255f,
            steps = 254,
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

private enum class DefaultOverlayPosition(@StringRes val labelRes: Int) {
    TopLeft(R.string.extractor_position_top_left),
    TopRight(R.string.extractor_position_top_right),
    Center(R.string.extractor_position_center),
    BottomLeft(R.string.extractor_position_bottom_left),
    BottomRight(R.string.extractor_position_bottom_right);

    fun resolve(base: Bitmap, overlay: Bitmap): Pair<Int, Int> {
        return resolveDimensions(base.width, base.height, overlay.width, overlay.height)
    }

    fun resolveDimensions(
        baseWidth: Int,
        baseHeight: Int,
        overlayWidth: Int,
        overlayHeight: Int
    ): Pair<Int, Int> {
        val rawX = when (this) {
            TopLeft, BottomLeft -> 0
            TopRight, BottomRight -> baseWidth - overlayWidth
            Center -> (baseWidth - overlayWidth) / 2
        }
        val rawY = when (this) {
            TopLeft, TopRight -> 0
            BottomLeft, BottomRight -> baseHeight - overlayHeight
            Center -> (baseHeight - overlayHeight) / 2
        }
        val maxX = max(0, baseWidth - overlayWidth)
        val maxY = max(0, baseHeight - overlayHeight)
        return rawX.coerceIn(0, maxX) to rawY.coerceIn(0, maxY)
    }
}

private enum class OverlayPreviewFilter(@StringRes val labelRes: Int) {
    None(R.string.extractor_filter_none),
    Invert(R.string.extractor_filter_invert),
    InvertBrightness(R.string.extractor_filter_invert_brightness),
    Grayscale(R.string.extractor_filter_grayscale);

    fun colorFilter(): ColorFilter? = colorMatrix()?.let { ColorFilter.colorMatrix(it) }

    fun colorMatrix(): ColorMatrix? = when (this) {
        None -> null
        Invert -> invertColorMatrix()
        InvertBrightness -> invertBrightnessMatrix()
        Grayscale -> grayscaleMatrix()
    }
}

private enum class OverlayBlendModeOption(@StringRes val labelRes: Int, val blendMode: BlendMode) {
    Normal(R.string.extractor_blend_normal, BlendMode.SrcOver),
    Difference(R.string.extractor_blend_difference, BlendMode.Difference),
    Darken(R.string.extractor_blend_darken, BlendMode.Darken),
    Lighten(R.string.extractor_blend_lighten, BlendMode.Lighten),
    HardLight(R.string.extractor_blend_hard_light, BlendMode.Hardlight)
}

private enum class ResultFilterOption(@StringRes val labelRes: Int) {
    None(R.string.extractor_result_filter_none),
    Grayscale(R.string.extractor_result_filter_grayscale),
    Invert(R.string.extractor_result_filter_invert),
    Sepia(R.string.extractor_result_filter_sepia);

    fun colorFilter(): ColorFilter? = colorMatrix()?.let { ColorFilter.colorMatrix(it) }

    fun colorMatrix(): ColorMatrix? = when (this) {
        None -> null
        Grayscale -> grayscaleMatrix()
        Invert -> invertColorMatrix()
        Sepia -> sepiaMatrix()
    }
}

private fun ResultFilterOption.applyForSaving(bitmap: Bitmap): Bitmap {
    val matrix = colorMatrix() ?: return bitmap
    val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(result)
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(AndroidColorMatrix(matrix.values))
    }
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    return result
}

private fun invertColorMatrix(): ColorMatrix {
    return ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

private fun grayscaleMatrix(): ColorMatrix {
    return ColorMatrix(
        floatArrayOf(
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

private fun invertBrightnessMatrix(): ColorMatrix {
    val saturationMatrix = ColorMatrix().apply { setToSaturation(0f) }
    return concatenateColorMatrices(saturationMatrix, invertColorMatrix())
}

private fun sepiaMatrix(): ColorMatrix {
    return ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

private fun concatenateColorMatrices(first: ColorMatrix, second: ColorMatrix): ColorMatrix {
    val a = first.values
    val b = second.values
    val result = FloatArray(20)
    for (row in 0 until 4) {
        val rowIndex = row * 5
        for (col in 0 until 4) {
            val columnIndex = col
            result[rowIndex + col] =
                a[rowIndex + 0] * b[columnIndex + 0] +
                a[rowIndex + 1] * b[columnIndex + 5] +
                a[rowIndex + 2] * b[columnIndex + 10] +
                a[rowIndex + 3] * b[columnIndex + 15]
        }
        result[rowIndex + 4] =
            a[rowIndex + 0] * b[4] +
            a[rowIndex + 1] * b[9] +
            a[rowIndex + 2] * b[14] +
            a[rowIndex + 3] * b[19] +
            a[rowIndex + 4]
    }
    return ColorMatrix(result)
}

@Composable
private fun PreviewCard(
    baseBitmap: Bitmap?,
    watermarkBitmap: Bitmap?,
    offsetX: Float,
    offsetY: Float,
    detectionResults: List<WatermarkDetection>,
    selectedDetectionIndices: Set<Int>,
    onSetOffset: (Float, Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.image_preview_title),
                style = MaterialTheme.typography.titleMedium
            )
            val base = baseBitmap
            if (base == null) {
                Text(text = stringResource(id = R.string.no_base_image))
            } else {
                val baseImage: ImageBitmap = base.asImageBitmap()
                val currentWatermarkBitmap = watermarkBitmap
                val watermarkImage: ImageBitmap? = currentWatermarkBitmap?.asImageBitmap()
                val density = LocalDensity.current
                val coroutineScope = rememberCoroutineScope()
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val baseWidthPx = with(density) { maxWidth.toPx() }
                    val scale = if (base.width == 0) 1f else baseWidthPx / base.width
                    val aspectRatio = if (base.height == 0) 1f else base.width.toFloat() / base.height.toFloat()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspectRatio)
                            .pointerInput(base, currentWatermarkBitmap) {
                                if (base != null && currentWatermarkBitmap != null) {
                                    detectTapGestures { tapOffset ->
                                        val newOffsetX = (tapOffset.x / scale) - currentWatermarkBitmap.width / 2f
                                        val newOffsetY = (tapOffset.y / scale) - currentWatermarkBitmap.height / 2f
                                        onSetOffset(newOffsetX, newOffsetY)
                                        coroutineScope.launch {
                                            try {
                                                val refined = withContext(Dispatchers.Default) {
                                                    WatermarkDetector.refinePosition(
                                                        base = base,
                                                        watermark = currentWatermarkBitmap,
                                                        approximateOffsetX = newOffsetX,
                                                        approximateOffsetY = newOffsetY
                                                    )
                                                }
                                                if (refined != null) {
                                                    onSetOffset(refined.offsetX, refined.offsetY)
                                                }
                                            } catch (e: Exception) {
                                                Log.e("AstralUNWM", "Failed to refine watermark position", e)
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        Image(
                            bitmap = baseImage,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        if (currentWatermarkBitmap != null && watermarkImage != null) {
                            val widthDp = (currentWatermarkBitmap.width * scale / density.density).dp
                            val heightDp = (currentWatermarkBitmap.height * scale / density.density).dp
                            val offsetXDp = (offsetX * scale / density.density).dp
                            val offsetYDp = (offsetY * scale / density.density).dp
                            Image(
                                bitmap = watermarkImage,
                                contentDescription = null,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(offsetXDp, offsetYDp)
                                    .size(width = widthDp, height = heightDp),
                                alpha = 0.4f,
                                contentScale = ContentScale.FillBounds
                            )
                            val highlightColor = MaterialTheme.colorScheme.primary
                            val secondaryColor = MaterialTheme.colorScheme.outline
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokeWidth = 2.dp.toPx()
                                detectionResults.forEachIndexed { index, detection ->
                                    val color = if (index in selectedDetectionIndices) highlightColor else secondaryColor
                                    val left = detection.offsetX * scale
                                    val top = detection.offsetY * scale
                                    val rectWidth = currentWatermarkBitmap.width * scale
                                    val rectHeight = currentWatermarkBitmap.height * scale
                                    drawRect(
                                        color = color,
                                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                        size = androidx.compose.ui.geometry.Size(rectWidth, rectHeight),
                                        style = Stroke(width = strokeWidth)
                                    )
                                }
                            }
                            Text(
                                text = stringResource(id = R.string.tap_to_place_hint),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = stringResource(id = R.string.load_watermark_hint),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.detection_card_title),
                style = MaterialTheme.typography.titleMedium
            )
            when (detectionState) {
                DetectionState.Idle -> {
                    Text(text = stringResource(id = R.string.detection_idle_hint))
                }
                DetectionState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = stringResource(id = R.string.detection_in_progress),
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
                DetectionState.NoMatch -> {
                    Text(text = stringResource(id = R.string.detection_not_found))
                }
                is DetectionState.Error -> {
                    Text(text = stringResource(id = R.string.detection_error, detectionState.message))
                }
                DetectionState.Success -> {
                    Text(text = stringResource(id = R.string.detection_success_hint))
                    detectionResults.forEachIndexed { index, detection ->
                        val label = stringResource(
                            id = R.string.detection_match_label,
                            index + 1,
                            detection.score.toDouble()
                        )
                        if (index in selectedDetections) {
                            Button(onClick = { onDetectionToggled(index) }) {
                                Text(text = label)
                            }
                        } else {
                            OutlinedButton(onClick = { onDetectionToggled(index) }) {
                                Text(text = label)
                            }
                        }
                    }
                    if (detectionResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = applyAllDetections,
                                onCheckedChange = onApplyAllDetectionsChanged
                            )
                            Text(
                                text = stringResource(id = R.string.detection_apply_all),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.bulk_card_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(text = stringResource(id = R.string.bulk_queue_count, queueSize))
            if (isLoadingCurrent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(id = R.string.bulk_current_loading),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            } else if (!currentItemName.isNullOrBlank()) {
                Text(text = stringResource(id = R.string.bulk_current_label, currentItemName))
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
                    Text(text = stringResource(id = R.string.bulk_mark_complete))
                }
                OutlinedButton(
                    onClick = onSkipCurrent,
                    enabled = !isAutomationRunning && queueSize > 0 && !isLoadingCurrent,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(id = R.string.bulk_skip))
                }
            }
            TextButton(
                onClick = onClearQueue,
                enabled = !isAutomationRunning && !isLoadingCurrent,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(text = stringResource(id = R.string.bulk_clear))
            }
            Button(
                onClick = onAutomate,
                enabled = !isAutomationRunning && queueSize > 0 && !isLoadingCurrent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isAutomationRunning) {
                        stringResource(id = R.string.bulk_automation_running_button)
                    } else {
                        stringResource(id = R.string.bulk_automation_start)
                    }
                )
            }
            if (isAutomationRunning && automationTotal > 0) {
                Text(
                    text = stringResource(
                        id = R.string.bulk_automation_status,
                        automationProgress.coerceAtMost(automationTotal),
                        automationTotal
                    )
                )
            }
        }
    }
}

@Composable
private fun WatermarkPreviewCard(watermarkBitmap: Bitmap?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.watermark_preview_title),
                style = MaterialTheme.typography.titleMedium
            )
            val watermark = watermarkBitmap
            if (watermark == null) {
                Text(text = stringResource(id = R.string.no_watermark_loaded))
            } else {
                val watermarkImage = remember(watermark) { watermark.asImageBitmap() }
                val aspectRatio = if (watermark.height == 0) 1f else watermark.width.toFloat() / watermark.height.toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = watermarkImage,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    resultBitmap: Bitmap?,
    onSaveResult: (Bitmap) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.result_preview_title),
                style = MaterialTheme.typography.titleMedium
            )
            val result = resultBitmap
            if (result == null) {
                Text(text = stringResource(id = R.string.no_result_yet))
            } else {
                val resultImage = remember(result) { result.asImageBitmap() }
                Image(
                    bitmap = resultImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
                Button(
                    modifier = Modifier.align(Alignment.End),
                    onClick = { onSaveResult(result) }
                ) {
                    Text(text = stringResource(id = R.string.save_result))
                }
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
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "$title: ${valueFormatter(value)}")
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
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

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
    val filename = "AstralUNWM_${System.currentTimeMillis()}.png"
    val resolver = context.contentResolver
    val imageCollection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    return try {
        val uri = resolver.insert(imageCollection, contentValues) ?: return false
        resolver.openOutputStream(uri).use { outputStream ->
            if (outputStream == null) return false
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                return false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val updateValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, updateValues, null, null)
        }
        true
    } catch (ioe: IOException) {
        false
    }
}

@Composable
private fun rememberImagePickerLauncher(
    context: Context,
    onBitmapLoaded: (Bitmap?) -> Unit
) =
    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            onBitmapLoaded(null)
            return@rememberLauncherForActivityResult
        }
        val bitmap = loadBitmapFromUri(context, uri)
        onBitmapLoaded(bitmap)
    }

private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.buffered().use { bufferedStream ->
                BitmapFactory.decodeStream(bufferedStream)?.copy(Bitmap.Config.ARGB_8888, true)
            }
        }
    } catch (e: Exception) {
        null
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                cursor.getString(index)
            } else {
                null
            }
        }
}
