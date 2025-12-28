package com.example.ntiempo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

private const val BASE_URL =
    "https://services.meteored.com/img/models/ecmwf/ECMWF_%03d_ES_SFC_es-ES_es.png"
private const val DEFAULT_MAX_CONCURRENT = 4
private const val NEIGHBOR_PREFETCH = 2
private const val MIN_SELECTION_PX = 10f
private const val SETTINGS_NAME = "timelapse_settings"
private const val CACHE_DIR_NAME = "forecast_cache"

data class RectRatio(val left: Float, val top: Float, val width: Float, val height: Float)

data class RegionPreset(val name: String, val ratio: RectRatio)

enum class LoopMode(val label: String) {
    LOOP("Loop"),
    PING_PONG("Ping-pong"),
    STOP("Stop");

    companion object {
        fun fromLabel(label: String?): LoopMode {
            return entries.firstOrNull { it.label == label } ?: LOOP
        }
    }
}

@Suppress("LongParameterList")
data class SettingsSnapshot(
    val useUtc: Boolean,
    val speedMs: Int,
    val refreshMin: Int,
    val autoBase: Boolean,
    val loopMode: LoopMode,
    val currentIndex: Int,
    val baseEpochSeconds: Long,
    val zoomed: Boolean,
    val selectionRatio: RectRatio?,
    val regionPresets: List<RegionPreset>,
    val exportUseSelection: Boolean
)

private data class HeaderInfo(val etag: String?, val lastModified: String?)

private sealed class FetchResult {
    data class Updated(val offset: Int, val bytes: ByteArray, val headers: HeaderInfo) : FetchResult()
    data class NotModified(val offset: Int) : FetchResult()
    data class Error(val offset: Int, val message: String) : FetchResult()
}

private data class OcrTimes(val baseInstant: Instant?, val validInstant: Instant?)

private data class OcrResult(val baseInstant: Instant, val validInstant: Instant)

