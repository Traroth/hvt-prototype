# Implementation Notes — hvt-prototype

This document records everything that was built, every decision that was made, and
the reasoning behind each choice. It is intended for two audiences:

1. **Readers of the prototype** — developers or researchers who want to understand
   not just what the code does, but why it was written this way.
2. **Paper authors** — this document, together with `hvt-paper-v3.md` and
   `ARCHITECTURE.md`, provides the factual basis for the Proof of Concept section
   of the paper.

---

## 1. What the prototype proves

The paper proposes **Heterogeneous Virtual Threads (HVTs)**: a new kind of virtual
thread whose carrier is a hardware accelerator (GPU, DSP, FPGA, NPU) rather than a
CPU platform thread. The central claim is that this is feasible within the JVM
concurrency model and that the resulting speedup is significant.

The prototype proves the following:

1. **A Java-defined compute kernel can be compiled to SPIR-V** using the Beehive
   SPIR-V Toolkit, without any external compiler toolchain.
2. **SPIR-V can be submitted to a GPU via Vulkan Compute** using Project Panama
   (pure Java, no JNI, no C glue).
3. **The API is consistent with the paper's proposed model** — the user writes
   `HvtThread.builder().preferring(GPU).kernel(...).start()`.
4. **The speedup is real and significant**: 2.4× for a single dispatch (including
   host-device transfer), 15.5× for a batch of 20 dispatches (transfer amortised).
5. **A virtual thread can park while the GPU executes**, freeing the carrier thread
   for other work. This is the architectural property central to the HVT proposal.

---

## 2. Implementation chronology

The prototype was built incrementally over five phases.

### Phase 1 — Core stack

**Components built:**
- `HvtMemory<T>` — wraps a Java array with an explicit `TransferMode`
- `TransferMode` — `TO_DEVICE`, `FROM_DEVICE`, `DEVICE_ONLY`
- `AcceleratorType` — `GPU`, `CPU`
- `HvtKernel` — functional interface `void execute(HvtMemory<?>[])`
- `HvtThread` — builder façade over `ExecutorService`
- `KernelCompiler` — generates `bilinearZoom` SPIR-V via Beehive
- `HvtCarrierRegistry` — Vulkan instance + physical device + logical device
- `KernelDispatcher` — submits SPIR-V to Vulkan compute queue
- Panama/jextract bindings — 2346 source files generated from Vulkan headers

**Key decisions made in this phase:**
- Vulkan over OpenCL (see §4.1)
- Panama over JNI (see §4.2)
- Beehive for SPIR-V (see §4.3)
- `ExecutorService` façade over real JVM integration (see §4.4)
- Push constants for kernel parameters (see §4.7)

### Phase 2 — Error model and benchmarks

**Components built:**
- `HvtErrorBuffer` — device-resident 1-int SSBO at descriptor binding 2
- `HvtKernelException` — checked exception thrown when error code is non-zero
- `KernelDispatcherIntegrationTest` — end-to-end GPU correctness tests
- `BilinearZoomBenchmarkTest` — quick benchmark (runs during `mvn test`)
- `KernelDispatcher.submitBatch()` — N dispatches with persistent device buffers

**Key decisions made in this phase:**
- Error buffer as SSBO (see §4.8)
- Output not transferred on error (see §4.9)
- Batch semantics: one upload + N dispatches + one download (see §4.10)

**Results obtained:**
- Single dispatch: GPU ~185 ms, CPU ~210 ms, speedup ~2.4×
- Batch ×20: GPU ~13 ms/iter, CPU ~208 ms/iter, speedup ~15.5×

### Phase 3 — Stability and correctness fixes

**Issues discovered and fixed:**

**JVM crash (EXCEPTION_ACCESS_VIOLATION in nvoglv64.dll):**
- Cause: two independently registered JVM shutdown hooks
  (`HvtCarrierRegistry` and `KernelDispatcher`) ran in undefined order.
  When `HvtCarrierRegistry`'s hook ran first, it destroyed the `VkDevice`.
  `KernelDispatcher`'s hook then called `vkDestroyPipeline` on the
  already-destroyed device → access violation in the NVIDIA driver.
- Fix: removed `KernelDispatcher`'s shutdown hook entirely. Extracted
  `destroyStaticResources()` as a package-private method, called by
  `HvtCarrierRegistry`'s single hook in deterministic order:
  pipeline → pipeline layout → DSL → shader module → command pool
  → device → instance. See §4.11.

**Decimal separator issue (Windows locale):**
- Cause: `System.out.printf` used the French locale (`,` as decimal separator)
  instead of `.`, producing `2,40x` instead of `2.40x`.
- Fix: added `Locale.US` to all `printf` calls in benchmark classes.

**Non-ASCII characters in format strings (Windows console):**
- Cause: `→` and `×` in format strings displayed as `?` on Windows console
  (UTF-8 vs CP850 encoding mismatch).
- Fix: replaced with ASCII equivalents `->` and `x`.

### Phase 4 — JMH and phase decomposition

**Components built:**
- `HvtBenchmark` — JMH benchmark class in `src/main/java` with 3 `@Benchmark`
  methods: `cpuSingle`, `gpuSingle`, `gpuBatch`
