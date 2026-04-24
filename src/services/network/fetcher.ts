const USER_AGENT =
  "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36";
const DEFAULT_ACCEPT =
  "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8";
const FORM_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
const SCRIBBLE_HUB_HOST = "www.scribblehub.com";
const SCRIBBLE_HUB_REQUEST_GAP_MS = 1500;
const SCRIBBLE_HUB_MAX_RETRIES = 3;
const SCRIBBLE_HUB_RETRY_BASE_DELAY_MS = 2500;

const nextAllowedRequestAtByHost = new Map<string, number>();

export class HttpError extends Error {
  status: number;
  url: string;

  constructor(status: number, url: string) {
    super(`HTTP error! status: ${status}`);
    this.name = "HttpError";
    this.status = status;
    this.url = url;
  }
}

const sleep = async (ms: number): Promise<void> => {
  await new Promise((resolve) => setTimeout(resolve, ms));
};

const getHost = (url: string): string | undefined => {
  try {
    return new URL(url).host;
  } catch {
    return undefined;
  }
};

const getRequestGapMs = (url: string): number => {
  return getHost(url) === SCRIBBLE_HUB_HOST ? SCRIBBLE_HUB_REQUEST_GAP_MS : 0;
};

const waitForRateLimitSlot = async (url: string): Promise<void> => {
  const host = getHost(url);
  if (!host) return;

  const gapMs = getRequestGapMs(url);
  if (gapMs <= 0) return;

  const now = Date.now();
  const nextAllowedAt = nextAllowedRequestAtByHost.get(host) ?? 0;

  if (nextAllowedAt > now) {
    await sleep(nextAllowedAt - now);
  }

  nextAllowedRequestAtByHost.set(host, Date.now() + gapMs);
};

const getRetryDelayMs = (attempt: number): number => {
  return SCRIBBLE_HUB_RETRY_BASE_DELAY_MS * attempt;
};

const shouldRetryRequest = (url: string, status: number, attempt: number): boolean => {
  return (
    getHost(url) === SCRIBBLE_HUB_HOST &&
    (status === 403 || status === 429) &&
    attempt < SCRIBBLE_HUB_MAX_RETRIES
  );
};

const fetchWithRetries = async (
  url: string,
  init: RequestInit,
  logLabel: string,
): Promise<string> => {
  for (let attempt = 1; ; attempt++) {
    await waitForRateLimitSlot(url);

    const response = await fetch(url, init);

    if (response.ok) {
      const html = await response.text();
      console.log(`${logLabel} Success. Length: ${html.length}`);
      return html;
    }

    if (shouldRetryRequest(url, response.status, attempt)) {
      const delayMs = getRetryDelayMs(attempt);
      console.warn(
        `${logLabel} Received ${response.status} for ${url}. Retrying in ${delayMs}ms (${attempt}/${SCRIBBLE_HUB_MAX_RETRIES})...`,
      );
      await sleep(delayMs);
      continue;
    }

    throw new HttpError(response.status, url);
  }
};

export const fetchPage = async (url: string): Promise<string> => {
  try {
    console.log(`[Fetcher] Requesting: ${url}`);
    return await fetchWithRetries(
      url,
      {
      headers: {
        "User-Agent": USER_AGENT,
        Accept: DEFAULT_ACCEPT,
        "Accept-Language": "en-US,en;q=0.9",
      },
      },
      "[Fetcher]",
    );
  } catch (error) {
    console.error("[Fetcher] Error:", error);
    throw error;
  }
};

export const fetchFormPage = async (
  url: string,
  data: Record<string, string | number>,
): Promise<string> => {
  try {
    console.log(`[Fetcher] Posting form: ${url}`);
    const body = new URLSearchParams(
      Object.entries(data).map(([key, value]) => [key, String(value)]),
    ).toString();

    return await fetchWithRetries(
      url,
      {
        method: "POST",
        headers: {
          "User-Agent": USER_AGENT,
          Accept: FORM_ACCEPT,
          "Accept-Language": "en-US,en;q=0.9",
          "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
          "X-Requested-With": "XMLHttpRequest",
        },
        body,
      },
      "[Fetcher] Form",
    );
  } catch (error) {
    console.error("[Fetcher] Form error:", error);
    throw error;
  }
};
