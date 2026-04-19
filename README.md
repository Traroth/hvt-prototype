# hvt-prototype

## What this is

`hvt-prototype` is a proof-of-concept supporting the research paper
*Heterogeneous Virtual Threads: Extending Project Loom for First-Class Accelerator Support in the JVM*.

It demonstrates the feasibility of the proposed HVT stack end-to-end:

- A Java-defined compute kernel (`bilinearZoom`) compiled to SPIR-V via the [Beehive SPIR-V Toolkit](https://github.com/beehive-lab/beehive-spirv-toolkit)
- Dispatch to a GPU via Vulkan Compute, using Project Panama (Foreign Function & Memory API, JEP 454)
- A façade API consistent with the paper's proposed `Thread.ofHeterogeneousVirtual()` model

This is a research prototype, not a production library. Its sole goal is demonstrating feasibility and measuring speedup.

---

## Stack

```
User code
    │
    ▼
HVT API façade  (HvtThread / HvtMemory / TransferMode)
    │
    ▼
HvtCarrierRegistry  —  Vulkan device discovery via Panama
    │
    ▼
KernelDispatcher  —  submits SPIR-V + VkFence; parks virtual thread
    │                  (carrier thread freed during GPU execution)
    │◄── GpuCompletionScheduler  —  polls fence, unparks on completion
    ▼
KernelCompiler  —  generates SPIR-V binary via Beehive SPIR-V Toolkit
    │
    ▼
Vulkan Compute runtime  —  executes on GPU
```

### Package layout

| Package | Contents |
|---|---|
| `fr.dufrenoy.hvt.api` | `HvtThread`, `HvtMemory`, `TransferMode`, `AcceleratorType` |
| `fr.dufrenoy.hvt.kernel` | `KernelCompiler` — SPIR-V code generation |
| `fr.dufrenoy.hvt.runtime` | `HvtCarrierRegistry`, `KernelDispatcher`, `GpuCompletionScheduler` — Vulkan dispatch + async completion |
| `fr.dufrenoy.hvt.runtime.vulkan` | Panama/jextract bindings for Vulkan — do not edit |
| `fr.dufrenoy.hvt.error` | `HvtErrorBuffer`, `HvtKernelException` |

---

## Results

Kernel: bilinear zoom, 1920×1080 → 3840×2160 (8.3 M output pixels).
Hardware: Intel Core i7-8700K (3.7 GHz), 32 GB RAM, NVIDIA GTX 1080 (8 GB GDDR5X, PCIe 3.0 ×16), driver 576.52 DCH.

| Mode | GPU | CPU (sequential Java) | Speedup |
|---|---|---|---|
| Single dispatch (includes full transfer round-trip) | ~185 ms | ~210 ms | ~2.4× |
| Batch ×20 (device buffers persistent, transfer amortised) | ~13 ms/iter | ~208 ms/iter | ~15.5× |

The single-dispatch figure reflects the cost of host↔device transfer (allocation, upload, download)
dominating the compute time. The batch figure isolates GPU compute throughput: 13 ms to process 8.3 M
pixels per iteration vs. 208 ms for a single sequential CPU thread.

---

## Requirements

| Requirement | Version |
|---|---|
| Java | 25 (Panama FFM API required) |
| Maven | 3.9+ |
| Vulkan SDK | 1.4.341.1+ (provides `vulkan-1.dll` and `spirv-val`) |
| GPU | Any Vulkan 1.3 compute-capable device (tested on NVIDIA GTX 1080) |

The Beehive SPIR-V Toolkit (`beehive-spirv-lib:0.0.5`) must be installed in the local Maven
repository before building. It is not available on Maven Central.

---

## Building and running

```bash
# Build and run all tests (including GPU integration tests and quick benchmarks)
mvn test

# Build the JMH benchmark jar
mvn package

# Run JMH benchmarks (GPU required for gpuSingle and gpuBatch)
java --enable-native-access=ALL-UNNAMED --enable-preview -jar target/benchmarks.jar
```

GPU tests skip automatically if no compute-capable Vulkan device is found. The quick benchmark
(`BilinearZoomBenchmarkTest`) always runs the CPU path and logs a `[HVT Benchmark]` /
`[HVT Batch Benchmark]` line regardless.

The JMH benchmark (`HvtBenchmark`) runs three benchmarks: `cpuSingle`, `gpuSingle`, and
`gpuBatch` (20 iterations with persistent device buffers). The setup phase warms up the Vulkan
driver with 3 dispatches and logs a `[Phase breakdown]` line decomposing the single-dispatch
time into upload, compute, and download.

---

## Design notes

### Façade API, not JVM integration

`HvtThread` is implemented as a thin façade over a standard `ExecutorService`. The proposed
`Thread.ofHeterogeneousVirtual()` API is preserved exactly, but the JVM scheduler is not extended
to support heterogeneous carriers — doing so would require modifying the JVM itself, outside the
scope of this POC.

### API usage

```java
try (HvtMemory<int[]> src    = HvtMemory.of(pixels, TransferMode.TO_DEVICE);
     HvtMemory<int[]> dst    = HvtMemory.of(new int[w * h], TransferMode.FROM_DEVICE);
     HvtMemory<int[]> params = HvtMemory.of(new int[]{srcW, srcH, dstW, dstH}, TransferMode.TO_DEVICE)) {

    HvtThread t = HvtThread.builder()
            .preferring(AcceleratorType.GPU)
            .fallbackTo(AcceleratorType.CPU)
            .kernel(Kernels::bilinearZoom, src, dst, params)
            .start();
    t.join();

    int[] result = dst.get();
}
```

### Vulkan Compute over OpenCL

NVIDIA's proprietary OpenCL driver does not support `clCreateProgramWithIL`, preventing direct SPIR-V
submission. Vulkan Compute accepts SPIR-V natively via `vkCreateShaderModule` and is architecturally
closer to the paper's long-term vision. Panama bindings are generated by `jextract` from Vulkan
headers — no JNI, no C code.

### Known limitations

- Only `AcceleratorType.GPU` routes to hardware; DSP, FPGA, NPU are not implemented.
- Only the `bilinearZoom` kernel is compiled to SPIR-V; the binding between `HvtKernel` instances
  and SPIR-V binaries is not resolved dynamically at runtime.
- Error granularity via `HvtErrorBuffer` is coarse: SIMT threads continue after one thread signals
  an error, and the triggering thread is not identified.

---

## License

MIT — see file headers.