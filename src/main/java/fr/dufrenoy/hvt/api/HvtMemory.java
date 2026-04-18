/*
 * HvtMemory.java
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
package fr.dufrenoy.hvt.api;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A typed, off-heap buffer shared between the JVM heap and a compute device.
 *
 * <p>Each instance wraps a native {@link MemorySegment} allocated via the
 * Panama Foreign Function &amp; Memory API and carries a {@link TransferMode}
 * that governs when data is transferred between the JVM and the device:
 *
 * <ul>
 *   <li>{@link TransferMode#TO_DEVICE} — JVM data is copied to device memory
 *       before kernel execution.</li>
 *   <li>{@link TransferMode#FROM_DEVICE} — device data is copied to JVM memory
 *       after kernel execution; readable via {@link #get()}.</li>
 *   <li>{@link TransferMode#DEVICE_ONLY} — memory lives exclusively on the device
 *       and is never transferred; {@link #get()} throws.</li>
 * </ul>
 *
 * <p>{@code HvtMemory} is {@link AutoCloseable}: the underlying native arena is
 * released when {@link #close()} is called. Use try-with-resources where possible.
 *
 * <p>Usage:
 * <pre>{@code
 * try (HvtMemory<int[]> src = HvtMemory.of(pixels, TransferMode.TO_DEVICE);
 *      HvtMemory<int[]> dst = HvtMemory.of(new int[w * h], TransferMode.FROM_DEVICE)) {
 *     HvtThread t = HvtThread.builder()
 *         .preferring(AcceleratorType.GPU)
 *         .fallbackTo(AcceleratorType.CPU)
 *         .kernel(Kernels::bilinearZoom, src, dst)
 *         .start();
 *     t.join();
 *     int[] result = dst.get();
 * }
 * }</pre>
 *
 * @param <T> the Java array type backing this buffer ({@code int[]}, {@code float[]}, etc.)
 */
public final class HvtMemory<T> implements AutoCloseable {

    // ─── Fields ───────────────────────────────────────────────────────────────

    private final T data;
    private final TransferMode mode;
    private final Arena arena;
    private final MemorySegment segment;
    private final long elementCount;

    // ─── Private constructor ───────────────────────────────────────────────────

    private HvtMemory(T data, TransferMode mode, Arena arena, MemorySegment segment, long elementCount) {
        this.data         = data;
        this.mode         = mode;
        this.arena        = arena;
        this.segment      = segment;
        this.elementCount = elementCount;
    }

    // ─── Factory methods ───────────────────────────────────────────────────────

    /**
     * Creates an {@code HvtMemory<int[]>} backed by the supplied array.
     *
     * <p>The array content is copied into an off-heap {@link MemorySegment}
     * at construction time. For {@link TransferMode#TO_DEVICE} buffers this
     * staging copy is what the runtime uploads to device memory. For
     * {@link TransferMode#FROM_DEVICE} buffers the segment acts as the
     * download staging area; call {@link #get()} after kernel execution to
     * retrieve the updated values.
     *
     * @param data array to wrap; must not be null
     * @param mode transfer direction
     * @return a new {@code HvtMemory<int[]>}
     * @throws IllegalArgumentException if {@code mode} is {@link TransferMode#DEVICE_ONLY}
     */
    public static HvtMemory<int[]> of(int[] data, TransferMode mode) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (mode == TransferMode.DEVICE_ONLY) {
            throw new IllegalArgumentException(
                    "Use ofDeviceOnly() to create a DEVICE_ONLY buffer");
        }
        Arena arena = Arena.ofShared();
        MemorySegment segment = arena.allocate(ValueLayout.JAVA_INT, data.length);
        segment.copyFrom(MemorySegment.ofArray(data));
        return new HvtMemory<>(data, mode, arena, segment, data.length);
    }

    /**
     * Creates an {@code HvtMemory<float[]>} backed by the supplied array.
     *
     * @param data array to wrap; must not be null
     * @param mode transfer direction
     * @return a new {@code HvtMemory<float[]>}
     * @throws IllegalArgumentException if {@code mode} is {@link TransferMode#DEVICE_ONLY}
     */
    public static HvtMemory<float[]> of(float[] data, TransferMode mode) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (mode == TransferMode.DEVICE_ONLY) {
            throw new IllegalArgumentException(
                    "Use ofDeviceOnly() to create a DEVICE_ONLY buffer");
        }
        Arena arena = Arena.ofShared();
        MemorySegment segment = arena.allocate(ValueLayout.JAVA_FLOAT, data.length);
        segment.copyFrom(MemorySegment.ofArray(data));
        return new HvtMemory<>(data, mode, arena, segment, data.length);
    }

    /**
     * Creates a {@link TransferMode#DEVICE_ONLY} buffer of the given element count.
     *
     * <p>The buffer is allocated on the device and never transferred to the JVM heap.
     * Calling {@link #get()} on a DEVICE_ONLY buffer throws {@link IllegalStateException}.
     *
     * @param elementCount number of {@code int}-sized elements to allocate
     * @return a new device-only {@code HvtMemory<int[]>}
     * @throws IllegalArgumentException if {@code elementCount} is not positive
     */
    public static HvtMemory<int[]> ofDeviceOnly(long elementCount) {
        if (elementCount <= 0) {
            throw new IllegalArgumentException("elementCount must be positive");
        }
        Arena arena = Arena.ofShared();
        MemorySegment segment = arena.allocate(ValueLayout.JAVA_INT, elementCount);
        return new HvtMemory<>(null, TransferMode.DEVICE_ONLY, arena, segment, elementCount);
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the data for this buffer as a Java array.
     *
     * <p>For {@link TransferMode#FROM_DEVICE} buffers, the segment content is copied
     * into a new Java array reflecting the values written by the kernel. For
     * {@link TransferMode#TO_DEVICE} buffers, the original array passed at construction
     * is returned unchanged.
     *
     * @return the buffer data as a Java array
     * @throws IllegalStateException if this is a {@link TransferMode#DEVICE_ONLY} buffer
     */
    @SuppressWarnings("unchecked")
    public T get() {
        if (mode == TransferMode.DEVICE_ONLY) {
            throw new IllegalStateException(
                    "DEVICE_ONLY memory cannot be transferred to the JVM heap");
        }
        if (mode == TransferMode.FROM_DEVICE) {
            if (data instanceof int[]) {
                return (T) segment.toArray(ValueLayout.JAVA_INT);
            } else if (data instanceof float[]) {
                return (T) segment.toArray(ValueLayout.JAVA_FLOAT);
            } else {
                throw new IllegalStateException(
                        "Unsupported element type for FROM_DEVICE download: "
                        + data.getClass().getComponentType().getName());
            }
        }
        return data;
    }

    /**
     * Closes this buffer and releases its native memory.
     *
     * <p>After this call the backing {@link MemorySegment} is no longer accessible.
     */
    @Override
    public void close() {
        arena.close();
    }

    // ─── Package-private accessors for the runtime layer ──────────────────────

    /**
     * Returns the off-heap {@link MemorySegment} backing this buffer.
     * Used by the runtime to upload/download data to/from the device.
     */
    MemorySegment segment() {
        return segment;
    }

    /**
     * Returns the {@link TransferMode} governing this buffer's transfer behaviour.
     */
    TransferMode transferMode() {
        return mode;
    }

    /**
     * Returns the number of elements in this buffer.
     */
    long elementCount() {
        return elementCount;
    }
}