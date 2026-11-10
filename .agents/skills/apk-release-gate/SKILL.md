---
name: apk-release-gate
description: Run a conservative Android pre-release quality gate covering tests, build, lint/security/release checks, shrinker concerns, manifest/permission review, and smoke testing. Use only for release readiness, not every routine edit.
---

# APK release gate

First inspect the repository's existing release tasks and CI; do not invent a parallel release process.

Candidate checks, when configured and relevant:
- unit tests
- instrumentation/UI tests
- release/debug build as appropriate
- Android lint
- manifest/exported-component/permission review
- signing configuration sanity without exposing secrets
- R8/shrinker rules and warnings
- APK/AAB size regression when a baseline exists
- startup and critical-flow smoke test on a device/emulator

Never print keystore passwords, tokens, signing secrets, or private credentials.
Do not publish, upload, sign with production credentials, commit, or push unless explicitly requested.
Report skipped checks and why they were skipped.
