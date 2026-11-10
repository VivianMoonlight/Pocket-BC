<!-- BEGIN CODEX-ANDROID-MOBILE-BC -->
## Android / mobile-bc working rules

- This repository is `D:\workspace\mobile-bc`. Do not infer another repo root from attachment or workspace paths.
- Preserve the existing Android build toolchain unless a task explicitly requires an upgrade.
- Use the repository Gradle wrapper; do not use `--no-daemon`.
- Do not touch Git/GitHub authentication or global Git config.
- Standing user authorization: after each completed update, promptly manage the Git branch, commit the scoped changes after relevant validation, and merge into the established integration branch when the target is clear and the change is ready. Do not ask again for routine branch/commit/merge operations. Use `codex/` for new branches unless the user specifies otherwise; reuse a suitable existing task branch. If the merge target is unclear, clarify it before merging. Do not include unrelated working-tree changes in commits. Push only when explicitly requested.
- Keep architecture changes evidence-based and scoped; avoid speculative abstraction layers and duplicate facades.
- For implementation work, run relevant tests and a build before reporting completion.
- Default validation for ordinary code changes: `.\gradlew.bat testDebugUnitTest assembleDebug` when those tasks are applicable.
- For UI, navigation, lifecycle, or interaction changes, use device/emulator validation when available and report any portion that could not be exercised.
- Prefer the existing Android SDK at `D:\Android\Sdk`; do not install a second SDK just because a tool is missing from PATH.
- Treat Android CLI as optional agent tooling, not as a prerequisite for Gradle/APK builds.
- Keep official Android skills selective. Compose daily baseline: testing-setup, edge-to-edge, adaptive, styles; use security/release/performance skills only when relevant.
- During architecture audits, report evidence and recommendations first; do not modify code unless implementation was requested.
<!-- END CODEX-ANDROID-MOBILE-BC -->
