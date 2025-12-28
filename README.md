# Nothing-Forecast

Nothing-Forecast is a minimal Android timelapse viewer for ECMWF model imagery. It pulls the ECMWF HRES frames, lets you scrub and loop the forecast, and syncs timestamps via OCR so each frame shows its real valid time.

## Features
- Frame-by-frame playback with loop modes and speed presets.
- OCR-based timestamp detection per frame (UTC).
- Region selection + presets.
- Snapshot export (optionally using the selection).

## Project layout
- `Android-Version/`: Android (Jetpack Compose) app.
- `tiempo_timelapse.py`: Desktop reference implementation.

## Android build
1. Open `Android-Version/` in Android Studio.
2. Sync Gradle.
3. Run on device or emulator (minSdk 34).

## Notes
- OCR runs on cached frames and stores results in `cache/ocr_times.json`.
- The UI displays both OCR-detected time and the calculated base time.
