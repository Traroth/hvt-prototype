---
name: update-backlog
description: >
  Update BACKLOG.md at the end of a working session in hvt-prototype.
  Trigger this skill when the user says "update the backlog", "end of session",
  "wrap up", or "what did we do". Also run it automatically after completing
  any backlog task, even if the user doesn't ask — it should never be skipped.
---

# Update Backlog — hvt-prototype

## When to run

- At the end of every working session
- After completing, partially completing, or abandoning a backlog task
- After discovering a new issue or design decision worth tracking

---

## Procedure

### 1. Identify changes from the session

Review the conversation and extract:

- Tasks **fully completed** → move to `## Done`, use `[x]`
- Tasks **partially completed** → keep with `[ ]`, add note `(in progress — [what remains])`
- Tasks **abandoned or on hold** → keep with `[ ]`, add `— on hold` with short reason
- **New tasks discovered** → add under the correct component with `[ ]`
- **New design decisions** → these belong in `ARCHITECTURE.md`, not the backlog;
  flag them separately for the user

### 2. Determine the correct section

Each task belongs to one of the component sections defined in `BACKLOG.md`:

- `## Project-wide`
- `## HVT API`
- `## Kernel`
- `## Runtime`
- `## Error model`
- `## Integration tests`

### 3. Write the update

Follow the existing format:

```markdown
- [ ] Task description — optional note
- [x] Completed task description
```

- One task per line
- Short, imperative phrasing ("Implement", "Fix", "Write", "Validate")
- Dependency or constraint appended after ` — `

### 4. Present the diff to the user

Before writing, show:
- What is being marked as done
- What is being added
- What is being modified

Ask for confirmation if anything is ambiguous.

---

## Format reminder

Preserve all existing content. Only add, tick, or annotate — never delete.
