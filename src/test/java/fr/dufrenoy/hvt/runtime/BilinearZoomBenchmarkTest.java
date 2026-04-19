/*
 * BilinearZoomBenchmarkTest.java
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
import fr.dufrenoy.hvt.api.HvtKernel;
import fr.dufrenoy.hvt.api.HvtMemory;
import fr.dufrenoy.hvt.api.HvtThread;
import fr.dufrenoy.hvt.api.TransferMode;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Locale;

/**
 * Measures GPU vs. sequential CPU speedup for a 1920×1080 → 3840×2160 bilinear upscale.
 *
 * <p>The CPU kernel uses the same pixel-center coordinate convention as the SPIR-V shader
 * so the comparison is algorithm-fair. No correctness assertions are made; the result is
 * written to stdout as a single {@code [HVT Benchmark]} line.
 *
 * <p>If no compute-capable GPU is found the GPU timing is skipped and only the CPU time
 * is logged.
 */
class BilinearZoomBenchmarkTest {

    // ─── Dimensions ───────────────────────────────────────────────────────────

    private static final int SRC_W = 1920;
    private static final int SRC_H = 1080;
    private static final int DST_W = 3840;
    private static final int DST_H = 2160;

    private static final int BATCH_ITERATIONS = 20;

    // ─── Test ─────────────────────────────────────────────────────────────────

    /**
     * Benchmarks GPU vs. sequential CPU bilinear upscale on a 1920×1080 → 3840×2160 image.
     * Logs: {@code [HVT Benchmark] 1920×1080→3840×2160  GPU=X ms  CPU=Y ms  speedup=Z×}
     */
    @Test
    void benchmark_gpu_vs_cpu_3840x2160() {
        int[] src    = generateGradient(SRC_W, SRC_H);
        int[] params = {SRC_W, SRC_H, DST_W, DST_H};

        // CPU: one warm-up run so the JIT has compiled the hot loop
        runZoom(AcceleratorType.CPU, BilinearZoomBenchmarkTest::bilinearZoomCpu, src, params);
        long cpuNs = timeZoom(AcceleratorType.CPU, BilinearZoomBenchmarkTest::bilinearZoomCpu, src, params);

        if (HvtCarrierRegistry.gpuAvailable()) {
            // GPU pipeline is already compiled in the static initialiser; no warm-up needed.
            long gpuNs = timeZoom(AcceleratorType.GPU, (mems) -> {}, src, params);
            System.out.printf(Locale.US,
                    "[HVT Benchmark] %dx%d->%dx%d  GPU=%.1f ms  CPU=%.1f ms  speedup=%.2fx%n",
                    SRC_W, SRC_H, DST_W, DST_H,
                    gpuNs / 1e6, cpuNs / 1e6, (double) cpuNs / gpuNs);
        } else {
            System.out.printf(Locale.US,
                    "[HVT Benchmark] %dx%d->%dx%d  GPU=unavailable  CPU=%.1f ms%n",
                    SRC_W, SRC_H, DST_W, DST_H, cpuNs / 1e6);
        }
    }

