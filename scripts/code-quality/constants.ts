import path from "path";

export const ROOT = path.resolve(__dirname, "..", "..");
export const BASELINE_PATH = path.join(ROOT, "quality-baseline.json");
export const REPORT_DIR = path.join(ROOT, "quality-report");
export const REPORT_PATH = path.join(REPORT_DIR, "code-quality-report.json");

export const SOURCE_DIRS = ["app", "src", "modules/tts-media-session/src"];
export const SOURCE_EXTENSIONS = new Set([".ts", ".tsx"]);
export const EXCLUDED_DIRS = new Set(["node_modules", "build", "dist", "coverage", ".expo"]);

/** Directory name segments that indicate test files. */
export const TEST_DIR_SEGMENTS = new Set(["__tests__", "__mocks__", "__test_helpers__"]);

/** Glob patterns for test files, used by external tools (jscpd, madge, knip). */
export const TEST_IGNORE_PATTERNS = [
  "**/__tests__/**",
  "**/__mocks__/**",
  "**/__test_helpers__/**",
  "**/*.test.ts",
  "**/*.test.tsx",
  "**/*.spec.ts",
  "**/*.spec.tsx",
];

export const OVERSIZED_FILE_LINE_THRESHOLD = 500;
export const DUPLICATION_PERCENT_EPSILON = 0.001;
export const TOP_LARGEST_FILES_COUNT = 10;
export const SUMMARY_LARGEST_FILES_COUNT = 5;
export const MIN_CLONE_LINES = 5;
export const MIN_CLONE_TOKENS = 50;
