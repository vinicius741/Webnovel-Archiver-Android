# Adding a Novel Source

Novel sources are compiled into the Android app. A conventional HTML source should require one
provider implementation, sanitized fixtures and tests, and one explicit `SourceRegistry` entry.
Do not add source-name or host switches to sync, downloads, Settings, or UI code; declare the policy
on the provider descriptor instead.

## 1. Define stable identity and policy

Implement `SourceProvider` under `android/app/src/main/java/.../source/` and start with a
`SourceDescriptor`:

```kotlin
override val descriptor =
    SourceDescriptor(
        id = "example_source",
        displayName = "Example Source",
        browseUrl = "https://example.com",
        hosts = setOf("example.com", "mobile.example.com"),
        capabilities = SourceCapabilities(latestChapterSync = true),
        networkPolicy = SourceNetworkPolicy(minimumRequestGapMillis = 1_000L),
        featuredMetrics = listOf(SourceMetricKind.FOLLOWERS, SourceMetricKind.WORDS),
    )
```

- `id` is a permanent storage key. Use lowercase letters, digits, and underscores. Never rename it
  after release.
- `displayName` is user-facing and may change without invalidating persisted settings.
- `hosts` must list every exact story/chapter host. The registry parses the URI host; it never accepts
  supported URLs embedded in another host's path or query.
- Put concurrency, partial-sync, retry/pacing, desktop-user-agent, cookie seeding, protected browser
  session, rendered-page validation, and featured-metric choices in the descriptor.

## 2. Implement URL and parsing behavior

Implement:

- `classifyUrl` for importable story URLs and downloadable chapter URLs.
- `normalizeStoryUrl` for equivalent mobile, tracking, or alternate URL forms.
- Stable `getStoryId` and `getChapterId` results. Reject malformed URLs; never use timestamps or
  random fallback IDs.
- `parseMetadata`, `getChapterList`, and `parseChapterContent`.
- `getLatestChapterList` only when the descriptor declares `latestChapterSync`.
- `fetchChapterContent` only when a source can reuse reader pages or otherwise needs nonstandard
  chapter retrieval.

The inherited `loadStory` implementation performs the normal HTML workflow and reuses the first
response if a latest-only chapter list needs a full-sync fallback. Override `loadStory` as one unit
for sources driven by JSON/GraphQL APIs, authentication, or JavaScript rather than forcing those
flows into HTML parsing hooks.

## 3. Register and test

Add the provider to the explicit list in `SourceRegistry`. Then add sanitized fixtures under:

```text
android/app/src/test/resources/fixtures/<source-id>/story.html
android/app/src/test/resources/fixtures/<source-id>/chapter.html
```

Provider tests should cover metadata, chapter ordering and stable IDs, content cleanup, URL variants,
and malformed pages. The shared registry contract additionally checks unique stable IDs, exact host
ownership, typed URL matches, normalization, and legacy setting-key resolution.

Run:

```bash
android/gradlew -p android :app:testInstrumentationUnitTest --tests 'com.vinicius741.webnovelarchiver.source.*'
android/gradlew -p android :app:lintKotlin :app:ci
```

When source behavior affects rendered pages or browser verification, also exercise imports and a
chapter download on the `webnovel_api36` emulator.

## Persistence compatibility

`Story.sourceId` and `DownloadJob.sourceId` carry the stable provider ID. Startup migration backfills
legacy records from their URLs and changes historical display-name settings keys to descriptor IDs.
Unknown IDs and settings keys are preserved so newer backups are not destructively rewritten by an
older build.

Existing story IDs, archive IDs, chapter paths, and backup formats remain compatibility-sensitive.
Adding a source must not change the ID algorithm of a source that has already shipped.