    /**
     * Benchmarks N repeated GPU dispatches (device buffers persistent) vs. N repeated CPU
     * runs. Isolates compute throughput from host-device transfer overhead.
     * Logs: {@code [HVT Batch Benchmark] ... x20  GPU=X ms (Y ms/iter)  CPU=... speedup=Zx}
     */
    @Test
    void benchmark_batch_gpu_vs_cpu_repeated() {
        int[] src    = generateGradient(SRC_W, SRC_H);
        int[] params = {SRC_W, SRC_H, DST_W, DST_H};

        // CPU: 1 warm-up (JIT), then BATCH_ITERATIONS timed runs in one pass
        runZoom(AcceleratorType.CPU, BilinearZoomBenchmarkTest::bilinearZoomCpu, src, params);
        long cpuNs = timeCpuBatch(src, params, BATCH_ITERATIONS);

        if (HvtCarrierRegistry.gpuAvailable()) {
            long gpuNs = timeGpuBatch(src, params, BATCH_ITERATIONS);
            System.out.printf(Locale.US,
                    "[HVT Batch Benchmark] %dx%d->%dx%d x%d  "
                    + "GPU=%.1f ms (%.1f ms/iter)  CPU=%.1f ms (%.1f ms/iter)  speedup=%.2fx%n",
                    SRC_W, SRC_H, DST_W, DST_H, BATCH_ITERATIONS,
                    gpuNs / 1e6, gpuNs / 1e6 / BATCH_ITERATIONS,
                    cpuNs / 1e6, cpuNs / 1e6 / BATCH_ITERATIONS,
                    (double) cpuNs / gpuNs);
        } else {
            System.out.printf(Locale.US,
                    "[HVT Batch Benchmark] %dx%d->%dx%d x%d  GPU=unavailable  "
                    + "CPU=%.1f ms (%.1f ms/iter)%n",
                    SRC_W, SRC_H, DST_W, DST_H, BATCH_ITERATIONS,
                    cpuNs / 1e6, cpuNs / 1e6 / BATCH_ITERATIONS);
        }
    }

    // ─── Timing helpers ───────────────────────────────────────────────────────

    /**
     * Runs the zoom once and discards the result (warm-up or fire-and-forget).
     */
    private static void runZoom(AcceleratorType preferred, HvtKernel kernel,
                                int[] src, int[] params) {
        int[] dst = new int[DST_W * DST_H];
        try (HvtMemory<int[]> srcMem    = HvtMemory.of(src,    TransferMode.TO_DEVICE);
             HvtMemory<int[]> dstMem    = HvtMemory.of(dst,    TransferMode.FROM_DEVICE);
             HvtMemory<int[]> paramsMem = HvtMemory.of(params, TransferMode.TO_DEVICE)) {
            HvtThread t = HvtThread.builder()
                    .preferring(preferred)
                    .fallbackTo(AcceleratorType.CPU)
                    .kernel(kernel, srcMem, dstMem, paramsMem)
                    .start();
            t.join();
        }
    }

    /**
     * Runs the zoom once and returns the wall-clock duration in nanoseconds.
     * The clock spans from {@code HvtThread.start()} to {@code HvtThread.join()} completion.
     */
    private static long timeZoom(AcceleratorType preferred, HvtKernel kernel,
                                 int[] src, int[] params) {
        int[] dst = new int[DST_W * DST_H];
        try (HvtMemory<int[]> srcMem    = HvtMemory.of(src,    TransferMode.TO_DEVICE);
             HvtMemory<int[]> dstMem    = HvtMemory.of(dst,    TransferMode.FROM_DEVICE);
             HvtMemory<int[]> paramsMem = HvtMemory.of(params, TransferMode.TO_DEVICE)) {
            long t0 = System.nanoTime();
            HvtThread t = HvtThread.builder()
                    .preferring(preferred)
                    .fallbackTo(AcceleratorType.CPU)
                    .kernel(kernel, srcMem, dstMem, paramsMem)
                    .start();
            t.join();
            return System.nanoTime() - t0;
        }
    }

