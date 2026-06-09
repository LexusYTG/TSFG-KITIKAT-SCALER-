package com.TuringSoftware.FrameGenerator.service.processors;

import java.util.Arrays;

/**
 * Detecta si la pantalla está estática comparando hashes djb2 de frames consecutivos.
 *
 * <p>Mantiene un historial circular de {@code HASH_HISTORY} hashes. Si todos son
 * iguales, el frame se considera estático y puede omitirse del pipeline de síntesis.
 */
public class StaticFrameDetector {

    private static final int HASH_HISTORY = 10;

    private final long[] hashHistory = new long[HASH_HISTORY];
    private int hashHead   = 0;
    private int hashFilled = 0;
    private int staticCount = 0;

    public void reset() {
        Arrays.fill(hashHistory, 0L);
        hashHead    = 0;
        hashFilled  = 0;
        staticCount = 0;
    }

    public long calculateHash(byte[] data) {
        if (data == null || data.length == 0) return 0L;
        long hash     = 5381L;
        int  len      = data.length;
        int  step     = len < 1024 ? 4 : (len >> 8);
        int  quarter  = len >> 2;
        for (int zone = 0; zone < 4; zone++) {
            int base = zone * quarter;
            int end  = base + quarter;
            for (int i = base; i < end; i += step) {
                hash = ((hash << 5) + hash) ^ (data[i] & 0xFF);
            }
        }
        return hash;
    }

    public boolean recordAndCheck(long hash) {
        hashHistory[hashHead] = hash;
        hashHead = (hashHead + 1) % HASH_HISTORY;
        if (hashFilled < HASH_HISTORY) hashFilled++;
        if (hashFilled < HASH_HISTORY) return false;
        for (int i = 0; i < HASH_HISTORY; i++) {
            if (hashHistory[i] != hash) return false;
        }
        return true;
    }
}
