package com.TuringSoftware.FrameGenerator.service.buffers;

import android.graphics.Bitmap;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

import static com.TuringSoftware.FrameGenerator.AppConstants.*;

/**
 * Gestiona el ciclo de vida de todos los buffers de byte[] y Bitmaps
 * reutilizables del pipeline de captura.
 *
 * <p>Separa la responsabilidad de memoria de {@code ScreenCaptureService},
 * que era el principal culpable de que ese archivo fuera titánico.
 *
 * <p>Thread-safety: los métodos {@link #init} y {@link #release} están
 * sincronizados. El resto se llama exclusivamente desde processingThread.
 */
public class BufferPool {

    // ── Claves públicas del caché ─────────────────────────────────────────────
    public static final String KEY_SOURCE_BUFFER    = "src";
    public static final String KEY_UPSCALE_A        = "ua";
    public static final String KEY_UPSCALE_B        = "ub";
    public static final String KEY_UPSCALE_C        = "uc";
    public static final String KEY_UPSCALE_D        = "ud";
    public static final String KEY_UPSCALE_E        = "ue";
    public static final String KEY_UPSCALE_F        = "uf";
    public static final String KEY_UPSCALE_G        = "ug";
    public static final String KEY_UPSCALE_H        = "uh";
    public static final String KEY_EXTRAPOLATION    = "ex";
    public static final String KEY_EXTRAPOLATION_B  = "ex2";
    public static final String KEY_ASIFG_SYNTH      = "as";
    public static final String KEY_LAST_REAL_FRAME  = "lr";
    public static final String KEY_PREV_FRAME       = "pf";
    public static final String KEY_STATIC_FRAME     = "sf";
    public static final String KEY_CACHED_STATIC    = "cs";
    public static final String KEY_DIRECT_BUFFER    = "db";
    public static final String KEY_DIRECT_BUFFER_SRC= "db_s";
    public static final String KEY_GPU_BLEND_COPY_A = "gba";
    public static final String KEY_GPU_BLEND_COPY_B = "gbb";
    public static final String KEY_RENDER_BITMAP    = "rb";
    public static final String KEY_RENDER_BITMAP_SRC= "rb_s";
    public static final String KEY_RENDER_BITMAP_SRC_B = "rb_sb";
    public static final String KEY_XMAP             = "xm";
    public static final String KEY_YMAP             = "ym";
    public static final String KEY_LAST_FRAME_TIME  = "lt";
    public static final String KEY_PREDICT_FRAME    = "prd";

    public static final String[] UPSCALE_ORDERED_POOL = {
        KEY_UPSCALE_C, KEY_UPSCALE_D, KEY_UPSCALE_E,
        KEY_UPSCALE_F, KEY_UPSCALE_G, KEY_UPSCALE_H
    };

    // ── Caché central ─────────────────────────────────────────────────────────
    private final HashMap<String, Object> cache = new HashMap<String, Object>();

    // ── Dimensiones actuales ──────────────────────────────────────────────────
    private int sourceWidth, sourceHeight;
    private int targetWidth, targetHeight;

    // ── Grid ASIFg (calculado aquí para no contaminar el servicio) ────────────
    public int[]   asifgColIdx;
    public float[] asifgColW;
    public int[]   asifgRowIdx;
    public float[] asifgRowW;

    private static final int ASIFG_GRID_COLS = 4;
    private static final int ASIFG_GRID_ROWS = 4;

    // ── API pública ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public synchronized void init(int srcW, int srcH, int tgtW, int tgtH) {
        sourceWidth  = srcW; sourceHeight = srcH;
        targetWidth  = tgtW; targetHeight = tgtH;

        int tSize = tgtW * tgtH * 4;
        int sSize = srcW * srcH * 4;

        cache.put(KEY_SOURCE_BUFFER,   new byte[sSize]);
        cache.put(KEY_EXTRAPOLATION,   new byte[sSize]);
        cache.put(KEY_EXTRAPOLATION_B, new byte[sSize]);
        cache.put(KEY_ASIFG_SYNTH,     new byte[sSize]);
        cache.put(KEY_LAST_REAL_FRAME, new byte[sSize]);
        cache.put(KEY_PREV_FRAME,      new byte[sSize]);
        cache.put(KEY_STATIC_FRAME,    new byte[sSize]);
        cache.put(KEY_CACHED_STATIC,   new byte[sSize]);

        int blockW = Math.max(1, srcW / ASIFG_GRID_COLS);
        int blockH = Math.max(1, srcH / ASIFG_GRID_ROWS);
        asifgColIdx = new int[srcW];   asifgColW = new float[srcW];
        asifgRowIdx = new int[srcH];   asifgRowW = new float[srcH];
        for (int x = 0; x < srcW; x++) {
            float fx = (float) x / blockW - 0.5f;
            int c0 = Math.max(0, Math.min(ASIFG_GRID_COLS - 2, (int) fx));
            asifgColIdx[x] = c0;
            asifgColW[x]   = Math.max(0f, Math.min(1f, fx - c0));
        }
        for (int y = 0; y < srcH; y++) {
            float fy = (float) y / blockH - 0.5f;
            int r0 = Math.max(0, Math.min(ASIFG_GRID_ROWS - 2, (int) fy));
            asifgRowIdx[y] = r0;
            asifgRowW[y]   = Math.max(0f, Math.min(1f, fy - r0));
        }

        cache.put(KEY_UPSCALE_A,       new byte[tSize]);
        cache.put(KEY_UPSCALE_B,       new byte[tSize]);
        cache.put(KEY_UPSCALE_C,       new byte[tSize]);
        cache.put(KEY_UPSCALE_D,       new byte[tSize]);
        cache.put(KEY_UPSCALE_E,       new byte[tSize]);
        cache.put(KEY_UPSCALE_F,       new byte[tSize]);
        cache.put(KEY_UPSCALE_G,       new byte[tSize]);
        cache.put(KEY_UPSCALE_H,       new byte[tSize]);
        cache.put(KEY_PREDICT_FRAME,   new byte[tSize]);
        cache.put(KEY_GPU_BLEND_COPY_A,new byte[tSize]);
        cache.put(KEY_GPU_BLEND_COPY_B,new byte[tSize]);

        ByteBuffer direct = ByteBuffer.allocateDirect(tSize);
        direct.order(ByteOrder.nativeOrder());
        cache.put(KEY_DIRECT_BUFFER, direct);

        recycleBitmap(KEY_RENDER_BITMAP);
        recycleBitmap(KEY_RENDER_BITMAP_SRC);
        cache.remove(KEY_DIRECT_BUFFER_SRC);
    }

    public synchronized void release() {
        recycleBitmap(KEY_RENDER_BITMAP);
        recycleBitmap(KEY_RENDER_BITMAP_SRC);
        recycleBitmap(KEY_RENDER_BITMAP_SRC_B);
        cache.clear();
    }

    public Object get(String key) { return cache.get(key); }
    public void   put(String key, Object value) { cache.put(key, value); }
    public void   remove(String key) { cache.remove(key); }

    private void recycleBitmap(String key) {
        Bitmap b = (Bitmap) cache.get(key);
        if (b != null && !b.isRecycled()) b.recycle();
        cache.remove(key);
    }
}
