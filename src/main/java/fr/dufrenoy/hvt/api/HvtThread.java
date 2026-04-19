/*
 * HvtThread.java
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

import fr.dufrenoy.hvt.error.HvtKernelException;
import fr.dufrenoy.hvt.runtime.HvtCarrierRegistry;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Façade for {@code Thread.ofHeterogeneousVirtual()} as proposed by the HVT paper.
 *
 * <p>An {@code HvtThread} submits a compute kernel to a preferred accelerator
 * (e.g. GPU) and falls back to an alternative (e.g. CPU) when the preferred
 * device is unavailable. Internally it uses a single-thread {@link ExecutorService};
 * this is not a real extension of the JVM scheduler, which is outside the scope
 * of this proof-of-concept.
 *
 * <p>Usage:
 * <pre>{@code
 * HvtThread t = HvtThread.builder()
 *     .preferring(AcceleratorType.GPU)
 *     .fallbackTo(AcceleratorType.CPU)
 *     .kernel(Kernels::bilinearZoom, srcMem, dstMem)
 *     .start();
 * t.join();
 * }</pre>
 */
public final class HvtThread {

    // ─── Fields ───────────────────────────────────────────────────────────────

    private final ExecutorService executor;
    private final Future<?> future;

    // ─── Private constructor ───────────────────────────────────────────────────

    private HvtThread(ExecutorService executor, Future<?> future) {
        this.executor = executor;
        this.future   = future;
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a new {@link Builder}.
     *
     * @return a fresh builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Waits for the kernel to complete.
     *
     * @throws HvtKernelException if the kernel threw an exception, or if the
     *                            calling thread was interrupted while waiting
     */
    public void join() {
        try {
            future.get();
        } catch (ExecutionException ee) {
            throw new HvtKernelException("Kernel execution failed", ee.getCause());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new HvtKernelException("Interrupted while waiting for kernel to complete", ie);
        } finally {
            executor.shutdown();
        }
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Builder for {@link HvtThread}.
     *
     * <p>All four methods ({@link #preferring}, {@link #fallbackTo}, {@link #kernel},
     * and {@link #start}) must be called before {@link #start} returns an
     * {@link HvtThread}.
     */
    public static final class Builder {

        private AcceleratorType preferred;
        private AcceleratorType fallback;
        private HvtKernel kernel;
        private HvtMemory<?>[] memories;

        private Builder() {}

        /**
         * Sets the preferred accelerator type.
         *
         * @param type the preferred accelerator
         * @return this builder
         * @throws IllegalArgumentException if {@code type} is null
         */
        public Builder preferring(AcceleratorType type) {
            if (type == null) {
                throw new IllegalArgumentException("preferred AcceleratorType must not be null");
            }
            this.preferred = type;
            return this;
        }

        /**
         * Sets the fallback accelerator type used when the preferred device is unavailable.
         *
         * @param type the fallback accelerator
         * @return this builder
         * @throws IllegalArgumentException if {@code type} is null
         */
        public Builder fallbackTo(AcceleratorType type) {
            if (type == null) {
                throw new IllegalArgumentException("fallback AcceleratorType must not be null");
            }
            this.fallback = type;
            return this;
        }

        /**
         * Sets the kernel to execute and the memory buffers it operates on.
         *
         * @param kernel   the compute kernel; must not be null
         * @param memories the {@link HvtMemory} buffers bound to the kernel, in order
         * @return this builder
         * @throws IllegalArgumentException if {@code kernel} is null
         */
        public Builder kernel(HvtKernel kernel, HvtMemory<?>... memories) {
            if (kernel == null) {
                throw new IllegalArgumentException("kernel must not be null");
            }
            this.kernel   = kernel;
            this.memories = (memories != null) ? memories : new HvtMemory<?>[0];
            return this;
        }

        /**
         * Validates the configuration and starts the heterogeneous virtual thread.
         *
         * @return the running {@link HvtThread}
         * @throws IllegalStateException if any required field has not been set
         */
        public HvtThread start() {
            validate();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            HvtMemory<?>[] snapshot  = memories;
            Future<?> future = executor.submit(
                    () -> dispatch(preferred, fallback, kernel, snapshot));
            return new HvtThread(executor, future);
        }

        // ─── Private helpers ──────────────────────────────────────────────────

        private void validate() {
            if (preferred == null) {
                throw new IllegalStateException("preferring() must be called before start()");
            }
            if (fallback == null) {
                throw new IllegalStateException("fallbackTo() must be called before start()");
            }
            if (kernel == null) {
                throw new IllegalStateException("kernel() must be called before start()");
            }
        }

        /**
         * Routes the kernel to the preferred accelerator, falling back to the
         * alternative if the preferred device is unavailable.
         *
         * <p>In this prototype the runtime layer ({@code HvtCarrierRegistry}) is
         * not yet implemented. CPU execution is used as a temporary stand-in;
         * this method will delegate to {@code HvtCarrierRegistry.dispatch()} once
         * the runtime layer is complete.
         */
        private static void dispatch(AcceleratorType preferred,
                                     AcceleratorType fallback,
                                     HvtKernel kernel,
                                     HvtMemory<?>[] memories) {
            HvtCarrierRegistry.dispatch(preferred, fallback, kernel, memories);
        }
    }
}