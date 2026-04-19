/*
 * HvtBenchmark.java
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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

/**
 * JMH benchmark — GPU vs. CPU bilinear upscale on a 1920×1080 → 3840×2160 image.
 *
 * <p>Three benchmarks are included:
 * <ul>
 *   <li>{@link #cpuSingle} — one sequential CPU dispatch via {@link HvtThread}</li>
 *   <li>{@link #gpuSingle} — one full GPU round-trip (upload + compute + download)</li>
 *   <li>{@link #gpuBatch}  — 20 GPU dispatches with device buffers persistent across iterations</li>
 * </ul>
 *
 * <p>The setup phase performs 3 GPU warm-up dispatches to prime the Vulkan driver
 * before JMH begins its own warm-up iterations, and logs a one-time phase-breakdown
 * line ({@code upload / compute / download}) to stderr.
 *
 * <p>Build and run:
 * <pre>
 *   mvn package
 *   java --enable-native-access=ALL-UNNAMED --enable-preview -jar target/benchmarks.jar
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 2, jvmArgsPrepend = {
        "--enable-native-access=ALL-UNNAMED",
        "--enable-preview"})
@State(Scope.Benchmark)
public class HvtBenchmark {

    // ─── Dimensions ───────────────────────────────────────────────────────────

    private static final int SRC_W = 1920;
    private static final int SRC_H = 1080;
    private static final int DST_W = 3840;
    private static final int DST_H = 2160;
    private static final int BATCH_N = 20;

    // ─── State ────────────────────────────────────────────────────────────────

    private int[] src;
    private int[] dst;
    private int[] params;

    // ─── Setup ────────────────────────────────────────────────────────────────

    @Setup(Level.Trial)
    public void setup() {
        src    = generateGradient(SRC_W, SRC_H);
        dst    = new int[DST_W * DST_H];
        params = new int[]{SRC_W, SRC_H, DST_W, DST_H};

        if (HvtCarrierRegistry.gpuAvailable()) {
            // Prime Vulkan driver before JMH begins its own warm-up iterations.
            for (int i = 0; i < 3; i++) {
                runGpuSingle();
            }
            // Log phase breakdown once per fork to stderr (appears in JMH output stream).
            long[] t = new long[3];
            try (HvtMemory<int[]> srcMem    = HvtMemory.of(src,    TransferMode.TO_DEVICE);
                 HvtMemory<int[]> dstMem    = HvtMemory.of(dst,    TransferMode.FROM_DEVICE);
                 HvtMemory<int[]> paramsMem = HvtMemory.of(params, TransferMode.TO_DEVICE)) {
                KernelDispatcher.submitTimed(new HvtMemory<?>[]{srcMem, dstMem, paramsMem}, t);
            }
            System.err.printf(Locale.US,
                    "[Phase breakdown] upload=%.1f ms  compute=%.1f ms  download=%.1f ms%n",
                    t[0] / 1e6, t[1] / 1e6, t[2] / 1e6);
        }
    }

    // ─── Benchmarks ───────────────────────────────────────────────────────────

    /**
     * One sequential CPU bilinear zoom dispatch.
     * Measures pure CPU throughput including Java array traversal.
     */
    @Benchmark
    public long cpuSingle() {
        try (HvtMemory<int[]> srcMem    = HvtMemory.of(src,    TransferMode.TO_DEVICE);
             HvtMemory<int[]> dstMem    = HvtMemory.of(dst,    TransferMode.FROM_DEVICE);
             HvtMemory<int[]> paramsMem = HvtMemory.of(params, TransferMode.TO_DEVICE)) {
            HvtThread t = HvtThread.builder()
                    .preferring(AcceleratorType.CPU)
                    .fallbackTo(AcceleratorType.CPU)
                    .kernel(HvtBenchmark::bilinearZoomCpu, srcMem, dstMem, paramsMem)
                    .start();
            t.join();
        }
        return System.nanoTime();
    }

    /**
     * One full GPU round-trip: host→device upload, compute, device→host download.
     * Measures the end-to-end single-dispatch latency seen by user code.
     */
    @Benchmark
    public long gpuSingle() {
        try (HvtMemory<int[]> srcMem    = HvtMemory.of(src,    TransferMode.TO_DEVICE);
             HvtMemory<int[]> dstMem    = HvtMemory.of(dst,    TransferMode.FROM_DEVICE);
             HvtMemory<int[]> paramsMem = HvtMemory.of(params, TransferMode.TO_DEVICE)) {
            KernelDispatcher.submit(new HvtMemory<?>[]{srcMem, dstMem, paramsMem});
        }
        return System.nanoTime();
    }

    /**
     * {@value #BATCH_N} GPU dispatches with device buffers persistent across iterations.
     * Measures sustained GPU compute throughput, amortising the transfer cost.
     */
    @Benchmark
    public long gpuBatch() {
        try (HvtMemory<int[]> srcMem    = HvtMemory.of(src,    TransferMode.TO_DEVICE);
             HvtMemory<int[]> dstMem    = HvtMemory.of(dst,    TransferMode.FROM_DEVICE);
             HvtMemory<int[]> paramsMem = HvtMemory.of(params, TransferMode.TO_DEVICE)) {
            KernelDispatcher.submitBatch(new HvtMemory<?>[]{srcMem, dstMem, paramsMem}, BATCH_N);
        }
        return System.nanoTime();
    }

    // ─── GPU warm-up helper ───────────────────────────────────────────────────

    private void runGpuSingle() {
        try (HvtMemory<int[]> srcMem    = HvtMemory.of(src,    TransferMode.TO_DEVICE);
             HvtMemory<int[]> dstMem    = HvtMemory.of(dst,    TransferMode.FROM_DEVICE);
             HvtMemory<int[]> paramsMem = HvtMemory.of(params, TransferMode.TO_DEVICE)) {
            KernelDispatcher.submit(new HvtMemory<?>[]{srcMem, dstMem, paramsMem});
        }
    }

    // ─── CPU kernel ───────────────────────────────────────────────────────────

    /**
     * Sequential bilinear zoom — pixel-center convention matching the SPIR-V shader:
     * {@code srcX = (gx + 0.5) * (srcW / dstW) - 0.5}, clamped to [0, srcW-1].
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
                float srcXF = Math.max(0f, Math.min(srcW - 1f, (gx + 0.5f) * xRatio - 0.5f));
                float srcYF = Math.max(0f, Math.min(srcH - 1f, (gy + 0.5f) * yRatio - 0.5f));

                int x0 = (int) srcXF;
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