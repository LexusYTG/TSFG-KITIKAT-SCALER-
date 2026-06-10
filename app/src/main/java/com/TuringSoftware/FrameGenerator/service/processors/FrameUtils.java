package com.TuringSoftware.FrameGenerator.service.processors;

import java.util.Arrays;
import com.TuringSoftware.FrameGenerator.service.processors.GFaLFlowNet;
import com.TuringSoftware.FrameGenerator.service.processors.TileFreezeDetector;

public final class FrameUtils {

    private FrameUtils() {}

    // ── Offsets reutilizados (static final → cero allocaciones) ──────────────

    public static final int[][] GLOBAL_MOTION_OFFSETS = {
        {0,0},{4,0},{-4,0},{0,4},{0,-4},{3,3},{-3,3},{3,-3},{-3,-3}
    };

    public static final int[][] ULTRA_LIGHT_CANDS = {
        {-3,-3},{0,-3},{3,-3},
        {-3, 0},{0, 0},{3, 0},
        {-3, 3},{0, 3},{3, 3}
    };

    // ── Blend 50/50 ───────────────────────────────────────────────────────────

    public static void pureBlendFrames(byte[] a, byte[] b, byte[] out) {
        int len = Math.min(Math.min(a.length, b.length), out.length);
        int i = 0;
        for (; i + 7 < len; i += 8) {
            out[i]     = (byte)(((a[i]     & 0xFF) + (b[i]     & 0xFF)) >> 1);
            out[i + 1] = (byte)(((a[i + 1] & 0xFF) + (b[i + 1] & 0xFF)) >> 1);
            out[i + 2] = (byte)(((a[i + 2] & 0xFF) + (b[i + 2] & 0xFF)) >> 1);
            out[i + 3] = (byte) 255;
            out[i + 4] = (byte)(((a[i + 4] & 0xFF) + (b[i + 4] & 0xFF)) >> 1);
            out[i + 5] = (byte)(((a[i + 5] & 0xFF) + (b[i + 5] & 0xFF)) >> 1);
            out[i + 6] = (byte)(((a[i + 6] & 0xFF) + (b[i + 6] & 0xFF)) >> 1);
            out[i + 7] = (byte) 255;
        }
        for (; i + 3 < len; i += 4) {
            out[i]     = (byte)(((a[i]     & 0xFF) + (b[i]     & 0xFF)) >> 1);
            out[i + 1] = (byte)(((a[i + 1] & 0xFF) + (b[i + 1] & 0xFF)) >> 1);
            out[i + 2] = (byte)(((a[i + 2] & 0xFF) + (b[i + 2] & 0xFF)) >> 1);
            out[i + 3] = (byte) 255;
        }
    }

    // ── Traslación de frame ───────────────────────────────────────────────────

    public static void translateFrame(byte[] src, byte[] dst, int dx, int dy, int w, int h) {
        final int stride = w * 4;
        Arrays.fill(dst, (byte) 0);
        int srcY0 = dy < 0 ? -dy : 0;
        int srcY1 = dy < 0 ? h   : h - dy;
        if (srcY0 >= srcY1) return;
        int srcX0 = dx < 0 ? -dx : 0;
        int srcX1 = dx < 0 ? w   : w - dx;
        if (srcX0 >= srcX1) return;
        final int copyBytes = (srcX1 - srcX0) * 4;
        final int srcXOff   = srcX0 * 4;
        final int dstXOff   = (srcX0 + dx) * 4;
        for (int sy = srcY0; sy < srcY1; sy++) {
            System.arraycopy(src, sy * stride + srcXOff,
                             dst, (sy + dy) * stride + dstXOff, copyBytes);
        }
    }

    // ── Muestreo bilineal ─────────────────────────────────────────────────────

