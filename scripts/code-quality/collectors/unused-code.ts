import { run, logStep } from "../utils";
import { KnipIssue, KnipReport, UnusedCodeMetrics } from "../types";

const countKnipIssues = (issues: KnipIssue[]): number => {
  return issues.reduce((sum, issue) => {
    return Object.entries(issue).reduce((issueSum, [key, value]) => {
      if (key === "file" || !Array.isArray(value)) {
        return issueSum;
      }
      return issueSum + value.length;
    }, sum);
  }, 0);
};

export const collectUnusedCodeMetrics = (): UnusedCodeMetrics => {
  logStep("Checking unused files, exports, and dependencies...");

  const result = run("npx", [
    "knip",
    "--reporter",
    "json",
    "--no-progress",
    "--no-exit-code",
    "--max-show-issues",
    "100000",
  ]);

  if (result.status !== 0) {
    throw new Error(result.stderr || result.stdout || "knip failed");
  }

  const report = result.stdout.trim()
    ? (JSON.parse(result.stdout) as KnipReport)
    : { issues: [] };

  const issues = report.issues || [];

  return {
    unusedCodeIssueCount: countKnipIssues(issues),
    unusedCodeFilesWithIssues: issues.length,
  };
};