class TimelapseController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)
    private val cache = ForecastCache(cacheDir)
    private val metadata = MetadataCache(File(cacheDir, "metadata.json"))
    private val ocrCache = OcrCache(File(cacheDir, "ocr_times.json"))
    private val settings = SettingsStore(appContext)
    private val textRecognizer = runCatching {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }.getOrNull()
    private val refreshInProgress = AtomicBoolean(false)
    private val offsets = buildOffsets()

    private var refreshJob: Job? = null
    private var playJob: Job? = null
    private var fetchJob: Job? = null
    private var playDirection = 1
    private var pendingSelectionRatio: RectRatio? = null
    private var statusMessage = "Estado: listo"
    private val ocrJobs = mutableMapOf<Int, Job>()
    private val ocrByOffset = mutableStateMapOf<Int, Instant>()

    var currentIndex by mutableStateOf(0)
        private set
    var currentBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var loopMode by mutableStateOf(LoopMode.LOOP)
        private set
    var speedMs by mutableStateOf(500)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var refreshMinutes by mutableStateOf(15)
        private set
    var useUtc by mutableStateOf(false)
        private set
    var autoBase by mutableStateOf(false)
        private set
    var baseUtc by mutableStateOf(lastCycleUtc(Instant.now()))
        private set
    var zoomed by mutableStateOf(false)
        private set
    var selectionRect by mutableStateOf<Rect?>(null)
        private set
    var selectionRatio by mutableStateOf<RectRatio?>(null)
        private set
    var statusText by mutableStateOf(statusMessage)
        private set
    var progressText by mutableStateOf("Progreso: -")
        private set
    var updatedAtText by mutableStateOf("Ultima actualizacion: -")
        private set
    var exportUseSelection by mutableStateOf(false)
        private set
    var selectedRegionIndex by mutableStateOf(-1)
        private set

    val regionPresets = mutableStateListOf<RegionPreset>()
    val ocrAvailable = textRecognizer != null

    fun start() {
        val snapshot = settings.load(lastCycleUtc(Instant.now()))
        applySettings(snapshot)
        ocrByOffset.clear()
        ocrByOffset.putAll(ocrCache.loadAll())
        loadCurrentFrame()
        startRefreshTimer()
        if (autoBase) {
            applyAutoBase()
        }
        refreshImages()
    }

    fun stop() {
        saveSettings()
        ocrJobs.values.forEach { it.cancel() }
        ocrJobs.clear()
        textRecognizer?.close()
        scope.cancel()
    }

    fun forecastLabel(): String {
        val offset = offsets[currentIndex]
        val forecastUtc = baseUtc.plusSeconds(offset * 3600L)
        val resolved = ocrByOffset[offset] ?: forecastUtc
        return "Prediccion: ${formatOcrInstant(resolved)} (T+%03dh)".format(offset)
    }

    fun baseLabel(): String {
        val offset = offsets[currentIndex]
        val baseInstant = ocrByOffset[offset]?.minusSeconds(offset * 3600L) ?: baseUtc
        return formatOcrInstant(baseInstant)
    }

    fun ocrLabel(): String {
        val instant = ocrByOffset[offsets[currentIndex]] ?: return "-"
        return formatOcrInstant(instant)
    }

    fun offsetsCount(): Int = offsets.size

    fun setFrameIndex(index: Int) {
        currentIndex = index.coerceIn(0, offsets.lastIndex)
        loadCurrentFrame()
    }

    fun prevFrame() {
        playDirection = -1
        setFrameIndex(floorMod(currentIndex - 1, offsets.size))
    }

    fun nextFrameManual() {
        playDirection = 1
        setFrameIndex(floorMod(currentIndex + 1, offsets.size))
    }

    fun jumpHours(hours: Int) {
        val steps = hours / 6
        setFrameIndex(floorMod(currentIndex + steps, offsets.size))
    }

    fun updateLoopMode(mode: LoopMode) {
        loopMode = mode
    }

    fun updateSpeedMs(value: Int) {
        val clamped = value.coerceIn(100, 5000)
        speedMs = clamped
        if (isPlaying) {
            startPlayback()
        }
    }

    fun updatePlaying(playing: Boolean) {
        isPlaying = playing
        if (playing) {
            startPlayback()
        } else {
            playJob?.cancel()
        }
    }

    fun updateRefreshMinutes(value: Int) {
        refreshMinutes = value.coerceIn(1, 120)
        startRefreshTimer()
    }

    fun updateUseUtc(value: Boolean) {
        useUtc = value
    }

    fun updateAutoBase(value: Boolean) {
        autoBase = value
        applyAutoBase()
    }

    fun setBaseDateTime(date: LocalDate, time: LocalTime) {
        val zone = if (useUtc) ZoneOffset.UTC else ZoneId.systemDefault()
        val local = LocalDateTime.of(date, time)
        baseUtc = local.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toInstant()
    }

    fun clearSelection() {
        selectionRect = null
        selectionRatio = null
        zoomed = false
        exportUseSelection = false
    }

    fun updateSelection(rect: Rect?) {
        val normalized = rect?.normalized()
        if (normalized != null &&
            (normalized.width < MIN_SELECTION_PX || normalized.height < MIN_SELECTION_PX)
        ) {
            clearSelection()
            return
        }
        selectionRect = normalized
        val bitmap = currentBitmap
        if (normalized != null && bitmap != null) {
            selectionRatio = rectToRatio(normalized, bitmap)
            exportUseSelection = true
        } else {
            selectionRatio = null
            exportUseSelection = false
        }
    }

    fun updateZoomed(value: Boolean) {
        zoomed = value && selectionRect != null
    }

    fun applyRegionPreset(index: Int) {
        val preset = regionPresets.getOrNull(index) ?: return
        selectedRegionIndex = index
        applySelectionRatio(preset.ratio)
    }

    fun saveRegionPreset(name: String) {
        val ratio = selectionRatio
        if (ratio == null) {
            setStatus("Estado: seleccione una zona primero.")
            return
        }
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            setStatus("Estado: nombre de region invalido.")
            return
        }
        regionPresets.add(RegionPreset(trimmed, ratio))
        selectedRegionIndex = regionPresets.lastIndex
        setStatus("Estado: region guardada.")
        saveSettings()
    }

    fun deleteRegionPreset(index: Int) {
        if (index !in regionPresets.indices) {
            return
        }
        regionPresets.removeAt(index)
        if (selectedRegionIndex == index) {
            selectedRegionIndex = -1
        } else if (selectedRegionIndex > index) {
            selectedRegionIndex -= 1
        }
        setStatus("Estado: region eliminada.")
        saveSettings()
    }

    fun updateExportUseSelection(value: Boolean) {
        exportUseSelection = value && selectionRect != null
    }

    fun saveSnapshot(uri: Uri) {
        val bitmap = currentBitmap
        if (bitmap == null) {
            setStatus("Estado: no hay imagen para guardar.")
            return
        }
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val output = appContext.contentResolver.openOutputStream(uri)
                    output?.use { stream ->
                        val target = if (exportUseSelection && selectionRect != null) {
                            cropBitmap(bitmap, selectionRect!!)
                        } else {
                            bitmap
                        }
                        target.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    } ?: false
                } catch (ex: IOException) {
                    false
                }
            }
            setStatus(if (success) {
                "Estado: snapshot guardado."
            } else {
                "Estado: fallo al guardar."
            })
        }
    }

    fun refreshImages() {
        if (!refreshInProgress.compareAndSet(false, true)) {
            return
        }
        fetchJob?.cancel()
        applyAutoBase()
        progressText = "Progreso: 0/${offsets.size}"
        setStatus("Estado: actualizando imagenes...")
        val headersByOffset = offsets.associateWith { metadata.headersFor(it) }
        val fetchOffsets = buildPriorityOffsets()
        val total = fetchOffsets.size

        fetchJob = scope.launch {
            try {
                var done = 0
                var updated = 0
                var unchanged = 0
                coroutineScope {
                    val dispatcher = Dispatchers.IO.limitedParallelism(DEFAULT_MAX_CONCURRENT)
                    fetchOffsets.forEach { offset ->
                        launch(dispatcher) {
                            val result = fetchOffset(offset, headersByOffset[offset])
                            withContext(Dispatchers.Main) {
                                done += 1
                                when (result) {
                                    is FetchResult.Updated -> {
                                        updated += 1
                                        metadata.update(offset, result.headers)
                                        maybeAttemptOcr(offset, result.bytes, force = true)
                                        if (offset == offsets[currentIndex]) {
                                            updateCurrentBitmap(result.bytes)
                                        }
                                    }

                                    is FetchResult.NotModified -> {
                                        unchanged += 1
                                        if (!ocrByOffset.containsKey(offset)) {
                                            scope.launch {
                                                val cached = withContext(Dispatchers.IO) {
                                                    cache.load(offset)
                                                }
                                                if (cached != null) {
                                                    maybeAttemptOcrOnBitmap(
                                                        offset,
                                                        cached,
                                                        force = false,
                                                        updateStatus = offset == currentIndex
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    is FetchResult.Error -> {
                                        if (offset == offsets[currentIndex]) {
                                            setStatus("Estado: error de descarga (${result.message})")
                                        }
                                    }
                                }
                                progressText =
                                    "Progreso: $done/$total (nuevas $updated, sin cambios $unchanged)"
                            }
                        }
                    }
                }

                metadata.save()
                updatedAtText =
                    "Ultima actualizacion: " +
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                if (currentBitmap == null) {
                    setStatus("Estado: imagen no disponible")
                }
            } finally {
                refreshInProgress.set(false)
            }
        }
    }

    private fun applySettings(snapshot: SettingsSnapshot) {
        useUtc = snapshot.useUtc
        speedMs = snapshot.speedMs
        refreshMinutes = snapshot.refreshMin
        autoBase = snapshot.autoBase
        loopMode = snapshot.loopMode
        currentIndex = snapshot.currentIndex.coerceIn(0, offsets.lastIndex)
        baseUtc = Instant.ofEpochSecond(snapshot.baseEpochSeconds)
        zoomed = snapshot.zoomed && snapshot.selectionRatio != null
        selectionRatio = snapshot.selectionRatio
        pendingSelectionRatio = snapshot.selectionRatio
        regionPresets.clear()
        regionPresets.addAll(snapshot.regionPresets)
        selectedRegionIndex = -1
        exportUseSelection = snapshot.exportUseSelection && snapshot.selectionRatio != null
    }

    private fun saveSettings() {
        settings.save(
            SettingsSnapshot(
                useUtc = useUtc,
                speedMs = speedMs,
                refreshMin = refreshMinutes,
                autoBase = autoBase,
                loopMode = loopMode,
                currentIndex = currentIndex,
                baseEpochSeconds = baseUtc.epochSecond,
                zoomed = zoomed,
                selectionRatio = selectionRatio,
                regionPresets = regionPresets.toList(),
                exportUseSelection = exportUseSelection
            )
        )
    }

    private fun loadCurrentFrame() {
        val offset = offsets[currentIndex]
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { cache.load(offset) }
            currentBitmap = bitmap
            if (bitmap != null) {
                setStatus("Estado: imagen cargada")
                applyPendingSelection(bitmap)
                maybeAttemptOcrOnBitmap(offset, bitmap, force = false, updateStatus = true)
            } else {
                setStatus("Estado: imagen no disponible")
            }
        }
    }

    private fun updateCurrentBitmap(bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        currentBitmap = bitmap
        if (bitmap != null) {
            setStatus("Estado: imagen cargada")
            applyPendingSelection(bitmap)
            maybeAttemptOcrOnBitmap(offsets[currentIndex], bitmap, force = false, updateStatus = true)
        } else {
            setStatus("Estado: imagen no disponible")
        }
    }

    private fun applyPendingSelection(bitmap: Bitmap) {
        val ratio = pendingSelectionRatio ?: return
        val rect = ratioToRect(ratio, bitmap)
        pendingSelectionRatio = null
        if (rect != null) {
            selectionRect = rect
            selectionRatio = ratio
            exportUseSelection = true
        }
    }

    private fun applySelectionRatio(ratio: RectRatio) {
        val bitmap = currentBitmap
        if (bitmap != null) {
            val rect = ratioToRect(ratio, bitmap)
            updateSelection(rect)
        } else {
            pendingSelectionRatio = ratio
        }
    }

    fun detectBaseFromCurrent() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            setStatus("Estado: no hay imagen disponible para detectar la base.")
            return
        }
        val offset = offsets[currentIndex]
        ocrJobs[offset]?.cancel()
        ocrJobs[offset] = scope.launch {
            val ocrResult = recognizeOcrResult(bitmap, offset)
            if (ocrResult != null) {
                applyOcrResult(offset, ocrResult, updateStatus = true)
            } else {
                setStatus("Estado: no se pudo leer la marca temporal.")
            }
            ocrJobs.remove(offset)
        }
    }

    private fun startRefreshTimer() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                delay(refreshMinutes * 60_000L)
                refreshImages()
            }
        }
    }

    private fun startPlayback() {
        playJob?.cancel()
        playJob = scope.launch {
            while (isActive && isPlaying) {
                delay(speedMs.toLong())
                advanceFrame()
            }
        }
    }

    private fun advanceFrame() {
        if (loopMode == LoopMode.STOP && currentIndex == offsets.lastIndex) {
            updatePlaying(false)
            return
        }
        if (loopMode == LoopMode.PING_PONG) {
            if (playDirection > 0 && currentIndex == offsets.lastIndex) {
                playDirection = -1
            } else if (playDirection < 0 && currentIndex == 0) {
                playDirection = 1
            }
            setFrameIndex(currentIndex + playDirection)
        } else {
            setFrameIndex(floorMod(currentIndex + 1, offsets.size))
        }
    }

    private suspend fun fetchOffset(offset: Int, headers: HeaderInfo?): FetchResult {
        return withContext(Dispatchers.IO) {
            val url = URL(BASE_URL.format(offset))
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                useCaches = true
                if (headers?.etag?.isNotBlank() == true) {
                    setRequestProperty("If-None-Match", headers.etag)
                }
                if (headers?.lastModified?.isNotBlank() == true) {
                    setRequestProperty("If-Modified-Since", headers.lastModified)
                }
            }
            try {
                val status = connection.responseCode
                val etag = connection.getHeaderField("ETag")?.trim().orEmpty()
                val lastModified = connection.getHeaderField("Last-Modified")?.trim().orEmpty()
                val responseHeaders = HeaderInfo(
                    etag = etag.ifBlank { null },
                    lastModified = lastModified.ifBlank { null }
                )
                if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    return@withContext FetchResult.NotModified(offset)
                }
                if (status in 200..299) {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    if (bytes.isEmpty()) {
                        return@withContext FetchResult.Error(offset, "datos vacios")
                    }
                    cache.saveBytes(offset, bytes)
                    return@withContext FetchResult.Updated(offset, bytes, responseHeaders)
                }
                return@withContext FetchResult.Error(offset, "HTTP $status")
            } catch (ex: IOException) {
                return@withContext FetchResult.Error(offset, ex.message ?: "error")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun buildPriorityOffsets(): List<Int> {
        val total = offsets.size
        val order = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()

        fun addIndex(idx: Int) {
            val offset = offsets[idx]
            if (seen.add(offset)) {
                order.add(offset)
            }
        }

        addIndex(currentIndex)
        for (delta in 1..NEIGHBOR_PREFETCH) {
            addIndex(floorMod(currentIndex + delta, total))
            addIndex(floorMod(currentIndex - delta, total))
        }
        offsets.forEach { offset ->
            if (seen.add(offset)) {
                order.add(offset)
            }
        }
        return order
    }

    private fun applyAutoBase() {
        val offset = offsets[currentIndex]
        if (ocrByOffset[offset] == null) {
            baseUtc = lastCycleUtc(Instant.now())
        }
    }

    private fun maybeAttemptOcr(offset: Int, bytes: ByteArray, force: Boolean) {
        if (textRecognizer == null) {
            return
        }
        if (!force && ocrByOffset.containsKey(offset)) {
            return
        }
        if (ocrJobs.containsKey(offset)) {
            return
        }
        ocrJobs[offset] = scope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } ?: return@launch
                val ocrResult = recognizeOcrResult(bitmap, offset)
                if (ocrResult != null) {
                    applyOcrResult(offset, ocrResult, updateStatus = offset == currentIndex)
                }
            } finally {
                ocrJobs.remove(offset)
            }
        }
    }

    private fun maybeAttemptOcrOnBitmap(
        offset: Int,
        bitmap: Bitmap,
        force: Boolean,
        updateStatus: Boolean
    ) {
        if (textRecognizer == null) {
            return
        }
        if (!force && ocrByOffset.containsKey(offset)) {
            return
        }
        if (ocrJobs.containsKey(offset)) {
            return
        }
        ocrJobs[offset] = scope.launch {
            try {
                val ocrResult = recognizeOcrResult(bitmap, offset)
                if (ocrResult != null) {
                    applyOcrResult(offset, ocrResult, updateStatus = updateStatus)
                }
            } finally {
                ocrJobs.remove(offset)
            }
        }
    }

    private suspend fun recognizeOcrResult(bitmap: Bitmap, offset: Int): OcrResult? {
        val recognizer = textRecognizer ?: return null
        val crop = cropForOcr(bitmap) ?: return null
        val inputImage = InputImage.fromBitmap(crop, 0)
        val text = recognizeText(recognizer, inputImage) ?: return null
        val offsetSeconds = offset * 3600L
        val times = extractOcrTimes(text)
        val baseFromInicio = times.baseInstant
        val validFromValido = times.validInstant

        if (baseFromInicio != null) {
            val validFromBase = baseFromInicio.plusSeconds(offsetSeconds)
            if (validFromValido != null) {
                val diff = kotlin.math.abs(
                    validFromValido.epochSecond - validFromBase.epochSecond
                )
                if (diff <= 3600L) {
                    return OcrResult(baseFromInicio, validFromValido)
                }
            }
            return OcrResult(baseFromInicio, validFromBase)
        }

        val validInstant = validFromValido
            ?: parseDateTimeFromText(text.text)
            ?: return null
        val adjustedValid = adjustOcrInstantForOffset(validInstant, offset)
        val baseInstant = adjustedValid.minusSeconds(offsetSeconds)
        return OcrResult(baseInstant, adjustedValid)
    }

    private suspend fun recognizeText(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        image: InputImage
    ): Text? {
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
                .addOnFailureListener {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
        }
    }

    private fun extractOcrTimes(result: Text): OcrTimes {
        val baseCandidates = mutableListOf<Instant>()
        val validCandidates = mutableListOf<Instant>()
        result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val raw = line.text
                val normalized = normalizeOcrLine(raw)
                val instant = parseDateTimeFromLine(normalized) ?: return@forEach
                when {
                    normalized.contains("valido") -> validCandidates.add(instant)
                    normalized.contains("inicio") -> baseCandidates.add(instant)
                }
            }
        }
        return OcrTimes(
            baseInstant = chooseBestBase(baseCandidates),
            validInstant = chooseBestValid(validCandidates)
        )
    }

    private fun parseDateTimeFromLine(line: String): Instant? {
        if (line.isBlank()) return null
        val numericDmy =
            Regex("(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4}).*?(\\d{1,2})(?::(\\d{2}))?")
        val numericYmd =
            Regex("(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2}).*?(\\d{1,2})(?::(\\d{2}))?")
        val monthRegex = Regex(
            "(\\d{1,2})\\s+(ene|feb|mar|abr|may|jun|jul|ago|sep|sept|oct|nov|dic)[\\.,]?\\s+(\\d{4}).*?(\\d{1,2})(?::(\\d{2}))?"
        )
        val monthMap = mapOf(
            "ene" to 1,
            "feb" to 2,
            "mar" to 3,
            "abr" to 4,
            "may" to 5,
            "jun" to 6,
            "jul" to 7,
            "ago" to 8,
            "sep" to 9,
            "sept" to 9,
            "oct" to 10,
            "nov" to 11,
            "dic" to 12
        )

        fun buildInstant(
            year: String,
            month: Int,
            day: String,
            hour: String,
            minute: String?
        ): Instant? {
            val parsed = runCatching {
                LocalDateTime.of(
                    year.toInt(),
                    month,
                    day.toInt(),
                    hour.toInt(),
                    minute?.toInt() ?: 0
                )
            }.getOrNull()
            return parsed?.toInstant(ZoneOffset.UTC)
        }

        numericDmy.find(line)?.let { match ->
            val (day, month, year, hour, minute) = match.destructured
            return buildInstant(year, month.toInt(), day, hour, minute.ifBlank { null })
        }
        numericYmd.find(line)?.let { match ->
            val (year, month, day, hour, minute) = match.destructured
            return buildInstant(year, month.toInt(), day, hour, minute.ifBlank { null })
        }
        monthRegex.find(line)?.let { match ->
            val (day, monthKey, year, hour, minute) = match.destructured
            val month = monthMap[monthKey] ?: return null
            return buildInstant(year, month, day, hour, minute.ifBlank { null })
        }
        return null
    }

    private fun chooseBestBase(candidates: List<Instant>): Instant? {
        if (candidates.isEmpty()) return null
        return candidates.minBy { instant ->
            val hour = instant.atZone(ZoneOffset.UTC).hour
            val distance = minOf(
                kotlin.math.abs(hour - 0),
                kotlin.math.abs(hour - 12),
                kotlin.math.abs(hour - 24)
            )
            distance * 10 + kotlin.math.abs(instant.epochSecond - Instant.now().epochSecond)
        }
    }

    private fun chooseBestValid(candidates: List<Instant>): Instant? {
        if (candidates.isEmpty()) return null
        val now = Instant.now().epochSecond
        return candidates.minBy { kotlin.math.abs(it.epochSecond - now) }
    }

    private fun normalizeOcrLine(raw: String): String {
        val lowered = raw.lowercase()
        val normalized = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        val stripped = normalized.replace("\\p{Mn}+".toRegex(), "")
        return stripped.replace("\\s+".toRegex(), " ").trim()
    }

    private fun parseDateTimeFromText(text: String): Instant? {
        if (text.isBlank()) return null
        val normalized = text.replace("\\s+".toRegex(), " ").trim()
        val utcCandidates = mutableListOf<Instant>()
        val candidates = mutableListOf<Instant>()
        val dmyUtc = Regex(
            "(\\d{2})[/.\\-](\\d{2})[/.\\-](\\d{4}).*?(\\d{2})(?::(\\d{2}))?\\s*UTC",
            RegexOption.IGNORE_CASE
        )
        val ymdUtc = Regex(
            "(\\d{4})[/.\\-](\\d{2})[/.\\-](\\d{2}).*?(\\d{2})(?::(\\d{2}))?\\s*UTC",
            RegexOption.IGNORE_CASE
        )
        val dmy =
            Regex("(\\d{2})[/.\\-](\\d{2})[/.\\-](\\d{4}).*?(\\d{2})(?::(\\d{2}))?")
        val ymd =
            Regex("(\\d{4})[/.\\-](\\d{2})[/.\\-](\\d{2}).*?(\\d{2})(?::(\\d{2}))?")

        fun addCandidate(
            target: MutableList<Instant>,
            year: String,
            month: String,
            day: String,
            hour: String,
            minute: String?
        ) {
            val parsed = runCatching {
                LocalDateTime.of(
                    year.toInt(),
                    month.toInt(),
                    day.toInt(),
                    hour.toInt(),
                    minute?.toInt() ?: 0
                )
            }.getOrNull()
            parsed?.toInstant(ZoneOffset.UTC)?.let { target.add(it) }
        }

        dmyUtc.findAll(normalized).forEach { match ->
            val (day, month, year, hour, minute) = match.destructured
            addCandidate(utcCandidates, year, month, day, hour, minute.ifBlank { null })
        }
        ymdUtc.findAll(normalized).forEach { match ->
            val (year, month, day, hour, minute) = match.destructured
            addCandidate(utcCandidates, year, month, day, hour, minute.ifBlank { null })
        }
        dmy.findAll(normalized).forEach { match ->
            val (day, month, year, hour, minute) = match.destructured
            addCandidate(candidates, year, month, day, hour, minute.ifBlank { null })
        }
        ymd.findAll(normalized).forEach { match ->
            val (year, month, day, hour, minute) = match.destructured
            addCandidate(candidates, year, month, day, hour, minute.ifBlank { null })
        }

        val resolved = if (utcCandidates.isNotEmpty()) utcCandidates else candidates
        if (resolved.isEmpty()) {
            return null
        }
        val now = Instant.now()
        val best = resolved.minBy { kotlin.math.abs(it.epochSecond - now.epochSecond) }
        val maxSkewSeconds = 90L * 24 * 3600
        return if (kotlin.math.abs(best.epochSecond - now.epochSecond) > maxSkewSeconds) {
            null
        } else {
            best
        }
    }

    private fun adjustOcrInstantForOffset(instant: Instant, offset: Int): Instant {
        val candidates = listOf(-12, -6, 0, 6, 12)
        val bestDelta = candidates.minBy { delta ->
            val baseHour = instant
                .plusSeconds(delta * 3600L)
                .minusSeconds(offset * 3600L)
                .atZone(ZoneOffset.UTC)
                .hour
            val distance = minOf(
                kotlin.math.abs(baseHour - 0),
                kotlin.math.abs(baseHour - 12),
                kotlin.math.abs(baseHour - 24)
            )
            distance * 10 + kotlin.math.abs(delta)
        }
        val adjusted = instant.plusSeconds(bestDelta * 3600L)
        val baseHour = adjusted
            .minusSeconds(offset * 3600L)
            .atZone(ZoneOffset.UTC)
            .hour
        val distance = minOf(
            kotlin.math.abs(baseHour - 0),
            kotlin.math.abs(baseHour - 12),
            kotlin.math.abs(baseHour - 24)
        )
        return if (distance <= 1) adjusted else instant
    }

    private fun formatOcrInstant(instant: Instant): String {
        val display = instant.atZone(ZoneOffset.UTC)
        val formatted = display.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        return "$formatted UTC"
    }

    private fun applyOcrResult(offset: Int, result: OcrResult, updateStatus: Boolean) {
        ocrByOffset[offset] = result.validInstant
        ocrCache.put(offset, result.validInstant)
        ocrCache.save()
        baseUtc = result.baseInstant
        if (updateStatus) {
            setStatus("Estado: base ajustada por OCR")
        }
    }

    private fun setStatus(message: String) {
        statusMessage = message
        statusText = composeStatus(message)
    }

    private fun composeStatus(message: String): String {
        val ocrInstant = ocrByOffset[offsets[currentIndex]] ?: return message
        return "$message | OCR: ${formatOcrInstant(ocrInstant)}"
    }
}

