# Aceleración por GPU — RenderScript Pipeline

> Pipeline de upscaling bicúbico por GPU usando Android RenderScript.  
> Implementado en: `RenderScriptPipeline.java`, usado por `GpuFrameProcessor` (MODE_GPU) y `ASIFgGpuProcessor` (MODE_ASIFG_GPU).

---

## Descripción general

La aceleración por GPU en este proyecto se implementa a través de **Android RenderScript** (`android.renderscript`), la API de cómputo paralelo de Android para operaciones sobre arrays de datos. No se usa OpenGL, Vulkan, OpenCL ni ninguna otra API gráfica. RenderScript expone operaciones intrínsecas pre-compiladas que se ejecutan en el GPU del dispositivo — en este caso, `ScriptIntrinsicResize` para upscaling bicúbico.

El propósito exclusivo del pipeline GPU es el **upscaling de resolución**: escalar el frame desde la resolución de captura (por ejemplo, 540×960) a la resolución de display (por ejemplo, 1080×1920) usando interpolación bicúbica en lugar del nearest-neighbor que se usa en CPU. El blend de frames y la estimación de movimiento siempre se hacen en CPU, independientemente del modo GPU.

---

## Arquitectura del pipeline

```
CPU (processingThread)
    │
    ├── captureImageToBuffer(image, src[])       // RGBA del ImageReader → byte[] en CPU
    │
    ├── rsPipeline.uploadSource(src[])           // byte[] → Allocation GPU (allocSrc)
    │
    ├── rsPipeline.resize()                      // ScriptIntrinsicResize bicúbico en GPU
    │                                            // allocSrc (srcW×srcH) → allocTarget (dstW×dstH)
    │
    ├── rsPipeline.downloadTargetPing()          // Allocation GPU → byte[] en CPU (ping-pong)
    │
    └── renderToSurfaceOrdered(real[])           // byte[] → renderThread → Canvas
```

Todo el procesamiento de flujo óptico, blend y render ocurre en CPU. El GPU solo interviene en la operación `resize()`.

---

## Clase RenderScriptPipeline

`RenderScriptPipeline` encapsula todo el ciclo de vida RenderScript: inicialización, operaciones y destrucción. Es thread-safe para `init` y `destroy` (método `synchronized`), aunque las operaciones de hot path (`uploadSource`, `resize`, `downloadTargetPing`) no están sincronizadas para evitar overhead — se asume que solo `processingThread` las llama.

### Campos internos

```java
private RenderScript          rs;           // contexto RenderScript
private ScriptIntrinsicResize rsResize;     // script de resize bicúbico
private Allocation            allocSrc;     // buffer GPU fuente (srcW × srcH × RGBA_8888)
private Allocation            allocTarget;  // buffer GPU destino (dstW × dstH × RGBA_8888)
private byte[]                targetPingA;  // CPU — slot A del ping-pong de destino
private byte[]                targetPingB;  // CPU — slot B
private boolean               pingSlotA;    // toggle del ping-pong
private byte[]                prevBytes;    // copia del frame real anterior (para blend)
private byte[]                blendBuffer;  // buffer de trabajo para blend 50/50
private boolean               ready;        // pipeline inicializado y operativo
```

---

## Inicialización — init(sw, sh, tw, th)

```java
public synchronized void init(int sw, int sh, int tw, int th) {
    destroy();  // destruir pipeline previo si existe
    rs = RenderScript.create(ctx);

    Type typeSrc = new Type.Builder(rs, Element.RGBA_8888(rs))
        .setX(sw).setY(sh).create();
    Type typeTgt = new Type.Builder(rs, Element.RGBA_8888(rs))
        .setX(tw).setY(th).create();

    allocSrc    = Allocation.createTyped(rs, typeSrc, Allocation.USAGE_SCRIPT);
    allocTarget = Allocation.createTyped(rs, typeTgt, Allocation.USAGE_SCRIPT);

    rsResize = ScriptIntrinsicResize.create(rs);
    rsResize.setInput(allocSrc);

    int tSize   = tw * th * 4;
    targetPingA = new byte[tSize];
    targetPingB = new byte[tSize];
    blendBuffer = new byte[tSize];
    ready = true;
}
```

**`RenderScript.create(ctx)`**: crea el contexto RS. En Android, esto instancia un contexto de cómputo que puede ejecutarse en GPU, DSP o CPU según la disponibilidad y las políticas del driver. En la mayoría de dispositivos Android modernos, `ScriptIntrinsicResize` se ejecuta en GPU.

