package com.TuringSoftware.FrameGenerator.service;

public final class ASIFgNetwork {

    // ── Dimensiones ───────────────────────────────────────────────────────────
    public static final int PATCH = 8;
    public static final int INPUT = PATCH * PATCH * 2;   // 128
    public static final int H1    = 16;
    public static final int H2    =  8;
    public static final int OUT   =  2;                   // dx, dy

    private static final float LEAKY   = 0.1f;
    private static final float TWO_PI  = 2f * 3.14159265358979f;

    // ── Parámetros de la red ──────────────────────────────────────────────────
    public final float[] w1  = new float[INPUT * H1];   // 2 048
    public final float[] b1  = new float[H1];
    public final float[] w2  = new float[H1 * H2];      //   128
    public final float[] b2  = new float[H2];
    public final float[] w3  = new float[H2 * OUT];     //    16
    public final float[] b3  = new float[OUT];

    // ── Momentum SGD — vectores de velocidad ─────────────────────────────────
    private final float[] vw3 = new float[H2 * OUT];
    private final float[] vb3 = new float[OUT];
    private final float[] vw2 = new float[H1 * H2];
    private final float[] vb2 = new float[H2];

    // ── Buffers de activación (pre-alojados, sin GC por frame) ───────────────
    public final float[] inp  = new float[INPUT];
    public final float[] h1a  = new float[H1];
    public final float[] h2a  = new float[H2];
    public final float[] outa = new float[OUT];

    // ── Parámetros de aprendizaje ─────────────────────────────────────────────
    private static final float LR       = 0.001f;  // tasa base
    private static final float MOMENTUM = 0.90f;   // β para SGD con momento
    private static final float CLIP     = 2.0f;    // gradient clipping

    public ASIFgNetwork() {
        initKaiminBoxMuller();
    }


    private void initKaiminBoxMuller() {
        long seed = 0xCAFEBABE1234L;
        final float[] bm = new float[2];

        float std1 = (float) Math.sqrt(2.0 / ((1.0 + LEAKY * LEAKY) * INPUT));
        for (int i = 0; i < w1.length; i += 2) {
            seed = lcg(seed); float u1 = uniformPos(seed);
            seed = lcg(seed); float u2 = uniformPos(seed);
            boxMuller(u1, u2, bm);
            w1[i]           = bm[0] * std1;
            if (i + 1 < w1.length) w1[i + 1] = bm[1] * std1;
        }

        float std2 = (float) Math.sqrt(2.0 / ((1.0 + LEAKY * LEAKY) * H1));
        for (int i = 0; i < w2.length; i += 2) {
            seed = lcg(seed); float u1 = uniformPos(seed);
            seed = lcg(seed); float u2 = uniformPos(seed);
            boxMuller(u1, u2, bm);
            w2[i]           = bm[0] * std2;
            if (i + 1 < w2.length) w2[i + 1] = bm[1] * std2;
        }

        float std3 = (float) Math.sqrt(1.0 / H2);
        for (int i = 0; i < w3.length; i += 2) {
            seed = lcg(seed); float u1 = uniformPos(seed);
            seed = lcg(seed); float u2 = uniformPos(seed);
            boxMuller(u1, u2, bm);
            w3[i]           = bm[0] * std3;
            if (i + 1 < w3.length) w3[i + 1] = bm[1] * std3;
        }

        java.util.Arrays.fill(b1, 0f);
        java.util.Arrays.fill(b2, 0f);
        java.util.Arrays.fill(b3, 0f);
        java.util.Arrays.fill(vw3, 0f);
        java.util.Arrays.fill(vb3, 0f);
        java.util.Arrays.fill(vw2, 0f);
        java.util.Arrays.fill(vb2, 0f);
    }


    public void extractPatch(byte[] frame, int frameW, int frameH,
                             int cx, int cy, float[] buf, int offset) {
        int half   = PATCH / 2;
        int stride = frameW * 4;
        int p      = offset;

        for (int dy = -half; dy < half; dy++) {
            int fy       = clamp(cy + dy, 0, frameH - 1);
            int fyStride = fy * stride;   // hoist multiplicación fuera del bucle X
            for (int dx = -half; dx < half; dx++) {
                int fx  = clamp(cx + dx, 0, frameW - 1);
                int idx = fyStride + fx * 4;
                buf[p++] = (0.299f * (frame[idx]     & 0xFF)
					+ 0.587f * (frame[idx + 1] & 0xFF)
					+ 0.114f * (frame[idx + 2] & 0xFF)) / 255f - 0.5f;
            }
        }
    }