private fun buildOffsets(): List<Int> = (6..240 step 6).toList()

private fun lastCycleUtc(now: Instant): Instant {
    val zdt = now.atZone(ZoneOffset.UTC)
    val cycleHour = if (zdt.hour >= 12) 12 else 0
    return zdt.toLocalDate().atTime(cycleHour, 0).toInstant(ZoneOffset.UTC)
}

private fun floorMod(value: Int, size: Int): Int {
    if (size == 0) return 0
    val mod = value % size
    return if (mod < 0) mod + size else mod
}

private fun Rect.normalized(): Rect {
    val leftValue = min(left, right)
    val rightValue = max(left, right)
    val topValue = min(top, bottom)
    val bottomValue = max(top, bottom)
    return Rect(leftValue, topValue, rightValue, bottomValue)
}

private fun rectToRatio(rect: Rect, bitmap: Bitmap): RectRatio {
    val width = bitmap.width.toFloat().coerceAtLeast(1f)
    val height = bitmap.height.toFloat().coerceAtLeast(1f)
    return RectRatio(
        left = rect.left / width,
        top = rect.top / height,
        width = rect.width / width,
        height = rect.height / height
    )
}

private fun ratioToRect(ratio: RectRatio, bitmap: Bitmap): Rect? {
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()
    if (width <= 0f || height <= 0f) return null
    val left = (ratio.left * width).coerceIn(0f, width)
    val top = (ratio.top * height).coerceIn(0f, height)
    val rectWidth = (ratio.width * width).coerceIn(1f, width)
    val rectHeight = (ratio.height * height).coerceIn(1f, height)
    val right = (left + rectWidth).coerceIn(left + 1f, width)
    val bottom = (top + rectHeight).coerceIn(top + 1f, height)
    if (right <= left || bottom <= top) return null
    return Rect(left, top, right, bottom)
}

