# Java Standards — hvt-prototype

Coding standards and practices to follow for every Java class generated or
modified in this project.

---

## 1. Oracle guidelines compliance

### Source file structure

Elements in a Java source file must appear in the following order:

1. Beginning file comment (see below)
2. `package` statement
3. `import` statements (explicit, see below)
4. Class or interface declaration, with Javadoc

### Beginning file comment

Every source file must begin with a C-style comment containing the class
name, version, and copyright notice (MIT license).

Example:
```
/*
 * ClassName.java
 *
 * Version 0.1.0-SNAPSHOT
 *
 * hvt-prototype — Proof of concept for Heterogeneous Virtual Threads
 * Copyright (C) 2026  Dufrenoy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
```

### Imports

- Wildcard imports (`import java.util.*`) are forbidden
- Each imported class must have its own `import` statement
- Imports must be sorted alphabetically within each group
- Fully qualified references in code must be replaced by explicit imports
- Exception: generated Panama/jextract bindings in
  `fr.dufrenoy.hvt.runtime.opencl` — do not modify their imports

### Declaration order within a class

1. Static variables (`public`, then `protected`, then package, then `private`)
2. Instance variables (`public`, then `protected`, then package, then `private`)
3. Constructors
4. Methods (grouped by functionality, not by visibility)

### Section separators

Decorative separators are encouraged for readability:

```java
// ─── Section name ─────────────────────────────────────────────────────────────
```

---

## 2. Javadoc

- All public classes must have a class-level Javadoc comment
- All public methods must have a Javadoc comment
- `@Override` methods may omit Javadoc if behaviour is identical to the
  parent contract
- Parameters (`@param`), return values (`@return`), and exceptions
  (`@throws`) must be documented

---

## 3. Null handling

Prefer `Optional<T>` over `null` as a return type, except:

- **Interface contracts** — never change the return type imposed by an
  interface
- **Method parameters** — never use `Optional` as a parameter type
- **Instance fields** — use `null` for optional fields
- **Collections** — return an empty collection, never `Optional<Collection>`

---

## 4. Tests

Integration tests are preferred over unit tests. See `WORKFLOW.md` §5 for
the testing strategy.

Test class naming:
- `ClassNameIntegrationTest` for end-to-end stack tests
- `ClassNameTest` for isolated unit tests where applicable

---

## 5. Versioning

The version in each source file header must match the project version in
`pom.xml`. Versioning follows Semantic Versioning (semver):

- **Patch (x.y.Z)** — bug fix, internal refactoring with no behavioural change
- **Minor (x.Y.0)** — new feature, new public method, backward-compatible change
- **Major (X.0.0)** — breaking API change

---

## 6. Static analysis

After every class generation or significant modification, run the
`static-analysis` skill. Claude reports issues classified by severity:

- **Critical** — bugs, incorrect kernel results, Panama memory errors
- **Major** — performance issues, missing null checks, unclear semantics
- **Minor** — style issues, Javadoc gaps, guideline violations

All Critical issues must be resolved before continuing. All code,
comments, Javadoc, exception messages, and variable names must be in
English.

---

## 7. Naming conventions

### Exception parameters in catch blocks

Exception parameters must be named using the initials of the exception
type in lowercase:

| Exception type | Parameter name |
|---|---|
| `IOException` | `ioe` |
| `NullPointerException` | `npe` |
| `IllegalArgumentException` | `iae` |
| `IllegalStateException` | `ise` |
| `InterruptedException` | `ie` |

For generic or unknown exception types, `e` is acceptable.

### Panama / OpenCL specifics

- Variables holding `MemorySegment` instances should be suffixed `Seg`
  (e.g. `inputSeg`, `outputSeg`, `errorSeg`)
- Variables holding OpenCL handles (device, context, queue, program,
  kernel) should be named after the OpenCL object type in camelCase
  (e.g. `clDevice`, `clContext`, `clCommandQueue`, `clProgram`, `clKernel`)
- Arena variables should be named `arena`
