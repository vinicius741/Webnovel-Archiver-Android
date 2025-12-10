import { load } from 'cheerio';


export interface NovelMetadata {
    title: string;
    author: string;
    coverUrl?: string;
    description?: string;
}

export const parseMetadata = (html: string): NovelMetadata => {
    const $ = load(html);

    // RoyalRoad specific selectors (can be expanded later)
    const title = $('h1').first().text().trim() || 'Unknown Title';

    // Attempt to find author in common spots
    let author = 'Unknown Author';

    // 1. Common Royal Road Author Link (often in h4)
    const rrAuthorLink = $('h4 a').first().text().trim();
    if (rrAuthorLink) {
        author = rrAuthorLink;
    }
    // 2. Different RR layout (sometimes just text in h4 or similar)
    else {
        const rrAuthorText = $('h4').first().text().replace('Author:', '').trim();
        if (rrAuthorText && rrAuthorText.length > 0 && rrAuthorText !== 'Unknown Author') {
            author = rrAuthorText;
        }
    }

    // 3. Meta tags fallback (Generic)
    if (author === 'Unknown Author') {
        const metaAuthor = $('meta[name="author"]').attr('content') ||
            $('meta[property="article:author"]').attr('content') ||
            $('meta[name="twitter:creator"]').attr('content');
        if (metaAuthor) author = metaAuthor;
    }

    // Cover image
    let coverUrl: string | undefined;
    const rrCover = $('.page-content-inner .col-md-3 img').first().attr('src');
    if (rrCover) coverUrl = rrCover;

    // Generic fallback for cover
    if (!coverUrl) {
        coverUrl = $('meta[property="og:image"]').attr('content') || undefined;
    }

    // Description
    let description: string | undefined;

    // RoyalRoad description
    const rrDescription = $('.description').first().text().trim();
    if (rrDescription) {
        description = rrDescription;
    }
    // Generic meta description
    else {
        description = $('meta[name="description"]').attr('content') ||
            $('meta[property="og:description"]').attr('content');
    }

    return {
        title,
        author,
        coverUrl,
        description
    };
};
