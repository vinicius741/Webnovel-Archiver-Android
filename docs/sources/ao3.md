# Archive of Our Own

AO3 is registered as `ao3` through `Ao3Provider`. The Add Story source picker and source
settings discover it through `SourceRegistry`.

## Supported reading flow

Paste an `https://archiveofourown.org/works/<id>` URL or a work/chapter URL such as
`https://archiveofourown.org/works/<id>/chapters/<chapter-id>`. Imports normalize to the work,
so importing different chapters does not create separate books. Tracking parameters and fragments
are discarded. Bare `/chapters/<id>`, series, collection, and search URLs are not importable.

The provider reads `/works/<id>/navigate`, then requests its first chapter with
`view_adult=true`. This avoids work-page redirects and provides the full published chapter list
and dates without fetching the entire work. The `view_full_work` parameter must be omitted; AO3
treats even `view_full_work=false` as a request for the entire work. Full imports and update checks use this same flow. A missing, duplicate,
foreign-work, or incomplete index fails before the library can treat missing chapters as deletions.

Work IDs use `ao3_<work-id>`; chapter IDs use `ao3_chapter_<chapter-id>`. Single-chapter works use
actual chapter IDs too, preserving downloaded content and progress if the author adds chapters.
Downloads request one chapter at a time with the adult-content interstitial acknowledged. Source
requests have a three-second minimum gap and a shared budget of 20 requests per minute.

The reader receives chapter prose with HTML formatting and images. Work summaries, author notes,
endnotes, comments, and navigation are excluded. Metadata includes authors, summary, tags, fandoms,
characters, warnings, rating, language, dates, and completion status. Kudos, bookmarks, comments,
hits, and word counts are stored as separate metrics; content ratings are not numerical scores.
AO3 does not supply a standard work cover, so the app uses its existing cover fallback.

## Access limits

This integration supports publicly accessible works. Login-restricted, unrevealed, deleted,
or otherwise inaccessible works fail instead of importing a login page or placeholder.
No AO3 account-login or browser-session transfer flow is implemented.

## Validation

Synthetic HTML fixtures mirror AO3's public work, chapter, and navigation templates, with invented
story content. `Ao3ProviderTest` covers URL ownership, normalization, metadata, complete indexes,
stable chapter IDs, body cleanup, request orchestration, and download request gating.

Reference templates:

- [Work rendering](https://github.com/otwcode/otwarchive/blob/master/app/views/works/show.html.erb)
- [Chapter rendering](https://github.com/otwcode/otwarchive/blob/master/app/views/chapters/_chapter.html.erb)
- [Chapter index](https://github.com/otwcode/otwarchive/blob/master/app/views/works/navigate.html.erb)
