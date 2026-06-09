package com.TuringSoftware.FrameGenerator.service.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import static com.TuringSoftware.FrameGenerator.AppConstants.TAG;

/**
 * Aplica distorsión radial (corrección o exageración de ojo de pez)
 * mediante una malla {@code drawBitmapMesh} precalculada.
 *
 * <p>La malla se recalcula solo si cambian el factor o las dimensiones —
 * en el caso típico es un array float[] reutilizado sin allocaciones.
 */
public class FisheyeCorrector {

    private final int targetWidth, targetHeight;
    private final Paint paint;

    private float[] verts;
    private int lastFactor = Integer.MIN_VALUE;
    private int lastW      = -1;
    private int lastH      = -1;

    private static final int GRID_SIZE = 20;

    public FisheyeCorrector(int tgtW, int tgtH, Paint fisheyePaint) {
        this.targetWidth  = tgtW;
        this.targetHeight = tgtH;
        this.paint        = fisheyePaint;
    }

    public void invalidate() {
        lastFactor = Integer.MIN_VALUE;
        verts      = null;
    }

    public void apply(Canvas canvas, Bitmap bitmap, int fisheyeCorrection) {
        if (bitmap == null || bitmap.isRecycled()) return;
        ensureVerts(fisheyeCorrection);
        canvas.drawBitmapMesh(bitmap, GRID_SIZE, GRID_SIZE, verts, 0, null, 0, paint);
    }

    private void ensureVerts(int factor) {
        if (verts != null && lastFactor == factor
                && lastW == targetWidth && lastH == targetHeight) return;

        int vertices = (GRID_SIZE + 1) * (GRID_SIZE + 1);
        verts = new float[vertices * 2];
        float cx = targetWidth / 2f, cy = targetHeight / 2f;
        float maxR = (float) Math.sqrt(cx * cx + cy * cy);
        float f = factor / 100f;
        int idx = 0;
        for (int gy = 0; gy <= GRID_SIZE; gy++) {
            for (int gx = 0; gx <= GRID_SIZE; gx++) {
                float gridX = gx * targetWidth  / (float) GRID_SIZE;
                float gridY = gy * targetHeight / (float) GRID_SIZE;
                float dx = gridX - cx, dy = gridY - cy;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > 0f) {
                    float norm      = dist / maxR;
                    float corrected = norm * (1f + f * norm * norm) * maxR;
                    float angle     = (float) Math.atan2(dy, dx);
                    verts[idx * 2]     = cx + corrected * (float) Math.cos(angle);
                    verts[idx * 2 + 1] = cy + corrected * (float) Math.sin(angle);
                } else {
                    verts[idx * 2]     = gridX;
                    verts[idx * 2 + 1] = gridY;
                }
                idx++;
            }
        }
        lastFactor = factor;
        lastW = targetWidth; lastH = targetHeight;
    }
}
