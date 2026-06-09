package com.TuringSoftware.FrameGenerator;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.TuringSoftware.FrameGenerator.service.ASIFgNetwork;
import com.TuringSoftware.FrameGenerator.service.buffers.BufferPool;
import com.TuringSoftware.FrameGenerator.service.buffers.ImageCapture;
import com.TuringSoftware.FrameGenerator.service.buffers.NearestNeighborUpscaler;
import com.TuringSoftware.FrameGenerator.service.notification.CaptureNotification;
import com.TuringSoftware.FrameGenerator.service.overlay.OverlayWindow;
import com.TuringSoftware.FrameGenerator.service.pipeline.RenderScriptPipeline;
import com.TuringSoftware.FrameGenerator.service.processors.FrameUtils;
import com.TuringSoftware.FrameGenerator.service.processors.GFaLFlowNet;
import com.TuringSoftware.FrameGenerator.service.processors.StaticFrameDetector;
import com.TuringSoftware.FrameGenerator.service.processors.TileFreezeDetector;
import com.TuringSoftware.FrameGenerator.service.render.SurfaceRenderer;

import static com.TuringSoftware.FrameGenerator.AppConstants.*;
import static com.TuringSoftware.FrameGenerator.service.notification.CaptureNotification.*;

/**
 * Servicio en primer plano que orquesta el pipeline de captura de pantalla.
 *
 * <p><b>Refactorización:</b> la lógica fue dividida en módulos específicos:
 * <ul>
 *   <li>{@link BufferPool}           — gestión de buffers y Bitmaps.</li>
 *   <li>{@link ImageCapture}         — copia RGBA de ImageReader a byte[].</li>
 *   <li>{@link NearestNeighborUpscaler} — upscaling nearest-neighbor con ping-pong.</li>
 *   <li>{@link SurfaceRenderer}      — dibuja frames sobre el overlay.</li>
 *   <li>{@link OverlayWindow}        — crea/destruye la ventana flotante.</li>
 *   <li>{@link CaptureNotification}  — notificación persistente del servicio.</li>
 *   <li>{@link RenderScriptPipeline} — pipeline GPU vía RenderScript.</li>
 *   <li>{@link StaticFrameDetector}  — detección de frame estático por hash.</li>
 *   <li>{@link FrameUtils}           — blend, traslación, estimación de movimiento.</li>
 *   <li>{@link ASIFgNetwork}         — red neuronal liviana para flujo óptico.</li>
 * </ul>
 *
 * <p><b>Arquitectura de hilos:</b>
 * <pre>
 *   captureThread    → onImageAvailable  (adquisición ~60 FPS)
 *   processingThread → Generator/Processor (procesamiento pesado)
 *   renderThread     → lockCanvas/drawBitmap (render dedicado)
 *   mainHandler      → cambios de UI/overlay
 * </pre>
 *
 * <p>Java 7 — sin lambdas.
 */
public class ScreenCaptureService extends Service {

    // ── Constantes ────────────────────────────────────────────────────────────
    private static final int ASIFG_GRID_COLS = 4;
    private static final int ASIFG_GRID_ROWS = 4;
    private static final float FLOW_EMA      = 0.20f;

    // ── Hilos y handlers ──────────────────────────────────────────────────────
    private HandlerThread processingThread, captureThread, renderThread;
    private Handler       processingHandler, captureHandler, renderHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Módulos delegados ─────────────────────────────────────────────────────
    private BufferPool             bufferPool;
    private ImageCapture           imageCapture;
    private NearestNeighborUpscaler upscaler;
    private SurfaceRenderer        surfaceRenderer;
    private OverlayWindow          overlayWindow;
    private CaptureNotification    notification;
    private RenderScriptPipeline   rsPipeline;
    private StaticFrameDetector    staticDetector;
    private ASIFgNetwork           asifgNet;

    // ── MediaProjection / captura ─────────────────────────────────────────────
    private MediaProjection                   projection;
    private android.hardware.display.VirtualDisplay virtualDisplay;
    private ImageReader                       imageReader;
    private volatile boolean processingBusy = false;
    private volatile boolean running        = false;

    // ── Configuración (volatile = thread-safe para primitivos) ────────────────
    private volatile int   currentMode       = MODE_PERFORMANCE;
    private volatile int   sifgVersion       = 0;
    private volatile float resolutionScale   = DEFAULT_RESOLUTION_SCALE;
    private volatile int   framesToGenerate  = DEFAULT_FRAMES_TO_GENERATE;
    private volatile int   colorQuality      = DEFAULT_COLOR_QUALITY;
    private volatile int   fisheyeCorrection = DEFAULT_FISHEYE_CORRECTION;
    private volatile boolean useSIFg         = false;
    private volatile boolean useASIFg        = false;
    private volatile boolean useGpu          = false;
    private volatile boolean asifgGpu        = false;
    private volatile boolean useGfal         = false;
    private volatile int     gfalSearchRadius = DEFAULT_GFAL_SEARCH_RADIUS;
    private volatile int     gfalLinesLargo   = DEFAULT_GFAL_LINES_LARGO;
    private volatile int     gfalLinesAncho   = DEFAULT_GFAL_LINES_ANCHO;
    private volatile int     gfalGridW        = DEFAULT_GFAL_GRID_W;
    private volatile int     gfalGridH        = DEFAULT_GFAL_GRID_H;
    private volatile boolean touchForwardEnabled = false;
    private volatile int    captureMode       = CAPTURE_MODE_FULLSCREEN;
    private volatile long   targetFrameNanos  = 16_666_667L;

    // ── Dimensiones ───────────────────────────────────────────────────────────
    private int sourceWidth, sourceHeight, targetWidth, targetHeight;
    private int topInset, bottomInset;

    // ── Estadísticas atómicas ─────────────────────────────────────────────────
    private final AtomicInteger framesProcessed  = new AtomicInteger(0);
    private final AtomicLong    lastFpsUpdate     = new AtomicLong(0);
    private final AtomicInteger droppedFrames     = new AtomicInteger(0);
    private final AtomicInteger currentFps        = new AtomicInteger(0);
    private final AtomicInteger captureErrorCount = new AtomicInteger(0);

    // ── Sesión ────────────────────────────────────────────────────────────────
    private long sessionStartTime, sessionTotalFrames;
    private SharedPreferences prefs;
    private long nextFrameDeadlineNs = 0;

    // ── Campos EMA / SIFg ─────────────────────────────────────────────────────
    private float sifgPrevDx = 0f, sifgPrevDy = 0f;
    private int   sifgV10Dx  = 0,  sifgV10Dy  = 0;
    private int   asifgLearnCounter = 0;
    private boolean sifgBlendSlotA  = true;
    private boolean copySlotA       = true;
    private boolean gpuBlendSlotA   = true;

