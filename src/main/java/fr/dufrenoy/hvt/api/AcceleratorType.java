/*
 * AcceleratorType.java
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
 * Identifies the type of compute accelerator on which a kernel may be dispatched.
 *
 * <p>In this prototype only {@link #GPU} has a real hardware dispatch path
 * (Vulkan Compute via Panama). {@link #CPU} is the fallback and runs the kernel
 * on a standard JVM thread.
 */
public enum AcceleratorType {

    /**
     * Graphics Processing Unit — dispatched via Vulkan Compute in this prototype.
     */
    GPU,

    /**
     * CPU fallback — kernel runs on a standard JVM thread.
     */
    CPU
}