# Heterogeneous Virtual Threads: Extending Project Loom for First-Class Accelerator Support in the JVM

**Abstract**

Project Loom extends the Java concurrency model through Virtual Threads: lightweight, JVM-managed threads scheduled onto platform carrier threads, dramatically reducing the cost of concurrency in Java applications. We propose extending this model to heterogeneous hardware accelerators — GPUs, DSPs, FPGAs, and NPUs — through *Heterogeneous Virtual Threads* (HVTs): a new thread type whose carrier is not a CPU thread, but a hardware accelerator. Unlike TornadoVM, which operates as an external runtime layered above the JVM, HVTs would be first-class citizens of the JVM threading model, expressed through an API consistent with `Thread.ofVirtual()`. This proposal is conceptual in nature: no implementation exists. The API and architectural elements presented here are illustrative, intended to make the proposal concrete and discussable. A central constraint — shared with all existing Java GPU frameworks — is that HVT kernel methods are restricted to a constrained subset of Java, excluding dynamic allocation, recursion, and exception handling, a limitation inherent to the GPU execution model itself. We discuss the architectural implications for the JVM scheduler, the compiler toolchain required to compile a constrained Java subset to SPIR-V via the Beehive SPIR-V Toolkit, a novel atomic error signaling model for kernel faults, the memory model challenges arising from physically separate device memory, and the relevance of unified memory architectures to this proposal.

---

## 1. Introduction

The Java Virtual Machine has long pursued a "write once, run anywhere" philosophy across CPU architectures and operating systems. Project Loom [LOOM] extends this to the concurrency model itself: Virtual Threads abstract away OS threads, allowing the JVM scheduler to multiplex millions of lightweight threads onto a small pool of platform carrier threads, without the application developer being concerned with the underlying threading substrate.

Modern computing hardware has, however, become radically heterogeneous. GPUs, DSPs, FPGAs, and NPUs are now ubiquitous — present not only in datacenter hardware but in consumer devices, mobile SoCs, and embedded systems. OpenCL [OCL] and Vulkan Compute [VULKAN], through their shared intermediate representation SPIR-V [SPIRV], provide a vendor-neutral programming model that spans NVIDIA, AMD, Intel, ARM Mali, Qualcomm Adreno, Apple Silicon, and FPGA devices from Altera and Xilinx/AMD, among others. Yet Java has no native abstraction for these execution units.

Java already provides several parallel programming abstractions. Parallel Streams [JSR-335] and the Vector API [JEP-529] target CPU parallelism — the former at a high level via the Fork/Join framework, the latter at a low level via SIMD instructions. Virtual Threads [JEP-444] target I/O-bound concurrency on CPU. The following table positions these abstractions relative to the proposed HVT model:

| Abstraction | Level | Target | Primary use case |
|---|---|---|---|
| Parallel Streams | High | CPU multicore | Collection processing, data pipelines |
| Vector API | Low | CPU SIMD | Dense numerical computation |
| Virtual Threads | High | CPU multicore | I/O-bound concurrency, scalability |
| **HVT (this work)** | High | GPU/DSP/FPGA/NPU | Massively parallel, data-parallel workloads |

HVTs fill the missing quadrant: a high-level, threading-model-integrated abstraction targeting non-CPU accelerators. A central constraint of this proposal — shared with TornadoVM, Aparapi, and all prior Java GPU frameworks — is that HVT kernel methods are restricted to a constrained subset of Java. This limitation is not specific to our proposal: it is inherent to the GPU execution model, which precludes dynamic allocation, exception handling, and arbitrary control flow. We discuss this constraint in detail in Section 4.

TornadoVM [TORNADO] addresses GPU acceleration through an external runtime built on top of the JVM, using JVMCI [JEP-243] and the Graal compiler to JIT-compile annotated Java methods to OpenCL C, CUDA PTX, or SPIR-V. Project Babylon/HAT [BABYLON], an official OpenJDK project led by Paul Sandoz at Oracle, enhances code reflection to produce *code models* from Java methods and lambdas, which HAT (Heterogeneous Accelerator Toolkit) uses to generate GPU kernels. Both are complementary to our proposal.