    // ── Campos de flujo ASIFg ─────────────────────────────────────────────────
    private final float[] asifgFlowDx   = new float[ASIFG_GRID_COLS * ASIFG_GRID_ROWS];
    private final float[] asifgFlowDy   = new float[ASIFG_GRID_COLS * ASIFG_GRID_ROWS];
    private final float[] asifgSmoothDx = new float[ASIFG_GRID_COLS * ASIFG_GRID_ROWS];
    private final float[] asifgSmoothDy = new float[ASIFG_GRID_COLS * ASIFG_GRID_ROWS];
    private final int[]   asifgRefPatch = new int[2 + ASIFgNetwork.PATCH * ASIFgNetwork.PATCH];

    // ── Campos de flujo GFaL ──────────────────────────────────────────────────
    private static final int GFAL_TILE_COUNT = FrameUtils.GFAL_COLS * FrameUtils.GFAL_ROWS;
    private final float[] gfalFlowDx = new float[GFAL_TILE_COUNT];
    private final float[] gfalFlowDy = new float[GFAL_TILE_COUNT];
    private boolean gfalSlotA = true;

    // ── GFaL — Tile Freeze Detector ──────────────────────────────────────────
    /** Congela tiles cuyo contenido no ha cambiado en los últimos N frames. */
    private final TileFreezeDetector gfalFreezeDetector =
    new TileFreezeDetector(GFAL_TILE_COUNT);

    // ── GFaL refinamiento neuronal ────────────────────────────────────────────
    private volatile boolean gfalNetEnabled = DEFAULT_GFAL_NET_ENABLED;
    private volatile float   gfalNetAlpha   = DEFAULT_GFAL_NET_ALPHA / 100f;
    /** Scratch arrays para GFaLFlowNet — sin allocaciones en hot path. */
    private final float[] gfalNetHidden = new float[GFaLFlowNet.H];
    private final float[] gfalNetDelta  = new float[GFaLFlowNet.OUT];

    // ── Render pool ───────────────────────────────────────────────────────────
    private static final int RENDER_POOL_SIZE = 8;
    private final FrameRenderRunnable[] renderPool = new FrameRenderRunnable[RENDER_POOL_SIZE];
    private int renderPoolHead = 0;

    private volatile byte[] pendingRenderFrame  = null;
    private volatile Bitmap pendingRenderBitmap = null;
    private boolean renderSrcSlotA = true;

    private final java.util.concurrent.atomic.AtomicInteger lockTicket =
    new java.util.concurrent.atomic.AtomicInteger(0);

