package com.TuringSoftware.FrameGenerator.service.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;
import android.view.SurfaceView;
import java.nio.ByteBuffer;

import static com.TuringSoftware.FrameGenerator.AppConstants.*;
import com.TuringSoftware.FrameGenerator.service.buffers.BufferPool;

/**
 * Responsable de dibujar frames (byte[] o Bitmap) sobre el SurfaceView del overlay.
 *
 * <p>Extraído de {@code ScreenCaptureService} para que ese archivo deje de ser
 * titánico. Contiene:
 * <ul>
 *   <li>{@link #renderBytes} — convierte byte[] a Bitmap y dibuja.</li>
 *   <li>{@link #renderBitmap} — dibuja directamente un Bitmap (modo Rendimiento).</li>
 *   <li>Corrección fisheye delegada a {@link FisheyeCorrector}.</li>
 * </ul>
 */
public class SurfaceRenderer {

    private final BufferPool pool;
    private final int sourceWidth, sourceHeight;
    private final int targetWidth, targetHeight;
    private final Paint bitmapPaint;

    private SurfaceView overlayView;
    private volatile boolean isSurfaceValid = false;

    private FisheyeCorrector fisheyeCorrector;
    private int fisheyeCorrection = 0;
    private int captureMode;
    private int topInset, bottomInset;

    private android.graphics.Rect cachedDstRect = null;
    private android.graphics.Rect cachedSrcRect = null;
    private int cachedSrcW = -1, cachedSrcH = -1, cachedTop = Integer.MIN_VALUE, cachedBot = Integer.MIN_VALUE;

    public long lastRenderTime = 0;

    public SurfaceRenderer(BufferPool pool,
                           int srcW, int srcH, int tgtW, int tgtH) {
        this.pool         = pool;
        this.sourceWidth  = srcW;  this.sourceHeight = srcH;
        this.targetWidth  = tgtW;  this.targetHeight = tgtH;

        bitmapPaint = new Paint();
        bitmapPaint.setAntiAlias(false);
        bitmapPaint.setFilterBitmap(true);
        bitmapPaint.setDither(false);
        bitmapPaint.setAlpha(255);
        bitmapPaint.setXfermode(null);

        Paint fishPaint = new Paint();
        fishPaint.setAntiAlias(false);
        fishPaint.setFilterBitmap(true);
        fishPaint.setDither(false);
        fishPaint.setAlpha(255);
        fisheyeCorrector = new FisheyeCorrector(tgtW, tgtH, fishPaint);
    }

    public void setSurfaceView(SurfaceView sv, boolean valid) {
        this.overlayView    = sv;
        this.isSurfaceValid = valid;
    }
    public void setSurfaceValid(boolean v) { isSurfaceValid = v; }
    public void setFisheyeCorrection(int f) { fisheyeCorrection = f; }
    public void setCaptureMode(int m)        { captureMode = m; }
    public void setInsets(int top, int bot)  { topInset = top; bottomInset = bot; }
    public void invalidateFisheyeCache()     { fisheyeCorrector.invalidate(); cachedSrcRect = null; }

    // ── Render de byte[] (modos SIFg / ASIFg) ────────────────────────────────

