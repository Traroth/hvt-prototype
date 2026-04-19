/*
 * KernelDispatcherIntegrationTest.java
 *
 * Version 0.1.0-SNAPSHOT
 *
 * hvt-prototype — Proof of concept for Heterogeneous Virtual Threads
 * Copyright (C) 2026  Dufrenoy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package fr.dufrenoy.hvt.runtime;

import fr.dufrenoy.hvt.api.AcceleratorType;
import fr.dufrenoy.hvt.api.HvtMemory;
import fr.dufrenoy.hvt.api.HvtThread;
import fr.dufrenoy.hvt.api.TransferMode;
import fr.dufrenoy.hvt.error.HvtKernelException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the Vulkan compute dispatch path.
 *
 * <p>Tests are skipped automatically when no compute-capable GPU is available.
 *
 * <p>The {@code bilinearZoom} kernel convention:
 * <ul>
 *   <li>{@code memories[0]} — source pixels (packed ARGB), {@code TO_DEVICE}</li>
 *   <li>{@code memories[1]} — destination pixels (packed ARGB), {@code FROM_DEVICE}</li>
 *   <li>{@code memories[2]} — params {@code {srcW, srcH, dstW, dstH}},
 *       {@code TO_DEVICE}</li>
 * </ul>
 *
 * <p>The kernel uses the pixel-center convention:
 * {@code srcX = (gx + 0.5) * (srcW / dstW) - 0.5}, clamped to [0, srcW-1].
 * Per-channel bilinear interpolation is applied on each of the four 8-bit ARGB channels.
 */
class KernelDispatcherIntegrationTest {

    private static final float BILINEAR_TOLERANCE = 2.0f;

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static void assumeGpu() {
        Assumptions.assumeTrue(HvtCarrierRegistry.gpuAvailable(),
                "Skipping GPU test: no compute-capable Vulkan device found");
    }

    /**
     * Packs a grey value into all four ARGB channels.
     */
    private static int grey(int g) {
        int c = g & 0xFF;
        return (c << 24) | (c << 16) | (c << 8) | c;
    }

    /**
     * Runs {@code bilinearZoom} on the GPU and returns the output pixel array.
     */
    private static int[] zoom(int[] src, int srcW, int srcH, int dstW, int dstH) {
        int[] dst    = new int[dstW * dstH];
        int[] params = {srcW, srcH, dstW, dstH};
        try (HvtMemory<int[]> srcMem    = HvtMemory.of(src,    TransferMode.TO_DEVICE);
             HvtMemory<int[]> dstMem    = HvtMemory.of(dst,    TransferMode.FROM_DEVICE);
             HvtMemory<int[]> paramsMem = HvtMemory.of(params, TransferMode.TO_DEVICE)) {
            HvtThread t = HvtThread.builder()
                    .preferring(AcceleratorType.GPU)
                    .fallbackTo(AcceleratorType.CPU)
                    .kernel((mems) -> {}, srcMem, dstMem, paramsMem)
                    .start();
            t.join();
            return dstMem.get();
        }
    }

    /**
     * Reference implementation of the kernel's bilinear filter using the same
     * pixel-center coordinate convention: {@code srcX = (gx+0.5)*(srcW/dstW) - 0.5}.
     *
     * <p>Interpolation is performed per 8-bit ARGB channel and the result is
     * repacked into a 32-bit int, matching the kernel output exactly.
     */
    private static int bilinearRef(int[] src, int srcW, int srcH,
                                   int gx, int gy, int dstW, int dstH) {
        float xRatio = (float) srcW / dstW;
        float yRatio = (float) srcH / dstH;
        float srcXF  = (gx + 0.5f) * xRatio - 0.5f;
        float srcYF  = (gy + 0.5f) * yRatio - 0.5f;
        srcXF = Math.max(0f, Math.min(srcW - 1f, srcXF));
        srcYF = Math.max(0f, Math.min(srcH - 1f, srcYF));
        int   x0 = (int) Math.floor(srcXF);
        int   y0 = (int) Math.floor(srcYF);
        int   x1 = Math.min(x0 + 1, srcW - 1);
        int   y1 = Math.min(y0 + 1, srcH - 1);
        float tx = srcXF - x0;
        float ty = srcYF - y0;

        int p00 = src[y0 * srcW + x0];
        int p01 = src[y0 * srcW + x1];
        int p10 = src[y1 * srcW + x0];
        int p11 = src[y1 * srcW + x1];

        int result = 0;
        for (int shift : new int[]{0, 8, 16, 24}) {
            float c00 = (p00 >> shift) & 0xFF;
            float c01 = (p01 >> shift) & 0xFF;
            float c10 = (p10 >> shift) & 0xFF;
            float c11 = (p11 >> shift) & 0xFF;
            float val = c00 * (1 - tx) * (1 - ty)
                      + c01 * tx       * (1 - ty)
                      + c10 * (1 - tx) * ty
                      + c11 * tx       * ty;
            result |= ((Math.round(val) & 0xFF) << shift);
        }
        return result;
    }

