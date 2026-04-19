/*
 * GpuCompletionScheduler.java
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

import fr.dufrenoy.hvt.runtime.vulkan.vulkan_h;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Background scheduler that monitors in-flight GPU fences and resumes parked
 * virtual threads when their work completes.
 *
 * <p>A single daemon platform thread continuously polls each registered
 * {@code VkFence} with {@code timeout=0} (non-blocking check). When a fence
 * transitions to the signalled state, the scheduler removes the entry, sets its
 * completion flag, and calls {@link LockSupport#unpark} on the waiting virtual
 * thread, which then re-acquires the class monitor in {@link KernelDispatcher}
 * to download results and free device resources.
 *
 * <p>This mirrors the role of Project Loom's selector thread in async I/O:
 * just as a virtual thread parks while waiting for a socket and is unparked by
 * the selector when data arrives, a virtual thread parks while waiting for the
 * GPU and is unparked here when its fence is signalled. The carrier thread is
 * free to run other virtual threads in the interval.
 */
final class GpuCompletionScheduler {

    // VkResult code returned by vkWaitForFences when the fence is not yet signalled
    private static final int VK_TIMEOUT = 2;

    private record PendingDispatch(MemorySegment pFenceSlot, AtomicBoolean done, Thread thread) {}

    private static final ConcurrentLinkedQueue<PendingDispatch> PENDING =
            new ConcurrentLinkedQueue<>();

    // ─── Static initializer ───────────────────────────────────────────────────

    static {
        Thread.ofPlatform()
              .name("hvt-gpu-completion")
              .daemon(true)
              .start(GpuCompletionScheduler::pollLoop);
    }

    // ─── Private constructor ───────────────────────────────────────────────────

    private GpuCompletionScheduler() {}

    // ─── Package-private API (used by KernelDispatcher) ──────────────────────

    /**
     * Registers a fence for completion monitoring.
     *
     * <p>The caller must call {@link LockSupport#park()} in a loop conditioned
     * on {@code !done.get()} immediately after this method returns. The
     * scheduler sets {@code done} to {@code true} before unparking, so the
     * loop handles spurious wakeups correctly.
     *
     * @param pFenceSlot pointer-to-{@code VkFence} handle, in a shared arena
     *                   that remains valid until the caller is unparked
     * @param done       completion flag; the scheduler sets it before unparking
     * @param thread     the virtual thread to unpark on completion
     */
    static void register(MemorySegment pFenceSlot, AtomicBoolean done, Thread thread) {
        PENDING.add(new PendingDispatch(pFenceSlot, done, thread));
    }

    // ─── Completion poll loop ─────────────────────────────────────────────────

    private static void pollLoop() {
        MemorySegment dev = HvtCarrierRegistry.device();
        while (true) {
            boolean anyPending = false;
            for (var it = PENDING.iterator(); it.hasNext(); ) {
                PendingDispatch p = it.next();
                // timeout=0: non-blocking — returns VK_SUCCESS if signalled, VK_TIMEOUT otherwise.
                // Any other result (e.g. VK_ERROR_DEVICE_LOST) also unparks so the waiter
                // can fail fast in finishDispatch rather than blocking indefinitely.
                int result = vulkan_h.vkWaitForFences(dev, 1, p.pFenceSlot(), 1, 0L);
                if (result != VK_TIMEOUT) {
                    it.remove();
                    p.done().set(true);
                    LockSupport.unpark(p.thread());
                } else {
                    anyPending = true;
                }
            }
            if (!anyPending) {
                Thread.yield();
            }
        }
    }
}