package com.TuringSoftware.FrameGenerator.service.buffers;

import static com.TuringSoftware.FrameGenerator.service.buffers.BufferPool.*;

/**
 * Upscaling nearest-neighbor con ping-pong de buffers.
 *
 * <p>Opera sobre un {@link BufferPool} compartido y las dimensiones
 * fuente/destino que se le pasan en construcción. Utiliza mapas de
 * coordenadas precalculados (KEY_XMAP / KEY_YMAP) para evitar floats
 * y multiplicaciones en el hot loop.
 */
public class NearestNeighborUpscaler {

    private final BufferPool pool;
    private final int sourceWidth, sourceHeight;
    private final int targetWidth, targetHeight;

    private boolean useUpscaleA       = true;
    private boolean upscaleOrderedHead_idx = false; // no usado aquí, ver ordered pool
    private int     orderedHead       = 0;

    public NearestNeighborUpscaler(BufferPool pool,
                                   int srcW, int srcH,
                                   int tgtW, int tgtH) {
        this.pool         = pool;
        this.sourceWidth  = srcW; this.sourceHeight = srcH;
        this.targetWidth  = tgtW; this.targetHeight = tgtH;
    }

    public byte[] upscale(byte[] source) {
        if (source == null) return null;
        int[] xMap = (int[]) pool.get(KEY_XMAP);
        int[] yMap = (int[]) pool.get(KEY_YMAP);
        if (xMap == null || yMap == null) return null;

        byte[] dest = useUpscaleA
            ? (byte[]) pool.get(KEY_UPSCALE_A)
            : (byte[]) pool.get(KEY_UPSCALE_B);
        useUpscaleA = !useUpscaleA;
        return fillDest(source, dest, xMap, yMap);
    }

    public byte[] upscaleForOrdered(byte[] source) {
        if (source == null) return null;
        int[] xMap = (int[]) pool.get(KEY_XMAP);
        int[] yMap = (int[]) pool.get(KEY_YMAP);
        if (xMap == null || yMap == null) return null;

        String key = UPSCALE_ORDERED_POOL[orderedHead];
        orderedHead = (orderedHead + 1) % UPSCALE_ORDERED_POOL.length;

        byte[] dest = (byte[]) pool.get(key);
        return fillDest(source, dest, xMap, yMap);
    }

    public void precalculateMappings() {
        int[] xMap = new int[targetWidth];
        int[] yMap = new int[targetHeight];
        float invX = (float) sourceWidth  / targetWidth;
        float invY = (float) sourceHeight / targetHeight;
        for (int x = 0; x < targetWidth;  x++) xMap[x] = Math.min((int)(x * invX), sourceWidth  - 1);
        for (int y = 0; y < targetHeight; y++) yMap[y] = Math.min((int)(y * invY), sourceHeight - 1);
        pool.put(KEY_XMAP, xMap);
        pool.put(KEY_YMAP, yMap);
    }

    // ── Implementación interna ─────────────────────────────────────────────────

    private byte[] fillDest(byte[] source, byte[] dest, int[] xMap, int[] yMap) {
        if (dest == null) return null;
        final int srcStride = sourceWidth * 4;
        final int dstStride = targetWidth * 4;
        final int dstLen    = dest.length;
        int lastSrcRow  = -1;
        int lastDstBase = -1;

        for (int y = 0; y < targetHeight; y++) {
            int srcRow     = yMap[y];
            int dstRowBase = y * dstStride;
            if (srcRow == lastSrcRow && lastDstBase >= 0
                    && dstRowBase + dstStride <= dstLen
                    && lastDstBase + dstStride <= dstLen) {
                System.arraycopy(dest, lastDstBase, dest, dstRowBase, dstStride);
                continue;
            }
            int srcRowBase = srcRow * srcStride;
            for (int x = 0; x < targetWidth; x++) {
                int srcIdx = srcRowBase + xMap[x] * 4;
                int dstIdx = dstRowBase + x * 4;
                dest[dstIdx]     = source[srcIdx];
                dest[dstIdx + 1] = source[srcIdx + 1];
                dest[dstIdx + 2] = source[srcIdx + 2];
                dest[dstIdx + 3] = (byte) 255;
            }
            lastSrcRow  = srcRow;
            lastDstBase = dstRowBase;
        }
        return dest;
    }
}
