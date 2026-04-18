/*
 * HvtKernelException.java
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
package fr.dufrenoy.hvt.error;

/**
 * Thrown when a heterogeneous virtual thread fails during kernel dispatch or execution.
 *
 * <p>This covers both non-zero error codes written by the kernel into {@code HvtErrorBuffer}
 * and hardware faults reported via Vulkan error codes (device OOM, driver timeout).
 */
public final class HvtKernelException extends RuntimeException {

    /**
     * Constructs an exception with the given detail message.
     *
     * @param message description of the failure
     */
    public HvtKernelException(String message) {
        super(message);
    }

    /**
     * Constructs an exception with the given detail message and cause.
     *
     * @param message description of the failure
     * @param cause   the underlying exception
     */
    public HvtKernelException(String message, Throwable cause) {
        super(message, cause);
    }
}