---
name: device-validation
description: Validate Android UI, navigation, lifecycle, and interaction changes on an attached emulator/device using the existing SDK/ADB. Use after behavior-visible Android changes; do not require a device for pure setup or non-UI edits.
---

# Device validation

Do not declare a behavior-visible Android change complete from static inspection alone when an emulator/device is available.

Use the existing SDK, preferring:
`D:\Android\Sdk\platform-tools\adb.exe`
when adb is not on PATH.

Workflow:
1. Run relevant unit tests.
2. Build the debug APK with the repository Gradle wrapper.
3. Check `adb devices`.
4. If a usable device/emulator exists, install/update the debug APK when appropriate.
5. Launch the target flow.
6. Exercise the changed interaction.
7. Inspect relevant Logcat output for crashes/exceptions.
8. Capture a screenshot when visual behavior matters.
9. Inspect UI hierarchy only when useful.
10. Report what was actually executed and what remained unverified.

Do not create/start an emulator, wipe device data, uninstall unrelated apps, or alter developer settings unless explicitly requested.
