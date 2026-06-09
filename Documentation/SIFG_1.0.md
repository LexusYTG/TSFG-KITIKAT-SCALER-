# SIFG 1.0 — Synthetic Interpolated Frame Generator v1.0

> Modo de generación de fotogramas sintéticos por traslación de movimiento global con EMA.  
> Implementado en: `SIFgV1Generator` dentro de `ScreenCaptureService.java`  
> Constante de modo: `MODE_SIFG1` (`sifgVersion = 0`)

---

## Descripción general

SIFG 1.0 es el modo de interpolación más simple del pipeline. Su principio: capturar el frame real, estimar el vector de movimiento global entre el frame anterior y el actual, y usar ese vector para "predecir" dónde estaría el frame si el movimiento continúa uniforme. La predicción se produce **trasladando el frame upscaleado** según el vector del ciclo **anterior** (no el actual), de modo que siempre se muestra un frame que anticipa el siguiente movimiento.

No genera fotogramas intercalados entre dos frames reales — genera **un único frame desplazado** que intenta representar el estado futuro inmediato.

---

## Lugar en el pipeline de dispatch

Cuando llega un frame desde el `ImageReader`, el `captureThread` evalúa el modo activo y despacha a `processingThread`:

```
if (useSIFg && sifgVersion == 0) → new SIFgV1Generator(image)
```

`useSIFg` se activa únicamente cuando `currentMode == MODE_SIFG1`.

---

## Ciclo por frame — paso a paso

### Paso 0: throttle de FPS

Antes de que `SIFgV1Generator` llegue a ejecutarse, el `onImageAvailable` del `captureThread` ya filtró el frame con `nextFrameDeadlineNs`. Si el frame llega antes del deadline (según el `targetFrameNanos` configurado), se descarta sin procesar y `droppedFrames` se incrementa.

### Paso 1: captura del frame

```java
byte[] src = bufferPool.get(KEY_SOURCE_BUFFER);
imageCapture.captureImageToBuffer(image, src, colorQuality);
```

`captureImageToBuffer` copia el `ByteBuffer` RGBA de `Image.Plane[0]` al buffer `src` del pool. El modo `colorQuality` puede recortar bits por canal:

| colorQuality | Efecto |
|---|---|
| 0 — Full | Copia directa, sin modificación |
| 1 — 5-bit | `& 0xF8` en R, G, B → elimina 3 LSBs |
| 2 — 3-3-2 bit | R→3 bits, G→3 bits, B→2 bits (256 colores efectivos) |

Si hay `rowPad` (padding de alineación de fila en el `ImageReader`), se maneja saltando esos bytes por fila. El canal Alpha se fuerza a 255 en todos los modos.

### Paso 2: estimación de movimiento global

```java
byte[] prevSrc = bufferPool.get(KEY_LAST_REAL_FRAME);
int[] motion = FrameUtils.estimateMotionUltraLight(prevSrc, src, sourceWidth, sourceHeight);
```

Se llama a `estimateMotionUltraLight`, una estimación de movimiento global liviana que trabaja sobre 6 puntos de muestreo distribuidos en una grilla 3×2 (cuartos y mitades del frame). Para cada punto:

1. Se mide el gradiente local (4 vecinos, 3 canales). Si `grad < 12`, el punto se ignora (región plana, sin señal de movimiento).
2. Se hace block-matching con candidatos fijos `ULTRA_LIGHT_CANDS` — 9 offsets en una grilla 3×3 de paso 3px (`{-3,-3}, {0,-3}, {3,-3}, ...`).
3. SAD sobre 1 pixel (patch de 1×1) usando los 3 canales RGB del pixel central.
4. Si el mejor SAD es 0 (coincidencia exacta sin movimiento) y el offset es `(0,0)`, el punto se descarta (sin movimiento detectado, SAD demasiado bajo, no es confiable).
5. Si `bestSAD > 200`, el punto también se descarta (movimiento caótico o cambio de escena).
6. Se aplica sub-pixel refinement horizontal mediante interpolación parabólica de los SADs vecinos:

```java
dxSub = (sm - sp) / (2 * (sm + sp - 2 * bestSAD))   // clamp ±0.5
```

El resultado final es la mediana de los `dxSamples` / `dySamples` válidos (sort in-place + índice central). Usar la mediana en vez de la media hace que un punto outlier (e.g. objeto moviéndose en sentido opuesto al fondo) no contamine el vector global.

### Paso 3: escalar el vector a espacio target

```java
float scaleX = (float) targetWidth  / sourceWidth;
float scaleY = (float) targetHeight / sourceHeight;
int rawDx = (int)(motion[0] * scaleX);
int rawDy = (int)(motion[1] * scaleY);
rawDx = clamp(rawDx, -MAX_DELTA_PX, MAX_DELTA_PX);   // MAX_DELTA_PX = 32
rawDy = clamp(rawDy, -MAX_DELTA_PX, MAX_DELTA_PX);
```

El vector se escala al espacio de destino (resolución display) porque la traslación del frame se aplica sobre el frame **upscaleado**. El clamp a ±32px es anti-glitch: protege contra vectores aberrantes por cambios de escena.

### Paso 4: suavizado EMA

```java
final float EMA_ALPHA = 0.35f;
newDx = round(rawDx * EMA_ALPHA + sifgV10Dx * (1 - EMA_ALPHA));
newDy = round(rawDy * EMA_ALPHA + sifgV10Dy * (1 - EMA_ALPHA));
```