internal fun cropBitmap(source: Bitmap, rect: Rect): Bitmap {
    val left = rect.left.toInt().coerceIn(0, source.width - 1)
    val top = rect.top.toInt().coerceIn(0, source.height - 1)
    val width = rect.width.toInt().coerceAtLeast(1).coerceAtMost(source.width - left)
    val height = rect.height.toInt().coerceAtLeast(1).coerceAtMost(source.height - top)
    return Bitmap.createBitmap(source, left, top, width, height)
}

private fun cropForOcr(bitmap: Bitmap): Bitmap? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val left = (width * 0.60f).toInt().coerceIn(0, width - 1)
    val top = 0
    val cropWidth = (width * 0.38f).toInt().coerceAtLeast(1)
    val cropHeight = (height * 0.18f).toInt().coerceAtLeast(1)
    val safeWidth = cropWidth.coerceAtMost(width - left)
    val safeHeight = cropHeight.coerceAtMost(height - top)
    if (safeWidth < 1 || safeHeight < 1) return null
    return Bitmap.createBitmap(bitmap, left, top, safeWidth, safeHeight)
}

private class ForecastCache(private val root: File) {
    init {
        if (!root.exists()) {
            root.mkdirs()
        }
    }

    fun load(offset: Int): Bitmap? {
        val file = pathFor(offset)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun saveBytes(offset: Int, bytes: ByteArray) {
        val file = pathFor(offset)
        file.outputStream().use { stream -> stream.write(bytes) }
    }

    private fun pathFor(offset: Int): File = File(root, "ECMWF_%03d.png".format(offset))
}

private class MetadataCache(private val file: File) {
    private val data = mutableMapOf<Int, HeaderInfo>()