**`Element.RGBA_8888(rs)`**: especifica que cada elemento del `Allocation` es un píxel de 4 bytes en formato RGBA. Coincide exactamente con el formato del `ImageReader` y del `byte[]` que usa el resto del pipeline.

**`Allocation.USAGE_SCRIPT`**: el `Allocation` se usa exclusivamente para cómputo RS (no para render directo a pantalla, que requería `USAGE_IO_OUTPUT`). Esto permite leer y escribir desde el script.

**`rsResize.setInput(allocSrc)`**: vincula la entrada del script al `allocSrc`. Se hace una sola vez aquí; el `allocSrc` se reutiliza frame a frame actualizando su contenido con `copyFrom`.

**`targetPingA/B`**: dos buffers CPU del tamaño del frame destino. Se usan para descargar el resultado de `allocTarget` sin bloquear — mientras el processing thread lee `pingA`, el siguiente frame puede escribir en `pingB` y viceversa.

**`blendBuffer`**: buffer de trabajo para `pureBlendFrames` en resolución target. Se aloja aquí para no alocar en el hot path.

Si `RenderScript.create` o cualquier operación de inicialización falla (dispositivo sin soporte, OOM), se llama `destroy()` internamente y `ready` queda `false`. El caller verifica `isReady()` antes de usar el pipeline y cae al path CPU.

---

## Carga del frame fuente — uploadSource(src[])

```java
public void uploadSource(byte[] src) {
    if (!ready) return;
    allocSrc.copyFrom(src);
}
```

**`allocSrc.copyFrom(src)`**: transfiere el `byte[]` RGBA de CPU a la memoria de GPU (o memoria compartida CPU-GPU según el dispositivo). En hardware con memoria unificada (la mayoría de SoCs móviles), esta operación puede ser una copia de TLB más que un DMA real, haciendo que sea O(1) o muy rápida.

El `src` es el buffer capturado de `ImageReader` a través de `imageCapture.captureImageToBuffer` — contiene `srcW × srcH × 4` bytes en formato RGBA interleaved.

---

## Resize en GPU — resize()

```java
public void resize() {
    if (!ready) return;
    rsResize.forEach_bicubic(allocTarget);
}
```

**`rsResize.forEach_bicubic(allocTarget)`**: ejecuta el kernel de upscaling bicúbico sobre todos los píxeles del `allocTarget`. RenderScript dispara automáticamente el número correcto de threads en el GPU para procesar todos los `tw × th` píxeles de destino en paralelo.

### Interpolación bicúbica

El upscaling bicúbico de `ScriptIntrinsicResize` implementa el filtro de Catmull-Rom (o variante similar), que para cada píxel destino `(x, y)` en `allocTarget`:

1. Calcula la posición correspondiente en `allocSrc`: `(x × srcW / dstW, y × srcH / dstH)`.
2. Toma una vecindad de 4×4 píxeles del fuente alrededor de esa posición.
3. Aplica la función de peso bicúbico a los 16 vecinos.
4. Escribe el píxel interpolado en `allocTarget`.

Comparado con nearest-neighbor (que simplemente copia el píxel más cercano), el bicúbico produce bordes más suaves y elimina el efecto de pixelado en upscalings grandes (e.g. 2×). El costo en CPU sería prohibitivo (16 muestras × operaciones de punto flotante por píxel × millones de píxeles), pero en GPU el paralelismo masivo hace que sea comparable en tiempo de pared a un nearest-neighbor en CPU.

### Paralelismo

RenderScript no expone directamente el número de threads del GPU — es manejado internamente por el driver. En un GPU móvil típico (Adreno, Mali, PowerVR), `forEach_bicubic` puede ejecutar miles de píxeles en paralelo, limitado por el número de ALUs del GPU y el ancho de banda de memoria.

El pipeline no tiene control sobre si `forEach_bicubic` es síncrono o asíncrono respecto al CPU — en la API de RenderScript, las operaciones `forEach_*` se encolan y pueden completarse antes de que retorne la llamada Java, o pueden ejecutarse de forma pipelined con la siguiente llamada a `copyTo`. En la práctica, `copyTo` en el paso siguiente actúa como sincronización implícita.

---

## Descarga del resultado — downloadTargetPing()

```java
public byte[] downloadTargetPing() {
    if (!ready) return null;
    byte[] slot = pingSlotA ? targetPingA : targetPingB;
    pingSlotA = !pingSlotA;
    allocTarget.copyTo(slot);
    return slot;
}
```

