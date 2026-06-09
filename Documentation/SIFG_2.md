# SIFG 2 — Synthetic Interpolated Frame Generator v1.1

> Modo de generación de fotogramas por blend temporal (interpolación 50/50 entre frame anterior y actual).  
> Implementado en: `SIFgV1_1Generator` dentro de `ScreenCaptureService.java`  
> Constante de modo: `MODE_SIFG1` con `sifgVersion = 1`

---

## Descripción general

SIFG 2 (internamente `SIFgV1_1Generator`, versión `sifgVersion = 1`) cambia radicalmente el enfoque respecto a SIFG 1.0. En lugar de predecir un frame futuro por traslación, genera un frame **interpolado temporal**: mezcla el frame anterior y el actual a 50/50 para producir un frame sintético que representa el instante intermedio entre los dos frames reales. Luego emite ambos: primero el frame interpolado, después el frame real.

El resultado neto es duplicar el conteo de frames emitidos: por cada frame real capturado, se envían 2 frames al renderer. Si la captura va a 30 FPS reales, el overlay puede mostrar ~60 FPS perceptuales (con la mitad siendo frames sintéticos).

A diferencia de SIFG 1.0, **no hay estimación de movimiento, no hay traslación, no hay vectores**. Es puramente aritmética de píxeles.

---

## Lugar en el pipeline de dispatch

```
if (useSIFg && sifgVersion == 1) → new SIFgV1_1Generator(image)
```

`sifgVersion` se configura vía `setSifgVersion(int v)` desde la UI. El `sifgVersion` default es 0 (SIFG 1.0); al seleccionar la opción "v1.1" en la interfaz, se fija en 1.

---

## Diferencia clave con SIFG 1.0

| Aspecto | SIFG 1.0 | SIFG 2 (v1.1) |
|---|---|---|
| Frames emitidos por ciclo | 1 (predicho) | 2 (blend + real) |
| Técnica | Traslación por vector de movimiento | Mezcla 50/50 pixel a pixel |
| Estimación de movimiento | Sí (estimateMotionUltraLight) | No |
| Frame sintético representa | Futuro estimado | Presente interpolado |
| Lock de procesamiento | `processingBusy` (latest-wins) | `releaseProcessingLockOrdered` (ordenado) |

---

## Ciclo por frame — paso a paso

### Paso 0: throttle de FPS

Mismo mecanismo que SIFG 1.0: `nextFrameDeadlineNs` en `captureThread` antes del dispatch.

### Paso 1: captura del frame

```java
byte[] src = bufferPool.get(KEY_SOURCE_BUFFER);
imageCapture.captureImageToBuffer(image, src, colorQuality);
```

Idéntico a SIFG 1.0: copia RGBA del `ImageReader` al buffer del pool, con soporte de `rowPad` y los tres modos de `colorQuality` (Full / 5-bit / 3-3-2).

### Paso 2: verificación de ventana temporal

```java
byte[] prevSrc  = bufferPool.get(KEY_LAST_REAL_FRAME);
Long   prevTime = bufferPool.get(KEY_LAST_FRAME_TIME);
long   now      = System.nanoTime();
boolean generated = false;

if (prevSrc != null && prevTime != null
    && prevSrc.length == src.length
    && (now - prevTime) <= 50_000_000L) {  // 50 ms = máx 20 FPS de gap
```

La condición temporal `≤ 50 ms` es crítica: si el gap entre frames reales supera 50 ms (lo que ocurre si la captura baja de ~20 FPS), el frame anterior se considera "demasiado viejo" y no se genera interpolación. En ese caso se cae al path de fallback (solo se emite el frame real). Esto evita producir un blend entre frames que son temporalmente muy distantes, lo que generaría artefactos de ghosting severo.

### Paso 3: selección del slot de blend (ping-pong)

```java
String blendKey = sifgBlendSlotA
    ? BufferPool.KEY_EXTRAPOLATION
    : BufferPool.KEY_EXTRAPOLATION_B;
sifgBlendSlotA = !sifgBlendSlotA;
byte[] blend = bufferPool.get(blendKey);
```

El buffer de blend alterna entre dos slots (`KEY_EXTRAPOLATION` y `KEY_EXTRAPOLATION_B`) para evitar condiciones de carrera: el render thread puede estar leyendo el slot A mientras el processing thread escribe en el slot B.

### Paso 4: generación del frame interpolado — pureBlendFrames

```java
FrameUtils.pureBlendFrames(prevSrc, src, blend);
```

`pureBlendFrames` hace un promedio aritmético 50/50 de los dos frames en espacio RGBA:

```java
out[i]     = (byte)(((a[i]     & 0xFF) + (b[i]     & 0xFF)) >> 1);
out[i + 1] = (byte)(((a[i + 1] & 0xFF) + (b[i + 1] & 0xFF)) >> 1);
out[i + 2] = (byte)(((a[i + 2] & 0xFF) + (b[i + 2] & 0xFF)) >> 1);
out[i + 3] = (byte) 255;  // Alpha siempre 255
```

El loop está des-enrollado en bloques de 8 bytes (2 píxeles RGBA por iteración del bloque grande), con un loop de cierre para los píxeles restantes. No hay floats — todo es aritmética entera con shift derecho para la división por 2. El `& 0xFF` convierte byte con signo a int sin signo antes de sumar.

El canal Alpha no se mezcla: siempre se escribe 255. Esto es correcto porque todos los frames capturados tienen alpha opaco.

