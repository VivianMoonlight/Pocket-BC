---
name: compose-ui-review
description: Review Jetpack Compose UI changes for state ownership, recomposition behavior, accessibility, adaptive layout, insets, theming consistency, and visual regressions. Use for Compose screen/component reviews and UI refactors.
---

# Compose UI review

Review:
- state hoisting and source of truth
- stable keys and collection rendering
- effect/lifecycle correctness
- avoidable recomposition or work inside composition
- modifier order when behavior depends on it
- accessibility semantics and touch targets
- edge-to-edge/inset handling
- adaptive behavior across relevant window sizes
- theme/style/token consistency
- loading, empty, error, and disabled states
- navigation/back behavior where relevant

Prefer evidence from rendered previews, screenshots, UI tests, or device runs over static confidence alone.
Report concrete issues before suggesting broad redesigns.