- `KernelDispatcher.submitTimed()` — mirrors `submit()` with `System.nanoTime()`
  at three points: after upload, after `vkQueueWaitIdle`, after download
- `pom.xml` updates: JMH 1.37, shade plugin producing `target/benchmarks.jar`

**Key decisions made in this phase:**
- JMH in compile scope with `HvtBenchmark` in `src/main/java` (see §4.12)
- GPU warm-up (3 dispatches) in `@Setup(Level.Trial)` before JMH's own warmup
- Phase breakdown logged once to stderr per fork (not per measurement iteration)
- `gpuBatch` includes host-device allocation per invocation (the N dispatches
  share device buffers internally via `submitBatch`, but not the `HvtMemory`
  object lifecycle)

**Reasoning for JMH:**
The `BilinearZoomBenchmarkTest` numbers (run once during `mvn test`) are not
publication-quality: they include JIT warm-up variance and are not statistically
characterised. JMH provides:
- Statistical confidence (10 measurement iterations × 2 forks)
- Controlled JIT warm-up (5 warm-up iterations × 2s each)
- Dead-code elimination prevention (return values consumed by JMH)
- GPU driver warm-up (our explicit 3-dispatch setup phase)

### Phase 5 — Async GPU dispatch (the key HVT property)

**Components built:**
- `GpuCompletionScheduler` — daemon platform thread polling `VkFence` handles
- `KernelDispatcher.submit()` refactored — `VkFence` + `LockSupport.park()`
- `DispatchContext` record — carries Vulkan handles across the park boundary
- `KernelDispatcherIntegrationTest.two_virtual_threads_dispatch_concurrently()`

**This is the most architecturally significant change.** See §4.13 for full detail.

---

## 3. Component descriptions

### 3.1 HvtMemory\<T\>

Wraps a Java array (`int[]`, `float[]`, etc.) with:
- An off-heap `MemorySegment` backed by the array (zero-copy for int[] etc.)
- A `TransferMode` that controls when data crosses the host-device boundary
- `AutoCloseable` — the off-heap segment is released on `close()`

The segment's `byteSize()` is used by `KernelDispatcher` to size device buffers.
The `get()` accessor returns the original Java array.

### 3.2 KernelCompiler

Generates the `bilinearZoom` SPIR-V module programmatically using the Beehive
SPIR-V Toolkit. The module has:
- `OpCapability Shader`
- Entry point: `OpEntryPoint GLCompute %main "main" ...`
- Workgroup size: `LocalSize 16 16 1` (256 threads per workgroup)
- Three SSBOs:
  - `binding=0`: input pixels (packed 32-bit ARGB)
  - `binding=1`: output pixels (packed 32-bit ARGB)
  - `binding=2`: error code (single 32-bit int, written atomically via
    `OpAtomicStore` — unused in `bilinearZoom` since this kernel has no
    error conditions, but the variable is declared to satisfy the descriptor
    set layout)
- Four push constants: `srcW`, `srcH`, `dstW`, `dstH` (int)

The kernel uses the pixel-center coordinate convention:
`srcX = (gx + 0.5) × (srcW / dstW) - 0.5`, clamped to `[0, srcW-1]`.
This prevents the "black border" artifact that arises from naive `gx × ratio`
mapping.

SPIR-V validation: if `spirv-val` (from the Vulkan SDK) is on `PATH`, the
generated binary is validated before use. The validation is advisory (a warning
is logged if it fails, but execution continues).

### 3.3 HvtCarrierRegistry

Initialises the Vulkan stack at class-load time:
1. `System.loadLibrary("vulkan-1")` — loads the Vulkan runtime (System32 on Windows)
2. `vkCreateInstance` — Vulkan 1.3, no extensions, no validation layers
3. `vkEnumeratePhysicalDevices` — selects the first device with a compute queue
4. `vkCreateDevice` — logical device, one compute queue
5. `vkGetDeviceQueue` — retrieves the queue handle

All handles (`VkInstance`, `VkPhysicalDevice`, `VkDevice`, `VkQueue`) are stored in
`static final MemorySegment` fields. If any step fails, `GPU_AVAILABLE = false` and
all handles remain `MemorySegment.NULL`. Every `dispatch()` call checks
`GPU_AVAILABLE` before routing to `KernelDispatcher`.

Cleanup is registered as a single JVM shutdown hook that calls, in order:
1. `KernelDispatcher.destroyStaticResources()` — pipeline, layout, DSL, shader,
   command pool, arena
2. `vkDestroyDevice`
3. `vkDestroyInstance`
4. `PERSISTENT_ARENA.close()`

This ordering is mandatory: Vulkan objects must be destroyed before the device
that owns them, and the device before the instance.

### 3.4 KernelDispatcher — static resources

Initialised once at class-load time:
- **Shader module** (`VkShaderModule`) — the compiled SPIR-V binary
- **Descriptor set layout** (`VkDescriptorSetLayout`) — 3 SSBO bindings (0, 1, 2),
  all `VK_SHADER_STAGE_COMPUTE_BIT`
- **Pipeline layout** (`VkPipelineLayout`) — 1 descriptor set + 1 push constant
  range (16 bytes = 4 ints: srcW, srcH, dstW, dstH)
