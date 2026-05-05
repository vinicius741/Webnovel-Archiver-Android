import { collectFileMetrics } from "./files";
import { collectDuplicationMetrics } from "./duplication";
import { collectCircularDependencyMetrics } from "./circular-deps";
import { collectUnusedCodeMetrics } from "./unused-code";
import { SOURCE_DIRS } from "../constants";
import { QualityMetrics } from "../types";

export const collectMetrics = (): QualityMetrics => ({
  generatedAt: new Date().toISOString(),
  sourceDirs: SOURCE_DIRS,
  duplication: collectDuplicationMetrics(),
  files: collectFileMetrics(),
  dependencies: collectCircularDependencyMetrics(),
  unusedCode: collectUnusedCodeMetrics(),
});
