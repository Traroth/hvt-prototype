---
name: architecture-drift-check
description: >
  Verify that the current implementation still matches the design decisions
  documented in ARCHITECTURE.md. Run after significant refactoring or when
  the user asks if the implementation still follows the design.
---

# Architecture Drift Check — hvt-prototype

## When to run

- After significant refactoring
- When a new feature is added to an existing component
- When the user asks if the implementation still follows the design

---

## Before running

Read `.dev/design/ARCHITECTURE.md` in full.

---

## Review checklist

Report only findings — skip items with no issues.

### 1. Stack integrity

- [ ] The five layers (API façade → registry → dispatcher → compiler → OpenCL)
  are still cleanly separated
- [ ] No layer directly calls a layer it should not (e.g. API layer calling
  Panama bindings directly)
- [ ] Generated Panama bindings in `runtime.opencl` have not been manually edited

### 2. Technology choices

- [ ] OpenCL is still accessed via Panama, not JNI or JOCL
- [ ] SPIR-V is still generated via Beehive SPIR-V Toolkit
- [ ] `HvtThread` is still a façade over `ExecutorService`, not a real JVM extension

### 3. API consistency with the paper

- [ ] Public API in `fr.dufrenoy.hvt.api` matches the proposed API in the paper
  (`HvtMemory`, `TransferMode`, `AcceleratorType`, builder pattern)
- [ ] `HvtErrorBuffer` semantics preserved: error checked before output transfer,
  output not transferred if error code non-zero

### 4. Scope

- [ ] No features implemented beyond the POC scope defined in `ARCHITECTURE.md`
- [ ] Only `AcceleratorType.GPU` is implemented in the runtime

### 5. Documentation consistency

- [ ] `ARCHITECTURE.md` reflects the current implementation
- [ ] Any new design decision made since last check has been added to `ARCHITECTURE.md`

---

## Output format

Report findings grouped by category. For each finding, state:
- The class or component concerned
- The drift detected (what ARCHITECTURE.md says vs. what the code does)
- The recommended action (update the code, or update the documentation)

If no drift is found in a category, skip it entirely.
