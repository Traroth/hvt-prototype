/*
 * KernelCompiler.java
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

import uk.ac.manchester.beehivespirvtoolkit.lib.InvalidSPIRVModuleException;
import uk.ac.manchester.beehivespirvtoolkit.lib.SPIRVHeader;
import uk.ac.manchester.beehivespirvtoolkit.lib.SPIRVInstScope;
import uk.ac.manchester.beehivespirvtoolkit.lib.SPIRVModule;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpAccessChain;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpBitcast;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpBitwiseAnd;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpBitwiseOr;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpBranch;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpBranchConditional;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpCapability;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpCompositeExtract;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpConstant;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpConvertFToS;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpConvertFToU;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpConvertSToF;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpConvertUToF;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpDecorate;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpEntryPoint;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpExecutionMode;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpExtInst;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpExtInstImport;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpFAdd;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpFDiv;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpFMul;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpFSub;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpFunction;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpFunctionEnd;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpIAdd;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpIMul;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpISub;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpLabel;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpLoad;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpLogicalAnd;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpMemoryModel;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpMemberDecorate;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpReturn;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpSelectionMerge;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpShiftLeftLogical;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpShiftRightLogical;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpStore;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpTypeBool;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpTypeFloat;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpTypeFunction;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpTypeInt;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpTypePointer;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpTypeRuntimeArray;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpTypeStruct;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpTypeVector;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpTypeVoid;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpULessThan;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.SPIRVOpVariable;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVAddressingModel;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVBuiltIn;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVCapability;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVContextDependentFloat;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVContextDependentInt;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVDecoration;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVExecutionMode;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVExecutionModel;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVFunctionControl;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVId;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVLiteralExtInstInteger;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVLiteralInteger;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVLiteralString;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVMemoryModel;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVMultipleOperands;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVOptionalOperand;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVSelectionControl;
import uk.ac.manchester.beehivespirvtoolkit.lib.instructions.operands.SPIRVStorageClass;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Compiles the {@code bilinearZoom} kernel to a Vulkan Compute SPIR-V binary
 * using the Beehive SPIR-V Toolkit.
 *
 * <p>The generated binary:
 * <ul>
 *   <li>Uses the {@code Shader} capability and {@code GLSL.std.450} extension.</li>
 *   <li>Accepts three storage buffers (set=0): binding=0 input pixels
 *       (packed 32-bit ARGB), binding=1 output pixels (packed 32-bit ARGB),
 *       binding=2 error code (single 32-bit int, written by kernels that
 *       signal errors via {@code OpAtomicStore}).</li>
 *   <li>Accepts four push constants: {@code srcWidth}, {@code srcHeight},
 *       {@code dstWidth}, {@code dstHeight} (32-bit signed integers).</li>
 *   <li>Dispatches with a 2D local work group of 16 × 16 threads;
 *       each thread processes one output pixel.</li>
 * </ul>
 */
public final class KernelCompiler {

    // ─── GLSL.std.450 instruction opcodes ─────────────────────────────────────

