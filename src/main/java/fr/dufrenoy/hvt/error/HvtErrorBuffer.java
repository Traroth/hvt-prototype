/*
 * HvtErrorBuffer.java
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
 * Carries the device-resident error code written by a kernel into the error SSBO
 * (set=0, binding=2).
 *
 * <p>The runtime reads this 1-int buffer after {@code vkQueueWaitIdle} and wraps
 * the value in an {@code HvtErrorBuffer}. A non-zero code causes
 * {@link #checkAndThrow()} to throw {@link HvtKernelException}; the FROM_DEVICE
 * output download is then skipped.
 *
 * <p>Instances are immutable and carry no Panama/Vulkan dependencies.
 */
public final class HvtErrorBuffer {

    private final int code;

    private HvtErrorBuffer(int code) {
        this.code = code;
    }

    /**
     * Creates an {@code HvtErrorBuffer} wrapping the given device error code.
     *
     * @param code the integer read from the device error buffer (0 means no error)
     * @return a new {@code HvtErrorBuffer}
     */
    public static HvtErrorBuffer ofCode(int code) {
        return new HvtErrorBuffer(code);
    }

    /**
     * Returns {@code true} if the kernel signalled an error (code != 0).
     */
    public boolean isError() {
        return code != 0;
    }

    /**
     * Returns the raw device error code (0 means no error).
     */
    public int errorCode() {
        return code;
    }

    /**
     * Throws {@link HvtKernelException} if {@link #isError()} is {@code true}.
     *
     * @throws HvtKernelException if the kernel signalled a non-zero error code
     */
    public void checkAndThrow() {
        if (isError()) {
            throw new HvtKernelException("Kernel signalled error code " + code);
        }
    }
}