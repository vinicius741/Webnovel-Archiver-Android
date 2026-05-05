import { QualityMetrics } from "../types";
import { writeReport, printReportLocation } from "./json";
import {
  printSummary,
  printRegressions,
  printImprovements,
  printSuccess,
} from "./console";
import {
  loadBaseline,
  writeBaseline,
  findRegressions,
  findImprovements,
} from "../baseline";

export const handleReportMode = (metrics: QualityMetrics): void => {
  writeReport(metrics);
  printSummary(metrics);
  console.log("");
  printReportLocation();
};

export const handleBaselineMode = (metrics: QualityMetrics): void => {
  handleReportMode(metrics);
  writeBaseline(metrics);
};

export const handleCheckMode = (metrics: QualityMetrics): void => {
  handleReportMode(metrics);

  const baseline = loadBaseline();
  if (!baseline) {
    console.error("Missing baseline. Run npm run quality:baseline first.");
    process.exit(1);
  }

  const regressions = findRegressions(metrics, baseline);
  const improvements = findImprovements(metrics, baseline);

  if (regressions.length > 0) {
    printRegressions(regressions);
    process.exit(1);
  }

  if (improvements.length > 0) {
    printImprovements(improvements);
  }

  printSuccess();
};
