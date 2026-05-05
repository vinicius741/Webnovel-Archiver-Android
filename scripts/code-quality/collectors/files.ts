import { logStep, walkFiles, countLines } from "../utils";
import { SOURCE_DIRS, OVERSIZED_FILE_LINE_THRESHOLD, TOP_LARGEST_FILES_COUNT } from "../constants";
import { FileMetrics } from "../types";

export const collectFileMetrics = (): FileMetrics => {
  logStep("Counting source files and lines...");

  const files = SOURCE_DIRS.flatMap(walkFiles).sort();
  const fileLineCounts = files
    .map((file) => ({ file, lines: countLines(file) }))
    .sort((a, b) => b.lines - a.lines || a.file.localeCompare(b.file));

  const totalLines = fileLineCounts.reduce((sum, file) => sum + file.lines, 0);
  const oversizedFiles = fileLineCounts.filter(
    (file) => file.lines > OVERSIZED_FILE_LINE_THRESHOLD,
  );

  return {
    sourceFiles: files.length,
    totalLines,
    largestFileLines: fileLineCounts[0]?.lines || 0,
    largestFiles: fileLineCounts.slice(0, TOP_LARGEST_FILES_COUNT),
    oversizedFileLineThreshold: OVERSIZED_FILE_LINE_THRESHOLD,
    oversizedFileCount: oversizedFiles.length,
    oversizedFiles,
  };
};
