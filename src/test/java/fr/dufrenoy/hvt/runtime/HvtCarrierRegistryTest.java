/*
 * HvtCarrierRegistryTest.java
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

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HvtCarrierRegistryTest {

    @Test
    void gpuAvailable_returnsTrueOnDevMachine() {
        assertTrue(HvtCarrierRegistry.gpuAvailable(),
                "Expected a compute-capable Vulkan device on the development machine");
    }

    @Test
    void device_returnsNonNullNonZeroHandle() {
        MemorySegment dev = HvtCarrierRegistry.device();
        assertNotNull(dev);
        assertNotEquals(0L, dev.address(),
                "VkDevice handle must not be zero");
    }

    @Test
    void queue_returnsNonNullNonZeroHandle() {
        MemorySegment q = HvtCarrierRegistry.queue();
        assertNotNull(q);
        assertNotEquals(0L, q.address(),
                "VkQueue handle must not be zero");
    }

    @Test
    void computeQueueFamily_returnsNonNegativeIndex() {
        assertTrue(HvtCarrierRegistry.computeQueueFamily() >= 0,
                "Compute queue family index must be non-negative");
    }
}