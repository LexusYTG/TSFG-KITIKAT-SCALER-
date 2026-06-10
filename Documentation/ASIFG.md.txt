# ASIFG — Adaptive Synthetic Interpolated Frame Generator

> Modo de generación de fotogramas con campo de flujo óptico por bloques y red neuronal online.  
> Implementado en: `ASIFgGenerator` + `ASIFgGpuProcessor` + `ASIFgNetwork` dentro de `ScreenCaptureService.java` / `service/ASIFgNetwork.java`  
> Constantes de modo: `MODE_ASIFG` (CPU) / `MODE_ASIFG_GPU` (GPU vía RenderScript)

---

## Descripción general

ASIFG extiende la interpolación temporal de SIFG 2 añadiendo un **campo de flujo por bloques**: en lugar de mezclar los dos frames directamente a 50/50, primero estima el movimiento local de cada región de la imagen, y usa ese vector para desplazar los píxeles de cada frame antes de mezclarlos. El resultado es un frame sintético con motion-compensated blending: píxeles de `frameA` y `frameB` se toman de posiciones corregidas según la trayectoria local del movimiento.

Adicionalmente, ASIFG incluye una **micro red neuronal online** (`ASIFgNetwork`) que aprende a predecir el flujo en cada bloque a partir de los patches de luminancia, entrenándose frame a frame sin datos externos. La red se usa actualmente para el block-matching, no como predictor reemplazante — sirve como infraestructura para futuras mejoras.

Como SIFG 2, emite **2 frames por ciclo**: el frame sintético interpolado y el frame real.

---

## Modos de ASIFG

| Modo | Constante | Upscaling | Procesamiento |
|---|---|---|---|
| CPU | `MODE_ASIFG` | NearestNeighborUpscaler | Todo en processingThread |
| GPU | `MODE_ASIFG_GPU` | RenderScript bicúbico | Upscale en GPU, blend en CPU |

La selección ocurre en el dispatch:

```java
if (useASIFg) {
    if (asifgGpu && rsPipeline != null && rsPipeline.isReady())
        processingHandler.post(new ASIFgGpuProcessor(image));
    else
        processingHandler.post(new ASIFgGenerator(image));
}
```

Si el pipeline GPU no está disponible, `ASIFgGpuProcessor` cae automáticamente a `ASIFgGenerator`.

---

## Parámetros del grid de flujo

```java
private static final int ASIFG_GRID_COLS = 4;
private static final int ASIFG_GRID_ROWS = 4;
private static final float FLOW_EMA      = 0.20f;
```

El frame se divide en una grilla de **4×4 = 16 bloques**. Para cada bloque se estima un vector `(dx, dy)` independiente. Esta grilla es mucho más gruesa que GFaL (16×7 = 112 tiles), lo que reduce el costo CPU pero también la granularidad del campo de flujo.

---

## Ciclo por frame — ASIFgGenerator (CPU)

### Paso 1: captura

```java
byte[] src = bufferPool.get(KEY_SOURCE_BUFFER);
imageCapture.captureImageToBuffer(image, src, colorQuality);
```

Idéntico a los otros modos.

### Paso 2: verificación de ventana temporal

```java
Long lastRealTime = bufferPool.get(KEY_LAST_FRAME_TIME);
if ((now - lastRealTime) <= 60_000_000L) { // 60 ms = ~16 FPS mínimo
```

La ventana es 60 ms (vs 50 ms en SIFG 2), lo que permite activar la interpolación hasta ~16 FPS de captura. Por debajo de ese umbral, cae al fallback de solo emitir el frame real.

### Paso 3: construcción del campo de flujo — buildFlowField

`buildFlowField(lastReal, src)` recorre los 16 bloques de la grilla 4×4:

#### 3a. Cálculo del centro de cada bloque

```java
int blockW = max(1, sourceWidth  / ASIFG_GRID_COLS);
int blockH = max(1, sourceHeight / ASIFG_GRID_ROWS);
int cx = min(col * blockW + blockW / 2, sourceWidth  - 1);
int cy = min(row * blockH + blockH / 2, sourceHeight - 1);
```