    private static final int GLSL_ROUND  = 1;
    private static final int GLSL_FLOOR  = 8;
    private static final int GLSL_UMIN   = 38;
    private static final int GLSL_FCLAMP = 43;

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates the SPIR-V binary for the {@code bilinearZoom} kernel.
     *
     * <p>The kernel performs bilinear interpolation per ARGB channel. Each GPU
     * thread maps to one output pixel identified by {@code GlobalInvocationId.xy}.
     * Threads outside the destination bounds return immediately.
     *
     * @return the raw SPIR-V binary as a {@code byte[]}
     * @throws IllegalStateException if Beehive's module validation fails
     *                               (indicates a bug in this class)
     */
    public static byte[] compileBilinearZoom() {
        // SPIR-V 1.3: StorageBuffer storage class is core (promoted from SPV_KHR_storage_buffer_storage_class).
        // Vulkan 1.1+ requires at least SPIR-V 1.3; our target is Vulkan 1.3.
        SPIRVModule m = new SPIRVModule(new SPIRVHeader(1, 3, 0, 0, 0));

        // ─── Pre-allocate IDs ─────────────────────────────────────────────────
        // The SPIR-V spec requires a fixed layout order (spec §2.4):
        // preamble → OpEntryPoint → OpExecutionMode → annotations → types/constants/globals → functions.
        // All IDs are pre-allocated here so forward-reference instructions (OpEntryPoint,
        // OpDecorate) can be emitted before the definitions they reference.
        SPIRVId glsl450     = id(m);
        SPIRVId voidTy      = id(m);
        SPIRVId floatTy     = id(m);
        SPIRVId intTy       = id(m);
        SPIRVId uintTy      = id(m);
        SPIRVId boolTy      = id(m);
        SPIRVId v3uintTy    = id(m);
        SPIRVId arrIntTy    = id(m);
        SPIRVId inStructTy  = id(m);
        SPIRVId outStructTy = id(m);
        SPIRVId paramsTy    = id(m);
        SPIRVId ptrSBIn     = id(m);
        SPIRVId ptrSBOut    = id(m);
        SPIRVId ptrSBInt    = id(m);
        SPIRVId ptrPCPar    = id(m);
        SPIRVId ptrPCInt    = id(m);
        SPIRVId ptrInV3u    = id(m);
        SPIRVId voidFnTy    = id(m);
        SPIRVId u0          = id(m);
        SPIRVId u1          = id(m);
        SPIRVId u2          = id(m);
        SPIRVId u3          = id(m);
        SPIRVId i0          = id(m);
        SPIRVId i8          = id(m);
        SPIRVId i16         = id(m);
        SPIRVId i24         = id(m);
        SPIRVId i255        = id(m);
        SPIRVId f0          = id(m);
        SPIRVId fHalf       = id(m);
        SPIRVId f1          = id(m);
        SPIRVId inputVar    = id(m);
        SPIRVId outputVar   = id(m);
        SPIRVId errorVar    = id(m);
        SPIRVId paramsVar   = id(m);
        SPIRVId gidVar      = id(m);
        SPIRVId mainId      = id(m);

        // ─── Phase 1 — Preamble ───────────────────────────────────────────────
        // Beehive requires explicit declaration of transitive capability dependencies.
        // The SPIR-V spec defines Shader as depending on Matrix, so Matrix must be declared first.
        m.add(new SPIRVOpCapability(SPIRVCapability.Matrix()));
        m.add(new SPIRVOpCapability(SPIRVCapability.Shader()));
        m.add(new SPIRVOpExtInstImport(glsl450, new SPIRVLiteralString("GLSL.std.450")));
        m.add(new SPIRVOpMemoryModel(SPIRVAddressingModel.Logical(), SPIRVMemoryModel.GLSL450()));

        // ─── Phase 2 — Entry point and execution mode ─────────────────────────
        // SPIR-V ≤ 1.3: only Input/Output variables may appear in the interface list.
        // StorageBuffer variables (inputVar, outputVar) must be omitted here.
        m.add(new SPIRVOpEntryPoint(
                SPIRVExecutionModel.GLCompute(), mainId, new SPIRVLiteralString("main"),
                new SPIRVMultipleOperands<>(gidVar)));
        m.add(new SPIRVOpExecutionMode(mainId,
                SPIRVExecutionMode.LocalSize(lit(16), lit(16), lit(1))));

        // ─── Phase 3 — Decorations ────────────────────────────────────────────
        // Runtime array element stride
        m.add(new SPIRVOpDecorate(arrIntTy, SPIRVDecoration.ArrayStride(lit(4))));

        // Input buffer struct: Block + member 0 at offset 0
        m.add(new SPIRVOpMemberDecorate(inStructTy, lit(0), SPIRVDecoration.Offset(lit(0))));
        m.add(new SPIRVOpDecorate(inStructTy, SPIRVDecoration.Block()));

        // Output buffer struct: Block + member 0 at offset 0
        m.add(new SPIRVOpMemberDecorate(outStructTy, lit(0), SPIRVDecoration.Offset(lit(0))));
        m.add(new SPIRVOpDecorate(outStructTy, SPIRVDecoration.Block()));

        // Push constant struct: Block + members at offsets 0, 4, 8, 12
        m.add(new SPIRVOpMemberDecorate(paramsTy, lit(0), SPIRVDecoration.Offset(lit(0))));
        m.add(new SPIRVOpMemberDecorate(paramsTy, lit(1), SPIRVDecoration.Offset(lit(4))));
        m.add(new SPIRVOpMemberDecorate(paramsTy, lit(2), SPIRVDecoration.Offset(lit(8))));
        m.add(new SPIRVOpMemberDecorate(paramsTy, lit(3), SPIRVDecoration.Offset(lit(12))));
        m.add(new SPIRVOpDecorate(paramsTy, SPIRVDecoration.Block()));

        // Descriptor bindings and built-in decoration
        m.add(new SPIRVOpDecorate(inputVar,  SPIRVDecoration.DescriptorSet(lit(0))));
        m.add(new SPIRVOpDecorate(inputVar,  SPIRVDecoration.Binding(lit(0))));
        m.add(new SPIRVOpDecorate(outputVar, SPIRVDecoration.DescriptorSet(lit(0))));
        m.add(new SPIRVOpDecorate(outputVar, SPIRVDecoration.Binding(lit(1))));
        m.add(new SPIRVOpDecorate(errorVar,  SPIRVDecoration.DescriptorSet(lit(0))));
        m.add(new SPIRVOpDecorate(errorVar,  SPIRVDecoration.Binding(lit(2))));
        m.add(new SPIRVOpDecorate(gidVar,    SPIRVDecoration.BuiltIn(SPIRVBuiltIn.GlobalInvocationId())));

        // ─── Phase 4 — Type declarations ──────────────────────────────────────
        m.add(new SPIRVOpTypeVoid(voidTy));
        m.add(new SPIRVOpTypeFloat(floatTy, lit(32)));
        m.add(new SPIRVOpTypeInt(intTy,  lit(32), lit(1)));
        m.add(new SPIRVOpTypeInt(uintTy, lit(32), lit(0)));
        m.add(new SPIRVOpTypeBool(boolTy));
        m.add(new SPIRVOpTypeVector(v3uintTy, uintTy, lit(3)));

        // Storage buffer types: struct { int[] }
        m.add(new SPIRVOpTypeRuntimeArray(arrIntTy, intTy));
        m.add(new SPIRVOpTypeStruct(inStructTy,  new SPIRVMultipleOperands<>(arrIntTy)));
        m.add(new SPIRVOpTypeStruct(outStructTy, new SPIRVMultipleOperands<>(arrIntTy)));

        // Push constant type: struct { int srcWidth, srcHeight, dstWidth, dstHeight }
        m.add(new SPIRVOpTypeStruct(paramsTy, new SPIRVMultipleOperands<>(intTy, intTy, intTy, intTy)));

        // Pointer types
        m.add(new SPIRVOpTypePointer(ptrSBIn,  SPIRVStorageClass.StorageBuffer(), inStructTy));
        m.add(new SPIRVOpTypePointer(ptrSBOut, SPIRVStorageClass.StorageBuffer(), outStructTy));
        m.add(new SPIRVOpTypePointer(ptrSBInt, SPIRVStorageClass.StorageBuffer(), intTy));
        m.add(new SPIRVOpTypePointer(ptrPCPar, SPIRVStorageClass.PushConstant(), paramsTy));
        m.add(new SPIRVOpTypePointer(ptrPCInt, SPIRVStorageClass.PushConstant(), intTy));
        m.add(new SPIRVOpTypePointer(ptrInV3u, SPIRVStorageClass.Input(), v3uintTy));
        m.add(new SPIRVOpTypeFunction(voidFnTy, voidTy, new SPIRVMultipleOperands<>()));

        // ─── Phase 5 — Constants ──────────────────────────────────────────────
        m.add(new SPIRVOpConstant(uintTy,  u0,    ic(0)));
        m.add(new SPIRVOpConstant(uintTy,  u1,    ic(1)));
        m.add(new SPIRVOpConstant(uintTy,  u2,    ic(2)));
        m.add(new SPIRVOpConstant(uintTy,  u3,    ic(3)));
        m.add(new SPIRVOpConstant(intTy,   i0,    ic(0)));
        m.add(new SPIRVOpConstant(intTy,   i8,    ic(8)));
        m.add(new SPIRVOpConstant(intTy,   i16,   ic(16)));
        m.add(new SPIRVOpConstant(intTy,   i24,   ic(24)));
        m.add(new SPIRVOpConstant(intTy,   i255,  ic(255)));
        m.add(new SPIRVOpConstant(floatTy, f0,    fc(0.0f)));
        m.add(new SPIRVOpConstant(floatTy, fHalf, fc(0.5f)));
        m.add(new SPIRVOpConstant(floatTy, f1,    fc(1.0f)));

        // ─── Phase 6 — Global variables ───────────────────────────────────────
        m.add(new SPIRVOpVariable(ptrSBIn,  inputVar,  SPIRVStorageClass.StorageBuffer(), new SPIRVOptionalOperand<>()));
        m.add(new SPIRVOpVariable(ptrSBOut, outputVar, SPIRVStorageClass.StorageBuffer(), new SPIRVOptionalOperand<>()));
        m.add(new SPIRVOpVariable(ptrSBOut, errorVar,  SPIRVStorageClass.StorageBuffer(), new SPIRVOptionalOperand<>()));
        m.add(new SPIRVOpVariable(ptrPCPar, paramsVar, SPIRVStorageClass.PushConstant(),  new SPIRVOptionalOperand<>()));
        m.add(new SPIRVOpVariable(ptrInV3u, gidVar,    SPIRVStorageClass.Input(),         new SPIRVOptionalOperand<>()));

        // ─── Phase 7 — Kernel function body ───────────────────────────────────
        SPIRVInstScope fn = m.add(new SPIRVOpFunction(voidTy, mainId, SPIRVFunctionControl.None(), voidFnTy));

        SPIRVId lblEntry   = id(m);
        SPIRVId lblCompute = id(m);
        SPIRVId lblMerge   = id(m);

        // Entry block: load GID, push constants, bounds check
        SPIRVInstScope entry = fn.add(new SPIRVOpLabel(lblEntry));

        SPIRVId gidVec = id(m); entry.add(new SPIRVOpLoad(v3uintTy, gidVec, gidVar, new SPIRVOptionalOperand<>()));
        SPIRVId gx = id(m); entry.add(new SPIRVOpCompositeExtract(uintTy, gx, gidVec, new SPIRVMultipleOperands<>(lit(0))));
        SPIRVId gy = id(m); entry.add(new SPIRVOpCompositeExtract(uintTy, gy, gidVec, new SPIRVMultipleOperands<>(lit(1))));

        SPIRVId srcWidth  = loadPC(entry, m, ptrPCInt, intTy, paramsVar, u0);
        SPIRVId srcHeight = loadPC(entry, m, ptrPCInt, intTy, paramsVar, u1);
        SPIRVId dstWidth  = loadPC(entry, m, ptrPCInt, intTy, paramsVar, u2);
        SPIRVId dstHeight = loadPC(entry, m, ptrPCInt, intTy, paramsVar, u3);

        SPIRVId dstWidthU  = id(m); entry.add(new SPIRVOpBitcast(uintTy, dstWidthU,  dstWidth));
        SPIRVId dstHeightU = id(m); entry.add(new SPIRVOpBitcast(uintTy, dstHeightU, dstHeight));

        SPIRVId gxOk = id(m); entry.add(new SPIRVOpULessThan(boolTy, gxOk, gx, dstWidthU));
        SPIRVId gyOk = id(m); entry.add(new SPIRVOpULessThan(boolTy, gyOk, gy, dstHeightU));
        SPIRVId ok   = id(m); entry.add(new SPIRVOpLogicalAnd(boolTy, ok, gxOk, gyOk));
        entry.add(new SPIRVOpSelectionMerge(lblMerge, SPIRVSelectionControl.None()));
        entry.add(new SPIRVOpBranchConditional(ok, lblCompute, lblMerge, new SPIRVMultipleOperands<>()));

        // Compute block: bilinear sampling and write
        SPIRVInstScope compute = fn.add(new SPIRVOpLabel(lblCompute));

        // Float conversions of dimensions
        SPIRVId srcWidthF  = id(m); compute.add(new SPIRVOpConvertSToF(floatTy, srcWidthF,  srcWidth));
        SPIRVId srcHeightF = id(m); compute.add(new SPIRVOpConvertSToF(floatTy, srcHeightF, srcHeight));
        SPIRVId dstWidthF  = id(m); compute.add(new SPIRVOpConvertSToF(floatTy, dstWidthF,  dstWidth));
        SPIRVId dstHeightF = id(m); compute.add(new SPIRVOpConvertSToF(floatTy, dstHeightF, dstHeight));
        SPIRVId gxF = id(m); compute.add(new SPIRVOpConvertUToF(floatTy, gxF, gx));
        SPIRVId gyF = id(m); compute.add(new SPIRVOpConvertUToF(floatTy, gyF, gy));

        // Source coordinates — pixel-center convention: srcX = (gx+0.5)*(srcW/dstW) - 0.5
        SPIRVId xRatio = id(m); compute.add(new SPIRVOpFDiv(floatTy, xRatio, srcWidthF,  dstWidthF));
        SPIRVId yRatio = id(m); compute.add(new SPIRVOpFDiv(floatTy, yRatio, srcHeightF, dstHeightF));
        SPIRVId gxC    = id(m); compute.add(new SPIRVOpFAdd(floatTy, gxC,   gxF, fHalf));
        SPIRVId gyC    = id(m); compute.add(new SPIRVOpFAdd(floatTy, gyC,   gyF, fHalf));
        SPIRVId srcXR  = id(m); compute.add(new SPIRVOpFMul(floatTy, srcXR, gxC, xRatio));
        SPIRVId srcYR  = id(m); compute.add(new SPIRVOpFMul(floatTy, srcYR, gyC, yRatio));
        SPIRVId srcXF  = id(m); compute.add(new SPIRVOpFSub(floatTy, srcXF, srcXR, fHalf));
        SPIRVId srcYF  = id(m); compute.add(new SPIRVOpFSub(floatTy, srcYF, srcYR, fHalf));

        // Clamp source coords to [0, srcDim - 1]
        SPIRVId srcWM1F = id(m); compute.add(new SPIRVOpFSub(floatTy, srcWM1F, srcWidthF,  f1));
        SPIRVId srcHM1F = id(m); compute.add(new SPIRVOpFSub(floatTy, srcHM1F, srcHeightF, f1));
        SPIRVId srcXCl  = extFloat(compute, m, glsl450, floatTy, GLSL_FCLAMP, "FClamp", srcXF, f0, srcWM1F);
        SPIRVId srcYCl  = extFloat(compute, m, glsl450, floatTy, GLSL_FCLAMP, "FClamp", srcYF, f0, srcHM1F);

        // Floor → integer neighbours
        SPIRVId x0F = extFloat(compute, m, glsl450, floatTy, GLSL_FLOOR, "Floor", srcXCl);
        SPIRVId y0F = extFloat(compute, m, glsl450, floatTy, GLSL_FLOOR, "Floor", srcYCl);

        // Fractional parts (bilinear weights)
        SPIRVId tx = id(m); compute.add(new SPIRVOpFSub(floatTy, tx, srcXCl, x0F));
        SPIRVId ty = id(m); compute.add(new SPIRVOpFSub(floatTy, ty, srcYCl, y0F));

        // Convert to uint (safe: clamped to >= 0 above)
        SPIRVId x0 = id(m); compute.add(new SPIRVOpConvertFToU(uintTy, x0, x0F));
        SPIRVId y0 = id(m); compute.add(new SPIRVOpConvertFToU(uintTy, y0, y0F));

        // x1 = min(x0+1, srcWidth-1), y1 = min(y0+1, srcHeight-1)
        SPIRVId srcWidthU  = id(m); compute.add(new SPIRVOpBitcast(uintTy, srcWidthU,  srcWidth));
        SPIRVId srcHeightU = id(m); compute.add(new SPIRVOpBitcast(uintTy, srcHeightU, srcHeight));
        SPIRVId srcWM1 = id(m); compute.add(new SPIRVOpISub(uintTy, srcWM1, srcWidthU,  u1));
        SPIRVId srcHM1 = id(m); compute.add(new SPIRVOpISub(uintTy, srcHM1, srcHeightU, u1));
        SPIRVId x0p1  = id(m); compute.add(new SPIRVOpIAdd(uintTy, x0p1,   x0, u1));
        SPIRVId y0p1  = id(m); compute.add(new SPIRVOpIAdd(uintTy, y0p1,   y0, u1));
        SPIRVId x1 = extUint(compute, m, glsl450, uintTy, GLSL_UMIN, "UMin", x0p1, srcWM1);
        SPIRVId y1 = extUint(compute, m, glsl450, uintTy, GLSL_UMIN, "UMin", y0p1, srcHM1);

        // Pixel indices in the source buffer
        SPIRVId idx00 = pixelIdx(compute, m, uintTy, y0, srcWidthU, x0);
        SPIRVId idx01 = pixelIdx(compute, m, uintTy, y0, srcWidthU, x1);
        SPIRVId idx10 = pixelIdx(compute, m, uintTy, y1, srcWidthU, x0);
        SPIRVId idx11 = pixelIdx(compute, m, uintTy, y1, srcWidthU, x1);

        // Load 4 neighbouring pixels (packed ARGB ints)
        SPIRVId p00 = loadPx(compute, m, ptrSBInt, intTy, inputVar, u0, idx00);
        SPIRVId p01 = loadPx(compute, m, ptrSBInt, intTy, inputVar, u0, idx01);
        SPIRVId p10 = loadPx(compute, m, ptrSBInt, intTy, inputVar, u0, idx10);
        SPIRVId p11 = loadPx(compute, m, ptrSBInt, intTy, inputVar, u0, idx11);

        // Per-channel bilinear interpolation (A=shift 24, R=16, G=8, B=0)
        SPIRVId aOut = interpChannel(compute, m, glsl450, intTy, floatTy, p00, p01, p10, p11, tx, ty, i24, i255);
        SPIRVId rOut = interpChannel(compute, m, glsl450, intTy, floatTy, p00, p01, p10, p11, tx, ty, i16, i255);
        SPIRVId gOut = interpChannel(compute, m, glsl450, intTy, floatTy, p00, p01, p10, p11, tx, ty, i8,  i255);
        SPIRVId bOut = interpChannel(compute, m, glsl450, intTy, floatTy, p00, p01, p10, p11, tx, ty, i0,  i255);

        // Repack into packed ARGB int
        SPIRVId aS   = id(m); compute.add(new SPIRVOpShiftLeftLogical(intTy, aS, aOut, i24));
        SPIRVId rS   = id(m); compute.add(new SPIRVOpShiftLeftLogical(intTy, rS, rOut, i16));
        SPIRVId gS   = id(m); compute.add(new SPIRVOpShiftLeftLogical(intTy, gS, gOut, i8));
        SPIRVId ar   = id(m); compute.add(new SPIRVOpBitwiseOr(intTy, ar,   aS, rS));
        SPIRVId arg  = id(m); compute.add(new SPIRVOpBitwiseOr(intTy, arg,  ar, gS));
        SPIRVId argb = id(m); compute.add(new SPIRVOpBitwiseOr(intTy, argb, arg, bOut));

        // Write result to output buffer; dstWidthU dominates this block so it is reused directly
        SPIRVId dstIdx = pixelIdx(compute, m, uintTy, gy, dstWidthU, gx);
        SPIRVId dstPtr = id(m); compute.add(new SPIRVOpAccessChain(ptrSBInt, dstPtr, outputVar, new SPIRVMultipleOperands<>(u0, dstIdx)));
        compute.add(new SPIRVOpStore(dstPtr, argb, new SPIRVOptionalOperand<>()));
        compute.add(new SPIRVOpBranch(lblMerge));

        // Merge block
        SPIRVInstScope merge = fn.add(new SPIRVOpLabel(lblMerge));
        merge.add(new SPIRVOpReturn());
        fn.add(new SPIRVOpFunctionEnd());

        // ─── Phase 8 — Serialisation ──────────────────────────────────────────
        return serialize(m);
    }

