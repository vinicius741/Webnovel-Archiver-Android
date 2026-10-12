# Sufficient Velocity

Sufficient Velocity is registered as `sufficient_velocity` through `SufficientVelocityProvider`.
The Add Story picker, source settings, library filters, and download scheduler discover it through
`SourceRegistry`.

## Imports and chapters

Paste a public thread URL such as
`https://forums.sufficientvelocity.com/threads/story-title.12345/`.
Numeric thread URLs, query parameters, and thread links with post fragments are accepted too.
Standalone post URLs are chapter URLs and cannot start a new story import. Forum listings,
member pages, and reader or threadmark-index URLs are not importable; use the thread's main URL.

Story IDs are `sv_<thread-id>` and chapter IDs are `sv_<post-id>`. They remain distinct from
SpaceBattles IDs even when the numeric IDs match. Existing SpaceBattles identity and storage paths
are preserved.

The provider shares the XenForo threadmark and reader implementation with SpaceBattles. Full sync
fetches every page of the main Threadmarks category, in reading order, and deduplicates boundary
posts. Ordinary replies, side stories, apocrypha, informational posts, and other categories are
excluded. Empty or over-limit indexes fail instead of being committed as a truncated chapter list.
Update checks read the main-category RSS feed; metadata uses chapter publication dates rather than
RSS feed-generation time. SpaceBattles' separate Story Library enrichment remains source-specific.

Downloads locate the exact post inside reader pages, reuse a fetched page across chapters, check
neighboring pages when boundaries move, and fall back to the direct post URL. Paragraphs, formatting,
and images survive cleanup; scripts and forum navigation are excluded.

Metadata includes the title, author, available cover, description, tags, publication status,
creation/update dates, category, watchers, and main-category word count when present. Patreon links
are discovered from the story page. Missing covers use the app's existing fallback.

## Network and access

Requests use a 1.5-second minimum gap, a shared budget of 30 requests per minute, and one concurrent
chapter download. The source uses the existing browser-session and Cloudflare verification flow.
Public threads are supported; this change adds no account-login flow for restricted threads.

## Validation

Sanitized fixtures retain the public site's threadmark, metadata, RSS, and reader structure with
invented story content. `SufficientVelocityProviderTest` covers URL ownership, separate IDs,
metadata, category exclusion, chronology, pagination, incomplete indexes, reader-page reuse,
content cleanup, and direct-post fallback. Existing SpaceBattles tests protect shared behavior.

On October 12, 2026, the full local gate (`:app:lintKotlin :app:ci`) and debug build passed,
including 1,180 unit tests. The `webnovel_api36` debug app imported Shackleton's 12 main
threadmarks, downloaded its first two chapters, rendered chapter 2, and retained both downloads
after a follow-up sync. The source picker was checked on the inner and cover displays.

Public pages inspected for the implementation:

- [Shackleton thread](https://forums.sufficientvelocity.com/threads/shackleton-hard-sci-fi.159398/)
- [Main threadmark index](https://forums.sufficientvelocity.com/threads/shackleton-hard-sci-fi.159398/threadmarks?threadmark_category=1)
- [Main reader](https://forums.sufficientvelocity.com/threads/shackleton-hard-sci-fi.159398/reader/)