El centro del bloque es el punto de muestreo. No se busca en toda la región del bloque sino únicamente en este punto central.

#### 3b. Quick-SAD para saltar bloques estáticos

Antes del block-matching completo, se hace un "quick-SAD" ligero sobre una grilla de 3×3 puntos separados a `blockW/3` y `blockH/3` del centro:

```java
for (int ky = -1; ky <= 1; ky++) {
    for (int kx = -1; kx <= 1; kx++) {
        int px = cx + kx * bW3, py = cy + ky * bH3;
        quickSAD += |frameA[px,py,G] - frameB[px,py,G]|;
    }
}
if (quickSAD < 9 * 8) {  // threshold = 72
    // bloque sin movimiento → vector (0,0), saltar block-matching
    asifgSmoothDx[bIdx] = 0; asifgSmoothDy[bIdx] = 0;
    continue;
}
```

Si la diferencia total en los 9 puntos es menor a 72 (8 por punto en promedio), el bloque se considera estático y se asigna vector `(0, 0)` sin block-matching. Ahorra el block-matching completo para regiones quietas.

#### 3c. Block-matching local — blockMatchLocal

Para bloques con movimiento detectado:

```java
blockMatchLocal(frameA, frameB, cx, cy);
```

Usa un **patch de 8×8 píxeles** (`PATCH = 8`) centrado en `(cx, cy)`, **canal G (verde) únicamente** (1 canal vs 3, ~3× más rápido). El radio de búsqueda es fijo en **4 píxeles** (`searchR = 4`).

```java
final int patchR = ASIFgNetwork.PATCH / 2;  // = 4
final int searchR = 4;
```

Procedimiento:

1. Se extrae el patch 8×8 de `frameA` en canal G y se guarda en `asifgRefPatch[2..65]` (los primeros 2 elementos son el resultado `dx, dy`).
2. Se busca exhaustivamente en `sdy ∈ [-4,+4]`, `sdx ∈ [-4,+4]` — un total de 81 candidatos.
3. SAD del patch 8×8 de G entre `frameA[cx,cy]` y `frameB[cx+sdx,cy+sdy]`.
4. Si `SAD == 0`, se rompe el loop (coincidencia perfecta).
5. El `(bestDx, bestDy)` se guarda en `asifgRefPatch[0]` y `asifgRefPatch[1]`.

El patch pre-extraído de `frameA` en `asifgRefPatch` se reutiliza para todos los candidatos de búsqueda, evitando reacceder a `frameA` en el loop interno.

#### 3d. Clamp del vector y EMA de suavizado

```java
float maxS = min(blockW, blockH) * 0.75f;
bmDx = clamp(bmDx, -maxS, +maxS);
bmDy = clamp(bmDy, -maxS, +maxS);

float sDx = bmDx * (1f - FLOW_EMA) + asifgSmoothDx[bIdx] * FLOW_EMA;
float sDy = bmDy * (1f - FLOW_EMA) + asifgSmoothDy[bIdx] * FLOW_EMA;
asifgSmoothDx[bIdx] = sDx;
asifgFlowDx[bIdx]   = sDx;
```

El vector se clampea a 75% del tamaño mínimo del bloque (protección anti-glitch para vectores grandes incoherentes). Luego se aplica EMA con α = 0.20: solo el 20% del nuevo vector pasa a la estimación suavizada, dando mucho peso al historial (`0.80`). Esto estabiliza el campo de flujo pero lo hace lento para reaccionar a cambios de dirección súbitos.

`asifgSmoothDx/Dy` son los vectores suavizados persistentes por bloque. `asifgFlowDx/Dy` son los vectores efectivos usados en la síntesis (en este modo coinciden con los suavizados).

#### 3e. Aprendizaje online de la red

```java
asifgLearnCounter++;
```

