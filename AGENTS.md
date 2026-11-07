# Repository Instructions

## Scope

- Active development targets the native Kotlin app under `android/`.
- Follow any more-specific `AGENTS.md` found below this directory. Instructions closest to the files being changed take precedence.
- Product architecture, package map, build/validation commands, and emulator workflows live in `android/AGENTS.md`.
- Repo agent skills live under `.agents/skills/` (`build-and-install-apk`, `dev-launch-screen`, `emulator-qa`, `emulator-recovery`, `multi-model-review`, `perf-suite`, `restore-library-backup`). Prefer those skills over re-deriving build/install/QA/review steps.

## Prompt Input via Text-to-Speech

- The user frequently dictates prompts through a text-to-speech model, so messages may contain transcription artifacts — misrecognized words, odd casing, or extra letters (e.g. "AI" transcribed as "AIA").
- Treat such glitches as transcription noise, not intentional. When a word, identifier, or name in a prompt doesn't quite parse, infer the most likely intended term from context before acting or asking.

## Git Workflow

- Work in the current branch, normally `main`.
- Do not create or switch branches, commit, push, or open a pull request unless the user explicitly requests that action in the current message.
- When committing, stage only task-related changes. Preserve unrelated worktree changes.

## Device Safety

- Never target, inspect, install to, uninstall from, launch on, or otherwise operate the owner's physical phone unless the user explicitly requests phone use in the current message. Previous permission does not carry forward.
- Use the `webnovel_api36` Android emulator by default for builds, installs, launches, and QA.
- Use unqualified `adb devices -l` only to discover targets. For every device-targeting ADB operation, resolve a healthy serial whose name starts with `emulator-` and pass it with `adb -s "$EMULATOR_SERIAL" ...`.
- Never run an unqualified device-targeting `adb` command. If no emulator is available, or multiple emulators are running and no serial was explicitly selected, stop instead of falling back to another device.

### T3 Device panel sessions: always release the emulator

> **Mandatory.** Every thread shares one emulator. If a thread opens it with `device_open` and never releases it, every other thread gets `DEVICE_IN_USE` until the user steps in. This blocked QA on 2026-11-06 and again on 2026-11-07.

- A `device_open` session holds an exclusive claim on the emulator. Ending the turn, settling the thread, or killing the emulator does not release it. It stays until that thread calls `device_close` or the idle timeout below expires.
- Release it before your final reply in every turn that called `device_open`, whether the work succeeded, failed, or was aborted. Keep it only if the user asked to keep watching the device.
- To release it:
  1. Call `device_close` with the **exact `device.id` and `hostId` that `device_open` returned**, without `shutdown`. Once the emulator is booted, that id is the serial `emulator-5554`. `webnovel_api36` is the id only while the emulator is stopped.
  2. Call `device_list` and check that `open` is empty. `device_close` returns `{}` even when the id matched nothing and nothing was released. On 2026-11-07 a QA thread opened `emulator-5554`, closed `webnovel_api36`, got `{}`, and left the claim held. If `open` still lists a device, close it using the id shown there.
  3. Only after that, stop the emulator if needed (`adb -s emulator-5554 emu kill`).
- Never silence `agent-device` output (`>/dev/null 2>&1` and similar), especially for `open`. If `open` returns `DEVICE_IN_USE`, stop driving the device. Your other commands still reach the emulator even though `open` failed, so two threads would end up driving the same device.
- On `DEVICE_IN_USE`, report the holding session named in the error. Don't close or reuse another thread's session. The user can free it from the owning thread's Device panel (`…` menu → Power off device; closing the panel alone does not free it). Then restart the emulator with `scripts/emulator/start_emulator.sh`. Upstream: pingdotgg/t3code#17470 and #31303.
- Idle-timeout backstop: `~/.t3/service.env` sets `AGENT_DEVICE_SESSION_IDLE_TIMEOUT_MS=3600000`, which the T3 service passes to the agent-device daemon. agent-device then drops an emulator claim after 1 hour with no device commands. A dropped session's next command fails with `SESSION_NOT_FOUND` (`SESSION_IDLE_EXPIRED`); call `device_open` again. This is a safety net, not a substitute for `device_close`. A change to the value takes effect only after `systemctl --user restart t3code.service`, which also stops running threads.

### Build variant per target

The native app ships as distinct application IDs — each is a separate app with its own isolated data sandbox. Always use the variant that matches the target so you don't install the empty sibling over the user's working app:

- **Simulator / emulator:** always the **debug** variant — `com.vinicius741.webnovelarchiver.nativeapp.debug` (APK: `android/app/build/outputs/apk/debug/app-debug.apk`). This is where the user's in-progress library and test data live; the release variant on the emulator starts empty.
- **Owner's phone:** always the **release** variant — `com.vinicius741.webnovelarchiver.nativeapp` (APK: `android/app/build/outputs/apk/release/app-release.apk`), and only when phone use is explicitly authorized for the current message.
- **Instrumentation tests:** use the **instrumentation** variant — `com.vinicius741.webnovelarchiver.nativeapp.instrumentation` — so connected tests do not read, clear, or overwrite the debug library sandbox.

## Build and Validation

- The Gradle wrapper lives under `android/` (no root wrapper). Run builds from the repo root as `android/gradlew -p android :app:...`.
- Install APKs from the raw Gradle outputs under `android/app/build/outputs/apk/`, not the repo-root `apk-output/` mirrors (convenience copies from post-build tasks, potentially stale).
- `.github/workflows/android-release.yml` is a manually triggered release workflow for `main`. It runs the quality gate, signs using Actions secrets, and publishes the APK to GitHub Releases. See `README.md` for signing setup. Local validation remains required; see `android/AGENTS.md` for per-change command selection and the full gate (`:app:lintKotlin :app:ci`).

## Documentation

- Long-form documentation lives under `docs/`, grouped by subject — one folder per topic (e.g. `docs/cloudflare/`, `docs/tts/`, `docs/architecture/`).
- When adding a document, place it in the folder for its subject. Create a new subject folder only if no existing folder covers the topic; otherwise reuse the existing one.
- Keep `docs/README.md` current: it is the index of subjects and the documents each folder contains. Update it in the same change whenever documents are added, moved, or removed.
- Keep the root `README.md` product overview and package map aligned with user-facing features and the modular layout under `android/app/src/main/java/`. Update it in the same change when those shift.

## Repository Boundaries

- Do not expose signing credentials, keystores, `local.properties`, or other secrets.
- Do not overwrite or remove unrelated user changes.
- Keep instructions current: update the applicable `AGENTS.md` in the same change when build commands, architecture, validation requirements, or device workflows change.
