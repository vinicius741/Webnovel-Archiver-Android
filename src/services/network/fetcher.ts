const USER_AGENT = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36';

export const fetchPage = async (url: string): Promise<string> => {
    try {
        console.log(`[Fetcher] Requesting: ${url}`);
        const response = await fetch(url, {
            headers: {
                'User-Agent': USER_AGENT,
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8',
            },
        });

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const html = await response.text();
        console.log(`[Fetcher] Success. Length: ${html.length}`);
        return html;
    } catch (error) {
        console.error('[Fetcher] Error:', error);
        throw error;
    }
};
