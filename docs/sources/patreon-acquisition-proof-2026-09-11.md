# Patreon native acquisition proof — results (2026-09-11)

Status: **in progress — pre-login evidence complete; member-token API test pending user action.** This document records what was actually built, run, and observed on the emulator, what documentation research established, and the exact remaining steps. Companion to `patreon-integration-plan.md` (design) and `patreon-investigation-continuation.md` (superseded handoff).

## 1. Environment and tested versions

| Item | Value |
|---|---|
| Emulator | `webnovel_api36`, serial `emulator-5554` (tmux `webnovel-emulator`), API 36, `sdk_gphone64_arm64`, `sys.boot_completed=1` |
| Probe app | Standalone diagnostic, application ID `com.example.patreonprobe.debug`, versionCode 1, no libraries, `FLAG_SECURE`, credentials in memory only, report at app-private `files/report.txt` (statuses/lengths/markers only — never tokens or bodies) |
| Probe source | `scripts/patreon_spike/android/` (preserved continuation copy) |
| Build | Gradle 9.7.0 (main repo wrapper `android/gradlew`), AGP 9.3.1, JDK 18.0.2, `compileSdk 37`, `minSdk 26`, Java 17 compatibility; first build succeeded in 19s |
| Build command | `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools android/gradlew -p scripts/patreon_spike/android :app:assembleDebug --console=plain` (ran without sandbox escalation; the previous session's Gradle lock failure did not reproduce) |
| Install/launch | `adb -s emulator-5554 install -r .../app-debug.apk`; `am start -n com.example.patreonprobe.debug/com.example.patreonprobe.ProbeActivity` |
| Report readout | `adb -s emulator-5554 exec-out run-as com.example.patreonprobe.debug cat files/report.txt` |

Probe changes made this session (reviewed per the handoff's "review and improve" instruction): redirect `Location` + `Content-Type` capture, expanded HTML indicators (teaser/paywall/bootstrap-JSON/challenge), internal-API baseline call, API-level `isBlank` fix, a **paste-token field** (creator token from the portal drives the same API test without the OAuth redirect), and an identity call that includes `memberships` so a denied post response can be attributed correctly.

## 2. Device evidence captured (pre-login, unauthenticated)

Verbatim from `files/report.txt`, 2026-09-11, final probe build (results identical across two runs):

```text
App WebView cookie present before native request: false
Native post HTTP 200; bytes=944368; type=text/html; charset=utf-8
Native HTML indicators: full-body class=true, teaser=true, paywall=true, challenge=false, bootstrap json=false, target title=true
V2 API without OAuth HTTP 401; type=application/vnd.api+json
V2 API without OAuth: data=false, title chars=0, content chars=0, errors=true
API error code=1; name=Unauthorized
Internal api/posts without session HTTP 200; type=application/vnd.api+json
Internal api/posts without session: data=true, title chars=79, content chars=0, errors=false
```

Interpretation (markers are diagnostic only; Patreon renders client-side):

- **F1 — Native HTTP to the post page works and is not Cloudflare-blocked on this network.** Plain `HttpURLConnection` from the app (no cookies, no browser UA tricks) got HTTP 200, 944 KB of HTML, `challenge=false`, no redirect. The page shell contains both the post-content container class and the teaser/paywall markers — consistent with an unauthenticated view. Access is session-gated, not transport-gated. This is a meaningful contrast with the Royal Road/ScribbleHub Cloudflare behavior elsewhere in this repo.
- **F2 — v2 API without a token returns 401 Unauthorized** (JSON:API error), as expected. The endpoint is reachable natively.
- **F3 — Patreon's internal `GET /api/posts/169054023` returns HTTP 200 to an unauthenticated native client, with post metadata (title, 79 chars) but `content` present-and-empty.** The internal API (the gallery-dl route) is reachable from Android without browser machinery; the body is entitlement-gated server-side. A valid member session cookie is the missing ingredient on that route.
- **F4 — The probe app's cookie store is empty and stays empty**: an ordinary browser login (Custom Tab or separate browser) cannot share its session with native HTTP in the APK. Session isolation re-confirmed on-device.

## 3. Documentation research findings (no live credentials involved)

- **F5 — Patreon does not allow custom URL schemes as OAuth redirect URIs.** Developer-forum reports confirm registration rejects schemes like `app://callback`; only `https://` URIs are accepted ([forum: Patreon auth APIs limitations](https://www.patreondevelopers.com/t/patreon-auth-apis-limitations/7527), [forum: Custom URL scheme for redirect_URI](https://www.patreondevelopers.com/t/custom-url-scheme-for-redirect-uri/332)). The draft probe redirect `webnovelpatreonprobe://oauth/callback` therefore cannot be registered; the authorize-flow button is kept only to observe the rejection.
- **F6 — PKCE is not documented**; the only documented grant is authorization-code with client secret ([Patreon API reference](https://docs.patreon.com/)). Combined with F5, a **fully in-app OAuth authorization flow is impossible today without an https intermediary** the user controls (e.g. a minimal Cloudflare Worker redirect page / App Link — a future architecture decision, not part of this proof).
- **F7 — Registering a v2 client automatically issues a Creator's Access Token with all V2 scopes** ("The client creator's access token will automatically have all V2 scopes associated with it"), usable directly without the authorize flow ([Patreon API reference](https://docs.patreon.com/)). Because the user's account is also a patron of the target creator, this token is the practical device diagnostic for the core question. It does **not** validate a production authorization design.
- **F8 — `GET /api/oauth2/v2/posts/{id}` requires the `campaigns.posts` scope** ("Provides read access to the posts on a campaign"), and the docs are silent on whether a member's token may read another creator's post; one OAuth note ("If your client requires the ability to ask for pledges or campaign data of other users… please contact us") suggests clients may default to own-campaign access. Only the live test can resolve this.

## 4. Verdict per route (current state)

| Route | Verdict | Basis |
|---|---|---|
| Native HTTP with ordinary browser/Custom-Tab login | **Negative — not viable** | F4: sessions are isolated; F1/F3: content is session-gated |
| Native HTTP with in-app WebView session (gallery-dl pattern) | Untested, excluded for the product by the repo credential boundary | F3 shows the internal API would serve content given a member session cookie |
| Official API, member-grant token, third-party paid post | **Pending — one user action away** | Probe ready; F7 token path avoids the blocked redirect; F8 unresolved until run |
| Fully in-app OAuth authorization (production design) | **Blocked without an https intermediary** | F5 custom schemes rejected; F6 no PKCE; secret must not ship in APK |
| Firefox Android extension (Gate B) | Unchanged fallback | Not exercised this session |

## 5. Remaining user steps (the only blockers)

1. **On the emulator** (Chrome is already open at the target post): log into Patreon in the browser, then confirm visually that the full chapter renders (not a teaser/preview). Note the emulator browser session is separate from any desktop login; do not copy desktop cookies.
2. **Create the API client**: on `patreon.com/portal/registration/register-clients` (works from the emulator browser too), finish creator onboarding with the page kept **unpublished** (user already agreed), then register a v2 client. The redirect URI field can be any `https://` placeholder (custom schemes are rejected; the authorize flow will not be used). Then copy the **Creator's Access Token**.
3. **On the probe screen**: paste the token into "or paste access token (creator token from portal)" and tap **Test authorized post API**. The token stays in memory; the report never contains it.

## 6. How to read the outcome (next session)

Re-read the report with the run-as command in §1, then:

- If **Identity** shows `memberships included≥1` with `active_patron marker=true` and **Member target post** shows `content chars` in the tens of thousands (the desktop-rendered body was ~12k text chars; HTML will be larger): **Gate A is viable** — proceed to the plan's follow-ups (pagination via campaign posts listing, refresh, and the production authorization design, which still needs an https intermediary or backend per F5/F6).
- If the post returns **403/404/empty content while identity shows the active membership**: record the exact error line — that is the documented API refusal (F8 resolved negative), and Gate B (Firefox extension) becomes the route.
- If identity itself fails: check token freshness/scope generation before concluding anything about post access.

## 7. Artifacts

- Probe project: `scripts/patreon_spike/android/` (rebuild command in §1; APK at `app/build/outputs/apk/debug/app-debug.apk`).
- On-device report: `com.example.patreonprobe.debug` → `files/report.txt` (survives reinstall; resets when the app process restarts, by design).
- No credentials, tokens, or chapter bodies are stored in the repository, the report, or this document.
