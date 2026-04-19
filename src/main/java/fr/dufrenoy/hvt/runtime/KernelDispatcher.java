/*
 * KernelDispatcher.java
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

import fr.dufrenoy.hvt.api.HvtMemory;
import fr.dufrenoy.hvt.error.HvtErrorBuffer;
import fr.dufrenoy.hvt.kernel.KernelCompiler;
import fr.dufrenoy.hvt.runtime.vulkan.VkBufferCreateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkCommandBufferAllocateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkCommandBufferBeginInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkCommandPoolCreateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkComputePipelineCreateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkDescriptorBufferInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkDescriptorPoolCreateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkDescriptorPoolSize;
import fr.dufrenoy.hvt.runtime.vulkan.VkDescriptorSetAllocateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkDescriptorSetLayoutBinding;
import fr.dufrenoy.hvt.runtime.vulkan.VkDescriptorSetLayoutCreateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkFenceCreateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkMemoryAllocateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkMemoryRequirements;
import fr.dufrenoy.hvt.runtime.vulkan.VkMemoryType;
import fr.dufrenoy.hvt.runtime.vulkan.VkPhysicalDeviceMemoryProperties;
import fr.dufrenoy.hvt.runtime.vulkan.VkPipelineLayoutCreateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkPipelineShaderStageCreateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkPushConstantRange;
import fr.dufrenoy.hvt.runtime.vulkan.VkShaderModuleCreateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkSubmitInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkWriteDescriptorSet;
import fr.dufrenoy.hvt.runtime.vulkan.vulkan_h;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Submits the {@code bilinearZoom} SPIR-V kernel to the Vulkan compute queue.
 *
 * <p>Static resources (shader module, descriptor set layout, pipeline layout,
 * compute pipeline, command pool) are created once at class-initialisation time and
 * destroyed on JVM shutdown via a registered shutdown hook.
 *
 * <p>Per-call resources (device buffers, device memory, descriptor pool/set,
 * command buffer) are created inside {@link #submit} and destroyed before it returns.
 *
 * <p>Memory strategy: host-visible + host-coherent for all buffers; no staging
 * buffers required.
 *
 * <p>{@link #submit} submits work to the GPU with a {@code VkFence}, then parks
 * the calling thread via {@link LockSupport#park}. {@link GpuCompletionScheduler}
 * polls the fence on a dedicated daemon thread and unparks the caller when the GPU
 * signals completion. The carrier thread is free to run other virtual threads during
 * GPU execution — this is the key HVT property demonstrated by this prototype.
 *
 * <p><b>Thread safety:</b> {@code submit} uses two explicit {@code synchronized}
 * blocks on the class monitor (one for setup + dispatch, one for teardown), with
 * the park between them. The shared {@code VkCommandPool} is thus never accessed
 * concurrently.
 */
final class KernelDispatcher {

    // ─── VkStructureType integer constants (Vulkan spec §44.1) ───────────────

    private static final int STYPE_SHADER_MODULE_CREATE_INFO         = 16;
    private static final int STYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO = 32;
    private static final int STYPE_PIPELINE_LAYOUT_CREATE_INFO       = 30;
    private static final int STYPE_PIPELINE_SHADER_STAGE_CREATE_INFO = 18;
    private static final int STYPE_COMPUTE_PIPELINE_CREATE_INFO      = 29;
    private static final int STYPE_COMMAND_POOL_CREATE_INFO          = 39;
    private static final int STYPE_COMMAND_BUFFER_ALLOCATE_INFO      = 40;
    private static final int STYPE_COMMAND_BUFFER_BEGIN_INFO         = 42;
    private static final int STYPE_DESCRIPTOR_POOL_CREATE_INFO       = 33;
    private static final int STYPE_DESCRIPTOR_SET_ALLOCATE_INFO      = 34;
    private static final int STYPE_WRITE_DESCRIPTOR_SET              = 35;
    private static final int STYPE_BUFFER_CREATE_INFO                = 12;
    private static final int STYPE_MEMORY_ALLOCATE_INFO              = 5;
    private static final int STYPE_FENCE_CREATE_INFO                 = 8;
    private static final int STYPE_SUBMIT_INFO                       = 4;

    // ─── Static Vulkan resources ──────────────────────────────────────────────

    private static final Arena         ARENA;
    private static final MemorySegment SHADER_MODULE;
    private static final MemorySegment DSL;
    private static final MemorySegment PIPELINE_LAYOUT;
    private static final MemorySegment PIPELINE;
    private static final MemorySegment CMD_POOL;

    // ─── Static initializer ───────────────────────────────────────────────────

    static {
        ARENA           = Arena.ofShared();
        SHADER_MODULE   = initShaderModule();
        DSL             = initDescriptorSetLayout();
        PIPELINE_LAYOUT = initPipelineLayout();
        PIPELINE        = initPipeline();
        CMD_POOL        = initCommandPool();

        // No shutdown hook here: destroyStaticResources() is called by
        // HvtCarrierRegistry's hook before vkDestroyDevice, ensuring correct order.
    }

    // ─── Private constructor ───────────────────────────────────────────────────

    private KernelDispatcher() {}

    // ─── Dispatch context ─────────────────────────────────────────────────────

    /**
     * Carries all per-dispatch Vulkan handles across the park boundary between
     * {@link #beginDispatch} (Phase 1) and {@link #finishDispatch} (Phase 2).
     */
    private record DispatchContext(
            Arena         fenceArena,  // owns pFenceSlot; closed in finishDispatch
            MemorySegment pFenceSlot,  // &VkFence — read by GpuCompletionScheduler
            MemorySegment fence,       // VkFence handle — passed to vkDestroyFence
            MemorySegment srcBuf,      MemorySegment srcMem,
            MemorySegment dstBuf,      MemorySegment dstMem,  long dstBytes,
            MemorySegment errBuf,      MemorySegment errMem,
            MemorySegment pool,        // VkDescriptorPool
            MemorySegment cmdBuf,      // VkCommandBuffer handle
            AtomicBoolean done         // set by scheduler before unpark
    ) {}

    // ─── Public entrypoint ────────────────────────────────────────────────────