We propose a complementary and architecturally distinct approach: *Heterogeneous Virtual Threads* (HVTs), a new category of Virtual Thread whose carrier is not a platform OS thread, but a hardware accelerator. An HVT would be created, submitted, chained, and joined through an API consistent with `Thread.ofVirtual()`, making accelerator-backed execution a natural extension of Java's existing concurrency model rather than a separate programming paradigm.

The remainder of this paper is organized as follows. Section 2 surveys related work. Section 3 presents the proposed HVT programming model and API. Section 4 discusses the compiler toolchain and error model. Section 5 addresses the memory model. Section 6 discusses the JVM scheduler extensions required. Section 7 identifies open problems and directions for future work.

---

## 2. Related Work

### 2.1 Project Loom and Virtual Threads

Project Loom [LOOM] introduced Virtual Threads in Java 21 as a production feature (JEP 444). A Virtual Thread is a `Thread` instance managed by the JVM rather than the OS. The JVM schedules Virtual Threads onto *carrier threads* — ordinary platform threads drawn from a ForkJoinPool — using a work-stealing scheduler. When a Virtual Thread blocks (e.g., on I/O), the carrier thread is released and can execute another Virtual Thread.

The HVT model extends this carrier abstraction. In Project Loom, all carriers are CPU platform threads — the pool is homogeneous. In the HVT model, carriers may be CPU threads, GPU command queues, DSP task queues, or FPGA execution contexts — the pool becomes heterogeneous. This parallel is structural, not merely analogical:

- Virtual Thread → **Heterogeneous Virtual Thread**
- Carrier thread (OS thread) → **Accelerator carrier** (OpenCL command queue / Vulkan compute queue)
- ForkJoinPool → **HvtCarrierRegistry**

### 2.2 TornadoVM

TornadoVM [TORNADO] is the most complete existing framework for GPU/FPGA acceleration from Java. It compiles annotated Java methods to OpenCL C, NVIDIA PTX, or SPIR-V using an extended Graal compiler backend via JVMCI [JEP-243], and provides a `TaskGraph` API for defining data flow between accelerated tasks. When dynamic reconfiguration is enabled, TornadoVM spawns a separate Java thread per available device, compiles and executes the kernel on each concurrently, and migrates execution to whichever device completes first. This benchmarking-based approach is empirical, not heuristic.

TornadoVM's model requires developers to think in terms of tasks and data graphs, not threads. Our proposal differs in that HVTs are `Thread` instances, integrated into the JVM's existing threading and synchronization primitives. This is not a replacement for TornadoVM but a different level of abstraction — one that may, in fact, use TornadoVM's compiler infrastructure internally.

### 2.3 Project Babylon / HAT

Project Babylon [BABYLON] is an OpenJDK project led by Paul Sandoz at Oracle. It enhances Java's code reflection model to expose *code models* — symbolic representations of Java methods and lambdas — that can be transformed, optimized, or compiled to foreign programming models at runtime, without third-party bytecode manipulation. HAT (Heterogeneous Accelerator Toolkit) is Babylon's GPU subproject, which uses code models to generate OpenCL C, CUDA PTX, and SPIR-V kernels. Unlike TornadoVM, HAT is planned as an external library built on standard OpenJDK infrastructure, not a JVM plugin. HVTs differ from Babylon/HAT in that they target the *threading model* rather than the *compilation model* — a layer above, which could in principle use Babylon as its compiler backend.

### 2.4 Prior Java GPU Frameworks

