#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const BASELINE_PATH = path.join(ROOT, "quality-baseline.json");
const REPORT_DIR = path.join(ROOT, "quality-report");
const REPORT_PATH = path.join(REPORT_DIR, "code-quality-report.json");
const SOURCE_DIRS = ["app", "src", "modules/tts-media-session/src"];
const SOURCE_EXTENSIONS = new Set([".ts", ".tsx"]);
const OVERSIZED_FILE_LINE_THRESHOLD = 500;
const DUPLICATION_PERCENT_EPSILON = 0.001;

const mode = process.argv[2] || "check";

const logStep = (message) => {
  console.log(`[quality] ${message}`);
};

const run = (command, args, options = {}) => {
  const result = spawnSync(command, args, {
    cwd: ROOT,
    encoding: "utf8",
    shell: process.platform === "win32",
    ...options,
  });

  if (result.error) {
    throw result.error;
  }

  return result;
};

const isSourceFile = (filePath) => SOURCE_EXTENSIONS.has(path.extname(filePath));

const walkFiles = (directory) => {
  const absoluteDirectory = path.join(ROOT, directory);
  if (!fs.existsSync(absoluteDirectory)) {
    return [];
  }

  const files = [];
  const entries = fs.readdirSync(absoluteDirectory, { withFileTypes: true });

  for (const entry of entries) {
    const absolutePath = path.join(absoluteDirectory, entry.name);
    const relativePath = path.relative(ROOT, absolutePath);

    if (entry.isDirectory()) {
      if (["node_modules", "build", "dist", "coverage", ".expo"].includes(entry.name)) {
        continue;
      }

      files.push(...walkFiles(relativePath));
      continue;
    }

    if (entry.isFile() && isSourceFile(absolutePath) && !absolutePath.endsWith(".d.ts")) {
      files.push(relativePath);
    }
  }

  return files;
};

const countLines = (filePath) => {
  const content = fs.readFileSync(path.join(ROOT, filePath), "utf8");
  if (content.length === 0) {
    return 0;
  }

  return content.split(/\r\n|\r|\n/).length;
};

const collectFileMetrics = () => {
  logStep("Counting source files and lines...");
  const files = SOURCE_DIRS.flatMap(walkFiles).sort();
  const fileLineCounts = files
    .map((file) => ({ file, lines: countLines(file) }))
    .sort((a, b) => b.lines - a.lines || a.file.localeCompare(b.file));
  const totalLines = fileLineCounts.reduce((sum, file) => sum + file.lines, 0);
  const oversizedFiles = fileLineCounts.filter((file) => file.lines > OVERSIZED_FILE_LINE_THRESHOLD);

  return {
    sourceFiles: files.length,
    totalLines,
    largestFileLines: fileLineCounts[0]?.lines || 0,
    largestFiles: fileLineCounts.slice(0, 10),
    oversizedFileLineThreshold: OVERSIZED_FILE_LINE_THRESHOLD,
    oversizedFileCount: oversizedFiles.length,
    oversizedFiles,
  };
};

const collectDuplicationMetrics = () => {
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
      "5",
      "--min-tokens",
      "50",
      "--exitCode",
      "0",
    ]);

    if (result.status !== 0) {
      throw new Error(result.stderr || result.stdout || "jscpd failed");
    }

    const reportPath = path.join(outputDirectory, "jscpd-report.json");
    const report = JSON.parse(fs.readFileSync(reportPath, "utf8"));
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

const collectCircularDependencyMetrics = () => {
  logStep("Checking circular dependencies...");
  const result = run("npx", [
    "madge",
    ...SOURCE_DIRS,
    "--extensions",
    "ts,tsx",
    "--ts-config",
    "tsconfig.json",
    "--circular",
    "--json",
    "--no-spinner",
  ]);

  if (result.stderr.trim()) {
    console.warn(result.stderr.trim());
  }

  const output = result.stdout.trim();
  const circularDependencies = output ? JSON.parse(output) : [];

  return {
    circularDependencyCount: circularDependencies.length,
    circularDependencies,
  };
};

const countKnipIssues = (issues) => {
  return issues.reduce((sum, issue) => {
    return Object.entries(issue).reduce((issueSum, [key, value]) => {
      if (key === "file" || !Array.isArray(value)) {
        return issueSum;
      }

      return issueSum + value.length;
    }, sum);
  }, 0);
};

