/*
 * HvtCarrierRegistry.java
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
import fr.dufrenoy.hvt.runtime.vulkan.VkApplicationInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkDeviceCreateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkDeviceQueueCreateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkInstanceCreateInfo;
import fr.dufrenoy.hvt.runtime.vulkan.VkQueueFamilyProperties;
import fr.dufrenoy.hvt.runtime.vulkan.vulkan_h;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Singleton registry that initialises a Vulkan Compute device and routes
 * kernel dispatches to the GPU or to a CPU fallback.
 *
 * <p>Vulkan is initialised once when this class is first loaded. If no
 * compute-capable Vulkan device is found, or if initialisation fails for any
 * reason, {@link #gpuAvailable()} returns {@code false} and every call to
 * {@link #dispatch} falls back to CPU execution via {@link HvtKernel#execute}.
 *
 * <p>On JVM shutdown, the Vulkan logical device and instance are destroyed
 * and the persistent off-heap arena is closed.
 *
 * <p><b>POC limitation:</b> {@link KernelDispatcher#submit} always executes
 * the {@code bilinearZoom} kernel. The binding between an {@link HvtKernel}
 * instance and a SPIR-V binary is not resolved at runtime.
 */
public final class HvtCarrierRegistry {

    // ─── VkStructureType integer constants (Vulkan spec §44.1) ───────────────

    private static final int STYPE_APPLICATION_INFO       = 0;
    private static final int STYPE_INSTANCE_CREATE_INFO   = 1;
    private static final int STYPE_DEVICE_QUEUE_CREATE_INFO = 2;
    private static final int STYPE_DEVICE_CREATE_INFO     = 3;

    // VK_MAKE_API_VERSION(0, 1, 3, 0) = (1 << 22) | (3 << 12)
    private static final int VK_API_VERSION_1_3 = (1 << 22) | (3 << 12);

    // ─── Static state ─────────────────────────────────────────────────────────

    private static final Arena PERSISTENT_ARENA = Arena.ofShared();

    private static final boolean       GPU_AVAILABLE;
    private static final MemorySegment INSTANCE_SEG;
    private static final MemorySegment PHYSICAL_DEVICE_SEG;
    private static final MemorySegment DEVICE_SEG;
    private static final MemorySegment QUEUE_SEG;
    private static final int           COMPUTE_QUEUE_FAMILY;

    // ─── Static initializer ───────────────────────────────────────────────────