A succession of projects has attempted to bring GPU acceleration to Java. **Project Sumatra** [SUMATRA] (OpenJDK, 2012–2014), a collaboration between Oracle and AMD, explored extending the JVM JIT to generate HSAIL code for GPU/APU execution, targeting Java 8 parallel streams. It was discontinued with the decline of AMD's HSA initiative. **Marawacc** [MARAWACC], developed by Juan Fumero during his PhD at the University of Edinburgh, was a Graal-based JIT compiler that compiled Java bytecode to OpenCL, with a Java 8 Stream-based API (JPAI); it served as the direct precursor to TornadoVM. **Aparapi** [APARAPI] translated Java bytecode to OpenCL C at runtime; it is now inactive. **Rootbeer** [ROOTBEER] automated serialization of Java objects to GPU memory and generated CUDA kernels, supporting a broader Java subset than Aparapi, including methods and object fields; development ceased around 2016. **JaBEE** [JABEE], developed at NVIDIA, was a framework for compiling object-oriented Java bytecode to GPU. **Eclipse OpenJ9** [OPENJ9] provides two GPU APIs: CUDA4J (`com.ibm.cuda`) for explicit CUDA device control, and a GPU API (`com.ibm.gpu`) for accelerating specific Java operations such as array sorting; the JIT can also offload tasks via `-Xjit:enableGPU`. As noted in the HAT documentation, all these projects focused primarily on abstracting GPU programmability, often at the cost of access to advanced GPU optimizations [BABYLON-HAT].

### 2.5 Analogues in Other Languages

The problem of executing high-level language code on heterogeneous hardware is not specific to Java. Mojo [MOJO] introduces SIMD and GPU-aware types directly into a Python-compatible language. Julia [JULIA] supports GPU programming via packages such as CUDA.jl. Swift for TensorFlow [SFT] explored differentiable programming and GPU execution from Swift. C# LINQ inspired code-model approaches to query transformation. MLIR [MLIR] provides a multi-level intermediate representation designed for heterogeneous compilation. These efforts confirm that the challenge is universal and that no single approach has emerged as dominant.

### 2.6 Beehive SPIR-V Toolkit

The Beehive SPIR-V Toolkit [BEEHIVE], developed at the University of Manchester, is a Java library for programmatically generating SPIR-V binary modules, auto-generated from the official Khronos SPIR-V grammar JSON. It is analogous in spirit to the ASM bytecode manipulation library for JVM bytecode — a low-level, standard-compliant code generation backend — but targeting SPIR-V rather than JVM bytecode. It serves as the SPIR-V backend for TornadoVM and is the proposed backend for HVT kernel compilation.

### 2.7 OpenCL, Vulkan, and SPIR-V

OpenCL [OCL] defines a programming model for heterogeneous parallel computation across CPUs, GPUs, DSPs, and FPGAs. SPIR-V [SPIRV] is the standard portable intermediate representation for OpenCL 2.1+ and Vulkan compute shaders. SPIR-V portability is, however, not uniform across all accelerator types: for GPUs, coverage is complete and well-established; for FPGAs, support is available via vendor-specific extensions (e.g., `SPV_INTEL_fpga_dsp_control`); for NPUs, the Khronos Group is actively working on SPIR-V compute graph extensions but standardization is not yet complete; for DSPs, support is partial and driver-dependent. This non-uniformity has direct implications for the HVT compiler toolchain, discussed in Section 4.

---

## 3. The HVT Programming Model

### 3.1 Design Principles

The HVT API is designed around three principles:

1. **Consistency with Project Loom**: HVTs are created and managed through the same `Thread` API as Virtual Threads, using a builder pattern consistent with `Thread.ofVirtual()`.
2. **Explicit data transfer**: unlike CPU Virtual Threads which share the JVM heap, HVTs operate on explicitly scoped data segments that the runtime transfers to and from device memory. This is not hidden from the developer — it is a first-class part of the API.
3. **Composability**: HVTs can be chained, allowing the output of one kernel to flow directly into the input of another without a **costly** intermediate round-trip to the JVM heap.

This is a conceptual proposal. No implementation exists. The API presented below is illustrative, intended to make the proposal concrete and to invite discussion on the design choices involved.

