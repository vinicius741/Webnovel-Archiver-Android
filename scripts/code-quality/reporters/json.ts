import fs from "fs";
import path from "path";
import { ROOT, REPORT_DIR, REPORT_PATH } from "../constants";
import { QualityMetrics } from "../types";

export const writeReport = (metrics: QualityMetrics): void => {
  fs.mkdirSync(REPORT_DIR, { recursive: true });
  fs.writeFileSync(REPORT_PATH, `${JSON.stringify(metrics, null, 2)}\n`);
};

export const printReportLocation = (): void => {
  console.log(`Report written to ${path.relative(ROOT, REPORT_PATH)}`);
};