El contador de aprendizaje se incrementa cada vez que el campo de flujo se construye. La red `ASIFgNetwork` no se llama explícitamente en el path principal de `ASIFgGenerator` — el block-matching local sirve como señal de entrenamiento. La red está preparada en la infraestructura pero la arquitectura de uso activo está en `ASIFgNetwork.learnOnline`.

### Paso 4: síntesis del frame intermedio — buildSyntheticFrame

```java
byte[] synth = buildSyntheticFrame(lastReal, src);
```

Para cada píxel `(x, y)` del frame sintético:

#### 4a. Interpolación bilineal del campo de flujo

Se calculan el índice de fila y columna del bloque (y sus vecinos) para el píxel `(x, y)`, y se interpola bilinealmente el vector de flujo entre los 4 bloques vecinos:

```java
int r0 = rowIdx[y], r1 = r0 + 1;
float ty = rowW[y],  ity = 1f - ty;
int c0 = colIdx[x],  c1 = c0 + 1;
float tx = colW[x],  itx = 1f - tx;

float i00 = itx * ity, i10 = tx * ity, i01 = itx * ty, i11 = tx * ty;
float fdx = asifgFlowDx[b0+c0]*i00 + asifgFlowDx[b0+c1]*i10
          + asifgFlowDx[b1+c0]*i01 + asifgFlowDx[b1+c1]*i11;
float fdy = ...
```

`rowIdx`, `rowW`, `colIdx`, `colW` son tablas pre-computadas en el `BufferPool` para evitar divisiones y cálculos de índice en el hot loop. `rowIdx[y]` da el índice del bloque-fila inferior para el píxel `y`, y `rowW[y]` es el peso fraccional dentro de ese par de bloques.

La interpolación usa pesos **lineales** (no smoothstep como en GFaL) — diferencia de implementación entre los dos modelos.

#### 4b. Motion-compensated blend

Si el vector interpolado es casi nulo (`|fdx| < 0.5 && |fdy| < 0.5`):

```java
synth[di]   = (byte)(((frameA[di]   & 0xFF) + (frameB[di]   & 0xFF)) >> 1);
// ... misma posición para A y B — blend directo
```

Si hay movimiento significativo:

```java
float hx = fdx * 0.5f, hy = fdy * 0.5f;
// posición en frameA: retroceder medio vector
int ax = round(x - hx), ay = round(y - hy);
// posición en frameB: avanzar medio vector
int bx = round(x + hx), by = round(y + hy);
synth[di]   = (byte)(((frameA[aBase] & 0xFF) + (frameB[bBase] & 0xFF)) >> 1);
```

El frame sintético en `(x, y)` es la mezcla de:
- `frameA` en `(x - dx/2, y - dy/2)` — el píxel de A que "viene hacia aquí"
- `frameB` en `(x + dx/2, y + dy/2)` — el píxel de B que "va desde aquí"

Esto es motion-compensated interpolation: en lugar de mezclar los mismos píxeles en `(x,y)` de ambos frames (que produce ghosting en movimiento), se busca en la trayectoria del movimiento. En movimiento constante y lineal, esto produce interpolación perfecta sin ghosting.

Si `(ax, ay)` o `(bx, by)` caen fuera de los límites del frame, se clampean al borde.

### Paso 5: upscaling y emisión ordenada

```java
byte[] synthUp = upscaler.upscaleForOrdered(synth);
byte[] srcUp   = upscaler.upscaleForOrdered(src);
if (synthUp != null) { renderToSurfaceOrdered(synthUp); framesProcessed++; }
if (srcUp   != null) { renderToSurfaceOrdered(srcUp);   framesProcessed++; }
```

Idéntico a SIFG 2: 2 frames emitidos por ciclo, en orden (sintético primero, real después), usando el sistema `renderToSurfaceOrdered` + `lockTicket`.

### Paso 6: actualización del estado

```java
System.arraycopy(src, 0, lastReal, 0, src.length);
bufferPool.put(KEY_LAST_FRAME_TIME, now);
```

---