    // ─── Private helpers — SPIR-V instruction construction ────────────────────

    /** Allocates the next SPIR-V ID from the module. */
    private static SPIRVId id(SPIRVModule m) {
        return m.getNextId();
    }

    /** Creates a {@link SPIRVLiteralInteger} with the given value. */
    private static SPIRVLiteralInteger lit(int v) {
        return new SPIRVLiteralInteger(v);
    }

    /** Creates a {@link SPIRVContextDependentInt} constant value. */
    private static SPIRVContextDependentInt ic(int v) {
        return new SPIRVContextDependentInt(BigInteger.valueOf(v));
    }

    /** Creates a {@link SPIRVContextDependentFloat} constant value. */
    private static SPIRVContextDependentFloat fc(float v) {
        return new SPIRVContextDependentFloat(v);
    }

    /** Loads a push-constant int member at {@code index} from {@code paramsVar}. */
    private static SPIRVId loadPC(SPIRVInstScope b, SPIRVModule m,
                                   SPIRVId ptrPCInt, SPIRVId intTy,
                                   SPIRVId paramsVar, SPIRVId index) {
        SPIRVId ptr = id(m); b.add(new SPIRVOpAccessChain(ptrPCInt, ptr, paramsVar, new SPIRVMultipleOperands<>(index)));
        SPIRVId val = id(m); b.add(new SPIRVOpLoad(intTy, val, ptr, new SPIRVOptionalOperand<>()));
        return val;
    }

