---
name: new-class
description: >
  Scaffold a new class in hvt-prototype. Use this skill whenever the user
  asks to create, implement, or scaffold a new class — even if they just say
  "let's implement X" or "I want to start working on Y".
---

# New Class — hvt-prototype

## Before writing any code

Read the following files in full:

1. `.dev/WORKFLOW.md` — the required order of operations; follow it strictly
2. `.dev/standards/JAVA_STANDARDS.md` — coding standards
3. `.dev/design/ARCHITECTURE.md` — design decisions and package structure
4. `.dev/backlog/BACKLOG.md` — check if this class is already listed

---

## Procedure

### 1. Clarify the design (before coding)

Ask the user — or infer from context — the following:

- What is the role of this class in the HVT stack?
- Which package does it belong to (`api`, `kernel`, `runtime`, `error`)?
- What are its inputs and outputs?
- Does it interact with Panama, Beehive, or OpenCL?
- What are the failure modes and how should they be handled?

Do not proceed to code until the design is clear.

### 2. Generate the source file

Location: `src/main/java/fr/dufrenoy/hvt/<package>/ClassName.java`

Required structure (in order):

1. **File header** — C-style comment with class name, version (match `pom.xml`),
   and MIT license notice
2. **`package` statement**
3. **`import` statements** — explicit, sorted alphabetically, no wildcards
4. **Class Javadoc** — describe the role in the HVT stack, inputs/outputs,
   and any non-obvious contracts
5. **Class declaration**
6. **Body** — statics, instance fields, constructors, methods grouped by
   functionality with `// ─── Section ───` separators

Panama naming conventions (from `JAVA_STANDARDS.md`):
- `MemorySegment` variables → suffix `Seg`
- OpenCL handles → prefix `cl` (e.g. `clDevice`, `clContext`)
- Arena variables → `arena`

### 3. Write integration tests

Location: `src/test/java/fr/dufrenoy/hvt/ClassNameIntegrationTest.java`

Focus on:
- Correct output when the full stack is exercised
- Error propagation via `HvtErrorBuffer`
- Resource cleanup (no Arena leaks)

### 4. Run static analysis

Apply the `static-analysis` skill to the produced code. Report all findings
before proceeding.

### 5. Update ARCHITECTURE.md

Add a section describing:
- The role of the class
- Key implementation choices
- Alternatives considered and rejected

### 6. Update BACKLOG.md

Mark the class as in progress or completed. Add any known limitations
or follow-up tasks discovered during implementation.

---

## Checklist before handing off

- [ ] File header present and version matches `pom.xml`
- [ ] All imports explicit and sorted
- [ ] All public methods have Javadoc with `@param`, `@return`, `@throws`
- [ ] Panama naming conventions followed
- [ ] Arena closed in try-with-resources or finally block
- [ ] OpenCL error codes checked after every API call
- [ ] Static analysis run and findings reported
- [ ] `ARCHITECTURE.md` updated
- [ ] `BACKLOG.md` updated
