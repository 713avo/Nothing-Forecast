package com.example.ntiempo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.ntiempo.ui.theme.NTiempoTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val SPEED_PRESETS = listOf(
    SpeedPreset("0.25s", 250),
    SpeedPreset("0.5s", 500),
    SpeedPreset("1s", 1000),
    SpeedPreset("2s", 2000),
    SpeedPreset("Custom", null)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NTiempoTheme(darkTheme = true, dynamicColor = false) {
                TimelapseApp()
            }
        }
    }
}

@Composable
fun TimelapseApp() {
    val context = LocalContext.current
    val controller = remember { TimelapseController(context) }
    DisposableEffect(Unit) {
        controller.start()
        onDispose { controller.stop() }
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        TimelapseScreen(controller)
    }
}

@Composable
private fun TimelapseScreen(controller: TimelapseController) {
    val scrollState = rememberScrollState()
    val snapshotLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
            if (it != null) {
                controller.saveSnapshot(it)
            }
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
    ) {
        Text(
            text = controller.forecastLabel(),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = controller.updatedAtText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        TimelapseImageViewer(
            bitmap = controller.currentBitmap,
            zoomed = controller.zoomed,
            selectionRect = controller.selectionRect,
            onSelectionChange = controller::updateSelection,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .background(MaterialTheme.colorScheme.surface)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PlaybackControls(controller)
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            PlaybackSettings(controller)
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            FrameSlider(controller)
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            BaseSettings(
                controller = controller
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            ZoomControls(controller)
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            RegionControls(controller)
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            ExportControls(
                controller = controller,
                onSnapshot = { snapshotLauncher.launch("snapshot.png") }
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            StatusRow(controller)
        }
    }
}

@Composable
private fun PlaybackControls(controller: TimelapseController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = controller::prevFrame) { Text("Prev") }
        Button(onClick = { controller.updatePlaying(!controller.isPlaying) }) {
            Text(if (controller.isPlaying) "Pause" else "Play")
        }
        OutlinedButton(onClick = controller::nextFrameManual) { Text("Next") }
        OutlinedButton(onClick = { controller.jumpHours(-24) }) { Text("-24h") }
        OutlinedButton(onClick = { controller.jumpHours(24) }) { Text("+24h") }
    }
}

@Composable
private fun PlaybackSettings(controller: TimelapseController) {
    var loopExpanded by remember { mutableStateOf(false) }
    var presetExpanded by remember { mutableStateOf(false) }
    var speedText by remember { mutableStateOf(controller.speedMs.toString()) }

    LaunchedEffect(controller.speedMs) {
        val next = controller.speedMs.toString()
        if (speedText != next) {
            speedText = next
        }
    }

    val presetLabel = SPEED_PRESETS.firstOrNull { it.value == controller.speedMs }?.label
        ?: "Custom"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DropdownField(
                label = "Loop",
                value = controller.loopMode.label,
                expanded = loopExpanded,
                onExpandedChange = { loopExpanded = it },
                options = LoopMode.entries.map { it.label },
                onOptionSelected = { selected ->
                    controller.updateLoopMode(LoopMode.fromLabel(selected))
                },
                modifier = Modifier.weight(1f)
            )
            DropdownField(
                label = "Preset",
                value = presetLabel,
                expanded = presetExpanded,
                onExpandedChange = { presetExpanded = it },
                options = SPEED_PRESETS.map { it.label },
                onOptionSelected = { selected ->
                    val preset = SPEED_PRESETS.firstOrNull { it.label == selected }
                    if (preset?.value != null) {
                        controller.updateSpeedMs(preset.value)
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = speedText,
            onValueChange = { value ->
                speedText = value
                value.toIntOrNull()?.let { controller.updateSpeedMs(it) }
            },
            label = { Text("Frame ms") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
private fun FrameSlider(controller: TimelapseController) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Frame: ${controller.currentIndex + 1}/${controller.offsetsCount()}",
            style = MaterialTheme.typography.labelLarge
        )
        androidx.compose.material3.Slider(
            value = controller.currentIndex.toFloat(),
            onValueChange = {
                if (controller.isPlaying) {
                    controller.updatePlaying(false)
                }
                controller.setFrameIndex(it.roundToInt())
            },
            valueRange = 0f..(controller.offsetsCount() - 1).toFloat(),
            steps = max(0, controller.offsetsCount() - 2)
        )
    }
}

@Composable
private fun BaseSettings(controller: TimelapseController) {
    var refreshText by remember { mutableStateOf(controller.refreshMinutes.toString()) }

    LaunchedEffect(controller.refreshMinutes) {
        val next = controller.refreshMinutes.toString()
        if (refreshText != next) {
            refreshText = next
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "OCR detectada: ${controller.ocrLabel()}",
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = "Base calculada: ${controller.baseLabel()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = refreshText,
                onValueChange = { value ->
                    refreshText = value
                    value.toIntOrNull()?.let { controller.updateRefreshMinutes(it) }
                },
                label = { Text("Refresh min") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedButton(onClick = controller::refreshImages) {
                Text("Refresh now")
            }
        }
    }
}

@Composable
private fun ZoomControls(controller: TimelapseController) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = controller.zoomed,
                onCheckedChange = { controller.updateZoomed(it) },
                enabled = controller.selectionRect != null
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Zoom selection")
        }
        OutlinedButton(
            onClick = controller::clearSelection,
            enabled = controller.selectionRect != null
        ) {
            Text("Clear selection")
        }
    }
}