    public static int[] bilinearSample(byte[] frame, float xF, float yF,
                                       int stride, int len, int w, int h) {
        int   x0  = (int) xF, y0 = (int) yF;
        float fxA = xF - x0, fyA = yF - y0;
        int   r = 0, g = 0, b = 0;
        float wSum = 0f;
        for (int ky = 0; ky <= 1; ky++) {
            float wy = (ky == 0) ? (1f - fyA) : fyA;
            int   ny = y0 + ky;
            if (ny < 0 || ny >= h) continue;
            for (int kx = 0; kx <= 1; kx++) {
                float wx = (kx == 0) ? (1f - fxA) : fxA;
                int   nx = x0 + kx;
                if (nx < 0 || nx >= w) continue;
                int   idx = ny * stride + nx * 4;
                if (idx + 2 >= len) continue;
                float ww = wy * wx;
                r += (int)(ww * (frame[idx]     & 0xFF));
                g += (int)(ww * (frame[idx + 1] & 0xFF));
                b += (int)(ww * (frame[idx + 2] & 0xFF));
                wSum += ww;
            }
        }
        if (wSum < 0.001f) {
            int cx = Math.max(0, Math.min(w - 1, x0));
            int cy = Math.max(0, Math.min(h - 1, y0));
            int idx = cy * stride + cx * 4;
            r = (idx + 2 < len) ? (frame[idx]     & 0xFF) : 0;
            g = (idx + 2 < len) ? (frame[idx + 1] & 0xFF) : 0;
            b = (idx + 2 < len) ? (frame[idx + 2] & 0xFF) : 0;
            wSum = 1f;
        }
        return new int[]{
            Math.min(255, (int)(r / wSum)),
            Math.min(255, (int)(g / wSum)),
            Math.min(255, (int)(b / wSum))
        };
    }

    // ── Estimación de movimiento global ───────────────────────────────────────