    public void renderBytes(byte[] frameData) {
        if (!isSurfaceValid || frameData == null) return;
        int totalPixels = frameData.length / 4;
        int srcW, srcH;
        if      (totalPixels == sourceWidth  * sourceHeight) { srcW = sourceWidth;  srcH = sourceHeight; }
        else if (totalPixels == targetWidth  * targetHeight) { srcW = targetWidth;  srcH = targetHeight; }
        else return;

        android.graphics.Rect srcRect = buildSrcRect(srcW, srcH);
        android.graphics.Rect dstRect = ensureDstRect();

        String bmpKey = (srcW == sourceWidth)
            ? BufferPool.KEY_RENDER_BITMAP_SRC : BufferPool.KEY_RENDER_BITMAP;
        Bitmap bmp = getOrCreateBitmap(bmpKey, srcW, srcH);
        if (bmp == null) return;

        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(frameData));
        drawOnCanvas(bmp, srcRect, dstRect);
    }

    // ── Render de Bitmap (modo Rendimiento — GPU scale) ───────────────────────

    public void renderBitmap(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled() || !isSurfaceValid) return;
        android.graphics.Rect srcRect = null;
        if (captureMode == CAPTURE_MODE_FULLSCREEN && (topInset > 0 || bottomInset > 0)) {
            float scaleY   = (float) sourceHeight / targetHeight;
            int cropTop    = (int)(topInset    * scaleY);
            int cropBottom = sourceHeight - (int)(bottomInset * scaleY);
            cropTop    = Math.max(0, Math.min(cropTop,    sourceHeight - 1));
            cropBottom = Math.max(cropTop + 1, Math.min(cropBottom, sourceHeight));
            if (cropTop > 0 || cropBottom < sourceHeight) {
                srcRect = new android.graphics.Rect(0, cropTop, sourceWidth, cropBottom);
            }
        }
        android.graphics.Rect dstRect = ensureDstRect();
        drawOnCanvas(bmp, srcRect, dstRect);
    }

    // ── Helpers internos ───────────────────────────────────────────────────────

    private void drawOnCanvas(Bitmap bmp, android.graphics.Rect src, android.graphics.Rect dst) {
        if (overlayView == null) return;
        Canvas canvas = null;
        try {
            canvas = overlayView.getHolder().lockCanvas(null);
            if (canvas == null) return;
            if (fisheyeCorrection != 0) {
                Bitmap stretched = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
                Canvas tmp = new Canvas(stretched);
                tmp.drawBitmap(bmp, src, dst, bitmapPaint);
                fisheyeCorrector.apply(canvas, stretched, fisheyeCorrection);
                stretched.recycle();
            } else {
                canvas.drawBitmap(bmp, src, dst, bitmapPaint);
            }
            lastRenderTime = System.currentTimeMillis();
        } catch (Exception e) {
            Log.e(TAG, "Error render", e);
        } finally {
            if (canvas != null) {
                try { overlayView.getHolder().unlockCanvasAndPost(canvas); }
                catch (Exception e) { Log.e(TAG, "Error unlock canvas", e); }
            }
        }
    }

    private android.graphics.Rect buildSrcRect(int srcW, int srcH) {
        if (captureMode == CAPTURE_MODE_FULLSCREEN && (topInset > 0 || bottomInset > 0)) {
            if (cachedSrcRect != null && cachedSrcW == srcW && cachedSrcH == srcH
                    && cachedTop == topInset && cachedBot == bottomInset) {
                return cachedSrcRect;
            }
            float scaleY   = (float) srcH / targetHeight;
            int cropTop    = Math.max(0, Math.min((int)(topInset    * scaleY), srcH - 1));
            int cropBottom = Math.max(cropTop + 1, Math.min(srcH - (int)(bottomInset * scaleY), srcH));
            if (cropTop > 0 || cropBottom < srcH) {
                cachedSrcRect = new android.graphics.Rect(0, cropTop, srcW, cropBottom);
                cachedSrcW = srcW; cachedSrcH = srcH; cachedTop = topInset; cachedBot = bottomInset;
                return cachedSrcRect;
            }
        }
        cachedSrcRect = null;
        return null;
    }

    private android.graphics.Rect ensureDstRect() {
        if (cachedDstRect == null || cachedDstRect.right != targetWidth || cachedDstRect.bottom != targetHeight) {
            cachedDstRect = new android.graphics.Rect(0, 0, targetWidth, targetHeight);
        }
        return cachedDstRect;
    }

    private Bitmap getOrCreateBitmap(String key, int w, int h) {
        Bitmap bmp = (Bitmap) pool.get(key);
        if (bmp == null || bmp.isRecycled() || bmp.getWidth() != w || bmp.getHeight() != h) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            pool.put(key, bmp);
        }
        return bmp;
    }
}
