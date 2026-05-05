import fs from "fs";
import path from "path";
import { ROOT, BASELINE_PATH, DUPLICATION_PERCENT_EPSILON } from "./constants";
import { QualityMetrics, ComparableMetrics, MetricChange } from "./types";

const comparableMetrics = (metrics: QualityMetrics): ComparableMetrics => ({
  duplicatedLines: metrics.duplication.duplicatedLines,
  duplicatedPercentage: metrics.duplication.duplicatedPercentage,
  largestFileLines: metrics.files.largestFileLines,
  oversizedFileCount: metrics.files.oversizedFileCount,
  circularDependencyCount: metrics.dependencies.circularDependencyCount,
  unusedCodeIssueCount: metrics.unusedCode.unusedCodeIssueCount,
});

export const findRegressions = (current: QualityMetrics, baseline: QualityMetrics): MetricChange[] => {
  const currentComparable = comparableMetrics(current);
  const baselineComparable = comparableMetrics(baseline);
  const regressions: MetricChange[] = [];

  for (const [key, currentValue] of Object.entries(currentComparable)) {
    const baselineValue = baselineComparable[key as keyof ComparableMetrics] ?? 0;
    const tolerance = key === "duplicatedPercentage" ? DUPLICATION_PERCENT_EPSILON : 0;

    if (currentValue > baselineValue + tolerance) {
      regressions.push({ metric: key, baseline: baselineValue, current: currentValue });
    }
  }

  return regressions;
};

export const findImprovements = (current: QualityMetrics, baseline: QualityMetrics): MetricChange[] => {
  const currentComparable = comparableMetrics(current);
  const baselineComparable = comparableMetrics(baseline);

  return Object.entries(currentComparable)
    .filter(([key, currentValue]) => currentValue < (baselineComparable[key as keyof ComparableMetrics] ?? 0))
    .map(([metric, currentValue]) => ({
      metric,
      baseline: baselineComparable[metric as keyof ComparableMetrics],
      current: currentValue,
    }));
};

export const loadBaseline = (): QualityMetrics | null => {
  if (!fs.existsSync(BASELINE_PATH)) {
    return null;
  }
  return JSON.parse(fs.readFileSync(BASELINE_PATH, "utf8")) as QualityMetrics;
};

export const writeBaseline = (metrics: QualityMetrics): void => {
  fs.writeFileSync(BASELINE_PATH, `${JSON.stringify(metrics, null, 2)}\n`);
  console.log(`Baseline written to ${path.relative(ROOT, BASELINE_PATH)}`);
};
