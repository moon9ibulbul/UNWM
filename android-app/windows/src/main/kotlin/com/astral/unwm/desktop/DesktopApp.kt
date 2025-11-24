package com.astral.unwm.desktop

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.SwingUtilities

fun main() = SwingUtilities.invokeLater {
    initOpenCv()
    androidx.compose.ui.window.singleWindowApplication(title = "AstralUNWM") {
        MaterialTheme {
            DesktopApp()
        }
    }
}

enum class DesktopTab { Unwatermarker, Extractor }

@Composable
fun DesktopApp() {
    var selectedTab by remember { mutableStateOf(DesktopTab.Unwatermarker) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            Tab(
                selected = selectedTab == DesktopTab.Unwatermarker,
                onClick = { selectedTab = DesktopTab.Unwatermarker },
                text = { Text("Unwatermarker") }
            )
            Tab(
                selected = selectedTab == DesktopTab.Extractor,
                onClick = { selectedTab = DesktopTab.Extractor },
                text = { Text("Extractor") }
            )
        }
        when (selectedTab) {
            DesktopTab.Unwatermarker -> UnwatermarkerPanel()
            DesktopTab.Extractor -> ExtractorPanel()
        }
    }
}

@Composable
private fun ImagePreview(title: String, image: BufferedImage?, modifier: Modifier = Modifier) {
    Card(modifier = modifier.padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (image == null) {
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No image")
                }
            } else {
                Image(
                    bitmap = image.toImageBitmap(),
                    contentDescription = title,
                    modifier = Modifier.size(260.dp)
                )
            }
        }
    }
}

private fun pickImage(): BufferedImage? {
    val frame = JFrame()
    val dialog = FileDialog(frame as Frame, "Select image", FileDialog.LOAD)
    dialog.isVisible = true
    val file = dialog.files.firstOrNull()
    dialog.dispose()
    frame.dispose()
    return file?.let { loadImage(it) }
}

@Composable
private fun UnwatermarkerPanel() {
    var baseImage by remember { mutableStateOf<BufferedImage?>(null) }
    var watermarkImage by remember { mutableStateOf<BufferedImage?>(null) }
    var resultImage by remember { mutableStateOf<BufferedImage?>(null) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var transparencyThreshold by remember { mutableFloatStateOf(0f) }
    var opaqueThreshold by remember { mutableFloatStateOf(255f) }
    var alphaAdjust by remember { mutableFloatStateOf(1f) }
    var autoGuessAlpha by remember { mutableStateOf(true) }
    var detectionThreshold by remember { mutableFloatStateOf(0.9f) }
    var detectionResults by remember { mutableStateOf<List<WatermarkDetection>>(emptyList()) }
    var guessedAlpha by remember { mutableFloatStateOf(1f) }
    var isProcessing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { baseImage = pickImage() }) { Text("Load base image") }
            OutlinedButton(onClick = { watermarkImage = pickImage() }) { Text("Load watermark") }
            if (baseImage != null && watermarkImage != null) {
                Button(onClick = {
                    detectionResults = DesktopWatermarkDetector.detect(
                        baseImage!!,
                        watermarkImage!!,
                        maxResults = 10,
                        matchThreshold = detectionThreshold.toDouble(),
                        alphaThreshold = 5.0
                    )
                    detectionResults.firstOrNull()?.let {
                        offsetX = it.offsetX
                        offsetY = it.offsetY
                    }
                }) { Text("Detect") }
            }
        }

        if (detectionResults.isNotEmpty()) {
            Text(
                "Top detection: x=${'$'}{offsetX.toInt()}, y=${'$'}{offsetY.toInt()}, score=${'$'}{detectionResults.first().score}",
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ImagePreview("Base", baseImage)
            ImagePreview("Watermark", watermarkImage)
            ImagePreview("Result", resultImage)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Offset X")
            Slider(value = offsetX, onValueChange = { offsetX = it }, valueRange = -2000f..2000f)
            Text(offsetX.toInt().toString())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Offset Y")
            Slider(value = offsetY, onValueChange = { offsetY = it }, valueRange = -2000f..2000f)
            Text(offsetY.toInt().toString())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Alpha")
            Slider(value = alphaAdjust, onValueChange = { alphaAdjust = it }, valueRange = 0f..2f)
            Text(alphaAdjust.toString())
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = autoGuessAlpha, onCheckedChange = { autoGuessAlpha = it })
            Text("Auto guess alpha")
            TextButton(onClick = {
                val base = baseImage
                val wm = watermarkImage
                if (base != null && wm != null) {
                    DesktopWatermarkAlphaGuesser.guessAlpha(
                        base,
                        wm,
                        offsetX.toInt(),
                        offsetY.toInt(),
                        transparencyThreshold.toInt(),
                        opaqueThreshold.toInt()
                    )?.let { guessedAlpha = it; alphaAdjust = it }
                }
            }) { Text("Guess now") }
            Text("Guess: ${'$'}{guessedAlpha}")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Transparent clamp")
            Slider(value = transparencyThreshold, onValueChange = { transparencyThreshold = it }, valueRange = 0f..255f)
            Text(transparencyThreshold.toInt().toString())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Opaque clamp")
            Slider(value = opaqueThreshold, onValueChange = { opaqueThreshold = it }, valueRange = 0f..255f)
            Text(opaqueThreshold.toInt().toString())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Detection threshold")
            Slider(value = detectionThreshold, onValueChange = { detectionThreshold = it }, valueRange = 0.5f..0.99f)
            Text(detectionThreshold.toString())
        }

        Button(
            onClick = {
                val base = baseImage
                val wm = watermarkImage
                if (base != null && wm != null) {
                    isProcessing = true
                    coroutineScope.launch {
                        resultImage = withContext(Dispatchers.Default) {
                            DesktopWatermarkRemover.removeWatermark(
                                base,
                                wm,
                                offsetX.toInt(),
                                offsetY.toInt(),
                                alphaAdjust,
                                transparencyThreshold.toInt(),
                                opaqueThreshold.toInt()
                            )
                        }
                        isProcessing = false
                    }
                }
            },
            enabled = !isProcessing && baseImage != null && watermarkImage != null
        ) { Text("Apply unwatermark") }
    }
}

