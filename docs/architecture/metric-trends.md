# Per-Novel Metric Trends

Records a per-novel time series of score, Patreon figures, and source-reported engagement metrics
(watchers, favorites, follows, …) on every sync, and graphs them on a Trends sub-screen reached from
the novel's detail page. This is the app's first time-series store; before this feature every sync
overwrote `Story.score`, `Story.patreonStats`, and `Story.sourceMetadata.metrics` in place and the
previous values were discarded.

## What gets recorded

A `StoryMetricSnapshot` is captured once per sync, inside the `StorySyncEngine.fetchOrSync` storage
transaction:

| Field | Captured | Notes |
|-------|----------|-------|
| `capturedAt` | every sync | `syncedAt` (the sync's epoch-millis timestamp) |
| `score` | every sync | raw source score string (e.g. `"4.84 / 5"`); parsed to a number at chart time |
| `totalChapters` | every sync | derived chapter count |
| `publicationStatus` | every sync | enum |
| `metrics` | every sync | copy of the sync's `Story.sourceMetadata.metrics` (`SourceMetric` list — watchers, replies, views, likes, favorites, follows, …, with `isEstimated` flags). Empty for histories recorded before the field existed. |
| `patreonRaw` | only when Patreon was refreshed | `PatreonRawStats` — measured data only (member counts, exact pledge sum when public, tier ladder with USD-at-capture prices); `null` otherwise |

`patreonRaw` stays `null` on syncs that pass `refreshPatreonStats = false` (the former batch
"Follow Updates" sync, removed in November 2026, did this) and on stories without a Patreon URL. A `null` therefore reads as "not measured this sync" — never as
"zero" — so a chart correctly shows the gap rather than a fabricated zero point. (A *measured* zero —
a campaign publicly showing $0/0 — is stored and displayed as such; only null means unmeasured.)

### Patreon raw storage and estimation

No dollar figure is ever persisted. `PatreonStatsFetcher` captures only measurements — member counts,
the public pledge sum when visible, and the tier ladder converted to USD **at capture** (one FX call
per distinct currency), so re-estimating later never re-applies a newer exchange rate to old data.
On snapshots the tier ladder is delta-encoded: `tiers: null` means "unchanged, carry the previous
snapshot's ladder forward" (empty list means "measured, no paid tiers"), keeping history files small
even though ladders rarely change.

The estimate itself is computed at render by `domain/metrics/PatreonEarningsPlanning` — the single
formula shared by the Details card, library badge, library sort, and both Trends series:

- public pledge sum passes through untouched;
- published per-tier counts → count-weighted gross scaled to the paid total;
- counts hidden → **median** tier price (the mean is poisoned by joke/whale tiers — a R$3,052 meme
  tier once produced a 7–20x overstated estimate), shown with a cheapest-tier floor as a range.

Because nothing derived is stored, changing the formula re-derives every historical snapshot at
once — there is no legacy-value step in the charts. The display flags (`amountIsEstimated`,
`membersIsEstimated`) are computed, not persisted. Histories recorded before raw storage simply have
`patreonRaw: null` and start accruing points on later syncs. If an FX outage mid-fetch would drop the
pledge sum or the whole ladder, the fetch reports failure instead so the sync keeps the previously
stored stats rather than replacing them with a degraded block.

Two anchor rules keep the delta encoding safe: a snapshot only encodes `tiers: null` against an
explicit ladder on a **strictly earlier day** (same-day re-syncs keep the ladder explicit, because
last-wins coalescing would otherwise replace the anchor with the very snapshot that needs it), and
when retention trimming drops the day holding the last explicit ladder, the carried ladder is
materialized into the first surviving snapshot so the kept tail keeps resolving.

## Retention

Snapshots are bounded so each story's history file stays small and fast to read on every Trends
screen open. `MetricSnapshotPlanning.appendAndRetain` applies, in order:

1. **Same-calendar-day coalescing** — only the latest snapshot per (system timezone) day is kept, so
   re-syncing a novel several times in a day moves that day's point instead of stacking duplicates.
2. **Cap at 1000 points** — if still over the cap after coalescing, every day inside the recent
   60-day window is kept at full per-day resolution and the older tail is trimmed oldest-first.

The recent window is 60 days (`MetricSnapshotPlanning.RECENT_WINDOW_DAYS`); the cap is 1000
(`MetricSnapshotPlanning.MAX_SNAPSHOTS`).

## Storage layout

The history store follows the existing file-based-JSON persistence model (there is no Room/SQLite in
the app). One file per story:

```
<filesDir>/webnovel_archiver/metrics/<safeName(storyId)>.json
```

Written through the same `DurableJson` atomic-write path as every other document, and under the same
`@Synchronized(storage)` monitor the sync engine and repository use, so two concurrent syncs cannot
interleave history writes. Deleting a story (`AppStorage.deleteStory`) also deletes its history file.

### Backup & restore

Trend history is **portable via the full (ZIP) backup** — not the JSON metadata backup.

- **Export** (`BackupExporter.exportFull`): each story's existing `metrics/<id>.json` is added as a
  `metrics/<encoded-id>.json` ZIP entry and listed in the manifest under `metricFiles` (one
  `{ storyId, path }` per story that has a history file). `FullBackupPaths.metricPath` is the single
  source of truth for the in-Zip path encoding.
- **Restore** (`FullBackupRestorer`): the `metricFiles` index is validated by
  `FullBackupManifestValidation`, the entries are added to the exact-set ZIP-index check, and the
  extracted `metrics/` tree is copied verbatim into the staged root. The root-swap commit moves the
  whole staged tree into place, so no extra commit step is needed.
- **Backward/forward compatibility**: the manifest key is optional. Backups written before the
  feature shipped omit it and restore with empty history (the storage layer's missing-file path
  returns an empty `StoryMetricHistory`). Conversely, an older app build that does not know the
  `metrics/` allowlist will reject a new backup at extraction — it will not silently corrupt the
  library.
- **JSON metadata backup**: intentionally does **not** include trend history. It is derived data
  (re-accumulated by syncing) and would needlessly grow the JSON payload; the full backup is the
  intended vehicle for moving history between devices.

## Where the capture happens

`StorySyncEngine.fetchOrSync`, inside the existing `synchronized(storage)` block, immediately after
`storage.addOrUpdateStory(merged)`:

```kotlin
storage.appendMetricSnapshot(
    merged.id,
    MetricSnapshotPlanning.fromStory(merged, patreonRefreshed = refreshedPatreonStats != null, capturedAt = syncedAt),
)
```

`refreshedPatreonStats != null` is exactly the "Patreon was actually fetched this sync" signal.

## The Trends screen

Route `AppRoute.Trends(storyId, focus)`; `focus` optionally opens the screen scrolled/emphasized to a
series (`"score"`, `"patreon_members"`, `"patreon_usd"`, or `"metric_<SourceMetricKind>"` such as
`"metric_WATCHERS"`), or `null` for the generic view. Reached from four places on the detail screen:

- **Tappable score row** — opens focused on the score chart.
- **Tappable Patreon stats body** — opens focused on the Patreon USD chart (the header's
  open-external glyph keeps its open-in-browser handler, so the two tap targets stay distinct).
- **Tappable engagement chips** (source metric badges like "4.13K Watchers") — open focused on that
  metric's chart. This is the primary entry point for sources without a rating (SpaceBattles,
  FanFiction.net), whose detail page has no score row.
