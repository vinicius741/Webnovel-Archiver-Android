import { HttpError } from "../network/fetcher";
import { DownloadErrorCategory, DownloadJob } from "./types";

const DEFAULT_MAX_RETRIES = 3;
const RETRY_BASE_DELAY_MS = 3000;
const RETRY_MAX_DELAY_MS = 60000;

export interface ClassifiedDownloadError {
  message: string;
  category: DownloadErrorCategory;
  code?: string;
  retryable: boolean;
}

const classified = (
  message: string,
  category: DownloadErrorCategory,
  code: string,
  retryable: boolean,
): ClassifiedDownloadError => ({
  message,
  category,
  code,
  retryable,
});

const errorFromMessage = (error: Error): ClassifiedDownloadError | undefined => {
  const lowerMessage = error.message.toLowerCase();
  const rules: Array<{
    matches: boolean;
    category: DownloadErrorCategory;
    code: string;
    retryable: boolean;
    message?: string;
  }> = [
    {
      matches: error.name === "AbortError" || lowerMessage.includes("abort"),
      category: "cancelled",
      code: "CANCELLED",
      retryable: false,
      message: "Cancelled",
    },
    {
      matches: lowerMessage.includes("story not found"),
      category: "missing_story",
      code: "STORY_NOT_FOUND",
      retryable: false,
    },
    {
      matches: lowerMessage.includes("no provider"),
      category: "missing_provider",
      code: "NO_PROVIDER",
      retryable: false,
    },
    {
      matches: lowerMessage.includes("no url"),
      category: "invalid_chapter",
      code: "NO_CHAPTER_URL",
      retryable: false,
    },
    {
      matches: lowerMessage.includes("empty") || lowerMessage.includes("too short"),
      category: "parse",
      code: "CONTENT_TOO_SHORT",
      retryable: true,
    },
    {
      matches:
        lowerMessage.includes("network") ||
        lowerMessage.includes("timeout") ||
        lowerMessage.includes("failed to fetch"),
      category: "network",
      code: "NETWORK_ERROR",
      retryable: true,
    },
  ];

  const match = rules.find((rule) => rule.matches);
  if (!match) return undefined;

  return classified(
    match.message ?? error.message,
    match.category,
    match.code,
    match.retryable,
  );
};

export const classifyDownloadError = (
  error: unknown,
): ClassifiedDownloadError => {
  if (error instanceof HttpError) {
    const isRateLimit = error.status === 403 || error.status === 429;
    return classified(
      `HTTP ${error.status}`,
      isRateLimit ? "rate_limit" : "network",
      String(error.status),
      isRateLimit || [408, 500, 502, 503, 504].includes(error.status),
    );
  }

  if (error instanceof Error) {
    return (
      errorFromMessage(error) ??
      classified(error.message, "unknown", error.name || "UNKNOWN", false)
    );
  }

  return classified("Unknown error", "unknown", "UNKNOWN", false);
};

export const shouldAutoRetryDownload = (
  job: DownloadJob,
  error: ClassifiedDownloadError,
): boolean => {
  if (!error.retryable) return false;
  return (job.retryCount ?? 0) < (job.maxRetries ?? DEFAULT_MAX_RETRIES);
};

export const getDownloadRetryDelayMs = (job: DownloadJob): number => {
  const retryAttempt = (job.retryCount ?? 0) + 1;
  return Math.min(
    RETRY_MAX_DELAY_MS,
    RETRY_BASE_DELAY_MS * 2 ** Math.max(0, retryAttempt - 1),
  );
};