    static {
        MemorySegment instance    = MemorySegment.NULL;
        MemorySegment physDev    = MemorySegment.NULL;
        MemorySegment device      = MemorySegment.NULL;
        MemorySegment queue       = MemorySegment.NULL;
        int           queueFamily = -1;
        boolean       gpu         = false;

        try {
            // vulkan-1.dll must be loaded before the jextract SymbolLookup is initialised.
            // On Windows, vulkan-1.dll is installed in System32 by the Vulkan SDK.
            System.loadLibrary("vulkan-1");
            instance    = createInstance();
            int[] qf    = new int[1];
            physDev     = selectPhysicalDevice(instance, qf);
            queueFamily = qf[0];
            device      = createDevice(physDev, queueFamily);
            queue       = retrieveQueue(device, queueFamily);
            gpu         = true;
        } catch (Throwable t) {
            System.err.println("[HVT] GPU unavailable ("
                    + t.getClass().getSimpleName() + "): " + t.getMessage());
        }

        GPU_AVAILABLE        = gpu;
        INSTANCE_SEG         = instance;
        PHYSICAL_DEVICE_SEG  = physDev;
        DEVICE_SEG           = device;
        QUEUE_SEG            = queue;
        COMPUTE_QUEUE_FAMILY = queueFamily;

        if (gpu) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                KernelDispatcher.destroyStaticResources();
                vulkan_h.vkDestroyDevice(DEVICE_SEG, MemorySegment.NULL);
                vulkan_h.vkDestroyInstance(INSTANCE_SEG, MemorySegment.NULL);
                PERSISTENT_ARENA.close();
            }));
        }
    }

    // ─── Private constructor ───────────────────────────────────────────────────

    private HvtCarrierRegistry() {}

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Routes a kernel dispatch to the preferred accelerator, falling back to
     * the alternative if the preferred device is unavailable.
     *
     * <p>If {@code preferred} is {@link AcceleratorType#GPU} and a
     * compute-capable Vulkan device was found at initialisation time, the
     * kernel is submitted via {@link KernelDispatcher}. Otherwise it is
     * executed directly on the calling thread.
     *
     * @param preferred the preferred accelerator type
     * @param fallback  the fallback accelerator type
     * @param kernel    the kernel to execute on CPU fallback
     * @param memories  the memory buffers bound to the kernel
     */
    public static void dispatch(AcceleratorType preferred,
                                AcceleratorType fallback,
                                HvtKernel kernel,
                                HvtMemory<?>[] memories) {
        if (preferred == AcceleratorType.GPU && GPU_AVAILABLE) {
            KernelDispatcher.submit(memories);
        } else {
            kernel.execute(memories);
        }
    }

    // ─── Package-private accessors (used by KernelDispatcher) ─────────────────

    /**
     * Returns the {@code VkDevice} handle, or {@link MemorySegment#NULL} if
     * the GPU is unavailable.
     */
    static MemorySegment device() {
        return DEVICE_SEG;
    }

    /**
     * Returns the {@code VkQueue} compute handle, or {@link MemorySegment#NULL}
     * if the GPU is unavailable.
     */
    static MemorySegment queue() {
        return QUEUE_SEG;
    }

    /**
     * Returns the compute queue family index, or {@code -1} if the GPU is
     * unavailable.
     */
    static int computeQueueFamily() {
        return COMPUTE_QUEUE_FAMILY;
    }

    /**
     * Returns the {@code VkPhysicalDevice} handle, or {@link MemorySegment#NULL}
     * if the GPU is unavailable.
     */
    static MemorySegment physicalDevice() {
        return PHYSICAL_DEVICE_SEG;
    }

    /**
     * Returns {@code true} if a compute-capable Vulkan device was successfully
     * initialised.
     */
    static boolean gpuAvailable() {
        return GPU_AVAILABLE;
    }

    // ─── Phase 1 — Vulkan instance creation ───────────────────────────────────

    private static MemorySegment createInstance() {
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment appInfoSeg = tmp.allocate(VkApplicationInfo.layout());
            VkApplicationInfo.sType(appInfoSeg, STYPE_APPLICATION_INFO);
            VkApplicationInfo.pNext(appInfoSeg, MemorySegment.NULL);
            VkApplicationInfo.pApplicationName(appInfoSeg, tmp.allocateFrom("hvt-prototype"));
            VkApplicationInfo.applicationVersion(appInfoSeg, 1);
            VkApplicationInfo.pEngineName(appInfoSeg, tmp.allocateFrom("HVT"));
            VkApplicationInfo.engineVersion(appInfoSeg, 1);
            VkApplicationInfo.apiVersion(appInfoSeg, VK_API_VERSION_1_3);

            MemorySegment createInfoSeg = tmp.allocate(VkInstanceCreateInfo.layout());
            VkInstanceCreateInfo.sType(createInfoSeg, STYPE_INSTANCE_CREATE_INFO);
            VkInstanceCreateInfo.pNext(createInfoSeg, MemorySegment.NULL);
            VkInstanceCreateInfo.flags(createInfoSeg, 0);
            VkInstanceCreateInfo.pApplicationInfo(createInfoSeg, appInfoSeg);
            VkInstanceCreateInfo.enabledLayerCount(createInfoSeg, 0);
            VkInstanceCreateInfo.ppEnabledLayerNames(createInfoSeg, MemorySegment.NULL);
            VkInstanceCreateInfo.enabledExtensionCount(createInfoSeg, 0);
            VkInstanceCreateInfo.ppEnabledExtensionNames(createInfoSeg, MemorySegment.NULL);

            MemorySegment pInstanceSlot = PERSISTENT_ARENA.allocate(vulkan_h.C_POINTER);
            checkVulkan(
                    vulkan_h.vkCreateInstance(createInfoSeg, MemorySegment.NULL, pInstanceSlot),
                    "vkCreateInstance");
            return pInstanceSlot.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
        }
    }

    // ─── Phase 2 — Physical device selection ──────────────────────────────────

    private static MemorySegment selectPhysicalDevice(MemorySegment instance, int[] queueFamilyOut) {
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment countSeg = tmp.allocate(vulkan_h.C_INT);
            checkVulkan(
                    vulkan_h.vkEnumeratePhysicalDevices(instance, countSeg, MemorySegment.NULL),
                    "vkEnumeratePhysicalDevices (count)");
            int count = countSeg.get(ValueLayout.JAVA_INT, 0);
            if (count == 0) {
                throw new RuntimeException("no Vulkan physical device found");
            }

            MemorySegment devicesSeg = tmp.allocate(vulkan_h.C_POINTER, count);
            checkVulkan(
                    vulkan_h.vkEnumeratePhysicalDevices(instance, countSeg, devicesSeg),
                    "vkEnumeratePhysicalDevices (enumerate)");

            for (int i = 0; i < count; i++) {
                MemorySegment candidate = devicesSeg.getAtIndex(ValueLayout.ADDRESS, i)
                                                    .reinterpret(Long.MAX_VALUE);
                int qf = findComputeQueueFamily(candidate);
                if (qf >= 0) {
                    queueFamilyOut[0] = qf;
                    return candidate;
                }
            }
            throw new RuntimeException("no compute-capable Vulkan device found");
        }
    }

    private static int findComputeQueueFamily(MemorySegment physDev) {
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment countSeg = tmp.allocate(vulkan_h.C_INT);
            vulkan_h.vkGetPhysicalDeviceQueueFamilyProperties(physDev, countSeg, MemorySegment.NULL);
            int n = countSeg.get(ValueLayout.JAVA_INT, 0);

            long stride = VkQueueFamilyProperties.layout().byteSize();
            MemorySegment propsSeg = tmp.allocate(VkQueueFamilyProperties.layout(), n);
            vulkan_h.vkGetPhysicalDeviceQueueFamilyProperties(physDev, countSeg, propsSeg);

            for (int i = 0; i < n; i++) {
                MemorySegment elem = propsSeg.asSlice(i * stride, stride);
                if ((VkQueueFamilyProperties.queueFlags(elem) & vulkan_h.VK_QUEUE_COMPUTE_BIT()) != 0) {
                    return i;
                }
            }
            return -1;
        }
    }

    // ─── Phase 3 — Logical device creation ────────────────────────────────────

    private static MemorySegment createDevice(MemorySegment physDev, int queueFamily) {
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment prioritySeg = tmp.allocate(vulkan_h.C_FLOAT);
            prioritySeg.set(ValueLayout.JAVA_FLOAT, 0, 1.0f);

            MemorySegment queueInfoSeg = tmp.allocate(VkDeviceQueueCreateInfo.layout());
            VkDeviceQueueCreateInfo.sType(queueInfoSeg, STYPE_DEVICE_QUEUE_CREATE_INFO);
            VkDeviceQueueCreateInfo.pNext(queueInfoSeg, MemorySegment.NULL);
            VkDeviceQueueCreateInfo.flags(queueInfoSeg, 0);
            VkDeviceQueueCreateInfo.queueFamilyIndex(queueInfoSeg, queueFamily);
            VkDeviceQueueCreateInfo.queueCount(queueInfoSeg, 1);
            VkDeviceQueueCreateInfo.pQueuePriorities(queueInfoSeg, prioritySeg);

            MemorySegment deviceInfoSeg = tmp.allocate(VkDeviceCreateInfo.layout());
            VkDeviceCreateInfo.sType(deviceInfoSeg, STYPE_DEVICE_CREATE_INFO);
            VkDeviceCreateInfo.pNext(deviceInfoSeg, MemorySegment.NULL);
            VkDeviceCreateInfo.flags(deviceInfoSeg, 0);
            VkDeviceCreateInfo.queueCreateInfoCount(deviceInfoSeg, 1);
            VkDeviceCreateInfo.pQueueCreateInfos(deviceInfoSeg, queueInfoSeg);
            VkDeviceCreateInfo.enabledLayerCount(deviceInfoSeg, 0);
            VkDeviceCreateInfo.ppEnabledLayerNames(deviceInfoSeg, MemorySegment.NULL);
            VkDeviceCreateInfo.enabledExtensionCount(deviceInfoSeg, 0);
            VkDeviceCreateInfo.ppEnabledExtensionNames(deviceInfoSeg, MemorySegment.NULL);
            VkDeviceCreateInfo.pEnabledFeatures(deviceInfoSeg, MemorySegment.NULL);

            MemorySegment pDeviceSlot = PERSISTENT_ARENA.allocate(vulkan_h.C_POINTER);
            checkVulkan(
                    vulkan_h.vkCreateDevice(physDev, deviceInfoSeg, MemorySegment.NULL, pDeviceSlot),
                    "vkCreateDevice");
            return pDeviceSlot.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
        }
    }

    // ─── Phase 4 — Queue retrieval ─────────────────────────────────────────────

    private static MemorySegment retrieveQueue(MemorySegment device, int queueFamily) {
        MemorySegment pQueueSlot = PERSISTENT_ARENA.allocate(vulkan_h.C_POINTER);
        vulkan_h.vkGetDeviceQueue(device, queueFamily, 0, pQueueSlot);
        return pQueueSlot.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
    }

    // ─── Error checking ────────────────────────────────────────────────────────

    private static void checkVulkan(int result, String context) {
        if (result != vulkan_h.VK_SUCCESS()) {
            throw new RuntimeException(context + " returned VkResult=" + result);
        }
    }
}