@Composable
private fun ExtractorPanel() {
    var baseImage by remember { mutableStateOf<BufferedImage?>(null) }
    var overlayImage by remember { mutableStateOf<BufferedImage?>(null) }
    var extractedImage by remember { mutableStateOf<BufferedImage?>(null) }
    var windowLeft by remember { mutableFloatStateOf(0f) }
    var windowTop by remember { mutableFloatStateOf(0f) }
    var windowWidth by remember { mutableFloatStateOf(256f) }
    var windowHeight by remember { mutableFloatStateOf(256f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var bgBase by remember { mutableStateOf("0,0,0") }
    var bgOverlay by remember { mutableStateOf("255,255,255") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { baseImage = pickImage() }) { Text("Load base") }
            OutlinedButton(onClick = { overlayImage = pickImage() }) { Text("Load overlay") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ImagePreview("Base", baseImage)
            ImagePreview("Overlay", overlayImage)
            ImagePreview("Extracted", extractedImage)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Window left")
            Slider(value = windowLeft, onValueChange = { windowLeft = it }, valueRange = 0f..4000f)
            Text(windowLeft.toInt().toString())
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Window top")
            Slider(value = windowTop, onValueChange = { windowTop = it }, valueRange = 0f..4000f)
            Text(windowTop.toInt().toString())
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Window width")
            Slider(value = windowWidth, onValueChange = { windowWidth = it }, valueRange = 16f..4000f)
            Text(windowWidth.toInt().toString())
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Window height")
            Slider(value = windowHeight, onValueChange = { windowHeight = it }, valueRange = 16f..4000f)
            Text(windowHeight.toInt().toString())
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Offset X")
            Slider(value = offsetX, onValueChange = { offsetX = it }, valueRange = -2000f..2000f)
            Text(offsetX.toInt().toString())
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Offset Y")
            Slider(value = offsetY, onValueChange = { offsetY = it }, valueRange = -2000f..2000f)
            Text(offsetY.toInt().toString())
        }
        OutlinedTextField(value = bgBase, onValueChange = { bgBase = it }, label = { Text("Base background r,g,b") })
        OutlinedTextField(value = bgOverlay, onValueChange = { bgOverlay = it }, label = { Text("Overlay background r,g,b") })

        Button(
            onClick = {
                val base = baseImage
                val overlay = overlayImage
                if (base != null && overlay != null) {
                    val baseColor = parseColor(bgBase)
                    val overlayColor = parseColor(bgOverlay)
                    extractedImage = DesktopWatermarkExtractor.extract(
                        base,
                        overlay,
                        offsetX.toInt(),
                        offsetY.toInt(),
                        windowLeft.toInt(),
                        windowTop.toInt(),
                        windowWidth.toInt(),
                        windowHeight.toInt(),
                        baseColor,
                        overlayColor
                    )?.let { DesktopWatermarkExtractor.contrastStretch(it) }
                }
            },
            enabled = baseImage != null && overlayImage != null
        ) { Text("Extract watermark") }
    }
}

private fun parseColor(csv: String): IntArray {
    val parts = csv.split(",").mapNotNull { it.trim().toIntOrNull() }
    return intArrayOf(
        parts.getOrElse(0) { 0 }.coerceIn(0, 255),
        parts.getOrElse(1) { 0 }.coerceIn(0, 255),
        parts.getOrElse(2) { 0 }.coerceIn(0, 255)
    )
}
