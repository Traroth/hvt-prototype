# Backlog — hvt-prototype

Pending tasks and known issues, grouped by component. Update this file at
the end of each session.

---

## Project-wide

- [x] Generate Panama/jextract bindings for Vulkan headers (jextract 25, Vulkan SDK 1.4.341.1, 2346 files → `fr.dufrenoy.hvt.runtime.vulkan`)
- [x] Configure `pom.xml` with all required dependencies (beehive-lab:beehive-spirv-lib:0.0.5 installé localement, JUnit 5.11.0)
- [ ] Write `README.md`

---

## HVT API (`fr.dufrenoy.hvt.api`)

- [x] Implement `HvtMemory<T>` with `TransferMode` semantics
- [x] Implement `AcceleratorType` enum (GPU, CPU fallback)
- [x] Implement `HvtThread` façade with `.preferring()` / `.fallbackTo()` builder

---

## Kernel (`fr.dufrenoy.hvt.kernel`)

- [ ] Implement `KernelCompiler` — generates SPIR-V for `bilinearZoom` via Beehive
- [ ] Validate generated SPIR-V binary (spirv-val if available)

---

## Runtime (`fr.dufrenoy.hvt.runtime`)

- [ ] Implement `HvtCarrierRegistry` — Vulkan device discovery via Panama
- [ ] Implement `KernelDispatcher` — submits SPIR-V to Vulkan compute queue via Panama

---

## Error model (`fr.dufrenoy.hvt.error`)

- [ ] Implement `HvtErrorBuffer` — device-resident error code with atomic write
- [x] Implement `HvtKernelException`
- [ ] Integration test: kernel signalling error does not transfer output data
- [ ] Handle hardware faults via Vulkan error codes (device OOM, driver timeout)

---

## Integration tests

- [ ] End-to-end test: `bilinearZoom` on GPU produces correct output
- [ ] Benchmark: GPU vs. sequential CPU speedup on 3840×2160 image (log result)

---

## Done

- [x] Verify Vulkan 1.3 + SPIR-V support on target GPU (NVIDIA GTX 1080)
