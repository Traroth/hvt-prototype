# Backlog — hvt-prototype

Pending tasks and known issues, grouped by component. Update this file at
the end of each session.

---

## Project-wide

- [x] Generate Panama/jextract bindings for Vulkan headers (jextract 25, Vulkan SDK 1.4.341.1, 2346 files → `fr.dufrenoy.hvt.runtime.vulkan`)
- [x] Configure `pom.xml` with all required dependencies (beehive-lab:beehive-spirv-lib:0.0.5 installé localement, JUnit 5.11.0)
- [x] Configure JMH 1.37 + maven-shade-plugin in `pom.xml` — produces `target/benchmarks.jar` via `mvn package`
- [x] Write `README.md`

---

## HVT API (`fr.dufrenoy.hvt.api`)

- [x] Implement `HvtMemory<T>` with `TransferMode` semantics
- [x] Implement `AcceleratorType` enum (GPU, CPU fallback)
- [x] Implement `HvtThread` façade with `.preferring()` / `.fallbackTo()` builder

---

## Kernel (`fr.dufrenoy.hvt.kernel`)

- [x] Implement `KernelCompiler` — generates SPIR-V for `bilinearZoom` via Beehive
- [x] Validate generated SPIR-V binary (spirv-val if available)

---

## Runtime (`fr.dufrenoy.hvt.runtime`)

- [x] Implement `HvtCarrierRegistry` — Vulkan device discovery via Panama
- [x] Implement `KernelDispatcher` — submits SPIR-V to Vulkan compute queue via Panama
- [x] Add `KernelDispatcher.submitTimed()` — phase decomposition (upload / compute / download) for JMH setup logging
- [x] Async GPU dispatch — `KernelDispatcher.submit()` uses `VkFence` + `LockSupport.park()` instead of `vkQueueWaitIdle`; carrier thread freed during GPU execution
- [x] Implement `GpuCompletionScheduler` — daemon thread polling `VkFence` handles, unparks waiting virtual threads on completion

---

## Error model (`fr.dufrenoy.hvt.error`)

- [x] Implement `HvtErrorBuffer` — device-resident error code with atomic write
- [x] Implement `HvtKernelException`
- [x] Integration test: kernel signalling error does not transfer output data
- [x] Handle hardware faults via Vulkan error codes (device OOM, driver timeout)

---

## Integration tests

- [x] End-to-end test: `bilinearZoom` on GPU produces correct output
- [x] Benchmark: GPU vs. sequential CPU speedup on 3840×2160 image (log result) — single-run: 2.4×, batch ×20 (device buffers persistent): 15.5×
- [x] JMH benchmark (`HvtBenchmark`) — `cpuSingle`, `gpuSingle`, `gpuBatch` ×20, GPU warm-up (3 dispatches) + phase breakdown logging in setup — run via `java -jar target/benchmarks.jar`
- [x] Concurrency test — `two_virtual_threads_dispatch_concurrently`: two virtual threads park on GPU simultaneously, both get correct results

---

## Done

- [x] Verify Vulkan 1.3 + SPIR-V support on target GPU (NVIDIA GTX 1080)