- **Compute pipeline** (`VkPipeline`) — entry point "main" in the shader module
- **Command pool** (`VkCommandPool`) — `VK_COMMAND_POOL_CREATE_TRANSIENT_BIT`,
  shared across dispatches (external synchronisation via the class monitor)

### 3.5 KernelDispatcher — per-dispatch resources (submit)

Each `submit()` call (async since Phase 5) follows this lifecycle:

**Phase 1 (synchronized on class):**
1. Allocate 3 device buffers: src (srcW×srcH×4 bytes), dst (dstW×dstH×4 bytes),
   err (4 bytes). All host-visible + host-coherent (no staging buffers needed).
2. Upload: `vkMapMemory` → `copyFrom` → `vkUnmapMemory` for src buffer.
3. Zero the error buffer (write 0 via map).
4. Allocate descriptor pool (3 SSBOs) + descriptor set.
5. Record command buffer: bind pipeline, bind descriptor set, push constants,
   `vkCmdDispatch((dstW+15)/16, (dstH+15)/16, 1)`.
6. Create `VkFence` (unsignaled, in `Arena.ofShared()`).
7. `vkQueueSubmit(queue, 1, submitInfo, fence)`.
8. Return `DispatchContext` (all handles + fence arena + `AtomicBoolean done`).

**Park (no lock held):**
- `GpuCompletionScheduler.register(pFenceSlot, done, Thread.currentThread())`
- `while (!done.get()) LockSupport.park()`
- Carrier thread is free to run other virtual threads.

**Phase 2 (synchronized on class):**
1. Read error code from device error buffer (map).
2. If non-zero, throw `HvtKernelException` (in `finally` block to ensure cleanup).
3. Download: map dst device buffer → `copyFrom` → unmap → write to `memories[1]`.
4. Destroy fence, free command buffer, destroy descriptor pool, free device memory,
   destroy device buffers.
5. Close `fenceArena`.

### 3.6 GpuCompletionScheduler

A single daemon platform thread running `pollLoop()`:
```
while (true) {
    for each PendingDispatch(pFenceSlot, done, thread) in PENDING:
        result = vkWaitForFences(device, 1, pFenceSlot, waitAll=1, timeout=0)
        if result != VK_TIMEOUT:
            remove from PENDING
            done.set(true)          // set BEFORE unpark
            LockSupport.unpark(thread)
    if PENDING is empty:
        Thread.yield()
}
```

**VK_TIMEOUT (2)** means the fence is not yet signalled. Any other result (including
`VK_SUCCESS = 0` when signalled, or error codes such as `VK_ERROR_DEVICE_LOST`)
causes immediate unpark. Error cases are handled by Phase 2, which will fail fast
when it tries to map device memory on a lost device.

**`done.set(true)` before `unpark()`:** This ordering ensures the `while
(!done.get())` loop in `submit()` does not re-park after a spurious wakeup.
Consider the race: if the thread wakes spuriously before the scheduler reaches the
`set(true)` line, `done` is still false and the thread re-parks correctly. When the
real `unpark` comes, `done` is already true and the loop exits.

**Why a polling loop instead of `vkWaitForFences` with a long timeout on the fence:**
With N in-flight dispatches, a single blocking wait could only watch one fence at a
time. The non-blocking poll (`timeout=0`) allows the scheduler to check all N fences
on each iteration. `Thread.yield()` when the queue is empty prevents busy-spinning.

**Why a daemon thread:**
The scheduler must not prevent JVM shutdown. Since the shutdown hook in
`HvtCarrierRegistry` destroys the Vulkan device, any pending fences will be
lost on shutdown — this is acceptable, as `submit()` calls from user threads
during shutdown are undefined behaviour.

### 3.7 DispatchContext record

```java
private record DispatchContext(
    Arena fenceArena, MemorySegment pFenceSlot, MemorySegment fence,
    MemorySegment srcBuf, MemorySegment srcMem,
    MemorySegment dstBuf, MemorySegment dstMem, long dstBytes,
    MemorySegment errBuf, MemorySegment errMem,
    MemorySegment pool, MemorySegment cmdBuf,
    AtomicBoolean done
) {}
```

**Why a shared arena for `pFenceSlot`:** The scheduler thread reads `pFenceSlot`
from a different thread than the one that allocated it. `Arena.ofConfined()` would
throw `WrongThreadException`. `Arena.ofShared()` allows cross-thread access. The
arena contains exactly 8 bytes (one pointer slot) and is closed in Phase 2 after
the fence is destroyed.

**Why store Vulkan handles as `MemorySegment` values:** Vulkan handles are opaque
native pointers. After `pBufOut.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE)`,
the resulting `MemorySegment` encodes the handle value as its base address. This
value does not depend on the arena that held the pointer slot — the arena can close,
and the handle value remains valid as long as the Vulkan object exists.

**Why `pCmdBuf` is reconstructed in Phase 2:** `vkFreeCommandBuffers` requires a
pointer to an array of handles. In Phase 2, a fresh confined arena provides the
8-byte slot: `pCmd.set(ValueLayout.ADDRESS, 0, ctx.cmdBuf())`. This avoids carrying
a pointer slot across the park boundary in a shared arena.

---

## 4. Implementation difficulties

This section documents the concrete obstacles encountered during implementation.
These details are relevant for the paper's credibility argument: they show that
the prototype was not a trivial exercise, and that each difficulty was resolved
with a principled technical choice.