    private static void assertPixel(int ref, int got, float tol, int dx, int dy) {
        for (int shift : new int[]{0, 8, 16, 24}) {
            int refC = (ref >> shift) & 0xFF;
            int gotC = (got >> shift) & 0xFF;
            assertEquals(refC, gotC, tol, "channel=" + shift + " pixel(" + dx + "," + dy + ")");
        }
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    /**
     * Downscale 4×4 → 2×2: GPU output must match bilinear reference within
     * ±{@value #BILINEAR_TOLERANCE} per channel.
     */
    @Test
    void bilinearZoom_downscale_4x4to2x2() {
        assumeGpu();

        int srcW = 4, srcH = 4, dstW = 2, dstH = 2;
        int[] src = {
            grey(0),   grey(16),  grey(32),  grey(48),
            grey(64),  grey(80),  grey(96),  grey(112),
            grey(128), grey(144), grey(160), grey(176),
            grey(192), grey(208), grey(224), grey(240)
        };

        int[] result = zoom(src, srcW, srcH, dstW, dstH);
        assertEquals(dstW * dstH, result.length);

        for (int dy = 0; dy < dstH; dy++) {
            for (int dx = 0; dx < dstW; dx++) {
                int ref = bilinearRef(src, srcW, srcH, dx, dy, dstW, dstH);
                int got = result[dy * dstW + dx];
                assertPixel(ref, got, BILINEAR_TOLERANCE, dx, dy);
            }
        }
    }

    /**
     * CPU-path kernel that throws {@link HvtKernelException}: {@link HvtThread#join()}
     * must propagate the exception and the output buffer must remain unmodified.
     */
    @Test
    void kernelError_doesNotTransferOutput() {
        int[] src    = {grey(0), grey(255), grey(128), grey(64)};
        int[] dst    = new int[4]; // all zeros initially
        int[] params = {2, 2, 2, 2};

        try (HvtMemory<int[]> srcMem    = HvtMemory.of(src,    TransferMode.TO_DEVICE);
             HvtMemory<int[]> dstMem    = HvtMemory.of(dst,    TransferMode.FROM_DEVICE);
             HvtMemory<int[]> paramsMem = HvtMemory.of(params, TransferMode.TO_DEVICE)) {

            HvtThread t = HvtThread.builder()
                    .preferring(AcceleratorType.CPU)
                    .fallbackTo(AcceleratorType.CPU)
                    .kernel((mems) -> { throw new HvtKernelException("error code 42"); },
                            srcMem, dstMem, paramsMem)
                    .start();

            assertThrows(HvtKernelException.class, () -> t.join());
            // Kernel threw before writing anything; output buffer is still all zeros
            assertArrayEquals(new int[4], dstMem.get());
        }
    }

    /**
     * Upscale 2×2 → 4×4: GPU output must match bilinear reference within
     * ±{@value #BILINEAR_TOLERANCE} per channel.
     */
    @Test
    void bilinearZoom_upscale_2x2to4x4() {
        assumeGpu();

        int srcW = 2, srcH = 2, dstW = 4, dstH = 4;
        int[] src = {grey(0), grey(255), grey(128), grey(64)};

        int[] result = zoom(src, srcW, srcH, dstW, dstH);
        assertEquals(dstW * dstH, result.length);

        for (int dy = 0; dy < dstH; dy++) {
            for (int dx = 0; dx < dstW; dx++) {
                int ref = bilinearRef(src, srcW, srcH, dx, dy, dstW, dstH);
                int got = result[dy * dstW + dx];
                assertPixel(ref, got, BILINEAR_TOLERANCE, dx, dy);
            }
        }
    }
}