### 3.2 Proposed API — GPU Example

```java
// A kernel method — constrained subset of Java (see Section 4)
@HvtKernel
public static void bilinearZoom(
        HvtIntArray src, int srcWidth, int srcHeight,
        HvtIntArray dst, int dstWidth, int dstHeight,
        HvtErrorBuffer error, float scaleX, float scaleY) {
    int x = HvtContext.globalId(0);
    int y = HvtContext.globalId(1);
    if (x >= dstWidth || y >= dstHeight) {
        error.set(HvtError.OUT_OF_BOUNDS); return;
    }
    // bilinear sampling logic using primitive arithmetic only
    dst.set(y * dstWidth + x, sample);
}

// Submission — CPU side
HvtMemory<int[]> srcMem = HvtMemory.of(srcPixels, TransferMode.TO_DEVICE);
HvtMemory<int[]> dstMem = HvtMemory.allocate(dstWidth * dstHeight, TransferMode.FROM_DEVICE);

Thread hvt = Thread.ofHeterogeneousVirtual()
    .preferring(AcceleratorType.GPU)
    .fallbackTo(AcceleratorType.FPGA)
    .kernel(MyKernels::bilinearZoom, srcMem, srcWidth, srcHeight,
                                     dstMem, dstWidth, dstHeight,
                                     scaleX, scaleY)
    .start();

hvt.join();
int[] result = dstMem.get(); // triggers transfer back to JVM heap
```

### 3.3 Proposed API — DSP Example

To illustrate the relevance of the AcceleratorType taxonomy beyond GPUs, consider a FIR (Finite Impulse Response) filter — a signal processing operation well-suited to a DSP: regular sequential memory access, repetitive MAC (multiply-accumulate) operations, and deterministic latency requirements. A DSP is preferable to a GPU for such a workload because it avoids the overhead of the SIMT execution model and provides lower, more predictable latency.

```java
@HvtKernel
public static void firFilter(
        HvtFloatArray signal, HvtFloatArray coefficients,
        HvtFloatArray output, HvtErrorBuffer error) {
    int i = HvtContext.globalId(0);
    float acc = 0.0f;
    for (int j = 0; j < coefficients.length(); j++) {
        acc += signal.get(i + j) * coefficients.get(j);
    }
    output.set(i, acc);
}

Thread t = Thread.ofHeterogeneousVirtual()
    .preferring(AcceleratorType.DSP)
    .fallbackTo(AcceleratorType.GPU)
    .kernel(Kernels::firFilter, signalMem, coeffMem, outputMem, ...)
    .start();
```

### 3.4 Kernel Chaining

HVTs can be chained such that intermediate buffers remain in device memory across kernel boundaries, avoiding PCIe transfers. The `DEVICE_ONLY` transfer mode signals that a buffer never needs to cross to the JVM heap. This guarantee is only valid when chained kernels execute on the same physical device. Chaining across different physical devices — even of the same `AcceleratorType` — reintroduces a host-mediated transfer, negating the benefit. The runtime must detect and warn on cross-device chains using `DEVICE_ONLY` buffers.

```java
HvtMemory<int[]> raw    = HvtMemory.of(inputPixels, TransferMode.TO_DEVICE);
HvtMemory<int[]> zoomed = HvtMemory.allocate(size, TransferMode.DEVICE_ONLY);
HvtMemory<int[]> output = HvtMemory.allocate(size, TransferMode.FROM_DEVICE);

Thread t1 = Thread.ofHeterogeneousVirtual()
    .preferring(AcceleratorType.GPU)
    .kernel(Kernels::bilinearZoom, raw, zoomed, ...)
    .start();

Thread t2 = Thread.ofHeterogeneousVirtual()
    .preferring(AcceleratorType.GPU)
    .kernel(Kernels::rotate, zoomed, output, angle)
    .after(t1)  // data dependency — no CPU round-trip if same device
    .start();

t2.join();
int[] result = output.get();
```