Se aplica una EMA (Exponential Moving Average) con α = 0.35. Esto suaviza los saltos abruptos en el vector de movimiento. La EMA tiene más peso en el historial (`0.65`) que en la medición nueva (`0.35`), priorizando estabilidad temporal sobre reactividad. La operación se hace sobre enteros (resultado de `Math.round`).

`sifgV10Dx` / `sifgV10Dy` son los campos persistentes del vector suavizado del ciclo anterior.

### Paso 5: upscaling del frame actual

```java
byte[] upscaled = upscaler.upscale(src);
```

`NearestNeighborUpscaler` escala `src` (resolución fuente, e.g. 540×960) a resolución display (e.g. 1080×1920). El upscaler usa tablas de índice pre-computadas:

```java
xMap[dstX] = floor(dstX * srcWidth  / targetWidth)
yMap[dstY] = floor(dstY * srcHeight / targetHeight)
```

No hay floats ni multiplicaciones en el hot loop. Optimización adicional: si `yMap[y] == yMap[y-1]`, la fila de destino es idéntica a la anterior → `System.arraycopy` de la fila ya escrita (evita iterar X completo). Usa ping-pong de dos slots para evitar que el render thread lea un buffer mientras se escribe.

### Paso 6: predicción por traslación

```java
if (sifgV10Dx != 0 || sifgV10Dy != 0 && prevSrc != null) {
    byte[] predicted = bufferPool.get(KEY_PREDICT_FRAME);
    FrameUtils.translateFrame(upscaled, predicted, sifgV10Dx, sifgV10Dy, targetWidth, targetHeight);
    renderToSurface(predicted);
} else {
    renderToSurface(upscaled);
}
```

Se usa el vector del ciclo **anterior** (`sifgV10Dx/Dy`), no el recién calculado. Esto es intencional: el vector actual describe el movimiento A→B (pasado→presente), y ese mismo vector se asume que continúa hacia el frame siguiente. Aplicarlo al frame presente upscaleado produce la predicción del frame futuro.

`translateFrame` copia el contenido de `upscaled` a `predicted` desplazado por `(sifgV10Dx, sifgV10Dy)` píxeles usando `System.arraycopy` por filas. Las regiones descubiertas por el desplazamiento quedan en negro (el buffer se llena con `Arrays.fill(dst, 0)` antes de copiar).

Si el vector es `(0,0)` o no hay frame previo, se renderiza el frame upscaleado sin modificar.

### Paso 7: actualización del estado

```java
sifgV10Dx = newDx;
sifgV10Dy = newDy;
System.arraycopy(src, 0, prevSrc, 0, src.length);
```

Se persiste el vector EMA para el próximo ciclo y se actualiza `KEY_LAST_REAL_FRAME` con el frame capturado actualmente (que en el siguiente ciclo será el "frame anterior").

---

## Render a superficie

`renderToSurface(predicted)` envía el buffer al `renderThread` mediante latest-wins: se escribe en `pendingRenderFrame` y se postea `renderPendingRunnable` al `renderHandler`. Si el render thread aún no procesó el frame anterior, simplemente lo pisa. No hay cola — solo el frame más reciente se pinta.

El `SurfaceRenderer` hace `lockCanvas` → `drawBitmap` (cargando el `byte[]` en un `Bitmap` temporal o usando un Bitmap del pool) → `unlockCanvasAndPost`.

---

## Buffers involucrados

| Buffer (clave de pool) | Contenido | Tamaño |
|---|---|---|
| `KEY_SOURCE_BUFFER` | Frame capturado actual (RGBA) | `srcW × srcH × 4` |
| `KEY_LAST_REAL_FRAME` | Frame anterior (para estimación de movimiento) | `srcW × srcH × 4` |
| `KEY_PREDICT_FRAME` | Frame upscaleado desplazado (output) | `dstW × dstH × 4` |
| Upscale ping-pong A/B | Salida del upscaler | `dstW × dstH × 4` |

Todos los buffers se alocan **una sola vez** al iniciar la captura. No hay `new byte[]` en el hot path.

---

## Estado interno persistente

| Campo | Tipo | Rol |
|---|---|---|
| `sifgV10Dx` | `int` | Vector EMA horizontal del ciclo anterior |
| `sifgV10Dy` | `int` | Vector EMA vertical del ciclo anterior |
| `sifgPrevDx` | `float` | (no usado activamente en v1.0, reservado) |
| `sifgPrevDy` | `float` | (no usado activamente en v1.0, reservado) |

Estos se resetean a 0 en `resetHashHistory()`, que se llama al cambiar de modo.

---

## Limitaciones conocidas

- Un único vector global para todo el frame: si hay movimiento de cámara + objeto en dirección opuesta, el vector resultante es una mediana que puede ser incorrecta para ambos.
- El vector del ciclo anterior introduce un lag de 1 frame en la predicción — si el movimiento cambia abruptamente, el frame sintetizado puede ir en la dirección incorrecta por 1 ciclo.
- Las regiones descubiertas por la traslación (bordes negros) son visibles en movimientos grandes.
- No hay frame intermedio generado: la salida es 1 frame por frame real capturado.

---

*Basado en análisis del código fuente: `SIFgV1Generator`, `FrameUtils.estimateMotionUltraLight`, `FrameUtils.translateFrame`, `NearestNeighborUpscaler`, `ImageCapture`.*
