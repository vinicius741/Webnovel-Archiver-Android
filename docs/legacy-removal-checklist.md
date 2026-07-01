# Legacy React Native Removal — Checklist

Generated 2026-06-30. Final cleanup pass to fully remove the legacy React Native / Expo code from the repository after the native Kotlin rewrite under `android/`.

## Context

- The native **Gradle build is already fully decoupled** from the legacy tree: `android/settings.gradle` includes only `:app`, repos are `google()`/`mavenCentral()`, no `node_modules`/Expo maven, no JS bundle / Hermes bytecode / fonts / copied assets under `android/app/src`. The scripts (`scripts/redeploy.sh`, `scripts/watch-redeploy.sh`) are pure-native.
- Remaining coupling is at the **repo-root tooling/docs layer** only, plus leftover RN references *inside* `android/`.
- Decisions taken:
  - **Docs:** delete `docs/` entirely (keep README + AGENTS.md as source of truth). *Exception:* this checklist file.
  - **Kotlin comments / test-method names** mentioning "React Native" → scrub (cosmetic, no functional effect).

## Step 1 — Scrub RN references *inside* `android/` (tracked, no build impact)

- [x] `android/gradle.properties` — delete lines 28–63 (keep 13–26). Removes `reactNativeArchitectures`, `newArchEnabled`, `hermesEnabled`, `edgeToEdgeEnabled`, `expo.*`, `EX_DEV_CLIENT_NETWORK_INSPECTOR`, and the `extraMavenRepos` pointer into `node_modules`. *(Done in 8f06a34.)*
- [x] `android/app/proguard-rules.pro` — delete lines 10–12 (reanimated + `com.facebook.react.turbomodule` keep rules; `minifyEnabled` is `false` anyway). *(Done in 8f06a34.)*
- [x] `android/app/src/main/res/values/strings.xml:3` — delete `expo_splash_screen_resize_mode` (no `R.string` usage). *(Done in 8f06a34.)*
- [x] `android/app/src/main/res/values/styles.xml:8–10` — delete dead `Theme.App.SplashScreen` (manifest uses `AppTheme`). *(Done in 8f06a34.)*
- [x] `android/app/src/main/res/values/styles.xml:3` + `drawable/rn_edit_text_material.xml` — remove the `android:editTextBackground` item **and** delete the drawable together (AppCompat 1.7.1 ships its own). *(Done in 8f06a34.)*
- [x] Kotlin comments + test-method names mentioning "React Native" — scrub. *(8f06a34 scrubbed most; final hit in `DetailsScreen.kt:532` scrubbed in this pass.)*

## Step 2 — Update repo-root tooling/docs (must precede deletion)

- [x] `quality-baseline.json` — remove `"app"`, `"src"` from `sourceDirs` (else the quality gate scans missing dirs). *(Deviation — file deleted entirely, see Step 3.)*
- [x] `README.md` — rewrite the "originally developed with React Native… remains for reference" note to native-only.
- [x] `AGENTS.md` (root, "Scope") — drop the "legacy reference-only" carve-out for `app/`/`src/`/`modules/`; check for a more-specific `android/AGENTS.md`. *(android/AGENTS.md is clean — no legacy refs.)*
- [x] `.gitignore` — prune orphaned rules: `node_modules/`, `.expo/`, `expo-env.d.ts`, `.metro-health-check*`, `modules/tts-media-session/...` (optionally `coverage/`, `quality-report/`). *(Rewrote the whole file; kept coverage/ and quality-report/.)*

## Step 3 — Delete the legacy footprint

- [x] Legacy source: `git rm -r app src modules plugins patches assets`
- [x] Root RN/Expo config: `git rm app.json eas.json metro.config.js react-native.config.js package.json package-lock.json tsconfig.json jest.config.js jest-setup.ts eslint.config.cjs`
- [x] Docs: `git rm -r docs` — leaving README + AGENTS.md as the source of truth. *(This checklist file restored per the Context exception.)*
- [x] (Untracked, no `git rm` needed): `node_modules/`, `.expo/`, `coverage/`, `quality-report/` — delete on disk.
- [x] **Additions beyond the original list** (per user decision): `git rm -r scripts/code-quality quality-baseline.json`. The TS quality tooling was legacy Node code that generated `quality-baseline.json` via `npm run quality:*`; the gradle `:app:ci` task is the native quality gate, so both were removed with `package.json`. Stray on-disk-only dirs (`src/`, `modules/` build junk) deleted too.

## Step 4 — Verify nothing broke

- [x] `android/gradlew -p android :app:assembleDebug` — **BUILD SUCCESSFUL**.
- [x] `android/gradlew -p android ci` (`testDebugUnitTest` + `detekt` + `lintDebug`) — **BUILD SUCCESSFUL** (detekt warnings are pre-existing, non-blocking).
- [x] Smoke run on `webnovel_api36` via `scripts/redeploy.sh` — deployed to `emulator-5554`; `MainActivity` confirmed as topResumedActivity.

## Notes / gotchas

- **Pre-existing worktree changes** must stay out of the cleanup commit: `AppStorage.kt`, `DetailsScreen.kt`, `LibraryFilters.kt`, `LibraryScreen.kt`, `LibraryQueryTest.kt`, plus untracked `LegacyEpubsScreen.kt`. Stage only legacy-removal changes. *(No longer applicable at execution time — the worktree was clean; the only non-deletion change made in this pass was the `DetailsScreen.kt` comment scrub, which is itself a legacy-removal change.)*
- **Deleting root `package.json`/`package-lock.json`** severs the Node toolchain dependency — confirm nothing else (IDE, pre-commit hook, agent skill) shells out to `npm`/`yarn` first:
  `grep -rn "npm \|yarn " scripts/ .agents/ .zcode/` *(Ran: only `scripts/code-quality/` referenced `npm run quality:*`, and it was deleted alongside `package.json`. `.claude/settings.local.json` has a stale `npm test:*` permission entry, but it is not a build dependency.)*
- **`git rm -r docs`** also removes the dated Kotlin review report — intended, since docs are being deleted entirely. Confirm nothing (CI, README) links to those HTML files. *(Confirmed: no links in README, AGENTS.md, android/AGENTS.md, build.gradle, or the redeploy scripts.)*
