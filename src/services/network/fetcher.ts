const USER_AGENT =
  "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36";

export const fetchPage = async (url: string): Promise<string> => {
  try {
    console.log(`[Fetcher] Requesting: ${url}`);
    const response = await fetch(url, {
      headers: {
        "User-Agent": USER_AGENT,
        Accept:
          "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
      },
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const html = await response.text();
    console.log(`[Fetcher] Success. Length: ${html.length}`);
    return html;
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

    const response = await fetch(url, {
      method: "POST",
      headers: {
        "User-Agent": USER_AGENT,
        Accept:
          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With": "XMLHttpRequest",
      },
      body,
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const html = await response.text();
    console.log(`[Fetcher] Form success. Length: ${html.length}`);
    return html;
  } catch (error) {
    console.error("[Fetcher] Form error:", error);
    throw error;
  }
};