### 3.5 AcceleratorType Taxonomy

The `AcceleratorType` enum models the range of accelerators reachable via OpenCL/Vulkan. Each type has distinct performance characteristics that motivate the developer's explicit choice:

```java
public enum AcceleratorType {
    GPU,   // massively parallel SIMT — best for embarrassingly parallel, data-uniform workloads
    DSP,   // signal processing — best for streaming, regular memory access, MAC operations
    FPGA,  // reconfigurable logic — best for pipeline-structured, low-latency, deterministic workloads
    NPU    // neural processing — best for tensor operations, low-precision inference (INT8, FP8)
}
```

Note on SPIR-V portability: as discussed in Section 2.7, SPIR-V coverage varies by `AcceleratorType`. A complete HVT implementation may therefore require multiple compilation backends, one per target type.

---

## 4. Compiler Toolchain

### 4.1 The Java Subset Constraint

The fundamental challenge in compiling Java to GPU kernels is the incompatibility between the Java object model and the GPU execution model. This constraint is shared by TornadoVM, Aparapi, Rootbeer, and all prior Java GPU frameworks — it is inherent to the GPU execution model itself. The following Java features are excluded from the HVT kernel subset:

- Object allocation (`new`) — no heap allocation within kernels
- Recursion — GPU call stacks are severely limited or absent
- Exception handling (`try/catch/throw`) — see Section 4.3 for the proposed error model
- Reflection and dynamic dispatch
- I/O operations of any kind
- Non-primitive types other than `HvtArray` wrappers
- Synchronization primitives (`synchronized`, `volatile`) — replaced by explicit `HvtContext.barrier()`

What IS expressible within the HVT subset includes: primitive types (`int`, `float`, `double`, `long`), single-dimensional arrays of primitives, `HvtArray` wrappers, standard arithmetic and logical operations, `for` and `while` loops without cross-iteration data dependencies, conditional branches (with the caveat that branch divergence is costly on SIMT hardware), calls to `Math.*` functions (`sin`, `cos`, `sqrt`, `abs`...), `HvtContext.globalId()` for thread indexing, and `HvtContext.barrier()` for local synchronization. The `bilinearZoom` kernel in Section 3.2 is representative of this subset: primitive arithmetic on integer arrays, thread-indexed output, no allocation, no exceptions.

On the absence of exceptions in GPU kernels: Java exceptions rely on stack unwinding to locate handlers — a mechanism incompatible with the GPU execution model, in which thousands of threads execute the same instruction simultaneously (SIMT). A thread that encounters an exceptional condition cannot independently unwind a call stack or signal the JVM. GPU kernels therefore produce results silently, with no standard mechanism for error propagation. Section 4.3 proposes a lightweight model to address this.

### 4.2 Compilation Strategy

TornadoVM demonstrates the appropriate integration point: JVMCI [JEP-243], the Java Virtual Machine Compiler Interface available since Java 9, allows a custom JIT compiler to be plugged into the JVM without modifying `javac` or requiring a separate compilation tool. HVT kernel compilation would follow the same model. Methods annotated with `@HvtKernel` would be intercepted by a JVMCI-based compiler that: (1) validates the method against the HVT subset constraints, emitting errors for unsupported constructs; (2) constructs an intermediate representation from the validated bytecode; (3) generates a SPIR-V binary module via the Beehive SPIR-V Toolkit [BEEHIVE], or an alternative backend for `AcceleratorType`s where SPIR-V coverage is incomplete.

Working at the bytecode level via JVMCI, rather than at the source level, is consistent with TornadoVM's approach and avoids dependency on `javac` internals. The tradeoff is the loss of type information erased by the Java compiler; this can be partially recovered via bytecode analysis and type inference.

### 4.3 Atomic Error Signaling Model

