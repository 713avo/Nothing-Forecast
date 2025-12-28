import sys
import io
import json
import re
from pathlib import Path
from collections import deque

from PyQt5.QtCore import (
    Qt,
    QTimer,
    QDate,
    QTime,
    QDateTime,
    QRect,
    QRectF,
    QUrl,
    pyqtSignal,
    QObject,
    QBuffer,
    QIODevice,
    QSettings,
)
from PyQt5.QtGui import QPixmap, QPainter, QPen, QColor, QImage
from PyQt5.QtWidgets import (
    QApplication,
    QMainWindow,
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSlider,
    QSpinBox,
    QDateTimeEdit,
    QGraphicsView,
    QGraphicsScene,
    QGraphicsPixmapItem,
    QGraphicsRectItem,
    QSizePolicy,
    QComboBox,
    QCheckBox,
    QInputDialog,
    QFileDialog,
    QMessageBox,
)
from PyQt5.QtNetwork import QNetworkAccessManager, QNetworkRequest, QNetworkReply


APP_ORG = "tiempo"
APP_NAME = "ECMWF Timelapse"
BASE_URL = (
    "https://services.meteored.com/img/models/ecmwf/"
    "ECMWF_{hour:03d}_ES_SFC_es-ES_es.png"
)
SPEED_PRESETS = [250, 500, 1000, 2000]
LOOP_MODES = ["Loop", "Ping-pong", "Stop"]
DEFAULT_MAX_CONCURRENT = 4
NEIGHBOR_PREFETCH = 2


def build_offsets():
    return list(range(6, 241, 6))


def bool_value(value, default=False):
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        return value.strip().lower() in ("1", "true", "yes", "y", "on")
    return default


def last_cycle_utc(now=None):
    if now is None:
        now = QDateTime.currentDateTimeUtc()
    hour = now.time().hour()
    cycle_hour = 12 if hour >= 12 else 0
    return QDateTime(now.date(), QTime(cycle_hour, 0), Qt.UTC)


def parse_datetime_from_text(text):
    if not text:
        return None
    patterns = [
        (r"(\d{2})[/-](\d{2})[/-](\d{4}).*?(\d{2}):(\d{2})", "DMY"),
        (r"(\d{4})[/-](\d{2})[/-](\d{2}).*?(\d{2}):(\d{2})", "YMD"),
    ]
    for pattern, order in patterns:
        match = re.search(pattern, text)
        if not match:
            continue
        parts = [int(value) for value in match.groups()]
        if order == "DMY":
            day, month, year, hour, minute = parts
        else:
            year, month, day, hour, minute = parts
        date = QDate(year, month, day)
        time_value = QTime(hour, minute)
        if date.isValid() and time_value.isValid():
            return QDateTime(date, time_value, Qt.UTC)
    return None


def try_ocr_datetime(pixmap):
    try:
        from PIL import Image, ImageOps
        import pytesseract
    except Exception:
        return None

    if pixmap is None or pixmap.isNull():
        return None
    qimage = pixmap.toImage()
    width = qimage.width()
    height = qimage.height()
    if width <= 0 or height <= 0:
        return None
    crop_rect = QRect(int(width * 0.60), 0, int(width * 0.38), int(height * 0.18))
    if crop_rect.width() <= 0 or crop_rect.height() <= 0:
        return None
    crop = qimage.copy(crop_rect)
    buffer = QBuffer()
    buffer.open(QIODevice.WriteOnly)
    crop.save(buffer, "PNG")
    pil_image = Image.open(io.BytesIO(buffer.data()))
    pil_image = ImageOps.autocontrast(pil_image.convert("L"))
    text = pytesseract.image_to_string(pil_image, config="--psm 6")
    return parse_datetime_from_text(text)


def pixmap_to_pil(pixmap):
    try:
        from PIL import Image
    except Exception:
        return None

    if pixmap is None or pixmap.isNull():
        return None
    qimage = pixmap.toImage()
    if qimage.format() not in (QImage.Format_RGB888, QImage.Format_RGBA8888):
        qimage = qimage.convertToFormat(QImage.Format_RGBA8888)
    mode = "RGB" if qimage.format() == QImage.Format_RGB888 else "RGBA"
    width = qimage.width()
    height = qimage.height()
    ptr = qimage.bits()
    ptr.setsize(qimage.byteCount())
    return Image.frombuffer(mode, (width, height), bytes(ptr), "raw", mode, 0, 1)