## ASIFgGpuProcessor — variante GPU

`ASIFgGpuProcessor` hace el mismo ciclo que `ASIFgGenerator` con una diferencia fundamental: el **upscaling se hace en GPU vía RenderScript** en lugar de `NearestNeighborUpscaler`.

```java
rsPipeline.uploadSource(src);
rsPipeline.resize();  // ScriptIntrinsicResize bicúbico en GPU
```

El pipeline GPU usa `ScriptIntrinsicResize.forEach_bicubic(allocTarget)` — upscaling bicúbico, significativamente mejor calidad que nearest-neighbor. Ver el documento de aceleración GPU para los detalles del pipeline RenderScript.

En la variante GPU, el blend no usa `buildSyntheticFrame` + flujo óptico — en su lugar hace el mismo `pureBlendFrames` 50/50 de SIFG 2, pero sobre los frames **ya upscaleados** (en resolución target):

```java
byte[] prevReal = rsPipeline.getPreviousBytes();
byte[] real2    = rsPipeline.downloadTargetPing();
byte[] blendBuf = rsPipeline.getBlendBuffer();
FrameUtils.pureBlendFrames(prevReal, real2, blendBuf);
```

Esto significa que `ASIFgGpuProcessor` **no usa el campo de flujo ASIFg** — aplica blend directo sobre los frames upscaleados en target resolution. Es efectivamente SIFG 2 con upscaling bicúbico GPU en lugar de nearest-neighbor CPU. El nombre es algo engañoso; el flujo adaptivo solo se usa en el modo CPU.

---

## ASIFgNetwork — arquitectura de la red neuronal

`ASIFgNetwork` es una red feed-forward de 3 capas implementada puramente en Java, sin ninguna librería de ML.

### Dimensiones

```java
INPUT = PATCH * PATCH * 2  = 8 × 8 × 2 = 128 entradas
H1    = 16
H2    =  8
OUT   =  2  // dx, dy
```

La entrada es la concatenación de dos patches 8×8 de luminancia (uno de `frameA`, uno de `frameB`), cada píxel normalizado a `[−0.5, +0.5]`.

### Pesos totales

```
(128×16 + 16) + (16×8 + 8) + (8×2 + 2) = 2064 + 136 + 18 = 2218 parámetros
```

### Inicialización — Kaiming con Box-Muller

Los pesos se inicializan con distribución normal usando inicialización Kaiming (He):

```java
std1 = sqrt(2.0 / ((1 + LEAKY²) * INPUT))  // para capa 1 con Leaky ReLU
std2 = sqrt(2.0 / ((1 + LEAKY²) * H1))
std3 = sqrt(1.0 / H2)                       // capa de salida, sin activación
```

La muestra normal se genera mediante la transformación Box-Muller sobre dos uniformes generadas por LCG (generador lineal congruencial con seed `0xCAFEBABE1234`). Todo es determinístico y reproducible. No hay `java.util.Random`.

### Forward pass

```java
// Capa 1: INPUT → H1, Leaky ReLU
h1a[j] = Σ(w1[j*INPUT + i] * inp[i]) + b1[j]
h1a[j] = h1a[j] > 0 ? h1a[j] : 0.1 * h1a[j]  // Leaky ReLU α=0.1

// Capa 2: H1 → H2, Leaky ReLU
h2a[j] = Σ(w2[j*H1 + i] * h1a[i]) + b2[j]
h2a[j] = h2a[j] > 0 ? h2a[j] : 0.1 * h2a[j]

// Capa salida: H2 → OUT, lineal
outa[j] = Σ(w3[j*H2 + i] * h2a[i]) + b3[j]
```

Todos los buffers de activación (`inp`, `h1a`, `h2a`, `outa`) están pre-alojados como campos del objeto — sin allocaciones en el hot path.

### Extracción de patch — extractPatch

```java
lum = (0.299 * R + 0.587 * G + 0.114 * B) / 255 - 0.5
```

