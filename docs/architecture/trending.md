# Trending

The Trending screen (`AppRoute.Trending`, opened from the trending icon in the Updates app bar)
ranks library novels by how fast their recorded statistics are rising now. It reads the per-novel
metric histories that syncs already record (`metric-trends.md`). It stores no state of its own.

Code: `domain/metrics/TrendingPlanning.kt` (pure ranking, tests in `TrendingPlanningTest`) and
`feature/trending/` (screen model, renderer, rows, sparkline).

## Signals

| Signal | Series | Smoothing | Weight |
|---|---|---|---|
| Patreon | paid members, creator-merged like the Trends tab | 60 | 1.0 |
| Readers | the first of followers, watchers, follows, favorites, readers, bookmarks, kudos, ratings, reviews, likes that the novel records | 100 | 1.0 |
| Views | total views, else chapter views | 5,000 | 0.6 |
| Rating | the parsed score | none (plain change) | 0.6 |

Counts grow by `ln((to + k) / (from + k))`, with `k` the smoothing constant. The constant keeps
small numbers small: 2 → 5 patrons is a tiny rise, while 1,000 → 1,400 is a large one.

## Heat

1. **Decayed growth.** Each gap between two snapshots contributes its growth, spread evenly over
   the gap. Each day of growth is weighted by `0.5^(age / 6 days)`. Spreading the growth makes the
   sum independent of how often the novel is synced. The decay works like Reddit's hot ranking: a
   burst is worth half as much after 6 days and almost nothing after three weeks. No exposure
   history is stored, and a novel returns as soon as a new burst is recorded.
2. **Baseline discount.** 60% of the growth the novel's usual rate predicts over the same span is
   subtracted. The usual rate is half the novel's own rate before the last 14 days (when it has at
   least 10 days of older history) and half the library's median rate. A big novel that always
   grows at the same pace does not sit at the top forever. The rating has no baseline, because most
   ratings drift down with age, so any rise is news.
3. **Per-signal scaling.** Each signal's value becomes a strength `v / (v + s)`, where `s` is the
   library's 80th percentile of positive values for that signal (at least 0.03 for the rating).
   Patreon, readers and views then compete on equal terms, and no single source of numbers can
   fill the list.
4. **Combining.** Heat is the strongest weighted signal plus 35% of the others. A signal counts
   only when it ends the 14-day window above where it started, so a recovery from a dip is not a
   rise. Entries under heat 0.2 are dropped, and at most 30 are shown.

## Bookmark and creators

- A novel whose bookmark is on its last chapter is hidden until a new chapter arrives. The footer
  counts the hidden novels.
- Novels that link one Patreon creator share one Patreon history. Only the creator's hottest novel
  keeps the Patreon signal.

## Screen

- Chips: All, Patreon, Readers, Views, Rating. A filter lists every entry where that signal is
  rising (as lead or support), strongest on that signal first.
- All shows the #1 novel as a hero card: a large cover, the lead rise with its period, a 45-day
  sparkline and up to two other rising signals as pills. The rest are ranked rows with the lead
  rise, reading progress (the bookmark's ivory when in progress) and a small sparkline. At 600dp
  or wider the rows use two columns.
- "How Trending works" opens a bottom sheet with a plain-language version of this page.

## Tuning

Every constant is at the top of `TrendingPlanning`: `HALF_LIFE_DAYS`, `RECENT_WINDOW_DAYS`,
`BASELINE_DISCOUNT`, `SUPPORT_SHARE`, `SCALE_PERCENTILE`, `MIN_HEAT`, `MAX_ENTRIES`, and each
`Signal`'s weight and smoothing. These values were calibrated on the owner's 302-novel library
from 2026-11-22. In that library, 16 of the 30 entries had a Patreon rise, 12 a readers rise, 10 a
views rise and 3 a rating rise, and the top list changed visibly between dates two weeks apart.

Known gap: Royal Road novels currently record no source metrics (followers, views) in their
snapshots, so they can trend only on Patreon and rating.