    /**
     * Forward pass. Asume que {@link #inp} ya está relleno con los dos patches normalizados.
     *
     * <p>Activación: Leaky ReLU con α={@value #LEAKY} en capas ocultas.
     *
     * @return Referencia a {@link #outa} = {dx, dy}.
     */
    public float[] forward() {
        final float[] pw1 = w1, pw2 = w2, pw3 = w3;
        final float[] pb1 = b1, pb2 = b2, pb3 = b3;
        final float[] pinp = inp, ph1a = h1a, ph2a = h2a, pouta = outa;

        for (int j = 0; j < H1; j++) {
            float sum = pb1[j];
            int   off = j * INPUT;
            for (int i = 0; i < INPUT; i++) sum += pw1[off + i] * pinp[i];
            ph1a[j] = sum > 0f ? sum : LEAKY * sum;
        }
        for (int j = 0; j < H2; j++) {
            float sum = pb2[j];
            int   off = j * H1;
            for (int i = 0; i < H1; i++) sum += pw2[off + i] * ph1a[i];
            ph2a[j] = sum > 0f ? sum : LEAKY * sum;
        }
        for (int j = 0; j < OUT; j++) {
            float sum = pb3[j];
            int   off = j * H2;
            for (int i = 0; i < H2; i++) sum += pw3[off + i] * ph2a[i];
            pouta[j] = sum;
        }
        return pouta;
    }

    /**
     * Ajuste con SGD + Momentum (backprop truncado, capas 2 + salida).
     * Se llama con el vector de referencia obtenido por block-matching.
     *
     * <p>Gradient clipping: si |err| > {@value #CLIP}, se recorta.
     * Momentum: v = β·v - LR·g;  w += v
     *
     * @param labelDx Vector de referencia horizontal (píxeles, espacio source).
     * @param labelDy Vector de referencia vertical   (píxeles, espacio source).
     */
    public void learnOnline(float labelDx, float labelDy) {
        final float[] pw2 = w2, pw3 = w3;
        final float[] pb2 = b2, pb3 = b3;
        final float[] pvw2 = vw2, pvw3 = vw3, pvb2 = vb2, pvb3 = vb3;
        final float[] ph1a = h1a, ph2a = h2a;

        float eDx = clip(outa[0] - labelDx, CLIP);
        float eDy = clip(outa[1] - labelDy, CLIP);

        // ── Capa de salida (OUT=2, desenrollado manual — elimina rama j==0 por iteración) ──
        {   // j=0, error = eDx
            for (int i = 0; i < H2; i++) {
                float v = MOMENTUM * pvw3[i] - LR * eDx * ph2a[i];
                pvw3[i] = v;  pw3[i] += v;
            }
            float vb = MOMENTUM * pvb3[0] - LR * eDx;
            pvb3[0] = vb; pb3[0] += vb;
        }
        {   // j=1, error = eDy
            for (int i = 0; i < H2; i++) {
                float v = MOMENTUM * pvw3[H2 + i] - LR * eDy * ph2a[i];
                pvw3[H2 + i] = v;  pw3[H2 + i] += v;
            }
            float vb = MOMENTUM * pvb3[1] - LR * eDy;
            pvb3[1] = vb; pb3[1] += vb;
        }

        // ── Capa oculta 2 (Leaky ReLU grad + momentum) ───────────────────────────
        for (int i = 0; i < H2; i++) {
            float actGrad = (ph2a[i] > 0f) ? 1f : LEAKY;
            float g = clip((eDx * pw3[i] + eDy * pw3[H2 + i]) * actGrad, CLIP);
            int   off = i * H1;
            for (int k = 0; k < H1; k++) {
                float gw = g * ph1a[k];
                float v  = MOMENTUM * pvw2[off + k] - LR * gw;
                pvw2[off + k] = v; pw2[off + k] += v;
            }
            float vb = MOMENTUM * pvb2[i] - LR * g;
            pvb2[i] = vb; pb2[i] += vb;
        }
    }


    private static float leakyRelu(float x) {
        return x > 0f ? x : LEAKY * x;
    }

    private static float clip(float x, float limit) {
        return x > limit ? limit : (x < -limit ? -limit : x);
    }

    /**
     * Box-Muller transform: transforma dos uniformes independientes en dos N(0,1).
     * Evita problemas de log(0) garantizando u1 > 0.
     *
     * @param u1 Uniforme en (0,1] (positivo garantizado).
     * @param u2 Uniforme en (0,1].
     * @return Array de 2 muestras N(0,1).
     */
    private static void boxMuller(float u1, float u2, float[] out) {
        float r = (float) Math.sqrt(-2.0 * Math.log(u1));
        float t = TWO_PI * u2;
        out[0] = r * (float) Math.cos(t);
        out[1] = r * (float) Math.sin(t);
    }

    private static long lcg(long s) {
        return (s * 6364136223846793005L + 1442695040888963407L) & 0x7FFFFFFFFFFFFFFFL;
    }

    private static float uniformPos(long s) {
        float u = (float)((s & 0xFFFFFFL) + 1L) / (float)(0xFFFFFFL + 1L);
        return Math.max(1e-7f, Math.min(1f, u));
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}


