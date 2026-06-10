# GFAL — Generación de Fotogramas Asistida Lineal

> Pipeline de generación de fotogramas predichos por optical flow para servicios de overlay en Android.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Threading Model](#threading-model)
- [Frame Capture](#frame-capture)
- [BufferPool](#bufferpool)
- [GFaLGenerator — Per-Frame Cycle](#gfalgenerator--per-frame-cycle)
- [Flow Extraction — Block Matching](#flow-extraction--block-matching)
- [Flow Refinement — Neural Net (optional)](#flow-refinement--neural-net-optional)
- [Prediction Building](#prediction-building)
- [Upscaling & Render](#upscaling--render)
- [Optimizations](#optimizations)

---

## Architecture Overview

```
┌──────────────┐     ┌─────────────────────┐     ┌─────────────┐
│ captureThread│────▶│  processingThread   │────▶│ renderThread│
│  (ImageReader│     │  capture → flow     │     │  overlay    │
│   callback)  │     │  → predict → scale  │     │  surface    │
└──────────────┘     └─────────────────────┘     └─────────────┘
```

All three threads run at `URGENT_DISPLAY` priority. Processing is **single-slot**: if `processingBusy` is set when a new frame arrives, the incoming frame is dropped (`droppedFrames++`) and the pipeline always works with the **latest available frame** — no queue accumulates.

A `nextFrameDeadlineNs` throttle enforces the target FPS set by `setTargetFps`. Frames arriving before the deadline are discarded without processing.

---

## Threading Model

| Thread | Responsibility |
|---|---|
| `captureThread` | Receives `Image` availability events from `ImageReader`. Decides accept/drop. No processing. |
| `processingThread` | Full pipeline: capture → flow extraction → prediction → upscale. |
| `renderThread` | Receives the final `byte[]` and blits it to the overlay surface. Latest-wins (no queue). |

---

## Frame Capture

When Android delivers a frame, it arrives as an `Image` object with a `ByteBuffer` RGBA inside `Image.Plane[0]`.

`ImageCapture.captureImageToBuffer()` copies that buffer into a pooled `byte[]` (format: **RGBA interleaved, 4 bytes/pixel, stride = `width × 4`**), then immediately closes the `Image` to free the native resource.

### Color Quality Modes

| Mode | Description | Use case |
|---|---|---|
| `0` — Full | Direct byte copy. Single `buf.get()` if no row padding. | Default, fastest |
| `1` — 5-bit | `& 0xF8` per RGB channel. Drops 3 LSBs. | Reduces quantization noise |
| `2` — 3-3-2 bit | R→3 bits, G→3 bits, B→2 bits. 256 effective colors. | CPU-constrained devices |

> **Note:** `rowPad` handling — Android may add alignment padding at the end of each row. The slow path skips it explicitly per row.

---

## BufferPool

`BufferPool` is a `HashMap` of all `byte[]` and `Bitmap` objects the pipeline needs, allocated **once** at capture start. No `new byte[]` occurs in the hot path (except on first frame or resolution change).

| Key | Contents | Size |
|---|---|---|
| `KEY_SOURCE_BUFFER` | Current captured frame | `srcW × srcH × 4` |
| `KEY_LAST_REAL_FRAME` | Previous frame (frame A for comparison) | `srcW × srcH × 4` |
| `KEY_EXTRAPOLATION` / `_B` | Ping-pong predicted frames | `srcW × srcH × 4` |
| `KEY_UPSCALE_A` / `_B` | Ping-pong upscaled frames | `dstW × dstH × 4` |

The ping-pong pairs allow the render thread to read one buffer while the processing thread writes the other.

---

## GFaLGenerator — Per-Frame Cycle

This is the core pipeline, executed once per accepted frame in `processingThread`.

```
Image arrives
  ├── captureImageToBuffer()         → src[]
  ├── retrieve prevSrc[] from pool
  └── if prevSrc exists:
        ├── gfalExtractFlow(prevSrc, src)    → gfalFlowDx[], gfalFlowDy[]
        ├── (optional) gfalRefineFlow(...)   → adjust dx/dy via net
        ├── gfalBuildPrediction(src, pred)   → pred[]
        ├── upscaler.upscale(pred)           → up[]
        └── renderToSurface(up)

  └── System.arraycopy(src → prevSrc)
  └── image.close()
```

> **First frame:** No prediction available — frame is upscaled and displayed raw.

---

## Flow Extraction — Block Matching

`gfalExtractFlow` is the most CPU-intensive step. It computes a motion vector `(dx, dy)` for each tile in a fixed grid by comparing the previous frame (A) with the current frame (B).

### Tile Grid

The frame is divided into a **16 × 7 grid = 112 tiles** (`GFAL_COLS = 16`, `GFAL_ROWS = 7`).

Each tile center is placed at:

```
cx = (col + 1) × width  / (GFAL_COLS + 1)
cy = (row + 1) × height / (GFAL_ROWS + 1)
```

This distributes sampling points evenly with border margins. These are 112 **sample points**, not 112 contiguous blocks.

### Gradient Pre-filter

Before block matching, the local gradient on the **G channel** (green) is measured at the tile center:

```
grad = |G(cx,cy) − G(cx−1,cy)| + |G(cx+1,cy)| + |G(cx,cy−1)| + |G(cx,cy+1)|
```

If `grad < 10` (flat/textureless region):

- `outDx[tileIdx] = 0`, `outDy[tileIdx] = 0`
- `gfalSadNorm = 1.0`, `gfalGradNorm = 0.0`
- **Skip block matching** (`continue`)

This avoids wasting CPU on uniform backgrounds where no motion signal is recoverable.

### Exhaustive Block Matching

For tiles with sufficient texture, an exhaustive search is performed within a radius of `searchRadius` (default `16 px`, up to `64 px`).

The radius is clamped to prevent out-of-bounds access:

```
clampedSR = min(searchRadius, cx, cy, width−1−cx, height−1−cy)
```

For each candidate offset `(sdx, sdy)` in `[−clampedSR, +clampedSR]²`, the **SAD** (Sum of Absolute Differences) is computed over a **3×3 patch** (PATCH_R = 1) using only the G channel:

```
SAD = Σ |A[cy+ky][cx+kx] − B[cy+ky+sdy][cx+kx+sdx]|   for (kx,ky) ∈ [−1,+1]²
```

Using a single channel is ~3× faster than RGB and is sufficient since motion affects all channels equally.

The `(sdx, sdy)` with the minimum SAD is selected. If `SAD = 0` is found, search exits early (`break`). If the best SAD exceeds `9 × 200 = 1800`, the vector is set to `(0, 0)` (likely scene cut or chaotic region).

### Stored Metrics

After block matching, two normalized arrays are populated for use by `gfalRefineFlow`:

| Array | Formula | Range |
|---|---|---|
| `gfalSadNorm[i]` | `bestSAD / (9 × 255)` | [0, 1] |
| `gfalGradNorm[i]` | `grad / (4 × 255)` | [0, 1] |

---

## Flow Refinement — Neural Net (optional)

Activated when `gfalNetEnabled = true`. Implemented in `GFaLFlowNet`.

### Architecture

```
Input (8) → Hidden (12, ReLU) → Output (2)
Total parameters: (8×12 + 12) + (12×2 + 2) = 122
```

### Inputs per Tile

| Index | Value | Description |
|---|---|---|
| x0 | `dxRaw / 16` | Raw horizontal vector, normalized |
| x1 | `dyRaw / 16` | Raw vertical vector, normalized |
| x2 | `sadNorm` | Inverse confidence (high SAD = low trust) |
| x3 | `gradNorm` | Texture richness |
| x4 | `dxLeft / 16` | Refined vector of left neighbor tile |
| x5 | `dyLeft / 16` | Refined vector of left neighbor tile |
| x6 | `dxUp / 16` | Refined vector of tile above |
| x7 | `dyUp / 16` | Refined vector of tile above |

### Output

`delta[0], delta[1]` — a delta in pixels added to the raw vector:

```java
flowDx[idx] += alpha * delta[0];
```

With `alpha = 0` the net has no effect. With `alpha = 1` the full delta is applied.

> **Weights design:** The default weights approximate an identity — the net propagates `dx/dy` nearly unchanged, attenuating vectors when SAD is high or gradient is low. Actual refinement would emerge from training on real data.

Processing order is **left-to-right, top-to-bottom**, so when tile `(row, col)` is processed, its left and upper neighbors are already refined — the net has causal spatial context.

Scratch arrays `gfalNetHidden` and `gfalNetDelta` are pre-allocated as service fields to avoid hot-path allocations.

---

## Prediction Building

`gfalBuildPrediction` constructs the predicted frame C pixel-by-pixel from frame B and the flow field.

### Principle

Given the motion A→B, constant-motion extrapolation predicts that frame C = B shifted by the same vectors. For each output pixel `(x, y)`:

1. Interpolate `(dx, dy)` from the flow field at position `(x, y)`
2. Read the pixel from B at `(x + dx, y + dy)`
3. Write it to `(x, y)` in C

### Bilinear Flow Interpolation

The 112 tile vectors form a sparse grid. To interpolate at any frame position, the surrounding 4 tiles are located and their vectors are blended.

**Linear (previous):**

```
tx = fractional position between tile columns
dx = dx[r0,c0]×(1−tx)×(1−ty) + dx[r0,c1]×tx×(1−ty)
   + dx[r1,c0]×(1−tx)×ty     + dx[r1,c1]×tx×ty
```

At tile boundaries the derivative has a visible discontinuity — "block" artifacts appear when neighboring tiles have very different vectors.

**Smoothstep (current):**

```
tx_s = tx² × (3 − 2×tx)
```

Smoothstep has **zero derivative at t = 0 and t = 1**, and reaches 50% exactly at t = 0.5. Transitions between tiles blend smoothly instead of creating hard edges.

> Cost: 4 multiplications + 2 additions per pixel vs. 2 multiplications + 2 additions. Negligible given the loop already iterates `width × height` pixels.

### Pixel Lookup

```
srcX = x + round(dx)
srcY = y + round(dy)
out[y][x] = frameB[srcY][srcX]    // nearest-neighbor
```

No bilinear interpolation on the pixel lookup — direct access. Rounding introduces a max 0.5 px error, invisible for small motions.

---

## Upscaling & Render

The predicted frame `pred[]` (source resolution, e.g. 540×960) is upscaled to display resolution (e.g. 1080×1920) by `NearestNeighborUpscaler`.

### Pre-computed Index Tables

```java
xMap[dstX] = floor(dstX × srcWidth  / targetWidth)
yMap[dstY] = floor(dstY × srcHeight / targetHeight)
```

For each destination pixel, the upscaler copies `source[yMap[y]][xMap[x]]` — no floats, no multiplications in the hot loop.

**Row deduplication:** if `yMap[y] == yMap[y−1]`, the destination row is identical to the previous one → `System.arraycopy` of the already-written row, skipping the full X iteration.

### Render

The upscaled result is sent to `renderThread` via `renderToSurface()`. This is **latest-wins**: if the render thread hasn't consumed the previous post yet, it is replaced. No pending frame queue — only the most recent frame is ever painted.

---

## Optimizations

### Tile Freeze (`TileFreezeDetector`)

Skips block matching entirely for tiles that have been stationary across recent frames.

A tile is considered **frozen** when its SAD has been ≤ 40 for the last **4 consecutive frames**. When frozen, `continue` skips the entire block-matching block and the previous vector remains unchanged.

```
nextFrame()          // advance circular history pointer
isFrozen(tileIdx)    // true → skip, false → run block matching
recordSad(tileIdx, bestSAD)  // store result for history
```

CPU savings scale directly with the number of frozen tiles. On a static menu or text screen, 80–100 of 112 tiles can be frozen.

### Border Blending (Smoothstep)

Replaces linear flow interpolation in `gfalBuildPrediction` with `smoothstep(t) = t²(3−2t)` to eliminate visible seams at tile boundaries. See [Prediction Building](#prediction-building) for details.

---

*Documentación generada a partir del análisis del código fuente — `GFaLGenerator`, `FrameUtils`, `GFaLFlowNet`, `NearestNeighborUpscaler`, `TileFreezeDetector`.*

