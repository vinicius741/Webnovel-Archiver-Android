import { load } from 'cheerio';

export interface NovelMetadata {
    title: string;
    author: string;
    coverUrl: string | null;
}

export const parseMetadata = (html: string): NovelMetadata => {
    const $ = load(html);

    // RoyalRoad specific selectors (can be expanded later)
    const title = $('h1').first().text().trim() || 'Unknown Title';

    // Attempt to find author in common spots
    let author = 'Unknown Author';
    const rrAuthor = $('h4.margin-bottom-20 span a').first().text().trim(); // RR often has this
    if (rrAuthor) author = rrAuthor;

    // Cover image
    let coverUrl: string | null = null;
    const rrCover = $('.page-content-inner .col-md-3 img').first().attr('src');
    if (rrCover) coverUrl = rrCover;

    // Generic fallback for cover
    if (!coverUrl) {
        coverUrl = $('meta[property="og:image"]').attr('content') || null;
    }

    return {
        title,
        author,
        coverUrl
    };
};
