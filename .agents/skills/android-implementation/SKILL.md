---
name: android-implementation
description: Implement Android/Kotlin/Jetpack Compose features in this repository while preserving its established architecture and minimizing unnecessary abstractions. Use for feature work, bug fixes, and focused refactors.
---

# Android implementation

Before editing:
- read the nearest relevant AGENTS.md
- inspect adjacent implementation patterns
- identify state and lifecycle ownership
- keep the change scoped to the requested behavior

Prefer:
- Kotlin and the project's existing Compose patterns
- immutable UI state where practical
- explicit ownership and unidirectional state flow
- small Android/platform adapters
- existing project abstractions when they are useful

Avoid:
- global mutable state
- duplicate state holders
- pass-through facades
- new interfaces with only one speculative implementation
- broad module churn for a local feature
- dependency/version upgrades unrelated to the task

After implementation, run the smallest relevant tests plus the appropriate build task. For UI/navigation/lifecycle changes, invoke the device-validation workflow when a device or emulator is available.