While full exception semantics are unachievable within GPU kernels, we propose a lightweight atomic error signaling model that prevents silent incorrect results and provides a Java exception surface on the CPU side.

Each HVT kernel receives an implicit `HvtErrorBuffer` — a device-resident buffer containing a single error code, initialized to zero. When a thread detects an error condition, it writes an error code atomically (using `OpAtomicStore` in SPIR-V) and returns. After kernel completion, the HVT runtime reads this buffer before transferring output data to the JVM heap. If the error code is non-zero, the runtime raises an `HvtKernelException` on the CPU side, and output data is not transferred — preventing corrupted results from reaching the JVM.

This model has important limitations. Threads in the same SIMT warp continue execution after one thread signals an error — partial output corruption may have already occurred before the runtime can intervene. Error granularity is coarse: we know that an error occurred, but not which thread triggered it or at which instruction. Hardware faults (device out-of-memory, driver timeout) are outside this model and must be handled through the OpenCL/Vulkan error reporting mechanisms separately. Despite these limitations, the model provides a meaningful improvement over fully silent failures.

### 4.4 AOT vs JIT Compilation

Two compilation strategies are viable. Ahead-of-time compilation generates SPIR-V binaries at build time, embedded as resources in the application JAR, maximizing startup performance. Just-in-time compilation at class-loading time mirrors TornadoVM's approach and allows runtime specialization. A hybrid approach — AOT by default, JIT for dynamically generated kernels — is likely the most practical initial target.

---

## 5. Memory Model

### 5.1 The Transfer Problem

The fundamental asymmetry between CPU Virtual Threads and HVTs is memory. CPU Virtual Threads share the JVM heap transparently. HVTs targeting a discrete GPU or FPGA operate on physically separate device memory, connected to the host via PCIe. Data must be explicitly transferred in both directions, and this transfer cost can dominate kernel execution time for small workloads.

The HVT API addresses this through `HvtMemory` with explicit `TransferMode` annotations:

- `TO_DEVICE`: copied from JVM heap to device memory before kernel execution
- `FROM_DEVICE`: copied from device memory to JVM heap after kernel execution
- `DEVICE_ONLY`: allocated on device, never transferred — used for intermediate buffers in kernel chains

A `BIDIRECTIONAL` mode is conceivable but is not part of this proposal.

### 5.2 Unified Memory Architectures

A significant architectural trend in SoC and APU designs reduces or eliminates the transfer problem. Systems-on-Chip — including ARM-based mobile SoCs (Qualcomm Snapdragon, Samsung Exynos, MediaTek Dimensity), Apple Silicon, AMD APUs, and NVIDIA Jetson — use a physically unified memory architecture in which CPU and GPU share the same DRAM. There is no PCIe bus, no copy — a pointer into the JVM heap is directly readable by the GPU. On such architectures, `HvtMemory` with `TO_DEVICE` semantics could be implemented as a zero-copy operation.

This architectural trend echoes designs from early heterogeneous systems of the 1980s, where CPU and specialized co-processors shared a common memory space via DMA — a unified memory architecture whose advantages modern SoC designers have rediscovered at vastly greater bandwidth.

The HVT runtime would be architecture-aware, selecting the appropriate transfer strategy based on the detected memory topology. This is directly analogous to the memory topology awareness already present in CUDA Unified Memory and in the explicit memory heap selection API of Vulkan.

### 5.3 Off-Heap Storage via Project Panama

`HvtMemory` would be backed by off-heap memory segments allocated via the Foreign Function & Memory API [PANAMA], a production feature since Java 22. This avoids GC pressure on large data arrays and enables more efficient transfer to device memory, since the data layout is controlled and contiguous. This is consistent with TornadoVM's approach of allocating device-bound data off-heap.

---

## 6. JVM Scheduler Extensions

### 6.1 Accelerators as Carrier Types

In Project Loom, Virtual Threads are scheduled onto carrier threads drawn from a homogeneous `ForkJoinPool`. The HVT model requires extending this to a heterogeneous carrier pool in which carriers may be CPU threads, GPU command queues, DSP task queues, or FPGA execution contexts.