    /**
     * Submits the {@code bilinearZoom} kernel to the GPU and parks the calling
     * thread until the GPU signals completion.
     *
     * <p>The carrier thread is released during GPU execution and may run other
     * virtual threads. When the {@code VkFence} is signalled, {@link
     * GpuCompletionScheduler} unparks this thread, which then downloads the
     * result and frees device resources.
     *
     * @param memories the memory buffers bound to the kernel, in order:
     *                 {@code [0]} source pixels ({@code TO_DEVICE}),
     *                 {@code [1]} destination pixels ({@code FROM_DEVICE}),
     *                 {@code [2]} params ({@code TO_DEVICE}: srcWidth, srcHeight,
     *                 dstWidth, dstHeight)
     * @throws RuntimeException if any Vulkan call fails
     * @throws fr.dufrenoy.hvt.error.HvtKernelException if the kernel signals error
     */
    static void submit(HvtMemory<?>[] memories) {
        DispatchContext ctx;
        synchronized (KernelDispatcher.class) {
            ctx = beginDispatch(memories);
        }
        // Park the virtual thread; the carrier is free to run other threads
        // until GpuCompletionScheduler signals the fence and calls unpark().
        GpuCompletionScheduler.register(ctx.pFenceSlot(), ctx.done(), Thread.currentThread());
        while (!ctx.done().get()) {
            LockSupport.park();
        }
        synchronized (KernelDispatcher.class) {
            finishDispatch(ctx, memories);
        }
    }

    // ─── Phase 1 — setup and dispatch ────────────────────────────────────────

    private static DispatchContext beginDispatch(HvtMemory<?>[] memories) {
        int[]         params   = (int[]) memories[2].get();
        long          srcBytes = memories[0].segment().byteSize();
        long          dstBytes = memories[1].segment().byteSize();
        MemorySegment dev      = HvtCarrierRegistry.device();

        try (Arena tmp = Arena.ofConfined()) {

            // ── Step 2: allocate device buffers ───────────────────────────────
            MemorySegment pSrcBuf = tmp.allocate(vulkan_h.C_POINTER);
            MemorySegment pSrcMem = tmp.allocate(vulkan_h.C_POINTER);
            allocateBuffer(tmp, dev, srcBytes, pSrcBuf, pSrcMem);
            MemorySegment srcBuf = pSrcBuf.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            MemorySegment srcMem = pSrcMem.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

            MemorySegment pDstBuf = tmp.allocate(vulkan_h.C_POINTER);
            MemorySegment pDstMem = tmp.allocate(vulkan_h.C_POINTER);
            allocateBuffer(tmp, dev, dstBytes, pDstBuf, pDstMem);
            MemorySegment dstBuf = pDstBuf.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            MemorySegment dstMem = pDstMem.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

            // ── Step 3: upload source pixels ─────────────────────────────────
            mapCopy(tmp, dev, srcMem, srcBytes, memories[0].segment(), true);

            // ── Step 3b: allocate and zero the error buffer (1 int) ──────────
            MemorySegment pErrBuf = tmp.allocate(vulkan_h.C_POINTER);
            MemorySegment pErrMem = tmp.allocate(vulkan_h.C_POINTER);
            allocateBuffer(tmp, dev, 4L, pErrBuf, pErrMem);
            MemorySegment errBuf = pErrBuf.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            MemorySegment errMem = pErrMem.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            mapCopy(tmp, dev, errMem, 4L, tmp.allocate(ValueLayout.JAVA_INT), true);

            // ── Step 4: descriptor pool + descriptor set ──────────────────────
            MemorySegment pPool = tmp.allocate(vulkan_h.C_POINTER);
            MemorySegment pSet  = tmp.allocate(vulkan_h.C_POINTER);
            buildDescriptorSet(tmp, dev, srcBuf, srcBytes, dstBuf, dstBytes, errBuf, pPool, pSet);
            MemorySegment pool = pPool.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            MemorySegment set  = pSet.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

            // ── Step 5: record command buffer ─────────────────────────────────
            MemorySegment pCmdBuf = tmp.allocate(vulkan_h.C_POINTER);
            recordCommandBuffer(tmp, dev, set, params, pCmdBuf);
            MemorySegment cmdBuf = pCmdBuf.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

            // ── Step 6a: create fence in a shared arena ───────────────────────
            // pFenceSlot must outlive this method (read by GpuCompletionScheduler
            // from a different thread), so it lives in Arena.ofShared().
            Arena fenceArena = Arena.ofShared();
            MemorySegment pFenceSlot;
            MemorySegment fence;
            try {
                MemorySegment fenceInfo = tmp.allocate(VkFenceCreateInfo.layout());
                VkFenceCreateInfo.sType(fenceInfo, STYPE_FENCE_CREATE_INFO);
                VkFenceCreateInfo.pNext(fenceInfo, MemorySegment.NULL);
                VkFenceCreateInfo.flags(fenceInfo, 0); // unsignaled
                pFenceSlot = fenceArena.allocate(vulkan_h.C_POINTER);
                checkVulkan(
                        vulkan_h.vkCreateFence(dev, fenceInfo, MemorySegment.NULL, pFenceSlot),
                        "vkCreateFence");
                fence = pFenceSlot.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            } catch (Throwable t) {
                fenceArena.close();
                throw t;
            }

            // ── Step 6b: submit with fence ────────────────────────────────────
            MemorySegment submitInfo = tmp.allocate(VkSubmitInfo.layout());
            VkSubmitInfo.sType(submitInfo, STYPE_SUBMIT_INFO);
            VkSubmitInfo.pNext(submitInfo, MemorySegment.NULL);
            VkSubmitInfo.waitSemaphoreCount(submitInfo, 0);
            VkSubmitInfo.pWaitSemaphores(submitInfo, MemorySegment.NULL);
            VkSubmitInfo.pWaitDstStageMask(submitInfo, MemorySegment.NULL);
            VkSubmitInfo.commandBufferCount(submitInfo, 1);
            VkSubmitInfo.pCommandBuffers(submitInfo, pCmdBuf);
            VkSubmitInfo.signalSemaphoreCount(submitInfo, 0);
            VkSubmitInfo.pSignalSemaphores(submitInfo, MemorySegment.NULL);
            checkVulkan(
                    vulkan_h.vkQueueSubmit(HvtCarrierRegistry.queue(), 1, submitInfo, fence),
                    "vkQueueSubmit");

            return new DispatchContext(
                    fenceArena, pFenceSlot, fence,
                    srcBuf, srcMem,
                    dstBuf, dstMem, dstBytes,
                    errBuf, errMem,
                    pool, cmdBuf,
                    new AtomicBoolean(false));
        }
    }

