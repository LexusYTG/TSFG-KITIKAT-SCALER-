# KITIKAT Scaler

**Android app that captures your screen at a lower resolution and renders interpolated frames on top of it in real time, making everything look smoother without touching the system.**

Developed by **LexusYTG** — Turing Software · v1.1

---

## What it does

KITIKAT captures the screen via MediaProjection, scales it down to a configurable resolution, synthesizes intermediate frames using one of several algorithms, and renders the result in a floating overlay window on top of everything. The overlay forwards touches to the real screen underneath so you can interact normally while it's running.

The main use case is boosting perceived smoothness on games or apps that run at low FPS, without root or any system modification.

---

## Processing modes

| Mode | ID | Description |
|---|---|---|
| **Performance** | 0 | Pass-through — no synthesis, just downscale + upscale. Baseline. |
| **SIFg v1** (CPU) | 1 | Block-matching motion estimation, splits motion vector per region. |
| **SIFg v1.1** (GPU) | 3 | Same as SIFg v1 but runs through RenderScript for GPU acceleration. |
| **ASIFg** (CPU) | 2 | Adaptive SIFg — adds a small neural net (128→16→8→2) that learns optical flow per session via online SGD with momentum. Improves over time while running. |
| **ASIFg** (GPU) | 4 | ASIFg with RenderScript pipeline. |
| **GFaL v1** | 5 | Frame generation via Line Analysis. Block-matching over a grid, with optional neural refinement via GFaLFlowNet (8→12→2). |

All modes except Performance support skipping static frames via hash comparison (djb2 over 4 zones of the frame), which saves a lot of CPU when the screen isn't moving.

---

## Architecture

```
MainActivity
│   Manages permissions, UI, and binds to the service.
│   Delegates all UI logic to UiController / DialogManager / PrefManager.
│   Hosts TouchForwardService (AccessibilityService inner class).
│
└── ScreenCaptureService  [Foreground Service]
    │   Orchestrates the whole capture → process → render pipeline.
    │   Three dedicated threads:
    │     captureThread    — ImageReader callback, ~60 FPS acquisition
    │     processingThread — frame synthesis (heavy work)
    │     renderThread     — lockCanvas / drawBitmap to overlay
    │
    ├── BufferPool           — reusable byte[] and Bitmap pools (no GC pressure)
    ├── ImageCapture         — copies RGBA from ImageReader to byte[]
    ├── NearestNeighborUpscaler — ping-pong nearest-neighbor upscale
    ├── SurfaceRenderer      — draws frames onto the overlay Surface
    ├── OverlayWindow        — creates/destroys the SYSTEM_ALERT_WINDOW overlay
    ├── CaptureNotification  — persistent foreground notification
    ├── RenderScriptPipeline — GPU path via RenderScript
    ├── StaticFrameDetector  — skips synthesis when screen hasn't changed
    ├── FrameUtils           — blend, translation, motion estimation
    ├── ASIFgNetwork         — online-learning neural net for optical flow
    └── GFaLFlowNet          — tiny fixed-weight net for GFaL flow refinement
```

The whole codebase is **Java 7** — no lambdas, no streams. Built with AIDE / Android build tools, not Gradle from Android Studio.

---

## Configurable parameters

Everything is stored in `SharedPreferences` (`ScreenScalerStats`).

| Parameter | Default | Options |
|---|---|---|
| Resolution scale | 50% | 25 / 50 / 75% |
| Frames to generate | 1 | — |
| Color quality | 0 | — |
| Target FPS | 60 | 30 / 45 / 60 / 90 / 120 |
| Fisheye correction | off | — |
| Touch forward | off | — |
| Capture mode | Fullscreen | Fullscreen / Single app |
| SIFg region size | 8×8 px | 4 / 8 / 16 / 32 |
| ASIFg neurons | 64 | 16 / 32 / 64 / 128 / 256 |
| ASIFg threshold | 50 | — |
| GFaL search radius | 16 px | — |
| GFaL lines largo/ancho | 16 / 7 | 2–64 |
| GFaL grid | 4×4 | 2 / 4 / 8 / 16 |
| GFaLFlowNet enabled | off | — |
| GFaLFlowNet alpha | 50% | 0 / 10 / 25 / 50 / 75 / 100% |

---

## Permissions required

- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` — to keep the service alive and capture the screen.
- `SYSTEM_ALERT_WINDOW` — to draw the overlay on top of other apps.
- `WAKE_LOCK` — prevents CPU sleep during processing.
- `POST_NOTIFICATIONS` — persistent notification while running.
- `BIND_ACCESSIBILITY_SERVICE` — for touch forwarding.

---

## Requirements

- Android 7.0+ (API 24) · Targets API 34
- Hardware acceleration enabled (required for overlay rendering)
- Overlay permission must be granted manually from Settings

---

## Project structure

```
KITIKAT/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/TuringSoftware/FrameGenerator/
│       │   ├── MainActivity.java
│       │   ├── ScreenCaptureService.java
│       │   ├── AppConstants.java
│       │   ├── capture/
│       │   │   └── CaptureSettings.java
│       │   ├── service/
│       │   │   ├── ASIFgNetwork.java
│       │   │   ├── buffers/         (BufferPool, ImageCapture, NearestNeighborUpscaler)
│       │   │   ├── notification/    (CaptureNotification)
│       │   │   ├── overlay/         (OverlayWindow)
│       │   │   ├── pipeline/        (RenderScriptPipeline)
│       │   │   ├── processors/      (FrameUtils, GFaLFlowNet, StaticFrameDetector)
│       │   │   └── render/          (FisheyeCorrector, SurfaceRenderer)
│       │   ├── ui/
│       │   │   ├── UiController.java
│       │   │   ├── DialogManager.java
│       │   │   └── PrefManager.java
│       │   └── utils/
│       │       ├── BatteryHelper.java
│       │       ├── CatMoodHelper.java
│       │       └── PermissionHelper.java
│       └── res/
│           ├── layout/              (activity_main, cards: mode/resolution/metrics/config/status)
│           ├── values/              (strings, colors, styles, dimens)
│           └── xml/                 (accessibility_service_config)
└── build.gradle / settings.gradle / .gitignore
```

---

## Live metrics

While running, the UI shows:

- **FPS** — actual rendered frames per second
- **Generated** — interpolated frames produced
- **Dropped** — frames that didn't make the deadline
- **Efficiency** — ratio of generated vs dropped, displayed as a cat face:
  - `( ●ω● )` ≥ 95% · `( ´ω` )` ≥ 80% · `( •ω• )` ≥ 60% · `( ≥ω≤ )` ≥ 40% · `( ×ω× )` below that
- **Session** — elapsed time

---

## Building

The project is set up for AIDE or any Android build environment that supports the legacy `apply plugin: 'com.android.application'` syntax. No Kotlin, no ViewBinding, no Jetpack — pure Java 7 + Android SDK.

The `app/build/` folder with compiled `.class` and `.dex` files is included in the repo, so a prebuilt debug binary is available without recompiling.