Concretely, this would require a `HvtCarrierRegistry` that discovers available accelerators at JVM startup. TornadoVM demonstrates that this discovery is achievable via OpenCL device enumeration — a standard API call that lists all OpenCL-compatible devices present in the system, without requiring manual configuration. The `HvtCarrierRegistry` could reuse this mechanism directly.

An extended scheduler would, when an HVT is submitted, traverse the `.preferring()` / `.fallbackTo()` chain specified by the developer, selecting the first available carrier of the requested type. When an HVT completes, its continuation is resumed on a CPU carrier, as with standard Virtual Thread unparking.

### 6.2 Device Selection and Fallback

The HVT model distinguishes two levels of routing.

**Intra-AcceleratorType routing** — selecting which specific device among several available devices of the same type to use — can be handled automatically by the `HvtCarrierRegistry`, using a benchmarking-based policy similar to TornadoVM's dynamic reconfiguration: compile and execute the kernel on all available devices of the requested type concurrently, and migrate to the fastest. This is empirical, not heuristic.

**Inter-AcceleratorType routing** — choosing between GPU, DSP, FPGA, and NPU — is the responsibility of the developer, expressed through the `.preferring()` / `.fallbackTo()` chain. The runtime honors this chain in order, falling back to the CPU if no listed `AcceleratorType` is available. Automatic inter-type routing based on kernel analysis would require semantic understanding of the kernel's computational structure that is not reliably achievable at runtime; it is left as a direction for future research.

---

## 7. Open Problems

Several significant open problems remain before HVTs could be proposed as a JEP:

**Kernel verification**: the JVM's class verifier ensures bytecode safety. An analogous kernel verifier must ensure that `@HvtKernel` methods conform to the HVT subset at load time, not just at compile time, to handle dynamically loaded classes.

**Debugging and profiling**: GPU kernel debugging is notoriously difficult. Integration with JVM debugging tools (JDWP, JFR) for HVT kernels is a non-trivial research problem.

**Error model completeness**: the atomic error signaling model proposed in Section 4.3 addresses the most common case but leaves hardware faults, driver errors, and warp-level error propagation unresolved.

**Cross-device chaining**: kernel chaining across different physical devices reintroduces host-mediated PCIe transfers. Direct device-to-device transfer mechanisms (NVLink, GPUDirect) exist but are vendor-specific and not portable via OpenCL/SPIR-V.

**Interaction with structured concurrency**: Project Loom's structured concurrency [JEP-453] defines scoped lifetime for Virtual Thread groups. HVTs should participate in structured concurrency scopes naturally.

**Scheduler fairness**: a GPU command queue is not preemptible in the way a CPU thread is. Long-running HVTs could starve the carrier. GPU preemption support varies by hardware and driver.

**Inter-AcceleratorType dynamic routing**: automatic selection between GPU, DSP, FPGA, and NPU based on kernel characteristics remains an open research problem.

---

## 8. Conclusion

We have proposed Heterogeneous Virtual Threads (HVTs), a conceptual extension of Project Loom's Virtual Thread model to hardware accelerators. The key contributions of this proposal are:

- A `Thread`-consistent API for submitting work to GPU, DSP, FPGA, and NPU carriers, making heterogeneous execution a natural extension of Java's concurrency model rather than a separate programming paradigm.
- A kernel chaining model with explicit `TransferMode` semantics that minimizes costly PCIe data transfer, particularly effective on unified memory architectures.
- A JVMCI-based compilation path from a constrained Java subset to SPIR-V via the Beehive SPIR-V Toolkit, targeting the full range of OpenCL/Vulkan-compatible devices.
- An accelerator taxonomy (`AcceleratorType`) with developer-controlled fallback ordering via `.preferring()` / `.fallbackTo()`.
- A novel atomic error signaling model that prevents silent incorrect results from reaching the JVM heap.