    // ─── Phase 2 — result download and cleanup ────────────────────────────────

    private static void finishDispatch(DispatchContext ctx, HvtMemory<?>[] memories) {
        MemorySegment dev = HvtCarrierRegistry.device();
        try {
            try (Arena tmp = Arena.ofConfined()) {
                // ── Step 6c: read error code ──────────────────────────────────
                MemorySegment errHostSeg = tmp.allocate(ValueLayout.JAVA_INT);
                mapCopy(tmp, dev, ctx.errMem(), 4L, errHostSeg, false);
                try {
                    HvtErrorBuffer.ofCode(errHostSeg.get(ValueLayout.JAVA_INT, 0)).checkAndThrow();
                    // ── Step 7: download output (skipped if error) ────────────
                    mapCopy(tmp, dev, ctx.dstMem(), ctx.dstBytes(), memories[1].segment(), false);
                } finally {
                    // ── Step 8: per-call cleanup (always runs) ────────────────
                    MemorySegment pCmd = tmp.allocate(vulkan_h.C_POINTER);
                    pCmd.set(ValueLayout.ADDRESS, 0, ctx.cmdBuf());
                    vulkan_h.vkFreeCommandBuffers(dev, CMD_POOL, 1, pCmd);
                    vulkan_h.vkDestroyDescriptorPool(dev, ctx.pool(), MemorySegment.NULL);
                    vulkan_h.vkDestroyFence(dev, ctx.fence(), MemorySegment.NULL);
                    vulkan_h.vkFreeMemory(dev, ctx.errMem(), MemorySegment.NULL);
                    vulkan_h.vkDestroyBuffer(dev, ctx.errBuf(), MemorySegment.NULL);
                    vulkan_h.vkFreeMemory(dev, ctx.srcMem(), MemorySegment.NULL);
                    vulkan_h.vkDestroyBuffer(dev, ctx.srcBuf(), MemorySegment.NULL);
                    vulkan_h.vkFreeMemory(dev, ctx.dstMem(), MemorySegment.NULL);
                    vulkan_h.vkDestroyBuffer(dev, ctx.dstBuf(), MemorySegment.NULL);
                }
            }
        } finally {
            ctx.fenceArena().close();
        }
    }

    /**
     * Submits the {@code bilinearZoom} kernel {@code iterations} times, keeping all
     * device buffers alive across iterations (no host-device transfer per iteration).
     *
     * <p>Compared to calling {@link #submit} N times, this method pays the allocation,
     * upload, and download cost only once, isolating pure GPU compute throughput.
     *
     * <p>The command buffer is recorded once before the loop and re-submitted on each
     * iteration; after each {@code vkQueueWaitIdle} it returns to the executable state
     * (Vulkan spec §6.4) and can be re-submitted without re-recording.
     *
     * @param memories   same convention as {@link #submit}
     * @param iterations number of dispatch iterations; must be positive
     * @throws RuntimeException     if any Vulkan call fails
     * @throws fr.dufrenoy.hvt.error.HvtKernelException if the kernel signals a non-zero
     *                              error code after the last iteration
     */
    static synchronized void submitBatch(HvtMemory<?>[] memories, int iterations) {
        int[]         params   = (int[]) memories[2].get();
        long          srcBytes = memories[0].segment().byteSize();
        long          dstBytes = memories[1].segment().byteSize();
        MemorySegment dev      = HvtCarrierRegistry.device();

        try (Arena tmp = Arena.ofConfined()) {

            // ── Step 2: allocate device buffers ───────────────────────────────
            MemorySegment pSrcBuf = tmp.allocate(vulkan_h.C_POINTER);
            MemorySegment pSrcMem = tmp.allocate(vulkan_h.C_POINTER);
            allocateBuffer(tmp, dev, srcBytes, pSrcBuf, pSrcMem);
            MemorySegment srcBuf = pSrcBuf.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            MemorySegment srcMem = pSrcMem.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

            MemorySegment pDstBuf = tmp.allocate(vulkan_h.C_POINTER);
            MemorySegment pDstMem = tmp.allocate(vulkan_h.C_POINTER);
            allocateBuffer(tmp, dev, dstBytes, pDstBuf, pDstMem);
            MemorySegment dstBuf = pDstBuf.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            MemorySegment dstMem = pDstMem.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

            // ── Step 3: upload source pixels once ────────────────────────────
            mapCopy(tmp, dev, srcMem, srcBytes, memories[0].segment(), true);

            // ── Step 3b: allocate and zero the error buffer ───────────────────
            MemorySegment pErrBuf = tmp.allocate(vulkan_h.C_POINTER);
            MemorySegment pErrMem = tmp.allocate(vulkan_h.C_POINTER);
            allocateBuffer(tmp, dev, 4L, pErrBuf, pErrMem);
            MemorySegment errBuf = pErrBuf.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            MemorySegment errMem = pErrMem.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            mapCopy(tmp, dev, errMem, 4L, tmp.allocate(ValueLayout.JAVA_INT), true);

            // ── Step 4: descriptor pool + descriptor set ──────────────────────
            MemorySegment pPool = tmp.allocate(vulkan_h.C_POINTER);
            MemorySegment pSet  = tmp.allocate(vulkan_h.C_POINTER);
            buildDescriptorSet(tmp, dev, srcBuf, srcBytes, dstBuf, dstBytes, errBuf, pPool, pSet);
            MemorySegment pool = pPool.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            MemorySegment set  = pSet.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

            // ── Step 5: record command buffer once ────────────────────────────
            MemorySegment pCmdBuf = tmp.allocate(vulkan_h.C_POINTER);
            recordCommandBuffer(tmp, dev, set, params, pCmdBuf);

            // ── Step 6: submit N times, reusing the recorded command buffer ───
            MemorySegment submitInfo = tmp.allocate(VkSubmitInfo.layout());
            VkSubmitInfo.sType(submitInfo, STYPE_SUBMIT_INFO);
            VkSubmitInfo.pNext(submitInfo, MemorySegment.NULL);
            VkSubmitInfo.waitSemaphoreCount(submitInfo, 0);
            VkSubmitInfo.pWaitSemaphores(submitInfo, MemorySegment.NULL);
            VkSubmitInfo.pWaitDstStageMask(submitInfo, MemorySegment.NULL);
            VkSubmitInfo.commandBufferCount(submitInfo, 1);
            VkSubmitInfo.pCommandBuffers(submitInfo, pCmdBuf);
            VkSubmitInfo.signalSemaphoreCount(submitInfo, 0);
            VkSubmitInfo.pSignalSemaphores(submitInfo, MemorySegment.NULL);

            for (int i = 0; i < iterations; i++) {
                checkVulkan(
                        vulkan_h.vkQueueSubmit(
                                HvtCarrierRegistry.queue(), 1, submitInfo, MemorySegment.NULL),
                        "vkQueueSubmit [iter " + i + "]");
                checkVulkan(vulkan_h.vkQueueWaitIdle(HvtCarrierRegistry.queue()),
                        "vkQueueWaitIdle [iter " + i + "]");
            }

            // ── Step 6b: check error code once after all iterations ───────────
            MemorySegment errHostSeg = tmp.allocate(ValueLayout.JAVA_INT);
            mapCopy(tmp, dev, errMem, 4L, errHostSeg, false);
            HvtErrorBuffer.ofCode(errHostSeg.get(ValueLayout.JAVA_INT, 0)).checkAndThrow();

            // ── Step 7: download output pixels once ───────────────────────────
            mapCopy(tmp, dev, dstMem, dstBytes, memories[1].segment(), false);

            // ── Step 8: per-batch cleanup ─────────────────────────────────────
            vulkan_h.vkFreeCommandBuffers(dev, CMD_POOL, 1, pCmdBuf);
            vulkan_h.vkDestroyDescriptorPool(dev, pool, MemorySegment.NULL);
            vulkan_h.vkFreeMemory(dev, errMem, MemorySegment.NULL);
            vulkan_h.vkDestroyBuffer(dev, errBuf, MemorySegment.NULL);
            vulkan_h.vkFreeMemory(dev, srcMem, MemorySegment.NULL);
            vulkan_h.vkDestroyBuffer(dev, srcBuf, MemorySegment.NULL);
            vulkan_h.vkFreeMemory(dev, dstMem, MemorySegment.NULL);
            vulkan_h.vkDestroyBuffer(dev, dstBuf, MemorySegment.NULL);
        }
    }