**`allocTarget.copyTo(slot)`**: transfiere el resultado del GPU a CPU. Esta es una operación sincronizante — garantiza que el `forEach_bicubic` del paso anterior completó antes de copiar los datos. Es el punto donde el CPU espera al GPU si el GPU todavía no terminó.

El ping-pong entre `targetPingA` y `targetPingB` sirve para que el render thread pueda leer del slot anterior mientras el processing thread descarga en el slot nuevo. Sin ping-pong, el render thread podría leer datos parcialmente sobreescritos.

El `byte[]` devuelto **no se copia** — se devuelve la referencia directa al slot activo. El caller (processing thread) usa este buffer directamente para blend o para enviarlo al render thread.

---

## Buffer de frame anterior — savePreviousBytes / getPreviousBytes

```java
public void savePreviousBytes(byte[] real) {
    if (prevBytes == null || prevBytes.length != real.length)
        prevBytes = new byte[real.length];
    System.arraycopy(real, 0, prevBytes, 0, real.length);
}
public byte[] getPreviousBytes() { return prevBytes; }
```

`prevBytes` almacena una copia del último frame real descargado del GPU. Se usa en el siguiente ciclo para hacer el blend 50/50 entre el frame anterior y el actual. Esta copia es necesaria porque `downloadTargetPing` reutiliza los slots ping-pong y sobreescribe el frame del ciclo anterior.

`savePreviousBytes` se llama explícitamente por el processing thread después de usar el frame descargado (en `GpuFrameProcessor` y `ASIFgGpuProcessor`).

---

## Modo GPU puro — GpuFrameProcessor (MODE_GPU)

`GpuFrameProcessor` combina el upscaling GPU con el blend CPU de SIFG 2:

```java
rsPipeline.uploadSource(src);
rsPipeline.resize();
byte[] real = rsPipeline.downloadTargetPing();  // frame upscaleado en GPU

byte[] prevReal = rsPipeline.getPreviousBytes();
if (prevReal != null) {
    byte[] blendBuf = rsPipeline.getBlendBuffer();
    FrameUtils.pureBlendFrames(prevReal, real, blendBuf);  // blend 50/50 en CPU
    // copiar blendBuf a un slot fijo y renderizar en orden
    ...
    renderToSurfaceOrdered(bc);          // frame blend (sintético)
}
rsPipeline.savePreviousBytes(real);
renderToSurfaceOrdered(real);            // frame real upscaleado
```

El blend se hace sobre frames en **resolución target** (ya upscaleados), a diferencia de SIFG 2 que hace el blend en resolución fuente y luego upscalea. El resultado es el mismo conceptualmente, pero la operación `pureBlendFrames` trabaja con buffers más grandes (`dstW × dstH × 4` en lugar de `srcW × srcH × 4`), lo que tiene un costo CPU mayor.

Ventaja: el frame sintético también está upscaleado con calidad bicúbica.

---

## Modo ASIFg GPU — ASIFgGpuProcessor (MODE_ASIFG_GPU)

`ASIFgGpuProcessor` hace el mismo pipeline que `GpuFrameProcessor` (blend 50/50 en CPU sobre frames upscaleados en GPU), pero también mantiene `asifgLearnCounter` y el campo de flujo (aunque no lo usa para la síntesis en este modo — la síntesis es blend directo, no motion-compensated):

```java
rsPipeline.uploadSource(src); rsPipeline.resize();
if (lastReal != null && (now - lrt) <= 60_000_000L) {
    asifgLearnCounter++;
    byte[] prevReal = rsPipeline.getPreviousBytes();
    byte[] real2    = rsPipeline.downloadTargetPing();
    byte[] blendBuf = rsPipeline.getBlendBuffer();
    FrameUtils.pureBlendFrames(prevReal, real2, blendBuf);
    // ... render blend y real en orden
    rsPipeline.savePreviousBytes(real2);
}
```

La diferencia con `GpuFrameProcessor` es únicamente el incremento de `asifgLearnCounter` — el resto del comportamiento es idéntico. El campo de flujo `asifgFlowDx/Dy` no se actualiza ni se usa en el modo GPU.

---

## Destrucción — destroy()

