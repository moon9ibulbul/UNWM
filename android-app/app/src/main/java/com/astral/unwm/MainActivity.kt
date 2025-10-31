package com.astral.unwm

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AstralUnwmTheme {
                AstralUNWMApp()
            }
        }
    }
}

@Composable
fun AstralUNWMApp() {
    val context = LocalContext.current
    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var watermarkBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var alphaAdjust by remember { mutableFloatStateOf(1f) }
    var transparencyThreshold by remember { mutableFloatStateOf(10f) }
    var opaqueThreshold by remember { mutableFloatStateOf(230f) }

    var isProcessing by remember { mutableStateOf(false) }
    var lastSaveMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    val pickBaseImage = rememberImagePickerLauncher(context) { bitmap ->
        baseBitmap = bitmap
        resultBitmap = null
    }
    val pickWatermark = rememberImagePickerLauncher(context) { bitmap ->
        watermarkBitmap = bitmap
        resultBitmap = null
    }

    LaunchedEffect(lastSaveMessage) {
        lastSaveMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            lastSaveMessage = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { pickBaseImage.launch("image/*") }) {
                Text(text = stringResource(id = R.string.select_image))
            }
            Button(onClick = { pickWatermark.launch("image/*") }) {
                Text(text = stringResource(id = R.string.select_watermark))
            }
            if (baseBitmap != null || watermarkBitmap != null) {
                TextButton(onClick = {
                    baseBitmap = null
                    watermarkBitmap = null
                    resultBitmap = null
                    offsetX = 0f
                    offsetY = 0f
                }) {
                    Text(text = stringResource(id = R.string.reset))
                }
            }
        }

        PreviewCard(baseBitmap, watermarkBitmap, offsetX, offsetY)
        WatermarkPreviewCard(watermarkBitmap)

        SliderCard(
            title = stringResource(id = R.string.offset_x),
            value = offsetX,
            onValueChange = { offsetX = it },
            valueRange = -1000f..1000f,
            valueFormatter = { value -> "${value.roundToInt()} px" }
        )
        SliderCard(
            title = stringResource(id = R.string.offset_y),
            value = offsetY,
            onValueChange = { offsetY = it },
            valueRange = -1000f..1000f,
            valueFormatter = { value -> "${value.roundToInt()} px" }
        )
        SliderCard(
            title = stringResource(id = R.string.alpha_adjust),
            value = alphaAdjust,
            onValueChange = { alphaAdjust = it },
            valueRange = 0.5f..2f,
            steps = 15,
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
                            WatermarkRemover.removeWatermark(
                                base = base,
                                watermark = wm,
                                offsetX = offsetX.roundToInt(),
                                offsetY = offsetY.roundToInt(),
                                alphaAdjust = alphaAdjust,
                                transparencyThreshold = transparencyThreshold.roundToInt(),
                                opaqueThreshold = opaqueThreshold.roundToInt()
                            )
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
                    lastSaveMessage = context.getString(
                        if (saved) R.string.saved_to_gallery else R.string.save_failed
                    )
                }
            }
        )
    }
}

@Composable
private fun PreviewCard(
    baseBitmap: Bitmap?,
    watermarkBitmap: Bitmap?,
    offsetX: Float,
    offsetY: Float
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
                val baseImage: ImageBitmap = remember(base) { base.asImageBitmap() }
                val currentWatermarkBitmap = watermarkBitmap
                val watermarkImage: ImageBitmap? = remember(currentWatermarkBitmap) { currentWatermarkBitmap?.asImageBitmap() }
                val density = LocalDensity.current
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
    valueFormatter: (Float) -> String
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
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
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
        val bitmap = context.contentResolver.openInputStream(uri)?.use(InputStream::buffered)?.use { input ->
            BitmapFactory.decodeStream(input)?.copy(Bitmap.Config.ARGB_8888, true)
        }
        onBitmapLoaded(bitmap)
    }
