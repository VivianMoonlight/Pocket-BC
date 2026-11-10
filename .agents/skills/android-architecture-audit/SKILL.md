---
name: android-architecture-audit
description: Audit this Android repository's architecture, ownership boundaries, dependency direction, state flow, facades, lifecycle, test seams, and extension points. Use for architectural reviews and before large refactors. Do not modify code unless the user explicitly requests implementation.
---

# Android architecture audit

Inspect concrete code before proposing changes.

Check:
- dependency direction and module ownership
- UI / domain / data / platform boundaries
- state ownership and duplicate state holders
- service/session lifetime and lifecycle ownership
- pass-through facades and duplicate abstraction layers
- navigation ownership
- platform API isolation
- public API surface
- test seams and dependency injection points
- extension/plugin boundaries

For each finding report:
1. Evidence: exact files/symbols.
2. Consequence: why it matters in this repository.
3. Severity: low / medium / high.
4. Minimal correction.
5. Larger architectural correction only if justified.
6. Migration/regression risk.

Prefer removing accidental complexity over adding speculative abstractions.
Do not change files during an audit unless explicitly asked.
