import { RoyalRoadProvider } from '../RoyalRoadProvider';

describe('RoyalRoadProvider', () => {
    const mockHtml = `
<!DOCTYPE html>
<html>
<head>
    <title>Gunwitch: LitRPG Adventure | Royal Road</title>
    <link rel="canonical" href="https://www.royalroad.com/fiction/123/gunwitch-litrpg-adventure">
    <meta name="author" content="Test Author">
    <meta property="og:description" content="A great story about guns and witches.">
</head>
<body>
    <div class="page-content-inner">
        <div class="col-md-3">
             <img src="https://example.com/cover.jpg" />
        </div>
        <h1>Gunwitch: LitRPG Adventure</h1>
        <h4>Author: Test Author</h4>

        <div class="tags">
            <span class="label">LitRPG</span>
            <span class="label">Fantasy</span>
        </div>

        <div class="stats-content">
            <div class="col-sm-6">
                <ul class="list-unstyled">
                    <li class="bold uppercase list-item">Overall Score</li>
                    <li class="list-item">
                        <span class="font-red-sunglo popovers star" data-content=" 4.84 / 5 " aria-label="4.84 stars"></span>
                    </li>
                </ul>
            </div>
        </div>

        <div class="description">
            <p>A great story about guns and witches.</p>
        </div>
    </div>
</body>
</html>
    `;

    it('should parse metadata correctly including score', () => {
        const metadata = RoyalRoadProvider.parseMetadata(mockHtml);

        expect(metadata.title).toBe('Gunwitch: LitRPG Adventure');
        expect(metadata.author).toBe('Test Author');
        expect(metadata.score).toBe('4.84 / 5');
        expect(metadata.tags).toContain('LitRPG');
        expect(metadata.tags).toContain('Fantasy');
        expect(metadata.canonicalUrl).toBe('https://www.royalroad.com/fiction/123/gunwitch-litrpg-adventure');
    });

    it('should return undefined score if not found', () => {
        const htmlNoScore = `<html><body><h1>Title</h1><h4>Author</h4></body></html>`;
        const metadata = RoyalRoadProvider.parseMetadata(htmlNoScore);
        expect(metadata.score).toBeUndefined();
    });

    it('should parse chapter list correctly and exclude post time', async () => {
        const chapterHtml = `
            <table>
                <tr class="chapter-row">
                    <td>
                        <a href="/fiction/140335/i-became-a-paladin-girl/chapter/2768745/chapter-1-hope-armstrong">
                            Chapter 1. Hope Armstrong
                        </a>
                    </td>
                    <td>
                        <a href="/fiction/140335/i-became-a-paladin-girl/chapter/2768745/chapter-1-hope-armstrong">
                            <time>1 month </time> ago
                        </a>
                    </td>
                </tr>
            </table>
        `;
        const chapters = await RoyalRoadProvider.getChapterList(chapterHtml, 'https://www.royalroad.com');

        expect(chapters).toHaveLength(1);
        expect(chapters[0].title).toBe('Chapter 1. Hope Armstrong');
        expect(chapters[0].url).toBe('https://www.royalroad.com/fiction/140335/i-became-a-paladin-girl/chapter/2768745/chapter-1-hope-armstrong');
        expect(chapters[0].id).toBe('2768745');
    });
});
