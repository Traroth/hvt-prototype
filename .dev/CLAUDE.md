# CLAUDE.md — hvt-prototype

This is the entry point for Claude Code working on this project. Read this
file in full at the start of every session before generating or modifying
any code.

---

## Project overview

`hvt-prototype` is a proof-of-concept implementation supporting the research
paper *Heterogeneous Virtual Threads: Extending Project Loom for First-Class
Accelerator Support in the JVM*. It demonstrates the feasibility of the
proposed HVT stack end-to-end:

- Kernel compilation to SPIR-V via the Beehive SPIR-V Toolkit
- Dispatch to a GPU via OpenCL using Project Panama (Foreign Function & Memory API)
- A façade API consistent with the paper's proposed `Thread.ofHeterogeneousVirtual()` model

This is a research prototype, not a production library. Code quality standards
still apply, but the goal is demonstrating feasibility and measuring speedup,
not API completeness or broad hardware support.

Read `.dev/docs/hvt-paper-v3.md` to understand the full conceptual context.

---

## Files to read at session start

| File | Purpose |
|---|---|
| `.dev/CLAUDE.md` | This file — read first |
| `.dev/design/ARCHITECTURE.md` | Technical design decisions and rationale |
| `.dev/WORKFLOW.md` | Required order of operations |
| `.dev/standards/JAVA_STANDARDS.md` | Coding standards for all Java code |
| `.dev/backlog/BACKLOG.md` | Pending tasks and known issues |

Read `docs/hvt-paper-v3.md` when context on the conceptual proposal is needed.

---

## Skills

Skills encode recurring workflows. Read the relevant skill file before
starting the corresponding task.

| Task | Skill |
|---|---|
| Generate or significantly modify a class | `.dev/skills/static-analysis/SKILL.md` |
| Create a new class | `.dev/skills/new-class/SKILL.md` |
| After significant refactoring | `.dev/skills/architecture-drift-check/SKILL.md` |
| End of session | `.dev/skills/update-backlog/SKILL.md` |
| End of session (if API or architecture changed) | `.dev/skills/update-readme/SKILL.md` |

---

## Repository structure

```
src/
  main/java/fr/dufrenoy/hvt/
    api/          — public-facing HVT API (HvtMemory, TransferMode, AcceleratorType, etc.)
    kernel/       — kernel compilation to SPIR-V via Beehive
    runtime/      — OpenCL dispatch via Panama, HvtCarrierRegistry
    error/        — HvtErrorBuffer, HvtKernelException
  test/java/fr/dufrenoy/hvt/
docs/
  hvt-paper-v3.md   — the research paper this prototype supports
.dev/
  CLAUDE.md
  WORKFLOW.md
  design/
    ARCHITECTURE.md
  standards/
    JAVA_STANDARDS.md
  backlog/
    BACKLOG.md
  docs/
    hvt-paper-v3.md
  skills/
    static-analysis/SKILL.md
    new-class/SKILL.md
    architecture-drift-check/SKILL.md
    update-backlog/SKILL.md
    update-readme/SKILL.md
```

---

## Absolute rules

- Never modify `pom.xml` without explicit user instruction
- Never add dependencies not listed in `ARCHITECTURE.md` without explicit user instruction
- Never implement features beyond the POC scope defined in `ARCHITECTURE.md`
- All code, comments, Javadoc, exception messages, and variable names must be in English
- Run `static-analysis` after every class generation or significant modification
- Update `BACKLOG.md` at the end of every session without exception