    public static int[] estimateGlobalMotion(byte[] frameA, byte[] frameB,
                                             int srcW, int srcH) {
        if (frameA == null || frameB == null) return new int[]{0, 0};
        int stride = srcW * 4, lenA = frameA.length, lenB = frameB.length;
        int[] sampleX = new int[12], sampleY = new int[12];
        int n = 0;
        for (int row = 1; row <= 3; row++)
            for (int col = 1; col <= 4; col++) {
                sampleX[n] = col * srcW / 5; sampleY[n] = row * srcH / 4; n++;
            }
        int[] dxS = new int[12], dyS = new int[12]; int valid = 0;
        for (int pt = 0; pt < 12; pt++) {
            int cx = sampleX[pt], cy = sampleY[pt];
            if (cx < 4 || cy < 4 || cx >= srcW - 4 || cy >= srcH - 4) continue;
            int refBase = cy * stride + cx * 4;
            int bestSAD = Integer.MAX_VALUE, bestDx = 0, bestDy = 0;
            for (int[] off : GLOBAL_MOTION_OFFSETS) {
                int nx = cx + off[0], ny = cy + off[1];
                if (nx < 0 || ny < 0 || nx >= srcW || ny >= srcH) continue;
                int sad = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int aRO = dy * stride, bRO = (ny + dy) * stride;
                    for (int dx = -1; dx <= 1; dx++) {
                        int aIdx = refBase + aRO + dx * 4 + 1;
                        int bIdx = bRO + (nx + dx) * 4 + 1;
                        sad += (aIdx < 0 || aIdx >= lenA || bIdx < 0 || bIdx >= lenB)
                            ? 255
                            : Math.abs((frameA[aIdx] & 0xFF) - (frameB[bIdx] & 0xFF));
                    }
                }
                if (sad < bestSAD) { bestSAD = sad; bestDx = off[0]; bestDy = off[1]; }
            }
            if (bestSAD < 15 || bestSAD > 900) continue;
            dxS[valid] = bestDx; dyS[valid] = bestDy; valid++;
        }
        if (valid == 0) return new int[]{0, 0};
        sortInPlace(dxS, valid); sortInPlace(dyS, valid);
        return new int[]{dxS[valid / 2], dyS[valid / 2]};
    }

    public static int[] estimateMotionUltraLight(byte[] prevSrc, byte[] curSrc,
                                                 int srcW, int srcH) {
        int stride = srcW * 4, lenA = prevSrc.length, lenB = curSrc.length;
        int W = srcW, H = srcH;
        int[] sx = {W/4, W/2, W*3/4, W/4, W/2, W*3/4};
        int[] sy = {H/3, H/3, H/3, H*2/3, H*2/3, H*2/3};
        float[] dxSamples = new float[6], dySamples = new float[6];
        int validCount = 0;
        for (int pt = 0; pt < 6; pt++) {
            int cx = sx[pt], cy = sy[pt];
            float grad = 0f;
            int refBase = cy * stride + cx * 4;
            if (refBase + 3 < lenA) {
                for (int ch = 0; ch < 3; ch++) {
                    int ref = prevSrc[refBase + ch] & 0xFF;
                    if (cx > 0)   grad += Math.abs(ref - (prevSrc[refBase - 4    + ch] & 0xFF));
                    if (cx < W-1) grad += Math.abs(ref - (prevSrc[refBase + 4    + ch] & 0xFF));
                    if (cy > 0)   grad += Math.abs(ref - (prevSrc[refBase - stride + ch] & 0xFF));
                    if (cy < H-1) grad += Math.abs(ref - (prevSrc[refBase + stride + ch] & 0xFF));
                }
            }
            if (grad < 12f) continue;
            int bestSAD = Integer.MAX_VALUE, bestDx = 0, bestDy = 0, prevSAD0 = Integer.MAX_VALUE;
            for (int[] c : ULTRA_LIGHT_CANDS) {
                int nx = cx + c[0], ny = cy + c[1];
                if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                int bBase = ny * stride + nx * 4;
                if (bBase + 3 >= lenB) continue;
                int sad = 0;
                for (int ch = 0; ch < 3; ch++)
                    sad += Math.abs((prevSrc[refBase + ch] & 0xFF) - (curSrc[bBase + ch] & 0xFF));
                if (c[0] == 0 && c[1] == 0) prevSAD0 = sad;
                if (sad < bestSAD) { bestSAD = sad; bestDx = c[0]; bestDy = c[1]; }
            }
            if (bestSAD > 200 || (bestSAD == prevSAD0 && bestDx == 0 && bestDy == 0 && bestSAD < 6)) continue;
            float dxSub = 0f;
            {
                int nxm = cx + bestDx - 1, nxp = cx + bestDx + 1;
                if (nxm >= 0 && nxp < W) {
                    int bm = cy * stride + nxm * 4, bp = cy * stride + nxp * 4;
                    if (bm + 2 < lenB && bp + 2 < lenB) {
                        int sm = 0, sp = 0;
                        for (int ch = 0; ch < 3; ch++) {
                            sm += Math.abs((prevSrc[refBase + ch] & 0xFF) - (curSrc[bm + ch] & 0xFF));
                            sp += Math.abs((prevSrc[refBase + ch] & 0xFF) - (curSrc[bp + ch] & 0xFF));
                        }
                        int denom = sm + sp - 2 * bestSAD;
                        if (denom > 0) dxSub = Math.max(-0.5f, Math.min(0.5f, (float)(sm - sp) / (2f * denom)));
                    }
                }
            }
            dxSamples[validCount] = bestDx + dxSub;
            dySamples[validCount] = bestDy;
            validCount++;
        }
        if (validCount == 0) return new int[]{0, 0};
        sortFloatInPlace(dxSamples, validCount);
        sortFloatInPlace(dySamples, validCount);
        return new int[]{Math.round(dxSamples[validCount / 2]), Math.round(dySamples[validCount / 2])};
    }

    // ── Sorts ─────────────────────────────────────────────────────────────────

    public static void sortInPlace(int[] arr, int len) {
        for (int i = 1; i < len; i++) {
            int key = arr[i], j = i - 1;
            while (j >= 0 && arr[j] > key) { arr[j + 1] = arr[j]; j--; }
            arr[j + 1] = key;
        }
    }

    public static void sortFloatInPlace(float[] arr, int len) {
        for (int i = 1; i < len; i++) {
            float key = arr[i]; int j = i - 1;
            while (j >= 0 && arr[j] > key) { arr[j + 1] = arr[j]; j--; }
            arr[j + 1] = key;
        }
    }

    // ── GFaL v1 — Generación de Fotogramas por Análisis de Líneas ────────────

    public static final int GFAL_COLS = 16;
    public static final int GFAL_ROWS = 7;

    // ── GFaL — Freeze-aware flow extraction ──────────────────────────────────

    /**
     * Versión original sin freeze (compatibilidad hacia atrás).
     * Delega a la versión con freeze pasando {@code null}.
     */
    public static void gfalExtractFlow(byte[] frameA, byte[] frameB,
                                       int w, int h, int searchRadius,
                                       float[] outDx, float[] outDy) {
        gfalExtractFlow(frameA, frameB, w, h, searchRadius, outDx, outDy, null);
    }

    /**
     * Extrae el campo de movimiento por tile usando líneas de muestreo GFaL.
     *
     * <p><b>Mejora Tile Freeze:</b> si {@code freeze} no es null y el tile está
     * congelado ({@link TileFreezeDetector#isFrozen}), se reutiliza el vector
     * anterior y se salta el block-matching, ahorrando CPU.
     *
     * @param freeze  detector de congelamiento (puede ser {@code null} para desactivar)
     */
    public static void gfalExtractFlow(byte[] frameA, byte[] frameB,
                                       int w, int h, int searchRadius,
                                       float[] outDx, float[] outDy,
                                       TileFreezeDetector freeze) {
        final int stride  = w * 4;
        final int lenA    = frameA.length;
        final int lenB    = frameB.length;
        final int sW1     = w - 1;
        final int sH1     = h - 1;
        final int PATCH_R = 1;

        for (int row = 0; row < GFAL_ROWS; row++) {
            int cy = (row + 1) * h / (GFAL_ROWS + 1);
            if (cy > sH1) cy = sH1;

            for (int col = 0; col < GFAL_COLS; col++) {
                int tileIdx = row * GFAL_COLS + col;

                // ── Tile Freeze: si no cambió en últimos N frames, reutilizar flow ──
                if (freeze != null && freeze.isFrozen(tileIdx)) {
                    // outDx/outDy ya tienen el vector del frame anterior → no tocar
                    // SAD y grad quedan en sus últimos valores válidos → OK para refineFlow
                    continue;
                }

                int cx = (col + 1) * w / (GFAL_COLS + 1);
                if (cx > sW1) cx = sW1;

                int gBase = cy * stride + cx * 4 + 1; // canal G
                int grad = 0;
                if (cx > 0)   grad += Math.abs((frameA[gBase] & 0xFF) - (frameA[gBase - 4]      & 0xFF));
                if (cx < sW1) grad += Math.abs((frameA[gBase] & 0xFF) - (frameA[gBase + 4]      & 0xFF));
                if (cy > 0)   grad += Math.abs((frameA[gBase] & 0xFF) - (frameA[gBase - stride] & 0xFF));
                if (cy < sH1) grad += Math.abs((frameA[gBase] & 0xFF) - (frameA[gBase + stride] & 0xFF));

                if (grad < 10) {
                    outDx[tileIdx] = 0f;
                    outDy[tileIdx] = 0f;
                    gfalSadNorm [tileIdx] = 1f; // sin textura → SAD máximo conceptual
                    gfalGradNorm[tileIdx] = 0f;
                    if (freeze != null) freeze.recordSad(tileIdx, 0);
                    continue;
                }

                int bestSAD = Integer.MAX_VALUE;
                int bestDx  = 0, bestDy = 0;

                int clampedSR = Math.min(searchRadius, Math.min(cx, Math.min(cy, Math.min(sW1 - cx, sH1 - cy))));

                for (int sdy = -clampedSR; sdy <= clampedSR; sdy++) {
                    int bcy = cy + sdy;
                    for (int sdx = -clampedSR; sdx <= clampedSR; sdx++) {
                        int bcx = cx + sdx;
                        int sad = 0;
                        for (int ky = -PATCH_R; ky <= PATCH_R; ky++) {
                            int ay = cy + ky; if (ay < 0) ay = 0; else if (ay > sH1) ay = sH1;
                            int by = bcy + ky; if (by < 0) by = 0; else if (by > sH1) by = sH1;
                            int aRow = ay * stride;
                            int bRow = by * stride;
                            for (int kx = -PATCH_R; kx <= PATCH_R; kx++) {
                                int ax = cx + kx; if (ax < 0) ax = 0; else if (ax > sW1) ax = sW1;
                                int bx = bcx + kx; if (bx < 0) bx = 0; else if (bx > sW1) bx = sW1;
                                int idxA = aRow + ax * 4 + 1;
                                int idxB = bRow + bx * 4 + 1;
                                if (idxA < lenA && idxB < lenB)
                                    sad += Math.abs((frameA[idxA] & 0xFF) - (frameB[idxB] & 0xFF));
                            }
                        }
                        if (sad < bestSAD) {
                            bestSAD = sad;
                            bestDx  = sdx;
                            bestDy  = sdy;
                            if (sad == 0) break;
                        }
                    }
                    if (bestSAD == 0) break;
                }

                // Registrar SAD en el detector de freeze
                if (freeze != null) freeze.recordSad(tileIdx, bestSAD == Integer.MAX_VALUE ? 9999 : bestSAD);

                // Guardar métricas para gfalRefineFlow — costo cero, ya tenemos los valores
                gfalSadNorm [tileIdx] = (bestSAD == Integer.MAX_VALUE)
                    ? 1f : Math.min(1f, bestSAD / (9f * 255f));
                gfalGradNorm[tileIdx] = Math.min(1f, grad / (4f * 255f));

                if (bestSAD > 9 * 200) {
                    outDx[tileIdx] = 0f;
                    outDy[tileIdx] = 0f;
                } else {
                    outDx[tileIdx] = bestDx;
                    outDy[tileIdx] = bestDy;
                }
            }
        }
    }

    // ── GFaL — Border Blending con sub-tiles ─────────────────────────────────

    /**
     * Construye el frame predicho C a partir de B extrapolando con el flow A→B.
     *
     * <p><b>Mejora Border Blending:</b> En lugar de un salto abrupto entre tiles
     * vecinos, cada píxel recibe su vector de flow por interpolación bilineal entre
     * los 4 tiles más cercanos del grid. Esto equivale a subdividir cada tile en
     * sub-tiles y promediar los vectores con peso lineal decreciente según la
     * distancia al centro del tile, eliminando el efecto de "bloques" visible en
     * la imagen generada.
     *
     * <p>La fórmula es la misma que antes pero con coordenadas de grid centradas
     * en los tiles (no en sus esquinas), lo que produce pesos suaves hacia los
     * bordes y esquinas compartidas.
     *
     * @param t  factor de interpolación temporal en [0,1]. Proporcional a la
     *           magnitud del flow: si hay mucho movimiento t≈1, si hay poco t≈0.
     *           Calcular con {@link #gfalComputeT} y modular con touchVelFactor.
     */
    public static void gfalBuildPrediction(byte[] frameB, byte[] out,
                                           float[] flowDx, float[] flowDy,
                                           int w, int h, float t) {
        final int stride = w * 4;
        final int len    = Math.min(frameB.length, out.length);
        final int sW1    = w - 1;
        final int sH1    = h - 1;

        // Espaciado entre tiles en píxeles
        final float tileSpacingX = (float) w  / (GFAL_COLS + 1);
        final float tileSpacingY = (float) h  / (GFAL_ROWS + 1);

        for (int y = 0; y < h; y++) {
            // Coordenada en espacio de tiles (0 = antes del primer tile, GFAL_ROWS = pasado el último)
            // Centramos respecto al centro de cada tile
            float gridY = (float) y / tileSpacingY - 1f;
            int   r0    = (int) gridY;
            float ty    = gridY - r0;

            // Clamp con suavizado en bordes extremos
            if (r0 < 0)              { r0 = 0; ty = 0f; }
            if (r0 >= GFAL_ROWS - 1) { r0 = GFAL_ROWS - 2; ty = 1f; }
            int r1 = r0 + 1;

            // ── Sub-tile blending: peso smoothstep en Y para suavizar bordes ──
            // smoothstep(t) = t²(3-2t) — más suave que lineal en los bordes de tile
            float ty_s = ty * ty * (3f - 2f * ty);
            float ity_s = 1f - ty_s;

            int yBase = y * stride;

            for (int x = 0; x < w; x++) {
                float gridX = (float) x / tileSpacingX - 1f;
                int   c0    = (int) gridX;
                float tx    = gridX - c0;

                if (c0 < 0)              { c0 = 0; tx = 0f; }
                if (c0 >= GFAL_COLS - 1) { c0 = GFAL_COLS - 2; tx = 1f; }
                int c1 = c0 + 1;

                // ── Sub-tile blending: smoothstep en X ────────────────────────
                float tx_s  = tx * tx * (3f - 2f * tx);
                float itx_s = 1f - tx_s;

                int i00 = r0 * GFAL_COLS + c0;
                int i10 = r0 * GFAL_COLS + c1;
                int i01 = r1 * GFAL_COLS + c0;
                int i11 = r1 * GFAL_COLS + c1;

                // Interpolación bilineal con pesos smoothstep → transición suave
                float dx = flowDx[i00] * itx_s * ity_s + flowDx[i10] * tx_s * ity_s
                    + flowDx[i01] * itx_s * ty_s  + flowDx[i11] * tx_s * ty_s;
                float dy = flowDy[i00] * itx_s * ity_s + flowDy[i10] * tx_s * ity_s
                    + flowDy[i01] * itx_s * ty_s  + flowDy[i11] * tx_s * ty_s;

                // Backward warp con t dinámico:
                //   - negativo: buscamos de dónde viene el píxel (backward warp)
                //   - t: proporcional al movimiento — mucho movimiento → t alto → interpola más
                //        poco movimiento → t bajo → interpola poco (evita artefactos)
                int srcX = x + (int)(-dx * t + 0.5f);
                int srcY = y + (int)(-dy * t + 0.5f);
                if (srcX < 0) srcX = 0; else if (srcX > sW1) srcX = sW1;
                if (srcY < 0) srcY = 0; else if (srcY > sH1) srcY = sH1;

                int srcIdx = srcY * stride + srcX * 4;
                int dstIdx = yBase + x * 4;

                if (srcIdx + 3 < len && dstIdx + 3 < len) {
                    out[dstIdx]     = frameB[srcIdx];
                    out[dstIdx + 1] = frameB[srcIdx + 1];
                    out[dstIdx + 2] = frameB[srcIdx + 2];
                    out[dstIdx + 3] = (byte) 255;
                }
            }
        }
    }

    // ── GFaL — cálculo de t proporcional al movimiento ────────────────────────
    /**
     * Calcula el factor de interpolación temporal t ∈ [0, 1] proporcional
     * a la magnitud media del flow.
     *
     * <p>Lógica: si el movimiento medio es grande, el frame predicho debe
     * desplazarse proporcionalmente (mucho movimiento → t alto). Si hay poco
     * movimiento, interpolar poco evita artefactos (t bajo).
     *
     * <p>Fórmula: {@code t = clamp(avgMag / searchRadius, 0, 1)}
     *
     * @param flowDx       vectores dx del campo de movimiento
     * @param flowDy       vectores dy del campo de movimiento
     * @param searchRadius radio de búsqueda usado en gfalExtractFlow (píxeles)
     * @return t en [0, 1]
     */
    public static float gfalComputeT(float[] flowDx, float[] flowDy, int searchRadius) {
        final int n = GFAL_COLS * GFAL_ROWS;
        float sumMag = 0f;
        int   count  = 0;
        for (int i = 0; i < n; i++) {
            float dx = flowDx[i], dy = flowDy[i];
            if (dx == 0f && dy == 0f) continue; // tiles estáticos no contribuyen
            sumMag += (float) Math.sqrt(dx * dx + dy * dy);
            count++;
        }
        if (count == 0 || searchRadius <= 0) return 0f;
        float t = (sumMag / count) / searchRadius;
        return t > 1f ? 1f : t;
    }

    // ── GFaL — refinamiento de flow con micro red neuronal ───────────────────
    public static final float[] gfalSadNorm  = new float[GFAL_COLS * GFAL_ROWS];
    public static final float[] gfalGradNorm = new float[GFAL_COLS * GFAL_ROWS];
    public static void gfalRefineFlow(float[] flowDx, float[] flowDy,
                                      float alpha,
                                      float[] hidden, float[] delta) {
        if (alpha <= 0f) return;
        final float a = alpha > 1f ? 1f : alpha;

        for (int row = 0; row < GFAL_ROWS; row++) {
            for (int col = 0; col < GFAL_COLS; col++) {
                int idx = row * GFAL_COLS + col;

                float dxLeft = col > 0 ? flowDx[idx - 1]          : 0f;
                float dyLeft = col > 0 ? flowDy[idx - 1]          : 0f;
                float dxUp   = row > 0 ? flowDx[idx - GFAL_COLS]  : 0f;
                float dyUp   = row > 0 ? flowDy[idx - GFAL_COLS]  : 0f;

                GFaLFlowNet.infer(
                    flowDx[idx], flowDy[idx],
                    gfalSadNorm[idx], gfalGradNorm[idx],
                    dxLeft, dyLeft,
                    dxUp,   dyUp,
                    delta, hidden
                );

                flowDx[idx] += a * delta[0];
                flowDy[idx] += a * delta[1];
            }
        }
    }
}