    /** Calls a one-operand GLSL.std.450 float instruction. */
    private static SPIRVId extFloat(SPIRVInstScope b, SPIRVModule m,
                                     SPIRVId glsl, SPIRVId floatTy,
                                     int opcode, String name, SPIRVId val) {
        SPIRVId r = id(m);
        b.add(new SPIRVOpExtInst(floatTy, r, glsl,
                new SPIRVLiteralExtInstInteger(opcode, name),
                new SPIRVMultipleOperands<>(val)));
        return r;
    }

    /** Calls a three-operand GLSL.std.450 float instruction (e.g. FClamp). */
    private static SPIRVId extFloat(SPIRVInstScope b, SPIRVModule m,
                                     SPIRVId glsl, SPIRVId floatTy,
                                     int opcode, String name,
                                     SPIRVId a, SPIRVId b2, SPIRVId c) {
        SPIRVId r = id(m);
        b.add(new SPIRVOpExtInst(floatTy, r, glsl,
                new SPIRVLiteralExtInstInteger(opcode, name),
                new SPIRVMultipleOperands<>(a, b2, c)));
        return r;
    }

    /** Calls a two-operand GLSL.std.450 uint instruction (e.g. UMin). */
    private static SPIRVId extUint(SPIRVInstScope b, SPIRVModule m,
                                    SPIRVId glsl, SPIRVId uintTy,
                                    int opcode, String name, SPIRVId a, SPIRVId b2) {
        SPIRVId r = id(m);
        b.add(new SPIRVOpExtInst(uintTy, r, glsl,
                new SPIRVLiteralExtInstInteger(opcode, name),
                new SPIRVMultipleOperands<>(a, b2)));
        return r;
    }