    /**
     * Submits the {@code bilinearZoom} kernel once and writes wall-clock timings
     * for the three pipeline phases into {@code timingsNs}:
     * <ul>
     *   <li>{@code timingsNs[0]} — host→device upload ({@code vkMapMemory} + copy)</li>
     *   <li>{@code timingsNs[1]} — GPU compute ({@code vkQueueSubmit} + {@code vkQueueWaitIdle})</li>
     *   <li>{@code timingsNs[2]} — device→host download ({@code vkMapMemory} + copy)</li>
     * </ul>
     *
     * @param memories   same convention as {@link #submit}
     * @param timingsNs  output array of length ≥ 3; each element is overwritten
     */
    static synchronized void submitTimed(HvtMemory<?>[] memories, long[] timingsNs) {
        int[]         params   = (int[]) memories[2].get();
        long          srcBytes = memories[0].segment().byteSize();
        long          dstBytes = memories[1].segment().byteSize();
        MemorySegment dev      = HvtCarrierRegistry.device();

        try (Arena tmp = Arena.ofConfined()) {

            // ── Step 2: allocate device buffers ───────────────────────────────
            MemorySegment pSrcBuf = tmp.allocate(vulkan_h.C_POINTER);
            MemorySegment pSrcMem = tmp.allocate(vulkan_h.C_POINTER);
            allocateBuffer(tmp, dev, srcBytes, pSrcBuf, pSrcMem);
            MemorySegment srcBuf = pSrcBuf.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            MemorySegment srcMem = pSrcMem.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

            MemorySegment pDstBuf = tmp.allocate(vulkan_h.C_POINTER);
            MemorySegment pDstMem = tmp.allocate(vulkan_h.C_POINTER);
            allocateBuffer(tmp, dev, dstBytes, pDstBuf, pDstMem);
            MemorySegment dstBuf = pDstBuf.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            MemorySegment dstMem = pDstMem.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

            // ── Step 3: upload (timed) ────────────────────────────────────────
            long t0 = System.nanoTime();
            mapCopy(tmp, dev, srcMem, srcBytes, memories[0].segment(), true);
            timingsNs[0] = System.nanoTime() - t0;

            // ── Step 3b: allocate and zero the error buffer ───────────────────
            MemorySegment pErrBuf = tmp.allocate(vulkan_h.C_POINTER);
            MemorySegment pErrMem = tmp.allocate(vulkan_h.C_POINTER);
            allocateBuffer(tmp, dev, 4L, pErrBuf, pErrMem);
            MemorySegment errBuf = pErrBuf.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            MemorySegment errMem = pErrMem.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            mapCopy(tmp, dev, errMem, 4L, tmp.allocate(ValueLayout.JAVA_INT), true);

            // ── Step 4: descriptor pool + descriptor set ──────────────────────
            MemorySegment pPool = tmp.allocate(vulkan_h.C_POINTER);
            MemorySegment pSet  = tmp.allocate(vulkan_h.C_POINTER);
            buildDescriptorSet(tmp, dev, srcBuf, srcBytes, dstBuf, dstBytes, errBuf, pPool, pSet);
            MemorySegment pool = pPool.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            MemorySegment set  = pSet.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

            // ── Step 5: record command buffer ─────────────────────────────────
            MemorySegment pCmdBuf = tmp.allocate(vulkan_h.C_POINTER);
            recordCommandBuffer(tmp, dev, set, params, pCmdBuf);

            // ── Step 6: submit + wait (timed) ─────────────────────────────────
            MemorySegment submitInfo = tmp.allocate(VkSubmitInfo.layout());
            VkSubmitInfo.sType(submitInfo, STYPE_SUBMIT_INFO);
            VkSubmitInfo.pNext(submitInfo, MemorySegment.NULL);
            VkSubmitInfo.waitSemaphoreCount(submitInfo, 0);
            VkSubmitInfo.pWaitSemaphores(submitInfo, MemorySegment.NULL);
            VkSubmitInfo.pWaitDstStageMask(submitInfo, MemorySegment.NULL);
            VkSubmitInfo.commandBufferCount(submitInfo, 1);
            VkSubmitInfo.pCommandBuffers(submitInfo, pCmdBuf);
            VkSubmitInfo.signalSemaphoreCount(submitInfo, 0);
            VkSubmitInfo.pSignalSemaphores(submitInfo, MemorySegment.NULL);
            t0 = System.nanoTime();
            checkVulkan(
                    vulkan_h.vkQueueSubmit(
                            HvtCarrierRegistry.queue(), 1, submitInfo, MemorySegment.NULL),
                    "vkQueueSubmit");
            checkVulkan(vulkan_h.vkQueueWaitIdle(HvtCarrierRegistry.queue()), "vkQueueWaitIdle");
            timingsNs[1] = System.nanoTime() - t0;

            // ── Step 6b: check error code ─────────────────────────────────────
            MemorySegment errHostSeg = tmp.allocate(ValueLayout.JAVA_INT);
            mapCopy(tmp, dev, errMem, 4L, errHostSeg, false);
            HvtErrorBuffer.ofCode(errHostSeg.get(ValueLayout.JAVA_INT, 0)).checkAndThrow();

            // ── Step 7: download (timed) ──────────────────────────────────────
            t0 = System.nanoTime();
            mapCopy(tmp, dev, dstMem, dstBytes, memories[1].segment(), false);
            timingsNs[2] = System.nanoTime() - t0;

            // ── Step 8: per-call cleanup ──────────────────────────────────────
            vulkan_h.vkFreeCommandBuffers(dev, CMD_POOL, 1, pCmdBuf);
            vulkan_h.vkDestroyDescriptorPool(dev, pool, MemorySegment.NULL);
            vulkan_h.vkFreeMemory(dev, errMem, MemorySegment.NULL);
            vulkan_h.vkDestroyBuffer(dev, errBuf, MemorySegment.NULL);
            vulkan_h.vkFreeMemory(dev, srcMem, MemorySegment.NULL);
            vulkan_h.vkDestroyBuffer(dev, srcBuf, MemorySegment.NULL);
            vulkan_h.vkFreeMemory(dev, dstMem, MemorySegment.NULL);
            vulkan_h.vkDestroyBuffer(dev, dstBuf, MemorySegment.NULL);
        }
    }

