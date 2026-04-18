# Backlog — hvt-prototype

Pending tasks and known issues, grouped by component. Update this file at
the end of each session.

---

## Project-wide

- [ ] Generate Panama/jextract bindings for OpenCL headers
- [ ] Configure `pom.xml` with all required dependencies (Beehive SPIR-V Toolkit, JUnit 5)
- [ ] Verify OpenCL + SPIR-V support on target GPU (NVIDIA GTX 1080, driver version TBD)
- [ ] Write `README.md`

---

## HVT API (`fr.dufrenoy.hvt.api`)

- [ ] Implement `HvtMemory<T>` with `TransferMode` semantics
- [ ] Implement `AcceleratorType` enum (GPU, CPU fallback)
- [ ] Implement `HvtThread` façade with `.preferring()` / `.fallbackTo()` builder

---

## Kernel (`fr.dufrenoy.hvt.kernel`)

- [ ] Implement `KernelCompiler` — generates SPIR-V for `bilinearZoom` via Beehive
- [ ] Validate generated SPIR-V binary (spirv-val if available)

---

## Runtime (`fr.dufrenoy.hvt.runtime`)

- [ ] Implement `HvtCarrierRegistry` — OpenCL device discovery via Panama
- [ ] Implement `KernelDispatcher` — submits SPIR-V to OpenCL command queue via Panama

---

## Error model (`fr.dufrenoy.hvt.error`)

- [ ] Implement `HvtErrorBuffer` — device-resident error code with atomic write
- [ ] Implement `HvtKernelException`
- [ ] Integration test: kernel signalling error does not transfer output data

---

## Integration tests

- [ ] End-to-end test: `bilinearZoom` on GPU produces correct output
- [ ] Benchmark: GPU vs. sequential CPU speedup on 3840×2160 image (log result)

---

## Done

(nothing yet)
