export interface FileLineCount {
  file: string;
  lines: number;
}

export interface DuplicationMetrics {
  duplicatedLines: number;
  duplicatedPercentage: number;
  clones: number;
  scannedLines: number;
  scannedFiles: number;
}

export interface FileMetrics {
  sourceFiles: number;
  totalLines: number;
  largestFileLines: number;
  largestFiles: FileLineCount[];
  oversizedFileLineThreshold: number;
  oversizedFileCount: number;
  oversizedFiles: FileLineCount[];
}

export interface CircularDependencyMetrics {
  circularDependencyCount: number;
  circularDependencies: string[][];
}

export interface UnusedCodeMetrics {
  unusedCodeIssueCount: number;
  unusedCodeFilesWithIssues: number;
}

export interface QualityMetrics {
  generatedAt: string;
  sourceDirs: string[];
  duplication: DuplicationMetrics;
  files: FileMetrics;
  dependencies: CircularDependencyMetrics;
  unusedCode: UnusedCodeMetrics;
}

export interface ComparableMetrics {
  duplicatedLines: number;
  duplicatedPercentage: number;
  largestFileLines: number;
  oversizedFileCount: number;
  circularDependencyCount: number;
  unusedCodeIssueCount: number;
}

export interface MetricChange {
  metric: string;
  baseline: number;
  current: number;
}

export type Mode = "check" | "report" | "baseline";

export interface JscpdReport {
  statistics?: {
    total?: {
      duplicatedLines?: number;
      percentage?: number;
      clones?: number;
      lines?: number;
      sources?: number;
    };
  };
}

export interface KnipIssue {
  file: string;
  [key: string]: string[] | string;
}

export interface KnipReport {
  issues?: KnipIssue[];
}