    /**
     * Destroys all static Vulkan resources owned by this class.
     * Must be called by {@link HvtCarrierRegistry}'s shutdown hook before
     * {@code vkDestroyDevice}, so that the device handle is still valid.
     */
    static void destroyStaticResources() {
        MemorySegment dev = HvtCarrierRegistry.device();
        vulkan_h.vkDestroyCommandPool(dev, CMD_POOL, MemorySegment.NULL);
        vulkan_h.vkDestroyPipeline(dev, PIPELINE, MemorySegment.NULL);
        vulkan_h.vkDestroyPipelineLayout(dev, PIPELINE_LAYOUT, MemorySegment.NULL);
        vulkan_h.vkDestroyDescriptorSetLayout(dev, DSL, MemorySegment.NULL);
        vulkan_h.vkDestroyShaderModule(dev, SHADER_MODULE, MemorySegment.NULL);
        ARENA.close();
    }

    // ─── Static initialisation helpers ────────────────────────────────────────

    private static MemorySegment initShaderModule() {
        byte[] spirv = KernelCompiler.compileBilinearZoom();
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment spirvSeg = tmp.allocate(spirv.length);
            spirvSeg.copyFrom(MemorySegment.ofArray(spirv));

            MemorySegment createInfo = tmp.allocate(VkShaderModuleCreateInfo.layout());
            VkShaderModuleCreateInfo.sType(createInfo, STYPE_SHADER_MODULE_CREATE_INFO);
            VkShaderModuleCreateInfo.pNext(createInfo, MemorySegment.NULL);
            VkShaderModuleCreateInfo.flags(createInfo, 0);
            VkShaderModuleCreateInfo.codeSize(createInfo, spirv.length);
            VkShaderModuleCreateInfo.pCode(createInfo, spirvSeg);

            MemorySegment pModule = ARENA.allocate(vulkan_h.C_POINTER);
            checkVulkan(
                    vulkan_h.vkCreateShaderModule(
                            HvtCarrierRegistry.device(), createInfo, MemorySegment.NULL, pModule),
                    "vkCreateShaderModule");
            return pModule.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
        }
    }

    private static MemorySegment initDescriptorSetLayout() {
        try (Arena tmp = Arena.ofConfined()) {
            long stride   = VkDescriptorSetLayoutBinding.layout().byteSize();
            MemorySegment bindings = tmp.allocate(VkDescriptorSetLayoutBinding.layout(), 3);

            MemorySegment b0 = bindings.asSlice(0, stride);
            VkDescriptorSetLayoutBinding.binding(b0, 0);
            VkDescriptorSetLayoutBinding.descriptorType(b0, vulkan_h.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER());
            VkDescriptorSetLayoutBinding.descriptorCount(b0, 1);
            VkDescriptorSetLayoutBinding.stageFlags(b0, vulkan_h.VK_SHADER_STAGE_COMPUTE_BIT());
            VkDescriptorSetLayoutBinding.pImmutableSamplers(b0, MemorySegment.NULL);

            MemorySegment b1 = bindings.asSlice(stride, stride);
            VkDescriptorSetLayoutBinding.binding(b1, 1);
            VkDescriptorSetLayoutBinding.descriptorType(b1, vulkan_h.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER());
            VkDescriptorSetLayoutBinding.descriptorCount(b1, 1);
            VkDescriptorSetLayoutBinding.stageFlags(b1, vulkan_h.VK_SHADER_STAGE_COMPUTE_BIT());
            VkDescriptorSetLayoutBinding.pImmutableSamplers(b1, MemorySegment.NULL);

            MemorySegment b2 = bindings.asSlice(2 * stride, stride);
            VkDescriptorSetLayoutBinding.binding(b2, 2);
            VkDescriptorSetLayoutBinding.descriptorType(b2, vulkan_h.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER());
            VkDescriptorSetLayoutBinding.descriptorCount(b2, 1);
            VkDescriptorSetLayoutBinding.stageFlags(b2, vulkan_h.VK_SHADER_STAGE_COMPUTE_BIT());
            VkDescriptorSetLayoutBinding.pImmutableSamplers(b2, MemorySegment.NULL);

            MemorySegment createInfo = tmp.allocate(VkDescriptorSetLayoutCreateInfo.layout());
            VkDescriptorSetLayoutCreateInfo.sType(createInfo, STYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            VkDescriptorSetLayoutCreateInfo.pNext(createInfo, MemorySegment.NULL);
            VkDescriptorSetLayoutCreateInfo.flags(createInfo, 0);
            VkDescriptorSetLayoutCreateInfo.bindingCount(createInfo, 3);
            VkDescriptorSetLayoutCreateInfo.pBindings(createInfo, bindings);

            MemorySegment pDsl = ARENA.allocate(vulkan_h.C_POINTER);
            checkVulkan(
                    vulkan_h.vkCreateDescriptorSetLayout(
                            HvtCarrierRegistry.device(), createInfo, MemorySegment.NULL, pDsl),
                    "vkCreateDescriptorSetLayout");
            return pDsl.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
        }
    }

    private static MemorySegment initPipelineLayout() {
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment pushRange = tmp.allocate(VkPushConstantRange.layout());
            VkPushConstantRange.stageFlags(pushRange, vulkan_h.VK_SHADER_STAGE_COMPUTE_BIT());
            VkPushConstantRange.offset(pushRange, 0);
            VkPushConstantRange.size(pushRange, 16); // 4 × int: srcW, srcH, dstW, dstH

            MemorySegment pDslArray = tmp.allocate(vulkan_h.C_POINTER);
            pDslArray.set(ValueLayout.ADDRESS, 0, DSL);

            MemorySegment createInfo = tmp.allocate(VkPipelineLayoutCreateInfo.layout());
            VkPipelineLayoutCreateInfo.sType(createInfo, STYPE_PIPELINE_LAYOUT_CREATE_INFO);
            VkPipelineLayoutCreateInfo.pNext(createInfo, MemorySegment.NULL);
            VkPipelineLayoutCreateInfo.flags(createInfo, 0);
            VkPipelineLayoutCreateInfo.setLayoutCount(createInfo, 1);
            VkPipelineLayoutCreateInfo.pSetLayouts(createInfo, pDslArray);
            VkPipelineLayoutCreateInfo.pushConstantRangeCount(createInfo, 1);
            VkPipelineLayoutCreateInfo.pPushConstantRanges(createInfo, pushRange);

            MemorySegment pLayout = ARENA.allocate(vulkan_h.C_POINTER);
            checkVulkan(
                    vulkan_h.vkCreatePipelineLayout(
                            HvtCarrierRegistry.device(), createInfo, MemorySegment.NULL, pLayout),
                    "vkCreatePipelineLayout");
            return pLayout.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
        }
    }

    private static MemorySegment initPipeline() {
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment stageSeg = tmp.allocate(VkPipelineShaderStageCreateInfo.layout());
            VkPipelineShaderStageCreateInfo.sType(stageSeg, STYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            VkPipelineShaderStageCreateInfo.pNext(stageSeg, MemorySegment.NULL);
            VkPipelineShaderStageCreateInfo.flags(stageSeg, 0);
            VkPipelineShaderStageCreateInfo.stage(stageSeg, vulkan_h.VK_SHADER_STAGE_COMPUTE_BIT());
            VkPipelineShaderStageCreateInfo.module(stageSeg, SHADER_MODULE);
            VkPipelineShaderStageCreateInfo.pName(stageSeg, tmp.allocateFrom("main"));
            VkPipelineShaderStageCreateInfo.pSpecializationInfo(stageSeg, MemorySegment.NULL);

            MemorySegment createInfo = tmp.allocate(VkComputePipelineCreateInfo.layout());
            VkComputePipelineCreateInfo.sType(createInfo, STYPE_COMPUTE_PIPELINE_CREATE_INFO);
            VkComputePipelineCreateInfo.pNext(createInfo, MemorySegment.NULL);
            VkComputePipelineCreateInfo.flags(createInfo, 0);
            VkComputePipelineCreateInfo.stage(createInfo, stageSeg);
            VkComputePipelineCreateInfo.layout(createInfo, PIPELINE_LAYOUT);
            VkComputePipelineCreateInfo.basePipelineHandle(createInfo, MemorySegment.NULL);
            VkComputePipelineCreateInfo.basePipelineIndex(createInfo, -1);

            MemorySegment pPipeline = ARENA.allocate(vulkan_h.C_POINTER);
            checkVulkan(
                    vulkan_h.vkCreateComputePipelines(
                            HvtCarrierRegistry.device(), MemorySegment.NULL,
                            1, createInfo, MemorySegment.NULL, pPipeline),
                    "vkCreateComputePipelines");
            return pPipeline.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
        }
    }

    private static MemorySegment initCommandPool() {
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment createInfo = tmp.allocate(VkCommandPoolCreateInfo.layout());
            VkCommandPoolCreateInfo.sType(createInfo, STYPE_COMMAND_POOL_CREATE_INFO);
            VkCommandPoolCreateInfo.pNext(createInfo, MemorySegment.NULL);
            VkCommandPoolCreateInfo.flags(createInfo, vulkan_h.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT());
            VkCommandPoolCreateInfo.queueFamilyIndex(createInfo, HvtCarrierRegistry.computeQueueFamily());

            MemorySegment pPool = ARENA.allocate(vulkan_h.C_POINTER);
            checkVulkan(
                    vulkan_h.vkCreateCommandPool(
                            HvtCarrierRegistry.device(), createInfo, MemorySegment.NULL, pPool),
                    "vkCreateCommandPool");
            return pPool.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
        }
    }

    // ─── Per-call helpers ─────────────────────────────────────────────────────

    private static void allocateBuffer(Arena tmp, MemorySegment dev, long size,
                                       MemorySegment pBufOut, MemorySegment pMemOut) {
        MemorySegment bufInfo = tmp.allocate(VkBufferCreateInfo.layout());
        VkBufferCreateInfo.sType(bufInfo, STYPE_BUFFER_CREATE_INFO);
        VkBufferCreateInfo.pNext(bufInfo, MemorySegment.NULL);
        VkBufferCreateInfo.flags(bufInfo, 0);
        VkBufferCreateInfo.size(bufInfo, size);
        VkBufferCreateInfo.usage(bufInfo, vulkan_h.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT());
        VkBufferCreateInfo.sharingMode(bufInfo, 0); // VK_SHARING_MODE_EXCLUSIVE
        VkBufferCreateInfo.queueFamilyIndexCount(bufInfo, 0);
        VkBufferCreateInfo.pQueueFamilyIndices(bufInfo, MemorySegment.NULL);
        checkVulkan(vulkan_h.vkCreateBuffer(dev, bufInfo, MemorySegment.NULL, pBufOut),
                "vkCreateBuffer");

        MemorySegment buf = pBufOut.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

        MemorySegment req = tmp.allocate(VkMemoryRequirements.layout());
        vulkan_h.vkGetBufferMemoryRequirements(dev, buf, req);

        int memTypeIdx = findMemoryType(tmp,
                VkMemoryRequirements.memoryTypeBits(req),
                vulkan_h.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT()
                        | vulkan_h.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT());

        MemorySegment allocInfo = tmp.allocate(VkMemoryAllocateInfo.layout());
        VkMemoryAllocateInfo.sType(allocInfo, STYPE_MEMORY_ALLOCATE_INFO);
        VkMemoryAllocateInfo.pNext(allocInfo, MemorySegment.NULL);
        VkMemoryAllocateInfo.allocationSize(allocInfo, VkMemoryRequirements.size(req));
        VkMemoryAllocateInfo.memoryTypeIndex(allocInfo, memTypeIdx);
        checkVulkan(vulkan_h.vkAllocateMemory(dev, allocInfo, MemorySegment.NULL, pMemOut),
                "vkAllocateMemory");

        MemorySegment mem = pMemOut.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
        checkVulkan(vulkan_h.vkBindBufferMemory(dev, buf, mem, 0), "vkBindBufferMemory");
    }

    private static int findMemoryType(Arena tmp, int typeBits, int requiredFlags) {
        MemorySegment props = tmp.allocate(VkPhysicalDeviceMemoryProperties.layout());
        vulkan_h.vkGetPhysicalDeviceMemoryProperties(HvtCarrierRegistry.physicalDevice(), props);
        int count = VkPhysicalDeviceMemoryProperties.memoryTypeCount(props);
        for (int i = 0; i < count; i++) {
            if ((typeBits & (1 << i)) == 0) {
                continue;
            }
            MemorySegment mt = VkPhysicalDeviceMemoryProperties.memoryTypes(props, i);
            if ((VkMemoryType.propertyFlags(mt) & requiredFlags) == requiredFlags) {
                return i;
            }
        }
        throw new RuntimeException(
                "no suitable Vulkan memory type (typeBits=0x" + Integer.toHexString(typeBits)
                        + ", requiredFlags=0x" + Integer.toHexString(requiredFlags) + ")");
    }

    private static void mapCopy(Arena tmp, MemorySegment dev, MemorySegment mem, long size,
                                MemorySegment hostSeg, boolean upload) {
        MemorySegment ppData = tmp.allocate(vulkan_h.C_POINTER);
        checkVulkan(vulkan_h.vkMapMemory(dev, mem, 0L, size, 0, ppData), "vkMapMemory");
        MemorySegment mapped = ppData.get(ValueLayout.ADDRESS, 0).reinterpret(size);
        if (upload) {
            mapped.copyFrom(hostSeg);
        } else {
            hostSeg.copyFrom(mapped);
        }
        vulkan_h.vkUnmapMemory(dev, mem);
    }

    private static void buildDescriptorSet(Arena tmp, MemorySegment dev,
                                           MemorySegment srcBuf, long srcBytes,
                                           MemorySegment dstBuf, long dstBytes,
                                           MemorySegment errBuf,
                                           MemorySegment pPoolOut, MemorySegment pSetOut) {
        MemorySegment poolSize = tmp.allocate(VkDescriptorPoolSize.layout());
        VkDescriptorPoolSize.type(poolSize, vulkan_h.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER());
        VkDescriptorPoolSize.descriptorCount(poolSize, 3);

        MemorySegment poolInfo = tmp.allocate(VkDescriptorPoolCreateInfo.layout());
        VkDescriptorPoolCreateInfo.sType(poolInfo, STYPE_DESCRIPTOR_POOL_CREATE_INFO);
        VkDescriptorPoolCreateInfo.pNext(poolInfo, MemorySegment.NULL);
        VkDescriptorPoolCreateInfo.flags(poolInfo, 0);
        VkDescriptorPoolCreateInfo.maxSets(poolInfo, 1);
        VkDescriptorPoolCreateInfo.poolSizeCount(poolInfo, 1);
        VkDescriptorPoolCreateInfo.pPoolSizes(poolInfo, poolSize);
        checkVulkan(vulkan_h.vkCreateDescriptorPool(dev, poolInfo, MemorySegment.NULL, pPoolOut),
                "vkCreateDescriptorPool");
        MemorySegment pool = pPoolOut.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

        MemorySegment pDslArray = tmp.allocate(vulkan_h.C_POINTER);
        pDslArray.set(ValueLayout.ADDRESS, 0, DSL);

        MemorySegment allocInfo = tmp.allocate(VkDescriptorSetAllocateInfo.layout());
        VkDescriptorSetAllocateInfo.sType(allocInfo, STYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
        VkDescriptorSetAllocateInfo.pNext(allocInfo, MemorySegment.NULL);
        VkDescriptorSetAllocateInfo.descriptorPool(allocInfo, pool);
        VkDescriptorSetAllocateInfo.descriptorSetCount(allocInfo, 1);
        VkDescriptorSetAllocateInfo.pSetLayouts(allocInfo, pDslArray);
        checkVulkan(vulkan_h.vkAllocateDescriptorSets(dev, allocInfo, pSetOut),
                "vkAllocateDescriptorSets");
        MemorySegment set = pSetOut.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

        long          dbiStride = VkDescriptorBufferInfo.layout().byteSize();
        MemorySegment bufInfos  = tmp.allocate(VkDescriptorBufferInfo.layout(), 3);

        MemorySegment dbi0 = bufInfos.asSlice(0, dbiStride);
        VkDescriptorBufferInfo.buffer(dbi0, srcBuf);
        VkDescriptorBufferInfo.offset(dbi0, 0L);
        VkDescriptorBufferInfo.range(dbi0, srcBytes);

        MemorySegment dbi1 = bufInfos.asSlice(dbiStride, dbiStride);
        VkDescriptorBufferInfo.buffer(dbi1, dstBuf);
        VkDescriptorBufferInfo.offset(dbi1, 0L);
        VkDescriptorBufferInfo.range(dbi1, dstBytes);

        MemorySegment dbi2 = bufInfos.asSlice(2 * dbiStride, dbiStride);
        VkDescriptorBufferInfo.buffer(dbi2, errBuf);
        VkDescriptorBufferInfo.offset(dbi2, 0L);
        VkDescriptorBufferInfo.range(dbi2, 4L);

        long          wdsStride = VkWriteDescriptorSet.layout().byteSize();
        MemorySegment writes    = tmp.allocate(VkWriteDescriptorSet.layout(), 3);

        MemorySegment w0 = writes.asSlice(0, wdsStride);
        VkWriteDescriptorSet.sType(w0, STYPE_WRITE_DESCRIPTOR_SET);
        VkWriteDescriptorSet.pNext(w0, MemorySegment.NULL);
        VkWriteDescriptorSet.dstSet(w0, set);
        VkWriteDescriptorSet.dstBinding(w0, 0);
        VkWriteDescriptorSet.dstArrayElement(w0, 0);
        VkWriteDescriptorSet.descriptorCount(w0, 1);
        VkWriteDescriptorSet.descriptorType(w0, vulkan_h.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER());
        VkWriteDescriptorSet.pImageInfo(w0, MemorySegment.NULL);
        VkWriteDescriptorSet.pBufferInfo(w0, dbi0);
        VkWriteDescriptorSet.pTexelBufferView(w0, MemorySegment.NULL);

        MemorySegment w1 = writes.asSlice(wdsStride, wdsStride);
        VkWriteDescriptorSet.sType(w1, STYPE_WRITE_DESCRIPTOR_SET);
        VkWriteDescriptorSet.pNext(w1, MemorySegment.NULL);
        VkWriteDescriptorSet.dstSet(w1, set);
        VkWriteDescriptorSet.dstBinding(w1, 1);
        VkWriteDescriptorSet.dstArrayElement(w1, 0);
        VkWriteDescriptorSet.descriptorCount(w1, 1);
        VkWriteDescriptorSet.descriptorType(w1, vulkan_h.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER());
        VkWriteDescriptorSet.pImageInfo(w1, MemorySegment.NULL);
        VkWriteDescriptorSet.pBufferInfo(w1, dbi1);
        VkWriteDescriptorSet.pTexelBufferView(w1, MemorySegment.NULL);

        MemorySegment w2 = writes.asSlice(2 * wdsStride, wdsStride);
        VkWriteDescriptorSet.sType(w2, STYPE_WRITE_DESCRIPTOR_SET);
        VkWriteDescriptorSet.pNext(w2, MemorySegment.NULL);
        VkWriteDescriptorSet.dstSet(w2, set);
        VkWriteDescriptorSet.dstBinding(w2, 2);
        VkWriteDescriptorSet.dstArrayElement(w2, 0);
        VkWriteDescriptorSet.descriptorCount(w2, 1);
        VkWriteDescriptorSet.descriptorType(w2, vulkan_h.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER());
        VkWriteDescriptorSet.pImageInfo(w2, MemorySegment.NULL);
        VkWriteDescriptorSet.pBufferInfo(w2, dbi2);
        VkWriteDescriptorSet.pTexelBufferView(w2, MemorySegment.NULL);

        vulkan_h.vkUpdateDescriptorSets(dev, 3, writes, 0, MemorySegment.NULL);
    }

    private static void recordCommandBuffer(Arena tmp, MemorySegment dev,
                                            MemorySegment set, int[] params,
                                            MemorySegment pCmdBufOut) {
        MemorySegment allocInfo = tmp.allocate(VkCommandBufferAllocateInfo.layout());
        VkCommandBufferAllocateInfo.sType(allocInfo, STYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        VkCommandBufferAllocateInfo.pNext(allocInfo, MemorySegment.NULL);
        VkCommandBufferAllocateInfo.commandPool(allocInfo, CMD_POOL);
        VkCommandBufferAllocateInfo.level(allocInfo, 0); // VK_COMMAND_BUFFER_LEVEL_PRIMARY
        VkCommandBufferAllocateInfo.commandBufferCount(allocInfo, 1);
        checkVulkan(vulkan_h.vkAllocateCommandBuffers(dev, allocInfo, pCmdBufOut),
                "vkAllocateCommandBuffers");
        MemorySegment cmdBuf = pCmdBufOut.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

        MemorySegment beginInfo = tmp.allocate(VkCommandBufferBeginInfo.layout());
        VkCommandBufferBeginInfo.sType(beginInfo, STYPE_COMMAND_BUFFER_BEGIN_INFO);
        VkCommandBufferBeginInfo.pNext(beginInfo, MemorySegment.NULL);
        VkCommandBufferBeginInfo.flags(beginInfo, 0);
        VkCommandBufferBeginInfo.pInheritanceInfo(beginInfo, MemorySegment.NULL);
        checkVulkan(vulkan_h.vkBeginCommandBuffer(cmdBuf, beginInfo), "vkBeginCommandBuffer");

        vulkan_h.vkCmdBindPipeline(cmdBuf, vulkan_h.VK_PIPELINE_BIND_POINT_COMPUTE(), PIPELINE);

        MemorySegment pSet = tmp.allocate(vulkan_h.C_POINTER);
        pSet.set(ValueLayout.ADDRESS, 0, set);
        vulkan_h.vkCmdBindDescriptorSets(
                cmdBuf, vulkan_h.VK_PIPELINE_BIND_POINT_COMPUTE(), PIPELINE_LAYOUT,
                0, 1, pSet, 0, MemorySegment.NULL);

        MemorySegment pushSeg = tmp.allocate(vulkan_h.C_INT, 4);
        pushSeg.setAtIndex(ValueLayout.JAVA_INT, 0, params[0]); // srcW
        pushSeg.setAtIndex(ValueLayout.JAVA_INT, 1, params[1]); // srcH
        pushSeg.setAtIndex(ValueLayout.JAVA_INT, 2, params[2]); // dstW
        pushSeg.setAtIndex(ValueLayout.JAVA_INT, 3, params[3]); // dstH
        vulkan_h.vkCmdPushConstants(
                cmdBuf, PIPELINE_LAYOUT, vulkan_h.VK_SHADER_STAGE_COMPUTE_BIT(), 0, 16, pushSeg);

        int groupX = (params[2] + 15) / 16;
        int groupY = (params[3] + 15) / 16;
        vulkan_h.vkCmdDispatch(cmdBuf, groupX, groupY, 1);

        checkVulkan(vulkan_h.vkEndCommandBuffer(cmdBuf), "vkEndCommandBuffer");
    }

    // ─── Error checking ────────────────────────────────────────────────────────

    private static void checkVulkan(int result, String context) {
        if (result != vulkan_h.VK_SUCCESS()) {
            throw new RuntimeException(context + " returned VkResult=" + result);
        }
    }
}