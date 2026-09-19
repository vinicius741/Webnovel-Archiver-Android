"""Pure statistics helpers for perf report aggregation."""

import math


def percentile(values, pct):
    """Linear-interpolation percentile (numpy 'linear' method). Returns None for empty input."""
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return float(ordered[0])
    rank = (len(ordered) - 1) * (pct / 100.0)
    low = int(rank // 1)
    high = min(low + 1, len(ordered) - 1)
    frac = rank - low
    return float(ordered[low] + (ordered[high] - ordered[low]) * frac)


def summarize(values):
    """Count/median/mean/stdev/min/max summary. None fields when not computable."""
    if not values:
        return {"count": 0, "median": None, "mean": None, "stdev": None, "min": None, "max": None}
    ordered = sorted(values)
    n = len(ordered)
    mean = sum(ordered) / n
    var = sum((v - mean) ** 2 for v in ordered) / n if n > 0 else 0.0
    return {
        "count": n,
        "median": percentile(ordered, 50),
        "mean": mean,
        "stdev": math.sqrt(var),
        "min": ordered[0],
        "max": ordered[-1],
    }


def coefficient_of_variation(values):
    """stdev/|mean| as a variability signal; None when undefined."""
    s = summarize(values)
    if not s["count"] or s["mean"] in (None, 0):
        return None
    return s["stdev"] / abs(s["mean"])


def slope(x_y_pairs):
    """Least-squares slope for memory-trend diagnostics; None when undefined."""
    pts = [(x, y) for x, y in x_y_pairs if y is not None]
    if len(pts) < 2:
        return None
    n = len(pts)
    mean_x = sum(p[0] for p in pts) / n
    mean_y = sum(p[1] for p in pts) / n
    denom = sum((p[0] - mean_x) ** 2 for p in pts)
    if denom == 0:
        return None
    return sum((p[0] - mean_x) * (p[1] - mean_y) for p in pts) / denom


def nanos_to_ms(delta_nanos):
    return delta_nanos / 1_000_000.0