Conversión a luminancia con coeficientes BT.601, normalizada a `[-0.5, +0.5]`.

### Aprendizaje online — learnOnline

El entrenamiento se hace mediante **SGD con Momentum**, truncado a las últimas 2 capas (capas 2 y salida). La capa 1 no se actualiza — esto es backprop parcial para reducir el costo de entrenamiento.

```java
float eDx = clip(outa[0] - labelDx, CLIP);  // error en dx, clamp ±2.0
float eDy = clip(outa[1] - labelDy, CLIP);

// Momentum: v = β·v - LR·g;  w += v
for (int i = 0; i < H2; i++) {
    float v = MOMENTUM * vw3[i] - LR * eDx * h2a[i];
    vw3[i] = v;  w3[i] += v;
}
```

- **LR = 0.001** — tasa de aprendizaje
- **MOMENTUM = 0.90** — β del momentum
- **CLIP = 2.0** — gradient clipping simétrico

El label de entrenamiento proviene del block-matching: el `bestDx`, `bestDy` encontrado por búsqueda exhaustiva sirve como "ground truth" para que la red aprenda a predecir el flujo sin búsqueda.

La actualización del momentum para la capa de salida está desenrollada manualmente por los 2 nodos de salida (`j=0` para eDx, `j=1` para eDy) para eliminar el overhead del `for j in [0,1]`.

---

## Buffers involucrados

| Buffer (clave de pool) | Contenido | Tamaño |
|---|---|---|
| `KEY_SOURCE_BUFFER` | Frame actual (RGBA) | `srcW × srcH × 4` |
| `KEY_LAST_REAL_FRAME` | Frame anterior (para campo de flujo y blend) | `srcW × srcH × 4` |
| `KEY_LAST_FRAME_TIME` | Timestamp nanosegundos | `Long` |
| `KEY_ASIFG_SYNTH` | Frame sintético interpolado con flujo | `srcW × srcH × 4` |
| `bufferPool.asifgColIdx/W` | Tablas pre-computadas de columnas para interpolación | `srcW` entradas |
| `bufferPool.asifgRowIdx/W` | Tablas pre-computadas de filas para interpolación | `srcH` entradas |

---

## Estado interno persistente

| Campo | Tipo | Rol |
|---|---|---|
| `asifgFlowDx/Dy[16]` | `float[]` | Vectores de flujo efectivos por bloque (4×4) |
| `asifgSmoothDx/Dy[16]` | `float[]` | Vectores EMA suavizados por bloque |
| `asifgRefPatch[66]` | `int[]` | Patch de referencia A + resultado (dx,dy) |
| `asifgLearnCounter` | `int` | Contador de ciclos de aprendizaje de la red |
| `asifgNet` | `ASIFgNetwork` | Instancia de la red neuronal (lazy init) |
| `gpuBlendSlotA` | `boolean` | Ping-pong del buffer de blend GPU |

---

## Diferencias con los otros modos

| Aspecto | SIFG 1.0 | SIFG 2 | ASIFG CPU | ASIFG GPU |
|---|---|---|---|---|
| Frames emitidos | 1 (predicho) | 2 (blend + real) | 2 (sintetizado + real) | 2 (blend + real) |
| Tipo de síntesis | Traslación global | Blend directo 50/50 | Motion-compensated blend | Blend directo 50/50 |
| Campo de flujo | 1 vector global | No | 4×4 bloques, EMA 0.20 | No (usa GPU blend) |
| Upscaling | Nearest-neighbor CPU | Nearest-neighbor CPU | Nearest-neighbor CPU | Bicúbico GPU |
| Red neuronal | No | No | Presente (ASIFgNetwork) | Presente pero sin uso activo |
| Ventana temporal | N/A | 50 ms | 60 ms | 60 ms |

---

*Basado en análisis del código fuente: `ASIFgGenerator`, `ASIFgGpuProcessor`, `ASIFgNetwork`, `buildFlowField`, `blockMatchLocal`, `buildSyntheticFrame`, `BufferPool`.*
