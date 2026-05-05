import { QualityMetrics, MetricChange } from "../types";
import { SUMMARY_LARGEST_FILES_COUNT } from "../constants";

const formatMetricName = (metric: string): string =>
  metric
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .toLowerCase();

export const printSummary = (metrics: QualityMetrics): void => {
  console.log("Code quality metrics");
  console.log("--------------------");
  console.log(`Duplicate lines: ${metrics.duplication.duplicatedLines}`);
  console.log(`Duplicate percentage: ${metrics.duplication.duplicatedPercentage}%`);
  console.log(`Duplicate clones: ${metrics.duplication.clones}`);
  console.log(`Source files: ${metrics.files.sourceFiles}`);
  console.log(`Total lines: ${metrics.files.totalLines}`);
  console.log(`Largest file: ${metrics.files.largestFiles[0]?.file || "n/a"} (${metrics.files.largestFileLines} lines)`);
  console.log(
    `Oversized files > ${metrics.files.oversizedFileLineThreshold} lines: ${metrics.files.oversizedFileCount}`,
  );
  console.log(`Circular dependencies: ${metrics.dependencies.circularDependencyCount}`);
  console.log(`Knip issues: ${metrics.unusedCode.unusedCodeIssueCount}`);
  console.log("");
  console.log("Largest files:");
  for (const file of metrics.files.largestFiles.slice(0, SUMMARY_LARGEST_FILES_COUNT)) {
    console.log(`- ${file.file}: ${file.lines} lines`);
  }
};

export const printRegressions = (regressions: MetricChange[]): void => {
  console.error("");
  console.error("Code quality regressed against baseline:");
  for (const regression of regressions) {
    console.error(
      `- ${formatMetricName(regression.metric)}: ${regression.baseline} -> ${regression.current}`,
    );
  }
  console.error("");
  console.error("Improve the affected metrics before committing. Refresh the baseline only for intentional maintenance.");
};

export const printImprovements = (improvements: MetricChange[]): void => {
  console.log("");
  console.log("Quality improved versus baseline:");
  for (const improvement of improvements) {
    console.log(`- ${formatMetricName(improvement.metric)}: ${improvement.baseline} -> ${improvement.current}`);
  }
  console.log("Consider refreshing the baseline with npm run quality:baseline.");
};

export const printSuccess = (): void => {
  console.log("");
  console.log("Code quality is at or above baseline.");
};
