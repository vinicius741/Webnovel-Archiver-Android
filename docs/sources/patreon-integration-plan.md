# Patreon integration: feasibility and implementation handoff

Research date: 2026-09-11. Status: exploration and design only. No production code, account settings, memberships, or device data changed. This document distinguishes observed behavior from proposed behavior and untested assumptions.

Update 2026-09-11 (later): the emulator acquisition proof ran. Pre-login native evidence, OAuth documentation findings (custom-scheme redirects rejected; no PKCE; creator-token diagnostic path), and the pending member-token test are recorded in `patreon-acquisition-proof-2026-09-11.md`.

## 1. Decision and user requirements

An Android-only integration appears feasible with a browser extension as the acquisition layer, subject to the device proof in section 5. A seamless implementation contained entirely in the APK is **not established**. Do not promise that a successful Patreon login makes paid chapters available to the native downloader.

The user confirmed:

- Use **The Lich King Reincarnates as a Baby** as the main test novel.
- Keep one library novel containing public chapters and Patreon advance chapters.
- Prefer the public copy after the app reliably matches the two versions.
- Everything must run on Android. A desktop companion is out of scope.
- Prefer the existing app experience. An Android browser extension is acceptable if necessary.
- This task produces a plan for another agent. Implementation belongs to a later task.

Recommended execution order: establish the acquisition route, prove one chapter can reach Android storage, then build combined-source identity and sync. Do not begin with a Patreon button and leave authentication for last.

## 2. What we actually observed

The user signed into Patreon in the Codex internal desktop browser. The signed-in page became the home feed. The precise login method was not observed; this is not evidence that Google login works inside Android WebView. No passwords, cookies, tokens, account identifiers, or full chapter bodies were saved into repository files.