El resultado en `blend` es la imagen que representa el instante temporal entre `prevSrc` y `src`. En movimiento constante y lineal, este blend equivale a una interpolación perfecta. En movimiento no lineal o de alta velocidad, aparecerá ghosting (doble imagen difuminada).

### Paso 5: upscaling del frame blend y del frame real

```java
byte[] blendUp = upscaler.upscaleForOrdered(blend);
byte[] srcUp   = upscaler.upscaleForOrdered(src);
```

Se usa `upscaleForOrdered` en lugar de `upscale`. La diferencia: `upscaleForOrdered` forma parte del sistema de render **ordenado** (`renderToSurfaceOrdered`), que garantiza que los frames se presenten al display en el orden correcto (blend primero, real después), en lugar del latest-wins de `renderToSurface`.

Internamente `upscaleForOrdered` usa los mismos `xMap`/`yMap` pre-computados y la misma optimización de deduplicación de filas, pero devuelve un buffer reservado para el sistema de orden secuencial.

### Paso 6: emisión ordenada de los dos frames

```java
if (blendUp != null) {
    renderToSurfaceOrdered(blendUp);
    framesProcessed.incrementAndGet();
    sessionTotalFrames++;
}
if (srcUp != null) {
    renderToSurfaceOrdered(srcUp);
    framesProcessed.incrementAndGet();
    sessionTotalFrames++;
}
generated = (blendUp != null || srcUp != null);
```

Los dos frames se postean al `renderThread` **en orden**: primero el blend (frame sintético del instante t-0.5), luego el frame real (instante t). El sistema de render ordenado utiliza un `AtomicInteger lockTicket` para garantizar que aunque los Runnables se posteen en secuencia rápida, se presenten al `Canvas` en el orden correcto.

### Paso 7: fallback si no hay frame previo o el gap es demasiado grande

```java
if (!generated) {
    byte[] srcUp = upscaler.upscaleForOrdered(src);
    if (srcUp != null) {
        renderToSurfaceOrdered(srcUp);
        framesProcessed.incrementAndGet();
        sessionTotalFrames++;
    }
}
```

En el primer frame de la sesión (sin `prevSrc`), o si el gap entre frames supera 50 ms, se emite solo el frame real upscaleado sin interpolación.

### Paso 8: actualización del estado

```java
if (prevSrc != null && prevSrc.length == src.length)
    System.arraycopy(src, 0, prevSrc, 0, src.length);
bufferPool.put(KEY_LAST_FRAME_TIME, now);
```

Se actualiza `KEY_LAST_REAL_FRAME` con el frame actual (para el siguiente ciclo) y se guarda el timestamp `now` para la verificación temporal del próximo ciclo.

### Paso 9: liberación del lock

```java
releaseProcessingLockOrdered();
```

En SIFG 2, el lock de procesamiento se libera al final de forma ordenada, no en `finally` como en SIFG 1.0. Esto permite que el sistema de orden (`lockTicket`) funcione correctamente cuando hay múltiples frames en vuelo hacia el render thread.

---

## Sistema de render ordenado

SIFG 2 usa `renderToSurfaceOrdered` + `releaseProcessingLockOrdered` en lugar del latest-wins de SIFG 1.0. Esto es necesario porque emite 2 frames por ciclo: si ambos llegaran al render thread en latest-wins, el primero podría ser pisado por el segundo antes de mostrarse, perdiendo el frame de blend.

El mecanismo usa un `AtomicInteger lockTicket` como semáforo de turno. Cada frame obtiene un ticket en orden y solo puede renderizarse cuando el render thread llega a su turno. El pool de `FrameRenderRunnable[]` (8 slots) permite tener múltiples Runnables en vuelo simultáneamente sin allocaciones.

---

## Buffers involucrados

| Buffer (clave de pool) | Contenido | Tamaño |
|---|---|---|
| `KEY_SOURCE_BUFFER` | Frame actual capturado (RGBA) | `srcW × srcH × 4` |
| `KEY_LAST_REAL_FRAME` | Frame anterior (para blend) | `srcW × srcH × 4` |
| `KEY_LAST_FRAME_TIME` | Timestamp nanosegundos del frame anterior | `Long` |
| `KEY_EXTRAPOLATION` / `_B` | Ping-pong para frame blended | `srcW × srcH × 4` |
| Upscale slots ordenados | Salida del upscaler (blend y real) | `dstW × dstH × 4` |

---

## Estado interno persistente

| Campo | Tipo | Rol |
|---|---|---|
| `sifgBlendSlotA` | `boolean` | Toggle del ping-pong del buffer de blend |

No hay vector de movimiento ni EMA — este modo no requiere estado de movimiento.

---

## Ventajas y limitaciones

**Ventajas sobre SIFG 1.0:**
- Dobla el frame rate perceptual sin estimación de movimiento (menor costo CPU por frame real).
- No hay predicción fallida por vector erróneo — el blend siempre es el promedio temporal, que es visualmente neutro en el peor caso.
- No hay bordes negros por traslación.

**Limitaciones:**
- El blend a 50/50 produce ghosting (doble imagen) en objetos de alta velocidad o cambios de escena abruptos.
- La interpolación es temporal pura — no compensa el movimiento espacial (un objeto en movimiento aparece duplicado/difuminado en el frame blend, no suavemente interpolado en su trayectoria).
- El threshold de 50 ms puede causar que la generación se desactive en dispositivos lentos que no mantengan ≥ 20 FPS de captura.

---

*Basado en análisis del código fuente: `SIFgV1_1Generator`, `FrameUtils.pureBlendFrames`, `NearestNeighborUpscaler.upscaleForOrdered`, sistema de render ordenado con `lockTicket`.*
