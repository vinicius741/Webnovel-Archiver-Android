# Per-Novel Metric Trends

Records a per-novel time series of score and Patreon figures on every sync, and graphs them on a
Trends sub-screen reached from the novel's detail page. This is the app's first time-series store;
before this feature every sync overwrote `Story.score` and `Story.patreonStats` in place and the
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
| `patreonPaidMembers` | only when Patreon was refreshed | `null` otherwise |
| `patreonMonthlyUsdCents` | only when Patreon was refreshed | `null` otherwise |
| `patreonAmountIsEstimated`, `patreonMembersIsEstimated` | only when Patreon was refreshed | estimation flags |

The Patreon fields stay `null` on **batch "Follow Updates" syncs** (which pass `refreshPatreonStats = false`)
and on stories without a Patreon URL. A `null` Patreon field therefore reads as "not measured this
sync" — never as "zero" — so a chart correctly shows the gap rather than a fabricated zero point.

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
series (`"score"`, `"patreon_members"`, `"patreon_usd"`), or `null` for the generic view. Reached
from three places on the detail screen:

- **Tappable score row** — opens focused on the score chart.
- **Tappable Patreon stats body** — opens focused on the Patreon USD chart (the header's
  open-external glyph keeps its open-in-browser handler, so the two tap targets stay distinct).
- **Overflow menu → "Trends"** — opens the generic view.

The screen renders one MPAndroidChart `LineChart` per available series, each in a card with a
summary line ("Current 4.84 (+0.12 since last sync) · range 4.2–4.9"). A series needs at least two
points to draw a line; with fewer it shows an explanatory card. The empty state ("No trend data yet")
appears for a novel that has never been synced since the feature shipped.

Chart axes are fitted to the recorded data, not pinned to the metric's full domain: week-to-week
movement in these metrics is small (a score wobbling 4.6→4.8, members creeping 1200→1250), and a
fixed 0–5 / 0-based axis rendered that as a flat line. `TrendAxisPlanning.yAxisRange` computes the
Y range from the series with a padding margin, clamped to the valid domain (score 0–5, counts/cents
≥ 0), with a synthetic window for flat series. Each chart is laid out at a fixed height
(`TREND_CHART_HEIGHT_DP`) — a `LineChart` has no intrinsic height and collapses to a few dozen px
under `WRAP_CONTENT` inside the ScrollView — and the Y label count/format precision are chosen so a
narrow range cannot produce duplicate, overlapping axis labels. The X axis uses one-day granularity,
matching the per-calendar-day snapshot resolution.

## Trend indicator on the detail page

The detail page's score row carries a small arrow beside the rating (`DetailsHeader.observeScoreTrend`):
up (tertiary) when the score rose across the recorded series, down (error) when it fell, hidden when
there are fewer than two points or the series is flat. Direction is the overall trajectory —
`MetricSnapshotPlanning.direction` (last minus first point), not the last-sync delta — so one noisy
sync cannot flip it. The history read is asynchronous (the repository does not cache metric history
in memory) and the arrow is patched in place once loaded.

## Adding new metrics

Future metrics (rating count, favorites, ranking, Patreon tier breakdown) require small extensions to
the source providers (`NovelMetadata` gains new fields; `RoyalRoadProvider` / `ScribbleHubProvider`
parse them). `StoryMetricSnapshot` then gains matching **nullable** fields. Because the snapshot is
forward/backward-compatible (Gson fills missing fields with defaults), no history-format migration is
needed — old history files simply read as `null` for the new field until the next sync populates it.

The chart helper (`buildTrendChart` / `TrendMetricKind`), the axis-range planner
(`TrendAxisPlanning`), and the series extractors in `MetricSnapshotPlanning` are the only other
places that need a new entry per metric.
