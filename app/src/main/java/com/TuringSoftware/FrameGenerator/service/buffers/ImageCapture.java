package com.TuringSoftware.FrameGenerator.service.buffers;

import android.media.Image;
import android.util.Log;
import java.nio.ByteBuffer;

import static com.TuringSoftware.FrameGenerator.AppConstants.TAG;

/**
 * Copia los datos RGBA de un {@link Image} a un byte[].
 *
 * <p>Soporta tres modos de calidad de color:
 * <ul>
 *   <li>0 → Full color (sin reducción)</li>
 *   <li>1 → 5-bit por canal RGB</li>
 *   <li>2 → 3-3-2 bit (256 colores)</li>
 * </ul>
 *
 * <p>Optimizaciones: fast path para pixStride==4 sin padding, fast path por
 * filas, y slow path con bounds hoisted fuera del bucle interno.
 */
public class ImageCapture {

    private final int sourceWidth, sourceHeight;

    public ImageCapture(int srcW, int srcH) {
        this.sourceWidth  = srcW;
        this.sourceHeight = srcH;
    }

    public void captureImageToBuffer(Image image, byte[] dest, int colorQuality) {
        if (image == null || dest == null) return;
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return;

        ByteBuffer buf       = planes[0].getBuffer();
        int        pixStride = planes[0].getPixelStride();
        int        rowStride = planes[0].getRowStride();
        int        rowPad    = rowStride - pixStride * sourceWidth;

        buf.rewind();
        try {
            if (pixStride == 4 && rowPad == 0 && colorQuality == 0) {
                int needed = sourceWidth * sourceHeight * 4;
                if (dest.length >= needed && buf.capacity() >= needed) {
                    buf.get(dest, 0, needed);
                    return;
                }
            }
            if (pixStride == 4 && colorQuality == 0) {
                int rowBytes = sourceWidth * 4;
                int destPos  = 0;
                for (int y = 0; y < sourceHeight; y++) {
                    buf.get(dest, destPos, rowBytes);
                    destPos += rowBytes;
                    if (rowPad > 0) buf.position(buf.position() + rowPad);
                }
                return;
            }
            captureSlowPath(buf, dest, pixStride, rowPad, colorQuality);
        } catch (Exception e) {
            Log.e(TAG, "Error capturando imagen", e);
        }
    }

    private void captureSlowPath(ByteBuffer buf, byte[] dest,
                                 int pixStride, int rowPad, int colorQuality) {
        int h       = sourceHeight, w = sourceWidth;
        int cap     = buf.capacity(), dlen = dest.length;
        int bufPos  = 0, destPos = 0;

        switch (colorQuality) {
            case 1: // 5-bit por canal
                for (int y = 0; y < h; y++) {
                    int rowBufEnd  = bufPos  + w * pixStride;
                    int rowDestEnd = destPos + w * 4;
                    if (rowBufEnd - 1 < cap && rowDestEnd - 1 < dlen) {
                        for (int x = 0; x < w; x++) {
                            dest[destPos]     = (byte)(buf.get(bufPos)     & 0xF8);
                            dest[destPos + 1] = (byte)(buf.get(bufPos + 1) & 0xF8);
                            dest[destPos + 2] = (byte)(buf.get(bufPos + 2) & 0xF8);
                            dest[destPos + 3] = buf.get(bufPos + 3);
                            bufPos += pixStride; destPos += 4;
                        }
                    } else {
                        for (int x = 0; x < w; x++) {
                            if (bufPos + 3 < cap && destPos + 3 < dlen) {
                                dest[destPos]     = (byte)(buf.get(bufPos)     & 0xF8);
                                dest[destPos + 1] = (byte)(buf.get(bufPos + 1) & 0xF8);
                                dest[destPos + 2] = (byte)(buf.get(bufPos + 2) & 0xF8);
                                dest[destPos + 3] = buf.get(bufPos + 3);
                            }
                            bufPos += pixStride; destPos += 4;
                        }
                    }
                    bufPos += rowPad;
                }
                break;

            case 2: // 3-3-2 bit
                for (int y = 0; y < h; y++) {
                    int rbe = bufPos + w * pixStride, rde = destPos + w * 4;
                    if (rbe - 1 < cap && rde - 1 < dlen) {
                        for (int x = 0; x < w; x++) {
                            int r = (buf.get(bufPos) & 0xFF) >> 5;
                            int g = (buf.get(bufPos + 1) & 0xFF) >> 5;
                            int b = (buf.get(bufPos + 2) & 0xFF) >> 6;
                            dest[destPos]     = (byte)((r << 5) | (r << 2) | (r >> 1));
                            dest[destPos + 1] = (byte)((g << 5) | (g << 2) | (g >> 1));
                            dest[destPos + 2] = (byte)((b << 6) | (b << 4) | (b << 2) | b);
                            dest[destPos + 3] = (byte) 255;
                            bufPos += pixStride; destPos += 4;
                        }
                    } else {
                        for (int x = 0; x < w; x++) {
                            if (bufPos + 2 < cap && destPos + 3 < dlen) {
                                int r = (buf.get(bufPos) & 0xFF) >> 5;
                                int g = (buf.get(bufPos + 1) & 0xFF) >> 5;
                                int b = (buf.get(bufPos + 2) & 0xFF) >> 6;
                                dest[destPos]     = (byte)((r << 5) | (r << 2) | (r >> 1));
                                dest[destPos + 1] = (byte)((g << 5) | (g << 2) | (g >> 1));
                                dest[destPos + 2] = (byte)((b << 6) | (b << 4) | (b << 2) | b);
                                dest[destPos + 3] = (byte) 255;
                            }
                            bufPos += pixStride; destPos += 4;
                        }
                    }
                    bufPos += rowPad;
                }
                break;

            default: // colorQuality == 0 con pixStride != 4
                for (int y = 0; y < h; y++) {
                    int rbe = bufPos + w * pixStride, rde = destPos + w * 4;
                    if (rbe - 1 < cap && rde - 1 < dlen) {
                        for (int x = 0; x < w; x++) {
                            dest[destPos]     = buf.get(bufPos);
                            dest[destPos + 1] = buf.get(bufPos + 1);
                            dest[destPos + 2] = buf.get(bufPos + 2);
                            dest[destPos + 3] = buf.get(bufPos + 3);
                            bufPos += pixStride; destPos += 4;
                        }
                    } else {
                        for (int x = 0; x < w; x++) {
                            if (bufPos + 3 < cap && destPos + 3 < dlen) {
                                dest[destPos]     = buf.get(bufPos);
                                dest[destPos + 1] = buf.get(bufPos + 1);
                                dest[destPos + 2] = buf.get(bufPos + 2);
                                dest[destPos + 3] = buf.get(bufPos + 3);
                            }
                            bufPos += pixStride; destPos += 4;
                        }
                    }
                    bufPos += rowPad;
                }
                break;
        }
    }
}