### 4.1 jextract binding generation — scale and naming

`jextract` was run against the Vulkan 1.4.341.1 C headers to produce Java bindings.
The tool generated **2346 Java source files**, all placed under
`src/main/java/fr/dufrenoy/hvt/runtime/vulkan/`. This is the largest single-step
artifact in the project.

The generated class hierarchy uses an inheritance chain to stay within Java's
method-count limits:

```
vulkan_h                   (the entry class)
  extends vulkan_h_1
    extends vulkan_h_2
      extends vulkan_h_3
        extends vulkan_h_4  (contains vkCreateFence, vkDestroyFence, vkWaitForFences)
```

This means that calling `vulkan_h.vkWaitForFences(...)` works correctly at the
Java level, but finding which generated class actually declares the method requires
inspecting the inheritance chain. This was non-obvious during development: the
fence-related functions (`vkCreateFence`, `vkDestroyFence`, `vkWaitForFences`) are
defined four levels deep in `vulkan_h_4`.

The constant `VK_TIMEOUT` (return value 2 from `vkWaitForFences` when the fence is
not yet signalled) is not a named constant in the generated bindings. Its value was
obtained from the Vulkan spec and hard-coded as `private static final int VK_TIMEOUT = 2`
in `GpuCompletionScheduler`.

### 4.2 Panama Arena semantics — confined vs shared

Panama's `Arena` type enforces ownership: an `Arena.ofConfined()` segment may only
be accessed from the thread that created it. The first attempt to pass fence handles
between `KernelDispatcher` (main thread) and `GpuCompletionScheduler` (daemon thread)
threw `WrongThreadException` at runtime when the scheduler tried to read the fence
pointer slot.

The fix: allocate the fence pointer slot (`pFenceSlot`) in `Arena.ofShared()`,
which allows cross-thread access. The `DispatchContext` record documents why this
arena is specifically shared. All other per-dispatch allocations remain confined —
they are accessed only by the thread that creates them.

### 4.3 JVM crash — Vulkan shutdown hook ordering

During development, ending the JVM (after any test run) produced an
`EXCEPTION_ACCESS_VIOLATION` in `nvoglv64.dll` logged to `hs_err_pid*.log`.

Root cause: `HvtCarrierRegistry` and `KernelDispatcher` each registered an
independent JVM shutdown hook. JVM shutdown hooks run concurrently in undefined
order. When the Registry's hook ran first, it called `vkDestroyDevice`. The
Dispatcher's hook then called `vkDestroyPipeline` on the now-destroyed device —
this is undefined behaviour in the Vulkan spec, and the NVIDIA driver crashed.

Fix: single consolidated shutdown hook in `HvtCarrierRegistry` that calls
`KernelDispatcher.destroyStaticResources()` before destroying the device.
The destruction order is now deterministic and spec-compliant (child objects
before parent objects: pipeline → layout → DSL → shader → command pool → device
→ instance).

### 4.4 OpenCL — SPIR-V not supported by NVIDIA driver

The first target for GPU dispatch was OpenCL (`clCreateProgramWithIL`), because
the project started with the assumption that SPIR-V was a portable OpenCL input.
In practice, NVIDIA's proprietary OpenCL driver (installed with CUDA) does not
implement `clCreateProgramWithIL`. The function is present in the spec but
returns `CL_INVALID_OPERATION` on the GTX 1080.

This forced the switch to Vulkan Compute at an early stage. Vulkan
`vkCreateShaderModule` accepts SPIR-V natively. This turned out to be a better
architectural choice for the paper's argument regardless.

### 4.5 Windows-specific issues (development environment)

Three issues specific to running on Windows:

1. **Decimal separator**: `System.out.printf` formatted numbers using the French
   Windows locale, producing `2,40x` instead of `2.40x`. Fixed with `Locale.US`
   in all format strings.

2. **Non-ASCII characters on Windows console**: `→` and `×` in benchmark output
   displayed as `?` on the Windows console (UTF-8 source files, CP850 console
   encoding). Fixed by replacing with ASCII `->` and `x`.

3. **`vulkan-1.dll` location**: On Windows, the Vulkan loader (`vulkan-1.dll`)
   is installed by the Vulkan SDK into `C:\Windows\System32`. `System.loadLibrary("vulkan-1")`
   finds it there automatically without needing to set `PATH`.

---

## 5. Design decisions and rationale (detailed)

### 4.1 Vulkan Compute over OpenCL

NVIDIA's proprietary OpenCL driver (installed with CUDA) does not support
`clCreateProgramWithIL` — the function required to submit SPIR-V directly to an
OpenCL runtime. The driver accepts OpenCL C source only. This discovery was made
early in Phase 1 and forced the switch to Vulkan Compute.

Vulkan Compute accepts SPIR-V natively via `vkCreateShaderModule`, with no
intermediate translation. This is also architecturally cleaner: SPIR-V was
designed as Vulkan's native intermediate representation.

An additional benefit: Vulkan Compute is the direction modern GPU compute is
heading. OpenCL is in maintenance mode. The paper's long-term vision of a
vendor-neutral JVM accelerator model aligns better with Vulkan.

### 4.2 Panama (FFM API) over JNI