    /** Computes {@code row * width + col} in uint. */
    private static SPIRVId pixelIdx(SPIRVInstScope b, SPIRVModule m,
                                     SPIRVId uintTy, SPIRVId row, SPIRVId width, SPIRVId col) {
        SPIRVId off = id(m); b.add(new SPIRVOpIMul(uintTy, off, row, width));
        SPIRVId idx = id(m); b.add(new SPIRVOpIAdd(uintTy, idx, off, col));
        return idx;
    }

    /** Loads one ARGB int from {@code buf} at element {@code idx}. */
    private static SPIRVId loadPx(SPIRVInstScope b, SPIRVModule m,
                                   SPIRVId ptrSBInt, SPIRVId intTy,
                                   SPIRVId buf, SPIRVId member0, SPIRVId idx) {
        SPIRVId ptr = id(m); b.add(new SPIRVOpAccessChain(ptrSBInt, ptr, buf, new SPIRVMultipleOperands<>(member0, idx)));
        SPIRVId val = id(m); b.add(new SPIRVOpLoad(intTy, val, ptr, new SPIRVOptionalOperand<>()));
        return val;
    }

    /**
     * Extracts one 8-bit ARGB channel from a pixel as float:
     * {@code (pixel >> shift) & 0xFF} converted to {@code float}.
     */
    private static SPIRVId extractChannel(SPIRVInstScope b, SPIRVModule m,
                                           SPIRVId intTy, SPIRVId floatTy,
                                           SPIRVId pixel, SPIRVId shift, SPIRVId mask) {
        SPIRVId shifted = id(m); b.add(new SPIRVOpShiftRightLogical(intTy, shifted, pixel, shift));
        SPIRVId masked  = id(m); b.add(new SPIRVOpBitwiseAnd(intTy, masked, shifted, mask));
        SPIRVId asFloat = id(m); b.add(new SPIRVOpConvertSToF(floatTy, asFloat, masked));
        return asFloat;
    }

