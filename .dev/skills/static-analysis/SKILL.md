---
name: static-analysis
description: >
  Perform a structured static analysis of any Java class in hvt-prototype.
  Use this skill after every class generation or significant modification —
  even if the user doesn't explicitly ask for it. Also trigger when the user
  says "analyse", "check", "review", "audit", or "is this correct".
---

# Static Analysis — hvt-prototype

## When to run

- After generating or significantly modifying any Java class
- When the user asks for a review, audit, or correctness check
- Before marking a backlog task as done

Focus especially on **Panama memory management** and **OpenCL interactions**,
as these are the most common sources of bugs in this codebase.

---

# Analysis checklist

Run every item below. Report only findings — skip items with no issues.

---

## 1. Critical — bugs, memory errors, incorrect kernel results

### Logic and correctness

- [ ] Logic bugs (off-by-one, wrong condition, unreachable branch)
- [ ] Kernel produces incorrect output (wrong pixel values, wrong array indices)
- [ ] `HvtErrorBuffer` not checked before transferring output data
- [ ] Output data transferred even when error code is non-zero

### Panama / memory safety

- [ ] `MemorySegment` accessed after its `Arena` is closed
- [ ] `Arena` closed before kernel execution completes
- [ ] Off-heap memory not released (Arena not closed in finally block or
  try-with-resources)
- [ ] Wrong memory layout used for data transfer (element size, byte order)
- [ ] Native method called with wrong argument types or sizes

### OpenCL correctness

- [ ] OpenCL error codes not checked after API calls
- [ ] Kernel arguments set in wrong order
- [ ] Global work size does not match data dimensions
- [ ] SPIR-V binary not valid for the target device

---

## 2. Major — performance, resource management

### Resource management

- [ ] OpenCL objects (context, queue, program, kernel) not released
- [ ] `MemorySegment` allocated on heap instead of off-heap where device
  transfer is needed

### Performance

- [ ] Unnecessary host-device data transfers
- [ ] `DEVICE_ONLY` buffer copied to host when it should stay on device
- [ ] Synchronous wait where async execution would suffice

### Null safety

- [ ] Missing null check where contract requires it
- [ ] `Optional` used as parameter type or field type (forbidden)

---

## 3. Minor — style, documentation, guideline compliance

### File structure

- [ ] File header missing or malformed
- [ ] Version in file header does not match `pom.xml`

### Imports

- [ ] Wildcard import present
- [ ] Imports not sorted alphabetically
- [ ] Fully qualified reference in code instead of explicit import
  (exception: generated Panama bindings)

### Code organisation

- [ ] Declaration order violated (statics → instance fields → constructors → methods)
- [ ] Section separator missing or malformed

### Documentation

- [ ] Javadoc missing on a public class or public method
- [ ] `@param`, `@return`, or `@throws` missing where applicable

### Language

- [ ] Any identifier, comment, Javadoc, or exception message not in English

---

## Output format

Report findings grouped by severity (Critical / Major / Minor). For each
finding, state:
- The class and method concerned
- The issue
- The recommended fix

If no findings exist in a category, skip it entirely.