const collectUnusedCodeMetrics = () => {
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

  const report = result.stdout.trim() ? JSON.parse(result.stdout) : { issues: [] };
  const issues = report.issues || [];

  return {
    unusedCodeIssueCount: countKnipIssues(issues),
    unusedCodeFilesWithIssues: issues.length,
  };
};

const collectMetrics = () => ({
  generatedAt: new Date().toISOString(),
  sourceDirs: SOURCE_DIRS,
  duplication: collectDuplicationMetrics(),
  files: collectFileMetrics(),
  dependencies: collectCircularDependencyMetrics(),
  unusedCode: collectUnusedCodeMetrics(),
});

const comparableMetrics = (metrics) => ({
  duplicatedLines: metrics.duplication.duplicatedLines,
  duplicatedPercentage: metrics.duplication.duplicatedPercentage,
  largestFileLines: metrics.files.largestFileLines,
  oversizedFileCount: metrics.files.oversizedFileCount,
  circularDependencyCount: metrics.dependencies.circularDependencyCount,
  unusedCodeIssueCount: metrics.unusedCode.unusedCodeIssueCount,
});

const findRegressions = (current, baseline) => {
  const currentComparable = comparableMetrics(current);
  const baselineComparable = comparableMetrics(baseline);
  const regressions = [];

  for (const [key, currentValue] of Object.entries(currentComparable)) {
    const baselineValue = baselineComparable[key] ?? 0;
    const tolerance = key === "duplicatedPercentage" ? DUPLICATION_PERCENT_EPSILON : 0;

    if (currentValue > baselineValue + tolerance) {
      regressions.push({ metric: key, baseline: baselineValue, current: currentValue });
    }
  }

  return regressions;
};

const findImprovements = (current, baseline) => {
  const currentComparable = comparableMetrics(current);
  const baselineComparable = comparableMetrics(baseline);

  return Object.entries(currentComparable)
    .filter(([key, currentValue]) => currentValue < (baselineComparable[key] ?? 0))
    .map(([metric, currentValue]) => ({
      metric,
      baseline: baselineComparable[metric],
      current: currentValue,
    }));
};

const formatMetricName = (metric) =>
  metric
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .toLowerCase();

const printSummary = (metrics) => {
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
  for (const file of metrics.files.largestFiles.slice(0, 5)) {
    console.log(`- ${file.file}: ${file.lines} lines`);
  }
};

const writeReport = (metrics) => {
  fs.mkdirSync(REPORT_DIR, { recursive: true });
  fs.writeFileSync(REPORT_PATH, `${JSON.stringify(metrics, null, 2)}\n`);
};

const writeBaseline = (metrics) => {
  fs.writeFileSync(BASELINE_PATH, `${JSON.stringify(metrics, null, 2)}\n`);
};

const main = () => {
  if (!["check", "report", "baseline"].includes(mode)) {
    console.error("Usage: node scripts/code-quality.js [check|report|baseline]");
    process.exit(2);
  }

  const metrics = collectMetrics();
  writeReport(metrics);
  printSummary(metrics);
  console.log("");
  console.log(`Report written to ${path.relative(ROOT, REPORT_PATH)}`);

  if (mode === "baseline") {
    writeBaseline(metrics);
    console.log(`Baseline written to ${path.relative(ROOT, BASELINE_PATH)}`);
    return;
  }

  if (mode === "report") {
    return;
  }

  if (!fs.existsSync(BASELINE_PATH)) {
    console.error(`Missing ${path.relative(ROOT, BASELINE_PATH)}. Run npm run quality:baseline first.`);
    process.exit(1);
  }

  const baseline = JSON.parse(fs.readFileSync(BASELINE_PATH, "utf8"));
  const regressions = findRegressions(metrics, baseline);
  const improvements = findImprovements(metrics, baseline);

  if (regressions.length > 0) {
    console.error("");
    console.error("Code quality regressed against baseline:");
    for (const regression of regressions) {
      console.error(
        `- ${formatMetricName(regression.metric)}: ${regression.baseline} -> ${regression.current}`,
      );
    }
    console.error("");
    console.error("Improve the affected metrics before committing. Refresh the baseline only for intentional maintenance.");
    process.exit(1);
  }

  if (improvements.length > 0) {
    console.log("");
    console.log("Quality improved versus baseline:");
    for (const improvement of improvements) {
      console.log(`- ${formatMetricName(improvement.metric)}: ${improvement.baseline} -> ${improvement.current}`);
    }
    console.log("Consider refreshing the baseline with npm run quality:baseline.");
  }

  console.log("");
  console.log("Code quality is at or above baseline.");
};

main();
