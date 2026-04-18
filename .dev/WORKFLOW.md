# Workflow — hvt-prototype

This document defines the standard workflow for implementing or modifying
a class in this project. All steps should be followed in order unless
explicitly instructed otherwise.

Use Claude Sonnet for all steps.

---

## Overview

The development workflow follows a **design-first approach**:

1. Discussion
2. Update `ARCHITECTURE.md`
3. Update `BACKLOG.md`
4. Implementation
5. Integration tests
6. Static analysis
7. Documentation
8. Session wrap-up

---

## 1. Discussion

**Use Plan mode** — no code modifications should be made during this step.

Before writing any code, clarify:

- The role of the class in the overall POC stack
- Its inputs and outputs
- Its relationship to the Beehive SPIR-V Toolkit, Project Panama, or the HVT API
- Any design decisions that differ from what the paper proposes (and why)

Do not move to the next step until the design is clearly agreed upon.

---

## 2. Update ARCHITECTURE.md

Document the design decisions before writing any code:

- The role of the class in the stack
- Key implementation choices
- Alternatives considered and rejected
- Any deviation from the paper's proposed design

---

## 3. Update BACKLOG.md

Add or update tasks for the class:

- Implementation tasks
- Integration test tasks
- Known limitations or future improvements

---

## 4. Implementation

Write the class following `.dev/standards/JAVA_STANDARDS.md`.

Key principles:

- Clarity over cleverness — this is a research prototype, readability matters
- Prefer small, focused classes
- Keep the public API consistent with the paper's proposed API
- Panama bindings for OpenCL are generated via `jextract` — do not write
  JNI or C code

---

## 5. Integration tests

Write integration tests that verify the stack works end-to-end.

Tests are located in `src/test/java/fr/dufrenoy/hvt/`.

Focus on:

- Correct kernel execution (output matches expected result)
- `HvtErrorBuffer` correctly propagates errors
- `TransferMode` semantics (data transferred at the right time)
- Measured speedup vs. sequential CPU execution (logged, not asserted)

Unit tests are not required for every class. Prefer integration tests that
exercise the full stack. Mock only what cannot run in the test environment
(e.g. GPU not available).

---

## 6. Static analysis

Run the `static-analysis` skill after every class generation or significant
modification. Resolve all Critical findings before continuing.

---

## 7. Documentation

Update Javadoc when public behaviour changes. Update `README.md` when:

- A new component of the stack is implemented
- A benchmark result is obtained

Run the `update-readme` skill when this applies.

---

## 8. Session wrap-up

At the end of every session, run:

- `update-backlog`
- `update-readme` (if API or architecture changed)

This step must never be skipped.

---

## Guiding principles

### Design before code

Never implement a class before its role in the stack is clearly defined.

### Fix known issues before adding new code

Record all known issues in `BACKLOG.md` and resolve them before advancing.

### Stay within POC scope

This is a proof-of-concept. Do not implement features that are not needed
to demonstrate the stack end-to-end and measure speedup.

### Small iterations

Scaffold first, implement minimal behaviour, validate, then extend.