class ForecastCache:
    def __init__(self, cache_dir):
        self.cache_dir = Path(cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def path_for(self, offset):
        return self.cache_dir / f"ECMWF_{offset:03d}.png"

    def load(self, offset):
        path = self.path_for(offset)
        if not path.exists():
            return None
        pixmap = QPixmap(str(path))
        if pixmap.isNull():
            return None
        return pixmap

    def save(self, offset, pixmap):
        path = self.path_for(offset)
        pixmap.save(str(path), "PNG")


class MetadataCache:
    def __init__(self, path):
        self.path = Path(path)
        self.data = {}
        self._dirty = False
        self._load()

    def _load(self):
        if not self.path.exists():
            return
        try:
            self.data = json.loads(self.path.read_text(encoding="utf-8"))
        except Exception:
            self.data = {}

    def headers_for(self, offset):
        entry = self.data.get(str(offset), {})
        headers = {}
        if "etag" in entry:
            headers["etag"] = entry["etag"]
        if "last_modified" in entry:
            headers["last_modified"] = entry["last_modified"]
        return headers

    def update(self, offset, etag=None, last_modified=None):
        entry = self.data.setdefault(str(offset), {})
        if etag:
            entry["etag"] = etag
        if last_modified:
            entry["last_modified"] = last_modified
        self._dirty = True

    def save(self):
        if not self._dirty:
            return
        self.path.write_text(json.dumps(self.data, indent=2), encoding="utf-8")
        self._dirty = False


class ImageFetcher(QObject):
    imageLoaded = pyqtSignal(int, QPixmap, dict)
    notModified = pyqtSignal(int)
    imageError = pyqtSignal(int, str)
    progressChanged = pyqtSignal(int, int)
    batchFinished = pyqtSignal()

    def __init__(self, base_url, parent=None):
        super().__init__(parent)
        self.base_url = base_url
        self.manager = QNetworkAccessManager(self)
        self.manager.finished.connect(self._on_finished)
        self._queue = deque()
        self._headers_by_offset = {}
        self._in_flight = 0
        self._done = 0
        self._total = 0
        self._max_concurrent = DEFAULT_MAX_CONCURRENT
        self._force_network = False

    def set_max_concurrent(self, value):
        self._max_concurrent = max(1, int(value))

    def fetch_offsets(self, offsets, headers_by_offset=None, force_network=False):
        self._queue = deque(offsets)
        self._headers_by_offset = headers_by_offset or {}
        self._force_network = force_network
        self._in_flight = 0
        self._done = 0
        self._total = len(offsets)
        if self._total == 0:
            self.batchFinished.emit()
            return
        self._start_more()

    def _start_more(self):
        while self._in_flight < self._max_concurrent and self._queue:
            offset = self._queue.popleft()
            url = self.base_url.format(hour=offset)
            request = QNetworkRequest(QUrl(url))
            if self._force_network:
                request.setAttribute(
                    QNetworkRequest.CacheLoadControlAttribute,
                    QNetworkRequest.AlwaysNetwork,
                )
            else:
                request.setAttribute(
                    QNetworkRequest.CacheLoadControlAttribute,
                    QNetworkRequest.PreferNetwork,
                )
            headers = self._headers_by_offset.get(offset, {})
            etag = headers.get("etag")
            last_modified = headers.get("last_modified")
            if etag:
                request.setRawHeader(b"If-None-Match", etag.encode())
            if last_modified:
                request.setRawHeader(b"If-Modified-Since", last_modified.encode())
            reply = self.manager.get(request)
            reply.setProperty("offset", offset)
            self._in_flight += 1

    def _on_finished(self, reply):
        offset = int(reply.property("offset"))
        status = reply.attribute(QNetworkRequest.HttpStatusCodeAttribute)
        etag = bytes(reply.rawHeader(b"ETag")).decode("utf-8", "ignore").strip()
        last_modified = bytes(reply.rawHeader(b"Last-Modified")).decode(
            "utf-8", "ignore"
        ).strip()
        meta = {}
        if etag:
            meta["etag"] = etag
        if last_modified:
            meta["last_modified"] = last_modified

        if status == 304:
            self.notModified.emit(offset)
        elif reply.error() == QNetworkReply.NoError:
            data = bytes(reply.readAll())
            pixmap = QPixmap()
            if pixmap.loadFromData(data):
                self.imageLoaded.emit(offset, pixmap, meta)
            else:
                self.imageError.emit(offset, "invalid image data")
        else:
            self.imageError.emit(offset, reply.errorString())

        reply.deleteLater()
        self._in_flight -= 1
        self._done += 1
        self.progressChanged.emit(self._done, self._total)
        if self._done == self._total:
            self.batchFinished.emit()
        else:
            self._start_more()


class ImageView(QGraphicsView):
    selectionChanged = pyqtSignal(QRect)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._scene = QGraphicsScene(self)
        self.setScene(self._scene)
        self._pixmap_item = QGraphicsPixmapItem()
        self._scene.addItem(self._pixmap_item)
        self._selection_item = QGraphicsRectItem()
        self._selection_item.setPen(QPen(QColor(255, 0, 0), 2, Qt.DashLine))
        self._selection_item.setBrush(QColor(255, 0, 0, 40))
        self._selection_item.setZValue(1)
        self._scene.addItem(self._selection_item)
        self._selection_item.hide()
        self._full_pixmap = None
        self._selection_rect = QRect()
        self._zoomed = False
        self._dragging = False
        self._origin = None
        self.setRenderHint(QPainter.SmoothPixmapTransform)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.setVerticalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.setAlignment(Qt.AlignCenter)
        self.setBackgroundBrush(QColor(20, 20, 20))
        self.setFrameShape(QGraphicsView.NoFrame)

    def set_pixmap(self, pixmap):
        if pixmap is None or pixmap.isNull():
            self._full_pixmap = None
            self._pixmap_item.setPixmap(QPixmap())
            self._selection_item.hide()
            self._scene.setSceneRect(QRectF(0, 0, 1, 1))
            return
        self._full_pixmap = pixmap
        self._update_pixmap()

    def set_zoomed(self, zoomed):
        self._zoomed = zoomed
        self._update_pixmap()

    def is_zoomed(self):
        return self._zoomed

    def clear_selection(self):
        self._selection_rect = QRect()
        self._selection_item.hide()
        self._update_pixmap()

    def set_selection_rect(self, rect, emit_signal=True):
        if rect is None or rect.isNull() or rect.isEmpty():
            self._selection_rect = QRect()
            self._selection_item.hide()
        else:
            self._selection_rect = QRect(rect)
        self._update_pixmap()
        if emit_signal:
            self.selectionChanged.emit(self.selection_rect())

    def has_selection(self):
        return not self._selection_rect.isNull() and not self._selection_rect.isEmpty()

    def selection_rect(self):
        return QRect(self._selection_rect)

    def full_pixmap(self):
        return self._full_pixmap

    def _update_pixmap(self):
        if not self._full_pixmap:
            return
        if self._zoomed and self.has_selection():
            cropped = self._full_pixmap.copy(self._selection_rect)
            self._pixmap_item.setPixmap(cropped)
            self._selection_item.hide()
        else:
            self._pixmap_item.setPixmap(self._full_pixmap)
            if self.has_selection():
                self._selection_item.setRect(QRectF(self._selection_rect))
                self._selection_item.show()
            else:
                self._selection_item.hide()
        self._fit()

    def _fit(self):
        if self._pixmap_item.pixmap().isNull():
            return
        self.setSceneRect(self._pixmap_item.boundingRect())
        self.fitInView(self._pixmap_item, Qt.KeepAspectRatio)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._fit()

    def mousePressEvent(self, event):
        if self._zoomed or not self._full_pixmap:
            return super().mousePressEvent(event)
        if event.button() == Qt.LeftButton:
            self._dragging = True
            self._origin = self.mapToScene(event.pos())
            self._selection_item.setRect(QRectF(self._origin, self._origin))
            self._selection_item.show()
            event.accept()
            return
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event):
        if self._dragging and self._origin is not None:
            current = self.mapToScene(event.pos())
            rect = QRectF(self._origin, current).normalized()
            bounds = QRectF(
                0,
                0,
                self._full_pixmap.width(),
                self._full_pixmap.height(),
            )
            rect = rect.intersected(bounds)
            self._selection_item.setRect(rect)
            event.accept()
            return
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event):
        if self._dragging and event.button() == Qt.LeftButton:
            self._dragging = False
            rect = self._selection_item.rect().toRect()
            if rect.width() < 10 or rect.height() < 10:
                self._selection_rect = QRect()
                self._selection_item.hide()
            else:
                self._selection_rect = rect
                self._selection_item.show()
            self.selectionChanged.emit(self.selection_rect())
            event.accept()
            return
        super().mouseReleaseEvent(event)


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("ECMWF Timelapse")
        self.settings = QSettings(APP_ORG, APP_NAME)
        self.offsets = build_offsets()
        self.frames = {offset: None for offset in self.offsets}
        self.cache_dir = Path(__file__).with_name("cache")
        self.cache = ForecastCache(self.cache_dir)
        self.metadata = MetadataCache(self.cache_dir / "metadata.json")
        for offset in self.offsets:
            cached = self.cache.load(offset)
            if cached:
                self.frames[offset] = cached

        self.fetcher = ImageFetcher(BASE_URL, self)
        self.fetcher.set_max_concurrent(DEFAULT_MAX_CONCURRENT)
        self.fetcher.imageLoaded.connect(self._on_image_loaded)
        self.fetcher.notModified.connect(self._on_image_not_modified)
        self.fetcher.imageError.connect(self._on_image_error)
        self.fetcher.progressChanged.connect(self._on_fetch_progress)
        self.fetcher.batchFinished.connect(self._on_batch_finished)
        self._refresh_in_progress = False

        self.current_index = 0
        self.loop_mode = LOOP_MODES[0]
        self.play_direction = 1
        self.use_utc = False
        self._selection_ratio = None
        self._pending_selection_ratio = None
        self._ocr_attempted = False
        self._ocr_available = self._detect_ocr_available()
        self._export_caps = self._detect_export_backends()
        self._progress_total = 0
        self._progress_updated = 0
        self._progress_unchanged = 0
        self.region_presets = []

        self._build_ui()
        self._load_settings()
        self._update_current_frame()
        self._setup_timers()
        self._start_initial_refresh()

    def _build_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        layout = QVBoxLayout(central)

        self.time_label = QLabel("Prediccion: -")
        self.update_label = QLabel("Ultima actualizacion: -")
        layout.addWidget(self.time_label)
        layout.addWidget(self.update_label)

        playback_row = QHBoxLayout()
        self.prev_button = QPushButton("Prev")
        self.play_button = QPushButton("Play")
        self.play_button.setCheckable(True)
        self.next_button = QPushButton("Next")
        self.jump_back_button = QPushButton("-24h")
        self.jump_forward_button = QPushButton("+24h")
        playback_row.addWidget(self.prev_button)
        playback_row.addWidget(self.play_button)
        playback_row.addWidget(self.next_button)
        playback_row.addWidget(self.jump_back_button)
        playback_row.addWidget(self.jump_forward_button)
        layout.addLayout(playback_row)

        playback_settings = QHBoxLayout()
        self.loop_combo = QComboBox()
        self.loop_combo.addItems(LOOP_MODES)
        self.speed_preset_combo = QComboBox()
        self.speed_preset_combo.addItems(["0.25s", "0.5s", "1s", "2s", "Custom"])
        self.speed_spin = QSpinBox()
        self.speed_spin.setRange(100, 5000)
        self.speed_spin.setValue(500)
        self.speed_spin.setSuffix(" ms")
        playback_settings.addWidget(QLabel("Loop"))
        playback_settings.addWidget(self.loop_combo)
        playback_settings.addWidget(QLabel("Preset"))
        playback_settings.addWidget(self.speed_preset_combo)
        playback_settings.addWidget(QLabel("Frame"))
        playback_settings.addWidget(self.speed_spin)
        layout.addLayout(playback_settings)

        self.slider = QSlider(Qt.Horizontal)
        self.slider.setRange(0, len(self.offsets) - 1)
        self.slider.setTickPosition(QSlider.NoTicks)
        layout.addWidget(self.slider)

        settings = QHBoxLayout()
        self.base_edit = QDateTimeEdit(
            QDateTime(QDate.currentDate(), QTime(0, 0))
        )
        self.base_edit.setDisplayFormat("yyyy-MM-dd HH:mm")
        self.tz_combo = QComboBox()
        self.tz_combo.addItems(["Local", "UTC"])
        self.auto_base_check = QCheckBox("Auto base")
        self.detect_base_button = QPushButton("Detect base")
        self.detect_base_button.setEnabled(self._ocr_available)
        self.refresh_spin = QSpinBox()
        self.refresh_spin.setRange(1, 120)
        self.refresh_spin.setValue(15)
        self.refresh_spin.setSuffix(" min")
        self.refresh_button = QPushButton("Refresh now")
        settings.addWidget(QLabel("Base"))
        settings.addWidget(self.base_edit)
        settings.addWidget(self.tz_combo)
        settings.addWidget(self.auto_base_check)
        settings.addWidget(self.detect_base_button)
        settings.addWidget(QLabel("Refresh"))
        settings.addWidget(self.refresh_spin)
        settings.addWidget(self.refresh_button)
        layout.addLayout(settings)

        zoom_controls = QHBoxLayout()
        self.zoom_button = QPushButton("Zoom to selection")
        self.zoom_button.setCheckable(True)
        self.zoom_button.setEnabled(False)
        self.clear_selection_button = QPushButton("Clear selection")
        self.clear_selection_button.setEnabled(False)
        zoom_controls.addWidget(self.zoom_button)
        zoom_controls.addWidget(self.clear_selection_button)
        layout.addLayout(zoom_controls)

        region_controls = QHBoxLayout()
        self.region_combo = QComboBox()
        self.region_combo.addItem("Regions")
        self.save_region_button = QPushButton("Save region")
        self.delete_region_button = QPushButton("Delete region")
        region_controls.addWidget(self.region_combo)
        region_controls.addWidget(self.save_region_button)
        region_controls.addWidget(self.delete_region_button)
        layout.addLayout(region_controls)

        export_controls = QHBoxLayout()
        self.export_selection_checkbox = QCheckBox("Use selection")
        self.export_selection_checkbox.setEnabled(False)
        self.snapshot_button = QPushButton("Snapshot")
        self.gif_button = QPushButton("Export GIF")
        self.mp4_button = QPushButton("Export MP4")
        self.export_fps_spin = QSpinBox()
        self.export_fps_spin.setRange(1, 30)
        self.export_fps_spin.setValue(5)
        self.export_fps_spin.setSuffix(" fps")
        export_controls.addWidget(self.snapshot_button)
        export_controls.addWidget(self.gif_button)
        export_controls.addWidget(self.mp4_button)
        export_controls.addWidget(QLabel("FPS"))
        export_controls.addWidget(self.export_fps_spin)
        export_controls.addWidget(self.export_selection_checkbox)
        layout.addLayout(export_controls)

        self.image_view = ImageView()
        self.image_view.setSizePolicy(
            QSizePolicy.Expanding, QSizePolicy.Expanding
        )
        layout.addWidget(self.image_view, 1)

        status_row = QHBoxLayout()
        self.status_label = QLabel("Estado: listo")
        self.progress_label = QLabel("Progreso: -")
        status_row.addWidget(self.status_label)
        status_row.addStretch(1)
        status_row.addWidget(self.progress_label)
        layout.addLayout(status_row)

        self.prev_button.clicked.connect(self._prev_frame)
        self.next_button.clicked.connect(self._next_frame_manual)
        self.jump_back_button.clicked.connect(lambda: self._jump_hours(-24))
        self.jump_forward_button.clicked.connect(lambda: self._jump_hours(24))
        self.play_button.toggled.connect(self._toggle_play)
        self.speed_spin.valueChanged.connect(self._update_play_speed)
        self.speed_preset_combo.currentIndexChanged.connect(
            self._on_speed_preset_changed
        )
        self.loop_combo.currentTextChanged.connect(self._on_loop_mode_changed)
        self.slider.valueChanged.connect(self._on_slider_changed)
        self.slider.sliderPressed.connect(self._pause_playback)
        self.base_edit.dateTimeChanged.connect(self._update_current_frame)
        self.tz_combo.currentIndexChanged.connect(self._on_timezone_changed)
        self.auto_base_check.toggled.connect(self._on_auto_base_toggled)
        self.detect_base_button.clicked.connect(self._detect_base_from_current)
        self.refresh_spin.valueChanged.connect(self._update_refresh_interval)
        self.refresh_button.clicked.connect(self.refresh_images)
        self.zoom_button.toggled.connect(self._toggle_zoom)
        self.clear_selection_button.clicked.connect(self._clear_selection)
        self.image_view.selectionChanged.connect(self._on_selection_changed)
        self.region_combo.currentIndexChanged.connect(
            self._apply_region_preset
        )
        self.save_region_button.clicked.connect(self._save_region_preset)
        self.delete_region_button.clicked.connect(self._delete_region_preset)
        self.snapshot_button.clicked.connect(self._export_snapshot)
        self.gif_button.clicked.connect(self._export_gif)
        self.mp4_button.clicked.connect(self._export_mp4)

        self.gif_button.setEnabled(
            self._export_caps["pil"]
            or (self._export_caps["imageio"] and self._export_caps["numpy"])
        )
        self.mp4_button.setEnabled(
            self._export_caps["imageio"]
            and self._export_caps["numpy"]
            and self._export_caps["ffmpeg"]
        )

    def _setup_timers(self):
        self.play_timer = QTimer(self)
        self.play_timer.setInterval(self.speed_spin.value())
        self.play_timer.timeout.connect(self._next_frame_auto)

        self.refresh_timer = QTimer(self)
        self.refresh_timer.timeout.connect(self.refresh_images)
        self._update_refresh_interval(self.refresh_spin.value())

    def _start_initial_refresh(self):
        if self.auto_base_check.isChecked():
            self._apply_auto_base()
        self.refresh_images()

    def _detect_ocr_available(self):
        try:
            import PIL.Image  # noqa: F401
            import pytesseract  # noqa: F401
        except Exception:
            return False
        return True

    def _detect_export_backends(self):
        caps = {"pil": False, "imageio": False, "numpy": False, "ffmpeg": False}
        try:
            import PIL.Image  # noqa: F401
        except Exception:
            pass
        else:
            caps["pil"] = True

        try:
            import imageio  # noqa: F401
        except Exception:
            pass
        else:
            caps["imageio"] = True

        try:
            import numpy  # noqa: F401
        except Exception:
            pass
        else:
            caps["numpy"] = True

        if caps["imageio"]:
            try:
                import imageio_ffmpeg

                caps["ffmpeg"] = bool(imageio_ffmpeg.get_ffmpeg_exe())
            except Exception:
                try:
                    from imageio.plugins.ffmpeg import get_exe

                    caps["ffmpeg"] = bool(get_exe())
                except Exception:
                    caps["ffmpeg"] = False
        return caps

    def _settings_int(self, key, default):
        value = self.settings.value(key, default)
        if value is None:
            return default
        try:
            return int(value)
        except (TypeError, ValueError):
            return default

    def _load_json_setting(self, key):
        raw = self.settings.value(key, "")
        if not raw:
            return None
        try:
            return json.loads(raw)
        except Exception:
            return None

    def _load_settings(self):
        self.use_utc = bool_value(self.settings.value("use_utc"), False)
        self.tz_combo.blockSignals(True)
        self.tz_combo.setCurrentIndex(1 if self.use_utc else 0)
        self.tz_combo.blockSignals(False)

        speed_ms = self._settings_int("speed_ms", 500)
        self.speed_spin.setValue(speed_ms)
        self._sync_speed_preset(speed_ms)

        refresh_minutes = self._settings_int("refresh_min", 15)
        self.refresh_spin.setValue(refresh_minutes)

        auto_base = bool_value(self.settings.value("auto_base"), False)
        self.auto_base_check.setChecked(auto_base)

        loop_mode = self.settings.value("loop_mode", LOOP_MODES[0])
        if loop_mode in LOOP_MODES:
            self.loop_combo.setCurrentText(loop_mode)
            self.loop_mode = loop_mode

        current_index = self._settings_int("current_index", 0)
        if 0 <= current_index < len(self.offsets):
            self.current_index = current_index
        self.slider.setValue(self.current_index)

        base_epoch = self._settings_int("base_epoch", None)
        if base_epoch is not None:
            base_dt = QDateTime.fromSecsSinceEpoch(base_epoch, Qt.UTC)
            self._set_base_datetime_utc(base_dt, update_frame=False)

        zoomed = bool_value(self.settings.value("zoomed"), False)
        self.zoom_button.setChecked(zoomed)
        self.image_view.set_zoomed(zoomed)

        selection_ratio = self._load_json_setting("selection_ratio")
        if selection_ratio:
            self._pending_selection_ratio = selection_ratio

        self.region_presets = self._load_json_setting("region_presets") or []
        self._update_region_combo()

        export_fps = self._settings_int("export_fps", 5)
        self.export_fps_spin.setValue(export_fps)
        export_use_selection = bool_value(
            self.settings.value("export_use_selection"), True
        )
        self.export_selection_checkbox.setChecked(export_use_selection)

    def _save_settings(self):
        self.settings.setValue("use_utc", self.use_utc)
        self.settings.setValue("speed_ms", self.speed_spin.value())
        self.settings.setValue("refresh_min", self.refresh_spin.value())
        self.settings.setValue("auto_base", self.auto_base_check.isChecked())
        self.settings.setValue("loop_mode", self.loop_mode)
        self.settings.setValue("current_index", self.current_index)
        self.settings.setValue("zoomed", self.image_view.is_zoomed())
        self.settings.setValue(
            "export_fps", self.export_fps_spin.value()
        )
        self.settings.setValue(
            "export_use_selection",
            self.export_selection_checkbox.isChecked(),
        )
        base_epoch = self._get_base_datetime_utc().toSecsSinceEpoch()
        self.settings.setValue("base_epoch", base_epoch)
        if self._selection_ratio:
            self.settings.setValue(
                "selection_ratio", json.dumps(self._selection_ratio)
            )
        else:
            self.settings.setValue("selection_ratio", "")
        self.settings.setValue(
            "region_presets", json.dumps(self.region_presets)
        )
        self.settings.sync()

    def _get_base_datetime_utc(self):
        return self.base_edit.dateTime().toUTC()

    def _set_base_datetime_utc(self, base_utc, update_frame=True):
        if not base_utc or not base_utc.isValid():
            return
        if self.use_utc:
            display_dt = base_utc.toUTC()
        else:
            display_dt = base_utc.toLocalTime()
        self.base_edit.blockSignals(True)
        self.base_edit.setDateTime(display_dt)
        self.base_edit.blockSignals(False)
        if update_frame:
            self._update_current_frame()

    def _apply_auto_base(self):
        self._set_base_datetime_utc(last_cycle_utc())

    def _detect_base_from_current(self):
        pixmap = self.frames.get(self.offsets[self.current_index])
        if not pixmap:
            QMessageBox.information(
                self,
                "Detect base",
                "No hay imagen disponible para detectar la base.",
            )
            return
        ocr_dt = try_ocr_datetime(pixmap)
        if not ocr_dt:
            QMessageBox.information(
                self,
                "Detect base",
                "No se pudo leer la marca temporal.",
            )
            return
        offset = self.offsets[self.current_index]
        base_utc = ocr_dt.addSecs(-offset * 3600)
        self._set_base_datetime_utc(base_utc)
        self.status_label.setText("Estado: base ajustada por OCR")

    def _on_auto_base_toggled(self, checked):
        if checked:
            self._apply_auto_base()

    def _on_timezone_changed(self, index):
        use_utc = index == 1
        if use_utc == self.use_utc:
            return
        base_utc = self._get_base_datetime_utc()
        self.use_utc = use_utc
        self._set_base_datetime_utc(base_utc)

    def _toggle_play(self, checked):
        if checked:
            self.play_button.setText("Pause")
            self.play_timer.start()
        else:
            self.play_button.setText("Play")
            self.play_timer.stop()

    def _pause_playback(self):
        if self.play_button.isChecked():
            self.play_button.setChecked(False)

    def _update_play_speed(self, value):
        self.play_timer.setInterval(value)
        self._sync_speed_preset(value)

    def _sync_speed_preset(self, value):
        if value in SPEED_PRESETS:
            index = SPEED_PRESETS.index(value)
            self.speed_preset_combo.blockSignals(True)
            self.speed_preset_combo.setCurrentIndex(index)
            self.speed_preset_combo.blockSignals(False)
        else:
            self.speed_preset_combo.blockSignals(True)
            self.speed_preset_combo.setCurrentIndex(len(SPEED_PRESETS))
            self.speed_preset_combo.blockSignals(False)

    def _on_speed_preset_changed(self, index):
        if index < len(SPEED_PRESETS):
            self.speed_spin.setValue(SPEED_PRESETS[index])

    def _on_loop_mode_changed(self, text):
        if text in LOOP_MODES:
            self.loop_mode = text

    def _on_slider_changed(self, value):
        if value != self.current_index:
            self.current_index = value
            self._update_current_frame()

    def _set_current_index(self, index):
        self.current_index = index
        self.slider.blockSignals(True)
        self.slider.setValue(self.current_index)
        self.slider.blockSignals(False)
        self._update_current_frame()

    def _prev_frame(self):
        self.play_direction = -1
        self.current_index = (self.current_index - 1) % len(self.offsets)
        self._set_current_index(self.current_index)

    def _next_frame_manual(self):
        self.play_direction = 1
        self.current_index = (self.current_index + 1) % len(self.offsets)
        self._set_current_index(self.current_index)

    def _next_frame_auto(self):
        if self.loop_mode == "Stop" and self.current_index == len(self.offsets) - 1:
            self._pause_playback()
            return
        if self.loop_mode == "Ping-pong":
            if self.play_direction > 0 and self.current_index == len(self.offsets) - 1:
                self.play_direction = -1
            elif self.play_direction < 0 and self.current_index == 0:
                self.play_direction = 1
            self.current_index += self.play_direction
        else:
            self.current_index = (self.current_index + 1) % len(self.offsets)
        self._set_current_index(self.current_index)

    def _jump_hours(self, hours):
        steps = int(hours / 6)
        self.current_index = (self.current_index + steps) % len(self.offsets)
        self._set_current_index(self.current_index)

    def _toggle_zoom(self, checked):
        self.image_view.set_zoomed(checked)
        self._update_current_frame()

    def _clear_selection(self):
        self.image_view.clear_selection()
        self.zoom_button.setEnabled(False)
        self.zoom_button.setChecked(False)
        self.clear_selection_button.setEnabled(False)
        self.export_selection_checkbox.setEnabled(False)
        self.export_selection_checkbox.setChecked(False)

    def _rect_to_ratio(self, rect, pixmap):
        if not pixmap or pixmap.isNull():
            return None
        width = pixmap.width()
        height = pixmap.height()
        if width <= 0 or height <= 0:
            return None
        return [
            rect.x() / width,
            rect.y() / height,
            rect.width() / width,
            rect.height() / height,
        ]

    def _ratio_to_rect(self, ratio, pixmap):
        if not pixmap or pixmap.isNull():
            return QRect()
        if not ratio or len(ratio) != 4:
            return QRect()
        width = pixmap.width()
        height = pixmap.height()
        x = max(0, min(width - 1, int(ratio[0] * width)))
        y = max(0, min(height - 1, int(ratio[1] * height)))
        w = max(1, min(width - x, int(ratio[2] * width)))
        h = max(1, min(height - y, int(ratio[3] * height)))
        return QRect(x, y, w, h)

    def _on_selection_changed(self, rect):
        has_selection = not rect.isNull() and not rect.isEmpty()
        was_enabled = self.export_selection_checkbox.isEnabled()
        self.zoom_button.setEnabled(has_selection)
        self.clear_selection_button.setEnabled(has_selection)
        self.export_selection_checkbox.setEnabled(has_selection)
        if has_selection and not was_enabled:
            self.export_selection_checkbox.setChecked(True)
        if not has_selection:
            self.export_selection_checkbox.setChecked(False)
        if not has_selection and self.zoom_button.isChecked():
            self.zoom_button.setChecked(False)

        pixmap = self.frames.get(self.offsets[self.current_index])
        if has_selection and pixmap:
            self._selection_ratio = self._rect_to_ratio(rect, pixmap)
        else:
            self._selection_ratio = None

    def _update_current_frame(self):
        offset = self.offsets[self.current_index]
        pixmap = self.frames.get(offset)
        if pixmap:
            self.image_view.set_pixmap(pixmap)
            if self._pending_selection_ratio:
                rect = self._ratio_to_rect(
                    self._pending_selection_ratio, pixmap
                )
                self.image_view.set_selection_rect(rect, emit_signal=False)
                self._pending_selection_ratio = None
                self._on_selection_changed(rect)
            self.status_label.setText("Estado: imagen cargada")
        else:
            self.image_view.set_pixmap(QPixmap())
            self.status_label.setText("Estado: imagen no disponible")

        base_utc = self._get_base_datetime_utc()
        forecast_utc = base_utc.addSecs(offset * 3600)
        if self.use_utc:
            display_dt = forecast_utc.toUTC()
            tz_label = "UTC"
        else:
            display_dt = forecast_utc.toLocalTime()
            tz_label = "Local"
        self.time_label.setText(
            "Prediccion: {dt} {tz} (T+{offset:03d}h)".format(
                dt=display_dt.toString("dd/MM/yyyy HH:mm"),
                tz=tz_label,
                offset=offset,
            )
        )

    def _update_refresh_interval(self, minutes):
        self.refresh_timer.setInterval(minutes * 60 * 1000)
        if not self.refresh_timer.isActive():
            self.refresh_timer.start()

    def _build_priority_offsets(self):
        total = len(self.offsets)
        order = []
        seen = set()

        def add_index(idx):
            offset = self.offsets[idx]
            if offset not in seen:
                order.append(offset)
                seen.add(offset)

        add_index(self.current_index)
        for delta in range(1, NEIGHBOR_PREFETCH + 1):
            add_index((self.current_index + delta) % total)
            add_index((self.current_index - delta) % total)
        for offset in self.offsets:
            if offset not in seen:
                order.append(offset)
                seen.add(offset)
        return order

    def refresh_images(self):
        if self._refresh_in_progress:
            return
        self._refresh_in_progress = True
        self._ocr_attempted = False
        self._progress_total = len(self.offsets)
        self._progress_updated = 0
        self._progress_unchanged = 0
        self.progress_label.setText(
            "Progreso: 0/{}".format(self._progress_total)
        )
        if self.auto_base_check.isChecked():
            self._apply_auto_base()
        self.status_label.setText("Estado: actualizando imagenes...")

        headers_by_offset = {
            offset: self.metadata.headers_for(offset)
            for offset in self.offsets
        }
        self.fetcher.fetch_offsets(
            self._build_priority_offsets(),
            headers_by_offset=headers_by_offset,
            force_network=False,
        )

    def _on_fetch_progress(self, done, total):
        self.progress_label.setText(
            "Progreso: {}/{} (nuevas {}, sin cambios {})".format(
                done, total, self._progress_updated, self._progress_unchanged
            )
        )

    def _maybe_attempt_ocr(self, offset, pixmap):
        if not self.auto_base_check.isChecked():
            return
        if not self._ocr_available or self._ocr_attempted:
            return
        if offset != self.offsets[self.current_index] and offset != self.offsets[0]:
            return
        self._ocr_attempted = True
        ocr_dt = try_ocr_datetime(pixmap)
        if not ocr_dt:
            return
        base_utc = ocr_dt.addSecs(-offset * 3600)
        self._set_base_datetime_utc(base_utc)
        self.status_label.setText("Estado: base ajustada por OCR")

    def _on_image_loaded(self, offset, pixmap, meta):
        self.frames[offset] = pixmap
        self.cache.save(offset, pixmap)
        self.metadata.update(
            offset, meta.get("etag"), meta.get("last_modified")
        )
        self._progress_updated += 1
        self._maybe_attempt_ocr(offset, pixmap)
        if offset == self.offsets[self.current_index]:
            self._update_current_frame()

    def _on_image_not_modified(self, offset):
        self._progress_unchanged += 1

    def _on_image_error(self, offset, error_text):
        if offset == self.offsets[self.current_index]:
            self.status_label.setText(f"Estado: error de descarga ({error_text})")

    def _on_batch_finished(self):
        self._refresh_in_progress = False
        now = QDateTime.currentDateTime().toString("yyyy-MM-dd HH:mm")
        self.update_label.setText(f"Ultima actualizacion: {now}")
        self.metadata.save()
        if not self.frames.get(self.offsets[self.current_index]):
            self.status_label.setText("Estado: imagen no disponible")

    def _update_region_combo(self):
        self.region_combo.blockSignals(True)
        self.region_combo.clear()
        self.region_combo.addItem("Regions")
        for preset in self.region_presets:
            self.region_combo.addItem(preset.get("name", "Region"))
        self.region_combo.blockSignals(False)

    def _apply_region_preset(self, index):
        if index <= 0:
            return
        preset = self.region_presets[index - 1]
        ratio = preset.get("ratio")
        if not ratio:
            return
        pixmap = self.frames.get(self.offsets[self.current_index])
        if pixmap:
            rect = self._ratio_to_rect(ratio, pixmap)
            self.image_view.set_selection_rect(rect)
        else:
            self._pending_selection_ratio = ratio

    def _save_region_preset(self):
        rect = self.image_view.selection_rect()
        if rect.isNull() or rect.isEmpty():
            QMessageBox.information(
                self, "Save region", "Seleccione una zona primero."
            )
            return
        name, ok = QInputDialog.getText(
            self, "Save region", "Nombre de la region:"
        )
        if not ok or not name.strip():
            return
        pixmap = self.frames.get(self.offsets[self.current_index])
        ratio = self._rect_to_ratio(rect, pixmap)
        if not ratio:
            return
        self.region_presets.append({"name": name.strip(), "ratio": ratio})
        self._update_region_combo()
        self.settings.setValue(
            "region_presets", json.dumps(self.region_presets)
        )

    def _delete_region_preset(self):
        index = self.region_combo.currentIndex()
        if index <= 0:
            return
        self.region_presets.pop(index - 1)
        self._update_region_combo()
        self.settings.setValue(
            "region_presets", json.dumps(self.region_presets)
        )

    def _collect_export_frames(self, use_selection):
        frames = []
        missing = 0
        rect = None
        if use_selection:
            rect = self.image_view.selection_rect()
            if rect.isNull() or rect.isEmpty():
                QMessageBox.information(
                    self,
                    "Export",
                    "No hay seleccion activa para exportar.",
                )
                return [], 0
        for offset in self.offsets:
            pixmap = self.frames.get(offset)
            if not pixmap:
                missing += 1
                continue
            if rect:
                pixmap = pixmap.copy(rect)
            frames.append(pixmap)
        return frames, missing

    def _export_snapshot(self):
        pixmap = self.frames.get(self.offsets[self.current_index])
        if not pixmap:
            QMessageBox.information(
                self, "Snapshot", "No hay imagen disponible."
            )
            return
        if self.export_selection_checkbox.isChecked():
            rect = self.image_view.selection_rect()
            if not rect.isNull() and not rect.isEmpty():
                pixmap = pixmap.copy(rect)
        path, _ = QFileDialog.getSaveFileName(
            self, "Guardar snapshot", "snapshot.png", "PNG (*.png)"
        )
        if not path:
            return
        pixmap.save(path, "PNG")

    def _export_gif(self):
        if not (
            self._export_caps["pil"]
            or (self._export_caps["imageio"] and self._export_caps["numpy"])
        ):
            QMessageBox.information(
                self,
                "Export GIF",
                "Instala pillow o imageio + numpy para exportar GIF.",
            )
            return
        use_selection = self.export_selection_checkbox.isChecked()
        frames, missing = self._collect_export_frames(use_selection)
        if not frames:
            return
        path, _ = QFileDialog.getSaveFileName(
            self, "Export GIF", "timelapse.gif", "GIF (*.gif)"
        )
        if not path:
            return
        fps = self.export_fps_spin.value()
        duration_ms = int(1000 / fps)
        if self._export_caps["pil"]:
            try:
                from PIL import Image
            except Exception:
                QMessageBox.information(
                    self,
                    "Export GIF",
                    "No se pudo cargar pillow.",
                )
                return
            pil_frames = []
            for frame in frames:
                pil_image = pixmap_to_pil(frame)
                if pil_image is None:
                    QMessageBox.information(
                        self,
                        "Export GIF",
                        "No se pudo convertir a imagen.",
                    )
                    return
                pil_frames.append(pil_image)
            if not pil_frames:
                return
            try:
                pil_frames[0].save(
                    path,
                    save_all=True,
                    append_images=pil_frames[1:],
                    duration=duration_ms,
                    loop=0,
                )
            except Exception as exc:
                QMessageBox.information(
                    self,
                    "Export GIF",
                    f"Fallo al guardar GIF: {exc}",
                )
                return
        else:
            try:
                import imageio
                import numpy as np
            except Exception:
                QMessageBox.information(
                    self,
                    "Export GIF",
                    "Instala imageio y numpy para exportar GIF.",
                )
                return
            frames_np = []
            for frame in frames:
                pil_image = pixmap_to_pil(frame)
                if pil_image is None:
                    continue
                frames_np.append(np.asarray(pil_image.convert("RGB")))
            if not frames_np:
                return
            try:
                imageio.mimsave(
                    path,
                    frames_np,
                    format="GIF",
                    duration=1.0 / fps,
                )
            except Exception as exc:
                QMessageBox.information(
                    self,
                    "Export GIF",
                    f"Fallo al guardar GIF: {exc}",
                )
                return
        if missing:
            self.status_label.setText(
                f"Estado: GIF exportado, faltan {missing} frames."
            )
        else:
            self.status_label.setText("Estado: GIF exportado")

    def _export_mp4(self):
        if not (
            self._export_caps["imageio"]
            and self._export_caps["numpy"]
            and self._export_caps["ffmpeg"]
        ):
            QMessageBox.information(
                self,
                "Export MP4",
                "Instala imageio, numpy y ffmpeg para exportar MP4.",
            )
            return
        use_selection = self.export_selection_checkbox.isChecked()
        frames, missing = self._collect_export_frames(use_selection)
        if not frames:
            return
        path, _ = QFileDialog.getSaveFileName(
            self, "Export MP4", "timelapse.mp4", "MP4 (*.mp4)"
        )
        if not path:
            return
        fps = self.export_fps_spin.value()
        try:
            import imageio
            import numpy as np
        except Exception:
            QMessageBox.information(
                self,
                "Export MP4",
                "Instala imageio y numpy para exportar MP4.",
            )
            return
        try:
            writer = imageio.get_writer(path, fps=fps, codec="libx264")
        except Exception as exc:
            QMessageBox.information(
                self,
                "Export MP4",
                f"No se pudo iniciar el writer: {exc}",
            )
            return
        try:
            for frame in frames:
                pil_image = pixmap_to_pil(frame)
                if pil_image is None:
                    continue
                writer.append_data(np.asarray(pil_image.convert("RGB")))
        finally:
            writer.close()
        if missing:
            self.status_label.setText(
                f"Estado: MP4 exportado, faltan {missing} frames."
            )
        else:
            self.status_label.setText("Estado: MP4 exportado")

    def keyPressEvent(self, event):
        key = event.key()
        if key == Qt.Key_Space:
            self.play_button.setChecked(not self.play_button.isChecked())
            event.accept()
            return
        if key == Qt.Key_Left:
            self._prev_frame()
            event.accept()
            return
        if key == Qt.Key_Right:
            self._next_frame_manual()
            event.accept()
            return
        if key == Qt.Key_Up:
            self._jump_hours(24)
            event.accept()
            return
        if key == Qt.Key_Down:
            self._jump_hours(-24)
            event.accept()
            return
        if key == Qt.Key_Home:
            self._set_current_index(0)
            event.accept()
            return
        if key == Qt.Key_End:
            self._set_current_index(len(self.offsets) - 1)
            event.accept()
            return
        if key == Qt.Key_Z:
            if self.zoom_button.isEnabled():
                self.zoom_button.setChecked(
                    not self.zoom_button.isChecked()
                )
            event.accept()
            return
        super().keyPressEvent(event)

    def closeEvent(self, event):
        self._save_settings()
        super().closeEvent(event)


def main():
    app = QApplication(sys.argv)
    window = MainWindow()
    window.resize(1300, 900)
    window.show()
    sys.exit(app.exec_())


if __name__ == "__main__":
    main()