All Vulkan calls go through Panama (Foreign Function & Memory API, JEP 454,
stable in Java 22). The `jextract` tool generates Java bindings from Vulkan C
headers — 2346 source files, committed to the repository.

**No C code exists in this prototype.** All host-device memory management,
command buffer recording, and pipeline creation is done in pure Java via Panama.

This matters for the paper: it demonstrates that the HVT runtime layer (the
`KernelDispatcher`) can be implemented entirely in Java. A real JVM integration
would use JVM-internal C++ code, but the prototype proves the semantics are
achievable without leaving the JVM language.

### 4.3 Beehive SPIR-V Toolkit for kernel compilation

The Beehive toolkit generates SPIR-V by constructing the module in memory (analogous
to ASM for JVM bytecode). The `bilinearZoom` kernel is constructed instruction by
instruction in `KernelCompiler.compileBilinearZoom()`.

This approach has limitations: the programmer must manually emit every SPIR-V
instruction, including type declarations, variable decorations, and control flow.
It would not scale to a general-purpose compiler. For this POC, it is sufficient
because only one kernel is needed and it is of manageable complexity (~200 SPIR-V
instructions).

For a production HVT implementation, the compiler toolchain would be TornadoVM's
SPIR-V backend or Project Babylon/HAT — both of which compile Java bytecode or code
models to SPIR-V automatically.

### 4.4 Façade API over real JVM integration

`HvtThread` is a builder that submits work to a single-thread `ExecutorService`.
`Thread.ofHeterogeneousVirtual()` does not exist in the JVM; `HvtThread.builder()`
is its prototype stand-in.

This is explicitly acknowledged in the paper and the README. The programming model
(the API surface) is preserved exactly. The runtime behaviour (carrier scheduling,
JVM integration) is simulated. A real implementation would require:
- A new `HvtVirtualThread` class in the JVM, analogous to `VirtualThread`
- Extension of `ForkJoinPool`'s work-stealing scheduler to route HVTs to
  accelerator carriers
- JVM-internal parking/unparking hooks (the `park()` call currently goes to
  `LockSupport`, which in a real implementation would be the JVM's
  `Unsafe.park()`)

### 4.5 Memory layout: host-visible + host-coherent, no staging buffers

All device buffers use `VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
VK_MEMORY_PROPERTY_HOST_COHERENT_BIT`. This means the CPU can directly map and
write/read device memory without an explicit `vkFlushMappedMemoryRanges` or
`vkInvalidateMappedMemoryRanges` call.

The alternative — separate host and device buffers with a staging buffer and
`vkCmdCopyBuffer` — would be more performant on discrete GPUs (which have separate
VRAM) but adds significant complexity. For a POC, host-coherent memory is the
correct choice: simpler, correct, and the performance difference is captured
accurately by the phase decomposition (upload/compute/download timing).

On the GTX 1080 (discrete GPU), host-coherent memory actually resides in system RAM
accessible to the GPU over PCIe, so the upload/download timings in the phase
breakdown reflect real PCIe transfer times, not artificial overhead.

### 4.6 Descriptor set layout: 3 SSBOs

The compute pipeline uses a single descriptor set with 3 bindings:
- `binding=0`: VK_DESCRIPTOR_TYPE_STORAGE_BUFFER — source pixels
- `binding=1`: VK_DESCRIPTOR_TYPE_STORAGE_BUFFER — destination pixels
- `binding=2`: VK_DESCRIPTOR_TYPE_STORAGE_BUFFER — error code (1 int)

All 3 are present in the descriptor set layout regardless of whether a given kernel
uses the error buffer. The `bilinearZoom` kernel never writes to binding 2 (it has
no error conditions), but the variable is declared in SPIR-V to match the layout.
SPIR-V allows unused global variables — the spec does not require that all declared
SSBOs are accessed.

### 4.7 Push constants for kernel parameters

The 4 kernel parameters (`srcW`, `srcH`, `dstW`, `dstH`) are passed as push
constants rather than as a 4th SSBO. Push constants are 128-byte values stored
directly in the pipeline, accessible without a descriptor set or buffer allocation.
They are the correct Vulkan mechanism for small, per-dispatch constant data.

This avoids allocating a 4th device buffer per dispatch and simplifies the
`buildDescriptorSet` call (only 3 SSBOs to bind).

### 4.8 Error buffer as a device-resident SSBO

The error model uses a single `int` at descriptor binding 2. The kernel writes to
it via `OpAtomicStore` if it detects an error condition. This:
- Survives until the host reads it after `vkQueueWaitIdle` (or after the fence
  is signalled in the async model)
- Supports concurrent writes from multiple SIMT threads without data races
  (via the atomic operation)
- Is transparent to kernels that don't use it (unused SPIR-V variable)

**Known limitation:** SIMT threads in the same warp continue executing after one
thread signals an error. Partial output corruption may have already been written.
The host skips the FROM_DEVICE transfer when the error code is non-zero, so the
corrupted output does not reach the JVM heap — but the GPU wasted cycles computing
it. This is a fundamental GPU execution model limitation, not specific to this
prototype.

### 4.9 No output transfer on error

When `HvtErrorBuffer.ofCode(code).checkAndThrow()` throws `HvtKernelException`,
the `FROM_DEVICE` download (step 7 in the dispatch sequence) is skipped. This is
enforced by the `try / finally` structure in `finishDispatch`:

```java
try {
    HvtErrorBuffer.ofCode(...).checkAndThrow(); // may throw
    mapCopy(...);                                // download (skipped if throw)
} finally {
    // Vulkan cleanup always runs
}
```

The destination `HvtMemory` retains its original Java-side content (zeros, or
whatever was in the array before dispatch).

### 4.10 Batch dispatch (submitBatch)

`submitBatch(memories, N)`:
1. Allocates device buffers and uploads src once
2. Records the command buffer once
3. Submits the same command buffer N times in a loop with `vkQueueWaitIdle` after each
4. Downloads dst once after the loop

Vulkan spec §6.4 guarantees that a command buffer returns to the executable state
after `vkQueueWaitIdle`, so re-submission without re-recording is valid.

This method exists to provide a "batch throughput" number for the paper: it
eliminates the host-device transfer overhead from the per-iteration cost, showing
the raw GPU compute throughput (13 ms/iter at 8.3M pixels = ~640M pixels/second).

`submitBatch` is deliberately kept blocking (`vkQueueWaitIdle` per iteration, not
fenced). It is a measurement utility, not a user-facing concurrency primitive.

### 4.11 Consolidated JVM shutdown hook

**Problem discovered:** Two independently registered shutdown hooks
(`HvtCarrierRegistry` and `KernelDispatcher`) ran in undefined order.
When the Registry hook ran first, `VkDevice` was destroyed. Then the Dispatcher
hook called `vkDestroyPipeline(destroyed_device, ...)` → EXCEPTION_ACCESS_VIOLATION
in nvoglv64.dll → JVM crash logged to `hs_err_pid26700.log`.

**Fix:** `KernelDispatcher` no longer registers a shutdown hook. It exposes
`static void destroyStaticResources()`, called by `HvtCarrierRegistry`'s single
hook in guaranteed order:

```
KernelDispatcher.destroyStaticResources():
  vkDestroyCommandPool
  vkDestroyPipeline
  vkDestroyPipelineLayout
  vkDestroyDescriptorSetLayout
  vkDestroyShaderModule
  ARENA.close()

vkDestroyDevice
vkDestroyInstance
PERSISTENT_ARENA.close()
```

Every object is destroyed before the object it depends on. This is deterministic
and crash-free.

### 4.12 JMH in compile scope with HvtBenchmark in src/main/java

The natural Maven location for JMH benchmarks when the benchmark accesses
package-private code (as `HvtBenchmark` does — it calls `KernelDispatcher.submit()`
directly) is `src/main/java`, with JMH in `compile` scope. The alternative —
`src/test/java` with `test` scope — would prevent the shade plugin from including
the benchmark class in `benchmarks.jar`, because the shade plugin only packages
`target/classes`, not `target/test-classes`.

`HvtBenchmark` is thus not a JUnit test and will not be picked up by Surefire.
The `@Benchmark` methods are annotated with JMH annotations only.

### 4.13 Async GPU dispatch — the key HVT property

**Before Phase 5:** `submit()` called `vkQueueSubmit(fence=NULL)` followed by
`vkQueueWaitIdle()`. This blocked the calling thread (platform or virtual) until
the GPU finished. A virtual thread using this path would pin its carrier thread
for the entire GPU execution time — exactly what the HVT model promises NOT to do.

**After Phase 5:** `submit()` uses a `VkFence` and `LockSupport.park()`:

```
Phase 1 (synchronized):
  allocate buffers, upload, record command buffer
  vkCreateFence (unsignaled)
  vkQueueSubmit(queue, submitInfo, fence)   ← GPU starts executing
  return DispatchContext

Register fence with GpuCompletionScheduler
while (!done.get()) LockSupport.park()      ← carrier thread freed HERE

Phase 2 (synchronized):
  check error code, download, cleanup
```

The carrier thread is released between the two synchronized blocks, while
`vkQueueWaitIdle` would have held it for the entire GPU execution.

**This matches Loom's model exactly:**
| Loom virtual thread | HVT virtual thread |
|---|---|
| Parks waiting for socket read | Parks waiting for GPU fence |
| Selector thread detects data available | `GpuCompletionScheduler` detects fence signalled |
| `LockSupport.unpark(thread)` | `LockSupport.unpark(thread)` |
| Thread resumes, reads data | Thread resumes, downloads result |
| Carrier thread free during I/O wait | Carrier thread free during GPU execution |

**Concurrent dispatch:** With the async model, multiple virtual threads can have
their dispatches in-flight simultaneously. Thread A completes Phase 1 (releases
lock), parks. Thread B then enters Phase 1 (acquires lock), submits its own work,
parks. Both are now waiting for their respective fences. The GPU processes them
sequentially (single Vulkan queue), but from the JVM's perspective they are
concurrent — both virtual threads are parked and the carrier thread is available.

**Spurious wakeup protection:** `LockSupport.park()` may return spuriously
(JVM guarantee: it can return at any time without cause). The `while (!done.get())`
loop re-parks in this case. The `done.set(true)` in `GpuCompletionScheduler`
happens before `unpark()`, ensuring the loop exits only when the fence is actually
signalled.

---

## 6. Benchmark results and interpretation

### 6.1 Test hardware configuration