HVTs are not a replacement for TornadoVM or Project Babylon/HAT, which provide mature and complementary approaches to Java GPU acceleration. Rather, they represent a deeper integration point — bringing accelerator execution into the JVM's core threading model, with the long-term goal of making heterogeneous computing as transparent to the Java developer as virtual memory or garbage collection.

The technological foundations are in place: Project Loom is production-ready in Java 21 [JEP-444], Project Panama (Foreign Function & Memory API) is stable since Java 22 [PANAMA], the Beehive SPIR-V Toolkit provides a Java-native SPIR-V code generation backend, and SPIR-V's adoption across all major GPU vendors provides the portability layer that previous attempts lacked. The primary remaining work is in compiler frontend design via JVMCI, JVM scheduler extension, memory model formalization, and error handling semantics.

---

## References

- [JEP-243] JEP 243: Java-Level JVM Compiler Interface (Java 9). https://openjdk.org/jeps/243
- [JEP-444] JEP 444: Virtual Threads (Java 21). https://openjdk.org/jeps/444
- [JEP-453] JEP 453: Structured Concurrency (Java 21, Preview). https://openjdk.org/jeps/453
- [JEP-454] JEP 454: Foreign Function & Memory API (Java 22). https://openjdk.org/jeps/454
- [PANAMA] Project Panama — Foreign Function & Memory API (Java 22). https://openjdk.org/jeps/454
- [JEP-529] JEP 529: Vector API (Eleventh Incubator, Java 26). https://openjdk.org/jeps/529
- [JSR-335] JSR 335: Lambda Expressions for the Java Programming Language (Java 8). https://jcp.org/en/jsr/detail?id=335
- [LOOM] Project Loom. https://openjdk.org/projects/loom/
- [TORNADO] TornadoVM: A practical and efficient heterogeneous programming framework for managed languages. Fumero et al. https://github.com/beehive-lab/TornadoVM
- [BABYLON] Project Babylon / HAT. https://openjdk.org/projects/babylon/
- [BABYLON-HAT] Optimizing GPU Programs from Java using Babylon and HAT. https://openjdk.org/projects/babylon/articles/hat-matmul/hat-matmul
- [BEEHIVE] Accessible Dynamic SPIR-V Code Generation from Java. Fumero et al., VMIL'23. https://dl.acm.org/doi/10.1145/3623507.3623555
- [SUMATRA] OpenJDK Project Sumatra. https://wiki.openjdk.org/display/Sumatra/Main
- [MARAWACC] Fumero, J. Accelerating interpreted programming languages on GPUs with just-in-time compilation and runtime optimisations. PhD Dissertation, University of Edinburgh, 2017.
- [APARAPI] Aparapi: API for data parallel Java. https://github.com/Syncleus/aparapi
- [ROOTBEER] Pratt-Szeliga, P. Rootbeer: Seamlessly Using GPUs from Java. IEEE, 2012.
- [JABEE] Zaremba, W., Lin, Y., Grover, V. JaBEE: Framework for object-oriented Java bytecode compilation and execution on GPUs. NVIDIA, 2012.
- [OPENJ9] Eclipse OpenJ9 GPU API. https://eclipse.dev/openj9/docs/introduction/
- [SPIRV] SPIR-V Specification. Khronos Group. https://www.khronos.org/registry/SPIR-V/
- [OCL] OpenCL Specification. Khronos Group. https://www.khronos.org/opencl/
- [VULKAN] Vulkan Specification. Khronos Group. https://www.vulkan.org/
- [MOJO] Mojo Programming Language. https://www.modular.com/mojo
- [JULIA] CUDA.jl: Julia GPU Programming. https://cuda.juliagpu.org/
- [SFT] Swift for TensorFlow. https://github.com/tensorflow/swift
- [MLIR] MLIR: Multi-Level Intermediate Representation. https://mlir.llvm.org/
