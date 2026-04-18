---
name: update-readme
description: >
  Update README.md in hvt-prototype after a structural change or when a
  benchmark result is obtained. Trigger when a new component is implemented,
  a benchmark result is available, or the user says "update the README".
---

# Update README — hvt-prototype

## When to run

- A new component of the HVT stack is implemented
- A benchmark result (GPU vs CPU speedup) is obtained
- The overall status of the prototype changes

Do **not** update the README for internal refactoring or test-only changes.

---

## Before writing anything

Read the current `README.md` in full to understand the existing structure
before making any modifications. The README is written in English, in a
clear and concise technical style.

---

## README structure

```
# hvt-prototype
## What this is
## Stack
## Results
## Requirements
## Building and running
## Relationship to the paper
## License
```

---

## Style rules

- All text in English
- Backticks for all type names, method names, and code references inline
- Fenced ```java blocks for all code examples
- Tables use the compact `|---|---|` separator style
- No trailing whitespace

---

## Before presenting the result

Show the user a summary of what was changed and flag anything uncertain
for their decision.