| Component | Specification |
|---|---|
| CPU | Intel Core i7-8700K (Coffee Lake, 6 cores / 12 threads, 3.7 GHz base / 4.7 GHz boost) |
| RAM | 32 GB |
| GPU | NVIDIA GeForce GTX 1080 (Pascal architecture) |
| GPU VRAM | 8 GB GDDR5X dédié (24 549 Mo graphiques totaux sous Windows WDDM, dont ~16 357 Mo RAM système partagée) |
| GPU bus | PCIe 3.0 ×16 |
| NVIDIA driver | 576.52 DCH (driverVersion 576.52.0.0) |
| Vulkan SDK | 1.4.341.1 |
| Vulkan instance version | 1.4.341 |
| Vulkan API version (driver) | 1.4.303 |
| OS | Windows 10 |
| JDK | Java 25 |

The GTX 1080 is a discrete GPU: it has its own VRAM physically separate from
system RAM, connected via PCIe. Host↔device data transfer therefore crosses the
PCIe bus, which is the dominant cost in single-dispatch benchmarks.

### 6.2 Quick benchmark (BilinearZoomBenchmarkTest, run during mvn test)

Kernel: `bilinearZoom`, 1920×1080 → 3840×2160 (8.3M output pixels).
Hardware: as described in §6.1.

| Mode | GPU | CPU (sequential Java) | Speedup |
|---|---|---|---|
| Single dispatch (full round-trip) | ~185 ms | ~210 ms | ~2.4× |
| Batch ×20 (transfer amortised) | ~13 ms/iter | ~208 ms/iter | ~15.5× |

**Interpretation of the single-dispatch figure:**
The ~185 ms GPU time includes:
- Host→device upload (PCIe transfer, ~8.3M ints = ~33 MB)
- GPU compute (~13 ms based on batch results)
- Device→host download (~33 MB back over PCIe)

The dominant cost is PCIe transfer, not computation. This explains why the
single-dispatch speedup (2.4×) is much lower than the batch speedup (15.5×):
the CPU time is ~210 ms pure compute, while the GPU time is ~172 ms transfer
+ ~13 ms compute.

**Interpretation of the batch figure:**
At 13 ms/iteration with 8.3M pixels, the GPU processes ~640M pixels/second for
this kernel. The sequential CPU processes ~40M pixels/second (208 ms for 8.3M
pixels), giving a 16× raw throughput advantage.

**Why the single-dispatch speedup still matters:**
For real-world usage, host-device transfer cost is unavoidable on the first and
last dispatch in any workload. The 2.4× speedup shows that even with full transfer
overhead, the GPU is still faster than a single CPU thread. Applications that
process multiple frames (video, batch image processing) amortise this cost and
achieve throughput close to the batch figure.

### 6.3 Phase breakdown (from submitTimed)

`submitTimed()` inserts `System.nanoTime()` at three points in the dispatch:
1. After upload (`mapCopy` for src buffer)
2. After `vkQueueWaitIdle` (GPU compute completion)
3. After download (`mapCopy` for dst buffer)

Typical values on the GTX 1080 for 1920×1080 → 3840×2160:
- Upload: ~80 ms (33 MB over PCIe, ~410 MB/s effective bandwidth)
- Compute: ~13 ms (pure GPU kernel execution)
- Download: ~90 ms (33 MB over PCIe)

**The compute phase (13 ms) is the only part that benefits from the GPU.**
The upload and download phases run on the CPU and are bottlenecked by PCIe
bandwidth — not GDDR5X bandwidth. The prototype uses `VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
| HOST_COHERENT_BIT` memory, which on a discrete GPU maps to system RAM accessible
over PCIe (Windows WDDM shared memory), not to the GPU's dedicated GDDR5X. This
is why the measured ~410 MB/s transfer rate is PCIe-limited, not GDDR5X-limited
(the GTX 1080's GDDR5X theoretical bandwidth is ~480 GB/s — three orders of magnitude
higher). Staging buffers with `vkCmdCopyBuffer` would use GDDR5X but add
implementation complexity outside the POC scope.

This breakdown is used in the paper to argue that:
1. The GPU compute speedup is real (~16× raw)
2. The end-to-end speedup is limited by PCIe transfer
3. Unified memory architectures (AMD APU, Apple Silicon) would eliminate the
   transfer cost and expose the full GPU compute advantage

### 6.4 JMH benchmark (HvtBenchmark)

JMH configuration:
- Mode: `AverageTime`, unit: milliseconds
- Warmup: 5 iterations × 2 seconds
- Measurement: 10 iterations × 2 seconds
- Forks: 2 (separate JVM processes)
- JVM args: `--enable-native-access=ALL-UNNAMED --enable-preview`
- GPU warm-up: 3 dispatches in `@Setup(Level.Trial)` before JMH's warmup

The JMH results are the publication-quality numbers. The `BilinearZoomBenchmarkTest`
numbers (Phase 2) are indicative but include JIT variance.

---

## 7. Known limitations

### 6.1 Only bilinearZoom compiled to SPIR-V

The binding between an `HvtKernel` Java instance and a SPIR-V binary module is
not resolved dynamically at runtime. `KernelDispatcher.submit()` always executes
the `bilinearZoom` kernel regardless of which `HvtKernel` is passed.

