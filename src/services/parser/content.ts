import { load } from 'cheerio';

export const parseChapterContent = (html: string): string => {
    const $ = load(html);

    // RoyalRoad content container
    const content = $('.chapter-inner');

    // Remove ads, scripts, and navigation buttons
    content.find('div.portlet').remove(); // "Report this" etc.
    content.find('script').remove();
    content.find('.bold.uppercase.text-center').remove(); // "Chapter navigation" sometimes

    // Return inner HTML (preserving paragraphs)
    // Or text() if we want plain text, but we want HTML for EPUB
    return content.html() || 'No content found';
};
