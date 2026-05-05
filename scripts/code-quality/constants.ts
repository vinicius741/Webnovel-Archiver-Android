import path from "path";

export const ROOT = path.resolve(__dirname, "..", "..");
export const BASELINE_PATH = path.join(ROOT, "quality-baseline.json");
export const REPORT_DIR = path.join(ROOT, "quality-report");
export const REPORT_PATH = path.join(REPORT_DIR, "code-quality-report.json");

export const SOURCE_DIRS = ["app", "src", "modules/tts-media-session/src"];
export const SOURCE_EXTENSIONS = new Set([".ts", ".tsx"]);
export const EXCLUDED_DIRS = new Set(["node_modules", "build", "dist", "coverage", ".expo"]);

export const OVERSIZED_FILE_LINE_THRESHOLD = 500;
export const DUPLICATION_PERCENT_EPSILON = 0.001;
export const TOP_LARGEST_FILES_COUNT = 10;
export const SUMMARY_LARGEST_FILES_COUNT = 5;
export const MIN_CLONE_LINES = 5;
export const MIN_CLONE_TOKENS = 50;