@Composable
private fun RegionControls(controller: TimelapseController) {
    var expanded by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf("") }
    val selectedIndex = controller.selectedRegionIndex
    val selectedLabel =
        controller.regionPresets.getOrNull(selectedIndex)?.name ?: "Regions"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { expanded = true }
                ) {
                    Text(selectedLabel)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (controller.regionPresets.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No regions saved") },
                            onClick = { },
                            enabled = false
                        )
                    } else {
                        controller.regionPresets.forEachIndexed { index, preset ->
                            DropdownMenuItem(
                                text = { Text(preset.name) },
                                onClick = {
                                    controller.applyRegionPreset(index)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = {
                nameText = ""
                showDialog = true
            }) {
                Text("Save region")
            }
            OutlinedButton(
                onClick = {
                    if (selectedIndex in controller.regionPresets.indices) {
                        controller.deleteRegionPreset(selectedIndex)
                    }
                },
                enabled = selectedIndex in controller.regionPresets.indices
            ) {
                Text("Delete")
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Save region") },
            text = {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    controller.saveRegionPreset(nameText)
                    showDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ExportControls(controller: TimelapseController, onSnapshot: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onSnapshot) { Text("Snapshot") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = controller.exportUseSelection,
                    onCheckedChange = { controller.updateExportUseSelection(it) },
                    enabled = controller.selectionRect != null
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Use selection")
            }
        }
    }
}

@Composable
private fun StatusRow(controller: TimelapseController) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = controller.statusText,
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = controller.progressText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TimelapseImageViewer(
    bitmap: android.graphics.Bitmap?,
    zoomed: Boolean,
    selectionRect: Rect?,
    onSelectionChange: (Rect?) -> Unit,
    modifier: Modifier = Modifier
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val viewport = remember(containerSize, bitmap) {
        if (bitmap == null || containerSize.width == 0 || containerSize.height == 0) {
            null
        } else {
            computeViewport(containerSize, bitmap)
        }
    }
    val displayBitmap = remember(bitmap, zoomed, selectionRect) {
        if (bitmap != null && zoomed && selectionRect != null) {
            cropBitmap(bitmap, selectionRect)
        } else {
            bitmap
        }
    }

    val pointerModifier = if (bitmap != null && viewport != null && !zoomed) {
        Modifier.pointerInput(bitmap, viewport, zoomed) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = viewport.toImage(down.position)
                var end = start
                onSelectionChange(rectFromPoints(start, end))
                drag(down.id) { change ->
                    end = viewport.toImage(change.position)
                    onSelectionChange(rectFromPoints(start, end))
                    change.consume()
                }
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .then(pointerModifier)
    ) {
        if (displayBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = displayBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                alignment = Alignment.Center
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Sin imagen")
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (viewport != null && selectionRect != null && !zoomed) {
                val screenRect = viewport.toScreen(selectionRect)
                drawRect(
                    color = Color(1f, 0f, 0f, 0.2f),
                    topLeft = Offset(screenRect.left, screenRect.top),
                    size = androidx.compose.ui.geometry.Size(
                        screenRect.width,
                        screenRect.height
                    )
                )
                drawRect(
                    color = Color.Red,
                    topLeft = Offset(screenRect.left, screenRect.top),
                    size = androidx.compose.ui.geometry.Size(
                        screenRect.width,
                        screenRect.height
                    ),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                    )
                )
            }
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("$label: $value")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

private data class SpeedPreset(val label: String, val value: Int?)

private data class ImageViewport(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val imageWidth: Float,
    val imageHeight: Float
) {
    fun toImage(point: Offset): Offset {
        val x = ((point.x - offsetX) / scale).coerceIn(0f, imageWidth)
        val y = ((point.y - offsetY) / scale).coerceIn(0f, imageHeight)
        return Offset(x, y)
    }

    fun toScreen(rect: Rect): Rect {
        val left = offsetX + rect.left * scale
        val top = offsetY + rect.top * scale
        val right = offsetX + rect.right * scale
        val bottom = offsetY + rect.bottom * scale
        return Rect(left, top, right, bottom)
    }
}

private fun computeViewport(size: IntSize, bitmap: android.graphics.Bitmap): ImageViewport {
    val containerWidth = size.width.toFloat()
    val containerHeight = size.height.toFloat()
    val imageWidth = bitmap.width.toFloat()
    val imageHeight = bitmap.height.toFloat()
    val scale = min(containerWidth / imageWidth, containerHeight / imageHeight)
    val displayWidth = imageWidth * scale
    val displayHeight = imageHeight * scale
    val offsetX = (containerWidth - displayWidth) / 2f
    val offsetY = (containerHeight - displayHeight) / 2f
    return ImageViewport(scale, offsetX, offsetY, imageWidth, imageHeight)
}

private fun rectFromPoints(start: Offset, end: Offset): Rect {
    val left = min(start.x, end.x)
    val top = min(start.y, end.y)
    val right = max(start.x, end.x)
    val bottom = max(start.y, end.y)
    return Rect(left, top, right, bottom)
}
