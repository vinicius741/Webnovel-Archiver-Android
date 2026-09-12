# Continue the Patreon acquisition proof

> **Status 2026-09-11 (later): superseded.** The probe was compiled, installed on `webnovel_api36`, and the pre-login checks ran. Results, OAuth documentation findings, and the remaining user steps live in `patreon-acquisition-proof-2026-09-11.md`. This document is preserved for history only.

## Prompt for the next agent

Continue this investigation, not the full product implementation. Read `docs/sources/patreon-integration-plan.md` and the repository instructions first. The user specifically wants actual emulator evidence to resolve whether fully in-app Patreon acquisition is possible, instead of leaving it unproven.

The user requires Android-only operation. They prefer the app plus ordinary browser login, but accept a Firefox Android extension if necessary. Their target is The Lich King Reincarnates as a Baby, Patreon collection 2030713 and Royal Road fiction 154956. Eventually they want one combined novel and to prefer public editions after reliable matching, preserving existing reading progress and downloaded copies.

Do a bounded native acquisition proof first. Distinguish browser login, OAuth authorization, and permission to read another creator's full paid post body. Do not claim a member identity response proves post access. Do not claim a developer-client-secret prototype is a secure production native authorization design.

## Exact stopping point, 2026-09-11

- Detailed research is complete in `patreon-integration-plan.md`. The user then asked us to test the uncertainty in the emulator.
- `webnovel_api36` was started in tmux session `webnovel-emulator`. Its serial was `emulator-5554`, verified by `adb -s emulator-5554 emu avd name`; boot completion was `1`. Rediscover and verify before using it.
- No phone was targeted. No library data was changed. No probe APK has been built or installed yet.
- A standalone Java diagnostic app was drafted under `/tmp/wna-patreon-probe` and copied into `scripts/patreon_spike/android/` for continuation. It is separate from the novel app, with application ID `com.example.patreonprobe.debug`, and no external libraries.
- The first build attempt failed before compilation because the sandbox could not write the Gradle wrapper lock under `/Users/ilia/.gradle`. This is a tooling permission failure, not evidence of a Java/compiler or Patreon problem. Request normal tool escalation for Gradle cache/build access. Source has not been compiled or validated.
- The temporary app opens the target post in the ordinary browser, tests native HTTP with its own Android CookieManager, supports a manually entered test client ID/secret, handles a state-checked custom-scheme OAuth callback, exchanges a code, and probes identity plus the target post API.
- Its report is app-private `files/report.txt` and contains status codes, markers, and lengths, never tokens or bodies. Credentials live only in memory. `FLAG_SECURE` prevents screenshots of the probe screen. Read the redacted report via qualified emulator ADB.
- The API client redirect proposed in the draft is `webnovelpatreonprobe://oauth/callback`. Patreon acceptance of that URI is UNTESTED. If unsupported, design and test a legitimate local development callback. Do not substitute an arbitrary third-party callback service.
- The draft HTTP function deliberately does not follow redirects, and its post-body marker is only a diagnostic signal. Neither a redirect nor absent marker proves authenticated content is impossible. Review and improve the probe before drawing conclusions.

## User account setup in progress

The internal browser is signed into Patreon. We opened:

`https://www.patreon.com/portal/registration/register-clients`

It said: “Please become a creator to register OAuth clients. You do not need to launch your creator page, but your OAuth clients’ permissions are dependent upon your campaign.”

The user answered: **“I can enable the unpublished profile for the API test.”** We asked them to follow “become a creator,” keep it unpublished, and return to Clients & API Keys. There has been no confirmation that they finished. Check with the user/current page. Do not publish a creator page, purchase anything, or accept unrelated agreements. Have the user perform required credential/account setup and keep secrets out of chat and tool outputs.

The internal browser tab IDs at handoff were 1 for the collection and 3 for API setup, browser ID 1. Rediscover browser state; do not assume those handles survive. The desktop login is NOT an Android login. The user will need to sign in on the emulator for a meaningful device test.

## Next steps

1. Read the preserved probe source. Compile the standalone debug APK, fixing any compilation issues. The original command was `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools android/gradlew -p /tmp/wna-patreon-probe :app:assembleDebug --console=plain`. Prefer the preserved project path `scripts/patreon_spike/android` now. The Gradle wrapper is in the main repo's `android/` directory.
2. Install only the standalone probe's raw debug APK onto the verified emulator. Keep the user's main debug app and its library untouched. Follow the run-android-emulator skill; the separate probe has its own activity and does not use the main app's dev_start_screen routing.
3. Finish API client setup with the user, using the minimum test scopes. The draft requests `identity identity.memberships campaigns.posts`; review whether memberships is needed. Obtain explicit action-time authorization for new security-sensitive grants. Never persist or print the test secret in the repository, shell history, or chat. User entry on the probe UI is the intended path.
4. Run before/after browser-login native checks. The target is `https://www.patreon.com/TheBooksofORAR/posts/lich-king-as-4-5-169054023`. Verify the browser actually displays the complete readable chapter, not a preview. The previous desktop observation was roughly 12,126 body text characters and 82 paragraphs; these are reference observations, not fixed thresholds.
5. Use the member OAuth grant to call `GET /api/oauth2/v2/identity`, then `GET /api/oauth2/v2/posts/169054023?fields%5Bpost%5D=title%2Ccontent%2Curl`. Record redacted HTTP/error results and content completeness. A successful token exchange does not establish entitlement. A token granted by the user must not be confused with the generated creator token for their new empty campaign.
6. If full content succeeds, test pagination and secure native authorization requirements, especially actual PKCE/public-client support. The draft's manually entered secret is a temporary diagnostic aid, not something to ship in an APK.
7. If the API returns permission errors for the supported author's paid post while the same user can read it in the browser, document that exact result. Separate it from Cloudflare, transient network, malformed scopes, and client setup errors. Do not generalize one failed request into proof no Android integration can exist.
8. If native access cannot satisfy the requirements, proceed to the Android extension proof described in the main plan only as needed. There is no desktop-companion authorization or requirement.
9. Save a concrete results document with tested versions, commands, pass/fail evidence, limitations, and a clear verdict for each route. Update the main plan and docs index. Do not implement the full Patreon feature during this investigation.

## Constraints

Work in the current branch. Do not commit/push/create branches or PRs unless newly requested. No physical phone authorization exists. Preserve existing documentation edits and all unrelated changes. Keep Google OAuth and other third-party credential flows out of WebView under the current repository architecture. Never bypass locked content or transfer browser session cookies from the desktop to the emulator.

The previous agent's only attempted build did not reach compilation. No successful device authentication, native fetch, API authorization, or chapter download has been demonstrated yet. Report these honestly and finish the tests rather than repeating the earlier conditional conclusion.
