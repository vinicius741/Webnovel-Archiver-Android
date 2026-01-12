import { fetchPage } from '../../network/fetcher';

describe('fetchPage', () => {
    beforeEach(() => {
        jest.clearAllMocks();
    });

    it('should fetch page with user agent header', async () => {
        global.fetch = jest.fn().mockResolvedValue({
            ok: true,
            status: 200,
            text: () => Promise.resolve('<html><body>Test Content</body></html>'),
        }) as any;

        const result = await fetchPage('https://example.com');

        expect(fetch).toHaveBeenCalledWith('https://example.com', {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36',
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8',
            },
        });
        expect(result).toBe('<html><body>Test Content</body></html>');
    });

    it('should return HTML content on success', async () => {
        global.fetch = jest.fn().mockResolvedValue({
            ok: true,
            status: 200,
            text: () => Promise.resolve('<html><body>Content</body></html>'),
        }) as any;

        const result = await fetchPage('https://example.com');

        expect(result).toBe('<html><body>Content</body></html>');
    });

    it('should throw error on HTTP error status', async () => {
        global.fetch = jest.fn().mockResolvedValue({
            ok: false,
            status: 404,
            statusText: 'Not Found',
        }) as any;

        await expect(fetchPage('https://example.com')).rejects.toThrow('HTTP error! status: 404');
    });

    it('should throw error on network failure', async () => {
        global.fetch = jest.fn().mockRejectedValue(new Error('Network error')) as any;

        await expect(fetchPage('https://example.com')).rejects.toThrow('Network error');
    });

    it('should handle empty response', async () => {
        global.fetch = jest.fn().mockResolvedValue({
            ok: true,
            status: 200,
            text: () => Promise.resolve(''),
        }) as any;

        const result = await fetchPage('https://example.com');

        expect(result).toBe('');
    });

    it('should handle large HTML content', async () => {
        const largeContent = '<html><body>' + 'x'.repeat(100000) + '</body></html>';
        global.fetch = jest.fn().mockResolvedValue({
            ok: true,
            status: 200,
            text: () => Promise.resolve(largeContent),
        }) as any;

        const result = await fetchPage('https://example.com');

        expect(result).toBe(largeContent);
    });
});
