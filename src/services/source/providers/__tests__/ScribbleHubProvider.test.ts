import { ScribbleHubProvider } from '../ScribbleHubProvider';
import { fetchPage } from '../../../network/fetcher';

// Mock fetchPage
jest.mock('../../../network/fetcher', () => ({
  fetchPage: jest.fn(),
}));

describe('ScribbleHubProvider', () => {
  const mockFetchPage = fetchPage as jest.Mock;
  const consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});

  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterEach(() => {
    consoleErrorSpy.mockClear();
  });

  afterAll(() => {
    consoleErrorSpy.mockRestore();
  });

  describe('isSource', () => {
    it('returns true for scribblehub.com urls', () => {
      expect(ScribbleHubProvider.isSource('https://www.scribblehub.com/series/123/title')).toBe(true);
    });

    it('returns false for other urls', () => {
      expect(ScribbleHubProvider.isSource('https://www.royalroad.com/fiction/123')).toBe(false);
    });
  });

  describe('getStoryId', () => {
    it('extracts ID from URL correctly', () => {
      expect(ScribbleHubProvider.getStoryId('https://www.scribblehub.com/series/123456/my-novel')).toBe('sh_123456');
    });

    it('generates ID if not found (fallback)', () => {
      expect(ScribbleHubProvider.getStoryId('https://www.scribblehub.com/foo/bar')).toMatch(/^sh_\d+$/);
    });
  });

  describe('parseMetadata', () => {
    const mockHtml = `
      <html>
        <body>
          <div class="fic_title">My Cool Novel</div>
          <span class="auth_name_fic">Jane Doe</span>
          <div class="fic_image"><img src="https://example.com/cover.jpg" /></div>
          <div class="wi_fic_desc">This is a description.</div>
          <a class="stag">Fantasy</a>
          <a class="stag">Adventure</a>
        </body>
      </html>
    `;

    it('parses metadata correctly', () => {
      const metadata = ScribbleHubProvider.parseMetadata(mockHtml);
      expect(metadata).toEqual({
        title: 'My Cool Novel',
        author: 'Jane Doe',
        coverUrl: 'https://example.com/cover.jpg',
        description: 'This is a description.',
        tags: ['Fantasy', 'Adventure'],
      });
    });

    it('handles missing fields gracefully', () => {
      const minimalHtml = '<html><body></body></html>';
      const metadata = ScribbleHubProvider.parseMetadata(minimalHtml);
      expect(metadata.title).toBe('Unknown Title');
      expect(metadata.author).toBe('Unknown Author');
      expect(metadata.coverUrl).toBeUndefined();
    });
  });

  describe('getChapterList', () => {
    it('parses a single page of chapters and reverses them', async () => {
        // Assuming site lists Newest first (e.g. Ch 2 then Ch 1)
      const html = `
        <html><body>
          <li class="toc_w"><a class="toc_a" href="c2">Chapter 2</a></li>
          <li class="toc_w"><a class="toc_a" href="c1">Chapter 1</a></li>
        </body></html>
      `;

      const chapters = await ScribbleHubProvider.getChapterList(html, 'https://www.scribblehub.com/series/123/');

      // Provider reverses the list, so we expect [Ch 1, Ch 2]
      expect(chapters).toHaveLength(2);
      expect(chapters[0].title).toBe('Chapter 1');
      expect(chapters[1].title).toBe('Chapter 2');
    });

    it('handles pagination', async () => {
        // Page 1: Chapter 2 (Newer)
        const htmlPage1 = `
          <html><body>
            <li class="toc_w"><a class="toc_a" href="c2">Chapter 2</a></li>
            <a class="page-link" href="?toc=2">»</a>
          </body></html>
        `;

        // Page 2: Chapter 1 (Older)
        const htmlPage2 = `
          <html><body>
            <li class="toc_w"><a class="toc_a" href="c1">Chapter 1</a></li>
            <!-- No next link -->
          </body></html>
        `;

        // Configure mock to return Page 2, then something that stops the loop (e.g. empty or no chapters)
        // If the code sees no chapters, it stops.
        mockFetchPage.mockResolvedValueOnce(htmlPage2);
        // Safety for subsequent calls if any (Strategy 2 might trigger if next link missing)
        mockFetchPage.mockResolvedValue('<html><body></body></html>');

        const chapters = await ScribbleHubProvider.getChapterList(htmlPage1, 'https://www.scribblehub.com/series/123/');

        // Should have called fetchPage at least once for page 2
        expect(mockFetchPage).toHaveBeenCalled();

        // Total chapters: 2
        expect(chapters).toHaveLength(2);

        // Order after reverse: Ch 1, Ch 2
        expect(chapters[0].title).toBe('Chapter 1');
        expect(chapters[1].title).toBe('Chapter 2');
    });
  });

  describe('parseChapterContent', () => {
    it('extracts content and removes ads', () => {
      const html = `
        <html><body>
          <div id="chp_raw">
            <p>Paragraph 1</p>
            <div class="rv_ad">Ad Content</div>
            <p>Paragraph 2</p>
          </div>
        </body></html>
      `;
      const content = ScribbleHubProvider.parseChapterContent(html);
      expect(content).toContain('Paragraph 1');
      expect(content).toContain('Paragraph 2');
      expect(content).not.toContain('Ad Content');
    });
  });
});
