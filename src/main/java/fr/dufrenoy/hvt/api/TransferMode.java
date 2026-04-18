/*
 * TransferMode.java
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

/**
 * Describes the direction of data transfer between JVM heap and device memory
 * for an {@link HvtMemory} buffer.
 */
public enum TransferMode {

    /**
     * Buffer is copied from JVM heap to device memory before kernel execution.
     */
    TO_DEVICE,

    /**
     * Buffer is copied from device memory to JVM heap after kernel execution.
     */
    FROM_DEVICE,

    /**
     * Buffer is allocated on the device and never transferred to or from the JVM heap.
     * Used for intermediate buffers in kernel chains.
     */
    DEVICE_ONLY
}