```java
public synchronized void destroy() {
    ready = false;
    prevBytes   = null;
    targetPingA = null; targetPingB = null;
    safeDestroy(rsResize);    rsResize    = null;
    safeDestroy(allocSrc);    allocSrc    = null;
    safeDestroy(allocTarget); allocTarget = null;
    if (rs != null) { rs.destroy(); rs = null; }
    blendBuffer = null;
}
```

El orden de destrucción es crítico: primero los scripts y allocations que dependen del contexto RS, después el contexto en sí. Destruir `rs` antes que `allocSrc` o `rsResize` causaría errores de acceso.

`safeDestroy` envuelve la llamada en try-catch para ignorar excepciones durante la destrucción (common pattern en RenderScript — los objetos pueden estar en estados inválidos si el contexto ya tuvo un error previo).

`destroy` se llama en varios momentos:
- Al inicio de `init` (para limpiar un pipeline previo si se cambia de resolución)
- Al cambiar del modo GPU a CPU (`setMode` con un modo sin GPU)
- En `onDestroy` del servicio
- Cuando `init` falla (auto-limpieza)

---

## Configuración del RenderScript en el servicio

```java
// En setMode():
if (this.useGpu) {
    if (rsPipeline == null) rsPipeline = new RenderScriptPipeline(this);
    if (running) rsPipeline.init(sourceWidth, sourceHeight, targetWidth, targetHeight);
} else {
    if (rsPipeline != null) { rsPipeline.destroy(); rsPipeline = null; }
}
```

El `RenderScriptPipeline` se crea lazy al activar un modo GPU. Se inicializa con las dimensiones actuales de captura y display. Si el servicio no está corriendo cuando se activa el GPU, `init` se postergará hasta que la captura inicie.

Al desactivar el GPU (cambio a modo CPU), el pipeline se destruye inmediatamente y `rsPipeline` se pone a `null`. Esto libera la memoria del GPU y el contexto RS.

---

## Compatibilidad y fallback

**RenderScript fue deprecado en Android 12 (API 31)** a favor de Vulkan Compute / NNAPI / otras alternativas. Está disponible en API 11+ y sigue funcionando en dispositivos Android 12+ a través de la capa de compatibilidad, pero no recibirá nuevas funcionalidades.

Para asegurar compatibilidad hacia atrás, `RenderScriptPipeline` envuelve toda la inicialización en try-catch. Si `RenderScript.create` falla (dispositivo sin soporte, driver inestable, OOM), `ready` queda `false` y el processing thread cae automáticamente al path CPU:

```java
// En ASIFgGpuProcessor.run():
if (rsPipeline == null || !rsPipeline.isReady()) {
    new ASIFgGenerator(image).run();  // fallback CPU
    return;
}
```

```java
// En GpuFrameProcessor.run():
if (!running || rsPipeline == null || !rsPipeline.isReady()) {
    // captura, upscale CPU, render
    ...
    return;
}
```

El fallback es transparente para el usuario — el overlay continúa funcionando, solo con menor calidad de upscaling.

---

## Resumen del impacto del GPU en el pipeline

| Operación | Sin GPU | Con GPU |
|---|---|---|
| Upscaling | Nearest-neighbor en CPU | Bicúbico en GPU (ScriptIntrinsicResize) |
| Calidad de imagen | Pixelado en objetos pequeños | Bordes suaves, menor aliasing |
| Blend entre frames | 50/50 CPU (srcW×srcH) | 50/50 CPU (dstW×dstH, buffers más grandes) |
| Estimación de movimiento | Igual (CPU, por bloque) | No usada en modo GPU |
| Síntesis del frame | Motion-compensated (ASIFG CPU) o blend directo | Blend directo en ambos modos GPU |
| Costo CPU | Mayor (upscale en CPU) | Menor (upscale en GPU) |
| Costo GPU | Mínimo | Upscaling por frame (bicúbico) |
| Latencia extra | No | `allocSrc.copyFrom` + `allocTarget.copyTo` por frame |

El beneficio neto del modo GPU es la **mejora visual del upscaling** (bicúbico vs nearest-neighbor) y la **reducción de carga CPU** para el upscaling, a costa de latencia de transferencia CPU↔GPU y mayor complejidad de ciclo de vida. En dispositivos con GPU capaz y upscaling agresivo (e.g. 25% → 100%), la diferencia visual puede ser significativa.

---

*Basado en análisis del código fuente: `RenderScriptPipeline.java`, `GpuFrameProcessor`, `ASIFgGpuProcessor`, `AppConstants` (MODE_GPU, MODE_ASIFG_GPU), `ScreenCaptureService.setMode`.*
