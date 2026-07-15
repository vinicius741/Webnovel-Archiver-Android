# Repository Instructions

## Scope

- Active development targets the native Kotlin app under `android/`.
- Follow any more-specific `AGENTS.md` found below this directory. Instructions closest to the files being changed take precedence.
- Product architecture, package map, build/validation commands, and emulator workflows live in `android/AGENTS.md`.
- Repo agent skills live under `.agents/skills/` (`build-and-install-apk`, `dev-launch-screen`, `emulator-qa`). Prefer those skills over re-deriving build/install/QA steps.

## Git Workflow

- Work in the current branch, normally `main`.
- Do not create or switch branches, commit, push, or open a pull request unless the user explicitly requests that action in the current message.
- When committing, stage only task-related changes. Preserve unrelated worktree changes.

## Device Safety

- Never target, inspect, install to, uninstall from, launch on, or otherwise operate the owner's physical phone unless the user explicitly requests phone use in the current message. Previous permission does not carry forward.
- Use the `webnovel_api36` Android emulator by default for builds, installs, launches, and QA.
- Use unqualified `adb devices -l` only to discover targets. For every device-targeting ADB operation, resolve a healthy serial whose name starts with `emulator-` and pass it with `adb -s "$EMULATOR_SERIAL" ...`.
- Never run an unqualified device-targeting `adb` command. If no emulator is available, or multiple emulators are running and no serial was explicitly selected, stop instead of falling back to another device.

### Build variant per target

The native app ships as distinct application IDs — each is a separate app with its own isolated data sandbox. Always use the variant that matches the target so you don't install the empty sibling over the user's working app:

- **Simulator / emulator:** always the **debug** variant — `com.vinicius741.webnovelarchiver.nativeapp.debug` (APK: `android/app/build/outputs/apk/debug/app-debug.apk`). This is where the user's in-progress library and test data live; the release variant on the emulator starts empty.
- **Owner's phone:** always the **release** variant — `com.vinicius741.webnovelarchiver.nativeapp` (APK: `android/app/build/outputs/apk/release/app-release.apk`), and only when phone use is explicitly authorized for the current message.
- **Instrumentation tests:** use the **instrumentation** variant — `com.vinicius741.webnovelarchiver.nativeapp.instrumentation` — so connected tests do not read, clear, or overwrite the debug library sandbox.

## Documentation

- Long-form documentation lives under `docs/`, grouped by subject — one folder per topic (e.g. `docs/cloudflare/`, `docs/tts/`, `docs/architecture/`).
- When adding a document, place it in the folder for its subject. Create a new subject folder only if no existing folder covers the topic; otherwise reuse the existing one.
- Keep `docs/README.md` current: it is the index of subjects and the documents each folder contains. Update it in the same change whenever documents are added, moved, or removed.
- Keep the root `README.md` product overview and package map aligned with user-facing features and the modular layout under `android/app/src/main/java/`. Update it in the same change when those shift.

## Repository Boundaries

- Do not expose signing credentials, keystores, `local.properties`, or other secrets.
- Do not overwrite or remove unrelated user changes.
- Keep instructions current: update the applicable `AGENTS.md` in the same change when build commands, architecture, validation requirements, or device workflows change.
