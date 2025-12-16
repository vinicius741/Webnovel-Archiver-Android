import { load } from 'cheerio';
import { SourceProvider, NovelMetadata, ChapterInfo } from '../types';

export const RoyalRoadProvider: SourceProvider = {
    name: 'RoyalRoad',
    baseUrl: 'https://www.royalroad.com',

    isSource: (url: string) => {
        return url.includes('royalroad.com');
    },

    getStoryId: (url: string): string => {
        const rrMatch = url.match(/fiction\/(\d+)/);
        if (rrMatch) {
            return 'rr_' + rrMatch[1];
        }
        return 'rr_' + Date.now(); // Fallback
    },

    parseMetadata: (html: string): NovelMetadata => {
        const $ = load(html);

        const title = $('h1').first().text().trim() || 'Unknown Title';

        let author = 'Unknown Author';
        const rrAuthorLink = $('h4 a').first().text().trim();
        if (rrAuthorLink) {
            author = rrAuthorLink;
        } else {
            const rrAuthorText = $('h4').first().text().replace('Author:', '').trim();
            if (rrAuthorText && rrAuthorText.length > 0 && rrAuthorText !== 'Unknown Author') {
                author = rrAuthorText;
            }
        }

        if (author === 'Unknown Author') {
            const metaAuthor = $('meta[name="author"]').attr('content') ||
                $('meta[property="article:author"]').attr('content') ||
                $('meta[name="twitter:creator"]').attr('content');
            if (metaAuthor) author = metaAuthor;
        }

        let coverUrl: string | undefined;
        const rrCover = $('.page-content-inner .col-md-3 img').first().attr('src');
        if (rrCover) coverUrl = rrCover;

        if (!coverUrl) {
            coverUrl = $('meta[property="og:image"]').attr('content') || undefined;
        }

        let description: string | undefined;
        const rrDescription = $('.description').first().text().trim();
        if (rrDescription) {
            description = rrDescription;
        } else {
            description = $('meta[name="description"]').attr('content') ||
                $('meta[property="og:description"]').attr('content');
        }

        const tags: string[] = [];
        $('.tags .label, .tags a, .tag').each((_, el) => {
            const tag = $(el).text().trim();
            if (tag && tag.toLowerCase() !== 'tags') {
                tags.push(tag);
            }
        });

        const uniqueTags = Array.from(new Set(tags));

        let score: string | undefined;
        // Find the Overall Score list item, then get the score from the next list item
        const overallScoreLabel = $('.list-unstyled li.list-item:contains("Overall Score")').first();
        if (overallScoreLabel.length > 0) {
            const scoreItem = overallScoreLabel.next('li.list-item');
            const scoreSpan = scoreItem.find('span.star');
            const scoreText = scoreSpan.attr('data-content'); // e.g., "4.6 / 5"
            if (scoreText) {
                score = scoreText;
            }
        }

        return {
            title,
            author,
            coverUrl,
            description,
            tags: uniqueTags.length > 0 ? uniqueTags : undefined,
            score
        };
    },

    getChapterList: async (html: string, baseUrl: string, onProgress?: (message: string) => void): Promise<ChapterInfo[]> => {
        if (onProgress) onProgress('Parsing chapter list...');
        const $ = load(html);
        const chapters: ChapterInfo[] = [];

        $('.chapter-row').each((_, element) => {
            const link = $(element).find('a[href*="/fiction/"]');
            if (link.length > 0) {
                const title = link.text().trim();
                let relativeUrl = link.attr('href') || '';

                if (relativeUrl && !relativeUrl.startsWith('http')) {
                    if (relativeUrl.startsWith('/')) {
                        const urlObj = new URL(baseUrl);
                        relativeUrl = `${urlObj.origin}${relativeUrl}`;
                    } else {
                        relativeUrl = `${baseUrl}/${relativeUrl}`;
                    }
                }

                chapters.push({
                    title,
                    url: relativeUrl
                });
            }
        });

        return chapters;
    },

    parseChapterContent: (html: string): string => {
        const $ = load(html);
        const content = $('.chapter-inner');

        content.find('div.portlet').remove();
        content.find('script').remove();
        content.find('.bold.uppercase.text-center').remove();

        return content.html() || 'No content found';
    }
};