- **Overflow menu → "Trends"** — opens the generic view.

The screen uses MPAndroidChart 4's `MPChartLib` Android Views module from JitPack. Axis labels
implement `IAxisValueFormatter`, and chart options use the library's Kotlin properties.
Trends owns its redraw callback so fold/unfold keeps the same novel and focused series.
The screen renders one `LineChart` per available series, each in a card with a
summary line ("Current 4.84 (+0.12 since last sync)") and a window line ("+0.20 in the last 30 days ·
range 4.62–4.84"). `TrendSeriesModel.buildTrendsModel` extracts every series once per open, on
`Dispatchers.Default`. Beyond score and the two Patreon series, it adds one series per metric in the
story's source `descriptor.featuredMetrics` that has recorded points — so SpaceBattles charts
Watchers/Replies/Views/Likes, FanFiction.net charts Favorites/Follows/Reviews/Words, and
RoyalRoad/ScribbleHub additionally chart Followers/Views/Pages and Readers/Views/Words. A series
needs at least two points to draw a line; with fewer it shows an explanatory card. The empty state
("No trend data yet") appears for a novel that has never been synced since the feature shipped.

### Filters

Two chip rows sit above the cards; both are single-select and rebuild only the card list:

- **Range** — 7D / 30D / 90D / 1Y / All time. `TrendFilterPlanning.availableRanges` offers only ranges
  shorter than the recorded span, and hides the row when only All time is left. Windows end at the
  newest recorded point, not the wall clock, so a novel that has not synced lately still shows its
  last weeks. A window carries in the last value from before its start so the line enters from the
  left edge and the window change covers the whole period; the X axis is pinned to the window. A
  series with no value inside the window shows "No values recorded in the last N days."
- **Metric family** — All metrics / Score / Patreon / Engagement, shown only when at least two
  families have data.

The selection is kept per history entry in `RouteViewMemory.queries`, so Back restores it (and the
focus scroll only applies on a first open). The chosen range also carries over to other novels for
the rest of the process.

### Rendering cost

Charts are cheap to keep on screen but expensive to first-draw: MPAndroidChart rasterizes each data
set into a software bitmap. The screen therefore does not animate charts (a 400 ms `animateX` on
every card re-rasterized all of them each frame and dropped most frames on open), disables chart
touch handling (zoom is off, so panning had nothing to move), and attaches charts to fixed-height
slots one per animation frame (`postOnAnimation`) after the card list is laid out. Series longer
than `TrendFilterPlanning.MAX_CHART_POINTS` are thinned with Largest-Triangle-Three-Buckets before
charting, and per-point circles are dropped above 45 drawn points. On the `webnovel_api36` emulator,
opening a five-chart SpaceBattles novel went from a 22 ms to a 16 ms median frame, and from about 60%
to 12% of frames over budget (`dumpsys gfxinfo`, legacy jank count).

Chart axes are fitted to the recorded data, not pinned to the metric's full domain: week-to-week
movement in these metrics is small (a score wobbling 4.6→4.8, members creeping 1200→1250), and a
fixed 0–5 / 0-based axis rendered that as a flat line. `TrendAxisPlanning.yAxisRange` computes the
Y range from the series with a padding margin, clamped to the valid domain (score 0–5, counts/cents
≥ 0), with a synthetic window for flat series (a tight ±0.05 band for score, so a flat week sits
mid-chart instead of against 5.0). Each chart is laid out at a fixed height
(`TREND_CHART_HEIGHT_DP`) — a `LineChart` has no intrinsic height and collapses to a few dozen px
under `WRAP_CONTENT` inside the ScrollView — and the Y label count/format precision are chosen so a
narrow range cannot produce duplicate, overlapping axis labels (score uses a 0.01 granularity to match
its two-decimal labels). The X axis uses one-day granularity,
matching the per-calendar-day snapshot resolution.

## Trend indicator on the detail page

The detail page's score row carries a small arrow beside the rating (`DetailsHeader.observeScoreTrend`):
up (tertiary) when the score rose across the recorded series, down (error) when it fell, hidden when
there are fewer than two points or the series is flat. Direction is the overall trajectory —
`MetricSnapshotPlanning.direction` (last minus first point), not the last-sync delta — so one noisy
sync cannot flip it. The history read is asynchronous (the repository does not cache metric history
in memory) and the arrow is patched in place once loaded.

## Adding new metrics

Two paths exist:

- **New engagement count** — add a `SourceMetricKind` enum value, parse it into
  `NovelMetadata.sourceMetadata.metrics` in the provider, and list it in the source descriptor's
  `featuredMetrics`. It is then snapshotted (the `metrics` list is copied wholesale), charted, and
  shown as a tappable chip with no model or UI changes.
- **Non-count metric** (ranking, Patreon tier breakdown, …) — `StoryMetricSnapshot` gains a matching
  **nullable** field plus a series extractor next to `scoreSeries`/`patreonSeries`. Because the
  snapshot is forward/backward-compatible (Gson fills missing fields with defaults), no
  history-format migration is needed — old history files simply read as `null` for the new field
  until the next sync populates it.

The chart helper (`buildTrendChart` / `TrendMetricKind`), the axis-range planner
(`TrendAxisPlanning`), the series extractors in `MetricSnapshotPlanning`, and `buildTrendsModel`
(which assigns each series its title, focus tag, and filter family) are the only other places that
need a new entry per metric.
