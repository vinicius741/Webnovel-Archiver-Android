import fs from "fs";
import os from "os";
import path from "path";
import { run, logStep } from "../utils";
import { SOURCE_DIRS, MIN_CLONE_LINES, MIN_CLONE_TOKENS } from "../constants";
import { DuplicationMetrics, JscpdReport } from "../types";

export const collectDuplicationMetrics = (): DuplicationMetrics => {
  logStep("Running duplicate-code scan...");
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "webnovel-quality-jscpd-"));

  try {
    const result = run("npx", [
      "jscpd",
      ...SOURCE_DIRS,
      "--format",
      "typescript,tsx",
      "--reporters",
      "json",
      "--output",
      outputDirectory,
      "--silent",
      "--gitignore",
      "--min-lines",
      String(MIN_CLONE_LINES),
      "--min-tokens",
      String(MIN_CLONE_TOKENS),
      "--exitCode",
      "0",
    ]);

    if (result.status !== 0) {
      throw new Error(result.stderr || result.stdout || "jscpd failed");
    }

    const reportPath = path.join(outputDirectory, "jscpd-report.json");
    const report = JSON.parse(fs.readFileSync(reportPath, "utf8")) as JscpdReport;
    const total = report.statistics?.total || {};

    return {
      duplicatedLines: total.duplicatedLines || 0,
      duplicatedPercentage: total.percentage || 0,
      clones: total.clones || 0,
      scannedLines: total.lines || 0,
      scannedFiles: total.sources || 0,
    };
  } finally {
    fs.rmSync(outputDirectory, { force: true, recursive: true });
  }
};