    init {
        load()
    }

    fun headersFor(offset: Int): HeaderInfo? = data[offset]

    fun update(offset: Int, headers: HeaderInfo) {
        data[offset] = headers
    }

    fun save() {
        val root = JSONObject()
        data.forEach { (offset, headers) ->
            val entry = JSONObject()
            headers.etag?.let { entry.put("etag", it) }
            headers.lastModified?.let { entry.put("last_modified", it) }
            root.put(offset.toString(), entry)
        }
        file.writeText(root.toString(2))
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val raw = file.readText()
            val json = JSONObject(raw)
            json.keys().forEach { key ->
                val entry = json.optJSONObject(key) ?: return@forEach
                val etag = entry.optString("etag", null)
                val lastModified = entry.optString("last_modified", null)
                key.toIntOrNull()?.let { offset ->
                    data[offset] = HeaderInfo(etag, lastModified)
                }
            }
        } catch (_: Exception) {
            data.clear()
        }
    }
}

private class OcrCache(private val file: File) {
    private val data = mutableMapOf<Int, Long>()

    init {
        load()
    }

    fun loadAll(): Map<Int, Instant> {
        return data.mapValues { Instant.ofEpochSecond(it.value) }
    }

    fun put(offset: Int, instant: Instant) {
        data[offset] = instant.epochSecond
    }