    /**
     * Times {@code iterations} CPU bilinear zoom runs with the same HvtMemory objects,
     * matching the DEVICE_ONLY semantics of {@link #timeGpuBatch}.
     */
    private static long timeCpuBatch(int[] src, int[] params, int iterations) {
        int[] dst = new int[DST_W * DST_H];
        try (HvtMemory<int[]> srcMem    = HvtMemory.of(src,    TransferMode.TO_DEVICE);
             HvtMemory<int[]> dstMem    = HvtMemory.of(dst,    TransferMode.FROM_DEVICE);
             HvtMemory<int[]> paramsMem = HvtMemory.of(params, TransferMode.TO_DEVICE)) {
            HvtMemory<?>[] mems = {srcMem, dstMem, paramsMem};
            long t0 = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                bilinearZoomCpu(mems);
            }
            return System.nanoTime() - t0;
        }
    }

    /**
     * Times {@code iterations} GPU dispatches via {@link KernelDispatcher#submitBatch},
     * which keeps device buffers alive across iterations.
     */
    private static long timeGpuBatch(int[] src, int[] params, int iterations) {
        int[] dst = new int[DST_W * DST_H];
        try (HvtMemory<int[]> srcMem    = HvtMemory.of(src,    TransferMode.TO_DEVICE);
             HvtMemory<int[]> dstMem    = HvtMemory.of(dst,    TransferMode.FROM_DEVICE);
             HvtMemory<int[]> paramsMem = HvtMemory.of(params, TransferMode.TO_DEVICE)) {
            HvtMemory<?>[] mems = {srcMem, dstMem, paramsMem};
            long t0 = System.nanoTime();
            KernelDispatcher.submitBatch(mems, iterations);
            return System.nanoTime() - t0;
        }
    }

    // ─── CPU kernel ───────────────────────────────────────────────────────────

    /**
     * Sequential bilinear zoom kernel — same pixel-center convention as the SPIR-V shader:
     * {@code srcX = (gx + 0.5) * (srcW / dstW) - 0.5}, clamped to [0, srcW-1].
     * Writes directly to {@code mems[1].segment()} to match the HvtMemory contract.
     */
    private static void bilinearZoomCpu(HvtMemory<?>[] mems) {
        int[]         src    = (int[]) mems[0].get();
        int[]         params = (int[]) mems[2].get();
        int srcW = params[0], srcH = params[1], dstW = params[2], dstH = params[3];
        MemorySegment dst    = mems[1].segment();

        float xRatio = (float) srcW / dstW;
        float yRatio = (float) srcH / dstH;

        for (int gy = 0; gy < dstH; gy++) {
            for (int gx = 0; gx < dstW; gx++) {
                // Clamp after pixel-center shift (srcXF is always >= 0 after clamping)
                float srcXF = Math.max(0f, Math.min(srcW - 1f, (gx + 0.5f) * xRatio - 0.5f));
                float srcYF = Math.max(0f, Math.min(srcH - 1f, (gy + 0.5f) * yRatio - 0.5f));

                int x0 = (int) srcXF; // floor — safe because srcXF >= 0
                int y0 = (int) srcYF;
                int x1 = Math.min(x0 + 1, srcW - 1);
                int y1 = Math.min(y0 + 1, srcH - 1);
                float tx = srcXF - x0;
                float ty = srcYF - y0;

                int p00 = src[y0 * srcW + x0], p01 = src[y0 * srcW + x1];
                int p10 = src[y1 * srcW + x0], p11 = src[y1 * srcW + x1];

                int pixel = 0;
                for (int shift = 0; shift <= 24; shift += 8) {
                    float c00 = (p00 >> shift) & 0xFF;
                    float c01 = (p01 >> shift) & 0xFF;
                    float c10 = (p10 >> shift) & 0xFF;
                    float c11 = (p11 >> shift) & 0xFF;
                    float val = c00 * (1 - tx) * (1 - ty)
                              + c01 * tx       * (1 - ty)
                              + c10 * (1 - tx) * ty
                              + c11 * tx       * ty;
                    pixel |= (Math.round(val) & 0xFF) << shift;
                }
                dst.setAtIndex(ValueLayout.JAVA_INT, (long) gy * dstW + gx, pixel);
            }
        }
    }

    // ─── Image generation ─────────────────────────────────────────────────────

    /**
     * Generates a synthetic diagonal gradient image of {@code w × h} packed ARGB pixels.
     * Each pixel is grey with value {@code (x + y) & 0xFF}.
     */
    private static int[] generateGradient(int w, int h) {
        int[] img = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int g = (x + y) & 0xFF;
                img[y * w + x] = (g << 24) | (g << 16) | (g << 8) | g;
            }
        }
        return img;
    }
}