    // ── Binder ────────────────────────────────────────────────────────────────
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public ScreenCaptureService getService() { return ScreenCaptureService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    // ── Receptor STOP ─────────────────────────────────────────────────────────
    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_STOP.equals(intent.getAction())) stopCapture();
        }
    };

    // ── Runnables de render latest-wins ──────────────────────────────────────

    private final Runnable renderPendingRunnable = new Runnable() {
        @Override public void run() {
            byte[] frame = pendingRenderFrame;
            if (frame != null && surfaceRenderer != null) surfaceRenderer.renderBytes(frame);
        }
    };

    private final Runnable renderBitmapRunnable = new Runnable() {
        @Override public void run() {
            Bitmap bmp = pendingRenderBitmap;
            if (bmp != null && surfaceRenderer != null) surfaceRenderer.renderBitmap(bmp);
        }
    };


    @Override
    public void onCreate() {
        super.onCreate();
        processingThread = newUrgentThread("ScalerThread");
        captureThread    = newUrgentThread("CaptureThread");
        renderThread     = newUrgentThread("RenderThread");
        processingHandler = new Handler(processingThread.getLooper());
        captureHandler    = new Handler(captureThread.getLooper());
        renderHandler     = new Handler(renderThread.getLooper());
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        prefs        = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        notification = new CaptureNotification(this);
        notification.createChannel();
        staticDetector = new StaticFrameDetector();
        registerReceiver(controlReceiver, new IntentFilter(ACTION_STOP));
        nextFrameDeadlineNs = System.nanoTime();
        for (int i = 0; i < RENDER_POOL_SIZE; i++) renderPool[i] = new FrameRenderRunnable();
    }

    @Override
    public void onDestroy() {
        stopCapture();
        safeUnregisterReceiver();
        shutdownThread(processingThread); processingThread = null;
        shutdownThread(renderThread);     renderThread     = null;
        shutdownThread(captureThread);    captureThread    = null;
        if (bufferPool != null) { bufferPool.release(); bufferPool = null; }
        super.onDestroy();
    }

    private HandlerThread newUrgentThread(String name) {
        HandlerThread t = new HandlerThread(name, Process.THREAD_PRIORITY_URGENT_DISPLAY);
        t.start();
        return t;
    }

    private void safeUnregisterReceiver() {
        try { unregisterReceiver(controlReceiver); } catch (Exception ignored) {}
    }

    private void shutdownThread(HandlerThread t) {
        if (t == null) return;
        if (Build.VERSION.SDK_INT >= 18) t.quitSafely(); else t.quit();
        long deadline = System.currentTimeMillis() + 500;
        while (t.isAlive() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }
    }


    public void setMode(int mode) {
        this.currentMode = mode;
        this.useSIFg  = (mode == MODE_SIFG1);
        this.useASIFg = (mode == MODE_ASIFG || mode == MODE_ASIFG_GPU);
        this.useGpu   = (mode == MODE_GPU   || mode == MODE_ASIFG_GPU);
        this.asifgGpu = (mode == MODE_ASIFG_GPU);
        this.useGfal  = (mode == MODE_GFAL);
        if (this.useASIFg && asifgNet == null) asifgNet = new ASIFgNetwork();
        if (this.useGpu) {
            if (rsPipeline == null) rsPipeline = new RenderScriptPipeline(this);
            if (running) rsPipeline.init(sourceWidth, sourceHeight, targetWidth, targetHeight);
        } else {
            if (rsPipeline != null) { rsPipeline.destroy(); rsPipeline = null; }
        }
        resetHashHistory();
    }

    public void setSifgVersion(int v)        { sifgVersion      = v; }
    public void setResolutionScale(float s)  { resolutionScale  = s; }
    public void setFramesToGenerate(int c)   { framesToGenerate = Math.max(0, Math.min(4, c)); }
    public void setColorQuality(int q)       { colorQuality     = q; }
    public void setGfalSearchRadius(int r)   { gfalSearchRadius = Math.max(4, Math.min(64, r)); }
    public void setGfalLines(int largo, int ancho) { gfalLinesLargo = Math.max(1, largo); gfalLinesAncho = Math.max(1, ancho); }
    public void setGfalGrid(int w, int h)    { gfalGridW  = Math.max(1, w); gfalGridH = Math.max(1, h); }
    /** Activa o desactiva el refinamiento neuronal GFaLFlowNet. */
    public void setGfalNetEnabled(boolean enabled) { gfalNetEnabled = enabled; }
    /** Ajusta la agresividad del refinamiento: alphaPct en [0,100] → [0.0,1.0]. */
    public void setGfalNetAlpha(int alphaPct) { gfalNetAlpha = Math.max(0, Math.min(100, alphaPct)) / 100f; }
    public void setFisheyeCorrection(int c)  {
        fisheyeCorrection = c;
        if (surfaceRenderer != null) {
            surfaceRenderer.setFisheyeCorrection(c);
            surfaceRenderer.invalidateFisheyeCache();
        }
    }
    public void setCaptureMode(int mode) {
        captureMode = mode;
        if (surfaceRenderer != null) surfaceRenderer.setCaptureMode(mode);
    }
    public void setTouchForwardEnabled(boolean e) { touchForwardEnabled = e; }
    public void setTargetFps(int fps) {
        fps = Math.max(10, Math.min(240, fps));
        targetFrameNanos = 1_000_000_000L / fps;
    }


    public int  getFps()          { return currentFps.get();      }
    public int  getTotalFrames()  { return framesProcessed.get(); }
    public int  getDroppedFrames(){ return droppedFrames.get();   }
    public long getSessionFrames(){ return sessionTotalFrames;    }
    public int  getLearnCount()   { return asifgLearnCounter;     }
    public int  getEfficiencyPct() {
        int p = framesProcessed.get(), d = droppedFrames.get(), t = p + d;
        return t > 0 ? (int)(p * 100L / t) : 100;
    }


    public void startCapture(int resultCode, Intent data,
                             int srcW, int srcH, int tgtW, int tgtH,
                             int dpi, int top, int bottom, int mode,
                             int physW, int physH) {
        if (running) return;
        sourceWidth = srcW; sourceHeight = srcH;
        targetWidth = tgtW; targetHeight = tgtH;
        topInset = top;    bottomInset  = bottom;
        captureMode = mode;

        bufferPool    = new BufferPool();
        bufferPool.init(srcW, srcH, tgtW, tgtH);
        imageCapture  = new ImageCapture(srcW, srcH);
        upscaler      = new NearestNeighborUpscaler(bufferPool, srcW, srcH, tgtW, tgtH);
        upscaler.precalculateMappings();
        surfaceRenderer = new SurfaceRenderer(bufferPool, srcW, srcH, tgtW, tgtH);
        surfaceRenderer.setFisheyeCorrection(fisheyeCorrection);
        surfaceRenderer.setCaptureMode(captureMode);
        surfaceRenderer.setInsets(top, bottom);

        if (useGpu) {
            if (rsPipeline == null) rsPipeline = new RenderScriptPipeline(this);
            rsPipeline.init(srcW, srcH, tgtW, tgtH);
        }

        overlayWindow = new OverlayWindow(this, physW, physH, touchForwardEnabled);
        overlayWindow.setListener(new OverlayWindow.SurfaceStateListener() {
                @Override public void onSurfaceValid(SurfaceHolder h) {
                    if (surfaceRenderer != null) surfaceRenderer.setSurfaceView(overlayWindow.getSurfaceView(), true);
                }
                @Override public void onSurfaceInvalid() {
                    if (surfaceRenderer != null) surfaceRenderer.setSurfaceValid(false);
                }
            });
        ensureOverlayOnMainThread();

        try {
            startForeground(NOTIFICATION_ID, notification.build(0));
            MediaProjectionManager mpManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpManager == null) { stopCapture(); return; }
            projection = mpManager.getMediaProjection(resultCode, data);
            if (projection == null) { stopCapture(); return; }

            imageReader = ImageReader.newInstance(srcW, srcH, PixelFormat.RGBA_8888, 3);
            imageReader.setOnImageAvailableListener(new ImageAvailableListener(), captureHandler);
            virtualDisplay = projection.createVirtualDisplay(
                "ScreenScaler", srcW, srcH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);

            processingBusy     = false;
            running            = true;
            sessionStartTime   = System.currentTimeMillis();
            sessionTotalFrames = 0;
            lastFpsUpdate.set(System.currentTimeMillis());
            framesProcessed.set(0); droppedFrames.set(0);
            nextFrameDeadlineNs = System.nanoTime();
            processingHandler.post(new FpsUpdater());

        } catch (Exception e) {
            Log.e(TAG, "Error iniciando captura", e);
            stopCapture();
        }
    }

    public void stopCapture() {
        if (!running) return;
        running = false;
        saveStats();
        safeRelease(virtualDisplay); virtualDisplay = null;
        safeClose(imageReader);      imageReader    = null;
        safeStop(projection);        projection     = null;
        if (rsPipeline != null) { rsPipeline.destroy(); rsPipeline = null; }
        if (overlayWindow != null) { overlayWindow.remove(mainHandler); overlayWindow = null; }
        stopForeground(true);
        if (bufferPool != null) { bufferPool.release(); bufferPool = null; }
        stopSelf();
    }

    public void updateResolution(int srcW, int srcH, int tgtW, int tgtH) {
        sourceWidth = srcW; sourceHeight = srcH;
        targetWidth = tgtW; targetHeight = tgtH;
        if (bufferPool != null) bufferPool.init(srcW, srcH, tgtW, tgtH);
        if (upscaler != null)   upscaler.precalculateMappings();
        if (surfaceRenderer != null) surfaceRenderer.invalidateFisheyeCache();
    }

    public void onOrientationChanged(int srcW, int srcH, int tgtW, int tgtH,
                                     int dpi, int top, int bottom, int physW, int physH) {
        sourceWidth = srcW; sourceHeight = srcH;
        targetWidth = tgtW; targetHeight = tgtH;
        topInset = top;     bottomInset  = bottom;
        if (bufferPool    != null) bufferPool.init(srcW, srcH, tgtW, tgtH);
        if (upscaler      != null) upscaler.precalculateMappings();
        if (surfaceRenderer != null) {
            surfaceRenderer.setInsets(top, bottom);
            surfaceRenderer.invalidateFisheyeCache();
        }
        if (virtualDisplay != null) try { virtualDisplay.resize(srcW, srcH, dpi); }
            catch (Exception e) { Log.e(TAG, "Error resizing VirtualDisplay", e); }
        if (overlayWindow  != null) {
            final int finalPhysW = physW, finalPhysH = physH;
            mainHandler.post(new Runnable() {
                    @Override public void run() { overlayWindow.resize(finalPhysW, finalPhysH); }
                });
        }
    }

    // ── Helpers de release ────────────────────────────────────────────────────

    private void safeRelease(android.hardware.display.VirtualDisplay vd) {
        if (vd == null) return;
        try { vd.release(); } catch (Exception e) { Log.e(TAG, "Error releasing VirtualDisplay", e); }
    }
    private void safeClose(ImageReader ir) {
        if (ir == null) return;
        try { ir.close(); } catch (Exception e) { Log.e(TAG, "Error closing ImageReader", e); }
    }
    private void safeStop(MediaProjection mp) {
        if (mp == null) return;
        try { mp.stop(); } catch (Exception e) { Log.e(TAG, "Error stopping MediaProjection", e); }
    }

    private byte[] copySourceBuffer(byte[] src) {
        String key = copySlotA
            ? BufferPool.KEY_PREV_FRAME : BufferPool.KEY_STATIC_FRAME;
        copySlotA = !copySlotA;
        byte[] copy = (byte[]) bufferPool.get(key);
        if (copy == null || copy.length != src.length) {
            copy = new byte[src.length]; bufferPool.put(key, copy);
        }
        System.arraycopy(src, 0, copy, 0, src.length);
        return copy;
    }


    private void ensureOverlayOnMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) overlayWindow.create();
        else mainHandler.post(new Runnable() { @Override public void run() { overlayWindow.create(); } });
    }


    private void resetHashHistory() {
        staticDetector.reset();
        gfalFreezeDetector.thawAll();
        sifgV10Dx = 0; sifgV10Dy = 0; sifgPrevDx = 0f; sifgPrevDy = 0f;
    }


    private void renderToSurface(byte[] frameData) {
        pendingRenderFrame = frameData;
        renderHandler.removeCallbacks(renderPendingRunnable);
        renderHandler.post(renderPendingRunnable);
    }

    private void renderToSurfaceOrdered(byte[] frameData) {
        FrameRenderRunnable r = renderPool[renderPoolHead];
        renderPoolHead = (renderPoolHead + 1) % RENDER_POOL_SIZE;
        r.frameData = frameData;
        renderHandler.post(r);
    }

    private void releaseProcessingLockOrdered() { processingBusy = false; }

    private void renderBitmapDirect(final Bitmap srcBmp) {
        if (srcBmp == null) return;
        pendingRenderBitmap = srcBmp;
        renderHandler.removeCallbacks(renderBitmapRunnable);
        renderHandler.post(renderBitmapRunnable);
    }

    // ── Inner classes de render pool ──────────────────────────────────────────

    private final class FrameRenderRunnable implements Runnable {
        volatile byte[] frameData;
        @Override public void run() { if (surfaceRenderer != null) surfaceRenderer.renderBytes(frameData); }
    }


    private final class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            if (!running) return;
            if (processingBusy) {
                Image img = reader.acquireLatestImage();
                if (img != null) img.close();
                droppedFrames.incrementAndGet();
                return;
            }
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null || !running) { if (image != null) image.close(); return; }
                captureErrorCount.set(0);
                processingBusy = true;
                if (useASIFg) {
                    if (asifgGpu && rsPipeline != null && rsPipeline.isReady())
                        processingHandler.post(new ASIFgGpuProcessor(image));
                    else processingHandler.post(new ASIFgGenerator(image));
                } else if (useGpu)  { processingHandler.post(new GpuFrameProcessor(image));
                } else if (useSIFg) {
                    if (sifgVersion == 0) processingHandler.post(new SIFgV1Generator(image));
                    else                  processingHandler.post(new SIFgV1_1Generator(image));
                } else if (useGfal) { processingHandler.post(new GFaLGenerator(image));
                } else              { processingHandler.post(new FrameProcessor(image)); }
            } catch (Exception e) {
                processingBusy = false;
                Log.e(TAG, "Error acquiring image", e);
                if (image != null) image.close();
                droppedFrames.incrementAndGet();
                final int errors = captureErrorCount.incrementAndGet();
                if (errors >= 3) {
                    captureErrorCount.set(0);
                    mainHandler.post(new Runnable() { @Override public void run() {
                                Toast.makeText(ScreenCaptureService.this,
                                               "Error de captura repetido: reinicia la app", Toast.LENGTH_LONG).show();
                            }});
                }
            }
        }
    }


    private final class FpsUpdater implements Runnable {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            long elapsed = now - lastFpsUpdate.get();
            if (elapsed >= 1000L) {
                int frames = framesProcessed.getAndSet(0);
                currentFps.set(elapsed > 0 ? (int)(frames * 1000L / elapsed) : 0);
                lastFpsUpdate.set(now);
                notification.update(currentFps.get());
            }
            processingHandler.postDelayed(this, 250);
        }
    }


    private final class FrameProcessor implements Runnable {
        private final Image image;
        FrameProcessor(Image img) { this.image = img; }
        @Override
        public void run() {
            if (image == null || !running) { if (image != null) image.close(); processingBusy = false; return; }
            try {
                Image.Plane[] planes = image.getPlanes();
                if (planes.length == 0) return;
                ByteBuffer buf = planes[0].getBuffer();
                int pixStride  = planes[0].getPixelStride();
                int rowStride  = planes[0].getRowStride();
                int rowPad     = rowStride - pixStride * sourceWidth;

                String bmpKey = renderSrcSlotA
                    ? BufferPool.KEY_RENDER_BITMAP_SRC : BufferPool.KEY_RENDER_BITMAP_SRC_B;
                renderSrcSlotA = !renderSrcSlotA;

                Bitmap srcBmp = (Bitmap) bufferPool.get(bmpKey);
                if (srcBmp == null || srcBmp.isRecycled()
                    || srcBmp.getWidth() != sourceWidth || srcBmp.getHeight() != sourceHeight) {
                    if (srcBmp != null && !srcBmp.isRecycled()) srcBmp.recycle();
                    srcBmp = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888);
                    bufferPool.put(bmpKey, srcBmp);
                }

                int needed = sourceWidth * sourceHeight * 4;
                byte[] srcArr = (byte[]) bufferPool.get(BufferPool.KEY_SOURCE_BUFFER);
                if (srcArr == null || srcArr.length < needed) {
                    srcArr = new byte[needed]; bufferPool.put(BufferPool.KEY_SOURCE_BUFFER, srcArr);
                }
                buf.rewind();
                if (pixStride == 4 && rowPad == 0) {
                    buf.get(srcArr, 0, needed);
                } else if (pixStride == 4) {
                    int rowBytes = sourceWidth * 4, offset = 0;
                    for (int y = 0; y < sourceHeight; y++) {
                        buf.get(srcArr, offset, rowBytes); offset += rowBytes;
                        if (rowPad > 0) buf.position(buf.position() + rowPad);
                    }
                } else {
                    int sp = 0, dp = 0;
                    for (int y = 0; y < sourceHeight; y++) {
                        for (int x = 0; x < sourceWidth; x++) {
                            srcArr[dp] = buf.get(sp); srcArr[dp+1] = buf.get(sp+1);
                            srcArr[dp+2] = buf.get(sp+2); srcArr[dp+3] = buf.get(sp+3);
                            sp += pixStride; dp += 4;
                        }
                        sp += rowPad;
                    }
                }
                srcBmp.copyPixelsFromBuffer(ByteBuffer.wrap(srcArr, 0, needed));
                processingBusy = false;
                renderBitmapDirect(srcBmp);
                framesProcessed.incrementAndGet();
                sessionTotalFrames++;
            } catch (Exception e) {
                Log.e(TAG, "Error en FrameProcessor", e);
                droppedFrames.incrementAndGet();
                processingBusy = false;
            } finally { image.close(); }
        }
    }

    private final class SIFgV1Generator implements Runnable {
        private final Image image;
        SIFgV1Generator(Image img) { this.image = img; }

        private static final float EMA_ALPHA   = 0.35f;
        private static final int   MAX_DELTA_PX = 32; // clamp anti-glitch

        @Override
        public void run() {
            if (image == null || !running) {
                if (image != null) image.close();
                processingBusy = false;
                return;
            }
            try {
                byte[] src = (byte[]) bufferPool.get(BufferPool.KEY_SOURCE_BUFFER);
                if (src == null) return;
                imageCapture.captureImageToBuffer(image, src, colorQuality);

                byte[] prevSrc = (byte[]) bufferPool.get(BufferPool.KEY_LAST_REAL_FRAME);

                // ── Paso 1: calcular vector de movimiento de este ciclo ────────
                int newDx = 0, newDy = 0;
                if (prevSrc != null && prevSrc.length == src.length) {
                    int[] motion = FrameUtils.estimateMotionUltraLight(
                        prevSrc, src, sourceWidth, sourceHeight);
                    float scaleX = (float) targetWidth  / sourceWidth;
                    float scaleY = (float) targetHeight / sourceHeight;
                    int rawDx = (int)(motion[0] * scaleX);
                    int rawDy = (int)(motion[1] * scaleY);
                    rawDx = Math.max(-MAX_DELTA_PX, Math.min(MAX_DELTA_PX, rawDx));
                    rawDy = Math.max(-MAX_DELTA_PX, Math.min(MAX_DELTA_PX, rawDy));
                    newDx = Math.round(rawDx * EMA_ALPHA + sifgV10Dx * (1f - EMA_ALPHA));
                    newDy = Math.round(rawDy * EMA_ALPHA + sifgV10Dy * (1f - EMA_ALPHA));
                }

                // ── Paso 2: upscale del frame actual ──────────────────────────
                byte[] upscaled = upscaler.upscale(src);
                if (upscaled == null) return;

                // ── Paso 3: aplicar el vector del ciclo ANTERIOR para predecir ─
                if ((sifgV10Dx != 0 || sifgV10Dy != 0) && prevSrc != null) {
                    byte[] predicted = (byte[]) bufferPool.get(BufferPool.KEY_PREDICT_FRAME);
                    if (predicted != null && predicted.length == upscaled.length) {
                        FrameUtils.translateFrame(
                            upscaled, predicted,
                            sifgV10Dx, sifgV10Dy,
                            targetWidth, targetHeight);
                        renderToSurface(predicted);
                    } else {
                        renderToSurface(upscaled);
                    }
                } else {
                    renderToSurface(upscaled);
                }

                // ── Paso 4: guardar estado para el próximo ciclo ──────────────
                sifgV10Dx = newDx;
                sifgV10Dy = newDy;
                if (prevSrc != null && prevSrc.length == src.length) {
                    System.arraycopy(src, 0, prevSrc, 0, src.length);
                }

                framesProcessed.incrementAndGet();
                sessionTotalFrames++;

            } catch (Exception e) {
                Log.e(TAG, "Error en SIFgV1Generator", e);
                droppedFrames.incrementAndGet();
            } finally {
                image.close();
                processingBusy = false;
            }
        }
    }

    private final class SIFgV1_1Generator implements Runnable {
        private final Image image;
        SIFgV1_1Generator(Image img) { this.image = img; }
        @Override
        public void run() {
            try {
                if (image == null || !running) return;
                byte[] src = (byte[]) bufferPool.get(BufferPool.KEY_SOURCE_BUFFER);
                if (src == null) return;
                imageCapture.captureImageToBuffer(image, src, colorQuality);
                byte[] prevSrc  = (byte[]) bufferPool.get(BufferPool.KEY_LAST_REAL_FRAME);
                Long   prevTime = (Long)   bufferPool.get(BufferPool.KEY_LAST_FRAME_TIME);
                long   now      = System.nanoTime();
                boolean generated = false;
                if (prevSrc != null && prevTime != null && prevSrc.length == src.length
                    && (now - prevTime) <= 50_000_000L) {
                    String blendKey = sifgBlendSlotA ? BufferPool.KEY_EXTRAPOLATION : BufferPool.KEY_EXTRAPOLATION_B;
                    sifgBlendSlotA  = !sifgBlendSlotA;
                    byte[] blend = (byte[]) bufferPool.get(blendKey);
                    if (blend != null && blend.length == src.length) {
                        FrameUtils.pureBlendFrames(prevSrc, src, blend);
                        byte[] blendUp = upscaler.upscaleForOrdered(blend);
                        byte[] srcUp   = upscaler.upscaleForOrdered(src);
                        if (blendUp != null) { renderToSurfaceOrdered(blendUp); framesProcessed.incrementAndGet(); sessionTotalFrames++; }
                        if (srcUp   != null) { renderToSurfaceOrdered(srcUp);   framesProcessed.incrementAndGet(); sessionTotalFrames++; }
                        generated = (blendUp != null || srcUp != null);
                    }
                }
                if (!generated) {
                    byte[] srcUp = upscaler.upscaleForOrdered(src);
                    if (srcUp != null) { renderToSurfaceOrdered(srcUp); framesProcessed.incrementAndGet(); sessionTotalFrames++; }
                }
                if (prevSrc != null && prevSrc.length == src.length)
                    System.arraycopy(src, 0, prevSrc, 0, src.length);
                bufferPool.put(BufferPool.KEY_LAST_FRAME_TIME, now);
            } catch (Exception e) {
                Log.e(TAG, "Error en SIFgV1_1Generator", e); droppedFrames.incrementAndGet();
            } finally {
                if (image != null) image.close();
                releaseProcessingLockOrdered();
            }
        }
    }

    private final class ASIFgGenerator implements Runnable {
        private final Image image;
        ASIFgGenerator(Image img) { this.image = img; }
        @Override
        public void run() {
            try {
                if (image == null || !running) return;
                byte[] src = (byte[]) bufferPool.get(BufferPool.KEY_SOURCE_BUFFER);
                if (src == null) return;
                imageCapture.captureImageToBuffer(image, src, colorQuality);
                byte[] lastReal     = (byte[]) bufferPool.get(BufferPool.KEY_LAST_REAL_FRAME);
                Long   lastRealTime = (Long)   bufferPool.get(BufferPool.KEY_LAST_FRAME_TIME);
                long   now          = System.nanoTime();
                boolean generated = false;
                if (lastReal != null && lastRealTime != null && lastReal.length == src.length
                    && (now - lastRealTime) <= 60_000_000L) {
                    buildFlowField(lastReal, src);
                    asifgLearnCounter++;
                    byte[] synth = buildSyntheticFrame(lastReal, src);
                    if (synth != null) {
                        byte[] synthUp = upscaler.upscaleForOrdered(synth);
                        byte[] srcUp   = upscaler.upscaleForOrdered(src);
                        if (synthUp != null) { renderToSurfaceOrdered(synthUp); framesProcessed.incrementAndGet(); sessionTotalFrames++; }
                        if (srcUp   != null) { renderToSurfaceOrdered(srcUp);   framesProcessed.incrementAndGet(); sessionTotalFrames++; }
                        generated = (synthUp != null || srcUp != null);
                    }
                }
                if (!generated) {
                    byte[] srcUp = upscaler.upscaleForOrdered(src);
                    renderToSurface(srcUp != null ? srcUp : copySourceBuffer(src));
                    framesProcessed.incrementAndGet(); sessionTotalFrames++;
                }
                if (lastReal != null && lastReal.length == src.length)
                    System.arraycopy(src, 0, lastReal, 0, src.length);
                bufferPool.put(BufferPool.KEY_LAST_FRAME_TIME, now);
            } catch (Exception e) {
                Log.e(TAG, "Error en ASIFgGenerator", e); droppedFrames.incrementAndGet();
            } finally { if (image != null) image.close(); releaseProcessingLockOrdered(); }
        }

        // ── Campo de flujo ────────────────────────────────────────────────────
        private void buildFlowField(byte[] frameA, byte[] frameB) {
            int blockW = Math.max(1, sourceWidth  / ASIFG_GRID_COLS);
            int blockH = Math.max(1, sourceHeight / ASIFG_GRID_ROWS);
            int stride = sourceWidth * 4, lenA = frameA.length, lenB = frameB.length;
            for (int row = 0; row < ASIFG_GRID_ROWS; row++) {
                for (int col = 0; col < ASIFG_GRID_COLS; col++) {
                    int bIdx = row * ASIFG_GRID_COLS + col;
                    int cx = Math.min(col * blockW + blockW / 2, sourceWidth  - 1);
                    int cy = Math.min(row * blockH + blockH / 2, sourceHeight - 1);
                    int quickSAD = 0, bH3 = blockH / 3, bW3 = blockW / 3;
                    int sH1 = sourceHeight - 1, sW1 = sourceWidth - 1;
                    for (int ky = -1; ky <= 1; ky++) {
                        int py = cy + ky * bH3; if (py < 0) py = 0; else if (py > sH1) py = sH1;
                        int pyS = py * stride;
                        for (int kx = -1; kx <= 1; kx++) {
                            int px = cx + kx * bW3; if (px < 0) px = 0; else if (px > sW1) px = sW1;
                            int idx = pyS + px * 4 + 1;
                            if (idx < lenA && idx < lenB)
                                quickSAD += Math.abs((frameA[idx] & 0xFF) - (frameB[idx] & 0xFF));
                        }
                    }
                    if (quickSAD < 9 * 8) {
                        asifgSmoothDx[bIdx] = 0f; asifgSmoothDy[bIdx] = 0f;
                        asifgFlowDx[bIdx]   = 0f; asifgFlowDy[bIdx]   = 0f; continue;
                    }
                    blockMatchLocal(frameA, frameB, cx, cy);
                    float bmDx = asifgRefPatch[0], bmDy = asifgRefPatch[1];
                    float maxS = Math.min(blockW, blockH) * 0.75f;
                    bmDx = Math.max(-maxS, Math.min(maxS, bmDx));
                    bmDy = Math.max(-maxS, Math.min(maxS, bmDy));
                    float sDx = bmDx * (1f - FLOW_EMA) + asifgSmoothDx[bIdx] * FLOW_EMA;
                    float sDy = bmDy * (1f - FLOW_EMA) + asifgSmoothDy[bIdx] * FLOW_EMA;
                    asifgSmoothDx[bIdx] = sDx; asifgSmoothDy[bIdx] = sDy;
                    asifgFlowDx[bIdx]   = sDx; asifgFlowDy[bIdx]   = sDy;
                }
            }
        }

        private void blockMatchLocal(byte[] frameA, byte[] frameB, int cx, int cy) {
            final int stride = sourceWidth * 4, patchR = ASIFgNetwork.PATCH / 2, searchR = 4;
            final int sH1 = sourceHeight - 1, sW1 = sourceWidth - 1;
            int pi = 2;
            for (int dy = -patchR; dy < patchR; dy++) {
                int fy = cy + dy; if (fy < 0) fy = 0; else if (fy > sH1) fy = sH1;
                int fyS = fy * stride;
                for (int dx = -patchR; dx < patchR; dx++) {
                    int fx = cx + dx; if (fx < 0) fx = 0; else if (fx > sW1) fx = sW1;
                    asifgRefPatch[pi++] = frameA[fyS + fx * 4 + 1] & 0xFF;
                }
            }
            int bestSAD = Integer.MAX_VALUE, bestDx = 0, bestDy = 0;
            outer:
            for (int sdy = -searchR; sdy <= searchR; sdy++) {
                int bcy = cy + sdy;
                for (int sdx = -searchR; sdx <= searchR; sdx++) {
                    int sad = 0; pi = 2; int bcx = cx + sdx;
                    for (int dy = -patchR; dy < patchR; dy++) {
                        int fy = bcy + dy; if (fy < 0) fy = 0; else if (fy > sH1) fy = sH1;
                        int fyS = fy * stride;
                        for (int dx = -patchR; dx < patchR; dx++) {
                            int fx = bcx + dx; if (fx < 0) fx = 0; else if (fx > sW1) fx = sW1;
                            sad += Math.abs(asifgRefPatch[pi++] - (frameB[fyS + fx * 4 + 1] & 0xFF));
                        }
                    }
                    if (sad < bestSAD) { bestSAD = sad; bestDx = sdx; bestDy = sdy; if (sad == 0) break outer; }
                }
            }
            asifgRefPatch[0] = bestDx; asifgRefPatch[1] = bestDy;
        }

        private byte[] buildSyntheticFrame(byte[] frameA, byte[] frameB) {
            byte[] synth = (byte[]) bufferPool.get(BufferPool.KEY_ASIFG_SYNTH);
            if (synth == null || synth.length != frameB.length) return null;
            int[] colIdx = bufferPool.asifgColIdx; float[] colW = bufferPool.asifgColW;
            int[] rowIdx = bufferPool.asifgRowIdx; float[] rowW = bufferPool.asifgRowW;
            if (colIdx == null || rowIdx == null) return null;
            int stride = sourceWidth * 4;
            int sW1 = sourceWidth - 1, sH1 = sourceHeight - 1;
            for (int y = 0; y < sourceHeight; y++) {
                int   r0 = rowIdx[y], r1 = r0 + 1;
                float ty = rowW[y],   ity = 1f - ty;
                int   b0 = r0 * ASIFG_GRID_COLS, b1 = r1 * ASIFG_GRID_COLS;
                int   rowBase = y * stride;
                for (int x = 0, di = rowBase; x < sourceWidth; x++, di += 4) {
                    int   c0  = colIdx[x]; float tx = colW[x]; float itx = 1f - tx; int c1 = c0 + 1;
                    float i00 = itx * ity, i10 = tx * ity, i01 = itx * ty, i11 = tx * ty;
                    float fdx = asifgFlowDx[b0+c0]*i00 + asifgFlowDx[b0+c1]*i10
                        + asifgFlowDx[b1+c0]*i01 + asifgFlowDx[b1+c1]*i11;
                    float fdy = asifgFlowDy[b0+c0]*i00 + asifgFlowDy[b0+c1]*i10
                        + asifgFlowDy[b1+c0]*i01 + asifgFlowDy[b1+c1]*i11;
                    if (fdx > -0.5f && fdx < 0.5f && fdy > -0.5f && fdy < 0.5f) {
                        synth[di]   = (byte)(((frameA[di]   & 0xFF) + (frameB[di]   & 0xFF)) >> 1);
                        synth[di+1] = (byte)(((frameA[di+1] & 0xFF) + (frameB[di+1] & 0xFF)) >> 1);
                        synth[di+2] = (byte)(((frameA[di+2] & 0xFF) + (frameB[di+2] & 0xFF)) >> 1);
                        synth[di+3] = (byte) 255;
                    } else {
                        float hx = fdx * 0.5f, hy = fdy * 0.5f;
                        int ax = (int)(x - hx + 0.5f); if (ax < 0) ax = 0; else if (ax > sW1) ax = sW1;
                        int ay = (int)(y - hy + 0.5f); if (ay < 0) ay = 0; else if (ay > sH1) ay = sH1;
                        int aBase = ay * stride + ax * 4;
                        int bx = (int)(x + hx + 0.5f); if (bx < 0) bx = 0; else if (bx > sW1) bx = sW1;
                        int by = (int)(y + hy + 0.5f); if (by < 0) by = 0; else if (by > sH1) by = sH1;
                        int bBase = by * stride + bx * 4;
                        synth[di]   = (byte)(((frameA[aBase]   & 0xFF) + (frameB[bBase]   & 0xFF)) >> 1);
                        synth[di+1] = (byte)(((frameA[aBase+1] & 0xFF) + (frameB[bBase+1] & 0xFF)) >> 1);
                        synth[di+2] = (byte)(((frameA[aBase+2] & 0xFF) + (frameB[bBase+2] & 0xFF)) >> 1);
                        synth[di+3] = (byte) 255;
                    }
                }
            }
            return synth;
        }
    }

    private final class GpuFrameProcessor implements Runnable {
        private final Image image;
        GpuFrameProcessor(Image img) { this.image = img; }
        @Override
        public void run() {
            if (image == null || !running || rsPipeline == null || !rsPipeline.isReady()) {
                if (image != null) {
                    byte[] src = (byte[]) bufferPool.get(BufferPool.KEY_SOURCE_BUFFER);
                    if (src != null) { imageCapture.captureImageToBuffer(image, src, colorQuality);
                        byte[] up = upscaler.upscale(src); if (up != null) renderToSurface(up); }
                    image.close();
                }
                processingBusy = false; return;
            }
            try {
                byte[] src = (byte[]) bufferPool.get(BufferPool.KEY_SOURCE_BUFFER);
                if (src == null) return;
                imageCapture.captureImageToBuffer(image, src, colorQuality);
                rsPipeline.uploadSource(src);
                rsPipeline.resize();
                byte[] real = rsPipeline.downloadTargetPing();
                if (real == null) return;
                byte[] prevReal = rsPipeline.getPreviousBytes();
                if (prevReal != null) {
                    byte[] blendBuf = rsPipeline.getBlendBuffer();
                    if (blendBuf != null) {
                        FrameUtils.pureBlendFrames(prevReal, real, blendBuf);
                        String bKey = gpuBlendSlotA ? BufferPool.KEY_GPU_BLEND_COPY_A : BufferPool.KEY_GPU_BLEND_COPY_B;
                        gpuBlendSlotA = !gpuBlendSlotA;
                        byte[] bc = (byte[]) bufferPool.get(bKey);
                        if (bc == null || bc.length != blendBuf.length) { bc = new byte[blendBuf.length]; bufferPool.put(bKey, bc); }
                        System.arraycopy(blendBuf, 0, bc, 0, blendBuf.length);
                        renderToSurfaceOrdered(bc); framesProcessed.incrementAndGet(); sessionTotalFrames++;
                    }
                }
                rsPipeline.savePreviousBytes(real);
                renderToSurfaceOrdered(real); framesProcessed.incrementAndGet(); sessionTotalFrames++;
            } catch (Exception e) {
                Log.e(TAG, "Error GpuFrameProcessor", e); droppedFrames.incrementAndGet();
            } finally { image.close(); releaseProcessingLockOrdered(); }
        }
    }

    private final class ASIFgGpuProcessor implements Runnable {
        private final Image image;
        ASIFgGpuProcessor(Image img) { this.image = img; }
        @Override
        public void run() {
            if (rsPipeline == null || !rsPipeline.isReady()) { new ASIFgGenerator(image).run(); return; }
            try {
                if (image == null || !running) return;
                byte[] src = (byte[]) bufferPool.get(BufferPool.KEY_SOURCE_BUFFER);
                if (src == null) return;
                imageCapture.captureImageToBuffer(image, src, colorQuality);
                byte[] lastReal = (byte[]) bufferPool.get(BufferPool.KEY_LAST_REAL_FRAME);
                Long   lrt      = (Long)   bufferPool.get(BufferPool.KEY_LAST_FRAME_TIME);
                long   now      = System.nanoTime();
                rsPipeline.uploadSource(src); rsPipeline.resize();
                boolean generated = false;
                if (lastReal != null && lrt != null && lastReal.length == src.length && (now - lrt) <= 60_000_000L) {
                    asifgLearnCounter++;
                    byte[] prevReal = rsPipeline.getPreviousBytes();
                    if (prevReal != null) {
                        byte[] real2    = rsPipeline.downloadTargetPing();
                        byte[] blendBuf = rsPipeline.getBlendBuffer();
                        if (real2 != null && blendBuf != null) {
                            FrameUtils.pureBlendFrames(prevReal, real2, blendBuf);
                            String bKey = gpuBlendSlotA ? BufferPool.KEY_GPU_BLEND_COPY_A : BufferPool.KEY_GPU_BLEND_COPY_B;
                            gpuBlendSlotA = !gpuBlendSlotA;
                            byte[] bc = (byte[]) bufferPool.get(bKey);
                            if (bc == null || bc.length != blendBuf.length) { bc = new byte[blendBuf.length]; bufferPool.put(bKey, bc); }
                            System.arraycopy(blendBuf, 0, bc, 0, blendBuf.length);
                            renderToSurfaceOrdered(bc); framesProcessed.incrementAndGet(); sessionTotalFrames++;
                            generated = true;
                            rsPipeline.savePreviousBytes(real2);
                            renderToSurfaceOrdered(real2); framesProcessed.incrementAndGet(); sessionTotalFrames++;
                        }
                    }
                }
                if (!generated) {
                    byte[] real = rsPipeline.downloadTargetPing();
                    if (real != null) { rsPipeline.savePreviousBytes(real); renderToSurfaceOrdered(real); }
                    else { byte[] su = upscaler.upscaleForOrdered(src); renderToSurface(su != null ? su : copySourceBuffer(src)); }
                    framesProcessed.incrementAndGet(); sessionTotalFrames++;
                }
                if (lastReal != null && lastReal.length == src.length)
                    System.arraycopy(src, 0, lastReal, 0, src.length);
                bufferPool.put(BufferPool.KEY_LAST_FRAME_TIME, now);
            } catch (Exception e) {
                Log.e(TAG, "Error ASIFgGpuProcessor", e); droppedFrames.incrementAndGet();
            } finally { if (image != null) image.close(); releaseProcessingLockOrdered(); }
        }
    }

    private final class GFaLGenerator implements Runnable {
        private final Image image;
        GFaLGenerator(Image img) { this.image = img; }

        @Override
        public void run() {
            if (image == null || !running) {
                if (image != null) image.close();
                processingBusy = false;
                return;
            }
            try {
                byte[] src = (byte[]) bufferPool.get(BufferPool.KEY_SOURCE_BUFFER);
                if (src == null) { processingBusy = false; return; }
                imageCapture.captureImageToBuffer(image, src, colorQuality);

                byte[] prevSrc = (byte[]) bufferPool.get(BufferPool.KEY_LAST_REAL_FRAME);

                if (prevSrc != null && prevSrc.length == src.length) {
                    // ── Tile Freeze: avanzar historial antes de extraer flow ───
                    gfalFreezeDetector.nextFrame();

                    // ── Extraer flow A→B (saltando tiles congelados) ──────────
                    FrameUtils.gfalExtractFlow(
                        prevSrc, src,
                        sourceWidth, sourceHeight,
                        gfalSearchRadius,
                        gfalFlowDx, gfalFlowDy,
                        gfalFreezeDetector);

                    // ── Refinamiento neuronal (opcional) ──────────────────────
                    if (gfalNetEnabled) {
                        FrameUtils.gfalRefineFlow(
                            gfalFlowDx, gfalFlowDy,
                            gfalNetAlpha,
                            gfalNetHidden, gfalNetDelta);
                    }

                    // ── Construir predicción C en espacio fuente ──────────────
                    String predKey = gfalSlotA
                        ? BufferPool.KEY_EXTRAPOLATION : BufferPool.KEY_EXTRAPOLATION_B;
                    gfalSlotA = !gfalSlotA;
                    byte[] pred = (byte[]) bufferPool.get(predKey);
                    if (pred == null || pred.length != src.length) {
                        pred = new byte[src.length];
                        bufferPool.put(predKey, pred);
                    }

                    FrameUtils.gfalBuildPrediction(
                        src, pred,
                        gfalFlowDx, gfalFlowDy,
                        sourceWidth, sourceHeight);

                    // ── Upscale y render ──────────────────────────────────────
                    byte[] up = upscaler.upscale(pred);
                    if (up != null) renderToSurface(up);
                    else            renderToSurface(upscaler.upscale(src));

                } else {
                    byte[] up = upscaler.upscale(src);
                    if (up != null) renderToSurface(up);
                }

                // ── Guardar frame actual como A del próximo ciclo ─────────────
                if (prevSrc == null || prevSrc.length != src.length) {
                    prevSrc = new byte[src.length];
                    bufferPool.put(BufferPool.KEY_LAST_REAL_FRAME, prevSrc);
                }
                System.arraycopy(src, 0, prevSrc, 0, src.length);

                framesProcessed.incrementAndGet();
                sessionTotalFrames++;

            } catch (Exception e) {
                Log.e(TAG, "Error en GFaLGenerator", e);
                droppedFrames.incrementAndGet();
            } finally {
                image.close();
                processingBusy = false;
            }
        }
    }


    private void saveStats() {
        if (sessionTotalFrames == 0 || sessionStartTime == 0) return;
        long elapsed = System.currentTimeMillis() - sessionStartTime;
        if (elapsed <= 0) return;
        int avgFps = (int)(sessionTotalFrames * 1000L / elapsed);
        String key = currentMode == MODE_PERFORMANCE ? PREF_AVG_FPS_PERFORMANCE
            : currentMode == MODE_ASIFG       ? PREF_AVG_FPS_ASIFG
            : currentMode == MODE_GFAL        ? PREF_AVG_FPS_GFAL
            :                                   PREF_AVG_FPS_SIFG1;
        int old = prefs.getInt(key, 0);
        prefs.edit().putInt(key, old == 0 ? avgFps : (old + avgFps) / 2).apply();
    }
}