| Observation | Evidence and implication |
|---|---|
| Creator and novel are different entities | [TheBooksofORAR](https://www.patreon.com/cw/TheBooksofORAR) has several novel collections. A creator URL cannot unambiguously identify a library novel. |
| Target collection has a stable numeric identifier | [Collection 2030713](https://www.patreon.com/collection/2030713) showed 218 posts. Prefer collection identity over its editable name. |
| Listing is paginated | The collection initially rendered 20 post links and a Load more button. It offered Newest first, search, Filters, and list/post views. Never treat the first page as a complete inventory. |
| A detail page exposed a richer navigation list | The fractional chapter page contained 218 collection navigation entries. This is useful evidence, not a permanent API contract or proof all 218 bodies are accessible. |
| Readable prose has a separate container | [Post 169054023](https://www.patreon.com/TheBooksofORAR/posts/lich-king-as-4-5-169054023) rendered Book 4 Chapter 18.5 with a `.patreon-post-content` element, about 12,126 text characters, 82 paragraphs, and 13 line breaks. Counts describe this observation, not acceptance thresholds. |
| Rich text includes inline formatting | Italic/emphasized fragments appeared as separate text nodes. Flattening all text loses paragraph boundaries; concatenating nodes with inserted spaces can corrupt punctuation. |
| Main content is much wider than the chapter | The page also contained comments and collection links. `main.textContent` was about 32,238 characters. Extracting the whole main region would contaminate the chapter. |
| Body images need classification | One image element existed within the body but had zero natural dimensions at observation time. Its purpose and successful loading were not established. Do not classify every image as an illustration. |
| URL aliases refer to the same post | The collection used `/posts/169054023?collection=2030713`; the canonical link used `/TheBooksofORAR/posts/lich-king-as-4-5-169054023`. Title slugs and collection query parameters must not determine identity. |
| Chapter numbers are not integers | The collection contains fractional numbers, including a spaced `47 .5`, volume-local numbers, and epilogues. `ChapterInfo.chapterNumber: Int?` cannot encode this ordering by itself. |
| Numbering conventions change within a novel | Earlier entries use global chapter numbers; later entries use Book 3 and Book 4. A single numeric offset is unsafe across the whole series. |
| Creator feed mixes access levels | The signed-in home feed showed a RavensDagger post with Upgrade to unlock and Buy post controls. Being signed in or following a creator does not imply access to every post. The locked body was not accessed. |
| Public counterpart exists | [Royal Road fiction 154956](https://www.royalroad.com/fiction/154956/my-baby-sister-is-secretly-the-lich-king) was verified live with 199 chapters, ending at Book 4 Chapter 1. Patreon reached Book 4 Chapter 19. The raw count difference is 19, not a verified count of distinct readable advance chapters. |
| Public removal is a real case | The live Royal Road title and description announce Book 1 stubbing on October 3. Preserve downloaded copies when a source removes chapters. |

The live Royal Road last page also includes fractional chapters and a Book 3 epilogue. Title candidates exist on both sources, but this exploration did not compare corresponding full bodies or verify all mappings. One web retrieval returned an old 47-chapter snapshot; the live browser confirmed the current 199-chapter list. Prefer live evidence over cached search counts.

### Useful selectors observed, not guaranteed

On the Patreon detail page:

```text
link[rel="canonical"]
[data-testid="single-column-post-wrapper"]
.patreon-post-content
[data-tag="post-title"]
[data-tag="post-tags"]
[data-tag="post-tag"]
[data-tag="previous-post"]
[data-tag="next-post"]
[id^="post-link-element-"]
```

Some title and control elements appeared twice. Choose the visible/current post region and deduplicate by post identity. Teasers have a distinct `data-tag="teaser-post-content"` marker. Its presence elsewhere on the page must not invalidate an otherwise complete current post. Scope all access and extraction checks to the current post.

The observed `time` elements belonged to comments, not the post publication timestamp. Never use the first timestamp found on the page. Preserve a null timestamp when an authoritative post date cannot be obtained.

## 3. Login and acquisition options

There are three separate concerns: signing into Patreon, authorizing an API client, and obtaining a complete post body. Success at one does not establish the next.

Google prohibits OAuth authorization in developer-controlled embedded user agents. Continue using an external browser or Custom Tab for that login flow. Adding the Google Sign-In SDK to this app would not authenticate it into Patreon's website. [Google OAuth policies](https://developers.google.com/identity/protocols/oauth2/policies).

Custom Tabs run in the user's browser and share its browser session. WebViews have separate state. The current app's Import action returns a URL; it does not return authenticated HTML or browser cookies. Custom Tabs do not provide this app a general DOM-extraction or cookie-export interface. [Chrome Custom Tabs overview](https://developer.chrome.com/docs/android/custom-tabs).

| Route | Decision |
|---|---|
| Official Patreon API with member authorization | Preferred if a bounded proof establishes access to supported creators' complete posts. Currently unproven. |
| Ordinary Custom Tab login followed by OkHttp fetch | Insufficient by itself. These are different sessions. |
| Google OAuth inside WebView or a bundled browser engine | Excluded. It conflicts with the repository login boundary and Google's policy. Changing user-agent strings is not a solution. |
| Patreon password/email login inside WebView | Do not silently introduce it. The current repository rule places third-party credential flows in Custom Tabs. Availability of email login does not establish a compliant end-to-end session bridge. |
| Exporting browser cookies into the APK | Not the baseline. Avoid broad reusable credentials, browser-profile extraction, root, or clipboard instructions for session secrets. |
| Firefox for Android extension plus local file import | Recommended fallback, accepted by the user in principle. Requires device validation. Browser owns login; the extension collects authorized rendered content. |
| Creator-operated integration | Could change API access possibilities but requires author cooperation. It does not satisfy arbitrary supported authors automatically. |
| Desktop extension or hosted browser automation | Out of scope under the Android-only requirement. |
| Manual chapter text/file import | Last fallback if the extension proof fails. Useful but does not meet automatic collection sync expectations. |

### Official API uncertainty

Patreon documents post-list and post-detail endpoints requiring `campaigns.posts`. Membership information is separately scoped. The documented token exchange uses a client secret; PKCE support was not found in the reference. These facts do not establish that a paying member can retrieve another creator's posts. Use API v2 if this route succeeds. [Patreon API reference](https://docs.patreon.com/).

Resolved by the 2026-09-11 proof session (see `patreon-acquisition-proof-2026-09-11.md`): custom-scheme redirect URIs are rejected at registration (developer-forum confirmed; https only), and PKCE is undocumented — so a fully in-app OAuth authorization flow needs an https intermediary the user controls (e.g. a minimal Cloudflare Worker redirect/App Link) or a backend; that is a future architecture decision. Registering a v2 client automatically issues a Creator's Access Token carrying all V2 scopes, which sidesteps the redirect for the diagnostic. Unauthenticated baselines: the post page fetch and the internal `api/posts` endpoint both return HTTP 200 natively (no Cloudflare challenge) with metadata but no body — content is entitlement/session-gated server-side.

Before selecting native API access, prove subscriber access, complete bodies, pagination, refresh, and a secure native authorization flow without an embedded shared secret. No OAuth client was created or authorized during this investigation, so no live API claim is made. Creator credentials must never be distributed to readers. A backend would be a separate architecture decision, not an implicit dependency.

### Why the Android extension fallback is plausible

Firefox supports Android extensions and tab interaction, but its native messaging APIs are unavailable. Android may also kill extension processes. Use resumable work and a file transfer first, rather than assuming desktop-style native messaging. [Mozilla's Android extension differences](https://extensionworkshop.com/documentation/develop/differences-between-desktop-and-android-extensions/).

The initial transfer proposal is a locally generated JSON file downloaded by the extension, then selected through Android's Storage Access Framework. Mozilla documents `downloads.download`, including Android's rejection of `saveAs: true`. Blob-file download and the full import round trip still need testing. [MDN downloads.download](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/downloads/download).

Do not promise unattended background Patreon sync with this route. The first supported behavior is a user-started collection session in Firefox, resumable if interrupted. Reading, TTS, EPUB, matching, and public-source sync remain in the Android app.

## 4. Product behavior

From a novel's details page, offer **Add Patreon chapters**. Reuse the existing public Patreon link as a suggestion only. Ask the user to select a collection or define a selection rule; never import a creator's entire feed into one novel without review.

The user sees a preview with the selected creator/collection, chapter order, readable posts, locked posts, possible duplicates, and excluded non-chapter posts. Permit manual include/exclude and order corrections. Store these choices for the next import.

After import, show one chronological chapter list. A chapter may have a public edition and a Patreon edition, with a small source indication and an edition chooser. Already read chapters stay read after source matching. Public metadata stays the default novel title, author, cover, and description; do not replace it with a creator profile.

“Prefer public” means switch the default only after a matched, complete public edition is successfully downloaded. Keep the Patreon edition locally. For a chapter currently open in Reader/TTS, pin the session to its existing edition until a chapter boundary or explicit user switch. Do not transfer character offsets blindly between different text versions.

Locked posts may be listed with an explanation, but are not runnable download jobs, missing-text successes, or proof the creator is unavailable. Let the user open Patreon to manage access. Payment is outside the feature.

An extension import screen should say **Import Patreon chapter file**, not imply the APK owns a live Patreon session. Its status reports last imported time and collection coverage. A future verified API route may expose Connect/Disconnect instead.

## 5. Bounded feasibility proof before feature work

The next agent should produce a short go/no-go report and disposable test artifacts before broad model changes. Development may use this computer, but end-user execution must need only Android.

### Gate A: native API route

1. Confirm current Patreon documentation and client-registration requirements. Obtain any client creation/authorization explicitly through the user when needed; do not fabricate credentials or ask for them in chat.
2. ~~Determine whether public-client authorization with PKCE is actually supported.~~ Resolved 2026-09-11: PKCE is not documented; the only documented grant is authorization-code with client secret. Custom-scheme redirect URIs are rejected, so the device diagnostic uses the portal-issued Creator's Access Token (all V2 scopes) instead of the authorize flow. Do not put a client secret in the APK.
3. If a safe authorized client is available, request the least privileges needed. Test one known readable post, one known inaccessible post, and a paginated selected collection/campaign inventory with the ordinary member's grant. (Probe installed on `webnovel_api36`; awaiting the user's pasted creator token — see the results doc §5–6.)
4. Verify the readable response contains the complete body, not just identity, title, preview, or membership data. A successful identity response does not pass this gate.
5. Test refresh, denial, revocation, and process recreation. Record response status/shape without authorization headers or private bodies.
6. If member content access or secure native authorization cannot be established within one focused investigation, record the missing evidence and select Gate B. Do not spend the whole project reverse-engineering OAuth.

### Gate B: Android browser extension

1. Follow repo emulator skills. Use only `webnovel_api36`; no physical phone is authorized by this task. Keep debug library data intact and use the instrumentation sandbox for automated storage tests.
2. Validate current Firefox Android extension packaging, signing/distribution, required APIs, and supported manifest model. Consult [Mozilla's Android development guide](https://extensionworkshop.com/documentation/develop/developing-extensions-for-firefox-for-android/); do not assume Chrome MV3 service-worker behavior.
3. Have the user perform login in Firefox on the emulator. The desktop session in this research is not an Android session and must not be copied into it.
4. On the target collection, read metadata from at least two list pages. Test stable-ID deduplication and interruption/resume. Compare observed coverage with the displayed count without assuming that count guarantees access.
5. Open three user-readable posts, including a fractional chapter and a public-match candidate. Collect only their current-post bodies. Test a locked post without trying to unlock it.
6. Save a bounded, versioned JSON package to Android Downloads. Open it through an app SAF picker. First use a synthetic package to prove the transfer path without paid text.
7. Verify paragraphs, emphasis, and chapter boundaries in Reader and TTS. Compare the extracted beginning and ending locally against the rendered page. Exclude comments and recommendation prose.
8. Kill Firefox mid-batch; restart and resume. Kill the app before import commit. Verify no duplicate chapters, corrupt files, or lost progress.
9. Test stable Firefox installation and extension persistence across restart. A temporary developer-only add-on is not a production installation proof.

Gate B passes only when login, collection discovery, correct body extraction, local transfer, and interrupted-work recovery all work on Android. If it fails, document the exact failing step and present the manual-import limitation. Do not claim app-only automatic downloading is solved.

## 6. Domain model and compatibility strategy

Keep existing `Story.id` and existing chapter IDs. Add source bindings and per-chapter editions without migrating every legacy novel eagerly. Proposed names below are design suggestions, not existing classes.

| Entity | Required fields and rules |
|---|---|
| `StorySourceBinding` | Stable local binding ID, provider ID, source URL, optional remote creator/campaign ID, collection IDs or tag/manual selection, user label, selection revision, transport kind, last successful acquisition, coverage. Persist numeric campaign ID only when actually observed; a creator handle is an alias. |
| Logical chapter | Existing local chapter ID where one exists; otherwise an ID minted once on import. Display title, canonical order, reading state, and edition references. Never derive logical identity from list position or normalized title. |
| `ChapterEdition` | Provider ID, remote post/chapter ID, canonical URL, source title, source order, optional parsed volume/number/part, publication date, observed date, body hash, local path, download state, access state, extraction version. |
| `ChapterSourceMatch` | Two edition identities, target logical chapter ID, match status, reason/evidence, user override, matcher version. Persist rejected matches too, so the same bad suggestion does not return every sync. |
| `SourceSnapshot` | Binding and selection revision, complete/partial status, observed IDs, crawl start/end, resume checkpoint, failures, and known access exclusions. A timeout cannot yield an authoritative empty list. |
| `AcquisitionBatch` | Batch ID, transport/schema version, selected binding, payload items, statuses, and import result. It must be replay-safe. |

Remote identity should be provider-qualified, such as `patreon:post:169054023`. The same post in two collections has one remote identity. Selection membership is separate. Do not encode a source switch into the logical chapter ID.

Separate access from local availability. Suggested access states: unknown, readable, login required, locked, unavailable, unsupported. Suggested acquisition outcomes additionally include rate limited, verification required, malformed, partial, and network failure. A locked post is not necessarily deleted; a missing post is not necessarily locked. The browser session is authoritative for what it can currently read.

The legacy fields `Chapter.url`, `filePath`, and `downloaded` can remain a compatibility projection of the selected edition while consumers migrate. One shared resolver must own that projection. Two independently writable representations will eventually diverge.

Persist new structures within the same repository/storage transaction system. Do not add a second storage owner. Retain unknown fields/versions conservatively where practical and test Gson defaults explicitly; missing JSON fields do not always behave like Kotlin constructor defaults.

### Ordering and matching

1. Respect an explicit collection order when the user chooses it. Otherwise propose narrative order from parsed volume, chapter label, fraction, part, and special entry type. Use publication order only as a fallback and stable remote ID only as a tie breaker.
2. Retain raw labels. Parse fractions as exact decimal strings/rational values, not `Double`. Distinguish numbered parts from decimal numbers and volume-local numbering from global numbering.
3. Normalize spacing and punctuation for matching, including `47 .5` versus `47.5`. Do not erase volume information or meaningful subtitle differences.
4. Auto-link only high-confidence candidates: an explicit stored user mapping, or identical sufficiently substantial normalized bodies within the selected novel and with compatible order/context. Tiny boilerplate bodies are not enough.
5. Treat matching volume/number/title and neighbor sequence as a reviewable candidate unless a calibrated corpus supports automatic matching. Different creators can reuse the same title; the story binding is a prerequisite.
6. Content similarity can rank candidates. Preserve both editions when one is extended, revised, split, combined, or materially different. No AI service is needed for the baseline matcher.
7. If one public chapter corresponds to several Patreon posts, require an explicit grouping/edition representation. Do not auto-collapse a one-to-many relationship in v1.
8. When two pre-existing logical chapters are merged, preserve an alias from the retired ID and reconcile bookmarks, TTS positions, queue jobs, EPUB ranges, AI selections, and rewritten variants transactionally. Offer an undoable unlink operation.

The selected novel's early global numbering and later volume-local numbering make a whole-series numeric offset a known bad approach. First ship manual anchor mappings and conservative matching before trying to infer conversion rules.

## 7. Acquisition and extraction contract

Keep acquisition separate from library merge. Both an API adapter and an extension importer should yield the same source snapshot plus edition payloads. A file importer must not pretend it can make a live network fetch.

For the extension route:

- Work only after a user starts an import for a selected creator/collection. Narrow host access to Patreon and justified asset hosts; do not request all-site access or cookie-reading permissions for DOM extraction.
- Navigate readable post pages through the browser's normal session. Do not extract private application state, depend on undocumented hidden endpoints, bypass locked posts, or remove access controls.
- Serialize acquisition initially, with a configurable conservative interval, a finite batch limit, and cancellation. Stop on login, verification, or access challenges. Back off on rate limits when visible; never evade them with rotating identities.
- Wait for current post identity and settled body, with a timeout and explicit partial outcome. A spinner, login form, preview, or missing selector is a failure to acquire a complete chapter.
- Require exactly one intended current-post body after excluding duplicate responsive copies. A `.patreon-post-content` class alone is insufficient proof of completeness. Verify no scoped paywall/truncation controls remain and that the ending is present.
- Preserve paragraphs, line breaks, emphasis, headings, lists, quotations, and safe tables. Sanitize scripts, event handlers, forms, iframes, executable URLs, and unsafe styling. Never use full-page text as a fallback.
- Record author notes separately when identifiable. Preserve epigraphs by default; the sample novel uses opening quotations as story content. Unknown notes should not be silently stripped.
- Plain inline prose is v1. Attachments and external-document chapters produce an explicit unsupported/manual-import result until separately implemented. Never mark a post downloaded merely because its announcement text was readable.
- Classify images. Retain alt text and report missing meaningful assets. Do not silently declare image-only story content complete. Avoid loading remote trackers during offline reading.
- Save a checkpoint after each post. Resume by stable post identity; recheck collection overlap to detect insertions, not just a numeric page offset.

Patreon allows selected-tier access, early release, purchases, and changes to published-post access. Pledge amount is not a reliable entitlement calculation. Keep rules per post and recheck when acquiring a new body. [Patreon audience-access documentation](https://support.patreon.com/hc/en-us/articles/37807653033997-Setting-post-access-for-your-Patreon-audience).

Collections can contain overlapping posts and custom ordering, with permissions applied at the post level. User selection remains necessary even when collection metadata is available. [Patreon collections documentation](https://support.patreon.com/hc/en-gb/articles/16666733679757-How-to-use-Collections-to-organise-your-work).

### Local file format, first version

Use a bounded UTF-8 JSON file for text-only batches, provisionally `*.wna-patreon.json`. Avoid ZIP complexity until local assets are supported. Suggested envelope:

```json
{
  "format": "wna-source-import",
  "version": 1,
  "batchId": "random-once-per-batch",
  "providerId": "patreon",
  "collectionIds": ["2030713"],
  "capturedAt": "ISO-8601 UTC",
  "coverage": "partial",
  "selectionRevision": "stable-selection-fingerprint",
  "posts": [
    {
      "remoteId": "169054023",
      "canonicalUrl": "https://www.patreon.com/TheBooksofORAR/posts/lich-king-as-4-5-169054023",
      "title": "Synthetic fixture title",
      "access": "readable",
      "bodyStatus": "complete",
      "html": "<p>Synthetic fixture text.</p>",
      "extractorVersion": 1
    }
  ]
}
```

The example identifies a real page but contains synthetic text and is not an evidence fixture. Freeze the exact schema only after Gate B proves the transfer.

The app must recompute hashes after validation, validate exact HTTPS hosts and post-ID consistency, limit total bytes/post count/body size/nesting, reject unsupported major versions, and treat filenames and HTML as untrusted. File import does not prove authenticity; it is user-selected local content. Do not infer account entitlements from its claims.

Use `ACTION_OPEN_DOCUMENT` as the baseline. Add file-open/share intents only after testing MIME behavior and content URI permissions. Do not put chapter bodies or credentials in URL query strings or Android intent extras. Stream to private staging, validate, preview, then commit through the repository. Clean staged files after failure. Keep the input file until a verified import, and provide a user-controlled cleanup action for the Downloads copy.

Batch replay should add nothing twice. A changed body for the same remote post creates a new local revision; it must not silently overwrite a read edition. Do not send imported prose to remote analytics or AI services as part of extraction/matching.

## 8. Sync, downloads, and source replacement

Treat public sync and Patreon acquisition as independent source observations feeding a combined novel. A full Royal Road list is not an authoritative list of Patreon editions. A failed Patreon session must not block public updates.

For each sync/import:

1. Validate the binding and snapshot coverage.
2. Upsert remote editions by provider-qualified remote ID.
3. Apply persisted include/exclude choices and mappings.
4. Build candidate matches and preserve unresolved entries.
5. Compute logical order without reassigning logical IDs.
6. Re-read current repository state and fold concurrent reading/download changes.
7. Commit metadata and staged content atomically using the library generation guard.
8. Queue only supported, accessible acquisition work. Extension-needed content gets an action-required state, not automatic network retries.

Partial listings never delete prior chapters. Even a complete listing can only show a source edition is absent from that selection. It cannot prove permanent deletion, and cannot justify deleting downloaded content. Collection removals should mark source membership changes and offer review.

When a public match arrives, create/update the public edition, download it, validate it, then change the default selection. If that fails, retain Patreon as the readable default. Keep source provenance and both body revisions. If the public edition is later stubbed, keep the existing local public edition and Patreon backup. Do not manufacture a new unread chapter on every public release of an already read advance chapter.

Mark EPUB output stale on edition or ordering changes, even when chapter count is unchanged. Rewritten chapters must remain tied to the source-body hash that generated them. AI chapter selections currently stored by index need remapping through logical chapter identities before any insertion/reorder.

### Queue identity needs attention

Current jobs use `${story.id}_$index`, copy `story.sourceId`, and carry one chapter URL. Those assumptions are insufficient for mixed sources and changing editions. New work needs a stable key such as story ID + logical chapter ID + edition ID + revision/purpose, with compatibility migration for existing jobs. The provider and reliability budget must come from the edition being fetched, not the story's primary provider.

Source/auth problems need distinct handling: login required pauses relevant Patreon work; a single locked post does not stop readable posts; rate limits delay the relevant transport; malformed extraction fails without committing text; user cancellation ends the batch. Preserve existing public-source retry behavior.

## 9. Repository change map

Paths below are relative to `android/app/src/main/java/com/vinicius741/webnovelarchiver/` unless stated otherwise. Re-read files before implementation; this is a research snapshot.

| Existing file or package | Work required |
|---|---|
| `source/SourceRegistry.kt` | Register stable Patreon identity; add descriptor capabilities for acquisition/import selection as needed. Current URL kinds are only STORY and CHAPTER. Creator/collection discovery needs an explicit result or workflow. |
| `source/SourceProvider.kt` | API sources may override `loadStory`; add a separate acquisition contract for authenticated/file-backed sources rather than forcing import into HTML parser hooks. |
| `source/PatreonStatsFetcher.kt` | Keep public earnings/statistics enrichment separate from private chapter access. `Story.patreonUrl` remains a discovery hint, not a verified novel binding. |
| `domain/model/Models.kt` | Add source bindings, editions, access/coverage models with compatible defaults. Preserve old story/chapter IDs. |
| `sync/ChapterMatcher.kt` | Current matching uses one provider's ID function. Add logical identity and explicit edition aliases. |
| `sync/StorySyncPlanning.kt` | Current full merge treats incoming chapters as authoritative. Introduce per-binding snapshot merge for combined novels. |
| `sync/StorySyncMergePlanning.kt` | Preserve concurrent edition downloads, bookmarks, selection changes, and manual mappings during merge. |
| `sync/StorySyncEngine.kt` | Orchestrate primary-source sync and independent supplemental observations. Keep archived snapshots immutable. |
| `download/DownloadQueuePlanning.kt` | Replace positional identity for new mixed-source jobs and select edition provider. |
| `download/DownloadEngine.kt`, `DownloadProcessLoop.kt` | Route by edition, preserve generation/cancel guards, and avoid scheduling browser-only acquisition as native network work. |
| `source/network/SourceReliabilityCoordinator.kt` | Reuse policy for native requests; extension has its own browser pacing/checkpoint logic. Do not pretend these share a runtime budget. |
| `data/repository/` | Own batch import, matching, edition selection, and reconciliation transactions. |
| `data/storage/AppStorage.kt` | Current chapter filenames include index/title. Store edition revisions at stable paths; leave legacy paths readable. Add staging/recovery without parallel storage locks. |
| `data/backup/`, `data/storage/JsonBackupImporter.kt` | Round-trip bindings, aliases, editions, and content. Never export credentials. New importer is distinct from existing whole-library backup restore. |
| `feature/browser/BrowserScreen.kt`, `BrowserImportPlanning.kt` | Retain Custom Tab login boundary. Route Patreon URLs to selection/import guidance. Existing Import only supplies a URL. |
| `feature/details/`, `feature/settings/`, `navigation/` | Add binding setup, import preview, edition selection, and acquisition status as programmatic Android Views and stable routes. |
| `feature/reader/ChapterContentResolver.kt`, `tts/`, `epub/`, `ai/` | Resolve selected edition consistently, pin active sessions, invalidate by content revision, remap index-based choices. |
| `app/AppContainer.kt` | Wire one repository-backed acquisition/import coordinator and shared resolvers. |
| Proposed `browser-extensions/patreon-android/` at repo root | Only if Gate B succeeds: narrow-purpose Firefox extension, schema fixtures, parser tests, Android installation instructions. Confirm any closest instructions before creating. |

Follow [adding a source](adding-a-source.md) and `android/AGENTS.md`. Keep policy in descriptors/capabilities; avoid scattered Patreon string checks in generic engines. Update root README when the feature ships, plus relevant architecture/source docs and AGENTS instructions if workflows change.

## 10. Additional failure cases to design for

| Case | Required behavior |
|---|---|
| Login expires mid-collection | Save checkpoint, show login required, preserve earlier imports. |
| Account changes | Clear acquisition assumptions; never claim the new account can fetch old account's content. Previously imported local files remain explicit user-owned library state. |
| Pledge without an entitled tier | Show actual locked result; never infer access from dollar amount. |
| Public release happens on Patreon itself | Refresh access for the same post ID; do not create another chapter. |
| Public release occurs on another site | Link a distinct remote edition to an existing logical chapter. |
| Creator changes title/handle | Preserve remote ID and update aliases. |
| Post edited or republished | Distinguish same-ID revision from new-ID publication; do not deduplicate by title alone. |
| Collection reordered or split into volumes | Preserve local IDs and user order choices; allow multiple collection bindings. |
| Announcement, poll, artwork, or advertisement | Exclude/review; a title containing “chapter” is insufficient. |
| Bundle contains several chapters | Keep as a bundle or require explicit splitting. Never guess boundaries silently. |
| Single chapter spread across several posts | Require user grouping; preserve source identities. |
| Locale changes | Prefer IDs and scoped structural markers. Treat text labels as localized signals. |
| HTTP success contains login/preview | Fail acquisition without marking downloaded. |
| Broken or expiring attachment URL | Report missing attachment; do not persist signed URLs as stable identity. |
| Blank/missing date | Keep null; capture observation time separately. |
| Reader/TTS open during replacement | Continue the pinned edition until safe handover. |
| Backup restored while acquisition runs | Library generation rejects stale results. |
| Reimport or browser resume repeats posts | Stable-ID/hash idempotency prevents duplication. |
| Process dies during import | Recover staging or roll back; do not expose half-committed chapters. |
| Unknown future schema | Reject clearly or import supported subset only with explicit disclosure; never clear existing data. |
| Remote parser changes | Mark extraction unsupported and retain files. Offer a sanitized diagnostic report with version/counts, not chapter text or cookies. |

Do not interpret paid access as permission to redistribute. Keep content local by default and respect creator/platform access restrictions. Patreon's terms incorporate additional policies and prohibit technical abuse; this investigation does not establish blanket permission for automated archiving. Check the actual applicable terms before distributing the integration, without inventing a claim that the API or scraping is categorically permitted or forbidden. [Patreon terms](https://www.patreon.com/policy/legal).

## 11. Implementation phases and acceptance checks

| Phase | Deliverable | Exit criterion |
|---|---|---|
| 0. Acquisition proof | Gate A or B report, exact tested versions, redacted evidence | A complete authorized chapter reaches Android local storage through the chosen route. Otherwise stop feature expansion. |
| 1. Identity and persistence | Additive models, edition resolver, snapshot merge, import staging | Old library/backup fixtures round-trip; replay and process-death tests pass; IDs and read positions preserved. |
| 2. Narrow acquisition release | Target collection selection, text extraction, local import preview, status handling | Main sample imports correctly, locked content is not queued as successful/runnable, partial inventory cannot remove data. |
| 3. Combined novel | Manual mappings, conservative duplicate proposals, source-specific queue routing | Public release attaches an edition without duplicate unread chapter; failed replacement retains old content. |
| 4. Consumer integration | Reader, TTS, EPUB, AI variant/selection handling, backup | All consumers resolve the same chosen edition; active sessions and archives remain stable. |
| 5. Broader validation | A second creator, multiple collections, packaging, docs | No novel mixing; Android install/restart/resume proven; full local gate and emulator QA pass. |

Phases 1-4 have cross-cutting dependencies. They can be developed behind a disabled capability, but do not ship partially migrated combined-source behavior. Broader API discovery, attachments, hands-off scheduling, automatic split/merge inference, and other browsers are later work.

### Required tests

Use synthetic/sanitized fixtures instead of paid chapter bodies in version control.

1. All observed post URL aliases resolve to the same remote ID; malicious lookalike hosts, URL-embedded credentials, malformed IDs, and cross-host redirects are rejected.
2. Same post in two collections deduplicates; same title with two post IDs remains distinct pending review.
3. Volume restarts, `18.5`, `47 .5`, prologues, epilogues, Roman part labels, and equal timestamps keep deterministic order.
4. Partial first page, repeated page, lost cursor, login interruption, and empty failed response never remove chapters.
5. Full selection rescan reports source membership changes without deleting downloaded bodies.
6. Locked/preview/login/verification HTML never passes complete-body validation. Other posts' locked recommendations cannot invalidate the current readable post.
7. Paragraphs, italics, quotations, line breaks, and safe tables survive cleanup; scripts, comments, recommendation text, and forms do not.
8. Attachment-only and image-only content stays unsupported/incomplete instead of becoming an empty downloaded chapter.
9. Manual and rejected mappings survive sync; title-only ambiguous matches never auto-collapse.
10. Public replacement succeeds, fails, changes text, or is stubbed; every path preserves logical identity and a readable prior edition.
11. Chapter insertion while jobs run cannot deliver text to the wrong chapter. Same chapter's two editions cannot share a queue job key.
12. Bookmark/TTS position, active playback, EPUB staleness, AI selected indices, and rewrite hashes remain consistent across reordering/replacement.
13. Replay identical batch, changed same-post batch, malformed JSON, oversized payload, malicious HTML, unsupported schema, and file URI permission loss.
14. Cancel/remove/clear/restore during import or download cannot publish stale results.
15. Old JSON, new JSON, full backup/restore, archive creation, and unknown-source recovery preserve expected content and exclude credentials.
16. Firefox process kill, app process kill, rotation/fold changes, network loss, extension restart, and stable extension reinstall/upgrade.

Start with the relevant pure planning tests, then run the repository-required gate for a cross-cutting Kotlin change:

```sh
android/gradlew -p android :app:lintKotlin :app:ci
android/gradlew -p android :app:assembleDebug
```

Use the current emulator QA/build/dev-launch skills for device work. Install raw debug Gradle output, use qualified emulator ADB commands, and keep connected tests in the instrumentation variant. Never substitute the release app or touch the physical phone without fresh explicit authorization. Add separate extension parser/schema tests using its chosen tooling. This documentation-only investigation did not run Gradle or emulator tests.

## 12. What remains unproven

- Whether a present-day official member grant can read third-party paid post bodies and selected inventories. (Probe is installed and ready on the emulator; one user step — pasting the creator token — remains. Evidence so far: `patreon-acquisition-proof-2026-09-11.md`.)
- ~~A secure supported native authorization exchange without a shared APK secret.~~ Resolved 2026-09-11: none exists today (no PKCE, no custom-scheme redirects). Production needs an https intermediary or backend; the diagnostic proceeds via the portal-issued creator token, which is not a production authorization design.
- Firefox Android login behavior on this user's account, including any Google/2FA challenge.
- Android extension DOM parity, blob download/import, distribution, and restart behavior.
- Full-body equality between Patreon and Royal Road counterparts.
- The complete per-post access inventory for all 218 collection entries.
- Actual attachment, image-only, and external-document patterns among the user's novels.
- Long-term reliability under Patreon changes and mobile background suspension.

These are acceptance gates, not tasks the implementing agent may mark completed from this document. The desktop inspection establishes that the selected post renders usable prose and that collection metadata is discoverable. It does not establish an Android download implementation.

## 13. Copyable prompt for the implementing agent

> Read `docs/sources/patreon-integration-plan.md`, root `AGENTS.md`, `android/AGENTS.md`, and the source-extension guide. Implement the planned Patreon integration in the current branch only after completing the acquisition proof in section 5. The user requires Android-only operation, prefers the normal app experience, and accepts Firefox for Android plus a helper extension if native acquisition is not feasible. Use The Lich King Reincarnates as a Baby, Patreon collection 2030713 and Royal Road fiction 154956, as the primary validation case. Keep one logical novel, preserve existing IDs/progress/downloads, and prefer a public edition only after a reliable match and successful full download. Preserve Patreon copies and ambiguous variants. Never assume Custom Tab cookies are available to OkHttp, never move Google OAuth into WebView, and never mark preview/locked content downloaded. First report which acquisition route actually passed and its limitations, then implement the gated phases, source-aware queue and merge behavior, reader/TTS/EPUB/backup compatibility, and meaningful regression tests. Use only the debug emulator and the instrumentation test sandbox unless the user explicitly authorizes a physical phone in a new message. Do not commit, push, switch branches, or create a PR unless explicitly requested. Keep private chapter bodies and credentials out of fixtures, logs, and documentation. Finish with actual validation evidence and unresolved limitations.