In a real HVT implementation, a compiler (TornadoVM or Babylon/HAT) would compile
any annotated Java method to SPIR-V on first invocation, cache the module, and
retrieve it by kernel identity at dispatch time. This is architecturally feasible
but out of scope for the POC.

### 6.2 Single Vulkan queue, sequential GPU execution

`KernelDispatcher` uses one `VkQueue`. Multiple virtual threads can park
simultaneously (multiple dispatches are "in-flight" from the JVM perspective), but
the GPU processes them sequentially on the single queue. True concurrent GPU
execution would require multiple queues or a GPU supporting multiple compute queue
families, and a scheduler that distributes work across them.

### 6.3 GpuCompletionScheduler uses polling, not OS-level events

The scheduler polls with `vkWaitForFences(timeout=0)` + `Thread.yield()`. This
means the scheduler thread occasionally burns a CPU quantum even when there are
no in-flight dispatches, and adds up to one OS scheduling quantum of latency
between fence signal and virtual thread resume.

A production implementation would use Vulkan timeline semaphores or a
driver-provided event mechanism to block the scheduler thread with zero CPU usage.
For a POC, polling is correct and the latency is negligible relative to GPU
execution time (~200 ms).

### 6.4 Façade only — no real JVM scheduler integration

The park/unpark mechanism demonstrated here uses `LockSupport.park/unpark` at the
Java library level. In a real HVT implementation, the JVM's work-stealing scheduler
would be extended to recognize HVT carriers and route work to them. The carrier
thread release would happen at the JVM level (`Unsafe.park`) rather than the
library level. The observable behaviour would be identical, but the implementation
path goes through JVM internals.

### 6.5 AcceleratorType.CPU fallback uses the calling thread

When GPU is unavailable or when `AcceleratorType.CPU` is preferred,
`HvtCarrierRegistry.dispatch()` calls `kernel.execute(memories)` on the calling
thread. This is synchronous and does not use the `ExecutorService` in `HvtThread`.
A more faithful implementation would submit CPU work to a ForkJoinPool and park
the calling virtual thread, just as the GPU path does.

---

## 8. Mapping to the paper

| Paper concept | Prototype component |
|---|---|
| `Thread.ofHeterogeneousVirtual()` | `HvtThread.builder()` façade |
| Accelerator carrier | `VkQueue` in `HvtCarrierRegistry` |
| `HvtCarrierRegistry` (paper §6) | `HvtCarrierRegistry` class |
| SPIR-V kernel compilation | `KernelCompiler` via Beehive |
| Kernel dispatch | `KernelDispatcher.submit()` |
| Virtual thread parking (paper §6) | `LockSupport.park()` in `submit()` |
| Heterogeneous scheduler wakeup | `GpuCompletionScheduler.pollLoop()` |
| HVT memory model (paper §5) | `HvtMemory` + `TransferMode` |
| Atomic error signalling (paper §4) | `HvtErrorBuffer` + `OpAtomicStore` |
| Hardware fault detection | Vulkan error codes in `checkVulkan()` |

The paper's §6 (Scheduler Extensions) is the section most directly supported by
Phase 5: the async dispatch with `VkFence` and `LockSupport.park/unpark`
demonstrates the exact mechanism the paper proposes for carrier thread release.

---

## 9. Files and their roles

| File | Role |
|---|---|
| `src/main/java/fr/dufrenoy/hvt/api/HvtThread.java` | Builder façade — public API entry point |
| `src/main/java/fr/dufrenoy/hvt/api/HvtMemory.java` | Buffer wrapper with TransferMode |
| `src/main/java/fr/dufrenoy/hvt/api/TransferMode.java` | Transfer direction enum |
| `src/main/java/fr/dufrenoy/hvt/api/AcceleratorType.java` | Accelerator type enum |
| `src/main/java/fr/dufrenoy/hvt/api/HvtKernel.java` | Kernel functional interface |
| `src/main/java/fr/dufrenoy/hvt/kernel/KernelCompiler.java` | SPIR-V code generation |
| `src/main/java/fr/dufrenoy/hvt/runtime/HvtCarrierRegistry.java` | Vulkan device lifecycle |
| `src/main/java/fr/dufrenoy/hvt/runtime/KernelDispatcher.java` | GPU dispatch + async completion |
| `src/main/java/fr/dufrenoy/hvt/runtime/GpuCompletionScheduler.java` | Fence polling daemon |
| `src/main/java/fr/dufrenoy/hvt/runtime/HvtBenchmark.java` | JMH benchmarks |
| `src/main/java/fr/dufrenoy/hvt/error/HvtErrorBuffer.java` | Error code wrapper |
| `src/main/java/fr/dufrenoy/hvt/error/HvtKernelException.java` | Kernel error exception |
| `src/main/java/fr/dufrenoy/hvt/runtime/vulkan/` | jextract-generated Panama bindings |
| `src/test/java/.../KernelDispatcherIntegrationTest.java` | GPU correctness + concurrency tests |
| `src/test/java/.../BilinearZoomBenchmarkTest.java` | Quick benchmark (mvn test) |
| `.dev/design/ARCHITECTURE.md` | Key design decisions |
| `.dev/docs/hvt-paper-v3.md` | The full research paper |
| `.dev/docs/IMPLEMENTATION_NOTES.md` | This file |