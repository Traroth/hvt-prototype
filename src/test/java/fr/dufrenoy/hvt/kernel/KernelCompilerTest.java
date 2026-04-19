/*
 * KernelCompilerTest.java
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
package fr.dufrenoy.hvt.kernel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelCompilerTest {

    private static final int SPIRV_MAGIC = 0x07230203;

    @Test
    void compileBilinearZoom_returnsSpirvBinary() {
        byte[] spv = KernelCompiler.compileBilinearZoom();

        assertNotNull(spv);
        assertTrue(spv.length >= 20, "SPIR-V binary must be at least 20 bytes (header size)");

        int magic = ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        assertEquals(SPIRV_MAGIC, magic,
                String.format("Expected SPIR-V magic 0x%08X but got 0x%08X", SPIRV_MAGIC, magic));
    }

    @Test
    void compileBilinearZoom_passesSpirVVal() throws IOException, InterruptedException {
        byte[] spv = KernelCompiler.compileBilinearZoom();

        Path tmp = Files.createTempFile("bilinearZoom", ".spv");
        try {
            Files.write(tmp, spv);

            Process proc = new ProcessBuilder("spirv-val", "--target-env", "vulkan1.3", tmp.toString())
                    .redirectErrorStream(true)
                    .start();

            String output = new String(proc.getInputStream().readAllBytes());
            int exitCode  = proc.waitFor();

            assertEquals(0, exitCode,
                    "spirv-val reported errors:\n" + output);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}