    fun save() {
        val root = JSONObject()
        data.forEach { (offset, epoch) ->
            root.put(offset.toString(), epoch)
        }
        file.writeText(root.toString(2))
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val raw = file.readText()
            val json = JSONObject(raw)
            json.keys().forEach { key ->
                val epoch = json.optLong(key, Long.MIN_VALUE)
                if (epoch != Long.MIN_VALUE) {
                    key.toIntOrNull()?.let { offset ->
                        data[offset] = epoch
                    }
                }
            }
        } catch (_: Exception) {
            data.clear()
        }
    }
}

private class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)

    fun load(defaultBase: Instant): SettingsSnapshot {
        val baseEpoch = prefs.getLong("base_epoch", defaultBase.epochSecond)
        val selectionRatio = prefs.getString("selection_ratio", null)?.let { ratioFromJson(it) }
        val regionPresets = presetsFromJson(prefs.getString("region_presets", null))
        return SettingsSnapshot(
            useUtc = prefs.getBoolean("use_utc", false),
            speedMs = prefs.getInt("speed_ms", 500),
            refreshMin = prefs.getInt("refresh_min", 15),
            autoBase = prefs.getBoolean("auto_base", false),
            loopMode = LoopMode.fromLabel(prefs.getString("loop_mode", LoopMode.LOOP.label)),
            currentIndex = prefs.getInt("current_index", 0),
            baseEpochSeconds = baseEpoch,
            zoomed = prefs.getBoolean("zoomed", false),
            selectionRatio = selectionRatio,
            regionPresets = regionPresets,
            exportUseSelection = prefs.getBoolean("export_use_selection", false)
        )
    }

    fun save(snapshot: SettingsSnapshot) {
        prefs.edit()
            .putBoolean("use_utc", snapshot.useUtc)
            .putInt("speed_ms", snapshot.speedMs)
            .putInt("refresh_min", snapshot.refreshMin)
            .putBoolean("auto_base", snapshot.autoBase)
            .putString("loop_mode", snapshot.loopMode.label)
            .putInt("current_index", snapshot.currentIndex)
            .putLong("base_epoch", snapshot.baseEpochSeconds)
            .putBoolean("zoomed", snapshot.zoomed)
            .putString("selection_ratio", ratioToJson(snapshot.selectionRatio))
            .putString("region_presets", presetsToJson(snapshot.regionPresets))
            .putBoolean("export_use_selection", snapshot.exportUseSelection)
            .apply()
    }

    private fun ratioFromJson(raw: String): RectRatio? {
        return try {
            val arr = JSONArray(raw)
            if (arr.length() != 4) return null
            RectRatio(
                left = arr.getDouble(0).toFloat(),
                top = arr.getDouble(1).toFloat(),
                width = arr.getDouble(2).toFloat(),
                height = arr.getDouble(3).toFloat()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun ratioToJson(ratio: RectRatio?): String {
        if (ratio == null) return ""
        val arr = JSONArray()
        arr.put(ratio.left)
        arr.put(ratio.top)
        arr.put(ratio.width)
        arr.put(ratio.height)
        return arr.toString()
    }

    private fun presetsFromJson(raw: String?): List<RegionPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val presets = mutableListOf<RegionPreset>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "Region").trim()
                val ratioArr = obj.optJSONArray("ratio") ?: continue
                if (ratioArr.length() != 4) continue
                val ratio = RectRatio(
                    left = ratioArr.getDouble(0).toFloat(),
                    top = ratioArr.getDouble(1).toFloat(),
                    width = ratioArr.getDouble(2).toFloat(),
                    height = ratioArr.getDouble(3).toFloat()
                )
                presets.add(RegionPreset(name, ratio))
            }
            presets
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun presetsToJson(presets: List<RegionPreset>): String {
        if (presets.isEmpty()) return ""
        val arr = JSONArray()
        presets.forEach { preset ->
            val obj = JSONObject()
            val ratio = JSONArray()
            ratio.put(preset.ratio.left)
            ratio.put(preset.ratio.top)
            ratio.put(preset.ratio.width)
            ratio.put(preset.ratio.height)
            obj.put("name", preset.name)
            obj.put("ratio", ratio)
            arr.put(obj)
        }
        return arr.toString()
    }
}