    /**
     * Computes {@code a + (b - a) * t} in float.
     */
    private static SPIRVId lerp(SPIRVInstScope b, SPIRVModule m, SPIRVId floatTy,
                                 SPIRVId a, SPIRVId bVal, SPIRVId t) {
        SPIRVId diff   = id(m); b.add(new SPIRVOpFSub(floatTy, diff,   bVal, a));
        SPIRVId tdiff  = id(m); b.add(new SPIRVOpFMul(floatTy, tdiff,  diff, t));
        SPIRVId result = id(m); b.add(new SPIRVOpFAdd(floatTy, result, a, tdiff));
        return result;
    }

    /**
     * Full bilinear interpolation for one channel across 4 pixels, returning
     * a rounded signed int in [0, 255].
     */
    private static SPIRVId interpChannel(SPIRVInstScope b, SPIRVModule m,
                                          SPIRVId glsl, SPIRVId intTy, SPIRVId floatTy,
                                          SPIRVId p00, SPIRVId p01, SPIRVId p10, SPIRVId p11,
                                          SPIRVId tx, SPIRVId ty, SPIRVId shift, SPIRVId mask) {
        SPIRVId c00 = extractChannel(b, m, intTy, floatTy, p00, shift, mask);
        SPIRVId c01 = extractChannel(b, m, intTy, floatTy, p01, shift, mask);
        SPIRVId c10 = extractChannel(b, m, intTy, floatTy, p10, shift, mask);
        SPIRVId c11 = extractChannel(b, m, intTy, floatTy, p11, shift, mask);
        SPIRVId row0    = lerp(b, m, floatTy, c00, c01, tx);
        SPIRVId row1    = lerp(b, m, floatTy, c10, c11, tx);
        SPIRVId blended = lerp(b, m, floatTy, row0, row1, ty);
        SPIRVId rounded = id(m);
        b.add(new SPIRVOpExtInst(floatTy, rounded, glsl,
                new SPIRVLiteralExtInstInteger(GLSL_ROUND, "Round"),
                new SPIRVMultipleOperands<>(blended)));
        SPIRVId asInt = id(m); b.add(new SPIRVOpConvertFToS(intTy, asInt, rounded));
        return asInt;
    }

    // ─── Serialisation ─────────────────────────────────────────────────────────

    private static byte[] serialize(SPIRVModule m) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(m.getByteCount());
            buf.order(ByteOrder.LITTLE_ENDIAN);
            m.validate().write(buf);
            return buf.array();
        } catch (InvalidSPIRVModuleException isme) {
            throw new IllegalStateException("Generated SPIR-V module failed validation", isme);
        }
    }
}